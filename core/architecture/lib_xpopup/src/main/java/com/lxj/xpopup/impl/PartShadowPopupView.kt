package com.lxj.xpopup.impl

import android.content.Context
import android.view.Gravity
import android.view.LayoutInflater
import android.view.ViewGroup
import com.lxj.xpopup.R
import com.lxj.xpopup.animator.PopupAnimator
import com.lxj.xpopup.animator.TranslateAnimator
import com.lxj.xpopup.core.BasePopupView
import com.lxj.xpopup.enums.PopupAnimation
import com.lxj.xpopup.enums.PopupPosition
import com.lxj.xpopup.interfaces.OnClickOutsideListener
import com.lxj.xpopup.util.XPopupUtils
import com.lxj.xpopup.widget.PartShadowContainer
import kotlin.math.min

/**
 * Description: 局部阴影的弹窗，类似于淘宝商品列表的下拉筛选弹窗
 * Create by dance, at 2018/12/21
 */
abstract class PartShadowPopupView(context: Context) : BasePopupView(context) {
    protected var attachPopupContainer: PartShadowContainer
    override val innerLayoutId: Int
        get() = R.layout._xpopup_partshadow_popup_view

    protected open fun addInnerContent() {
        val contentView = LayoutInflater.from(getContext())
            .inflate(implLayoutId, attachPopupContainer, false)
        attachPopupContainer.addView(contentView)
    }

    override fun initPopupContent() {
        if (attachPopupContainer.getChildCount() == 0) addInnerContent()
        // 指定阴影动画的目标View
        if (popupInfo.hasShadowBg) {
            shadowBgAnimator!!.targetView = popupContentView
        }

        popupImplView.setTranslationX(popupInfo.offsetX.toFloat())
        popupImplView.setTranslationY(popupInfo.offsetY.toFloat())
        popupImplView.setAlpha(0f)
        XPopupUtils.applyPopupSize(
            popupContentView as ViewGroup, maxWidth, maxHeight,
            popupWidth, popupHeight, object : Runnable {
                override fun run() {
                    doAttach()
                }
            })
    }

    override fun doMeasure() {
        super.doMeasure()
        XPopupUtils.applyPopupSize(
            popupContentView as ViewGroup, maxWidth, maxHeight,
            popupWidth, popupHeight, object : Runnable {
                override fun run() {
                    doAttach()
                }
            })
    }

    private var hasInit = false
    private fun initAndStartAnimation() {
        if (hasInit) return
        hasInit = true
        initAnimator()
        doShowAnimation()
        doAfterShow()
    }

    override fun onDismiss() {
        super.onDismiss()
        hasInit = false
    }

    var isShowUp: Boolean = false

    init {
        attachPopupContainer = findViewById<PartShadowContainer>(R.id.attachPopupContainer)
        attachPopupContainer.popupView = this
    }

    fun doAttach() {
        requireNotNull(popupInfo.atView) { "atView() must be called before show()！" }

        //1. apply width and height
        val params = popupContentView.getLayoutParams() as MarginLayoutParams
        //1. 获取atView在屏幕上的位置
        val rect = popupInfo.atViewRect
        val centerY = rect.top + rect.height() / 2
        val implView = popupImplView
        var implParams = implView.getLayoutParams() as LayoutParams?
        if (implParams == null) implParams = LayoutParams(-2, -2)
        if ((centerY > getMeasuredHeight() / 2 || popupInfo.popupPosition == PopupPosition.Top) && popupInfo.popupPosition != PopupPosition.Bottom) {
            // 说明atView在Window下半部分，PartShadow应该显示在它上方，计算atView之上的高度
            params.height = rect.top
            isShowUp = true
            implParams.gravity = Gravity.BOTTOM
            if (maxHeight > 0) implParams.height =
                min(implView.getMeasuredHeight(), maxHeight)
        } else {
            // atView在上半部分，PartShadow应该显示在它下方，计算atView之下的高度
            params.height = getMeasuredHeight() - rect.bottom
            isShowUp = false
            params.topMargin = rect.bottom
            implParams.gravity = Gravity.TOP
            if (maxHeight > 0) implParams.height =
                min(implView.getMeasuredHeight(), maxHeight)
        }

        popupContentView.setLayoutParams(params)
        implView.setLayoutParams(implParams)
        popupContentView.post(object : Runnable {
            override fun run() {
                initAndStartAnimation()
                popupImplView.setAlpha(1f)
            }
        })
        attachPopupContainer.notDismissArea = popupInfo.notDismissWhenTouchInArea
        attachPopupContainer.setOnClickOutsideListener(object : OnClickOutsideListener {
            override fun onClickOutside() {
                if (popupInfo.isDismissOnTouchOutside) dismiss()
            }
        })
    }

    override val popupAnimator: PopupAnimator
        get() = TranslateAnimator(
            popupImplView,
            animationDuration,
            if (isShowUp) PopupAnimation.TranslateFromBottom else PopupAnimation.TranslateFromTop
        )
}
