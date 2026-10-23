package com.lxj.xpopup.animator

import android.view.View
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import com.lxj.xpopup.enums.PopupAnimation

/**
 * Description: 平移动画
 * Create by dance, at 2018/12/9
 */
class TranslateAlphaAnimator(
    target: View?,
    animationDuration: Int,
    popupAnimation: PopupAnimation?
) : PopupAnimator(target, animationDuration, popupAnimation) {
    //动画起始坐标
    private var startTranslationX = 0f
    private var startTranslationY = 0f
    private var defTranslationX = 0f
    private var defTranslationY = 0f
    public override fun initAnimator() {
        defTranslationX = targetView!!.getTranslationX()
        defTranslationY = targetView!!.getTranslationY()

        targetView!!.setAlpha(0f)
        // 设置移动坐标
        applyTranslation()
        startTranslationX = targetView!!.getTranslationX()
        startTranslationY = targetView!!.getTranslationY()
    }

    private fun applyTranslation() {
        if (popupAnimation == PopupAnimation.TranslateAlphaFromLeft) {
            targetView!!.setTranslationX(
                -targetView!!.getMeasuredWidth().toFloat() /* + halfWidthOffset */
            )
        } else if (popupAnimation == PopupAnimation.TranslateAlphaFromTop) {
            targetView!!.setTranslationY(
                -targetView!!.getMeasuredHeight().toFloat() /* + halfHeightOffset */
            )
        } else if (popupAnimation == PopupAnimation.TranslateAlphaFromRight) {
            targetView!!.setTranslationX(
                targetView!!.getMeasuredWidth().toFloat() /* + halfWidthOffset */
            )
        } else if (popupAnimation == PopupAnimation.TranslateAlphaFromBottom) {
            targetView!!.setTranslationY(
                targetView!!.getMeasuredHeight().toFloat() /* + halfHeightOffset */
            )
        }
    }

    public override fun animateShow() {
        targetView!!.animate().translationX(defTranslationX).translationY(defTranslationY).alpha(1f)
            .setInterpolator(FastOutSlowInInterpolator())
            .setDuration(duration.toLong())
            .withLayer()
            .start()
    }

    public override fun animateDismiss() {
        if (animating) return
        observerAnimator(
            targetView!!.animate().translationX(startTranslationX).translationY(startTranslationY)
                .alpha(0f)
                .setInterpolator(FastOutSlowInInterpolator())
                .setDuration(duration.toLong())
                .withLayer()
        )
            .start()
    }
}
