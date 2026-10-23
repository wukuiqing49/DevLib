package com.lxj.xpopup.widget

import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.core.view.ViewCompat
import androidx.customview.widget.ViewDragHelper
import androidx.viewpager.widget.ViewPager
import com.lxj.xpopup.interfaces.OnDragChangeListener
import com.lxj.xpopup.photoview.PhotoView
import kotlin.math.abs
import kotlin.math.min

/**
 * wrap ViewPager, process drag event.
 */
class PhotoViewContainer @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {
    private var dragHelper: ViewDragHelper? = null
    var viewPager: ViewPager? = null
    private var HideTopThreshold = 80
    private var maxOffset = 0
    private var dragChangeListener: OnDragChangeListener? = null
    var isReleasing: Boolean = false
    private fun init() {
        HideTopThreshold = dip2px(HideTopThreshold.toFloat())
        dragHelper = ViewDragHelper.create(this, cb)
        setBackgroundColor(Color.TRANSPARENT)
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        viewPager = getChildAt(0) as ViewPager
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        maxOffset = getHeight() / 3
    }

    var isVertical: Boolean = false
    private var touchX = 0f
    private var touchY = 0f

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.getPointerCount() > 1) return super.dispatchTouchEvent(ev)
        try {
            if (ev.getAction() == MotionEvent.ACTION_DOWN) {
                touchX = ev.getX()
                touchY = ev.getY()
            } else if (ev.getAction() == MotionEvent.ACTION_MOVE) {
                val dx = ev.getX() - touchX
                val dy = ev.getY() - touchY
                viewPager!!.dispatchTouchEvent(ev)
                isVertical = abs(dy) > abs(dx)
                touchX = ev.getX()
                touchY = ev.getY()
            } else if (ev.getAction() == MotionEvent.ACTION_UP || ev.getAction() == MotionEvent.ACTION_CANCEL) {
                touchX = 0f
                touchY = 0f
                isVertical = false
            }
        } catch (e: Exception) {
        }
        return super.dispatchTouchEvent(ev)
    }

    private val isTopOrBottomEnd: Boolean
        get() {
            val view = this.currentImageView
            if (view is PhotoView) {
                return (view.attacher!!.isTopEnd || view.attacher!!.isBottomEnd)
            }
            //        SubsamplingScaleImageView ssiv = (SubsamplingScaleImageView) view;
            return false
        }

    private val currentImageView: View?
        get() {
            val fl = viewPager!!.getChildAt(viewPager!!.getCurrentItem()) as FrameLayout?
            if (fl == null) return null
            return fl.getChildAt(0)
        }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        val result = dragHelper!!.shouldInterceptTouchEvent(ev)
        if (ev.getPointerCount() > 1 && ev.getAction() == MotionEvent.ACTION_MOVE) return false
        if (this.isTopOrBottomEnd && isVertical) return true
        return result && isVertical
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (ev.getPointerCount() > 1) return false
        try {
            dragHelper!!.processTouchEvent(ev)
            return true
        } catch (e: Exception) {
        }
        return true
    }

    var cb: ViewDragHelper.Callback = object : ViewDragHelper.Callback() {
        override fun tryCaptureView(view: View, i: Int): Boolean {
            return !isReleasing
        }

        override fun getViewVerticalDragRange(child: View): Int {
            return 1
        }

        override fun clampViewPositionVertical(child: View, top: Int, dy: Int): Int {
            val t = viewPager!!.getTop() + dy / 2
            if (t >= 0) {
                return min(t, maxOffset)
            } else {
                return -min(-t, maxOffset)
            }
        }

        override fun onViewPositionChanged(
            changedView: View,
            left: Int,
            top: Int,
            dx: Int,
            dy: Int
        ) {
            super.onViewPositionChanged(changedView, left, top, dx, dy)
            if (changedView !== viewPager) {
                viewPager!!.offsetTopAndBottom(dy)
            }
            val fraction: Float = abs(top) * 1f / maxOffset
            val pageScale = 1 - fraction * .2f
            viewPager!!.setScaleX(pageScale)
            viewPager!!.setScaleY(pageScale)
            changedView.setScaleX(pageScale)
            changedView.setScaleY(pageScale)
            if (dragChangeListener != null) {
                dragChangeListener!!.onDragChange(dy, pageScale, fraction)
            }
        }

        override fun onViewReleased(releasedChild: View, xvel: Float, yvel: Float) {
            super.onViewReleased(releasedChild, xvel, yvel)
            if (abs(releasedChild.getTop()) > HideTopThreshold) {
                if (dragChangeListener != null) dragChangeListener!!.onRelease()
            } else {
                dragHelper!!.smoothSlideViewTo(viewPager!!, 0, 0)
                dragHelper!!.smoothSlideViewTo(releasedChild, 0, 0)
                ViewCompat.postInvalidateOnAnimation(this@PhotoViewContainer)
            }
        }
    }

    init {
        init()
    }

    override fun computeScroll() {
        super.computeScroll()
        if (dragHelper!!.continueSettling(false)) {
            ViewCompat.postInvalidateOnAnimation(this@PhotoViewContainer)
        }
    }

    fun dip2px(dpValue: Float): Int {
        val scale = getContext().getResources().getDisplayMetrics().density
        return (dpValue * scale + 0.5f).toInt()
    }

    fun setOnDragChangeListener(listener: OnDragChangeListener?) {
        this.dragChangeListener = listener
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        isReleasing = false
    }

    companion object {
        private const val TAG = "PhotoViewContainer"
    }
}
