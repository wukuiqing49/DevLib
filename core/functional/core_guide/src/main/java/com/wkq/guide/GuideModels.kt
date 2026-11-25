package com.wkq.guide

import android.view.View
import androidx.annotation.ColorInt

/** 高亮外形；圆形使用目标外接圆，圆角矩形保留真实目标比例。 */
enum class HighlightShape { CIRCLE, ROUNDED_RECTANGLE }

/** 点击高亮区时的明确行为；调用真实目标仅执行一次 performClick，不伪造触摸序列。 */
enum class TargetAction { NONE, NEXT, CLICK_AND_NEXT }

/** 目标强调效果；默认不持续循环，避免影响阅读。 */
enum class GuideEmphasis { NONE, PULSE, RIPPLE }

/** 关闭结果区分阅读完成、跳过、返回、手动结束、替换及生命周期结束。 */
enum class GuideResult { COMPLETED, SKIPPED, BACK, DISMISSED, REPLACED, DESTROYED, TARGET_UNAVAILABLE }

/** 语义颜色成组提供；空值从宿主主题解析，再回退到库的明暗资源。 */
data class GuideColors(
    @param:ColorInt val scrim: Int? = null,
    @param:ColorInt val surface: Int? = null,
    @param:ColorInt val title: Int? = null,
    @param:ColorInt val body: Int? = null,
    @param:ColorInt val primary: Int? = null,
    @param:ColorInt val onPrimary: Int? = null,
    @param:ColorInt val secondary: Int? = null,
    @param:ColorInt val highlight: Int? = null,
)

/** 提示面板可跟随宿主，或使用与页面相反的明暗层次以突出短说明。 */
enum class GuideAppearance { HOST, CONTRAST }

/** 字号单位为 sp，距离单位为 dp；正文完整换行，溢出交由内部滚动区域承接。 */
data class GuideStyle(
    val colors: GuideColors = GuideColors(),
    val maxWidthDp: Float = 280f,
    val marginDp: Float = 16f,
    val cornerDp: Float = 14f,
    val titleSp: Float = 16f,
    val bodySp: Float = 14f,
    val buttonSp: Float = 14f,
    val lineSpacingDp: Float = 3f,
    val showArrow: Boolean = false,
    val appearance: GuideAppearance = GuideAppearance.CONTRAST,
    val filledPrimaryAction: Boolean = false,
) {
    init {
        require(maxWidthDp.isFinite() && maxWidthDp > 0)
        require(listOf(marginDp, cornerDp, lineSpacingDp).all { it.isFinite() && it >= 0 })
        require(listOf(titleSp, bodySp, buttonSp).all { it.isFinite() && it > 0 })
    }
}

/** 动画可整体关闭，系统禁用动画时自动直接呈现最终状态；强调最多播放指定次数。 */
data class GuideMotion(
    val enabled: Boolean = true,
    val enterMillis: Long = 180,
    val transitionMillis: Long = 200,
    val exitMillis: Long = 120,
    val emphasis: GuideEmphasis = GuideEmphasis.PULSE,
    val emphasisCount: Int = 1,
) {
    init {
        require(enterMillis in 0..2000 && transitionMillis in 0..2000 && exitMillis in 0..2000)
        require(emphasisCount in 0..10)
    }
}

/** 自定义提示布局的操作入口；布局内容、无障碍与滚动由调用方负责。 */
interface GuideActions {
    /** 前往下一步，末步报告阅读完成。 */
    fun next()
    /** 返回前一步，首步保持不变。 */
    fun previous()
    /** 用户主动跳过整个引导。 */
    fun skip()
}

/** 单步描述；target 每帧按需获取，必须属于同一窗口；customContent 每次进入该步重新创建。 */
data class GuideStep(
    val id: String,
    val target: () -> View?,
    val title: CharSequence,
    val message: CharSequence,
    val shape: HighlightShape = HighlightShape.ROUNDED_RECTANGLE,
    val paddingDp: Float = 4f,
    val radiusDp: Float = 10f,
    val targetAction: TargetAction = TargetAction.NONE,
    val style: GuideStyle? = null,
    val customContent: ((GuideActions) -> View)? = null,
) {
    init {
        require(id.isNotBlank())
        require(paddingDp.isFinite() && paddingDp >= 0 && radiusDp.isFinite() && radiusDp >= 0)
    }
}

/** 展示选项；等待不可见目标时不绘制错误高亮，到期明确返回 TARGET_UNAVAILABLE。 */
data class GuideOptions(
    val style: GuideStyle = GuideStyle(),
    val motion: GuideMotion = GuideMotion(),
    val targetTimeoutMillis: Long = 3000,
    val dismissOnOutside: Boolean = false,
    val onStep: (String, Int) -> Unit = { _, _ -> },
    val onFinished: (GuideResult) -> Unit = {},
) {
    init { require(targetTimeoutMillis in 0..60000) }
}
