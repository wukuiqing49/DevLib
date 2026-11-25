package com.wkq.guide

import android.os.Looper
import android.view.ViewGroup
import android.view.Window
import androidx.activity.OnBackPressedDispatcher
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner

/** 独立的 View 操作引导入口；不创建窗口、不改变系统栏、不执行宿主业务完成判定。 */
class FeatureGuide private constructor() : GuideActions {
    private var overlay: GuideOverlay? = null

    /** 切换下一步；末步只表示引导阅读完成。 */
    override fun next() { checkMainThread(); overlay?.next() }

    /** 返回前一步，目标会重新解析。 */
    override fun previous() { checkMainThread(); overlay?.previous() }

    /** 记录主动跳过并关闭当前引导。 */
    override fun skip() { checkMainThread(); overlay?.finish(GuideResult.SKIPPED) }

    /** 主动关闭；可以多次调用，结束事件只派发一次。 */
    fun dismiss() { checkMainThread(); overlay?.finish(GuideResult.DISMISSED) }

    companion object {
        /** 附着 Activity 或 Dialog 的真实 Window；系统栏明暗和全屏模式继续由宿主负责。 */
        @JvmStatic
        fun show(
            window: Window,
            owner: LifecycleOwner,
            steps: List<GuideStep>,
            options: GuideOptions = GuideOptions(),
            backDispatcher: OnBackPressedDispatcher? = null,
        ): FeatureGuide = showIn(window.decorView as ViewGroup, owner, steps, options, backDispatcher)

        /** 附着专用覆盖容器；容器必须使用叠放布局，目标须位于同一 Window，不能跨窗口高亮。 */
        @JvmStatic
        fun showIn(
            container: ViewGroup,
            owner: LifecycleOwner,
            steps: List<GuideStep>,
            options: GuideOptions = GuideOptions(),
            backDispatcher: OnBackPressedDispatcher? = null,
        ): FeatureGuide {
            checkMainThread()
            require(steps.isNotEmpty() && steps.map { it.id }.distinct().size == steps.size)
            require(owner.lifecycle.currentState != Lifecycle.State.DESTROYED)
            val guide = FeatureGuide()
            val previous = container.getTag(R.id.guide_active_session) as? FeatureGuide
            container.setTag(R.id.guide_active_session, guide)
            previous?.overlay?.finish(GuideResult.REPLACED, false)
            // 结束回调中启动的新会话优先，防止重入产生两层遮罩。
            if (container.getTag(R.id.guide_active_session) !== guide) {
                options.onFinished(GuideResult.REPLACED)
                return guide
            }
            val layer = GuideOverlay(container, owner, steps.toList(), options, backDispatcher) { result ->
                guide.overlay = null
                if (container.getTag(R.id.guide_active_session) === guide) container.setTag(R.id.guide_active_session, null)
                options.onFinished(result)
            }
            guide.overlay = layer
            layer.start()
            return guide
        }

        /** 公开会话 API 均限制主线程，避免动画与生命周期并发修改 View。 */
        private fun checkMainThread() { check(Looper.myLooper() == Looper.getMainLooper()) { "FeatureGuide requires the main thread" } }
    }
}
