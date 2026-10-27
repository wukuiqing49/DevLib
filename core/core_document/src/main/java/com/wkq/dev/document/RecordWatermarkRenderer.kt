package com.wkq.dev.document

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import java.io.File

/** 将水印绘入透明页面图，三格式共用角度、透明度、大小和相对位置。 */
internal object RecordWatermarkRenderer {
    /** 输出透明 PNG；图片缩采样解码，文字按宽度缩放，所有位图在 finally 释放。 */
    fun render(template: RecordTemplate, output: File): File? {
        val w = template.watermark
        if (w.kind == WatermarkKind.NONE) return null
        val width = if (template.style.landscape) 842 else 595
        val height = if (template.style.landscape) 595 else 842
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            val x = width * w.xPercent / 100f; val y = height * w.yPercent / 100f
            canvas.rotate(w.rotationDegrees.toFloat(), x, y)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                color = Color.parseColor("#${template.style.primaryColor}"); alpha = w.opacityPercent * 255 / 100
            }
            val desired = width * w.widthPercent / 100f
            if (w.kind == WatermarkKind.TEXT) {
                paint.textSize = 48f
                paint.textSize *= (desired / paint.measureText(w.text).coerceAtLeast(1f)).coerceAtMost(2f)
                paint.textAlign = Paint.Align.CENTER
                canvas.drawText(w.text, x, y - (paint.ascent() + paint.descent()) / 2, paint)
            } else {
                val file = checkNotNull(w.image); val bounds = RecordPdfWriter.imageBounds(file)
                var sample = 1
                while (maxOf(bounds.first, bounds.second) / sample > 1200) sample *= 2
                val image = requireNotNull(BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }))
                try {
                    val scale = minOf(desired / image.width, height * 0.7f / image.height)
                    val iw = image.width * scale; val ih = image.height * scale
                    canvas.drawBitmap(image, null, RectF(x - iw / 2, y - ih / 2, x + iw / 2, y + ih / 2), paint)
                } finally { image.recycle() }
            }
            output.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            return output
        } finally { bitmap.recycle() }
    }
}
