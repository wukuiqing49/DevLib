package com.lxj.xpopup.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * Description: 大图浏览弹窗显示后的占位View
 * Create by lxj, at 2019/2/2
 */
class BlankView : View {
    constructor(context: Context?) : super(context)

    constructor(context: Context?, attrs: AttributeSet?) : super(context, attrs) {
        init()
    }

    constructor(context: Context?, attrs: AttributeSet?, defStyleAttr: Int) : super(
        context,
        attrs,
        defStyleAttr
    ) {
        init()
    }

    private val paint = Paint()
    private var rect: RectF? = null
    var radius: Int = 0
    var color: Int = Color.WHITE
    var strokeColor: Int = Color.parseColor("#DDDDDD")

    private fun init() {
        paint.setAntiAlias(true)
        paint.setStrokeWidth(1f)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rect = RectF(0f, 0f, getMeasuredWidth().toFloat(), getMeasuredHeight().toFloat())
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        paint.setColor(color)
        canvas.drawRoundRect(rect!!, radius.toFloat(), radius.toFloat(), paint)

        paint.setStyle(Paint.Style.STROKE)
        paint.setColor(strokeColor)
        canvas.drawRoundRect(rect!!, radius.toFloat(), radius.toFloat(), paint)
        paint.setStyle(Paint.Style.FILL)
    }
}
