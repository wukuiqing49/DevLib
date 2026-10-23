package com.lxj.xpopup.animator

import android.view.View
import android.view.animation.OvershootInterpolator
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import com.lxj.xpopup.enums.PopupAnimation

/**
 * Description: 缩放透明
 * Create by dance, at 2018/12/9
 */
class ScaleAlphaAnimator(target: View?, animationDuration: Int, popupAnimation: PopupAnimation?) :
    PopupAnimator(target, animationDuration, popupAnimation) {
    var startScale: Float = .95f
    public override fun initAnimator() {
        targetView!!.setScaleX(startScale)
        targetView!!.setScaleY(startScale)
        targetView!!.setAlpha(0f)

        // 设置动画参考点
        targetView!!.post(object : Runnable {
            override fun run() {
                applyPivot()
            }
        })
    }

    /**
     * 根据不同的PopupAnimation来设定对应的pivot
     */
    private fun applyPivot() {
        if (popupAnimation == PopupAnimation.ScaleAlphaFromCenter) {
            targetView!!.setPivotX(targetView!!.getMeasuredWidth() / 2f)
            targetView!!.setPivotY(targetView!!.getMeasuredHeight() / 2f)
        } else if (popupAnimation == PopupAnimation.ScaleAlphaFromLeftTop) {
            targetView!!.setPivotX(0f)
            targetView!!.setPivotY(0f)
        } else if (popupAnimation == PopupAnimation.ScaleAlphaFromRightTop) {
            targetView!!.setPivotX(targetView!!.getMeasuredWidth().toFloat())
            targetView!!.setPivotY(0f)
        } else if (popupAnimation == PopupAnimation.ScaleAlphaFromLeftBottom) {
            targetView!!.setPivotX(0f)
            targetView!!.setPivotY(targetView!!.getMeasuredHeight().toFloat())
        } else if (popupAnimation == PopupAnimation.ScaleAlphaFromRightBottom) {
            targetView!!.setPivotX(targetView!!.getMeasuredWidth().toFloat())
            targetView!!.setPivotY(targetView!!.getMeasuredHeight().toFloat())
        }
    }

    public override fun animateShow() {
        targetView!!.post(object : Runnable {
            override fun run() {
                targetView!!.animate().scaleX(1f).scaleY(1f).alpha(1f)
                    .setDuration(duration.toLong())
                    .setInterpolator(OvershootInterpolator(1f)) //                .withLayer() 在部分6.0系统会引起crash
                    .start()
            }
        })
    }

    public override fun animateDismiss() {
        if (animating) return
        observerAnimator(
            targetView!!.animate().scaleX(startScale).scaleY(startScale).alpha(0f)
                .setDuration(duration.toLong())
                .setInterpolator(FastOutSlowInInterpolator())
        ) //                .withLayer() 在部分6.0系统会引起crash
            .start()
    }
}
