package com.lxj.xpopup.widget

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.AbsSeekBar
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import androidx.core.view.ViewCompat
import androidx.customview.widget.ViewDragHelper
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager.widget.ViewPager
import androidx.viewpager2.widget.ViewPager2
import com.lxj.xpopup.enums.LayoutStatus
import kotlin.math.abs
import com.lxj.xpopup.enums.PopupPosition
import com.lxj.xpopup.util.XPopupUtils

/**
 * Description: 根据手势拖拽子View的layout，这种类型的弹窗比较特殊，不需要额外的动画器，因为
 * 动画是根据手势滑动而发生的
 * Create by dance, at 2018/12/20
 */
class PopupDrawerLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {
    var status: LayoutStatus? = null
    lateinit var dragHelper: ViewDragHelper
    var placeHolder: View? = null
    var mChild: View? = null
    var position: PopupPosition? = PopupPosition.Left
    var fraction: Float = 0f
    var enableDrag: Boolean = true

    fun setDrawerPosition(position: PopupPosition?) {
        this.position = position
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        placeHolder = getChildAt(0)
        mChild = getChildAt(1)
    }

    var ty: Float = 0f

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        ty = getTranslationY()
    }

    var hasLayout: Boolean = false
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        placeHolder!!.layout(0, 0, getMeasuredWidth(), getMeasuredHeight())
        if (!hasLayout) {
            if (position == PopupPosition.Left) {
                mChild!!.layout(-mChild!!.getMeasuredWidth(), 0, 0, getMeasuredHeight())
            } else {
                mChild!!.layout(
                    getMeasuredWidth(),
                    0,
                    getMeasuredWidth() + mChild!!.getMeasuredWidth(),
                    getMeasuredHeight()
                )
            }
            hasLayout = true
        } else {
            mChild!!.layout(
                mChild!!.getLeft(),
                mChild!!.getTop(),
                mChild!!.getRight(),
                mChild!!.getMeasuredHeight()
            )
        }
    }

    var isIntercept: Boolean = false
    private var touchX: Float = 0f
    private var touchY: Float = 0f
    var downX: Float = 0f
    var downY: Float = 0f
    var isToLeft: Boolean = false
    var canChildScrollLeft: Boolean = false

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (!enableDrag) return super.onInterceptTouchEvent(ev)
        if (dragHelper.continueSettling(true) || status == LayoutStatus.Close) return true
        isToLeft = ev.getX() < touchX
        touchX = ev.getX()
        touchY = ev.getY()
        if (ev.getAction() == MotionEvent.ACTION_DOWN) {
            downX = ev.getX()
            downY = ev.getY()
        } else if (ev.getAction() == MotionEvent.ACTION_MOVE) {
            val dx: Float = abs(ev.getX() - downX)
            val dy: Float = abs(ev.getY() - downY)
            if (dy > dx) {
                // 垂直方向滑动，不拦截
                return false
            }
        } else if (ev.getAction() == MotionEvent.ACTION_UP || ev.getAction() == MotionEvent.ACTION_CANCEL) {
            downX = 0f
            downY = 0f
        }
        //        boolean canChildScrollRight = canScroll(this, ev.getX(), ev.getY(), -1);
        canChildScrollLeft = canScroll(this, ev.getX(), ev.getY(), 1)
        //        if (ev.getAction() == MotionEvent.ACTION_UP || ev.getAction() == MotionEvent.ACTION_CANCEL) {
