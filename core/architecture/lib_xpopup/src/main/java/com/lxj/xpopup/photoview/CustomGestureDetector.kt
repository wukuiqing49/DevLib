/*
 Copyright 2011, 2012 Chris Banes.
 <p/>
 Licensed under the Apache License, Version 2.0 (the "License");
 you may not use this file except in compliance with the License.
 You may obtain a copy of the License at
 <p/>
 http://www.apache.org/licenses/LICENSE-2.0
 <p/>
 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.
 */
package com.lxj.xpopup.photoview

import android.content.Context
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ScaleGestureDetector.OnScaleGestureListener
import android.view.VelocityTracker
import android.view.ViewConfiguration
import kotlin.Boolean
import kotlin.Exception
import kotlin.IllegalArgumentException
import kotlin.Int
import kotlin.compareTo
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Does a whole lot of gesture detecting.
 */
internal class CustomGestureDetector(context: Context, listener: OnGestureListener) {
    private var mActivePointerId: Int = INVALID_POINTER_ID
    private var mActivePointerIndex = 0
    private val mDetector: ScaleGestureDetector

    private var mVelocityTracker: VelocityTracker? = null
    var isDragging: Boolean = false
        private set
    private var mLastTouchX = 0f
    private var mLastTouchY = 0f
    private val mTouchSlop: Float
    private val mMinimumVelocity: Float
    private val mListener: OnGestureListener

    init {
        val configuration = ViewConfiguration
            .get(context)
        mMinimumVelocity = configuration.getScaledMinimumFlingVelocity().toFloat()
        mTouchSlop = configuration.getScaledTouchSlop().toFloat()

        mListener = listener
        val mScaleListener: OnScaleGestureListener = object : OnScaleGestureListener {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val scaleFactor = detector.getScaleFactor()
                if (scaleFactor.isNaN() || scaleFactor.isInfinite()) return false
                if (scaleFactor >= 0) {
                    mListener.onScale(
                        scaleFactor,
                        detector.getFocusX(), detector.getFocusY()
                    )
                }
                return true
            }

            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                // NO-OP
            }
        }
        mDetector = ScaleGestureDetector(context, mScaleListener)
    }

    private fun getActiveX(ev: MotionEvent): kotlin.Float {
        try {
            return ev.getX(mActivePointerIndex)
        } catch (e: Exception) {
            return ev.getX()
        }
    }

    private fun getActiveY(ev: MotionEvent): kotlin.Float {
        try {
            return ev.getY(mActivePointerIndex)
        } catch (e: Exception) {
            return ev.getY()
        }
    }

    val isScaling: Boolean
        get() = mDetector.isInProgress()

    fun onTouchEvent(ev: MotionEvent): Boolean {
        try {
            if (ev.getPointerCount() > 1) mDetector.onTouchEvent(ev)
            return processTouchEvent(ev)
        } catch (e: IllegalArgumentException) {
            // Fix for support lib bug, happening when onDestroy is called
            return true
        }
    }

    private fun processTouchEvent(ev: MotionEvent): Boolean {
        val action = ev.getAction()

        val actionMasked = action and MotionEvent.ACTION_MASK

        if (actionMasked == MotionEvent.ACTION_DOWN) {
            mActivePointerId = ev.getPointerId(0)

            mVelocityTracker = VelocityTracker.obtain()
            if (mVelocityTracker != null) {
                mVelocityTracker!!.addMovement(ev)
            }

            mLastTouchX = getActiveX(ev)
            mLastTouchY = getActiveY(ev)
            this.isDragging = false
        } else if (actionMasked == MotionEvent.ACTION_MOVE) {
            val x = getActiveX(ev)
            val y = getActiveY(ev)
            val dx = x - mLastTouchX
            val dy = y - mLastTouchY

            if (!this.isDragging) {
                this.isDragging = sqrt((dx * dx) + (dy * dy)) >= mTouchSlop
            }

            if (this.isDragging) {
                mListener.onDrag(dx, dy)
                mLastTouchX = x
                mLastTouchY = y

                if (mVelocityTracker != null) {
                    mVelocityTracker!!.addMovement(ev)
                }
            }
        } else if (actionMasked == MotionEvent.ACTION_CANCEL) {
            mActivePointerId = INVALID_POINTER_ID
            if (mVelocityTracker != null) {
                mVelocityTracker!!.recycle()
                mVelocityTracker = null
            }
        } else if (actionMasked == MotionEvent.ACTION_UP) {
            mActivePointerId = INVALID_POINTER_ID
            if (this.isDragging && mVelocityTracker != null) {
                mLastTouchX = getActiveX(ev)
                mLastTouchY = getActiveY(ev)

                mVelocityTracker!!.addMovement(ev)
                mVelocityTracker!!.computeCurrentVelocity(1000)

                val vX = mVelocityTracker!!.getXVelocity()
                val vY = mVelocityTracker!!.getYVelocity()

                if (max(abs(vX), abs(vY)) >= mMinimumVelocity) {
                    mListener.onFling(mLastTouchX, mLastTouchY, -vX, -vY)
                }
            }
            if (mVelocityTracker != null) {
                mVelocityTracker!!.recycle()
                mVelocityTracker = null
            }
        } else if (actionMasked == MotionEvent.ACTION_POINTER_UP) {
            val pointerIndex = Util.getPointerIndex(ev.getAction())
            val pointerId = ev.getPointerId(pointerIndex)
            if (pointerId == mActivePointerId) {
                val newPointerIndex = if (pointerIndex == 0) 1 else 0
                mActivePointerId = ev.getPointerId(newPointerIndex)
                mLastTouchX = ev.getX(newPointerIndex)
                mLastTouchY = ev.getY(newPointerIndex)
            }
        }

        mActivePointerIndex = ev
            .findPointerIndex(
                if (mActivePointerId != INVALID_POINTER_ID)
                    mActivePointerId
                else
                    0
            )
        return true
    }

    companion object {
        private val INVALID_POINTER_ID = -1
    }
}
