package com.wkq.photo.imagerender

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.wkq.photo.core.operation.ImageEncodeOptions
import com.wkq.photo.core.operation.ImageOutputFormat
import com.wkq.photo.core.operation.MetadataPolicy
import com.wkq.photo.core.operation.OperationBudget
import com.wkq.photo.core.operation.OperationCancellation
import com.wkq.photo.core.operation.OperationErrorCode
import com.wkq.photo.core.operation.OperationException
import com.wkq.photo.core.operation.OperationProgress
import com.wkq.photo.core.operation.OperationResult
import com.wkq.photo.core.operation.OperationRunner
import com.wkq.photo.core.operation.OperationStage
import com.wkq.photo.core.operation.OperationStatistics
import com.wkq.photo.core.operation.getOrThrow
import com.wkq.photo.core.operation.withOutputPath
import com.wkq.photo.core.utils.AtomicFileOutput
import java.io.File
import kotlin.math.cos
import kotlin.math.sin

data class NormalizedPoint(val x: Float, val y: Float) {
    init { require(x in 0f..1f && y in 0f..1f) }
}

data class NormalizedRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    init {
        require(left in 0f..1f && top in 0f..1f && right in 0f..1f && bottom in 0f..1f)
        require(right > left && bottom > top)
    }
}

data class AnnotationStroke(
    val color: Int = Color.RED,
    val widthRatio: Float = 0.006f,
    val alpha: Int = 255,
) {
    init {
        require(widthRatio in 0.0005f..0.2f)
        require(alpha in 0..255)
    }
}

sealed interface AnnotationLayer {
    data class Brush(val points: List<NormalizedPoint>, val stroke: AnnotationStroke) : AnnotationLayer {
        init { require(points.size >= 2) }
    }

    data class Line(
        val start: NormalizedPoint,
        val end: NormalizedPoint,
        val stroke: AnnotationStroke,
        val arrowEnd: Boolean = false,
    ) : AnnotationLayer

    data class Rectangle(
        val bounds: NormalizedRect,
        val stroke: AnnotationStroke,
        val fillColor: Int? = null,
    ) : AnnotationLayer

    data class Ellipse(
        val bounds: NormalizedRect,
        val stroke: AnnotationStroke,
        val fillColor: Int? = null,
    ) : AnnotationLayer

    data class Text(
        val bounds: NormalizedRect,
        val value: String,
        val color: Int,
        val textSizeRatio: Float,
        val bold: Boolean = false,
        val maxLines: Int = 4,
    ) : AnnotationLayer {
        init {
            require(value.isNotBlank())
            require(textSizeRatio in 0.005f..0.25f)
            require(maxLines > 0)
        }
    }

    data class Number(
        val center: NormalizedPoint,
        val value: Int,
        val backgroundColor: Int = Color.RED,
        val textColor: Int = Color.WHITE,
        val radiusRatio: Float = 0.025f,
    ) : AnnotationLayer {
        init {
            require(value >= 0)
            require(radiusRatio in 0.005f..0.2f)
        }
    }

    data class Mosaic(val bounds: NormalizedRect, val blockSizeRatio: Float = 0.018f) : AnnotationLayer {
        init { require(blockSizeRatio in 0.002f..0.2f) }
    }

    data class Blur(val bounds: NormalizedRect, val radiusRatio: Float = 0.012f) : AnnotationLayer {
        init { require(radiusRatio in 0.001f..0.05f) }
    }

    data class Cover(val bounds: NormalizedRect, val color: Int = Color.BLACK) : AnnotationLayer
}

data class AnnotationDocument(val layers: List<AnnotationLayer> = emptyList())

class AnnotationHistory(
    initial: AnnotationDocument = AnnotationDocument(),
    private val maxStates: Int = 100,
) {
    private val undo = ArrayDeque<AnnotationDocument>()
    private val redo = ArrayDeque<AnnotationDocument>()
    var current: AnnotationDocument = initial
        private set

    init { require(maxStates > 0) }

    fun update(document: AnnotationDocument) {
        if (document == current) return
        undo.addLast(current)
        while (undo.size > maxStates) undo.removeFirst()
        current = document
        redo.clear()
    }

    fun undo(): AnnotationDocument? {
        if (undo.isEmpty()) return null
        redo.addLast(current)
        current = undo.removeLast()
        return current
    }

    fun redo(): AnnotationDocument? {
        if (redo.isEmpty()) return null
        undo.addLast(current)
        current = redo.removeLast()
        return current
    }
}

