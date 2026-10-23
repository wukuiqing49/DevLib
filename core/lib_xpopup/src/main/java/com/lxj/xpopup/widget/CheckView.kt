package com.lxj.xpopup.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import com.lxj.xpopup.util.XPopupUtils

/**
 * Description: 对勾View
 * Create by dance, at 2018/12/21
 */
class CheckView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {
    var paint: Paint
    private var checkColor: Int = Color.TRANSPARENT

    /**
     * 设置对勾View
     * @param color
     */
    fun setColor(color: Int) {
        this.checkColor = color
        paint.setColor(color)
        postInvalidate()
    }

    var path: Path = Path()

    init {
        paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.setStrokeWidth(XPopupUtils.dp2px(context, 2f).toFloat())
        paint.setStyle(Paint.Style.STROKE)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (checkColor == Color.TRANSPARENT) return
        // first part
        path.moveTo(getMeasuredWidth() / 4f, getMeasuredHeight() / 2f)
        path.lineTo(getMeasuredWidth() / 2f, getMeasuredHeight() * 3 / 4f)
        // second part
        path.lineTo(getMeasuredWidth().toFloat(), getMeasuredHeight() / 4f)
        canvas.drawPath(path, paint)
    }
}
