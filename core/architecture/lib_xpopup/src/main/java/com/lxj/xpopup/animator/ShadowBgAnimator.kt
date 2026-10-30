package com.lxj.xpopup.animator

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.animation.ValueAnimator.AnimatorUpdateListener
import android.graphics.Color
import android.view.View
import androidx.interpolator.view.animation.FastOutSlowInInterpolator

/**
 * Description: 背景Shadow动画器，负责执行半透明的渐入渐出动画
 * Create by dance, at 2018/12/9
 */
class ShadowBgAnimator : PopupAnimator {
    var argbEvaluator: ArgbEvaluator = ArgbEvaluator()
    var startColor: Int = Color.TRANSPARENT
    var isZeroDuration: Boolean = false
    var shadowColor: Int = 0

    constructor(target: View?, animationDuration: Int, shadowColor: Int) : super(
        target,
        animationDuration
    ) {
        this.shadowColor = shadowColor
    }

    constructor()

    public override fun initAnimator() {
        targetView!!.setBackgroundColor(startColor)
    }

    public override fun animateShow() {
        val animator = ValueAnimator.ofObject(argbEvaluator, startColor, shadowColor)
        animator.addUpdateListener(object : AnimatorUpdateListener {
            override fun onAnimationUpdate(animation: ValueAnimator) {
                targetView!!.setBackgroundColor((animation.getAnimatedValue() as Int?)!!)
            }
        })
        animator.setInterpolator(FastOutSlowInInterpolator())
        animator.setDuration(if (isZeroDuration) 0 else duration.toLong()).start()
    }

    public override fun animateDismiss() {
        if (animating) return
        val animator = ValueAnimator.ofObject(argbEvaluator, shadowColor, startColor)
        animator.addUpdateListener(object : AnimatorUpdateListener {
            override fun onAnimationUpdate(animation: ValueAnimator) {
                targetView!!.setBackgroundColor((animation.getAnimatedValue() as Int?)!!)
            }
        })
        observerAnimator(animator)
        animator.setInterpolator(FastOutSlowInInterpolator())
        animator.setDuration(if (isZeroDuration) 0 else duration.toLong()).start()
    }

    fun applyColorValue(`val`: Float) {
        targetView!!.setBackgroundColor((calculateBgColor(`val`) as Int?)!!)
    }

    fun calculateBgColor(fraction: Float): Int {
        return argbEvaluator.evaluate(fraction, startColor, shadowColor) as Int
    }
}