object AnnotationRenderer {
    fun render(
        source: ImageSource,
        output: File,
        document: AnnotationDocument,
        encodeOptions: ImageEncodeOptions = ImageEncodeOptions(),
        budget: OperationBudget = OperationBudget.DEFAULT,
        cancellation: OperationCancellation = OperationCancellation.NONE,
        onProgress: (OperationProgress) -> Unit = {},
    ): OperationResult<File> = OperationRunner.run(budget, cancellation, onProgress) { context ->
        context.report(OperationStage.VALIDATING, 0, document.layers.size)
        if (encodeOptions.format == ImageOutputFormat.TIFF) {
            throw OperationException(
                OperationErrorCode.UNSUPPORTED_FORMAT,
                "TIFF annotation output requires a libvips composition pipeline",
            )
        }
        if (encodeOptions.metadataPolicy == MetadataPolicy.PRESERVE) {
            throw OperationException(
                OperationErrorCode.INVALID_INPUT,
                "Annotation bitmap export cannot preserve source metadata",
            )
        }
        validateExtension(output, encodeOptions.format)
        val decoded = BitmapFileDecoder.decodeOriented(
            source,
            BitmapDecodeOptions(mutable = true),
            budget,
            cancellation,
        ).getOrThrow()
        val bitmap = if (decoded.isMutable) decoded else decoded.copy(Bitmap.Config.ARGB_8888, true).also {
            decoded.recycle()
        }
        val width = bitmap.width
        val height = bitmap.height
        try {
            val canvas = Canvas(bitmap)
            document.layers.forEachIndexed { index, layer ->
                context.checkActive()
                drawLayer(canvas, bitmap, layer, context::checkActive)
                context.report(OperationStage.PROCESSING, index + 1, document.layers.size)
            }
            withOutputPath(output) {
                AtomicFileOutput.write(output) { temporary ->
                    context.report(OperationStage.ENCODING, document.layers.size, document.layers.size)
                    temporary.outputStream().buffered().use { stream ->
                        if (!bitmap.compress(encodeOptions.format.toBitmapFormat(), encodeOptions.quality, stream)) {
                            throw OperationException(OperationErrorCode.ENCODE_FAILED, "Unable to encode annotation output")
                        }
                    }
                }
            }
        } finally {
            bitmap.recycle()
        }
        context.report(OperationStage.COMPLETED, document.layers.size, document.layers.size)
        OperationResult.Success(
            output,
            OperationStatistics(
                durationMillis = context.elapsedMillis(),
                inputCount = 1,
                outputCount = 1,
                inputBytes = (source as? ImageSource.FileSource)?.file?.length() ?: 0,
                outputBytes = output.length(),
                outputPixels = width.toLong() * height,
                outputWidth = width,
                outputHeight = height,
            ),
        )
    }

    private fun drawLayer(
        canvas: Canvas,
        bitmap: Bitmap,
        layer: AnnotationLayer,
        checkActive: () -> Unit,
    ) {
        when (layer) {
            is AnnotationLayer.Brush -> drawBrush(canvas, bitmap, layer)
            is AnnotationLayer.Line -> drawLine(canvas, bitmap, layer)
            is AnnotationLayer.Rectangle -> drawOutline(canvas, bitmap, layer.bounds, layer.stroke, layer.fillColor, false)
            is AnnotationLayer.Ellipse -> drawOutline(canvas, bitmap, layer.bounds, layer.stroke, layer.fillColor, true)
            is AnnotationLayer.Text -> drawText(canvas, bitmap, layer)
            is AnnotationLayer.Number -> drawNumber(canvas, bitmap, layer)
            is AnnotationLayer.Mosaic -> drawMosaic(bitmap, layer, checkActive)
            is AnnotationLayer.Blur -> drawBlur(bitmap, layer, checkActive)
            is AnnotationLayer.Cover -> canvas.drawRect(layer.bounds.toRectF(bitmap), Paint().apply { color = layer.color })
        }
    }

    private fun drawBrush(canvas: Canvas, bitmap: Bitmap, layer: AnnotationLayer.Brush) {
        val path = Path()
        val first = layer.points.first().toPoint(bitmap)
        path.moveTo(first.first, first.second)
        layer.points.drop(1).forEach {
            val point = it.toPoint(bitmap)
            path.lineTo(point.first, point.second)
        }
        canvas.drawPath(path, layer.stroke.toPaint(bitmap))
    }

