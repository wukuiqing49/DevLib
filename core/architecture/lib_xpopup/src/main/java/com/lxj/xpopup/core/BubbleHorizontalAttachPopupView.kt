package com.lxj.xpopup.core

import android.content.Context
import com.lxj.xpopup.XPopup
import com.lxj.xpopup.enums.PopupPosition
import com.lxj.xpopup.util.XPopupUtils.dp2px
import com.lxj.xpopup.util.XPopupUtils.getAppWidth
import com.lxj.xpopup.util.XPopupUtils.isLayoutRtl
import kotlin.math.max
import com.lxj.xpopup.widget.BubbleLayout

/**
 * Description: 水平方向带气泡的Attach弹窗
 */
open class BubbleHorizontalAttachPopupView(context: Context) : BubbleAttachPopupView(context) {
    protected override fun initPopupContent() {
        bubbleContainer.setLook(BubbleLayout.Look.LEFT) //解决高度不正确的问题
        super.initPopupContent()
        defaultOffsetY = popupInfo!!.offsetY
        defaultOffsetX =
            if (popupInfo!!.offsetX == 0) dp2px(getContext(), 2f) else popupInfo!!.offsetX
    }

    /**
     * 执行附着逻辑
     */
    var popupTranslationX: Float = 0f
    var popupTranslationY: Float = 0f
    public override fun doAttach() {
        val isRTL = isLayoutRtl(getContext())
        //0. 判断是依附于某个点还是某个View
        if (popupInfo!!.touchPoint != null) {
            if (XPopup.longClickPoint != null) popupInfo!!.touchPoint = XPopup.longClickPoint
            popupInfo!!.touchPoint!!.x -= activityContentLeft.toFloat()
            isShowLeft = popupInfo!!.touchPoint!!.x > getAppWidth(getContext()) / 2f
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
                    if (popupInfo == null) return
                    // popupTranslationX: 在左边就和点左边对齐，在右边就和其右边对齐
                    if (isRTL) {
                        popupTranslationX = if (isShowLeft)
                            -(getAppWidth(getContext()) - popupInfo!!.touchPoint!!.x + defaultOffsetX)
                        else
                            -(getAppWidth(getContext()) - popupInfo!!.touchPoint!!.x - popupContentView.getMeasuredWidth() - defaultOffsetX)
                    } else {
                        popupTranslationX =
                            if (this@BubbleHorizontalAttachPopupView.isShowLeftToTarget) (popupInfo!!.touchPoint!!.x - popupContentView.getMeasuredWidth() - defaultOffsetX) else (popupInfo!!.touchPoint!!.x + defaultOffsetX)
                    }
                    popupTranslationY =
                        popupInfo!!.touchPoint!!.y - popupContentView.getMeasuredHeight() * .5f + defaultOffsetY
                    doBubble()
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
                            (if (this@BubbleHorizontalAttachPopupView.isShowLeftToTarget) (rect.left - popupContentView.getMeasuredWidth() - defaultOffsetX) else (rect.right + defaultOffsetX)).toFloat()
                    }
                    popupTranslationY =
                        rect.top + (rect.height() - popupContentView.getMeasuredHeight()) / 2f + defaultOffsetY
                    doBubble()
                }
            })
        }
    }

    private fun doBubble() {
        //设置气泡相关
        if (this@BubbleHorizontalAttachPopupView.isShowLeftToTarget) {
            bubbleContainer.setLook(BubbleLayout.Look.RIGHT)
        } else {
            bubbleContainer.setLook(BubbleLayout.Look.LEFT)
        }
        if (defaultOffsetY == 0) {
            bubbleContainer.setLookPositionCenter(true)
        } else {
            bubbleContainer.setLookPosition(
                max(
                    0,
                    (bubbleContainer.getMeasuredHeight() / 2f - defaultOffsetY - bubbleContainer.mLookLength / 2).toInt()
                )
            )
        }
        bubbleContainer.invalidate()

        popupContentView.setTranslationX(popupTranslationX)
        popupContentView.setTranslationY(popupTranslationY)
        initAndStartAnimation()
    }

    private val isShowLeftToTarget: Boolean
        get() = (isShowLeft || popupInfo!!.popupPosition == PopupPosition.Left)
                && popupInfo!!.popupPosition != PopupPosition.Right
}
