package com.wkq.guide

import android.app.Activity
import android.graphics.Rect
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import androidx.activity.OnBackPressedDispatcher
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf
import java.time.Duration

/** Robolectric 验证实际 View、生命周期及窗口不被修改；不冒充设备或视觉验收。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 35])
class FeatureGuideTest {
    private val controllers = mutableListOf<ActivityController<Activity>>()
    private val owners = mutableListOf<TestOwner>()

    /** 测试生命周期与真实 Activity 解耦，验证 Fragment viewLifecycleOwner 接入契约。 */
    private class TestOwner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }

    /** 夹具包含页面、目标与独立生命周期，避免测试间共享窗口。 */
    private data class Fixture(val activity: Activity, val root: FrameLayout, val target: Button, val owner: TestOwner)

    /** 创建已经布局的真实目标，测试不使用业务模拟状态。 */
    private fun fixture(): Fixture {
        val controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
        controllers += controller
        val activity = controller.get()
        val root = FrameLayout(activity)
        val target = Button(activity)
        root.addView(target, FrameLayout.LayoutParams(160, 64).apply { leftMargin = 80; topMargin = 120 })
        activity.setContentView(root)
        val decor = activity.window.decorView
        decor.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY))
        decor.layout(0, 0, 1080, 1920)
        val owner = TestOwner().also { it.registry.currentState = Lifecycle.State.RESUMED; owners += it }
        return Fixture(activity, root, target, owner)
    }

    /** 使用无动画配置隔离业务状态行为，动画交由专用测试检查。 */
    private fun options(results: MutableList<GuideResult>) = GuideOptions(motion = GuideMotion(enabled = false), onFinished = { results += it })

    /** 单步描述由资源提供文案，目标每次读取，保留 View 可见性变化。 */
    private fun step(f: Fixture, id: String = "one") = GuideStep(id, { f.target }, f.activity.getString(R.string.guide_next), f.activity.getString(R.string.guide_step, 1, 2))

    /** 显式执行覆盖层布局与预绘制，检查实际目标和提示而非只检查结束回调。 */
    private fun layout(f: Fixture, width: Int = 1080, height: Int = 1920) {
        val decor = f.activity.window.decorView
        decor.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        decor.layout(0, 0, width, height)
        decor.viewTreeObserver.dispatchOnPreDraw()
    }

    /** 清理测试生命周期和 Activity，不向后续测试残留监听。 */
    @After fun cleanup() {
        owners.forEach { it.registry.currentState = Lifecycle.State.DESTROYED }
        controllers.forEach { it.pause().stop().destroy() }
    }

    /** 最后一步完成后回调一次，多次关闭不会重复回调。 */
    @Test fun completingAndRepeatedDismissOnlyEmitOnce() {
        val f = fixture(); val results = mutableListOf<GuideResult>()
        val guide = FeatureGuide.show(f.activity.window, f.owner, listOf(step(f)), options(results))
        guide.next(); guide.dismiss(); guide.skip()
        assertEquals(listOf(GuideResult.COMPLETED), results)
    }

    /** 快速切步及上一步仍保持正确终态。 */
    @Test fun sequenceSupportsPreviousAndSkip() {
        val f = fixture(); val results = mutableListOf<GuideResult>()
        val guide = FeatureGuide.show(f.activity.window, f.owner, listOf(step(f), step(f, "two")), options(results))
        guide.next(); guide.previous(); guide.next(); guide.skip()
        assertEquals(listOf(GuideResult.SKIPPED), results)
    }

    /** 销毁页面会立即释放遮罩并恢复背景可访问性。 */
    @Test fun lifecycleDestroyRestoresAccessibility() {
        val f = fixture(); val original = f.root.importantForAccessibility
        val results = mutableListOf<GuideResult>()
        val count = (f.activity.window.decorView as FrameLayout).childCount
        FeatureGuide.show(f.activity.window, f.owner, listOf(step(f)), options(results))
        f.owner.registry.currentState = Lifecycle.State.DESTROYED
        assertEquals(listOf(GuideResult.DESTROYED), results)
        assertEquals(count, (f.activity.window.decorView as FrameLayout).childCount)
        assertEquals(original, f.root.importantForAccessibility)
    }

    /** 同一容器新会话会替换旧会话，旧句柄不能关闭新会话。 */
    @Test fun replacesPreviousSession() {
        val f = fixture(); val first = mutableListOf<GuideResult>(); val second = mutableListOf<GuideResult>()
        val old = FeatureGuide.show(f.activity.window, f.owner, listOf(step(f)), options(first))
        val fresh = FeatureGuide.show(f.activity.window, f.owner, listOf(step(f)), options(second))
        old.dismiss()
        assertEquals(listOf(GuideResult.REPLACED), first)
        assertTrue(second.isEmpty())
        fresh.dismiss()
        assertEquals(listOf(GuideResult.DISMISSED), second)
    }

    /** 退出回调重入创建的新引导优先，不能堆叠两层遮罩。 */
    @Test fun replacementCallbackCanStartAnotherSession() {
        val f = fixture(); val results = mutableListOf<GuideResult>()
        FeatureGuide.show(f.activity.window, f.owner, listOf(step(f)), GuideOptions(motion = GuideMotion(false), onFinished = {
            FeatureGuide.show(f.activity.window, f.owner, listOf(step(f)), options(results))
        }))
        val count = (f.activity.window.decorView as FrameLayout).childCount
        val displaced = mutableListOf<GuideResult>()
        FeatureGuide.show(f.activity.window, f.owner, listOf(step(f)), options(displaced))
        assertEquals(count, (f.activity.window.decorView as FrameLayout).childCount)
        assertEquals(listOf(GuideResult.REPLACED), displaced)
    }

    /** 显式返回分发器只关闭引导，保留宿主后退路径。 */
    @Test fun backClosesGuide() {
        val f = fixture(); val results = mutableListOf<GuideResult>(); var hostBack = false
        val dispatcher = OnBackPressedDispatcher { hostBack = true }
        FeatureGuide.show(f.activity.window, f.owner, listOf(step(f)), options(results), dispatcher)
        dispatcher.onBackPressed()
        assertEquals(listOf(GuideResult.BACK), results)
        assertFalse(hostBack)
        dispatcher.onBackPressed()
        assertTrue(hostBack)
    }

    /** 缺失目标必须报告明确结果，不能永久等待或高亮旧坐标。 */
    @Test fun missingTargetTimesOut() {
        val f = fixture(); val results = mutableListOf<GuideResult>()
        FeatureGuide.show(f.activity.window, f.owner, listOf(step(f).copy(target = { null })), options(results).copy(targetTimeoutMillis = 100))
        shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(120))
        assertEquals(listOf(GuideResult.TARGET_UNAVAILABLE), results)
    }

    /** 库进入和退出前后不修改系统栏属性、padding 或 Window flags。 */
    @Test fun leavesHostWindowAndPaddingUntouched() {
        val f = fixture(); val flags = f.activity.window.attributes.flags
        f.root.setPadding(9, 17, 25, 33)
        val original = Rect(f.root.paddingLeft, f.root.paddingTop, f.root.paddingRight, f.root.paddingBottom)
        val guide = FeatureGuide.show(f.activity.window, f.owner, listOf(step(f)), GuideOptions(motion = GuideMotion(false)))
        guide.dismiss()
        assertEquals(flags, f.activity.window.attributes.flags)
        assertEquals(original, Rect(f.root.paddingLeft, f.root.paddingTop, f.root.paddingRight, f.root.paddingBottom))
    }

    /** 阅读记录与业务完成互不混用，不同版本与含分隔符 ID 不冲突。 */
    @Test fun historySeparatesVersionsAndBusinessSuccess() {
        val history = GuideHistory(fixture().activity, "test")
        history.mark("one:2", 1, GuideRecord.READ)
        assertEquals(setOf(GuideRecord.READ), history.records("one:2", 1))
        assertTrue(history.records("one", 2).isEmpty())
        assertFalse(history.records("one:2", 1).contains(GuideRecord.BUSINESS_COMPLETED))
        history.reset("one:2", 1)
        assertTrue(history.records("one:2", 1).isEmpty())
    }

    /** 有效目标真正展示后才报告步骤，反复预绘制不重复报告。 */
    @Test fun visibleTargetReportsStepExactlyOnce() {
        val f = fixture(); val shown = mutableListOf<String>()
        FeatureGuide.show(f.activity.window, f.owner, listOf(step(f)), GuideOptions(motion = GuideMotion(false), onStep = { id, _ -> shown += id }))
        layout(f); layout(f)
        assertEquals(listOf("one"), shown)
        val message = f.activity.window.decorView.findViewById<android.widget.TextView>(R.id.message)
        assertNotNull(message)
        assertTrue(message.isShown)
        assertTrue(message.width > 0 && message.height > 0)
    }

    /** 目标在展示后消失不得保留旧高亮，也不能一直等待。 */
    @Test fun disappearingTargetEndsWithUnavailable() {
        val f = fixture(); val results = mutableListOf<GuideResult>()
        FeatureGuide.show(f.activity.window, f.owner, listOf(step(f)), options(results).copy(targetTimeoutMillis = 100))
        layout(f)
        f.target.visibility = View.GONE
        layout(f)
        shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(120))
        assertEquals(listOf(GuideResult.TARGET_UNAVAILABLE), results)
    }

    /** 超长介绍真实测量后仍可通过滚动访问完整内容。 */
    @Test fun longTextWrapsWithinCardWidth() {
        val f = fixture()
        val longStep = step(f).copy(message = (f.activity.getString(R.string.guide_target_action) + "\n").repeat(80))
        FeatureGuide.show(f.activity.window, f.owner, listOf(longStep), GuideOptions(motion = GuideMotion(false), style = GuideStyle(maxWidthDp = 220f)))
        layout(f)
        val message = f.activity.window.decorView.findViewById<android.widget.TextView>(R.id.message)
        assertEquals(longStep.message, message.text)
        assertTrue(message.lineCount >= 80)
        assertTrue(message.width <= (220 * f.activity.resources.displayMetrics.density).toInt())
    }

    /** 快速结束正在运行的入场动画不能重复派发结束事件。 */
    @Test fun closingAnimatedSessionReleasesOnce() {
        val f = fixture(); val results = mutableListOf<GuideResult>()
        val guide = FeatureGuide.show(f.activity.window, f.owner, listOf(step(f)), GuideOptions(onFinished = { results += it }))
        layout(f)
        guide.dismiss(); guide.skip()
        shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(1500))
        assertEquals(listOf(GuideResult.DISMISSED), results)
    }

    /** 显式目标点击只调用一次真实监听，不向底层注入手势。 */
    @Test fun explicitTargetTapInvokesRealClickOnce() {
        val f = fixture(); var clicks = 0
        f.target.setOnClickListener { clicks++ }
        FeatureGuide.show(f.activity.window, f.owner, listOf(step(f).copy(targetAction = TargetAction.CLICK_AND_NEXT)), GuideOptions(motion = GuideMotion(false)))
        layout(f)
        val layer = (f.activity.window.decorView as FrameLayout).getChildAt((f.activity.window.decorView as FrameLayout).childCount - 1) as GuideOverlay
        val targetLocation = IntArray(2); val overlayLocation = IntArray(2)
        f.target.getLocationOnScreen(targetLocation); layer.getLocationOnScreen(overlayLocation)
        val x = (targetLocation[0] - overlayLocation[0] + f.target.width / 2).toFloat()
        val y = (targetLocation[1] - overlayLocation[1] + f.target.height / 2).toFloat()
        val down = android.view.MotionEvent.obtain(0, 0, android.view.MotionEvent.ACTION_DOWN, x, y, 0)
        val up = android.view.MotionEvent.obtain(0, 10, android.view.MotionEvent.ACTION_UP, x, y, 0)
        layer.onTouchEvent(down); layer.onTouchEvent(up); layer.onTouchEvent(up)
        down.recycle(); up.recycle()
        assertEquals(1, clicks)
    }

    /** 中间亮度主色自动选择对比度更高的按钮前景，避免白字直接落在浅色上。 */
    @Test fun customPrimaryChoosesReadableForeground() {
        val f = fixture(); val primary = 0xff888888.toInt()
        FeatureGuide.show(f.activity.window, f.owner, listOf(step(f)), GuideOptions(motion = GuideMotion(false),
            style = GuideStyle(colors = GuideColors(primary = primary), filledPrimaryAction = true)))
        layout(f)
        val button = f.activity.window.decorView.findViewById<Button>(R.id.next)
        assertTrue(androidx.core.graphics.ColorUtils.calculateContrast(button.currentTextColor, primary) >= 4.5)
    }

    /** 超大按钮字号触发整卡滚动，避免固定操作区被小窗口直接裁切。 */
    @Test fun largeActionsUseScrollableCardFallback() {
        val f = fixture()
        FeatureGuide.showIn(f.root, f.owner, listOf(step(f)), GuideOptions(motion = GuideMotion(false),
            style = GuideStyle(buttonSp = 100f, maxWidthDp = 150f)))
        // 专用覆盖容器提供确定的窄小视口，不依赖模拟器 DecorView 的窗口尺寸缓存。
        f.root.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(220, View.MeasureSpec.EXACTLY))
        f.root.layout(0, 0, 360, 220)
        f.root.viewTreeObserver.dispatchOnPreDraw()
        val button = f.activity.window.decorView.findViewById<Button>(R.id.skip)
        var parent = button.parent
        var hasScroller = false
        val diagnostic = StringBuilder("button=${button.measuredWidth}x${button.measuredHeight}; ")
        while (parent is View) {
            diagnostic.append("${parent.javaClass.simpleName}=${parent.width}x${parent.height}/${parent.measuredWidth}x${parent.measuredHeight}; ")
            if (parent is android.widget.ScrollView) hasScroller = true
            parent = parent.parent
        }
        assertTrue(diagnostic.toString(), hasScroller)
        assertTrue(button.height > 0)
    }
    /** 普通宽度保持同一导航行，跳过属于标题区，主按钮不占据整行。 */
    @Test fun compactNavigationKeepsPrimaryAndPreviousOnOneRow() {
        val f = fixture()
        val guide = FeatureGuide.show(f.activity.window, f.owner, listOf(step(f), step(f, "two")), GuideOptions(motion = GuideMotion(false)))
        guide.next(); layout(f)
        val previous = f.activity.window.decorView.findViewById<Button>(R.id.previous)
        val next = f.activity.window.decorView.findViewById<Button>(R.id.next)
        val actions = f.activity.window.decorView.findViewById<android.widget.LinearLayout>(R.id.actions)
        assertEquals(previous.top, next.top)
        assertTrue(next.width < actions.width)
    }

    /** RTL 使用原生逻辑顺序，前进位于尾侧，上一步位于起始侧。 */
    @Test fun navigationMirrorsInRtl() {
        val f = fixture()
        f.activity.applicationInfo.flags = f.activity.applicationInfo.flags or android.content.pm.ApplicationInfo.FLAG_SUPPORTS_RTL
        f.activity.window.decorView.layoutDirection = View.LAYOUT_DIRECTION_RTL
        val guide = FeatureGuide.show(f.activity.window, f.owner, listOf(step(f), step(f, "two")), GuideOptions(motion = GuideMotion(false)))
        guide.next(); layout(f)
        val previous = f.activity.window.decorView.findViewById<Button>(R.id.previous)
        val next = f.activity.window.decorView.findViewById<Button>(R.id.next)
        assertTrue("previous=${previous.left}, next=${next.left}, direction=${next.layoutDirection}", previous.left > next.left)
    }

    /** 长译文按实际宽度纵排并保留完整按钮文字，不以省略号或缩字适配。 */
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    @Test fun longNavigationLabelsStackWithoutTruncation() {
        val f = fixture()
        val guide = FeatureGuide.show(f.activity.window, f.owner, listOf(step(f), step(f, "two")), GuideOptions(motion = GuideMotion(false), style = GuideStyle(maxWidthDp = 180f)))
        guide.next(); layout(f)
        val previous = f.activity.window.decorView.findViewById<Button>(R.id.previous)
        val next = f.activity.window.decorView.findViewById<Button>(R.id.next)
        val label = f.activity.getString(R.string.guide_target_action).repeat(3)
        previous.text = label
        next.text = label
        layout(f)
        val actions = f.activity.window.decorView.findViewById<android.widget.LinearLayout>(R.id.actions)
        assertEquals(android.widget.LinearLayout.VERTICAL, actions.orientation)
        assertTrue(next.top >= previous.bottom)
        assertEquals(label, next.text)
        assertTrue("lines=${next.lineCount}, width=${next.width}, measured=${next.measuredWidth}, maxLines=${next.maxLines}, layoutWidth=${next.layout?.width}", next.lineCount > 1)
    }

}