    private fun drawLine(canvas: Canvas, bitmap: Bitmap, layer: AnnotationLayer.Line) {
        val start = layer.start.toPoint(bitmap)
        val end = layer.end.toPoint(bitmap)
        val paint = layer.stroke.toPaint(bitmap)
        canvas.drawLine(start.first, start.second, end.first, end.second, paint)
        if (!layer.arrowEnd) return
        val angle = kotlin.math.atan2(end.second - start.second, end.first - start.first)
        val length = paint.strokeWidth * 5f
        val spread = Math.toRadians(28.0).toFloat()
        canvas.drawLine(
            end.first,
            end.second,
            end.first - length * cos(angle - spread),
            end.second - length * sin(angle - spread),
            paint,
        )
        canvas.drawLine(
            end.first,
            end.second,
            end.first - length * cos(angle + spread),
            end.second - length * sin(angle + spread),
            paint,
        )
    }

    private fun drawOutline(
        canvas: Canvas,
        bitmap: Bitmap,
        bounds: NormalizedRect,
        stroke: AnnotationStroke,
        fillColor: Int?,
        ellipse: Boolean,
    ) {
        val rect = bounds.toRectF(bitmap)
        fillColor?.let { color ->
            val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
            if (ellipse) canvas.drawOval(rect, fill) else canvas.drawRect(rect, fill)
        }
        val paint = stroke.toPaint(bitmap)
        if (ellipse) canvas.drawOval(rect, paint) else canvas.drawRect(rect, paint)
    }

