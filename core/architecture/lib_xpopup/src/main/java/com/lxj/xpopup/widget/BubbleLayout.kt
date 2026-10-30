package com.lxj.xpopup.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.os.Bundle
import android.os.Parcelable
import android.util.AttributeSet
import android.widget.FrameLayout
import kotlin.math.max
import com.lxj.xpopup.util.XPopupUtils

/**
 * 气泡布局
 * Created by JiajiXu on 17-12-1.
 */
class BubbleLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {
    //    @Override
    //    public boolean onTouchEvent(MotionEvent event) {
    //        if (event.getAction() == MotionEvent.ACTION_DOWN) {
    //            RectF r = new RectF();
    //            mPath.computeBounds(r, true);
    //            mRegion.setPath(mPath, new Region((int) r.left, (int) r.top, (int) r.right, (int) r.bottom));
    //            if (!mRegion.contains((int) event.getX(), (int) event.getY()) && mListener != null) {
    //                mListener.edge();
    //            }
    //        }
    //        return super.onTouchEvent(event);
    //    }
    val mPaint: Paint
        //    @Override
        get() {
            return field
        }
    private val mPath: Path
    private var mLook: Look? = null
    private var mBubblePadding = 0
    private var mWidth = 0
    private var mHeight = 0
    private var mLeft = 0
    private var mTop = 0
    private var mRight = 0
    private var mBottom = 0
    var mLookPosition: Int = 0
    var mLookWidth: Int = 0
    var mLookLength: Int = 0
    private var mShadowColor = 0
    private var mShadowRadius = 0
    private var mShadowX = 0
    private var mShadowY = 0
    private var mBubbleRadius = 0
    private var mBubbleColor = 0

    // 坐上弧度，右上弧度，右下弧度，左下弧度
    private var mLTR = 0
    private var mRTR = 0
    private var mRDR = 0
    private var mLDR = -1

    // 箭头
    //     箭头尖分左右两个弧度分别是由 mArrowTopLeftRadius, mArrowTopRightRadius 控制
    //     箭头底部左右两个弧度分别是由 mArrowDownLeftRadius, mArrowDownRightRadius 控制
    private var mArrowTopLeftRadius = 0
    private var mArrowTopRightRadius = 0
    private var mArrowDownLeftRadius = 0
    private var mArrowDownRightRadius = 0

    //    private OnClickEdgeListener mListener;
    //    private Region mRegion = new Region();
    // 气泡背景图资源
    private var mBubbleBgRes = -1

    // 气泡背景图
    private var mBubbleImageBg: Bitmap? = null

