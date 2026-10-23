package com.lxj.xpopup.core

import android.content.Context
import android.os.Build
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.FrameLayout
import com.lxj.xpopup.R
import com.lxj.xpopup.XPopup
import com.lxj.xpopup.animator.PopupAnimator
import com.lxj.xpopup.animator.ScrollScaleAnimator
import com.lxj.xpopup.enums.PopupAnimation
import com.lxj.xpopup.enums.PopupPosition
import com.lxj.xpopup.util.XPopupUtils
import com.lxj.xpopup.util.XPopupUtils.dp2px
import com.lxj.xpopup.util.XPopupUtils.getAppHeight
import com.lxj.xpopup.util.XPopupUtils.getAppWidth
import com.lxj.xpopup.util.XPopupUtils.isLayoutRtl
import kotlin.math.max

/**
 * Description: 依附于某个View的弹窗，弹窗会出现在目标的上方或下方，如果你想要出现在目标的左边或者右边，请使用HorizontalAttachPopupView。
 * 支持通过popupPosition()方法手动指定想要出现在目标的上边还是下边，但是对Left和Right则不生效。
 * Create by dance, at 2018/12/11
 */
abstract class AttachPopupView(context: Context) : BasePopupView(context) {
    protected var defaultOffsetY: Int = 0
    protected var defaultOffsetX: Int = 0
    protected var attachPopupContainer: FrameLayout

    protected open fun addInnerContent() {
        val contentView = LayoutInflater.from(getContext())
            .inflate(implLayoutId, attachPopupContainer, false)
        attachPopupContainer.addView(contentView)
    }

    override val innerLayoutId: Int
        get() = R.layout._xpopup_attach_popup_view

    var isShowUp: Boolean = false
    var isShowLeft: Boolean = false

    override fun initPopupContent() {
        super.initPopupContent()
        if (attachPopupContainer.getChildCount() == 0) addInnerContent()
        require(!(popupInfo.atView == null && popupInfo.touchPoint == null)) { "atView() or watchView() must be called for AttachPopupView before show()！" }

        defaultOffsetY = popupInfo.offsetY
        defaultOffsetX = popupInfo.offsetX

        attachPopupContainer.setTranslationX(popupInfo.offsetX.toFloat())
        attachPopupContainer.setTranslationY(popupInfo.offsetY.toFloat())
        applyBg()
        XPopupUtils.applyPopupSize(
            (popupContentView as ViewGroup?)!!, maxWidth, maxHeight,
            popupWidth, popupHeight, object : Runnable {
                override fun run() {
                    doAttach()
                }
            })
    }

    override fun doMeasure() {
        super.doMeasure()
        XPopupUtils.applyPopupSize(
            (popupContentView as ViewGroup?)!!, maxWidth, maxHeight,
            popupWidth, popupHeight, object : Runnable {
                override fun run() {
                    doAttach()
                }
            })
    }

