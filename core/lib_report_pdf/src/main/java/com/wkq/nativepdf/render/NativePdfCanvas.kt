package com.wkq.nativepdf.render

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import android.text.TextUtils
import androidx.exifinterface.media.ExifInterface
import androidx.core.graphics.withSave
import java.io.File

internal fun Canvas.drawTextLines(
    text: String,
    x: Float,
    startY: Float,
    maxWidth: Float,
    paint: Paint,
    lineHeight: Float
): Float {
    if (maxWidth <= 0f) return startY
    val layout = text.ifBlank { " " }.buildPdfTextLayout(maxWidth, paint, lineHeight)
    val height = layout.pdfTextHeight(lineHeight)
    withSave {
        translate(x, startY + paint.ascent())
        layout.draw(this)
    }
    return startY + height
}

private fun String.buildPdfTextLayout(maxWidth: Float, paint: Paint, lineHeight: Float): StaticLayout {
    val textPaint = TextPaint(paint).apply {
        isAntiAlias = true
    }
    val spacingExtra = (lineHeight - textPaint.fontSpacing).coerceAtLeast(0f)
    return StaticLayout.Builder
        .obtain(this, 0, length, textPaint, maxWidth.toInt().coerceAtLeast(1))
        .setAlignment(Layout.Alignment.ALIGN_NORMAL)
        .setTextDirection(TextDirectionHeuristics.FIRSTSTRONG_LTR)
        .setLineSpacing(spacingExtra, 1f)
        .setIncludePad(false)
        .build()
}

private fun StaticLayout.pdfTextHeight(lineHeight: Float): Float {
    return height.toFloat().coerceAtLeast(lineHeight)
}

internal fun Canvas.drawSingleLineEllipsized(
    text: String,
    x: Float,
    baseline: Float,
    maxWidth: Float,
    paint: Paint
) {
    if (text.isBlank() || maxWidth <= 0f) return
    val safeText = TextUtils.ellipsize(
        text.replace('\n', ' '),
        TextPaint(paint),
        maxWidth,
        TextUtils.TruncateAt.END
    ).toString()
    val originalAlign = paint.textAlign
    if (safeText.isRightToLeftText() && originalAlign == Paint.Align.LEFT) {
        paint.textAlign = Paint.Align.RIGHT
        drawText(safeText, x + maxWidth, baseline, paint)
        paint.textAlign = originalAlign
    } else {
        drawText(safeText, x, baseline, paint)
    }
}

internal fun String.measureTextHeight(maxWidth: Float, paint: Paint, lineHeight: Float): Float {
    if (isBlank()) return lineHeight
    if (maxWidth <= 0f) return lineHeight
    return buildPdfTextLayout(maxWidth, paint, lineHeight).pdfTextHeight(lineHeight)
}

internal fun String.wrapLines(maxWidth: Float, paint: Paint): List<String> {
    if (isBlank()) return emptyList()
    val result = mutableListOf<String>()
    var line = ""
    val tokens = Regex("""\S+\s*|\s+""").findAll(this).map { it.value }.toList()
    val source = tokens.ifEmpty { listOf(this) }
    source.forEach { token ->
        val next = line + token
        if (paint.measureText(next) <= maxWidth || line.isEmpty()) {
            line = next.trimStart()
        } else if (paint.measureText(token.trim()) <= maxWidth) {
            result += line.trimEnd()
            line = token.trimStart()
        } else {
            if (line.isNotBlank()) {
                result += line.trimEnd()
                line = ""
            }
            token.trim().forEach { char ->
                val charNext = line + char
                if (paint.measureText(charNext) <= maxWidth || line.isEmpty()) {
                    line = charNext
                } else {
                    result += line
                    line = char.toString()
                }
            }
        }
    }
    if (line.isNotBlank()) result += line.trimEnd()
    return result
}

private fun String.ellipsizeToWidth(maxWidth: Float, paint: Paint): String {
    if (paint.measureText(this) <= maxWidth) return this
    val ellipsis = "..."
    val ellipsisWidth = paint.measureText(ellipsis)
    val textWidth = maxWidth - ellipsisWidth
    if (textWidth <= 0f) return ellipsis
    val prefix = wrapLines(textWidth, paint).firstOrNull().orEmpty()
    return if (prefix.isBlank()) ellipsis else prefix + ellipsis
}

