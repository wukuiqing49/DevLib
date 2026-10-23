package com.wkq.photo.qrcode

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import kotlin.math.min

object QRBackgroundProcessor {
    fun draw(
        canvas: Canvas,
        image: Bitmap,
        destination: RectF,
        veilColor: Int,
        veilAlpha: Float
    ) {
        require(veilAlpha in 0f..1f)
        val sourceSide = min(image.width, image.height)
        val source = Rect(
            (image.width - sourceSide) / 2,
            (image.height - sourceSide) / 2,
            (image.width + sourceSide) / 2,
            (image.height + sourceSide) / 2
        )
        canvas.drawBitmap(image, source, destination, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        val adaptiveVeilColor = blend(representativeColor(image), veilColor, 0.9f)
        canvas.drawRect(destination, Paint().apply {
            style = Paint.Style.FILL
            color = adaptiveVeilColor
            alpha = (Color.alpha(adaptiveVeilColor) * veilAlpha).toInt().coerceIn(0, 255)
        })
    }

    /** 使用稀疏采样避免为了取色创建额外 Bitmap。 */
    fun representativeColor(image: Bitmap): Int {
        val stepX = (image.width / 24).coerceAtLeast(1)
        val stepY = (image.height / 24).coerceAtLeast(1)
        var red = 0L
        var green = 0L
        var blue = 0L
        var count = 0L
        for (y in 0 until image.height step stepY) {
            for (x in 0 until image.width step stepX) {
                val color = image.getPixel(x, y)
                red += Color.red(color)
                green += Color.green(color)
                blue += Color.blue(color)
                count++
            }
        }
        return if (count == 0L) Color.WHITE else Color.rgb((red / count).toInt(), (green / count).toInt(), (blue / count).toInt())
    }

    private fun blend(first: Int, second: Int, secondWeight: Float): Int {
        val firstWeight = 1f - secondWeight
        return Color.rgb(
            (Color.red(first) * firstWeight + Color.red(second) * secondWeight).toInt(),
            (Color.green(first) * firstWeight + Color.green(second) * secondWeight).toInt(),
            (Color.blue(first) * firstWeight + Color.blue(second) * secondWeight).toInt()
        )
    }
}
