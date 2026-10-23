package com.lxj.xpopup.animator

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.view.View
import android.view.ViewPropertyAnimator
import com.lxj.xpopup.enums.PopupAnimation

/**
 * Description: 弹窗动画执行器
 * Create by dance, at 2018/12/9
 */
abstract class PopupAnimator {
    protected var animating: Boolean = false
    var hasInit: Boolean = false
    var targetView: View? = null
    var duration: Int = 0
    var popupAnimation: PopupAnimation? = null // 内置的动画

    constructor()

    @JvmOverloads
    constructor(target: View?, animationDuration: Int, popupAnimation: PopupAnimation? = null) {
        this.targetView = target
        this.duration = animationDuration
        this.popupAnimation = popupAnimation
    }

    abstract fun initAnimator()
    abstract fun animateShow()
    abstract fun animateDismiss()

    protected open fun observerAnimator(animator: ValueAnimator): ValueAnimator {
        animator.removeAllListeners()
        animator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationStart(animation: Animator) {
                super.onAnimationStart(animation)
                animating = true
            }

            override fun onAnimationEnd(animation: Animator) {
                super.onAnimationEnd(animation)
                animating = false
            }
        })
        return animator
    }

    protected open fun observerAnimator(animator: ViewPropertyAnimator): ViewPropertyAnimator {
        animator.setListener(object : AnimatorListenerAdapter() {
            override fun onAnimationStart(animation: Animator) {
                super.onAnimationStart(animation)
                animating = true
            }

            override fun onAnimationEnd(animation: Animator) {
                super.onAnimationEnd(animation)
                animating = false
            }
        })
        return animator
    }
}
