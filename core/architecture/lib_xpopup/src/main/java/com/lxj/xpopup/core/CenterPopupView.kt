package com.lxj.xpopup.core

import android.content.Context
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.lxj.xpopup.R
import com.lxj.xpopup.animator.PopupAnimator
import com.lxj.xpopup.animator.ScaleAlphaAnimator
import com.lxj.xpopup.enums.PopupAnimation
import com.lxj.xpopup.util.XPopupUtils.applyPopupSize
import com.lxj.xpopup.util.XPopupUtils.createDrawable
import com.lxj.xpopup.util.XPopupUtils.getAppWidth

/**
 * Description: 在中间显示的Popup
 * Create by dance, at 2018/12/8
 */
open class CenterPopupView(context: Context) : BasePopupView(context) {
    protected var centerPopupContainer: FrameLayout
    protected var bindLayoutId: Int = 0
    protected var bindItemLayoutId: Int = 0
    protected var contentView: View? = null

    init {
        centerPopupContainer = findViewById<FrameLayout>(R.id.centerPopupContainer)
    }

    protected open fun addInnerContent() {
        contentView =
            LayoutInflater.from(getContext()).inflate(implLayoutId, centerPopupContainer, false)
        val params = contentView!!.getLayoutParams() as LayoutParams
        params.gravity = Gravity.CENTER
        centerPopupContainer.addView(contentView, params)
    }

    override val innerLayoutId: Int
        get() = R.layout._xpopup_center_popup_view

    override fun initPopupContent() {
        super.initPopupContent()
        if (centerPopupContainer.getChildCount() == 0) addInnerContent()
        popupContentView.setTranslationX(popupInfo!!.offsetX.toFloat())
        popupContentView.setTranslationY(popupInfo!!.offsetY.toFloat())
        applyPopupSize(
            popupContentView as ViewGroup, maxWidth, maxHeight,
            popupWidth, popupHeight, null
        )
    }

    override fun doMeasure() {
        super.doMeasure()
        applyPopupSize(
            popupContentView as ViewGroup, maxWidth, maxHeight,
            popupWidth, popupHeight, null
        )
    }

    protected open fun applyTheme() {
        if (bindLayoutId == 0) {
            if (popupInfo!!.isDarkTheme) {
                applyDarkTheme()
            } else {
                applyLightTheme()
            }
        }
    }

    override fun applyDarkTheme() {
        super.applyDarkTheme()
        centerPopupContainer.setBackground(
            createDrawable(
                getResources().getColor(R.color._xpopup_dark_color),
                popupInfo!!.borderRadius
            )
        )
    }

    override fun applyLightTheme() {
        super.applyLightTheme()
        centerPopupContainer.setBackground(
            createDrawable(
                getResources().getColor(R.color._xpopup_light_color),
                popupInfo!!.borderRadius
            )
        )
    }

    override val implLayoutId: Int
        /**
         * 具体实现的类的布局
         *
         * @return
         */
        get() = 0

    override val maxWidth: Int
        get() {
            if (popupInfo == null) return 0
            return if (popupInfo!!.maxWidth == 0) (getAppWidth(getContext()) * 0.85f).toInt() else
                popupInfo!!.maxWidth
        }

    override val popupAnimator: PopupAnimator?
        get() = ScaleAlphaAnimator(
            popupContentView,
            animationDuration,
            PopupAnimation.ScaleAlphaFromCenter
        )
}