//            x = 0;
//            y = 0;
//        }
        isIntercept = dragHelper.shouldInterceptTouchEvent(ev)
        if (isToLeft && !canChildScrollLeft) {
            return isIntercept
        }

        val canChildScrollHorizontal = canScroll(this, ev.getX(), ev.getY())
        if (!canChildScrollHorizontal) return isIntercept

        return super.onInterceptTouchEvent(ev)
    }

    private fun canScroll(group: ViewGroup, x: Float, y: Float, direction: Int = 0): Boolean {
        for (i in 0 until group.getChildCount()) {
            val child = group.getChildAt(i)
            val location = IntArray(2)
            child.getLocationInWindow(location)
            val rect = Rect(
                location[0], location[1], location[0] + child.getWidth(),
                location[1] + child.getHeight()
            )
            val inRect = XPopupUtils.isInRect(x, y, rect)
            if (inRect) {
                if (child is ViewGroup) {
                    if (child is ViewPager) {
                        val pager = child
                        if (direction == 0) {
                            return pager.canScrollHorizontally(-1) || pager.canScrollHorizontally(1)
                        }
                        return pager.canScrollHorizontally(direction)
                    } else if (child is HorizontalScrollView) {
                        val hsv = child
                        if (direction == 0) {
                            return hsv.canScrollHorizontally(-1) || hsv.canScrollHorizontally(1)
                        }
                        return hsv.canScrollHorizontally(direction)
                    } else if (child is ViewPager2) {
                        val pager2 = child
                        val rv = pager2.getChildAt(0) as RecyclerView
                        return rv.canScrollHorizontally(-1) || rv.canScrollHorizontally(1)
                    } else {
                        return canScroll(child, x, y, direction)
                    }
                } else {
                    if (child is AbsSeekBar && child.isEnabled()) {
                        return true
                    }
                }
            }
        }
        return false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!enableDrag) return super.onTouchEvent(event)
        if (dragHelper.continueSettling(true)) return true
        dragHelper.processTouchEvent(event)
        return true
    }

    var callback: ViewDragHelper.Callback = object : ViewDragHelper.Callback() {
        override fun tryCaptureView(view: View, i: Int): Boolean {
            return enableDrag && !dragHelper.continueSettling(true) && status != LayoutStatus.Close
        }

        override fun getViewHorizontalDragRange(child: View): Int {
            return 1
        }

        override fun clampViewPositionHorizontal(child: View, left: Int, dx: Int): Int {
            if (child === placeHolder) return left
            return fixLeft(left)
        }

        override fun onViewPositionChanged(
            changedView: View,
            left: Int,
            top: Int,
            dx: Int,
            dy: Int
        ) {
            super.onViewPositionChanged(changedView, left, top, dx, dy)
            if (changedView === placeHolder) {
                placeHolder!!.layout(
                    0,
                    0,
                    placeHolder!!.getMeasuredWidth(),
                    placeHolder!!.getMeasuredHeight()
                )
                val newLeft = fixLeft(mChild!!.getLeft() + dx)
                mChild!!.layout(
                    newLeft,
                    mChild!!.getTop(),
                    newLeft + mChild!!.getMeasuredWidth(),
                    mChild!!.getBottom()
                )
                calcFraction(newLeft, dx)
            } else {
                calcFraction(left, dx)
            }
        }

        private fun calcFraction(left: Int, dx: Int) {
            // fraction = (now - start) * 1f / (end - start)
            if (position == PopupPosition.Left) {
                fraction = (left + mChild!!.getMeasuredWidth()) * 1f / mChild!!.getMeasuredWidth()
                if (left == -mChild!!.getMeasuredWidth() && listener != null && status != LayoutStatus.Close) {
                    status = LayoutStatus.Close
                    listener!!.onClose()
                }
            } else if (position == PopupPosition.Right) {
                fraction = (getMeasuredWidth() - left) * 1f / mChild!!.getMeasuredWidth()
                if (left == getMeasuredWidth() && listener != null && status != LayoutStatus.Close) {
                    status = LayoutStatus.Close
                    listener!!.onClose()
                }
            }
            if (listener != null) {
                listener!!.onDrag(left, fraction, dx < 0)
                if (fraction == 1f && status != LayoutStatus.Open) {
                    status = LayoutStatus.Open
                    listener!!.onOpen()
                }
            }
        }

        override fun onViewReleased(releasedChild: View, xvel: Float, yvel: Float) {
            super.onViewReleased(releasedChild, xvel, yvel)
            if (releasedChild === placeHolder && xvel == 0f) {
                if (isDismissOnTouchOutside) close()
                return
            }
            if (releasedChild === mChild && isToLeft && !canChildScrollLeft && xvel < -500) {
                close()
                return
            }

            var centerLeft = 0
            var finalLeft = 0
            if (position == PopupPosition.Left) {
                if (xvel < -1000) {
                    finalLeft = -mChild!!.getMeasuredWidth()
                } else {
                    centerLeft = -mChild!!.getMeasuredWidth() / 2
                    finalLeft =
                        if (mChild!!.getLeft() < centerLeft) -mChild!!.getMeasuredWidth() else 0
                }
            } else {
                if (xvel > 1000) {
                    finalLeft = getMeasuredWidth()
                } else {
                    centerLeft = getMeasuredWidth() - mChild!!.getMeasuredWidth() / 2
                    finalLeft =
                        if (releasedChild.getLeft() < centerLeft) getMeasuredWidth() - mChild!!.getMeasuredWidth() else getMeasuredWidth()
                }
            }
            dragHelper.smoothSlideViewTo(mChild!!, finalLeft, releasedChild.getTop())
            ViewCompat.postInvalidateOnAnimation(this@PopupDrawerLayout)
        }
    }

    private fun fixLeft(left: Int): Int {
        var left = left
        if (position == PopupPosition.Left) {
            if (left < -mChild!!.getMeasuredWidth()) left = -mChild!!.getMeasuredWidth()
            if (left > 0) left = 0
        } else if (position == PopupPosition.Right) {
            if (left < (getMeasuredWidth() - mChild!!.getMeasuredWidth())) left =
                (getMeasuredWidth() - mChild!!.getMeasuredWidth())
            if (left > getMeasuredWidth()) left = getMeasuredWidth()
        }
        return left
    }

    override fun computeScroll() {
        super.computeScroll()
        if (dragHelper.continueSettling(true)) {
            ViewCompat.postInvalidateOnAnimation(this)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        //        status = null;
//        hasLayout = false;
//        fraction = 0f;
//        setTranslationY(ty);
    }

    /**
     * 打开Drawer
     */
    fun open() {
        post(object : Runnable {
            override fun run() {
                dragHelper.smoothSlideViewTo(
                    mChild!!,
                    if (position == PopupPosition.Left) 0 else (mChild!!.getLeft() - mChild!!.getMeasuredWidth()),
                    0
                )
                ViewCompat.postInvalidateOnAnimation(this@PopupDrawerLayout)
            }
        })
    }

    var isDismissOnTouchOutside: Boolean = true

    /**
     * 关闭Drawer
     */
    fun close() {
        post(object : Runnable {
            override fun run() {
                dragHelper.abort()
                dragHelper.smoothSlideViewTo(
                    mChild!!,
                    if (position == PopupPosition.Left) -mChild!!.getMeasuredWidth() else getMeasuredWidth(),
                    0
                )
                ViewCompat.postInvalidateOnAnimation(this@PopupDrawerLayout)
            }
        })
    }

    private var listener: OnCloseListener? = null

    init {
        dragHelper = ViewDragHelper.create(this, callback)
    }

    fun setOnCloseListener(listener: OnCloseListener?) {
        this.listener = listener
    }

    interface OnCloseListener {
        fun onClose()

        fun onOpen()

        /**
         * 关闭过程中执行
         *
         * @param fraction 关闭的百分比
         */
        fun onDrag(x: Int, fraction: Float, isToLeft: Boolean)
    }
}
