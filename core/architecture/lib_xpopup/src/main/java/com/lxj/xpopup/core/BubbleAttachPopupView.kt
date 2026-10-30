package com.lxj.xpopup.core

import android.content.Context
import android.os.Build
import android.view.LayoutInflater
import android.view.ViewGroup
import com.lxj.xpopup.R
import com.lxj.xpopup.XPopup
import com.lxj.xpopup.animator.PopupAnimator
import com.lxj.xpopup.animator.ScaleAlphaAnimator
import com.lxj.xpopup.enums.PopupAnimation
import com.lxj.xpopup.enums.PopupPosition
import com.lxj.xpopup.util.XPopupUtils.applyPopupSize
import com.lxj.xpopup.util.XPopupUtils.dp2px
import com.lxj.xpopup.util.XPopupUtils.getAppHeight
import com.lxj.xpopup.util.XPopupUtils.getAppWidth
import com.lxj.xpopup.util.XPopupUtils.isLayoutRtl
import com.lxj.xpopup.widget.BubbleLayout
import kotlin.math.max

/**
 * Description: 带气泡背景的Attach弹窗
 */
abstract class BubbleAttachPopupView(context: Context) : BasePopupView(context) {
    protected var defaultOffsetY: Int = 0
    protected var defaultOffsetX: Int = 0
    protected var bubbleContainer: BubbleLayout

    protected open fun addInnerContent() {
        val contentView =
            LayoutInflater.from(getContext()).inflate(implLayoutId, bubbleContainer, false)
        bubbleContainer.addView(contentView)
    }

    override val innerLayoutId: Int
        get() = R.layout._xpopup_bubble_attach_popup_view

    var isShowUp: Boolean = false
    var isShowLeft: Boolean = false

