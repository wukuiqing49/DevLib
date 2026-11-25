package com.wkq.dev.demo.files

import android.app.Dialog
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.wkq.dev.demo.common.DemoActivity
import com.wkq.dev.demo.common.DemoViewModel
import com.wkq.dev.demo.files.databinding.DemoGuideBinding
import com.wkq.guide.FeatureGuide
import com.wkq.guide.GuideAppearance
import com.wkq.guide.GuideResult
import com.wkq.dev.demo.files.databinding.DemoGuideDialogBinding
import com.wkq.guide.GuideEmphasis
import com.wkq.guide.GuideMotion
import com.wkq.guide.GuideOptions
import com.wkq.guide.GuideStep
import com.wkq.guide.GuideStyle
import com.wkq.guide.HighlightShape
import com.wkq.router.annotation.Route

/** 通用引导演示复用工作台 Base；由宿主演示全屏切换，库只处理页内布局。 */
@Route(path = "/files/core_guide")
class GuideDemoActivity : DemoActivity<DemoGuideBinding, GuideDemoViewModel>() {
    /** 保留工作台返回、系统栏与输出容器，六个入口均调用真实库 API。 */
    override fun initView() {
        val b = contentBinding
        bindDemo(b.result, getString(R.string.demo_guide_title))
        b.target.setOnClickListener { showGuide() }
        b.start.setOnClickListener { showGuide() }
        b.result.root.visibility = android.view.View.GONE
        b.longText.setOnClickListener { showGuide(longText = true) }
        b.noMotion.setOnClickListener { showGuide(motion = GuideMotion(enabled = false)) }
        b.colors.setOnClickListener {
            showGuide(style = GuideStyle(appearance = GuideAppearance.HOST),
                motion = GuideMotion(emphasis = GuideEmphasis.RIPPLE))
        }
        b.fullscreen.setOnClickListener { showFullscreenGuide() }
        b.dialog.setOnClickListener { showDialogGuide() }
    }

    /** 引导预览按钮与真实返回入口；长文模式单独检查正文滚动，不自动执行导航。 */
    private fun showGuide(longText: Boolean = false, style: GuideStyle = GuideStyle(), motion: GuideMotion = GuideMotion()) {
        val b = contentBinding
        b.target.requestRectangleOnScreen(android.graphics.Rect(0, 0, b.target.width, b.target.height), true)
        val steps = mutableListOf(GuideStep("start", { b.target }, getString(R.string.demo_guide_target_title),
            getString(if (longText) R.string.demo_guide_long_message else R.string.demo_guide_target_message)))
        if (!longText) steps += GuideStep("back", { findViewById(com.wkq.base.R.id.ll_left_container) }, getString(R.string.demo_guide_second_title),
            getString(R.string.demo_guide_second_message), shape = HighlightShape.ROUNDED_RECTANGLE)
        FeatureGuide.show(window, this, steps, demoOptions(style, motion), onBackPressedDispatcher)
    }

    /** 由宿主进入全屏并在引导结束后按进入前快照恢复，库不负责切换系统栏。 */
    private fun showFullscreenGuide() {
        contentBinding.target.requestRectangleOnScreen(android.graphics.Rect(0, 0, contentBinding.target.width, contentBinding.target.height), true)
        val insets = ViewCompat.getRootWindowInsets(window.decorView)
        val statusVisible = insets?.isVisible(WindowInsetsCompat.Type.statusBars()) ?: true
        val navigationVisible = insets?.isVisible(WindowInsetsCompat.Type.navigationBars()) ?: true
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        val behavior = controller.systemBarsBehavior
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
        FeatureGuide.show(window, this, listOf(GuideStep("fullscreen", { contentBinding.target },
            getString(R.string.demo_guide_fullscreen), getString(R.string.demo_guide_target_message))),
            demoOptions(afterFinished = {
                if (!isDestroyed) {
                    controller.systemBarsBehavior = behavior
                    if (statusVisible) controller.show(WindowInsetsCompat.Type.statusBars()) else controller.hide(WindowInsetsCompat.Type.statusBars())
                    if (navigationVisible) controller.show(WindowInsetsCompat.Type.navigationBars()) else controller.hide(WindowInsetsCompat.Type.navigationBars())
                }
            }), onBackPressedDispatcher)
    }

    /** 默认会话反馈保留在演示页，阅读结束与业务成功分别记录。 */
    private fun demoOptions(style: GuideStyle = GuideStyle(), motion: GuideMotion = GuideMotion(), afterFinished: () -> Unit = {}): GuideOptions =
        GuideOptions(style = style, motion = motion,
            onStep = { id, index ->
                android.util.Log.i("GuideDemo", "step=$id index=$index")
                if (!isDestroyed) contentBinding.event.setText(R.string.demo_guide_running)
            },
            onFinished = {
                android.util.Log.i("GuideDemo", "finished=$it")
                if (it != GuideResult.DESTROYED && !isDestroyed) contentBinding.event.setText(R.string.demo_guide_finished)
                afterFinished()
            })

    /** 紧凑 Dialog 使用自身 Window；真实目标是弹窗里的按钮，不借用宿主坐标。 */
    private fun showDialogGuide() {
        val dialog = Dialog(this)
        val b = DemoGuideDialogBinding.inflate(layoutInflater)
        dialog.setContentView(b.root)
        b.close.setOnClickListener { dialog.dismiss() }
        dialog.show()
        val density = resources.displayMetrics.density
        val available = window.decorView.height
        dialog.window?.apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
            setDimAmount(.16f)
            setLayout((window.decorView.width - 32 * density).toInt().coerceAtMost((360 * density).toInt()),
                (400 * density).toInt().coerceAtMost((available * .8f).toInt()))
        }
        var guide: FeatureGuide? = null
        b.target.setOnClickListener {
            guide = FeatureGuide.show(checkNotNull(dialog.window), this,
                listOf(GuideStep("dialog", { b.target }, getString(R.string.demo_guide_dialog_name), getString(R.string.demo_guide_target_message))), demoOptions())
        }
        dialog.setOnDismissListener { guide?.dismiss() }
        b.target.post { if (dialog.isShowing && b.target.isAttachedToWindow) b.target.performClick() }
    }

}

/** 演示状态沿用工作台 ViewModel，引导内容和定位不存入业务 ViewModel。 */
class GuideDemoViewModel : DemoViewModel()
