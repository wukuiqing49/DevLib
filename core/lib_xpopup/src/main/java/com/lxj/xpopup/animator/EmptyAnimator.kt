package com.lxj.xpopup.animator

import android.view.View

/**
 * Description: 没有动画效果的动画器
 * Create by dance, at 2019/6/6
 */
class EmptyAnimator(target: View?, animationDuration: Int) :
    PopupAnimator(target, animationDuration) {
    override fun initAnimator() {
        targetView?.alpha = 0f
    }

    override fun animateShow() {
        targetView?.animate()?.alpha(1f)?.setDuration(duration.toLong())?.withLayer()?.start()
    }

    override fun animateDismiss() {
        if (animating) return
        observerAnimator(
            targetView!!.animate().alpha(0f).setDuration(duration.toLong()).withLayer()
        )
            .start()
    }
}
