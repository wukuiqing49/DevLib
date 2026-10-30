package com.lxj.xpopup.animator

import android.animation.IntEvaluator
import android.animation.ValueAnimator
import android.animation.ValueAnimator.AnimatorUpdateListener
import android.view.View
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import com.lxj.xpopup.enums.PopupAnimation

/**
 * Description: 像系统的PopupMenu那样的动画
 * Create by lxj, at 2018/12/12
 */
class ScrollScaleAnimator(target: View?, animationDuration: Int, popupAnimation: PopupAnimation?) :
    PopupAnimator(target, animationDuration, popupAnimation) {
    private val intEvaluator = IntEvaluator()
    private var startScrollX = 0
    private var startScrollY = 0
    private val startAlpha = 0f
    private val startScale = 0f

    public override fun initAnimator() {
        targetView!!.setAlpha(startAlpha)

        targetView!!.post(object : Runnable {
            override fun run() {
                // 设置参考点
                applyPivot()
                targetView!!.scrollTo(startScrollX, startScrollY)
            }
        })
    }

    private fun applyPivot() {
        if (popupAnimation == PopupAnimation.ScrollAlphaFromLeft) {
            targetView!!.setPivotX(0f)
            targetView!!.setPivotY(targetView!!.getMeasuredHeight() / 2f)
            startScrollX = targetView!!.getMeasuredWidth()
            startScrollY = 0
            targetView!!.setScaleX(startScale)
        } else if (popupAnimation == PopupAnimation.ScrollAlphaFromLeftTop) {
            targetView!!.setPivotX(0f)
            targetView!!.setPivotY(0f)
            startScrollX = targetView!!.getMeasuredWidth()
            startScrollY = targetView!!.getMeasuredHeight()
            targetView!!.setScaleX(startScale)
            targetView!!.setScaleY(startScale)
        } else if (popupAnimation == PopupAnimation.ScrollAlphaFromTop) {
            targetView!!.setPivotX(targetView!!.getMeasuredWidth() / 2f)
            targetView!!.setPivotY(0f)
            startScrollY = targetView!!.getMeasuredHeight()
            targetView!!.setScaleY(startScale)
        } else if (popupAnimation == PopupAnimation.ScrollAlphaFromRightTop) {
            targetView!!.setPivotX(targetView!!.getMeasuredWidth().toFloat())
            targetView!!.setPivotY(0f)
            startScrollX = -targetView!!.getMeasuredWidth()
            startScrollY = targetView!!.getMeasuredHeight()
            targetView!!.setScaleX(startScale)
            targetView!!.setScaleY(startScale)
        } else if (popupAnimation == PopupAnimation.ScrollAlphaFromRight) {
            targetView!!.setPivotX(targetView!!.getMeasuredWidth().toFloat())
            targetView!!.setPivotY(targetView!!.getMeasuredHeight() / 2f)
            startScrollX = -targetView!!.getMeasuredWidth()
            targetView!!.setScaleX(startScale)
        } else if (popupAnimation == PopupAnimation.ScrollAlphaFromRightBottom) {
            targetView!!.setPivotX(targetView!!.getMeasuredWidth().toFloat())
            targetView!!.setPivotY(targetView!!.getMeasuredHeight().toFloat())
            startScrollX = -targetView!!.getMeasuredWidth()
            startScrollY = -targetView!!.getMeasuredHeight()
            targetView!!.setScaleX(startScale)
            targetView!!.setScaleY(startScale)
        } else if (popupAnimation == PopupAnimation.ScrollAlphaFromBottom) {
            targetView!!.setPivotX(targetView!!.getMeasuredWidth() / 2f)
            targetView!!.setPivotY(targetView!!.getMeasuredHeight().toFloat())
            startScrollY = -targetView!!.getMeasuredHeight()
            targetView!!.setScaleY(startScale)
        } else if (popupAnimation == PopupAnimation.ScrollAlphaFromLeftBottom) {
            targetView!!.setPivotX(0f)
            targetView!!.setPivotY(targetView!!.getMeasuredHeight().toFloat())
            startScrollX = targetView!!.getMeasuredWidth()
            startScrollY = -targetView!!.getMeasuredHeight()
            targetView!!.setScaleX(startScale)
            targetView!!.setScaleY(startScale)
        }
    }

    public override fun animateShow() {
        targetView!!.post(object : Runnable {
            override fun run() {
                val animator = ValueAnimator.ofFloat(0f, 1f)
                animator.addUpdateListener(object : AnimatorUpdateListener {
                    override fun onAnimationUpdate(animation: ValueAnimator) {
                        val fraction = animation.getAnimatedFraction()
                        targetView!!.setAlpha(fraction)
                        targetView!!.scrollTo(
                            intEvaluator.evaluate(fraction, startScrollX, 0),
                            intEvaluator.evaluate(fraction, startScrollY, 0)
                        )
                        doScaleAnimation(fraction)
                    }
                })
                animator.setDuration(duration.toLong()).setInterpolator(FastOutSlowInInterpolator())
                animator.start()
            }
        })
    }

    private fun doScaleAnimation(fraction: Float) {
        if (popupAnimation == PopupAnimation.ScrollAlphaFromLeft || popupAnimation == PopupAnimation.ScrollAlphaFromRight) {
            targetView!!.setScaleX(fraction)
        } else if (popupAnimation == PopupAnimation.ScrollAlphaFromTop || popupAnimation == PopupAnimation.ScrollAlphaFromBottom) {
            targetView!!.setScaleY(fraction)
        } else if (popupAnimation == PopupAnimation.ScrollAlphaFromLeftTop || popupAnimation == PopupAnimation.ScrollAlphaFromLeftBottom || popupAnimation == PopupAnimation.ScrollAlphaFromRightTop || popupAnimation == PopupAnimation.ScrollAlphaFromRightBottom) {
            targetView!!.setScaleX(fraction)
            targetView!!.setScaleY(fraction)
        }
    }

    public override fun animateDismiss() {
        if (animating) return
        val animator = ValueAnimator.ofFloat(0f, 1f)
        observerAnimator(animator)
        animator.addUpdateListener(object : AnimatorUpdateListener {
            override fun onAnimationUpdate(animation: ValueAnimator) {
                val fraction = animation.getAnimatedFraction()
                targetView!!.setAlpha(1 - fraction)
                targetView!!.scrollTo(
                    intEvaluator.evaluate(fraction, 0, startScrollX),
                    intEvaluator.evaluate(fraction, 0, startScrollY)
                )
                doScaleAnimation(1 - fraction)
            }
        })
        animator.setDuration(duration.toLong())
            .setInterpolator(FastOutSlowInInterpolator())
        animator.start()
    }
}