internal fun Canvas.drawCoverImage(filePath: String?, dst: RectF, radius: Float, paint: Paint): Boolean {
    val bitmap = filePath?.decodeSampledBitmap(
        (dst.width() * pdfImageScale()).toInt(),
        (dst.height() * pdfImageScale()).toInt()
    ) ?: return false
    try {
        val bitmapPaint = paint.highQualityBitmapPaint()
        val src = bitmap.centerCropSource(dst.width() / dst.height())
        withSave {
            clipPath(Path().apply {
                addRoundRect(dst, radius, radius, Path.Direction.CW)
            })
            drawBitmap(bitmap, src, dst, bitmapPaint)
        }
        return true
    } finally {
        bitmap.recycle()
    }
}

/** 按 Unicode 首个强方向判断，数字与标点不会错误切换整段方向。 */
private fun String.isRightToLeftText(): Boolean {
    for (char in this) {
        when (Character.getDirectionality(char)) {
            Character.DIRECTIONALITY_RIGHT_TO_LEFT,
            Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC -> return true
            Character.DIRECTIONALITY_LEFT_TO_RIGHT -> return false
        }
    }
    return false
}

internal fun Canvas.drawFitImage(filePath: String?, dst: RectF, paint: Paint, radius: Float = 8f): Boolean {
    val bitmap = filePath?.decodeSampledBitmap(
        (dst.width() * pdfImageScale()).toInt(),
        (dst.height() * pdfImageScale()).toInt()
    ) ?: return false
    try {
        val src = bitmap.centerCropSource(dst.width() / dst.height())
        withSave {
            clipPath(Path().apply {
                addRoundRect(dst, radius, radius, Path.Direction.CW)
            })
            drawBitmap(bitmap, src, dst, paint.highQualityBitmapPaint())
        }
        return true
    } finally {
        bitmap.recycle()
    }
}

internal fun Canvas.drawContainImage(
    filePath: String?,
    dst: RectF,
    paint: Paint,
    radius: Float = 8f
): Boolean {
    val bitmap = filePath?.decodeSampledBitmap(
        (dst.width() * pdfImageScale()).toInt(),
        (dst.height() * pdfImageScale()).toInt()
    ) ?: return false
    try {
        val sourceRatio = bitmap.width / bitmap.height.toFloat()
        val targetRatio = dst.width() / dst.height()
        val target = if (sourceRatio > targetRatio) {
            val height = dst.width() / sourceRatio
            RectF(dst.left, dst.centerY() - height / 2f, dst.right, dst.centerY() + height / 2f)
        } else {
            val width = dst.height() * sourceRatio
            RectF(dst.centerX() - width / 2f, dst.top, dst.centerX() + width / 2f, dst.bottom)
        }
        drawRoundRect(dst, radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(245, 247, 250)
            style = Paint.Style.FILL
        })
        withSave {
            clipPath(Path().apply {
                addRoundRect(dst, radius, radius, Path.Direction.CW)
            })
            drawBitmap(bitmap, null, target, paint.highQualityBitmapPaint())
        }
        return true
    } finally {
        bitmap.recycle()
    }
}

internal fun Canvas.drawImagePlaceholder(dst: RectF, label: String, radius: Float = 8f) {
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(245, 247, 250)
        style = Paint.Style.FILL
    }
    val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(210, 216, 226)
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }
    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(100, 116, 139)
        textSize = 9f
        textAlign = Paint.Align.CENTER
    }
    drawRoundRect(dst, radius, radius, fill)
    drawRoundRect(dst, radius, radius, stroke)
    drawLine(dst.left + 12f, dst.top + 12f, dst.right - 12f, dst.bottom - 12f, stroke)
    drawLine(dst.right - 12f, dst.top + 12f, dst.left + 12f, dst.bottom - 12f, stroke)
    if (label.isNotBlank()) {
        val text = label.replace('\n', ' ').ellipsizeToWidth(dst.width() - 24f, textPaint)
        drawText(text, dst.centerX(), dst.centerY(), textPaint)
    }
}

