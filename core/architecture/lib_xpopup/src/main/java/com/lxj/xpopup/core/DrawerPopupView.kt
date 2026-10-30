package com.lxj.xpopup.core

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.animation.ValueAnimator.AnimatorUpdateListener
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import com.lxj.xpopup.R
import com.lxj.xpopup.animator.PopupAnimator
import com.lxj.xpopup.enums.PopupPosition
import com.lxj.xpopup.enums.PopupStatus
import com.lxj.xpopup.util.KeyboardUtils
import com.lxj.xpopup.widget.PopupDrawerLayout
import kotlin.math.min

/**
 * Description: 带Drawer的弹窗
 * Create by dance, at 2018/12/20
 */
abstract class DrawerPopupView(context: Context) : BasePopupView(context) {
    protected var drawerLayout: PopupDrawerLayout
    protected var drawerContentContainer: FrameLayout
    var mFraction: Float = 0f
    protected open fun addInnerContent() {
        val contentView =
            LayoutInflater.from(getContext()).inflate(implLayoutId, drawerContentContainer, false)
        drawerContentContainer.addView(contentView)
        val params = contentView.getLayoutParams()
        if (popupInfo != null) {
            params.height = LayoutParams.MATCH_PARENT
            if (popupWidth > 0) params.width = popupWidth
            if (maxWidth > 0) params.width = min(params.width, maxWidth)
            contentView.setLayoutParams(params)
        }
    }

    override fun doMeasure() {
        super.doMeasure()
        val contentView = drawerContentContainer.getChildAt(0)
        if (contentView == null) return
        val params = contentView.getLayoutParams()
        if (popupInfo != null) {
            params.height = LayoutParams.MATCH_PARENT
            if (popupWidth > 0) params.width = popupWidth
            if (maxWidth > 0) params.width = min(params.width, maxWidth)
            contentView.setLayoutParams(params)
        }
    }

    override val popupImplView: View
        get() = drawerContentContainer.getChildAt(0)

    override val popupInsetTarget: View?
        get() = drawerContentContainer.getChildAt(0)

    override val innerLayoutId: Int
        get() = R.layout._xpopup_drawer_popup_view

    override fun initPopupContent() {
        super.initPopupContent()
        if (drawerContentContainer.getChildCount() == 0) addInnerContent()
        drawerLayout.isDismissOnTouchOutside = popupInfo!!.isDismissOnTouchOutside
        drawerLayout.setOnCloseListener(object : PopupDrawerLayout.OnCloseListener {
            override fun onClose() {
                beforeDismiss()
                if (popupInfo != null && popupInfo!!.xPopupCallback != null) popupInfo!!.xPopupCallback?.beforeDismiss(
                    this@DrawerPopupView
                )
                doAfterDismiss()
            }

            override fun onOpen() {}
            override fun onDrag(x: Int, fraction: Float, isToLeft: Boolean) {
                if (popupInfo == null) return
                if (popupInfo!!.xPopupCallback != null) popupInfo!!.xPopupCallback?.onDrag(
                    this@DrawerPopupView,
                    x, fraction, isToLeft
                )
                mFraction = fraction
                if (popupInfo!!.hasShadowBg) shadowBgAnimator!!.applyColorValue(fraction)
                postInvalidate()
            }
        })
        popupImplView.setTranslationX(popupInfo!!.offsetX.toFloat())
        popupImplView.setTranslationY(popupInfo!!.offsetY.toFloat())
        drawerLayout.setDrawerPosition(if (popupInfo!!.popupPosition == null) PopupPosition.Left else popupInfo!!.popupPosition)
        drawerLayout.enableDrag = popupInfo!!.enableDrag
        drawerLayout.getChildAt(0).setOnClickListener(object : OnClickListener {
            override fun onClick(v: View?) {
                if (popupInfo != null) {
                    if (popupInfo!!.xPopupCallback != null) {
                        popupInfo!!.xPopupCallback?.onClickOutside(this@DrawerPopupView)
                    }
                    if (popupInfo!!.isDismissOnTouchOutside) {
                        dismiss()
                    }
                }
            }
        })
    }

    var paint: Paint = Paint()
    var shadowRect: Rect? = null
    var argbEvaluator: ArgbEvaluator = ArgbEvaluator()
    var currColor: Int = Color.TRANSPARENT
    var defaultColor: Int = Color.TRANSPARENT
    private var statusBarAnimator: ValueAnimator? = null

    init {
        drawerLayout = findViewById<PopupDrawerLayout>(R.id.drawerLayout)
        drawerContentContainer = findViewById<FrameLayout>(R.id.drawerContentContainer)
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (popupInfo != null && popupInfo!!.hasStatusBarShadow) {
            if (shadowRect == null) {
                shadowRect = Rect(0, 0, getMeasuredWidth(), statusBarHeight)
            }
            paint.setColor(
                (argbEvaluator.evaluate(
                    mFraction,
                    defaultColor,
                    statusBarBgColor
                ) as Int?)!!
            )
            canvas.drawRect(shadowRect!!, paint)
        }
    }

    fun doStatusBarColorTransform(isShow: Boolean) {
        if (popupInfo != null && popupInfo!!.hasStatusBarShadow) {
            //状态栏渐变动画
            statusBarAnimator?.cancel()
            val animator = ValueAnimator.ofObject(
                argbEvaluator,
                if (isShow) Color.TRANSPARENT else statusBarBgColor,
                if (isShow) statusBarBgColor else Color.TRANSPARENT
            )
            animator.addUpdateListener(object : AnimatorUpdateListener {
                override fun onAnimationUpdate(animation: ValueAnimator) {
                    currColor = (animation.getAnimatedValue() as Int?)!!
                    postInvalidate()
                }
            })
            statusBarAnimator = animator
            animator.setDuration(animationDuration.toLong()).start()
        }
    }

    public override fun doShowAnimation() {
        drawerLayout.open()
        doStatusBarColorTransform(true)
    }

    public override fun doDismissAnimation() {
    }

    override fun doAfterDismiss() {
        if (popupInfo != null && popupInfo!!.autoOpenSoftInput) KeyboardUtils.hideSoftInput(this)
        popupHandler.removeCallbacks(doAfterDismissTask)
        popupHandler.postDelayed(doAfterDismissTask, 0)
    }

    public override fun dismiss() {
        if (popupInfo == null) return
        if (popupStatus == PopupStatus.Dismissing) return
        popupStatus = PopupStatus.Dismissing
        if (popupInfo!!.autoOpenSoftInput) KeyboardUtils.hideSoftInput(this)
        clearFocus()
        doStatusBarColorTransform(false)
        // 关闭Drawer，由于Drawer注册了关闭监听，会自动调用dismiss
        drawerLayout.close()
    }

    override val popupAnimator: PopupAnimator?
        get() = null

    override fun onDetachedFromWindow() {
        statusBarAnimator?.cancel()
        statusBarAnimator = null
        super.onDetachedFromWindow()
    }
}