    private fun drawText(canvas: Canvas, bitmap: Bitmap, layer: AnnotationLayer.Text) {
        val bounds = layer.bounds.toRectF(bitmap)
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = layer.color
            textSize = minOf(bitmap.width, bitmap.height) * layer.textSizeRatio
            typeface = if (layer.bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
        val layout = StaticLayout.Builder.obtain(
            layer.value,
            0,
            layer.value.length,
            paint,
            bounds.width().toInt().coerceAtLeast(1),
        )
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .setMaxLines(layer.maxLines)
            .setEllipsize(android.text.TextUtils.TruncateAt.END)
            .build()
        canvas.save()
        canvas.clipRect(bounds)
        canvas.translate(bounds.left, bounds.top)
        layout.draw(canvas)
        canvas.restore()
    }

    private fun drawNumber(canvas: Canvas, bitmap: Bitmap, layer: AnnotationLayer.Number) {
        val center = layer.center.toPoint(bitmap)
        val radius = minOf(bitmap.width, bitmap.height) * layer.radiusRatio
        canvas.drawCircle(center.first, center.second, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = layer.backgroundColor
        })
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = layer.textColor
            textAlign = Paint.Align.CENTER
            textSize = radius * 1.15f
            typeface = Typeface.DEFAULT_BOLD
        }
        val baseline = center.second - (paint.ascent() + paint.descent()) / 2f
        canvas.drawText(layer.value.toString(), center.first, baseline, paint)
    }

    private fun drawMosaic(
        bitmap: Bitmap,
        layer: AnnotationLayer.Mosaic,
        checkActive: () -> Unit,
    ) {
        val rect = layer.bounds.toRect(bitmap)
        val width = rect.width()
        val height = rect.height()
        val block = (minOf(bitmap.width, bitmap.height) * layer.blockSizeRatio).toInt().coerceAtLeast(2)
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, rect.left, rect.top, width, height)
        for (blockTop in 0 until height step block) {
            checkActive()
            val blockBottom = minOf(blockTop + block, height)
            for (blockLeft in 0 until width step block) {
                val blockRight = minOf(blockLeft + block, width)
                var alpha = 0L
                var red = 0L
                var green = 0L
                var blue = 0L
                var count = 0
                for (y in blockTop until blockBottom) {
                    for (x in blockLeft until blockRight) {
                        val color = pixels[y * width + x]
                        alpha += Color.alpha(color)
                        red += Color.red(color)
                        green += Color.green(color)
                        blue += Color.blue(color)
                        count++
                    }
                }
                val average = Color.argb(
                    (alpha / count).toInt(),
                    (red / count).toInt(),
                    (green / count).toInt(),
                    (blue / count).toInt(),
                )
                for (y in blockTop until blockBottom) {
                    for (x in blockLeft until blockRight) {
                        pixels[y * width + x] = average
                    }
                }
            }
        }
        bitmap.setPixels(pixels, 0, width, rect.left, rect.top, width, height)
    }

    private fun drawBlur(
        bitmap: Bitmap,
        layer: AnnotationLayer.Blur,
        checkActive: () -> Unit,
    ) {
        val rect = layer.bounds.toRect(bitmap)
        val width = rect.width()
        val height = rect.height()
        val radius = (minOf(bitmap.width, bitmap.height) * layer.radiusRatio).toInt().coerceIn(1, 64)
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, rect.left, rect.top, width, height)
        boxBlur(pixels, width, height, radius, checkActive)
        bitmap.setPixels(pixels, 0, width, rect.left, rect.top, width, height)
    }

    private fun boxBlur(
        pixels: IntArray,
        width: Int,
        height: Int,
        radius: Int,
        checkActive: () -> Unit,
    ) {
        val source = pixels.copyOf()
        for (y in 0 until height) {
            if (y % 32 == 0) checkActive()
            for (x in 0 until width) {
                var alpha = 0
                var red = 0
                var green = 0
                var blue = 0
                var count = 0
                val left = (x - radius).coerceAtLeast(0)
                val right = (x + radius).coerceAtMost(width - 1)
                for (sampleX in left..right) {
                    val color = source[y * width + sampleX]
                    alpha += Color.alpha(color)
                    red += Color.red(color)
                    green += Color.green(color)
                    blue += Color.blue(color)
                    count++
                }
                pixels[y * width + x] = Color.argb(alpha / count, red / count, green / count, blue / count)
            }
        }
        source.indices.forEach { index -> source[index] = pixels[index] }
        for (x in 0 until width) {
            if (x % 32 == 0) checkActive()
            for (y in 0 until height) {
                var alpha = 0
                var red = 0
                var green = 0
                var blue = 0
                var count = 0
                val top = (y - radius).coerceAtLeast(0)
                val bottom = (y + radius).coerceAtMost(height - 1)
                for (sampleY in top..bottom) {
                    val color = source[sampleY * width + x]
                    alpha += Color.alpha(color)
                    red += Color.red(color)
                    green += Color.green(color)
                    blue += Color.blue(color)
                    count++
                }
                pixels[y * width + x] = Color.argb(alpha / count, red / count, green / count, blue / count)
            }
        }
    }

    private fun AnnotationStroke.toPaint(bitmap: Bitmap) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = this@toPaint.color
        alpha = this@toPaint.alpha
        style = Paint.Style.STROKE
        strokeWidth = minOf(bitmap.width, bitmap.height) * widthRatio
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private fun NormalizedPoint.toPoint(bitmap: Bitmap) = x * bitmap.width to y * bitmap.height
    private fun NormalizedRect.toRectF(bitmap: Bitmap) = RectF(
        left * bitmap.width, top * bitmap.height, right * bitmap.width, bottom * bitmap.height
    )
    private fun NormalizedRect.toRect(bitmap: Bitmap): Rect {
        val pixelLeft = (left * bitmap.width).toInt().coerceIn(0, bitmap.width - 1)
        val pixelTop = (top * bitmap.height).toInt().coerceIn(0, bitmap.height - 1)
        val pixelRight = (right * bitmap.width).toInt().coerceIn(pixelLeft + 1, bitmap.width)
        val pixelBottom = (bottom * bitmap.height).toInt().coerceIn(pixelTop + 1, bitmap.height)
        return Rect(pixelLeft, pixelTop, pixelRight, pixelBottom)
    }

    private fun validateExtension(output: File, format: ImageOutputFormat) {
        val valid = output.extension.equals(format.extension, true) ||
            format == ImageOutputFormat.JPEG && output.extension.equals("jpeg", true)
        if (!valid) throw OperationException(OperationErrorCode.INVALID_INPUT, "Output extension does not match format")
    }

    private fun ImageOutputFormat.toBitmapFormat(): Bitmap.CompressFormat = when (this) {
        ImageOutputFormat.JPEG -> Bitmap.CompressFormat.JPEG
        ImageOutputFormat.PNG -> Bitmap.CompressFormat.PNG
        ImageOutputFormat.WEBP -> Bitmap.CompressFormat.WEBP
        ImageOutputFormat.TIFF -> error("TIFF is not supported by Android Bitmap")
    }
}
