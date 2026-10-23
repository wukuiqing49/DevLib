package com.lxj.xpopup.animator

import android.util.Log
import android.view.View
import android.view.ViewPropertyAnimator
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import com.lxj.xpopup.enums.PopupAnimation

/**
 * Description: 平移动画，不带渐变
 * Create by dance, at 2018/12/9
 */
class TranslateAnimator(target: View?, animationDuration: Int, popupAnimation: PopupAnimation?) :
    PopupAnimator(target, animationDuration, popupAnimation) {
    var startTranslationX: Float = 0f
    var startTranslationY: Float = 0f
    var endTranslationX: Float = 0f
    var endTranslationY: Float = 0f
    public override fun initAnimator() {
        if (!hasInit) {
            endTranslationX = targetView!!.getTranslationX()
            endTranslationY = targetView!!.getTranslationY()
            // 设置起始坐标
            applyTranslation()
            startTranslationX = targetView!!.getTranslationX()
            startTranslationY = targetView!!.getTranslationY()
        }
    }

    private fun applyTranslation() {
        if (popupAnimation == PopupAnimation.TranslateFromLeft) {
            targetView!!.setTranslationX(-targetView!!.getRight() + targetView!!.getTranslationX())
        } else if (popupAnimation == PopupAnimation.TranslateFromTop) {
            targetView!!.setTranslationY(-targetView!!.getBottom() + targetView!!.getTranslationY())
        } else if (popupAnimation == PopupAnimation.TranslateFromRight) {
            targetView!!.setTranslationX((targetView!!.getParent() as View).getMeasuredWidth() - targetView!!.getLeft() + targetView!!.getTranslationX())
        } else if (popupAnimation == PopupAnimation.TranslateFromBottom) {
            targetView!!.setTranslationY((targetView!!.getParent() as View).getMeasuredHeight() - targetView!!.getTop() + targetView!!.getTranslationY())
        }
    }

    public override fun animateShow() {
        var animator: ViewPropertyAnimator? = null
        if (popupAnimation == PopupAnimation.TranslateFromLeft || popupAnimation == PopupAnimation.TranslateFromRight) {
            animator = targetView!!.animate().translationX(endTranslationX)
        } else if (popupAnimation == PopupAnimation.TranslateFromTop || popupAnimation == PopupAnimation.TranslateFromBottom) {
            animator = targetView!!.animate().translationY(endTranslationY)
        }

        if (animator != null) animator.setInterpolator(FastOutSlowInInterpolator())
            .setDuration(duration.toLong())
            .withLayer()
            .start()
        Log.e("part", "start: " + targetView!!.getTranslationY() + "  endy: " + endTranslationY)
    }

    public override fun animateDismiss() {
        if (animating) return
        var animator: ViewPropertyAnimator? = null
        if (popupAnimation == PopupAnimation.TranslateFromLeft) {
            startTranslationX = -targetView!!.getRight().toFloat()
            animator = targetView!!.animate().translationX(startTranslationX)
        } else if (popupAnimation == PopupAnimation.TranslateFromTop) {
            startTranslationY = -targetView!!.getBottom().toFloat()
            animator = targetView!!.animate().translationY(startTranslationY)
        } else if (popupAnimation == PopupAnimation.TranslateFromRight) {
            startTranslationX =
                ((targetView!!.getParent() as View).getMeasuredWidth() - targetView!!.getLeft()).toFloat()
            animator = targetView!!.animate().translationX(startTranslationX)
        } else if (popupAnimation == PopupAnimation.TranslateFromBottom) {
            startTranslationY =
                ((targetView!!.getParent() as View).getMeasuredHeight() - targetView!!.getTop()).toFloat()
            animator = targetView!!.animate().translationY(startTranslationY)
        }

        if (animator != null) observerAnimator(
            animator.setInterpolator(FastOutSlowInInterpolator())
                .setDuration((duration * .8).toLong())
                .withLayer()
        )
            .start()
    }
}
