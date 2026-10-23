package com.wkq.photo.qrcode

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import kotlin.math.min

object QRLogoProcessor {
    private const val LOGO_RATIO = 0.17f
    private const val PROTECTION_RATIO = 0.22f

    fun draw(canvas: Canvas, logo: Bitmap, qrBounds: RectF, moduleSize: Float) {
        val qrSide = min(qrBounds.width(), qrBounds.height())
        val logoSide = alignDown(qrSide * LOGO_RATIO, moduleSize).coerceAtLeast(moduleSize * 3f)
        val protectionSide = alignUp(qrSide * PROTECTION_RATIO, moduleSize)
        val protection = centeredRect(qrBounds.centerX(), qrBounds.centerY(), protectionSide)
        val target = centeredRect(qrBounds.centerX(), qrBounds.centerY(), logoSide)

        canvas.drawRoundRect(
            protection,
            moduleSize * 0.9f,
            moduleSize * 0.9f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = Color.WHITE
            }
        )
        val sourceSide = min(logo.width, logo.height)
        val source = Rect(
            (logo.width - sourceSide) / 2,
            (logo.height - sourceSide) / 2,
            (logo.width + sourceSide) / 2,
            (logo.height + sourceSide) / 2
        )
        canvas.drawBitmap(logo, source, target, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
    }

    private fun centeredRect(centerX: Float, centerY: Float, side: Float): RectF = RectF(
        centerX - side / 2f,
        centerY - side / 2f,
        centerX + side / 2f,
        centerY + side / 2f
    )

    private fun alignDown(value: Float, unit: Float): Float = (value / unit).toInt().coerceAtLeast(1) * unit

    private fun alignUp(value: Float, unit: Float): Float = kotlin.math.ceil(value / unit).toFloat() * unit
}
