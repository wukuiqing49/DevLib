package com.lxj.xpopup.widget

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import androidx.core.view.ViewCompat
import androidx.customview.widget.ViewDragHelper
import kotlin.math.sqrt
import com.lxj.xpopup.enums.DragOrientation

/**
 * PositionPopupView的容器.
 */
class PositionPopupContainer @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {
    private var dragHelper: ViewDragHelper? = null
    var child: View? = null
    var dragRatio: Float = 0.2f
    private var positionDragListener: OnPositionDragListener? = null
    var enableDrag: Boolean = false
    var dragOrientation: DragOrientation = DragOrientation.DragToUp
    var touchSlop: Int = 0
    private fun init() {
        dragHelper = ViewDragHelper.create(this, cb)
        touchSlop = ViewConfiguration.get(getContext()).getScaledTouchSlop()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        child = getChildAt(0)
    }

    private var touchX = 0f
    private var touchY = 0f
    var canIntercept: Boolean = false
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.getPointerCount() > 1 || !enableDrag) return super.dispatchTouchEvent(ev)
        try {
            val action = ev.getAction()

            if (action == MotionEvent.ACTION_DOWN) {
                touchX = ev.getX()
                touchY = ev.getY()
            } else if (action == MotionEvent.ACTION_MOVE) {
                val dx = ev.getX() - touchX
                val dy = ev.getY() - touchY
                canIntercept = sqrt(dx * dx + dy * dy) > touchSlop
                touchX = ev.getX()
                touchY = ev.getY()
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                touchX = 0f
                touchY = 0f
            }
        } catch (e: Exception) {
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (!enableDrag) return super.onInterceptTouchEvent(ev)
        val result = dragHelper!!.shouldInterceptTouchEvent(ev)
        return dragHelper!!.shouldInterceptTouchEvent(ev) || canIntercept
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (ev.getPointerCount() > 1 || !enableDrag) return false
        try {
            dragHelper!!.processTouchEvent(ev)
        } catch (e: Exception) {
        }
        return true
    }

    var cb: ViewDragHelper.Callback = object : ViewDragHelper.Callback() {
        override fun tryCaptureView(view: View, i: Int): Boolean {
            return view === child && enableDrag
        }

        override fun getViewVerticalDragRange(child: View): Int {
            return if (dragOrientation == DragOrientation.DragToUp || dragOrientation == DragOrientation.DragToBottom) 1 else 0
        }

        override fun getViewHorizontalDragRange(child: View): Int {
            return if (dragOrientation == DragOrientation.DragToLeft || dragOrientation == DragOrientation.DragToRight) 1 else 0
        }

        override fun clampViewPositionVertical(child: View, top: Int, dy: Int): Int {
            if (dragOrientation == DragOrientation.DragToUp) {
                return if (dy < 0) top else 0
            }
            return if (dragOrientation == DragOrientation.DragToBottom && dy > 0) top else 0
        }

        override fun clampViewPositionHorizontal(child: View, left: Int, dx: Int): Int {
            if (dragOrientation == DragOrientation.DragToLeft) {
                return if (dx < 0) left else 0
            }
            return if (dragOrientation == DragOrientation.DragToRight && dx > 0) left else 0
        }

        override fun onViewPositionChanged(
            changedView: View,
            left: Int,
            top: Int,
            dx: Int,
            dy: Int
        ) {
            super.onViewPositionChanged(changedView, left, top, dx, dy)
        }

        override fun onViewReleased(releasedChild: View, xvel: Float, yvel: Float) {
            super.onViewReleased(releasedChild, xvel, yvel)
            val maxX = releasedChild.getMeasuredWidth() * dragRatio
            val maxY = releasedChild.getMeasuredHeight() * dragRatio
            if ((dragOrientation == DragOrientation.DragToLeft && releasedChild.getLeft() < -maxX)
                || (dragOrientation == DragOrientation.DragToRight && releasedChild.getRight() > (releasedChild.getMeasuredWidth() + maxX))
                || (dragOrientation == DragOrientation.DragToUp && releasedChild.getTop() < -maxY)
                || (dragOrientation == DragOrientation.DragToBottom && releasedChild.getBottom() > (releasedChild.getMeasuredHeight() + maxY))
            ) {
                positionDragListener!!.onDismiss()
            } else {
                dragHelper!!.smoothSlideViewTo(releasedChild, 0, 0)
                ViewCompat.postInvalidateOnAnimation(this@PositionPopupContainer)
            }
        }
    }

    init {
        init()
    }

    override fun computeScroll() {
        super.computeScroll()
        if (dragHelper!!.continueSettling(false)) {
            ViewCompat.postInvalidateOnAnimation(this@PositionPopupContainer)
        }
    }

    fun setOnPositionDragChangeListener(positionDragListener: OnPositionDragListener) {
        this.positionDragListener = positionDragListener
    }

    interface OnPositionDragListener {
        fun onDismiss()
    }

    companion object {
        private const val TAG = "PositionPopupContainer"
    }
}
