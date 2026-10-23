package com.lxj.xpopup.core

import android.content.Context
import com.lxj.xpopup.XPopup
import com.lxj.xpopup.animator.PopupAnimator
import com.lxj.xpopup.animator.ScrollScaleAnimator
import com.lxj.xpopup.enums.PopupAnimation
import com.lxj.xpopup.enums.PopupPosition
import com.lxj.xpopup.util.XPopupUtils.dp2px
import com.lxj.xpopup.util.XPopupUtils.getAppWidth
import com.lxj.xpopup.util.XPopupUtils.isLayoutRtl
import kotlin.math.max

/**
 * Description: 水平方向的依附于某个View或者某个点的弹窗，可以轻松实现微信朋友圈点赞的弹窗效果。
 * 支持通过popupPosition()方法手动指定想要出现在目标的左边还是右边，但是对Top和Bottom则不生效。
 * Create by lxj, at 2019/3/13
 */
open class HorizontalAttachPopupView(context: Context) : AttachPopupView(context) {
    protected override fun initPopupContent() {
        super.initPopupContent()
        defaultOffsetY = popupInfo!!.offsetY
        defaultOffsetX =
            if (popupInfo!!.offsetX == 0) dp2px(getContext(), 2f) else popupInfo!!.offsetX
    }

    var popupTranslationX: Float = 0f
    var popupTranslationY: Float = 0f

    /**
     * 执行附着逻辑
     */
    public override fun doAttach() {
        if (popupInfo == null) return
        val isRTL = isLayoutRtl(getContext())
        val w = popupContentView.getMeasuredWidth()
        val h = popupContentView.getMeasuredHeight()
        //0. 判断是依附于某个点还是某个View
        if (popupInfo!!.touchPoint != null) {
            if (XPopup.longClickPoint != null) popupInfo!!.touchPoint = XPopup.longClickPoint
            // 依附于指定点
            popupInfo!!.touchPoint!!.x -= activityContentLeft.toFloat()
            isShowLeft = popupInfo!!.touchPoint!!.x > getAppWidth(getContext()) / 2f
            //限制最大宽高
            val params = popupContentView.getLayoutParams()
            var maxWidth = 0
            if (isRTL) {
                maxWidth = (if (isShowLeft) (popupInfo!!.touchPoint!!.x - overflow) else (getAppWidth(
                    getContext()
                ) - popupInfo!!.touchPoint!!.x - overflow)).toInt()
            } else {
                maxWidth = (if (isShowLeft) (popupInfo!!.touchPoint!!.x - overflow) else (getAppWidth(
                    getContext()
                ) - popupInfo!!.touchPoint!!.x - overflow)).toInt()
            }
            if (popupContentView.getMeasuredWidth() > maxWidth) {
                params.width = max(maxWidth, popupWidth)
            }
            popupContentView.setLayoutParams(params)
            popupContentView.post(object : Runnable {
                override fun run() {
                    if (isRTL) {
                        popupTranslationX = if (isShowLeft)
                            -(getAppWidth(getContext()) - popupInfo!!.touchPoint!!.x + defaultOffsetX)
                        else
                            -(getAppWidth(getContext()) - popupInfo!!.touchPoint!!.x - popupContentView.getMeasuredWidth() - defaultOffsetX)
                    } else {
                        popupTranslationX =
                            if (this@HorizontalAttachPopupView.isShowLeftToTarget) (popupInfo!!.touchPoint!!.x - w - defaultOffsetX) else (popupInfo!!.touchPoint!!.x + defaultOffsetX)
                    }
                    popupTranslationY = popupInfo!!.touchPoint!!.y - h * .5f + defaultOffsetY
                    popupContentView.setTranslationX(popupTranslationX)
                    popupContentView.setTranslationY(popupTranslationY)
                    initAndStartAnimation()
                }
            })
        } else {
            // 依附于指定View
            //1. 获取atView在屏幕上的位置
            val rect = popupInfo!!.atViewRect
            rect.left -= activityContentLeft
            rect.right -= activityContentLeft
            val centerX = (rect.left + rect.right) / 2
            isShowLeft = centerX > getAppWidth(getContext()) / 2
            //限制最大宽高
            val params = popupContentView.getLayoutParams()
            var maxWidth = 0
            if (isRTL) {
                maxWidth =
                    if (isShowLeft) (rect.left - overflow) else (getAppWidth(getContext()) - rect.right - overflow)
            } else {
                maxWidth =
                    if (isShowLeft) (rect.left - overflow) else (getAppWidth(getContext()) - rect.right - overflow)
            }
            if (popupContentView.getMeasuredWidth() > maxWidth) {
                params.width = max(maxWidth, popupWidth)
            }
            popupContentView.setLayoutParams(params)
            popupContentView.post(object : Runnable {
                override fun run() {
                    if (isRTL) {
                        popupTranslationX = (if (isShowLeft)
                            -(getAppWidth(getContext()) - rect.left + defaultOffsetX)
                        else
                            -(getAppWidth(getContext()) - rect.right - popupContentView.getMeasuredWidth() - defaultOffsetX)).toFloat()
                    } else {
                        popupTranslationX =
                            (if (this@HorizontalAttachPopupView.isShowLeftToTarget) (rect.left - w - defaultOffsetX) else (rect.right + defaultOffsetX)).toFloat()
                    }
                    popupTranslationY = rect.top + (rect.height() - h) / 2f + defaultOffsetY
                    popupContentView.setTranslationX(popupTranslationX)
                    popupContentView.setTranslationY(popupTranslationY)
                    initAndStartAnimation()
                }
            })
        }
    }

    private val isShowLeftToTarget: Boolean
        get() = (isShowLeft || popupInfo!!.popupPosition == PopupPosition.Left)
                && popupInfo!!.popupPosition != PopupPosition.Right

    protected override val popupAnimator: PopupAnimator?
        get() {
        val animator: ScrollScaleAnimator?
        if (this@HorizontalAttachPopupView.isShowLeftToTarget) {
            animator = ScrollScaleAnimator(
                popupContentView,
                animationDuration,
                PopupAnimation.ScrollAlphaFromRight
            )
        } else {
            animator = ScrollScaleAnimator(
                popupContentView,
                animationDuration,
                PopupAnimation.ScrollAlphaFromLeft
            )
        }
        return animator
        }
}
