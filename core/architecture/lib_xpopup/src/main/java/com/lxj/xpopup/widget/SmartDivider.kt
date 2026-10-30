package com.lxj.xpopup.widget

import android.R
import android.content.Context
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.util.Log
import android.view.View
import android.widget.LinearLayout
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.RecyclerView.ItemDecoration

class SmartDivider(context: Context, orientation: Int) : ItemDecoration() {
    private var mDivider: Drawable?

    /**
     * Current orientation. Either [.HORIZONTAL] or [.VERTICAL].
     */
    private var mOrientation = 0

    private val mBounds = Rect()

    /**
     * Creates a divider [RecyclerView.ItemDecoration] that can be used with a
     * [LinearLayoutManager].
     *
     * @param context Current context, it will be used to access resources.
     * @param orientation Divider orientation. Should be [.HORIZONTAL] or [.VERTICAL].
     */
    init {
        val a = context.obtainStyledAttributes(ATTRS)
        mDivider = a.getDrawable(0)
        if (mDivider == null) {
            Log.w(
                TAG, "@android:attr/listDivider was not set in the theme used for this "
                        + "DividerItemDecoration. Please set that attribute all call setDrawable()"
            )
        }
        a.recycle()
        setOrientation(orientation)
    }

    /**
     * Sets the orientation for this divider. This should be called if
     * [RecyclerView.LayoutManager] changes orientation.
     *
     * @param orientation [.HORIZONTAL] or [.VERTICAL]
     */
    fun setOrientation(orientation: Int) {
        require(!(orientation != HORIZONTAL && orientation != VERTICAL)) { "Invalid orientation. It should be either HORIZONTAL or VERTICAL" }
        mOrientation = orientation
    }

    var drawable: Drawable?
        /**
         * @return the [Drawable] for this divider.
         */
        get() = mDivider
        /**
         * Sets the [Drawable] for this divider.
         *
         * @param drawable Drawable that should be used as a divider.
         */
        set(drawable) {
            requireNotNull(drawable) { "Drawable cannot be null." }
            mDivider = drawable
        }

    override fun onDraw(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        if (parent.getLayoutManager() == null || mDivider == null) {
            return
        }
        if (mOrientation == VERTICAL) {
            drawVertical(c, parent)
        } else {
            drawHorizontal(c, parent)
        }
    }

    private fun drawVertical(canvas: Canvas, parent: RecyclerView) {
        canvas.save()
        val left: Int
        val right: Int
        if (parent.getClipToPadding()) {
            left = parent.getPaddingStart()
            right = parent.getWidth() - parent.getPaddingEnd()
            canvas.clipRect(
                left, parent.getPaddingTop(), right,
                parent.getHeight() - parent.getPaddingBottom()
            )
        } else {
            left = 0
            right = parent.getWidth()
        }

        val childCount = parent.getChildCount()
        for (i in 0 until childCount) {
            if (i == (childCount - 1)) break //最后一个不要

            val child = parent.getChildAt(i)
            parent.getDecoratedBoundsWithMargins(child, mBounds)
            val bottom = mBounds.bottom + Math.round(child.getTranslationY())
            val top = bottom - mDivider!!.getIntrinsicHeight()
            mDivider!!.setBounds(left, top, right, bottom)
            mDivider!!.draw(canvas)
        }
        canvas.restore()
    }

    private fun drawHorizontal(canvas: Canvas, parent: RecyclerView) {
        canvas.save()
        val top: Int
        val bottom: Int
        if (parent.getClipToPadding()) {
            top = parent.getPaddingTop()
            bottom = parent.getHeight() - parent.getPaddingBottom()
            canvas.clipRect(
                parent.getPaddingStart(), top,
                parent.getWidth() - parent.getPaddingEnd(), bottom
            )
        } else {
            top = 0
            bottom = parent.getHeight()
        }

        val childCount = parent.getChildCount()
        for (i in 0 until childCount) {
            if (i == (childCount - 1)) break //最后一个不要

            val child = parent.getChildAt(i)
            parent.getLayoutManager()!!.getDecoratedBoundsWithMargins(child, mBounds)
            val right = mBounds.right + Math.round(child.getTranslationX())
            val left = right - mDivider!!.getIntrinsicWidth()
            mDivider!!.setBounds(left, top, right, bottom)
            mDivider!!.draw(canvas)
        }
        canvas.restore()
    }

    override fun getItemOffsets(
        outRect: Rect, view: View, parent: RecyclerView,
        state: RecyclerView.State
    ) {
        if (mDivider == null) {
            outRect.set(0, 0, 0, 0)
            return
        }
        if (mOrientation == VERTICAL) {
            outRect.set(0, 0, 0, mDivider!!.getIntrinsicHeight())
        } else {
            outRect.set(0, 0, mDivider!!.getIntrinsicWidth(), 0)
        }
    }

    companion object {
        val HORIZONTAL: Int = LinearLayout.HORIZONTAL
        val VERTICAL: Int = LinearLayout.VERTICAL

        private const val TAG = "DividerItem"
        private val ATTRS = intArrayOf(R.attr.listDivider)
    }
}
