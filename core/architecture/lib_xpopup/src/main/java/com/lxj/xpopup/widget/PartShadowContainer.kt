package com.lxj.xpopup.widget

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout
import com.lxj.xpopup.core.BasePopupView
import com.lxj.xpopup.interfaces.OnClickOutsideListener
import com.lxj.xpopup.util.XPopupUtils
import kotlin.math.sqrt

/**
 * Description:
 * Create by dance, at 2019/1/10
 */
class PartShadowContainer : FrameLayout {
    var notDismissArea: ArrayList<Rect>? = null
    var popupView: BasePopupView? = null

    constructor(context: Context) : super(context)

    @JvmOverloads
    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int = 0) : super(
        context,
        attrs,
        defStyleAttr
    )

    private var x = 0f
    private var y = 0f

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // 计算implView的Rect
        val implView = getChildAt(0)
        val location = IntArray(2)
        implView.getLocationInWindow(location)
        val implViewRect = Rect(
            location[0], location[1], location[0] + implView.getMeasuredWidth(),
            location[1] + implView.getMeasuredHeight()
        )
        if (!XPopupUtils.isInRect(event.getRawX(), event.getRawY(), implViewRect)) {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                x = event.getX()
                y = event.getY()
                // TODO: PartShadowContainer需要实现自己的isClickThrough和isTouchThrough
                // if(popupView != null ){
                //     popupView.passTouchThrough(event);
                // }
            } else if (event.getAction() == MotionEvent.ACTION_MOVE) {
                // if(popupView != null && popupView.popupInfo != null && popupView.popupInfo.isTouchThrough){
                //     popupView.passTouchThrough(event);
                // }
            } else if (event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_CANCEL) {
                val dx = event.getX() - x
                val dy = event.getY() - y
                val distance = sqrt(dx * dx + dy * dy)
                if (distance < ViewConfiguration.get(getContext()).getScaledTouchSlop()) {
                    val excludedAreas = notDismissArea
                    if (excludedAreas != null && !excludedAreas.isEmpty()) {
                        var inRect = false
                        for (r in excludedAreas) {
                            if (XPopupUtils.isInRect(event.getRawX(), event.getRawY(), r)) {
                                inRect = true
                                break
                            }
                        }
                        if (!inRect && listener != null) {
                            listener!!.onClickOutside()
                        }
                    } else {
                        if (listener != null) listener!!.onClickOutside()
                    }
                }
                x = 0f
                y = 0f
            }
        }
        return true
    }

    private var listener: OnClickOutsideListener? = null

    fun setOnClickOutsideListener(listener: OnClickOutsideListener?) {
        this.listener = listener
    }
}