internal fun imageAspectRatio(filePath: String?): Float? {
    val file = filePath?.takeIf { it.isNotBlank() }?.let(::File) ?: return null
    if (!file.isFile) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val orientation = file.readExifOrientation()
    val rotated = orientation == ExifInterface.ORIENTATION_ROTATE_90 ||
        orientation == ExifInterface.ORIENTATION_ROTATE_270 ||
        orientation == ExifInterface.ORIENTATION_TRANSPOSE ||
        orientation == ExifInterface.ORIENTATION_TRANSVERSE
    val width = if (rotated) bounds.outHeight else bounds.outWidth
    val height = if (rotated) bounds.outWidth else bounds.outHeight
    return width / height.toFloat()
}

private fun String.decodeSampledBitmap(reqWidth: Int, reqHeight: Int): Bitmap? {
    val file = File(this)
    if (!file.exists()) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val sampleSize = pdfImageSampleSize(bounds.outWidth, bounds.outHeight, reqWidth, reqHeight)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && !file.hasExifTransform()) {
        runCatching {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, _, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.setTargetSampleSize(sampleSize)
            }
        }.getOrElse {
            if (it is Error) throw it
            null
        }?.let { decoded ->
            // ImageDecoder 已按 EXIF 输出正确方向，不能再次手动旋转。
            return decoded
        }
    }
    val decoded = BitmapFactory.decodeFile(
        file.absolutePath,
        BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
    ) ?: return null
    return decoded.rotateForExif(file)
}

private fun Paint.highQualityBitmapPaint(): Paint = Paint(this).apply {
    isAntiAlias = true
    isFilterBitmap = true
    isDither = true
}

private fun Bitmap.rotateForExif(file: File): Bitmap {
    val orientation = file.readExifOrientation()
    val matrix = Matrix()
    when (orientation) {
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
        ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> {
            matrix.setRotate(180f)
            matrix.postScale(-1f, 1f)
        }
        ExifInterface.ORIENTATION_TRANSPOSE -> {
            matrix.setRotate(90f)
            matrix.postScale(-1f, 1f)
        }
        ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
        ExifInterface.ORIENTATION_TRANSVERSE -> {
            matrix.setRotate(-90f)
            matrix.postScale(-1f, 1f)
        }
        ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
        else -> return this
    }
    val rotated = try {
        Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
    } catch (error: Throwable) {
        recycle()
        throw error
    }
    if (rotated !== this) recycle()
    return rotated
}

private fun File.readExifOrientation(): Int = runCatching {
    ExifInterface(absolutePath).getAttributeInt(
        ExifInterface.TAG_ORIENTATION,
        ExifInterface.ORIENTATION_NORMAL
    )
}.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

private fun File.hasExifTransform(): Boolean {
    return readExifOrientation() !in setOf(
        ExifInterface.ORIENTATION_UNDEFINED,
        ExifInterface.ORIENTATION_NORMAL
    )
}

private fun Bitmap.centerCropSource(targetRatio: Float): Rect {
    val sourceRatio = width / height.toFloat()
    return if (sourceRatio > targetRatio) {
        val cropWidth = (height * targetRatio).toInt()
        val left = (width - cropWidth) / 2
        Rect(left, 0, left + cropWidth, height)
    } else {
        val cropHeight = (width / targetRatio).toInt()
        val top = (height - cropHeight) / 2
        Rect(0, top, width, top + cropHeight)
    }
}

private val imageScaleScope = ThreadLocal<Float>()
private fun pdfImageScale(): Float = imageScaleScope.get() ?: 3f

/** 同步渲染范围内隔离采样比例，并在异常或嵌套调用后恢复。 */
internal fun <T> withPdfImageScale(scale: Float, block: () -> T): T {
    require(scale.isFinite() && scale in 1f..3f)
    val previous = imageScaleScope.get()
    imageScaleScope.set(scale)
    return try { block() } finally {
        if (previous == null) imageScaleScope.remove() else imageScaleScope.set(previous)
    }
}
