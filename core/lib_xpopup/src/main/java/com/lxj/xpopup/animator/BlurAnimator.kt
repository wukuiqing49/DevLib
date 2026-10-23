package com.lxj.xpopup.animator

import android.animation.FloatEvaluator
import android.graphics.Bitmap
import android.graphics.PorterDuff
import android.graphics.drawable.BitmapDrawable
import android.view.View
import com.lxj.xpopup.util.XPopupUtils

/**
 * Description: 背景模糊动画器
 * Create by dance, at 2018/12/9
 */
class BlurAnimator : PopupAnimator {
    private val evaluate = FloatEvaluator()
    var shadowColor: Int = 0

    constructor(target: View?, shadowColor: Int) : super(target, 0) {
        this.shadowColor = shadowColor
    }

    var decorBitmap: Bitmap? = null
    var hasShadowBg: Boolean = false

    constructor()

    override fun initAnimator() {
        val target = targetView ?: return
        val bitmap = decorBitmap ?: return
        val blurBmp = XPopupUtils.renderScriptBlur(target.context, bitmap, 10f, true)
        val drawable = BitmapDrawable(target.resources, blurBmp)
        if (hasShadowBg) drawable.setColorFilter(shadowColor, PorterDuff.Mode.SRC_OVER)
        target.background = drawable
    }

    override fun animateShow() {
        //有性能问题
//        ValueAnimator animator = ValueAnimator.ofFloat(0,1);
//        animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
//            @Override
//            public void onAnimationUpdate(ValueAnimator animation) {
//                float fraction = animation.getAnimatedFraction();
//                Bitmap blurBmp = ImageUtils.renderScriptBlur(decorBitmap, evaluate.evaluate(0f, 25f, fraction), false);
//                targetView.setBackground(new BitmapDrawable(targetView.getResources(), blurBmp));
//            }
//        });
//        animator.setInterpolator(new LinearInterpolator());
//        animator.setDuration(XPopup.animationDuration).start();
    }

    override fun animateDismiss() {
//        ValueAnimator animator = ValueAnimator.ofFloat(1,0);
//        animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
//            @Override
//            public void onAnimationUpdate(ValueAnimator animation) {
//                float fraction = animation.getAnimatedFraction();
//                Bitmap blurBmp = ImageUtils.renderScriptBlur(decorBitmap, evaluate.evaluate(0f, 25f, fraction), false);
//                targetView.setBackground(new BitmapDrawable(targetView.getResources(), blurBmp));
//            }
//        });
//        animator.setInterpolator(new LinearInterpolator());
//        animator.setDuration(XPopup.animationDuration).start();
    }
}
