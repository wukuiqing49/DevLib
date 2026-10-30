package com.lxj.xpopup.core

import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import com.lxj.xpopup.R
import com.lxj.xpopup.animator.PopupAnimator
import com.lxj.xpopup.animator.ScaleAlphaAnimator
import com.lxj.xpopup.enums.DragOrientation
import com.lxj.xpopup.enums.PopupAnimation
import com.lxj.xpopup.util.XPopupUtils.applyPopupSize
import com.lxj.xpopup.util.XPopupUtils.getAppWidth
import com.lxj.xpopup.util.XPopupUtils.isLayoutRtl
import com.lxj.xpopup.widget.PositionPopupContainer
import com.lxj.xpopup.widget.PositionPopupContainer.OnPositionDragListener

/**
 * Description: 用于自由定位的弹窗
 * Create by dance, at 2019/6/14
 */
open class PositionPopupView(context: Context) : BasePopupView(context) {
    var positionPopupContainer: PositionPopupContainer

    init {
        positionPopupContainer = findViewById<PositionPopupContainer>(R.id.positionPopupContainer)
        val contentView =
            LayoutInflater.from(getContext()).inflate(implLayoutId, positionPopupContainer, false)
        positionPopupContainer.addView(contentView)
    }

    override val innerLayoutId: Int
        get() = R.layout._xpopup_position_popup_view

    override fun initPopupContent() {
        super.initPopupContent()
        setClipChildren(false)
        setClipToPadding(false)
        positionPopupContainer.enableDrag = popupInfo!!.enableDrag
        positionPopupContainer.dragOrientation = this.dragOrientation
        applyPopupSize(
            popupContentView as ViewGroup, maxWidth, maxHeight,
            popupWidth, popupHeight, object : Runnable {
                override fun run() {
                    doPosition()
                }
            })
        positionPopupContainer.setOnPositionDragChangeListener(object : OnPositionDragListener {
            override fun onDismiss() {
                dismiss()
            }
        })
    }

    override fun doMeasure() {
        super.doMeasure()
        applyPopupSize(
            popupContentView as ViewGroup, maxWidth, maxHeight,
            popupWidth, popupHeight, object : Runnable {
                override fun run() {
                    doPosition()
                }
            })
    }

    private fun doPosition() {
        if (popupInfo == null) return
        if (popupInfo!!.isCenterHorizontal) {
            val left = if (!isLayoutRtl(getContext()))
                (getAppWidth(getContext()) - positionPopupContainer.getMeasuredWidth()) / 2f
            else
                -(getAppWidth(getContext()) - positionPopupContainer.getMeasuredWidth()) / 2f
            positionPopupContainer.setTranslationX(left)
        } else {
            positionPopupContainer.setTranslationX(popupInfo!!.offsetX.toFloat())
        }
        positionPopupContainer.setTranslationY(popupInfo!!.offsetY.toFloat())
        initAndStartAnimation()
    }

    protected open fun initAndStartAnimation() {
        initAnimator()
        doShowAnimation()
        doAfterShow()
    }

    override val popupAnimator: PopupAnimator?
        get() = ScaleAlphaAnimator(
            popupContentView,
            animationDuration,
            PopupAnimation.ScaleAlphaFromCenter
        )

    protected open val dragOrientation: DragOrientation
        /**
         * 可以拖拽的方向，开启enableDrag时才生效
         * @return
         */
        get() = DragOrientation.DragToUp
}
