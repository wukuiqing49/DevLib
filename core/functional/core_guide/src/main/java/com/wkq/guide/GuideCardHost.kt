package com.wkq.guide

import android.annotation.SuppressLint
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.core.view.children

/** 根据真实文字宽度排列导航；窗口不足时纵排，极小窗口则整卡滚动。 */
@SuppressLint("ViewConstructor") // 内部组件仅程序创建，不支持 XML 实例化。
internal class GuideCardHost(
    private val content: LinearLayout,
    private val navigation: LinearLayout,
    private val spacer: View,
) : FrameLayout(content.context) {
    private val scroller = ScrollView(context).apply { isVerticalScrollBarEnabled = false }
    private var scrolling = false
    private val contentParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
    private val scrollParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)

    init { addView(content, contentParams) }

    /** 按钮保持自然宽度和完整文字；测量固定区域后确定正文或整卡滚动。 */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val availableWidth = MeasureSpec.getSize(widthMeasureSpec)
        val availableHeight = MeasureSpec.getSize(heightMeasureSpec)
        val innerWidth = (availableWidth - content.paddingLeft - content.paddingRight).coerceAtLeast(1)
        val buttons = navigation.children.filter { it !== spacer && it.visibility != GONE }.toList()
        val gap = (8 * resources.displayMetrics.density).toInt()
        val header = content.getChildAt(0) as LinearLayout
        val heading = header.getChildAt(0)
        val counter = header.getChildAt(1)
        counter.measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        val stackHeader = counter.measuredWidth + gap + (112 * resources.displayMetrics.density).toInt() > innerWidth
        header.orientation = if (stackHeader) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        val headingParams = heading.layoutParams as LinearLayout.LayoutParams
        val headingWidth = if (stackHeader) LayoutParams.MATCH_PARENT else 0
        val headingWeight = if (stackHeader) 0f else 1f
        if (headingParams.width != headingWidth || headingParams.weight != headingWeight) {
            headingParams.width = headingWidth
            headingParams.weight = headingWeight
            heading.layoutParams = headingParams
        }
        var desiredWidth = gap * (buttons.size - 1).coerceAtLeast(0)
        buttons.forEach {
            it.measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            desiredWidth += it.measuredWidth
        }
        val vertical = desiredWidth > innerWidth
        navigation.orientation = if (vertical) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        spacer.visibility = if (vertical) GONE else VISIBLE
        buttons.forEachIndexed { index, button ->
            val params = button.layoutParams as LinearLayout.LayoutParams
            val width = if (vertical) LayoutParams.MATCH_PARENT else LayoutParams.WRAP_CONTENT
            val top = if (vertical && index > 0) gap else 0
            if (params.width != width || params.topMargin != top) {
                params.width = width
                params.topMargin = top
                button.layoutParams = params
            }
        }
        var required = content.paddingTop + content.paddingBottom
        content.children.filter { it !is ScrollView && it.visibility != GONE }.forEach {
            it.measure(MeasureSpec.makeMeasureSpec(innerWidth, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            val margins = it.layoutParams as LinearLayout.LayoutParams
            required += it.measuredHeight + margins.topMargin + margins.bottomMargin
        }
        val needsScroll = required + (64 * resources.displayMetrics.density).toInt() > availableHeight
        if (needsScroll != scrolling) {
            if (needsScroll) {
                removeView(content)
                scroller.addView(content, contentParams)
                addView(scroller, scrollParams)
            } else {
                scroller.removeView(content)
                removeView(scroller)
                addView(content, contentParams)
            }
            scrolling = needsScroll
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
}
