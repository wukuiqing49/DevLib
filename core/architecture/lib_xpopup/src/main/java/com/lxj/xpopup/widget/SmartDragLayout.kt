package com.lxj.xpopup.widget

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.LinearLayout
import android.widget.OverScroller
import androidx.core.view.NestedScrollingParent
import androidx.core.view.ViewCompat
import com.lxj.xpopup.enums.LayoutStatus
import com.lxj.xpopup.util.XPopupUtils
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Description: 智能的拖拽布局，优先滚动整体，整体滚到头，则滚动内部能滚动的View
 * Create by dance, at 2018/12/23
 */
class SmartDragLayout @JvmOverloads constructor(
    context: Context?,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr), NestedScrollingParent {
    private var child: View? = null
    var scroller: OverScroller? = null
    var tracker: VelocityTracker? = null
    var enableDrag: Boolean = true //是否启用手势拖拽
    var dismissOnTouchOutside: Boolean = true
    var isUserClose: Boolean = false
    var isThreeDrag: Boolean = false //是否开启三段拖拽
    var status: LayoutStatus = LayoutStatus.Close
    private var scrollDuration: Int = 400

    var maxY: Int = 0
    var minY: Int = 0

    override fun onViewAdded(c: View?) {
        super.onViewAdded(c)
        child = c
    }

    var lastHeight: Int = 0
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        if (enableDrag) {
            if (child == null) return
            maxY = child!!.getMeasuredHeight()
            minY = 0
            val l = getMeasuredWidth() / 2 - child!!.getMeasuredWidth() / 2
            child!!.layout(
                l,
                getMeasuredHeight(),
                l + child!!.getMeasuredWidth(),
                getMeasuredHeight() + maxY
            )
            if (status == LayoutStatus.Open) {
                if (isThreeDrag) {
                    //通过scroll上移
                    scrollTo(getScrollX(), getScrollY() - (lastHeight - maxY))
                } else {
                    //通过scroll上移
                    scrollTo(getScrollX(), getScrollY() - (lastHeight - maxY))
                }
            }
            lastHeight = maxY
        } else {
            val l = getMeasuredWidth() / 2 - child!!.getMeasuredWidth() / 2
            child!!.layout(
                l,
                getMeasuredHeight() - child!!.getMeasuredHeight(),
                l + child!!.getMeasuredWidth(),
                getMeasuredHeight()
            )
        }
    }

    override fun onInterceptTouchEvent(ev: MotionEvent?): Boolean {
        isUserClose = true
        if (status == LayoutStatus.Closing || status == LayoutStatus.Opening) return false
        return super.onInterceptTouchEvent(ev)
    }

    var touchX: Float = 0f
    var touchY: Float = 0f

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (status == LayoutStatus.Closing || status == LayoutStatus.Opening) return false
        if (enableDrag && (scroller!!.computeScrollOffset() || status == LayoutStatus.Close)) {
            touchX = 0f
            touchY = 0f
            return true
        }
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            if (enableDrag) {
                if (tracker != null) tracker!!.clear()
                tracker = VelocityTracker.obtain()
            }
            touchX = event.getX()
            touchY = event.getY()
        } else if (event.getAction() == MotionEvent.ACTION_MOVE) {
            if (enableDrag && tracker != null) {
                tracker!!.addMovement(event)
                tracker!!.computeCurrentVelocity(1000)
                val dy = (event.getY() - touchY).toInt()
                scrollTo(getScrollX(), getScrollY() - dy)
                touchY = event.getY()
            }
        } else if (event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_CANCEL) {
            // click in child rect
            val rect = Rect()
            child!!.getGlobalVisibleRect(rect)
            if (!XPopupUtils.isInRect(
                    event.getRawX(),
                    event.getRawY(),
                    rect
                ) && dismissOnTouchOutside
            ) {
                val distance = sqrt(
                    (event.getX() - touchX).toDouble().pow(2.0) + (event.getY() - touchY).toDouble()
                        .pow(2.0)
                ).toFloat()
                if (distance < ViewConfiguration.get(getContext()).getScaledTouchSlop()) {
                    performClick()
                }
            }

            if (enableDrag && tracker != null) {
                val yVelocity = tracker!!.getYVelocity()
                if (yVelocity > 1500 && !isThreeDrag) {
                    close()
                } else {
                    finishScroll()
                }
                tracker = null
            }
        }

        return enableDrag
    }

    private fun finishScroll() {
        if (enableDrag) {
            val threshold = if (isScrollUp) (maxY - minY) / 3 else (maxY - minY) * 2 / 3
            var dy = (if (getScrollY() > threshold) maxY else minY) - getScrollY()
            if (isThreeDrag) {
                val per = maxY / 3
                if (getScrollY() > per * 2.5f) {
                    dy = maxY - getScrollY()
                } else if (getScrollY() <= per * 2.5f && getScrollY() > per * 1.5f) {
                    dy = per * 2 - getScrollY()
                } else if (getScrollY() > per) {
                    dy = per - getScrollY()
                } else {
                    dy = minY - getScrollY()
                }
            }
            scroller!!.startScroll(getScrollX(), getScrollY(), 0, dy, scrollDuration)
            ViewCompat.postInvalidateOnAnimation(this)
        }
    }

    var isScrollUp: Boolean = false

    override fun scrollTo(x: Int, y: Int) {
        var y = y
        if (y > maxY) y = maxY
        if (y < minY) y = minY
        val fraction = (y - minY) * 1f / (maxY - minY)
        isScrollUp = y > getScrollY()
        if (listener != null) {
            if (isUserClose && fraction == 0f && status != LayoutStatus.Close) {
                status = LayoutStatus.Close
                listener!!.onClose()
            } else if (fraction == 1f && status != LayoutStatus.Open) {
                status = LayoutStatus.Open
                listener!!.onOpen()
            }
            listener!!.onDrag(y, fraction, isScrollUp)
        }
        super.scrollTo(x, y)
    }

    override fun computeScroll() {
        super.computeScroll()
        if (scroller!!.computeScrollOffset()) {
            scrollTo(scroller!!.getCurrX(), scroller!!.getCurrY())
            ViewCompat.postInvalidateOnAnimation(this)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        isScrollUp = false
        isUserClose = false
        setTranslationY(0f)
    }

    fun open() {
        post(object : Runnable {
            override fun run() {
                val dy = maxY - getScrollY()
                smoothScroll(if (enableDrag && isThreeDrag) dy / 3 else dy, true)
                status = LayoutStatus.Opening
            }
        })
    }

    fun close() {
        isUserClose = true
        post(object : Runnable {
            override fun run() {
                scroller!!.abortAnimation()
                smoothScroll(minY - getScrollY(), false)
                status = LayoutStatus.Closing
            }
        })
    }

    private fun smoothScroll(dy: Int, isOpen: Boolean) {
        scroller!!.startScroll(
            getScrollX(),
            getScrollY(),
            0,
            dy,
            (if (isOpen) scrollDuration.toFloat() else scrollDuration * 0.8f).toInt()
        )
        ViewCompat.postInvalidateOnAnimation(this@SmartDragLayout)
    }

    override fun onStartNestedScroll(child: View, target: View, nestedScrollAxes: Int): Boolean {
        return nestedScrollAxes == ViewCompat.SCROLL_AXIS_VERTICAL && enableDrag
    }

    override fun onNestedScrollAccepted(child: View, target: View, nestedScrollAxes: Int) {
        //必须要取消，否则会导致滑动初次延迟
        scroller!!.abortAnimation()
    }

    override fun onStopNestedScroll(target: View) {
        finishScroll()
    }

    override fun onNestedScroll(
        target: View,
        dxConsumed: Int,
        dyConsumed: Int,
        dxUnconsumed: Int,
        dyUnconsumed: Int
    ) {
        scrollTo(getScrollX(), getScrollY() + dyUnconsumed)
    }

    override fun onNestedPreScroll(target: View, dx: Int, dy: Int, consumed: IntArray) {
        if (dy > 0) {
            //scroll up
            val newY = getScrollY() + dy
            if (newY < maxY) {
                consumed[1] = dy // dy不一定能消费完
            }
            scrollTo(getScrollX(), newY)
        }
    }

    override fun onNestedFling(
        target: View,
        velocityX: Float,
        velocityY: Float,
        consumed: Boolean
    ): Boolean {
        val isDragging = getScrollY() > minY && getScrollY() < maxY
        if (isDragging && velocityY < -1500 && !isThreeDrag) {
            close()
        }
        return false
    }

    override fun onNestedPreFling(target: View, velocityX: Float, velocityY: Float): Boolean {
        return false
    }

    override fun getNestedScrollAxes(): Int {
        return ViewCompat.SCROLL_AXIS_VERTICAL
    }

    fun isThreeDrag(isThreeDrag: Boolean) {
        this.isThreeDrag = isThreeDrag
    }

    fun enableDrag(enableDrag: Boolean) {
        this.enableDrag = enableDrag
    }

    fun setDuration(duration: Int) {
        this.scrollDuration = duration
    }

    fun dismissOnTouchOutside(dismissOnTouchOutside: Boolean) {
        this.dismissOnTouchOutside = dismissOnTouchOutside
    }

    private var listener: OnCloseListener? = null

    init {
        if (enableDrag) {
            scroller = OverScroller(context)
        }
    }

    fun setOnCloseListener(listener: OnCloseListener?) {
        this.listener = listener
    }

    interface OnCloseListener {
        fun onClose()

        fun onDrag(y: Int, percent: Float, isScrollUp: Boolean)

        fun onOpen()
    }
}