    // 气泡背景显示区域
    private val mBubbleImageBgDstRectF = RectF()
    private val mBubbleImageBgSrcRect = Rect()
    private val mBubbleImageBgPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
    private val mBubbleImageBgBeforePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)

    // 气泡边框颜色
    private var mBubbleBorderColor = Color.BLACK

    // 气泡边框大小
    private var mBubbleBorderSize = 0

    // 气泡边框画笔
    private val mBubbleBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
    var arrowOffset: Int = 0

    /**
     * 箭头指向
     */
    enum class Look(var value: Int) {
        /**
         * 坐上右下
         */
        LEFT(1), TOP(2), RIGHT(3), BOTTOM(4);

        companion object {
            fun getType(value: Int): Look {
                var type = Look.BOTTOM

                if (value == 1) {
                    type = Look.LEFT
                } else if (value == 2) {
                    type = Look.TOP
                } else if (value == 3) {
                    type = Look.RIGHT
                } else if (value == 4) {
                    type = Look.BOTTOM
                }

                return type
            }
        }
    }


    fun initPadding() {
        val p = mBubblePadding + mShadowRadius
        if (mLook == Look.BOTTOM) {
            setPadding(p, p, p + mShadowX, mLookLength + p + mShadowY)
        } else if (mLook == Look.TOP) {
            setPadding(p, p + mLookLength, p + mShadowX, p + mShadowY)
        } else if (mLook == Look.LEFT) {
            setPadding(p + mLookLength, p, p + mShadowX, p + mShadowY)
        } else if (mLook == Look.RIGHT) {
            setPadding(p, p, p + mLookLength + mShadowX, p + mShadowY)
        }
    }

    /**
     * 初始化参数
     */
    private fun initAttr() {
        mLook = Look.BOTTOM
        mLookPosition = 0
        mLookWidth = XPopupUtils.dp2px(getContext(), 10f)
        mLookLength = XPopupUtils.dp2px(getContext(), 9f)
        //        mShadowRadius = 0;
        mShadowX = 0
        mShadowY = 0

        mBubbleRadius = XPopupUtils.dp2px(getContext(), 8f)
        mLTR = -1
        mRTR = -1
        mRDR = -1
        mLDR = -1

        mArrowTopLeftRadius = XPopupUtils.dp2px(getContext(), 1f)
        mArrowTopRightRadius = XPopupUtils.dp2px(getContext(), 1f)
        mArrowDownLeftRadius = XPopupUtils.dp2px(getContext(), 1f)
        mArrowDownRightRadius = XPopupUtils.dp2px(getContext(), 1f)

        mBubblePadding = XPopupUtils.dp2px(getContext(), 0f)
        mShadowColor = Color.DKGRAY
        //        mShadowColor = Color.RED;
        mBubbleColor = Color.parseColor("#3b3c3d")

        //        mBubbleBgRes = -1;
//        if (mBubbleBgRes != -1) {
//            mBubbleImageBg = BitmapFactory.decodeResource(getResources(), mBubbleBgRes);
//        }
        mBubbleBorderColor = Color.TRANSPARENT
        mBubbleBorderSize = 0
    }


    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        mWidth = w
        mHeight = h
        initData()
    }

    override fun invalidate() {
        initData()
        super.invalidate()
    }

    override fun postInvalidate() {
        initData()
        super.postInvalidate()
    }

    /**
     * 初始化数据
     */
    private fun initData() {
        initPadding()
        if (lookPositionCentered) {
            mLookPosition =
                if (mLook == Look.LEFT || mLook == Look.RIGHT) (mHeight / 2 - mLookLength / 2) else (mWidth / 2 - mLookWidth / 2)
        }
        mLookPosition += arrowOffset
        //        mPaint.setPathEffect(new CornerPathEffect(mBubbleRadius));
        mPaint.setShadowLayer(
            mShadowRadius.toFloat(),
            mShadowX.toFloat(),
            mShadowY.toFloat(),
            mShadowColor
        )
        mBubbleBorderPaint.setColor(mBubbleBorderColor)
        mBubbleBorderPaint.setStrokeWidth(mBubbleBorderSize.toFloat())
        mBubbleBorderPaint.setStyle(Paint.Style.STROKE)

        mLeft =
            mShadowRadius + (if (mShadowX < 0) -mShadowX else 0) + (if (mLook == Look.LEFT) mLookLength else 0)
        mTop =
            mShadowRadius + (if (mShadowY < 0) -mShadowY else 0) + (if (mLook == Look.TOP) mLookLength else 0)
        mRight =
            mWidth - mShadowRadius + (if (mShadowX > 0) -mShadowX else 0) - (if (mLook == Look.RIGHT) mLookLength else 0)
        mBottom =
            mHeight - mShadowRadius + (if (mShadowY > 0) -mShadowY else 0) - (if (mLook == Look.BOTTOM) mLookLength else 0)
        mPaint.setColor(mBubbleColor)

        mPath.reset()

        var topOffset = if (mLookPosition + mLookLength > mBottom) mBottom - mLookWidth else mLookPosition
        topOffset = max(topOffset, mShadowRadius)
        var leftOffset = if (mLookPosition + mLookLength > mRight) mRight - mLookWidth else mLookPosition
        leftOffset = max(leftOffset, mShadowRadius)

        if (mLook == Look.LEFT) {
            if (topOffset >= getLTR() + mArrowDownRightRadius) {
                mPath.moveTo(mLeft.toFloat(), (topOffset - mArrowDownRightRadius).toFloat())
                mPath.rCubicTo(
                    0f,
                    mArrowDownRightRadius.toFloat(),
                    -mLookLength.toFloat(),
                    mLookWidth / 2f - mArrowTopRightRadius + mArrowDownRightRadius,
                    -mLookLength.toFloat(),
                    mLookWidth / 2f + mArrowDownRightRadius
                )
            } else {
                mPath.moveTo((mLeft - mLookLength).toFloat(), topOffset + mLookWidth / 2f)
            }

            if (topOffset + mLookWidth < mBottom - getLDR() - mArrowDownLeftRadius) {
                mPath.rCubicTo(
                    0f, mArrowTopLeftRadius.toFloat(),
                    mLookLength.toFloat(), mLookWidth / 2f,
                    mLookLength.toFloat(), mLookWidth / 2f + mArrowDownLeftRadius
                )
                mPath.lineTo(mLeft.toFloat(), (mBottom - getLDR()).toFloat())
            }
            mPath.quadTo(
                mLeft.toFloat(),
                mBottom.toFloat(),
                (mLeft + getLDR()).toFloat(),
                mBottom.toFloat()
            )
            mPath.lineTo((mRight - getRDR()).toFloat(), mBottom.toFloat())
            mPath.quadTo(
                mRight.toFloat(),
                mBottom.toFloat(),
                mRight.toFloat(),
                (mBottom - getRDR()).toFloat()
            )
            mPath.lineTo(mRight.toFloat(), (mTop + getRTR()).toFloat())
            mPath.quadTo(
                mRight.toFloat(),
                mTop.toFloat(),
                (mRight - getRTR()).toFloat(),
                mTop.toFloat()
            )
            mPath.lineTo((mLeft + getLTR()).toFloat(), mTop.toFloat())
            if (topOffset >= getLTR() + mArrowDownRightRadius) {
                mPath.quadTo(
                    mLeft.toFloat(),
                    mTop.toFloat(),
                    mLeft.toFloat(),
                    (mTop + getLTR()).toFloat()
                )
            } else {
                mPath.quadTo(
                    mLeft.toFloat(),
                    mTop.toFloat(),
                    (mLeft - mLookLength).toFloat(),
                    topOffset + mLookWidth / 2f
                )
            }
        } else if (mLook == Look.TOP) {
            if (leftOffset >= getLTR() + mArrowDownLeftRadius) {
                mPath.moveTo((leftOffset - mArrowDownLeftRadius).toFloat(), mTop.toFloat())
                mPath.rCubicTo(
                    mArrowDownLeftRadius.toFloat(),
                    0f,
                    mLookWidth / 2f - mArrowTopLeftRadius + mArrowDownLeftRadius,
                    -mLookLength.toFloat(),
                    mLookWidth / 2f + mArrowDownLeftRadius,
                    -mLookLength.toFloat()
                )
            } else {
                mPath.moveTo(leftOffset + mLookWidth / 2f, (mTop - mLookLength).toFloat())
            }

            if (leftOffset + mLookWidth < mRight - getRTR() - mArrowDownRightRadius) {
                mPath.rCubicTo(
                    mArrowTopRightRadius.toFloat(), 0f,
                    mLookWidth / 2f, mLookLength.toFloat(),
                    mLookWidth / 2f + mArrowDownRightRadius, mLookLength.toFloat()
                )
                mPath.lineTo((mRight - getRTR()).toFloat(), mTop.toFloat())
            }
            mPath.quadTo(
                mRight.toFloat(),
                mTop.toFloat(),
                mRight.toFloat(),
                (mTop + getRTR()).toFloat()
            )
            mPath.lineTo(mRight.toFloat(), (mBottom - getRDR()).toFloat())
            mPath.quadTo(
                mRight.toFloat(),
                mBottom.toFloat(),
                (mRight - getRDR()).toFloat(),
                mBottom.toFloat()
            )
            mPath.lineTo((mLeft + getLDR()).toFloat(), mBottom.toFloat())
            mPath.quadTo(
                mLeft.toFloat(),
                mBottom.toFloat(),
                mLeft.toFloat(),
                (mBottom - getLDR()).toFloat()
            )
            mPath.lineTo(mLeft.toFloat(), (mTop + getLTR()).toFloat())
            if (leftOffset >= getLTR() + mArrowDownLeftRadius) {
                mPath.quadTo(
                    mLeft.toFloat(),
                    mTop.toFloat(),
                    (mLeft + getLTR()).toFloat(),
                    mTop.toFloat()
                )
            } else {
                mPath.quadTo(
                    mLeft.toFloat(),
                    mTop.toFloat(),
                    leftOffset + mLookWidth / 2f,
                    (mTop - mLookLength).toFloat()
                )
            }
        } else if (mLook == Look.RIGHT) {
            if (topOffset >= getRTR() + mArrowDownLeftRadius) {
                mPath.moveTo(mRight.toFloat(), (topOffset - mArrowDownLeftRadius).toFloat())
                mPath.rCubicTo(
                    0f,
                    mArrowDownLeftRadius.toFloat(),
                    mLookLength.toFloat(),
                    mLookWidth / 2f - mArrowTopLeftRadius + mArrowDownLeftRadius,
                    mLookLength.toFloat(),
                    mLookWidth / 2f + mArrowDownLeftRadius
                )
            } else {
                mPath.moveTo((mRight + mLookLength).toFloat(), topOffset + mLookWidth / 2f)
            }

            if (topOffset + mLookWidth < mBottom - getRDR() - mArrowDownRightRadius) {
                mPath.rCubicTo(
                    0f, mArrowTopRightRadius.toFloat(),
                    -mLookLength.toFloat(), mLookWidth / 2f,
                    -mLookLength.toFloat(), mLookWidth / 2f + mArrowDownRightRadius
                )
                mPath.lineTo(mRight.toFloat(), (mBottom - getRDR()).toFloat())
            }
            mPath.quadTo(
                mRight.toFloat(),
                mBottom.toFloat(),
                (mRight - getRDR()).toFloat(),
                mBottom.toFloat()
            )
            mPath.lineTo((mLeft + getLDR()).toFloat(), mBottom.toFloat())
            mPath.quadTo(
                mLeft.toFloat(),
                mBottom.toFloat(),
                mLeft.toFloat(),
                (mBottom - getLDR()).toFloat()
            )
            mPath.lineTo(mLeft.toFloat(), (mTop + getLTR()).toFloat())
            mPath.quadTo(
                mLeft.toFloat(),
                mTop.toFloat(),
                (mLeft + getLTR()).toFloat(),
                mTop.toFloat()
            )
            mPath.lineTo((mRight - getRTR()).toFloat(), mTop.toFloat())
            if (topOffset >= getRTR() + mArrowDownLeftRadius) {
                mPath.quadTo(
                    mRight.toFloat(),
                    mTop.toFloat(),
                    mRight.toFloat(),
                    (mTop + getRTR()).toFloat()
                )
            } else {
                mPath.quadTo(
                    mRight.toFloat(),
                    mTop.toFloat(),
                    (mRight + mLookLength).toFloat(),
                    topOffset + mLookWidth / 2f
                )
            }
        } else if (mLook == Look.BOTTOM) {
            if (leftOffset >= getLDR() + mArrowDownRightRadius) {
                mPath.moveTo((leftOffset - mArrowDownRightRadius).toFloat(), mBottom.toFloat())
                mPath.rCubicTo(
                    mArrowDownRightRadius.toFloat(),
                    0f,
                    mLookWidth / 2f - mArrowTopRightRadius + mArrowDownRightRadius,
                    mLookLength.toFloat(),
                    mLookWidth / 2f + mArrowDownRightRadius,
                    mLookLength.toFloat()
                )
            } else {
                mPath.moveTo(leftOffset + mLookWidth / 2f, (mBottom + mLookLength).toFloat())
            }

            if (leftOffset + mLookWidth < mRight - getRDR() - mArrowDownLeftRadius) {
                mPath.rCubicTo(
                    mArrowTopLeftRadius.toFloat(), 0f,
                    mLookWidth / 2f, -mLookLength.toFloat(),
                    mLookWidth / 2f + mArrowDownLeftRadius, -mLookLength.toFloat()
                )
                mPath.lineTo((mRight - getRDR()).toFloat(), mBottom.toFloat())
            }
            mPath.quadTo(
                mRight.toFloat(),
                mBottom.toFloat(),
                mRight.toFloat(),
                (mBottom - getRDR()).toFloat()
            )
            mPath.lineTo(mRight.toFloat(), (mTop + getRTR()).toFloat())
            mPath.quadTo(
                mRight.toFloat(),
                mTop.toFloat(),
                (mRight - getRTR()).toFloat(),
                mTop.toFloat()
            )
            mPath.lineTo((mLeft + getLTR()).toFloat(), mTop.toFloat())
            mPath.quadTo(
                mLeft.toFloat(),
                mTop.toFloat(),
                mLeft.toFloat(),
                (mTop + getLTR()).toFloat()
            )
            mPath.lineTo(mLeft.toFloat(), (mBottom - getLDR()).toFloat())
            if (leftOffset >= getLDR() + mArrowDownRightRadius) {
                mPath.quadTo(
                    mLeft.toFloat(),
                    mBottom.toFloat(),
                    (mLeft + getLDR()).toFloat(),
                    mBottom.toFloat()
                )
            } else {
                mPath.quadTo(
                    mLeft.toFloat(),
                    mBottom.toFloat(),
                    leftOffset + mLookWidth / 2f,
                    (mBottom + mLookLength).toFloat()
                )
            }
        }


        mPath.close()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawPath(mPath, mPaint)
        if (mBubbleImageBg != null) {
            mPath.computeBounds(mBubbleImageBgDstRectF, true)
            val layer = canvas.saveLayer(mBubbleImageBgDstRectF, null, Canvas.ALL_SAVE_FLAG)
            canvas.drawPath(mPath, mBubbleImageBgBeforePaint)

            val dstRatio = mBubbleImageBgDstRectF.width() / mBubbleImageBgDstRectF.height()
            val imgRatio = mBubbleImageBg!!.getWidth() * 1f / mBubbleImageBg!!.getHeight()
            if (dstRatio > imgRatio) {
                val top =
                    ((mBubbleImageBg!!.getHeight() - mBubbleImageBg!!.getWidth() / dstRatio) / 2).toInt()
                val bottom = top + (mBubbleImageBg!!.getWidth() / dstRatio).toInt()
                mBubbleImageBgSrcRect.set(0, top, mBubbleImageBg!!.getWidth(), bottom)
            } else {
                val left =
                    ((mBubbleImageBg!!.getWidth() - mBubbleImageBg!!.getHeight() * dstRatio) / 2).toInt()
                val width = left + (mBubbleImageBg!!.getHeight() * dstRatio).toInt()
                mBubbleImageBgSrcRect.set(left, 0, width, mBubbleImageBg!!.getHeight())
            }

            canvas.drawBitmap(
                mBubbleImageBg!!,
                mBubbleImageBgSrcRect,
                mBubbleImageBgDstRectF,
                mBubbleImageBgPaint
            )
            canvas.restoreToCount(layer)
        }

        if (mBubbleBorderSize != 0) {
            canvas.drawPath(mPath, mBubbleBorderPaint)
        }
    }

    //    @Override
    //    public boolean onTouchEvent(MotionEvent event) {
    //        if (event.getAction() == MotionEvent.ACTION_DOWN) {
    //            RectF r = new RectF();
    //            mPath.computeBounds(r, true);
    //            mRegion.setPath(mPath, new Region((int) r.left, (int) r.top, (int) r.right, (int) r.bottom));
    //            if (!mRegion.contains((int) event.getX(), (int) event.getY()) && mListener != null) {
    //                mListener.edge();
    //            }
    //        }
    //        return super.onTouchEvent(event);
    //    }
    fun getPaint(): Paint {
        return mPaint
    }

    fun getPath(): Path {
        return mPath
    }

    fun getLook(): Look? {
        return mLook
    }

    fun getLookPosition(): Int {
        return mLookPosition
    }

    fun getLookWidth(): Int {
        return mLookWidth
    }

    fun getLookLength(): Int {
        return mLookLength
    }

    fun getShadowColor(): Int {
        return mShadowColor
    }

    fun getShadowRadius(): Int {
        return mShadowRadius
    }

    fun getShadowX(): Int {
        return mShadowX
    }

    fun getShadowY(): Int {
        return mShadowY
    }

    fun getBubbleRadius(): Int {
        return mBubbleRadius
    }

    fun getBubbleColor(): Int {
        return mBubbleColor
    }

    fun setBubbleColor(mBubbleColor: Int) {
        this.mBubbleColor = mBubbleColor
    }

    fun setLook(mLook: Look?) {
        this.mLook = mLook
        initPadding()
    }

    fun setLookPosition(mLookPosition: Int) {
        this.mLookPosition = mLookPosition
    }

    private var lookPositionCentered: Boolean = false

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        setWillNotDraw(false)
        initAttr()
        mPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
        mPaint.setStyle(Paint.Style.FILL)
        mPath = Path()
        mBubbleImageBgPaint.setXfermode(PorterDuffXfermode(PorterDuff.Mode.SRC_IN))
    }

    fun setLookPositionCenter(isCenter: Boolean) {
        this.lookPositionCentered = isCenter
    }

    fun setLookWidth(mLookWidth: Int) {
        this.mLookWidth = mLookWidth
    }

    fun setLookLength(mLookLength: Int) {
        this.mLookLength = mLookLength
        initPadding()
    }

    fun setShadowColor(mShadowColor: Int) {
        this.mShadowColor = mShadowColor
    }

    fun setShadowRadius(mShadowRadius: Int) {
        this.mShadowRadius = mShadowRadius
    }

    fun setShadowX(mShadowX: Int) {
        this.mShadowX = mShadowX
    }

    fun setShadowY(mShadowY: Int) {
        this.mShadowY = mShadowY
    }

    fun setBubbleRadius(mBubbleRadius: Int) {
        this.mBubbleRadius = mBubbleRadius
    }

    fun getLTR(): Int {
        return if (mLTR == -1) mBubbleRadius else mLTR
    }

    fun setLTR(mLTR: Int) {
        this.mLTR = mLTR
    }

    fun getRTR(): Int {
        return if (mRTR == -1) mBubbleRadius else mRTR
    }

    fun setRTR(mRTR: Int) {
        this.mRTR = mRTR
    }

    fun getRDR(): Int {
        return if (mRDR == -1) mBubbleRadius else mRDR
    }

    fun setRDR(mRDR: Int) {
        this.mRDR = mRDR
    }

    fun getLDR(): Int {
        return if (mLDR == -1) mBubbleRadius else mLDR
    }

    fun setLDR(mLDR: Int) {
        this.mLDR = mLDR
    }

    fun getArrowTopLeftRadius(): Int {
        return mArrowTopLeftRadius
    }

    fun setArrowTopLeftRadius(mArrowTopLeftRadius: Int) {
        this.mArrowTopLeftRadius = mArrowTopLeftRadius
    }

    fun getArrowTopRightRadius(): Int {
        return mArrowTopRightRadius
    }

    fun setArrowTopRightRadius(mArrowTopRightRadius: Int) {
        this.mArrowTopRightRadius = mArrowTopRightRadius
    }

    fun getArrowDownLeftRadius(): Int {
        return mArrowDownLeftRadius
    }

    fun setArrowDownLeftRadius(mArrowDownLeftRadius: Int) {
        this.mArrowDownLeftRadius = mArrowDownLeftRadius
    }

    fun getArrowDownRightRadius(): Int {
        return mArrowDownRightRadius
    }

    fun setArrowDownRightRadius(mArrowDownRightRadius: Int) {
        this.mArrowDownRightRadius = mArrowDownRightRadius
    }

    fun setArrowRadius(radius: Int) {
        setArrowDownLeftRadius(radius)
        setArrowDownRightRadius(radius)
        setArrowTopLeftRadius(radius)
        setArrowTopRightRadius(radius)
    }

    fun setBubblePadding(bubblePadding: Int) {
        this.mBubblePadding = bubblePadding
    }

    /**
     * 设置背景图片
     * @param bitmap 图片
     */
    fun setBubbleImageBg(bitmap: Bitmap?) {
        mBubbleImageBg = bitmap
    }

    /**
     * 设置背景图片资源
     * @param res 图片资源
     */
    fun setBubbleImageBgRes(res: Int) {
        mBubbleImageBg = BitmapFactory.decodeResource(getResources(), res)
    }

    fun setBubbleBorderSize(bubbleBorderSize: Int) {
        this.mBubbleBorderSize = bubbleBorderSize
    }

    fun setBubbleBorderColor(bubbleBorderColor: Int) {
        this.mBubbleBorderColor = bubbleBorderColor
    }

    public override fun onSaveInstanceState(): Parcelable {
        val bundle = Bundle()
        bundle.putParcelable("instanceState", super.onSaveInstanceState())
        bundle.putInt("mLookPosition", this.mLookPosition)
        bundle.putInt("mLookWidth", this.mLookWidth)
        bundle.putInt("mLookLength", this.mLookLength)
        bundle.putInt("mShadowColor", this.mShadowColor)
        bundle.putInt("mShadowRadius", this.mShadowRadius)
        bundle.putInt("mShadowX", this.mShadowX)
        bundle.putInt("mShadowY", this.mShadowY)
        bundle.putInt("mBubbleRadius", this.mBubbleRadius)

        bundle.putInt("mLTR", this.mLTR)
        bundle.putInt("mRTR", this.mRTR)
        bundle.putInt("mRDR", this.mRDR)
        bundle.putInt("mLDR", this.mLDR)

        bundle.putInt("mBubblePadding", this.mBubblePadding)

        bundle.putInt("mArrowTopLeftRadius", this.mArrowTopLeftRadius)
        bundle.putInt("mArrowTopRightRadius", this.mArrowTopRightRadius)
        bundle.putInt("mArrowDownLeftRadius", this.mArrowDownLeftRadius)
        bundle.putInt("mArrowDownRightRadius", this.mArrowDownRightRadius)

        bundle.putInt("mWidth", this.mWidth)
        bundle.putInt("mHeight", this.mHeight)
        bundle.putInt("mLeft", this.mLeft)
        bundle.putInt("mTop", this.mTop)
        bundle.putInt("mRight", this.mRight)
        bundle.putInt("mBottom", this.mBottom)

        bundle.putInt("mBubbleBgRes", this.mBubbleBgRes)

        bundle.putInt("mBubbleBorderColor", this.mBubbleBorderColor)
        bundle.putInt("mBubbleBorderSize", this.mBubbleBorderSize)
        return bundle
    }

    //    private int mWidth, mHeight;
    //    private int mLeft, mTop, mRight, mBottom;
    public override fun onRestoreInstanceState(state: Parcelable?) {
        if (state is Bundle) {
            val bundle = state
            this.mLookPosition = bundle.getInt("mLookPosition")
            this.mLookWidth = bundle.getInt("mLookWidth")
            this.mLookLength = bundle.getInt("mLookLength")
            this.mShadowColor = bundle.getInt("mShadowColor")
            this.mShadowRadius = bundle.getInt("mShadowRadius")
            this.mShadowX = bundle.getInt("mShadowX")
            this.mShadowY = bundle.getInt("mShadowY")
            this.mBubbleRadius = bundle.getInt("mBubbleRadius")

            this.mLTR = bundle.getInt("mLTR")
            this.mRTR = bundle.getInt("mRTR")
            this.mRDR = bundle.getInt("mRDR")
            this.mLDR = bundle.getInt("mLDR")

            this.mBubblePadding = bundle.getInt("mBubblePadding")

            this.mArrowTopLeftRadius = bundle.getInt("mArrowTopLeftRadius")
            this.mArrowTopRightRadius = bundle.getInt("mArrowTopRightRadius")
            this.mArrowDownLeftRadius = bundle.getInt("mArrowDownLeftRadius")
            this.mArrowDownRightRadius = bundle.getInt("mArrowDownRightRadius")

            this.mWidth = bundle.getInt("mWidth")
            this.mHeight = bundle.getInt("mHeight")
            this.mLeft = bundle.getInt("mLeft")
            this.mTop = bundle.getInt("mTop")
            this.mRight = bundle.getInt("mRight")
            this.mBottom = bundle.getInt("mBottom")
            this.mBubbleBgRes = bundle.getInt("mBubbleBgRes")

            if (this.mBubbleBgRes != -1) {
                mBubbleImageBg = BitmapFactory.decodeResource(getResources(), mBubbleBgRes)
            }

            this.mBubbleBorderSize = bundle.getInt("mBubbleBorderSize")
            this.mBubbleBorderColor = bundle.getInt("mBubbleBorderColor")
            super.onRestoreInstanceState(bundle.getParcelable<Parcelable?>("instanceState"))
            return
        }
        super.onRestoreInstanceState(state)
    } //
    //    public void setOnClickEdgeListener(OnClickEdgeListener l) {
    //        this.mListener = l;
    //    }
    //
    //    /**
    //     * 触摸到气泡的边缘
    //     */
    //    public interface OnClickEdgeListener {
    //        void edge();
    //    }
}
