package com.lxj.xpopup.impl

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
import com.lxj.xpopup.animator.TranslateAnimator
import com.lxj.xpopup.core.BasePopupView
import com.lxj.xpopup.enums.PopupAnimation

/**
 * Description: 宽高撑满的全屏弹窗
 * Create by lxj, at 2019/2/1
 */
open class FullScreenPopupView(context: Context) : BasePopupView(context) {
    var argbEvaluator: ArgbEvaluator = ArgbEvaluator()
    protected var contentView: View? = null
    protected var fullPopupContainer: FrameLayout
    override val innerLayoutId: Int
        get() = R.layout._xpopup_fullscreen_popup_view

    protected open fun addInnerContent() {
        contentView =
            LayoutInflater.from(getContext()).inflate(implLayoutId, fullPopupContainer, false)
        fullPopupContainer.addView(contentView)
    }

    override val popupInsetTarget: View?
        get() = contentView

    override fun initPopupContent() {
        super.initPopupContent()
        if (fullPopupContainer.getChildCount() == 0) addInnerContent()
        popupContentView.setTranslationX(popupInfo.offsetX.toFloat())
        popupContentView.setTranslationY(popupInfo.offsetY.toFloat())
    }

    private val paint = Paint()
    protected var shadowRect: Rect? = null
    var currColor: Int = Color.TRANSPARENT
    private var statusBarAnimator: ValueAnimator? = null
    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (popupInfo != null && popupInfo.hasStatusBarShadow) {
            paint.setColor(currColor)
            shadowRect = Rect(0, 0, getMeasuredWidth(), statusBarHeight)
            canvas.drawRect(shadowRect!!, paint)
        }
    }

    override fun doShowAnimation() {
        super.doShowAnimation()
        doStatusBarColorTransform(true)
    }

    override fun doDismissAnimation() {
        super.doDismissAnimation()
        doStatusBarColorTransform(false)
    }

    private fun doStatusBarColorTransform(isShow: Boolean) {
        if (popupInfo != null && popupInfo.hasStatusBarShadow) {
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

    private var translateAnimator: TranslateAnimator? = null

    init {
        fullPopupContainer = findViewById<FrameLayout>(R.id.fullPopupContainer)
    }

    override val popupAnimator: PopupAnimator
        get() {
        if (translateAnimator == null) {
            translateAnimator = TranslateAnimator(
                popupContentView,
                animationDuration,
                PopupAnimation.TranslateFromBottom
            )
        }
        return translateAnimator!!
        }

    override fun onDetachedFromWindow() {
        statusBarAnimator?.cancel()
        statusBarAnimator = null
        if (popupInfo != null && translateAnimator != null) {
            popupContentView.setTranslationX(translateAnimator!!.startTranslationX)
            popupContentView.setTranslationY(translateAnimator!!.startTranslationY)
            translateAnimator!!.hasInit = true
        }
        super.onDetachedFromWindow()
    }
}