    override fun initPopupContent() {
        super.initPopupContent()
        if (bubbleContainer.getChildCount() == 0) addInnerContent()
        require(!(popupInfo!!.atView == null && popupInfo!!.touchPoint == null)) { "atView() or watchView() must be called for BubbleAttachPopupView before show()！" }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            bubbleContainer.setElevation(dp2px(getContext(), 10f).toFloat())
        }
        bubbleContainer.setShadowRadius(dp2px(getContext(), 0f))
        defaultOffsetY = popupInfo!!.offsetY
        defaultOffsetX = popupInfo!!.offsetX
        applyPopupSize(
            popupContentView as ViewGroup, maxWidth, maxHeight,
            popupWidth, popupHeight, object : Runnable {
                override fun run() {
                    doAttach()
                }
            })
    }

    override fun doMeasure() {
        super.doMeasure()
        applyPopupSize(
            popupContentView as ViewGroup, maxWidth, maxHeight,
            popupWidth, popupHeight, object : Runnable {
                override fun run() {
                    doAttach()
                }
            })
    }

    /**
     * 执行倚靠逻辑
     */
    var attachTranslationX: Float = 0f
    var attachTranslationY: Float = 0f

    // 弹窗显示的位置不能超越Window高度
    var maxY: Float = getAppHeight(getContext()).toFloat()
    var overflow: Int = dp2px(getContext(), 10f)
    var centerY: Float = 0f

    init {
        bubbleContainer = findViewById<BubbleLayout>(R.id.bubbleContainer)
    }

    open fun doAttach() {
        if (popupInfo == null) return
        maxY = (getAppHeight(getContext()) - overflow).toFloat()
        val isRTL = isLayoutRtl(getContext())
        //0. 判断是依附于某个点还是某个View
        if (popupInfo!!.touchPoint != null) {
            if (XPopup.longClickPoint != null) popupInfo!!.touchPoint = XPopup.longClickPoint
            popupInfo!!.touchPoint!!.x -= activityContentLeft.toFloat()
            centerY = popupInfo!!.touchPoint!!.y
            // 依附于指定点,尽量优先放在下方，当不够的时候在显示在上方
            //假设下方放不下，超出window高度
            val isTallerThanWindowHeight =
                (popupInfo!!.touchPoint!!.y + popupContentView.getMeasuredHeight()) > maxY
            if (isTallerThanWindowHeight) {
                isShowUp = popupInfo!!.touchPoint!!.y > getAppHeight(getContext()) / 2f
            } else {
                isShowUp = false
            }
            isShowLeft = popupInfo!!.touchPoint!!.x > getAppWidth(getContext()) / 2f

            //限制最大宽高
            val params = popupContentView.getLayoutParams()
            val maxHeight = (if (this@BubbleAttachPopupView.isShowUpToTarget)
                (popupInfo!!.touchPoint!!.y - statusBarHeight - overflow)
            else
                (getAppHeight(getContext()) - popupInfo!!.touchPoint!!.y - overflow)).toInt()
            val maxWidth =
                (if (isShowLeft) (popupInfo!!.touchPoint!!.x - overflow) else (getAppWidth(getContext()) - popupInfo!!.touchPoint!!.x - overflow)).toInt()
            if (popupContentView.getMeasuredHeight() > maxHeight) {
                params.height = maxHeight
            }
            if (popupContentView.getMeasuredWidth() > maxWidth) {
                params.width = maxWidth
            }
            popupContentView.setLayoutParams(params)

            popupContentView.post(object : Runnable {
                override fun run() {
                    if (popupInfo == null) return
                    if (popupInfo!!.isCenterHorizontal) {
                        attachTranslationX =
                            popupInfo!!.touchPoint!!.x + defaultOffsetX - popupContentView.getMeasuredWidth() / 2f
                    } else {
                        if (isRTL) {
                            attachTranslationX =
                                -(getAppWidth(getContext()) - popupInfo!!.touchPoint!!.x - defaultOffsetX - popupContentView.getMeasuredWidth() / 2f)
                        } else {
                            attachTranslationX =
                                popupInfo!!.touchPoint!!.x + defaultOffsetX - popupContentView.getMeasuredWidth() + bubbleContainer.getShadowRadius()
                        }
                    }

                    if (this@BubbleAttachPopupView.isShowUpToTarget) {
                        // 应显示在point上方
                        // attachTranslationX: 在左边就和atView左边对齐，在右边就和其右边对齐
                        attachTranslationY =
                            popupInfo!!.touchPoint!!.y - popupContentView.getMeasuredHeight() - defaultOffsetY
                    } else {
                        attachTranslationY = popupInfo!!.touchPoint!!.y + defaultOffsetY
                    }
                    //设置气泡相关
                    if (popupInfo!!.isCenterHorizontal) {
                        bubbleContainer.setLookPositionCenter(true)
                    } else {
                        if (this@BubbleAttachPopupView.isShowUpToTarget) {
                            bubbleContainer.setLook(BubbleLayout.Look.BOTTOM)
                        } else {
                            bubbleContainer.setLook(BubbleLayout.Look.TOP)
                        }
                    }
                    bubbleContainer.setLookPosition(
                        max(
                            0,
                            (popupInfo!!.touchPoint!!.x - defaultOffsetX - attachTranslationX - bubbleContainer.mLookWidth / 2).toInt()
                        )
                    )
                    bubbleContainer.invalidate()

                    popupContentView.setTranslationX(attachTranslationX)
                    popupContentView.setTranslationY(attachTranslationY)
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

            // 尽量优先放在下方，当不够的时候在显示在上方
            //假设下方放不下，超出window高度
            val isTallerThanWindowHeight =
                (rect.bottom + popupContentView.getMeasuredHeight()) > maxY
            centerY = (rect.top + rect.bottom) / 2f
            if (isTallerThanWindowHeight) {
                //超出可用大小就显示在上方
                isShowUp = true
            } else {
                isShowUp = false
            }
            isShowLeft = centerX > getAppWidth(getContext()) / 2

            //修正高度，弹窗的高有可能超出window区域
            val params = popupContentView.getLayoutParams()
            val maxHeight = if (this@BubbleAttachPopupView.isShowUpToTarget)
                (rect.top - statusBarHeight - overflow)
            else
                (getAppHeight(getContext()) - rect.bottom - overflow)
            val maxWidth =
                if (isShowLeft) (rect.right - overflow) else (getAppWidth(getContext()) - rect.left - overflow)
            if (popupContentView.getMeasuredHeight() > maxHeight) {
                params.height = maxHeight
            }
            if (popupContentView.getMeasuredWidth() > maxWidth) {
                params.width = maxWidth
            }
            popupContentView.setLayoutParams(params)

            popupContentView.post(object : Runnable {
                override fun run() {
                    if (popupInfo == null) return
                    // attachTranslationX: 在左边就和atView左边对齐，在右边就和其右边对齐
                    if (popupInfo!!.isCenterHorizontal) {
                        attachTranslationX =
                            (rect.left + rect.right) / 2f + defaultOffsetX - popupContentView.getMeasuredWidth() / 2f
                    } else {
                        if (isRTL) {
                            if (isShowLeft) {
                                attachTranslationX =
                                    -(getAppWidth(getContext()) - rect.right - defaultOffsetX - bubbleContainer.getShadowRadius()).toFloat()
                            } else {
                                attachTranslationX =
                                    -(getAppWidth(getContext()) - rect.left + defaultOffsetX + bubbleContainer.getShadowRadius() - popupContentView.getMeasuredWidth()).toFloat()
                            }
                        } else {
                            if (isShowLeft) {
                                attachTranslationX =
                                    (rect.right + defaultOffsetX - popupContentView.getMeasuredWidth() + bubbleContainer.getShadowRadius()).toFloat()
                            } else {
                                attachTranslationX =
                                    (rect.left + defaultOffsetX - bubbleContainer.getShadowRadius()).toFloat()
                            }
                        }
                    }

                    if (this@BubbleAttachPopupView.isShowUpToTarget) {
                        //说明上面的空间比较大，应显示在atView上方
                        attachTranslationY =
                            (rect.top - popupContentView.getMeasuredHeight() - defaultOffsetY).toFloat()
                    } else {
                        attachTranslationY = (rect.bottom + defaultOffsetY).toFloat()
                    }

                    //设置气泡相关
                    if (this@BubbleAttachPopupView.isShowUpToTarget) {
                        bubbleContainer.setLook(BubbleLayout.Look.BOTTOM)
                    } else {
                        bubbleContainer.setLook(BubbleLayout.Look.TOP)
                    }
                    //箭头对着目标View的中心
                    if (popupInfo!!.isCenterHorizontal) {
                        bubbleContainer.setLookPositionCenter(true)
                    } else {
                        if (isRTL) {
                            if (isShowLeft) {
                                bubbleContainer.setLookPosition(
                                    max(
                                        0,
                                        (-attachTranslationX - rect.width() / 2 - defaultOffsetX + bubbleContainer.mLookWidth / 2).toInt()
                                    )
                                )
                            } else {
                                bubbleContainer.setLookPosition(
                                    max(
                                        0,
                                        (rect.width() / 2 - defaultOffsetX + bubbleContainer.mLookWidth / 2)
                                    )
                                )
                            }
                        } else {
                            bubbleContainer.setLookPosition(
                                max(
                                    0,
                                    (rect.right - rect.width() / 2 - attachTranslationX - bubbleContainer.mLookWidth / 2).toInt()
                                )
                            )
                        }
                    }
                    bubbleContainer.invalidate()

                    popupContentView.setTranslationX(attachTranslationX)
                    popupContentView.setTranslationY(attachTranslationY)
                    initAndStartAnimation()
                }
            })
        }
    }

    protected open fun initAndStartAnimation() {
        initAnimator()
        doShowAnimation()
        doAfterShow()
    }

    protected val isShowUpToTarget: Boolean
        get() {
            if (popupInfo.positionByWindowCenter) {
                return centerY > getAppHeight(getContext()) / 2
            }
            return (isShowUp || popupInfo.popupPosition == PopupPosition.Top) &&
                popupInfo.popupPosition != PopupPosition.Bottom
        }

    /**
     * 设置气泡背景颜色
     * @param color
     * @return
     */
    fun setBubbleBgColor(color: Int): BubbleAttachPopupView {
        bubbleContainer.setBubbleColor(color)
        bubbleContainer.invalidate()
        return this
    }

    /**
     * 设置气泡背景圆角
     * @param radius
     * @return
     */
    fun setBubbleRadius(radius: Int): BubbleAttachPopupView {
        bubbleContainer.setBubbleRadius(radius)
        bubbleContainer.invalidate()
        return this
    }

    /**
     * 设置气泡箭头的宽度
     * @param width
     * @return
     */
    fun setArrowWidth(width: Int): BubbleAttachPopupView {
        bubbleContainer.setLookWidth(width)
        bubbleContainer.invalidate()
        return this
    }

    /**
     * 设置气泡箭头的高度
     * @param height
     * @return
     */
    fun setArrowHeight(height: Int): BubbleAttachPopupView {
        bubbleContainer.setLookLength(height)
        bubbleContainer.invalidate()
        return this
    }

    /**
     * 设置气泡阴影大小
     * @param size
     * @return
     */
    fun setBubbleShadowSize(size: Int): BubbleAttachPopupView {
        bubbleContainer.setShadowRadius(size)
        bubbleContainer.invalidate()
        return this
    }

    /**
     * 设置气泡阴影颜色
     * @param color
     * @return
     */
    fun setBubbleShadowColor(color: Int): BubbleAttachPopupView {
        bubbleContainer.setShadowColor(color)
        bubbleContainer.invalidate()
        return this
    }

    /**
     * 设置气泡箭头的圆角，默认是1dp
     * @param radius
     * @return
     */
    fun setArrowRadius(radius: Int): BubbleAttachPopupView {
        bubbleContainer.setArrowRadius(radius)
        bubbleContainer.invalidate()
        return this
    }

    override val popupAnimator: PopupAnimator?
        get() = ScaleAlphaAnimator(
            popupContentView,
            animationDuration,
            PopupAnimation.ScaleAlphaFromCenter
        )
}
