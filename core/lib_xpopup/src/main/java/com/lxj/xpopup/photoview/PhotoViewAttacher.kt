/*
 Copyright 2011, 2012 Chris Banes.
 <p>
 Licensed under the Apache License, Version 2.0 (the "License");
 you may not use this file except in compliance with the License.
 You may obtain a copy of the License at
 <p>
 http://www.apache.org/licenses/LICENSE-2.0
 <p>
 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.
 */
package com.lxj.xpopup.photoview

import android.content.Context
import android.graphics.Matrix
import android.graphics.Matrix.ScaleToFit
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.GestureDetector
import android.view.GestureDetector.SimpleOnGestureListener
import android.view.MotionEvent
import android.view.View
import android.view.View.OnLayoutChangeListener
import android.view.View.OnLongClickListener
import android.view.View.OnTouchListener
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.Interpolator
import android.widget.ImageView
import android.widget.ImageView.ScaleType
import android.widget.OverScroller
import com.lxj.xpopup.photoview.Compat.postOnAnimation
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The component of which does the work allowing for zooming, scaling, panning, etc.
 * It is made public in case you need to subclass something other than AppCompatImageView and still
 * gain the functionality that [PhotoView] offers
 */
class PhotoViewAttacher(private val mImageView: ImageView) : OnTouchListener,
    OnLayoutChangeListener {
    private var mInterpolator: Interpolator = AccelerateDecelerateInterpolator()
    private var mZoomDuration: Int = DEFAULT_ZOOM_DURATION
    private var mMinScale: Float = DEFAULT_MIN_SCALE
    private var mMidScale: Float = DEFAULT_MID_SCALE
    private var mMaxScale: Float = DEFAULT_MAX_SCALE

    private var mAllowParentInterceptOnEdge = true
    private var mBlockParentIntercept = false

    // Gesture Detectors
    private val mGestureDetector: GestureDetector?
    private var mScaleDragDetector: CustomGestureDetector? = null

    // These are set so we don't keep allocating them on the heap
    private val mBaseMatrix = Matrix()
    val imageMatrix: Matrix = Matrix()
    private val mSuppMatrix = Matrix()
    private val mDisplayRect = RectF()
    private val mMatrixValues = FloatArray(9)

    // Listeners
    private var mMatrixChangeListener: OnMatrixChangedListener? = null
    private var mPhotoTapListener: OnPhotoTapListener? = null
    private var mOutsidePhotoTapListener: OnOutsidePhotoTapListener? = null
    private var mViewTapListener: OnViewTapListener? = null
    private var mOnClickListener: View.OnClickListener? = null
    private var mLongClickListener: OnLongClickListener? = null
    private var mScaleChangeListener: OnScaleChangedListener? = null
    private var mSingleFlingListener: OnSingleFlingListener? = null
    private var mOnViewDragListener: OnViewDragListener? = null

    private var mCurrentFlingRunnable: FlingRunnable? = null
    private var mHorizontalScrollEdge: Int = HORIZONTAL_EDGE_BOTH
    private var mVerticalScrollEdge: Int = VERTICAL_EDGE_BOTH
    private var mBaseRotation: Float = 0f
    var isTopEnd: Boolean = false
    var isBottomEnd: Boolean = false
    var isLeftEnd: Boolean = false
    var isRightEnd: Boolean = false
    var isVertical: Boolean = false
    var isHorizontal: Boolean = false

    @get:Deprecated("")
    var isZoomEnabled: Boolean = true
        private set
    private var isLongImage = false //是否是长图
    private var mScaleType = ScaleType.FIT_CENTER
    private val onGestureListener: OnGestureListener = object : OnGestureListener {
        override fun onDrag(dx: Float, dy: Float) {
            if (mScaleDragDetector!!.isScaling) {
                return  // Do not drag if we are already scaling
            }
            if (mOnViewDragListener != null) {
                mOnViewDragListener!!.onDrag(dx, dy)
            }
            mSuppMatrix.postTranslate(dx, dy)
            checkAndDisplayMatrix()
            isTopEnd = (mVerticalScrollEdge == VERTICAL_EDGE_TOP) && this@PhotoViewAttacher.scale != 1f
            isBottomEnd = (mVerticalScrollEdge == VERTICAL_EDGE_BOTTOM) && this@PhotoViewAttacher.scale != 1f
            isLeftEnd = (mHorizontalScrollEdge == HORIZONTAL_EDGE_LEFT) && this@PhotoViewAttacher.scale != 1f
            isRightEnd = (mHorizontalScrollEdge == HORIZONTAL_EDGE_RIGHT) && this@PhotoViewAttacher.scale != 1f

            val parent = mImageView.getParent()
            if (parent == null) return
            if (mAllowParentInterceptOnEdge && mScaleDragDetector?.isScaling != true && !mBlockParentIntercept) {
                if ((mHorizontalScrollEdge == HORIZONTAL_EDGE_BOTH && !isLongImage)
                    || (mHorizontalScrollEdge == HORIZONTAL_EDGE_LEFT && dx >= 0f && isHorizontal)
                    || (mHorizontalScrollEdge == HORIZONTAL_EDGE_RIGHT && dx <= -0f && isHorizontal) //                        || (mVerticalScrollEdge == VERTICAL_EDGE_TOP && dy >= 1f)
                //                        || (mVerticalScrollEdge == VERTICAL_EDGE_BOTTOM && dy <= -1f)
                ) {
                    parent.requestDisallowInterceptTouchEvent(false)
                } else if ((mVerticalScrollEdge == VERTICAL_EDGE_BOTH && isVertical)
                    || (isTopEnd && dy > 0 && isVertical)
                    || (isBottomEnd && dy < 0 && isVertical)
                ) {
                    parent.requestDisallowInterceptTouchEvent(false)
                } else if (isLongImage) {
                    //长图特殊上下滑动
                    if ((mVerticalScrollEdge == VERTICAL_EDGE_TOP && dy > 0 && isVertical)
                        || (mVerticalScrollEdge == VERTICAL_EDGE_BOTTOM && dy < 0 && isVertical)
                    ) {
                        parent.requestDisallowInterceptTouchEvent(false)
                    }
                }
            } else {
                if (mHorizontalScrollEdge == HORIZONTAL_EDGE_BOTH && isLongImage && isHorizontal) {
                    //长图左右滑动
                    parent.requestDisallowInterceptTouchEvent(false)
                } else if ((mHorizontalScrollEdge == HORIZONTAL_EDGE_RIGHT ||
                            mHorizontalScrollEdge == HORIZONTAL_EDGE_LEFT) && !isLongImage && !isHorizontal
                ) {
                    parent.requestDisallowInterceptTouchEvent(false)
                } else {
                    parent.requestDisallowInterceptTouchEvent(true)
                }
            }
        }

        override fun onFling(startX: Float, startY: Float, velocityX: Float, velocityY: Float) {
            mCurrentFlingRunnable = FlingRunnable(mImageView.getContext())
            mCurrentFlingRunnable!!.fling(
                getImageViewWidth(mImageView),
                getImageViewHeight(mImageView), velocityX.toInt(), velocityY.toInt()
            )
            mImageView.post(mCurrentFlingRunnable)
        }

        override fun onScale(scaleFactor: Float, focusX: Float, focusY: Float) {
            if (this@PhotoViewAttacher.scale < mMaxScale || scaleFactor < 1f) {
                if (mScaleChangeListener != null) {
                    mScaleChangeListener!!.onScaleChange(scaleFactor, focusX, focusY)
                }
                mSuppMatrix.postScale(scaleFactor, scaleFactor, focusX, focusY)
                checkAndDisplayMatrix()
            }
        }
    }

    fun setOnDoubleTapListener(newOnDoubleTapListener: GestureDetector.OnDoubleTapListener?) {
        this.mGestureDetector!!.setOnDoubleTapListener(newOnDoubleTapListener)
    }

    fun setOnScaleChangeListener(onScaleChangeListener: OnScaleChangedListener?) {
        this.mScaleChangeListener = onScaleChangeListener
    }

    fun setOnSingleFlingListener(onSingleFlingListener: OnSingleFlingListener?) {
        this.mSingleFlingListener = onSingleFlingListener
    }

    val displayRect: RectF?
        get() {
            checkMatrixBounds()
            return getDisplayRect(this.drawMatrix)
        }

    fun setDisplayMatrix(finalMatrix: Matrix): Boolean {
        requireNotNull(finalMatrix) { "Matrix cannot be null" }
        if (mImageView.getDrawable() == null) {
            return false
        }
        mSuppMatrix.set(finalMatrix)
        checkAndDisplayMatrix()
        return true
    }

    fun setBaseRotation(degrees: Float) {
        mBaseRotation = degrees % 360
        update()
        setRotationBy(mBaseRotation)
        checkAndDisplayMatrix()
    }

    fun setRotationTo(degrees: Float) {
        mSuppMatrix.setRotate(degrees % 360)
        checkAndDisplayMatrix()
    }

    fun setRotationBy(degrees: Float) {
        mSuppMatrix.postRotate(degrees % 360)
        checkAndDisplayMatrix()
    }

    var minimumScale: Float
        get() = mMinScale
        set(minimumScale) {
            Util.checkZoomLevels(minimumScale, mMidScale, mMaxScale)
            mMinScale = minimumScale
        }

    var mediumScale: Float
        get() = mMidScale
        set(mediumScale) {
            Util.checkZoomLevels(mMinScale, mediumScale, mMaxScale)
            mMidScale = mediumScale
        }

    var maximumScale: Float
        get() = mMaxScale
        set(maximumScale) {
            Util.checkZoomLevels(mMinScale, mMidScale, maximumScale)
            mMaxScale = maximumScale
        }

    var scale: Float
        get() {
            val scaleX = getValue(mSuppMatrix, Matrix.MSCALE_X)
            val skewY = getValue(mSuppMatrix, Matrix.MSKEW_Y)
            return sqrt(scaleX * scaleX + skewY * skewY)
        }
        set(scale) {
            setScale(scale, false)
        }

    var scaleType: ImageView.ScaleType
        get() = mScaleType
        set(scaleType) {
            if (Util.isSupportedScaleType(scaleType) && scaleType != mScaleType) {
                mScaleType = scaleType
                update()
            }
        }

    override fun onLayoutChange(
        v: View?,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        oldLeft: Int,
        oldTop: Int,
        oldRight: Int,
        oldBottom: Int
    ) {
        // Update our base matrix, as the bounds have changed
        if (left != oldLeft || top != oldTop || right != oldRight || bottom != oldBottom) {
            updateBaseMatrix(mImageView.getDrawable())
        }
    }

    var x: Float = 0f
    var y: Float = 0f

    init {
        mImageView.setOnTouchListener(this)
        mImageView.addOnLayoutChangeListener(this)
        if (mImageView.isInEditMode()) {
            mScaleDragDetector = null
            mGestureDetector = null
        } else {
            // Create Gesture Detectors...
            mScaleDragDetector = CustomGestureDetector(mImageView.getContext(), onGestureListener)
            mGestureDetector =
                GestureDetector(mImageView.getContext(), object : SimpleOnGestureListener() {
                // forward long click listener
                override fun onLongPress(e: MotionEvent) {
                    if (mLongClickListener != null) {
                        mLongClickListener!!.onLongClick(mImageView)
                    }
                }

                override fun onFling(
                    e1: MotionEvent?, e2: MotionEvent,
                    velocityX: Float, velocityY: Float
                ): Boolean {
                    if (mSingleFlingListener != null) {
                        if (this@PhotoViewAttacher.scale > DEFAULT_MIN_SCALE) {
                            return false
                        }
                        if (e1 == null || e1.getPointerCount() > SINGLE_TOUCH
                            || e2.getPointerCount() > SINGLE_TOUCH
                        ) {
                            return false
                        }
                        return mSingleFlingListener!!.onFling(e1, e2, velocityX, velocityY)
                    }
                    return false
                }
            })
            mGestureDetector.setOnDoubleTapListener(object : GestureDetector.OnDoubleTapListener {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (mOnClickListener != null) {
                    mOnClickListener!!.onClick(mImageView)
                }
                val displayRect: RectF? = this@PhotoViewAttacher.displayRect
                val x = e.getX()
                val y = e.getY()
                if (mViewTapListener != null) {
                    mViewTapListener!!.onViewTap(mImageView, x, y)
                }
                if (displayRect != null) {
                    // Check to see if the user tapped on the photo
                    if (displayRect.contains(x, y)) {
                        val xResult = ((x - displayRect.left)
                                / displayRect.width())
                        val yResult = ((y - displayRect.top)
                                / displayRect.height())
                        if (mPhotoTapListener != null) {
                            mPhotoTapListener!!.onPhotoTap(mImageView, xResult, yResult)
                        }
                        return true
                    } else {
                        if (mOutsidePhotoTapListener != null) {
                            mOutsidePhotoTapListener!!.onOutsidePhotoTap(mImageView)
                        }
                    }
                }
                return false
            }

            override fun onDoubleTap(ev: MotionEvent): Boolean {
                try {
                    val scale: Float = this@PhotoViewAttacher.scale
                    val x = ev.getX()
                    val y = ev.getY()
                    if (scale < this@PhotoViewAttacher.mediumScale) {
                        setScale(this@PhotoViewAttacher.mediumScale, x, y, true)
                    } else if (scale >= this@PhotoViewAttacher.mediumScale && scale < this@PhotoViewAttacher.maximumScale) {
                        setScale(this@PhotoViewAttacher.maximumScale, x, y, true)
                    } else {
                        setScale(this@PhotoViewAttacher.minimumScale, x, y, true)
                    }
                } catch (e: ArrayIndexOutOfBoundsException) {
                    // Can sometimes happen when getX() and getY() is called
                }
                return true
            }

            override fun onDoubleTapEvent(e: MotionEvent): Boolean {
                // Wait for the confirmed onDoubleTap() instead
                return true
            }
            })
        }
    }

    override fun onTouch(v: View?, ev: MotionEvent): Boolean {
        var handled = false
        val imageView = v as? ImageView ?: return false
        if (this.isZoomEnabled && Util.hasDrawable(imageView)) {
            if (ev.getAction() == MotionEvent.ACTION_DOWN) {
                x = ev.getX()
                y = ev.getY()
                val parent = v!!.getParent()
                // First, disable the Parent from intercepting the touch event
                // If we're flinging, and the user presses down, cancel fling
                cancelFling()
                if (parent != null) {
                    parent.requestDisallowInterceptTouchEvent(true)
                }
            } else if (ev.getAction() == MotionEvent.ACTION_CANCEL || ev.getAction() == MotionEvent.ACTION_UP) {
                isTopEnd = false
                // If the user has zoomed less than min scale, zoom back to min scale
                if (this.scale < mMinScale) {
                    val rect = this.displayRect
                    if (rect != null) {
                        v!!.post(
                            AnimatedZoomRunnable(
                                this.scale, mMinScale,
                                rect.centerX(), rect.centerY()
                            )
                        )
                        handled = true
                    }
                } else if (this.scale > mMaxScale) {
                    val rect = this.displayRect
                    if (rect != null) {
                        v!!.post(
                            AnimatedZoomRunnable(
                                this.scale, mMaxScale,
                                rect.centerX(), rect.centerY()
                            )
                        )
                        handled = true
                    }
                }
            } else if (ev.getAction() == MotionEvent.ACTION_MOVE) {
                val dx: Float = abs(ev.getX() - x)
                val dy: Float = abs(ev.getY() - y)
                if (isLongImage) {
                    isVertical = dy > dx
                    isHorizontal = dx > dy * 2
                } else {
                    isVertical = (this.scale.toDouble() != 1.0 && dy > dx)
                    isHorizontal = (this.scale.toDouble() != 1.0 && dx > dy * 2)
                }
            }

            // Try the Scale/Drag detector
            val scaleDragDetector = mScaleDragDetector
            if (scaleDragDetector != null) {
                val wasScaling = scaleDragDetector.isScaling
                val wasDragging = scaleDragDetector.isDragging
                handled = scaleDragDetector.onTouchEvent(ev)
                val didntScale = !wasScaling && !scaleDragDetector.isScaling
                val didntDrag = !wasDragging && !scaleDragDetector.isDragging
                mBlockParentIntercept = didntScale && didntDrag
            }
            // Check to see if the user double tapped
            if (mGestureDetector != null && mGestureDetector.onTouchEvent(ev)) {
                handled = true
            }
        }
        return handled
    }

    fun setAllowParentInterceptOnEdge(allow: Boolean) {
        mAllowParentInterceptOnEdge = allow
    }

    fun setScaleLevels(minimumScale: Float, mediumScale: Float, maximumScale: Float) {
        Util.checkZoomLevels(minimumScale, mediumScale, maximumScale)
        mMinScale = minimumScale
        mMidScale = mediumScale
        mMaxScale = maximumScale
    }

    fun setOnLongClickListener(listener: OnLongClickListener?) {
        mLongClickListener = listener
    }

    fun setOnClickListener(listener: View.OnClickListener?) {
        mOnClickListener = listener
    }

    fun setOnMatrixChangeListener(listener: OnMatrixChangedListener?) {
        mMatrixChangeListener = listener
    }

    fun setOnPhotoTapListener(listener: OnPhotoTapListener?) {
        mPhotoTapListener = listener
    }

    fun setOnOutsidePhotoTapListener(mOutsidePhotoTapListener: OnOutsidePhotoTapListener?) {
        this.mOutsidePhotoTapListener = mOutsidePhotoTapListener
    }

    fun setOnViewTapListener(listener: OnViewTapListener?) {
        mViewTapListener = listener
    }

    fun setOnViewDragListener(listener: OnViewDragListener?) {
        mOnViewDragListener = listener
    }

    fun setScale(scale: Float, animate: Boolean) {
        setScale(
            scale,
            ((mImageView.getRight()) / 2).toFloat(),
            ((mImageView.getBottom()) / 2).toFloat(),
            animate
        )
    }

    fun setScale(
        scale: Float, focalX: Float, focalY: Float,
        animate: Boolean
    ) {
        // Check to see if the scale is within bounds
//        if (scale < mMinScale || scale > mMaxScale) {
//            throw new IllegalArgumentException("Scale must be within the range of minScale and maxScale");
//        }
        if (animate) {
            mImageView.post(
                AnimatedZoomRunnable(
                    this.scale, scale,
                    focalX, focalY
                )
            )
        } else {
            mSuppMatrix.setScale(scale, scale, focalX, focalY)
            checkAndDisplayMatrix()
        }
    }

    /**
     * Set the zoom interpolator
     *
     * @param interpolator the zoom interpolator
     */
    fun setZoomInterpolator(interpolator: Interpolator) {
        mInterpolator = interpolator
    }

    var isZoomable: Boolean
        get() = this.isZoomEnabled
        set(zoomable) {
            this.isZoomEnabled = zoomable
            update()
        }

    fun update() {
        if (this.isZoomEnabled) {
            // Update the base matrix using the current drawable
            updateBaseMatrix(mImageView.getDrawable())
        } else {
            // Reset the Matrix...
            resetMatrix()
        }
    }

    /**
     * Get the display matrix
     *
     * @param matrix target matrix to copy to
     */
    fun getDisplayMatrix(matrix: Matrix) {
        matrix.set(this.drawMatrix)
    }

    /**
     * Get the current support matrix
     */
    fun getSuppMatrix(matrix: Matrix) {
        matrix.set(mSuppMatrix)
    }

    private val drawMatrix: Matrix
        get() {
            imageMatrix.set(mBaseMatrix)
            imageMatrix.postConcat(mSuppMatrix)
            return this.imageMatrix
        }

    fun setZoomTransitionDuration(milliseconds: Int) {
        this.mZoomDuration = milliseconds
    }

    /**
     * Helper method that 'unpacks' a Matrix and returns the required value
     *
     * @param matrix     Matrix to unpack
     * @param whichValue Which value from Matrix.M* to return
     * @return returned value
     */
    fun getValue(matrix: Matrix, whichValue: Int): Float {
        matrix.getValues(mMatrixValues)
        return mMatrixValues[whichValue]
    }

    /**
     * Resets the Matrix back to FIT_CENTER, and then displays its contents
     */
    private fun resetMatrix() {
        mSuppMatrix.reset()
        setRotationBy(mBaseRotation)
        setImageViewMatrix(this.drawMatrix)
        checkMatrixBounds()
    }

    private fun setImageViewMatrix(matrix: Matrix) {
        mImageView.setImageMatrix(matrix)
        // Call MatrixChangedListener if needed
        if (mMatrixChangeListener != null) {
            val displayRect = getDisplayRect(matrix)
            if (displayRect != null) {
                mMatrixChangeListener!!.onMatrixChanged(displayRect)
            }
        }
    }

    /**
     * Helper method that simply checks the Matrix, and then displays the result
     */
    private fun checkAndDisplayMatrix() {
        if (checkMatrixBounds()) {
            setImageViewMatrix(this.drawMatrix)
        }
    }

    /**
     * Helper method that maps the supplied Matrix to the current Drawable
     *
     * @param matrix - Matrix to map Drawable against
     * @return RectF - Displayed Rectangle
     */
    private fun getDisplayRect(matrix: Matrix): RectF? {
        val d = mImageView.getDrawable()
        if (d != null) {
            mDisplayRect.set(
                0f, 0f, d.getIntrinsicWidth().toFloat(),
                d.getIntrinsicHeight().toFloat()
            )
            matrix.mapRect(mDisplayRect)
            return mDisplayRect
        }
        return null
    }

    /**
     * Calculate Matrix for FIT_CENTER
     *
     * @param drawable - Drawable being displayed
     */
    private fun updateBaseMatrix(drawable: Drawable?) {
        if (drawable == null) {
            return
        }
        val viewWidth = getImageViewWidth(mImageView).toFloat()
        val viewHeight = getImageViewHeight(mImageView).toFloat()
        val drawableWidth = drawable.getIntrinsicWidth()
        val drawableHeight = drawable.getIntrinsicHeight()
        mBaseMatrix.reset()
        val widthScale = viewWidth / drawableWidth
        val heightScale = viewHeight / drawableHeight
        if (mScaleType == ScaleType.CENTER) {
            mBaseMatrix.postTranslate(
                (viewWidth - drawableWidth) / 2f,
                (viewHeight - drawableHeight) / 2f
            )
        } else if (mScaleType == ScaleType.CENTER_CROP) {
            val scale: Float = max(widthScale, heightScale)
            mBaseMatrix.postScale(scale, scale)
            mBaseMatrix.postTranslate(
                (viewWidth - drawableWidth * scale) / 2f,
                (viewHeight - drawableHeight * scale) / 2f
            )
        } else if (mScaleType == ScaleType.CENTER_INSIDE) {
            val scale: Float = min(1.0f, min(widthScale, heightScale))
            mBaseMatrix.postScale(scale, scale)
            mBaseMatrix.postTranslate(
                (viewWidth - drawableWidth * scale) / 2f,
                (viewHeight - drawableHeight * scale) / 2f
            )
        } else {
            var mTempSrc = RectF(0f, 0f, drawableWidth.toFloat(), drawableHeight.toFloat())
            val mTempDst = RectF(0f, 0f, viewWidth, viewHeight)
            if (mBaseRotation.toInt() % 180 != 0) {
                mTempSrc = RectF(0f, 0f, drawableHeight.toFloat(), drawableWidth.toFloat())
            }
            //            Log.e("tag", "mScaleType: "+mScaleType + "   drawableHeight: "+drawableHeight
//            + " viewHeight: "+ viewHeight + "  drawableWidth: "+drawableWidth + "  viewWidth: "+viewWidth) ;
            if (mScaleType == ScaleType.FIT_CENTER) {
                // for long image, 图片高>view高，比例也大于view的高/宽，则认为是长图
                if ( /*drawableHeight > viewHeight &&*/drawableHeight * 1f / drawableWidth > viewHeight * 1f / viewWidth) {
                    // 长图特殊处理，宽度撑满屏幕，并且顶部对齐
                    isLongImage = true
                    mBaseMatrix.setRectToRect(
                        mTempSrc,
                        RectF(0f, 0f, viewWidth, drawableHeight * widthScale),
                        ScaleToFit.START
                    )
                } else {
                    mBaseMatrix.setRectToRect(mTempSrc, mTempDst, ScaleToFit.CENTER)
                }
            } else if (mScaleType == ScaleType.FIT_START) {
                mBaseMatrix.setRectToRect(mTempSrc, mTempDst, ScaleToFit.START)
            } else if (mScaleType == ScaleType.FIT_END) {
                mBaseMatrix.setRectToRect(mTempSrc, mTempDst, ScaleToFit.END)
            } else if (mScaleType == ScaleType.FIT_XY) {
                mBaseMatrix.setRectToRect(mTempSrc, mTempDst, ScaleToFit.FILL)
            }
        }
        resetMatrix()
    }

    private fun checkMatrixBounds(): Boolean {
        val rect = getDisplayRect(this.drawMatrix)
        if (rect == null) {
            return false
        }
        val height = rect.height()
        val width = rect.width()
        var deltaX = 0f
        var deltaY = 0f
        val viewHeight = getImageViewHeight(mImageView)
        if (height <= viewHeight && rect.top >= 0) {
            if (mScaleType == ScaleType.FIT_START) {
                deltaY = -rect.top
            } else if (mScaleType == ScaleType.FIT_END) {
                deltaY = viewHeight - height - rect.top
            } else {
                deltaY = (viewHeight - height) / 2 - rect.top
            }

            mVerticalScrollEdge = VERTICAL_EDGE_BOTH
        } else if (rect.top >= 0) {
            mVerticalScrollEdge = VERTICAL_EDGE_TOP
            deltaY = -rect.top
        } else if (rect.bottom <= viewHeight) {
            mVerticalScrollEdge = VERTICAL_EDGE_BOTTOM
            deltaY = viewHeight - rect.bottom
        } else {
            mVerticalScrollEdge = VERTICAL_EDGE_NONE
        }
        val viewWidth = getImageViewWidth(mImageView)
        //        Log.e("tag", "rect: " + rect.toShortString() + " viewWidth: " + viewWidth + " viewHeight: " + viewHeight
//                + " recLeft: " + rect.left + "  recRight: " + rect.right + " mHorizontalScrollEdge: " + mHorizontalScrollEdge);
        if (width <= viewWidth && rect.left >= 0) {
            if (mScaleType == ScaleType.FIT_START) {
                deltaX = -rect.left
            } else if (mScaleType == ScaleType.FIT_END) {
                deltaX = viewWidth - width - rect.left
            } else {
                deltaX = (viewWidth - width) / 2 - rect.left
            }

            mHorizontalScrollEdge = HORIZONTAL_EDGE_BOTH
        } else if (rect.left >= 0) {
            mHorizontalScrollEdge = HORIZONTAL_EDGE_LEFT
            deltaX = -rect.left
        } else if (rect.right <= viewWidth) {
            deltaX = viewWidth - rect.right
            mHorizontalScrollEdge = HORIZONTAL_EDGE_RIGHT
        } else {
            mHorizontalScrollEdge = HORIZONTAL_EDGE_NONE
        }
        // Finally actually translate the matrix
        mSuppMatrix.postTranslate(deltaX, deltaY)
        return true
    }

    private fun getImageViewWidth(imageView: ImageView): Int {
        return imageView.getWidth() - imageView.getPaddingStart() - imageView.getPaddingEnd()
    }

    private fun getImageViewHeight(imageView: ImageView): Int {
        return imageView.getHeight() - imageView.getPaddingTop() - imageView.getPaddingBottom()
    }

    private fun cancelFling() {
        if (mCurrentFlingRunnable != null) {
            mCurrentFlingRunnable!!.cancelFling()
            mCurrentFlingRunnable = null
        }
    }

    private inner class AnimatedZoomRunnable(
        private val mZoomStart: Float, private val mZoomEnd: Float,
        private val mFocalX: Float, private val mFocalY: Float
    ) : Runnable {
        private val mStartTime: Long

        init {
            mStartTime = System.currentTimeMillis()
        }

        override fun run() {
            val t = interpolate()
            val scale = mZoomStart + t * (mZoomEnd - mZoomStart)
            val deltaScale: Float = scale / this@PhotoViewAttacher.scale
            onGestureListener.onScale(deltaScale, mFocalX, mFocalY)
            // We haven't hit our target scale yet, so post ourselves again
            if (t < 1f) {
                postOnAnimation(mImageView, this)
            }
        }

        fun interpolate(): Float {
            var t = 1f * (System.currentTimeMillis() - mStartTime) / mZoomDuration
            t = min(1f, t)
            t = mInterpolator.getInterpolation(t)
            return t
        }
    }

    private inner class FlingRunnable(context: Context?) : Runnable {
        private val mScroller: OverScroller
        private var mCurrentX = 0
        private var mCurrentY = 0

        init {
            mScroller = OverScroller(context)
        }

        fun cancelFling() {
            mScroller.forceFinished(true)
        }

        fun fling(
            viewWidth: Int, viewHeight: Int, velocityX: Int,
            velocityY: Int
        ) {
            val rect: RectF? = this@PhotoViewAttacher.displayRect
            if (rect == null) {
                return
            }
            val startX = Math.round(-rect.left)
            val minX: Int
            val maxX: Int
            val minY: Int
            val maxY: Int
            if (viewWidth < rect.width()) {
                minX = 0
                maxX = Math.round(rect.width() - viewWidth)
            } else {
                maxX = startX
                minX = maxX
            }
            val startY = Math.round(-rect.top)
            if (viewHeight < rect.height()) {
                minY = 0
                maxY = Math.round(rect.height() - viewHeight)
            } else {
                maxY = startY
                minY = maxY
            }
            mCurrentX = startX
            mCurrentY = startY
            // If we actually can move, fling the scroller
            if (startX != maxX || startY != maxY) {
                mScroller.fling(
                    startX, startY, velocityX, velocityY, minX,
                    maxX, minY, maxY, 0, 0
                )
            }
        }

        override fun run() {
            if (mScroller.isFinished()) {
                return  // remaining post that should not be handled
            }
            if (mScroller.computeScrollOffset()) {
                val newX = mScroller.getCurrX()
                val newY = mScroller.getCurrY()
                mSuppMatrix.postTranslate(
                    (mCurrentX - newX).toFloat(),
                    (mCurrentY - newY).toFloat()
                )
                checkAndDisplayMatrix()
                mCurrentX = newX
                mCurrentY = newY
                // Post On animation
                postOnAnimation(mImageView, this)
            }
        }
    }

    companion object {
        private const val DEFAULT_MAX_SCALE = 4.0f
        private const val DEFAULT_MID_SCALE = 2.5f
        private const val DEFAULT_MIN_SCALE = 1.0f
        private const val DEFAULT_ZOOM_DURATION = 200

        private val HORIZONTAL_EDGE_NONE = -1
        private const val HORIZONTAL_EDGE_LEFT = 0
        private const val HORIZONTAL_EDGE_RIGHT = 1
        private const val HORIZONTAL_EDGE_BOTH = 2
        private val VERTICAL_EDGE_NONE = -1
        private const val VERTICAL_EDGE_TOP = 0
        private const val VERTICAL_EDGE_BOTTOM = 1
        private const val VERTICAL_EDGE_BOTH = 2
        private const val SINGLE_TOUCH = 1
    }
}
