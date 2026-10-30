package com.wkq.google.rate

import android.app.Activity
import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.google.android.play.core.review.ReviewManager
import com.google.android.play.core.review.ReviewManagerFactory
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/** 评分提示门面；不依赖 GoogleKit、登录、Billing、宿主 UI 或报告业务。 */
object GoogleRateManager {
    private val inFlight = AtomicBoolean(false)

    /** 尽早初始化首次使用时间；只初始化评分策略，不连接任何 Google 服务。 */
    suspend fun initialize(context: Context) { snapshot(context) }

    /** 记录由宿主确认完成的关键操作，最近 128 个操作 ID 去重。 */
    suspend fun recordCompletedAction(context: Context, actionId: String): Boolean =
        RateAppController.record(context.applicationContext, actionId, System.currentTimeMillis())

    /** 查询节流状态，既不发起 Play 请求，也不改变请求次数。 */
    suspend fun snapshot(context: Context): GoogleRateState =
        RateAppController.snapshot(context.applicationContext, System.currentTimeMillis())

    /** 只判断自动请求资格，不保证 Play 实际展示评分卡片。 */
    suspend fun evaluate(context: Context, policy: GoogleRatePolicy = GoogleRatePolicy()): GoogleRateDecision =
        policy.evaluate(snapshot(context), System.currentTimeMillis())

    /** 设置不再自动提示偏好，不冒充已评分状态。 */
    suspend fun setSuppressed(context: Context, suppressed: Boolean) =
        RateAppController.suppress(context.applicationContext, suppressed)

    /** 在自然业务完成节点请求评分；不可用于先选星再分流或承诺一定显示的按钮。 */
    suspend fun requestIfEligible(activity: Activity, policy: GoogleRatePolicy = GoogleRatePolicy()): GoogleReviewResult =
        request(activity, policy) { ReviewManagerFactory.create(activity.applicationContext) }

    /** 拒绝不可见或已结束的页面，异步取回评分信息后也必须重新核对。 */
    private fun isActiveHost(activity: Activity): Boolean = !activity.isFinishing && !activity.isDestroyed &&
        ((activity as? LifecycleOwner)?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) ?: activity.hasWindowFocus())

    /** 注入官方 ReviewManager 以验证流程与异常；并发请求在读写偏好前被挡住。 */
    internal suspend fun request(activity: Activity, policy: GoogleRatePolicy, factory: () -> ReviewManager): GoogleReviewResult =
        withContext(Dispatchers.Main.immediate) {
            if (!isActiveHost(activity)) return@withContext GoogleReviewResult(GoogleReviewStatus.HOST_INACTIVE)
            if (!inFlight.compareAndSet(false, true)) return@withContext GoogleReviewResult(GoogleReviewStatus.IN_PROGRESS)
            var releaseOnExit = true
            try {
                val decision = RateAppController.reserve(activity.applicationContext, policy, System.currentTimeMillis())
                if (decision != GoogleRateDecision.ELIGIBLE) return@withContext GoogleReviewResult(GoogleReviewStatus.NOT_ELIGIBLE, decision)
                currentCoroutineContext().ensureActive()
                val manager = factory()
                val info = manager.requestReviewFlow().await()
                currentCoroutineContext().ensureActive()
                if (!isActiveHost(activity)) return@withContext GoogleReviewResult(GoogleReviewStatus.HOST_INACTIVE)
                val flow = manager.launchReviewFlow(activity, info)
                // 协程取消不等于 Play 窗口结束；由实际 Task 完成释放防重入，不持有页面引用。
                flow.addOnCompleteListener { inFlight.set(false) }
                releaseOnExit = false
                flow.await()
                GoogleReviewResult(GoogleReviewStatus.FLOW_FINISHED)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { GoogleReviewResult(GoogleReviewStatus.UNAVAILABLE) }
            finally { if (releaseOnExit) inFlight.set(false) }
        }
}