    protected open fun applyBg() {
        if (!isCreated) {
            //实现shadow
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                //优先使用implView的背景
                if (popupImplView.getBackground() != null) {
                    //复制一份，为了阴影效果
                    val constantState = popupImplView.getBackground().getConstantState()
                    if (constantState != null) {
                        val newDrawable = constantState.newDrawable(getResources())
                        attachPopupContainer.setBackground(newDrawable)
                        popupImplView.setBackground(null)
                    }
                } else {
                    //不再设置默认背景
//                    attachPopupContainer.setBackground(XPopupUtils.createDrawable(getResources().getColor(popupInfo.isDarkTheme ? R.color._xpopup_dark_color
//                            : R.color._xpopup_light_color), popupInfo.borderRadius));
                }
                attachPopupContainer.setElevation(dp2px(getContext(), 10f).toFloat())
            } else {
                //优先使用implView的背景
                if (popupImplView.getBackground() != null) {
                    val constantState = popupImplView.getBackground().getConstantState()
                    if (constantState != null) {
                        val newDrawable = constantState.newDrawable(getResources())
                        attachPopupContainer.setBackground(newDrawable)
                        popupImplView.setBackground(null)
                    }
                }
            }
        }
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
        attachPopupContainer = findViewById<FrameLayout>(R.id.attachPopupContainer)
    }

    open fun doAttach() {
        if (popupInfo == null) return
        val realNavHeight = navBarHeight
        maxY = (getAppHeight(getContext()) - overflow - realNavHeight).toFloat()
        val isRTL = isLayoutRtl(getContext())
        //0. 判断是依附于某个点还是某个View
        if (popupInfo.touchPoint != null) {
            if (XPopup.longClickPoint != null) popupInfo.touchPoint = XPopup.longClickPoint
            popupInfo.touchPoint!!.x -= activityContentLeft.toFloat()
            centerY = popupInfo.touchPoint!!.y
            // 依附于指定点,尽量优先放在下方，当不够的时候在显示在上方
            //假设下方放不下，超出window高度
            val isTallerThanWindowHeight =
                (popupInfo.touchPoint!!.y + popupContentView.getMeasuredHeight()) > maxY
            if (isTallerThanWindowHeight) {
                isShowUp = popupInfo.touchPoint!!.y > getAppHeight(getContext()) / 2f
            } else {
                isShowUp = false
            }
            isShowLeft = popupInfo.touchPoint!!.x < getAppWidth(getContext()) / 2f

            //限制最大宽高
            val params = popupContentView.getLayoutParams()
            val maxHeight = (if (this@AttachPopupView.isShowUpToTarget)
                (popupInfo.touchPoint!!.y - statusBarHeight - overflow)
            else
                (getAppHeight(getContext()) - popupInfo.touchPoint!!.y - overflow - realNavHeight)).toInt()
            val maxWidth =
                (if (isShowLeft) (getAppWidth(getContext()) - popupInfo.touchPoint!!.x - overflow) else (popupInfo.touchPoint!!.x - overflow)).toInt()
            if (popupContentView.getMeasuredHeight() > maxHeight) {
                params.height = maxHeight
            }
            if (popupContentView.getMeasuredWidth() > maxWidth) {
                params.width = max(maxWidth, popupWidth)
            }
            popupContentView.setLayoutParams(params)

            popupContentView.post(object : Runnable {
                override fun run() {
                    if (popupInfo == null) return
                    if (isRTL) {
                        attachTranslationX = if (isShowLeft)
                            -(getAppWidth(getContext()) - popupInfo.touchPoint!!.x - popupContentView.getMeasuredWidth() - defaultOffsetX)
                        else
                            -(getAppWidth(getContext()) - popupInfo.touchPoint!!.x + defaultOffsetX)
                    } else {
                        attachTranslationX =
                            if (isShowLeft) (popupInfo.touchPoint!!.x + defaultOffsetX) else (popupInfo.touchPoint!!.x - popupContentView.getMeasuredWidth() - defaultOffsetX)
                    }
                    if (popupInfo.isCenterHorizontal) {
                        //水平居中
                        if (isShowLeft) {
                            if (isRTL) {
                                attachTranslationX += popupContentView.getMeasuredWidth() / 2f
                            } else {
                                attachTranslationX -= popupContentView.getMeasuredWidth() / 2f
                            }
                        } else {
                            if (isRTL) {
                                attachTranslationX -= popupContentView.getMeasuredWidth() / 2f
                            } else {
                                attachTranslationX += popupContentView.getMeasuredWidth() / 2f
                            }
                        }
                    }
                    if (this@AttachPopupView.isShowUpToTarget) {
                        // 应显示在point上方
                        // attachTranslationX: 在左边就和atView左边对齐，在右边就和其右边对齐
                        attachTranslationY =
                            popupInfo.touchPoint!!.y - popupContentView.getMeasuredHeight() - defaultOffsetY
                    } else {
                        attachTranslationY = popupInfo.touchPoint!!.y + defaultOffsetY
                    }

                    popupContentView.setTranslationX(attachTranslationX)
                    popupContentView.setTranslationY(attachTranslationY)
                    initAndStartAnimation()
                }
            })
        } else {
            // 依附于指定View
            //1. 获取atView在屏幕上的位置
            val rect = popupInfo.atViewRect
            rect.left -= activityContentLeft
            rect.right -= activityContentLeft

            val centerX = (rect.left + rect.right) / 2

            // 尽量优先放在下方，当不够的时候在显示在上方
            //假设下方放不下，超出window高度
            val isTallerThanWindowHeight =
                (rect.bottom + popupContentView.getMeasuredHeight()) > maxY
            centerY = (rect.top + rect.bottom) / 2f
            if (isTallerThanWindowHeight) {
                //超出下方可用大小，但未超出上方可用区域就显示在上方
                val upAvailableSpace = rect.top - statusBarHeight - overflow
                if (popupContentView.getMeasuredHeight() > upAvailableSpace) {
                    //如果也超出了上方可用区域则哪里空间大显示在哪个方向
                    isShowUp = upAvailableSpace > (maxY - rect.bottom)
                } else {
                    isShowUp = true
                }
                //                isShowUp = centerY > XPopupUtils.getScreenHeight(getContext()) / 2;
            } else {
                isShowUp = false
            }
            isShowLeft = centerX < getAppWidth(getContext()) / 2

            //修正高度，弹窗的高有可能超出window区域
//            if (!isCreated) {
            val params = popupContentView.getLayoutParams()
            val maxHeight = if (this@AttachPopupView.isShowUpToTarget)
                (rect.top - statusBarHeight - overflow)
            else
                (getAppHeight(getContext()) - rect.bottom - overflow - realNavHeight)
            val maxWidth =
                if (isShowLeft) (getAppWidth(getContext()) - rect.left - overflow) else (rect.right - overflow)
            if (popupContentView.getMeasuredHeight() > maxHeight) {
                params.height = maxHeight
            }
            if (popupContentView.getMeasuredWidth() > maxWidth) {
                params.width = max(maxWidth, popupWidth)
            }
            popupContentView.setLayoutParams(params)

            //            }
            popupContentView.post(object : Runnable {
                override fun run() {
                    if (popupInfo == null) return
                    if (isRTL) {
                        attachTranslationX = (if (isShowLeft)
                            -(getAppWidth(getContext()) - rect.left - popupContentView.getMeasuredWidth() - defaultOffsetX)
                        else
                            -(getAppWidth(getContext()) - rect.right + defaultOffsetX)).toFloat()
                    } else {
                        attachTranslationX =
                            (if (isShowLeft) (rect.left + defaultOffsetX) else (rect.right - popupContentView.getMeasuredWidth() - defaultOffsetX)).toFloat()
                    }
                    if (popupInfo.isCenterHorizontal) {
                        //水平居中
                        if (isShowLeft) if (isRTL) {
                            attachTranslationX -= (rect.width() - popupContentView.getMeasuredWidth()) / 2f
                        } else {
                            attachTranslationX += (rect.width() - popupContentView.getMeasuredWidth()) / 2f
                        }
                        else {
                            if (isRTL) {
                                attachTranslationX += (rect.width() - popupContentView.getMeasuredWidth()) / 2f
                            } else {
                                attachTranslationX -= (rect.width() - popupContentView.getMeasuredWidth()) / 2f
                            }
                        }
                    }
                    if (this@AttachPopupView.isShowUpToTarget) {
                        //说明上面的空间比较大，应显示在atView上方
                        // attachTranslationX: 在左边就和atView左边对齐，在右边就和其右边对齐
                        attachTranslationY =
                            (rect.top - popupContentView.getMeasuredHeight() - defaultOffsetY).toFloat()
                    } else {
                        attachTranslationY = (rect.bottom + defaultOffsetY).toFloat()
                    }
                    //
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

    override val popupAnimator: PopupAnimator?
        get() = if (isShowUpToTarget) {
            ScrollScaleAnimator(
                popupContentView,
                animationDuration,
                if (isShowLeft) PopupAnimation.ScrollAlphaFromLeftBottom
                else PopupAnimation.ScrollAlphaFromRightBottom
            )
        } else {
            ScrollScaleAnimator(
                popupContentView,
                animationDuration,
                if (isShowLeft) PopupAnimation.ScrollAlphaFromLeftTop
                else PopupAnimation.ScrollAlphaFromRightTop
            )
        }

}
