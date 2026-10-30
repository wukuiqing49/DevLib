package com.wkq.google.rate

import android.app.Activity
import android.content.Context
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.TaskCompletionSource
import com.google.android.gms.tasks.Tasks
import com.google.android.play.core.review.ReviewInfo
import com.google.android.play.core.review.ReviewManager
import com.google.android.play.core.review.testing.FakeReviewManager
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import android.os.Looper

/** 使用官方假评分管理器验证策略存储与请求控制，不显示窗口、不提交真实评分。 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class GoogleRateManagerTest {
    private lateinit var activity: ReviewHostActivity
    private lateinit var controller: org.robolectric.android.controller.ActivityController<ReviewHostActivity>
    private val immediatePolicy = GoogleRatePolicy(0, 30, 0)

    /** 为每个用例建立独立本地偏好和主线程调度器。 */
    @Before fun prepare() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        controller = Robolectric.buildActivity(ReviewHostActivity::class.java).setup()
        activity = controller.get()
        activity.getSharedPreferences("google_review_prompt_v1", Context.MODE_PRIVATE).edit().clear().commit()
    }
    /** 清理测试主线程状态与页面，不影响真实设备或应用数据。 */
    @After fun cleanup() {
        shadowOf(Looper.getMainLooper()).idle()
        activity.finish()
        Dispatchers.resetMain()
    }
    /** 同一关键操作重复回调只计一次；保存后的快照能够重复读取。 */
    @Test fun completionIsDeduplicatedAndPersisted() = runTest {
        assertTrue(GoogleRateManager.recordCompletedAction(activity, "export-42"))
        assertFalse(GoogleRateManager.recordCompletedAction(activity, "export-42"))
        assertEquals(1, GoogleRateManager.snapshot(activity).completedActions)
        val stored = activity.getSharedPreferences("google_review_prompt_v1", Context.MODE_PRIVATE).getString("recent_actions", "").orEmpty()
        assertFalse(stored.contains("export-42"))
    }
    /** 最近事件窗口保持有界，累计次数不因去重窗口裁剪而丢失。 */
    @Test fun recentEventWindowIsBounded() = runTest {
        repeat(130) { GoogleRateManager.recordCompletedAction(activity, "action-$it") }
        val stored = activity.getSharedPreferences("google_review_prompt_v1", Context.MODE_PRIVATE).getString("recent_actions", "").orEmpty()
        assertEquals(128, stored.lines().size)
        assertEquals(130, GoogleRateManager.snapshot(activity).completedActions)
    }
    /** 用户关闭后拒绝请求，恢复偏好不会抹掉计数或时间。 */
    @Test fun suppressionAndResumeArePersisted() = runTest {
        GoogleRateManager.setSuppressed(activity, true)
        assertEquals(GoogleRateDecision.SUPPRESSED, GoogleRateManager.evaluate(activity, immediatePolicy))
        GoogleRateManager.setSuppressed(activity, false)
        assertEquals(GoogleRateDecision.ELIGIBLE, GoogleRateManager.evaluate(activity, immediatePolicy))
    }
    /** 不满足策略时不创建 Google 客户端，也不消耗本地请求次数。 */
    @Test fun ineligibleRequestDoesNotCreateClient() = runTest {
        val result = GoogleRateManager.request(activity, GoogleRatePolicy()) { error("Must not create client") }
        assertEquals(GoogleReviewStatus.NOT_ELIGIBLE, result.status)
        assertEquals(0, GoogleRateManager.snapshot(activity).requestCount)
    }
    /** 官方假流程结束只记录一次请求；后续请求遵守冷却而非标记已评分。 */
    @Test fun fakeFlowFinishesWithoutClaimingRated() = runTest {
        val result = GoogleRateManager.request(activity, immediatePolicy) { FakeReviewManager(activity) }
        assertEquals(GoogleReviewStatus.FLOW_FINISHED, result.status)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, GoogleRateManager.snapshot(activity).requestCount)
        assertFalse(GoogleRateManager.snapshot(activity).suppressed)
        assertEquals(GoogleRateDecision.COOLDOWN, GoogleRateManager.evaluate(activity, immediatePolicy))
    }
    /** SDK 请求失败后保留冷却，不自动跳商店、不重复尝试。 */
    @Test fun failureKeepsCooldown() = runTest {
        val fake = FakeReviewManager(activity)
        val manager = StubReviewManager(fake, requestTask = Tasks.forException(IllegalStateException("unavailable")))
        assertEquals(GoogleReviewStatus.UNAVAILABLE, GoogleRateManager.request(activity, immediatePolicy) { manager }.status)
        assertEquals(1, GoogleRateManager.snapshot(activity).requestCount)
        assertEquals(GoogleRateDecision.COOLDOWN, GoogleRateManager.evaluate(activity, immediatePolicy))
    }
    /** 页面已经结束时不请求服务、不写入请求计数。 */
    @Test fun finishingHostIsRejected() = runTest {
        activity.finish()
        assertEquals(GoogleReviewStatus.HOST_INACTIVE, GoogleRateManager.request(activity, immediatePolicy) { error("inactive") }.status)
        assertEquals(0, GoogleRateManager.snapshot(activity).requestCount)
    }
    /** 在取得 ReviewInfo 前取消，不会在异步返回后打开评分窗口。 */
    @Test fun cancellationBeforeLaunchDoesNotOpenWindow() = runTest {
        val fake = FakeReviewManager(activity)
        val source = TaskCompletionSource<ReviewInfo>()
        val started = CompletableDeferred<Unit>()
        val manager = StubReviewManager(fake, requestTask = source.task, requested = { started.complete(Unit) })
        val job = launch { GoogleRateManager.request(activity, immediatePolicy) { manager } }
        started.await()
        job.cancelAndJoin()
        source.setResult(fake.requestReviewFlow().result)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, manager.launches)
    }
    /** 异步请求期间宿主退到后台时，不继续拉起窗口。 */
    @Test fun stoppedHostDoesNotLaunchAfterRequestCompletes() = runTest {
        val fake = FakeReviewManager(activity)
        val source = TaskCompletionSource<ReviewInfo>()
        val started = CompletableDeferred<Unit>()
        val manager = StubReviewManager(fake, requestTask = source.task, requested = { started.complete(Unit) })
        val result = async { GoogleRateManager.request(activity, immediatePolicy) { manager } }
        started.await()
        controller.pause().stop()
        source.setResult(fake.requestReviewFlow().result)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(GoogleReviewStatus.HOST_INACTIVE, result.await().status)
        assertEquals(0, manager.launches)
    }
    /** 评分窗口未结束时即使宿主协程取消也保持防重入，Task 完成后恢复。 */
    @Test fun cancellationDuringFlowKeepsGateUntilPlayCompletes() = runTest {
        val fake = FakeReviewManager(activity)
        val source = TaskCompletionSource<Void>()
        val launched = CompletableDeferred<Unit>()
        val manager = StubReviewManager(fake, flowTask = source.task, launched = { launched.complete(Unit) })
        val policy = GoogleRatePolicy(0, 0, 0)
        val job = launch { GoogleRateManager.request(activity, policy) { manager } }
        try {
            launched.await()
            job.cancelAndJoin()
            assertEquals(GoogleReviewStatus.IN_PROGRESS, GoogleRateManager.request(activity, policy) { fake }.status)
        } finally {
            source.trySetResult(null)
            shadowOf(Looper.getMainLooper()).idle()
        }
        assertEquals(GoogleReviewStatus.FLOW_FINISHED, GoogleRateManager.request(activity, policy) { fake }.status)
    }
}

/** 在官方假实现外注入失败和挂起 Task，覆盖取消与异步完成边界。 */
private class StubReviewManager(
    private val delegate: ReviewManager,
    private val requestTask: Task<ReviewInfo>? = null,
    private val flowTask: Task<Void>? = null,
    private val requested: () -> Unit = {},
    private val launched: () -> Unit = {},
) : ReviewManager {
    var launches = 0
    /** 回传注入的请求任务，并通知测试已开始，避免基于等待时长猜测。 */
    override fun requestReviewFlow(): Task<ReviewInfo> {
        requested()
        return requestTask ?: delegate.requestReviewFlow()
    }
    /** 统计实际打开调用次数，返回可由测试显式完成的流程任务。 */
    override fun launchReviewFlow(activity: Activity, reviewInfo: ReviewInfo): Task<Void> {
        launches++
        launched()
        return flowTask ?: delegate.launchReviewFlow(activity, reviewInfo)
    }
}

/** 测试用生命周期宿主，模拟真实 Base 页面具备的 LifecycleOwner 契约。 */
class ReviewHostActivity : androidx.activity.ComponentActivity()
