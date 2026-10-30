package com.lxj.xpopup.core

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.lxj.xpopup.R
import com.lxj.xpopup.animator.PopupAnimator
import com.lxj.xpopup.animator.TranslateAnimator
import com.lxj.xpopup.enums.PopupAnimation
import com.lxj.xpopup.enums.PopupStatus
import com.lxj.xpopup.util.KeyboardUtils
import com.lxj.xpopup.util.XPopupUtils.applyPopupSize
import com.lxj.xpopup.widget.SmartDragLayout

/**
 * Description: 在底部显示的Popup
 * Create by lxj, at 2018/12/11
 */
open class BottomPopupView(context: Context) : BasePopupView(context) {
    protected var bottomPopupContainer: SmartDragLayout
    protected open fun addInnerContent() {
        val contentView =
            LayoutInflater.from(getContext()).inflate(implLayoutId, bottomPopupContainer, false)
        bottomPopupContainer.addView(contentView)
    }

    override val innerLayoutId: Int
        get() = R.layout._xpopup_bottom_popup_view

    override fun initPopupContent() {
        super.initPopupContent()
        if (bottomPopupContainer.getChildCount() == 0) {
            addInnerContent()
        }
        bottomPopupContainer.setDuration(animationDuration)
        bottomPopupContainer.enableDrag(popupInfo!!.enableDrag)
        if (popupInfo!!.enableDrag) {
            popupInfo!!.popupAnimation = null
            popupImplView.setTranslationX(popupInfo!!.offsetX.toFloat())
            popupImplView.setTranslationY(popupInfo!!.offsetY.toFloat())
        } else {
            popupContentView.setTranslationX(popupInfo!!.offsetX.toFloat())
            popupContentView.setTranslationY(popupInfo!!.offsetY.toFloat())
        }
        bottomPopupContainer.dismissOnTouchOutside(popupInfo!!.isDismissOnTouchOutside)
        bottomPopupContainer.isThreeDrag(popupInfo!!.isThreeDrag)

        applyPopupSize(
            popupContentView as ViewGroup, maxWidth, maxHeight,
            popupWidth, popupHeight, null
        )

        bottomPopupContainer.setOnCloseListener(object : SmartDragLayout.OnCloseListener {
            override fun onClose() {
                beforeDismiss()
                if (popupInfo != null && popupInfo!!.xPopupCallback != null) popupInfo!!.xPopupCallback?.beforeDismiss(
                    this@BottomPopupView
                )
                doAfterDismiss()
            }

            override fun onDrag(value: Int, percent: Float, isScrollUp: Boolean) {
                if (popupInfo == null) return
                if (popupInfo!!.xPopupCallback != null) popupInfo!!.xPopupCallback?.onDrag(
                    this@BottomPopupView,
                    value,
                    percent,
                    isScrollUp
                )
                if (popupInfo!!.hasShadowBg && !popupInfo!!.hasBlurBg) setBackgroundColor(
                    shadowBgAnimator!!.calculateBgColor(percent)
                )
            }

            override fun onOpen() {}
        })

        bottomPopupContainer.setOnClickListener(object : OnClickListener {
            override fun onClick(v: View?) {
                if (popupInfo != null) {
                    if (popupInfo!!.xPopupCallback != null) {
                        popupInfo!!.xPopupCallback?.onClickOutside(this@BottomPopupView)
                    }
                    if (popupInfo!!.isDismissOnTouchOutside != null) {
                        dismiss()
                    }
                }
            }
        })
    }

    override fun doMeasure() {
        super.doMeasure()
        applyPopupSize(
            popupContentView as ViewGroup, maxWidth, maxHeight,
            popupWidth, popupHeight, null
        )
    }

    public override fun doShowAnimation() {
        if (popupInfo == null) return
        if (popupInfo!!.enableDrag) {
            if (popupInfo!!.hasBlurBg && blurAnimator != null) {
                blurAnimator!!.animateShow()
            }
            bottomPopupContainer.open()
        } else {
            super.doShowAnimation()
        }
    }

    public override fun doDismissAnimation() {
        if (popupInfo == null) return
        if (popupInfo!!.enableDrag) {
            if (popupInfo!!.hasBlurBg && blurAnimator != null) {
                blurAnimator!!.animateDismiss()
            }
            bottomPopupContainer.close()
        } else {
            super.doDismissAnimation()
        }
    }

    override fun doAfterDismiss() {
        if (popupInfo == null) return
        if (popupInfo!!.enableDrag) {
            if (popupInfo!!.autoOpenSoftInput) KeyboardUtils.hideSoftInput(this)
            popupHandler.removeCallbacks(doAfterDismissTask)
            popupHandler.postDelayed(doAfterDismissTask, 0)
        } else {
            super.doAfterDismiss()
        }
    }

    private var translateAnimator: TranslateAnimator? = null

    init {
        bottomPopupContainer = findViewById<SmartDragLayout>(R.id.bottomPopupContainer)
    }

    override val popupAnimator: PopupAnimator?
        get() {
            if (popupInfo == null) return null
            if (translateAnimator == null) translateAnimator = TranslateAnimator(
                popupContentView, animationDuration,
                PopupAnimation.TranslateFromBottom
            )
            return if (popupInfo!!.enableDrag) null else translateAnimator
        }

    public override fun dismiss() {
        if (popupInfo == null) return
        if (popupInfo!!.enableDrag) {
            if (popupStatus == PopupStatus.Dismissing) return
            popupStatus = PopupStatus.Dismissing
            if (popupInfo!!.autoOpenSoftInput) KeyboardUtils.hideSoftInput(this)
            clearFocus()
            bottomPopupContainer.close()
        } else {
            super.dismiss()
        }
    }

    override val implLayoutId: Int
        /**
         * 具体实现的类的布局
         *
         * @return
         */
        get() = 0

    protected override fun onDetachedFromWindow() {
        if (popupInfo != null && !popupInfo!!.enableDrag && translateAnimator != null) {
            popupContentView.setTranslationX(translateAnimator!!.startTranslationX)
            popupContentView.setTranslationY(translateAnimator!!.startTranslationY)
            translateAnimator!!.hasInit = true
        }
        super.onDetachedFromWindow()
    }
}
