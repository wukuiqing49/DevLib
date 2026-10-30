package com.wkq.photo.imagerender

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Typeface
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
import kotlin.math.ceil
import kotlin.math.max

enum class WatermarkPosition {
    TOP_START,
    TOP_CENTER,
    TOP_END,
    CENTER_START,
    CENTER,
    CENTER_END,
    BOTTOM_START,
    BOTTOM_CENTER,
    BOTTOM_END
}

sealed interface WatermarkContent {
    data class Text(
        val value: String,
        val color: Int = Color.WHITE,
        val fontFamily: String? = null,
    ) : WatermarkContent {
        init { require(value.isNotBlank()) }
    }

    data class Image(val file: File) : WatermarkContent {
        init { require(file.isFile) }
    }
}

data class WatermarkSpec(
    val position: WatermarkPosition,
    val relativeSize: Float,
    val alpha: Int,
    val centerXRatio: Float? = null,
    val centerYRatio: Float? = null
) {
    init {
        require(relativeSize in 0.01f..0.5f)
        require(alpha in 0..255)
        require(centerXRatio == null || centerXRatio in 0f..1f)
        require(centerYRatio == null || centerYRatio in 0f..1f)
    }
}

enum class WatermarkOutputFormat(internal val bitmapFormat: Bitmap.CompressFormat) {
    JPEG(Bitmap.CompressFormat.JPEG),
    PNG(Bitmap.CompressFormat.PNG),
    WEBP(Bitmap.CompressFormat.WEBP)
}

enum class WatermarkPlacement { SINGLE, TILED }

data class WatermarkRenderOptions(
    val outputFormat: WatermarkOutputFormat = WatermarkOutputFormat.JPEG,
    val quality: Int = 94,
    val placement: WatermarkPlacement = WatermarkPlacement.SINGLE,
    val rotationDegrees: Float = 0f,
    val tileSpacingRatio: Float = 0.5f
) {
    init {
        require(quality in 1..100)
        require(rotationDegrees in -180f..180f)
        require(tileSpacingRatio in 0.1f..2f)
    }
}

/** 水印渲染器。调用方应在后台线程执行。 */
object WatermarkRenderer {

    fun render(source: File, output: File, content: WatermarkContent, spec: WatermarkSpec, jpegQuality: Int = 94) {
        render(source, output, content, spec, WatermarkRenderOptions(quality = jpegQuality))
    }

    fun render(
        source: File,
        output: File,
        content: WatermarkContent,
        spec: WatermarkSpec,
        options: WatermarkRenderOptions
    ) {
        renderResult(
            source = ImageSource.FileSource(source),
            output = output,
            content = content,
            spec = spec,
            encodeOptions = ImageEncodeOptions(
                format = options.outputFormat.toImageOutputFormat(),
                quality = options.quality,
                metadataPolicy = MetadataPolicy.STRIP_SENSITIVE
            ),
            renderOptions = options
        ).getOrThrow()
    }

    fun renderResult(
        source: ImageSource,
        output: File,
        content: WatermarkContent,
        spec: WatermarkSpec,
        encodeOptions: ImageEncodeOptions = ImageEncodeOptions(),
        renderOptions: WatermarkRenderOptions = WatermarkRenderOptions(),
        budget: OperationBudget = OperationBudget.DEFAULT,
        cancellation: OperationCancellation = OperationCancellation.NONE,
        onProgress: (OperationProgress) -> Unit = {}
    ): OperationResult<File> = OperationRunner.run(budget, cancellation, onProgress) { context ->
        context.report(OperationStage.VALIDATING, 0, 1)
        if (encodeOptions.format == ImageOutputFormat.TIFF) {
            throw OperationException(
                OperationErrorCode.UNSUPPORTED_FORMAT,
                "TIFF watermark output requires the libvips executor",
            )
        }
        if (encodeOptions.metadataPolicy == MetadataPolicy.PRESERVE) {
            throw OperationException(
                OperationErrorCode.INVALID_INPUT,
                "Watermark rendering cannot preserve source metadata"
            )
        }
        if (!output.extension.equals(encodeOptions.format.extension, ignoreCase = true) &&
            !(encodeOptions.format == ImageOutputFormat.JPEG && output.extension.equals("jpeg", ignoreCase = true))
        ) {
            throw OperationException(OperationErrorCode.INVALID_INPUT, "Output extension does not match encode format")
        }
        val decoded = BitmapFileDecoder.decodeOriented(
            source = source,
            options = BitmapDecodeOptions(mutable = true),
            budget = budget,
            cancellation = cancellation
        ).getOrThrow()
        val canvasBitmap = if (decoded.isMutable) {
            decoded
        } else {
            decoded.copy(Bitmap.Config.ARGB_8888, true).also { decoded.recycle() }
        }
        val outputWidth = canvasBitmap.width
        val outputHeight = canvasBitmap.height
        try {
            context.checkDimensions(canvasBitmap.width, canvasBitmap.height)
            context.checkTotalPixels(canvasBitmap.width.toLong() * canvasBitmap.height)
            context.report(OperationStage.PROCESSING, 0, 1)
            val watermark = when (content) {
                is WatermarkContent.Text -> createTextWatermark(canvasBitmap, content, spec)
                is WatermarkContent.Image -> createImageWatermark(canvasBitmap, content, spec)
            }
            val transformed = rotateWatermark(watermark, renderOptions.rotationDegrees)
            try {
                drawWatermark(canvasBitmap, transformed, spec, renderOptions)
            } finally {
                transformed.recycle()
                if (transformed !== watermark) watermark.recycle()
            }
            withOutputPath(output) {
                AtomicFileOutput.write(output) { temporary ->
                    context.report(OperationStage.ENCODING, 0, 1)
                    temporary.outputStream().buffered().use { stream ->
                        if (!canvasBitmap.compress(encodeOptions.format.bitmapFormat(), encodeOptions.quality, stream)) {
                            throw OperationException(OperationErrorCode.ENCODE_FAILED, "Unable to encode watermark output")
                        }
                    }
                }
            }
        } finally {
            canvasBitmap.recycle()
        }
        context.report(OperationStage.COMPLETED, 1, 1)
        OperationResult.Success(
            output,
            OperationStatistics(
                durationMillis = context.elapsedMillis(),
                inputCount = 1,
                outputCount = 1,
                inputBytes = (source as? ImageSource.FileSource)?.file?.length() ?: 0L,
                outputBytes = output.length(),
                outputPixels = outputWidth.toLong() * outputHeight,
                outputWidth = outputWidth,
                outputHeight = outputHeight
            )
        )
    }

    private fun createTextWatermark(target: Bitmap, content: WatermarkContent.Text, spec: WatermarkSpec): Bitmap {
        val size = (target.width.coerceAtMost(target.height) * spec.relativeSize).coerceAtLeast(8f)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = content.color
            textSize = size
            typeface = Typeface.create(content.fontFamily ?: "sans-serif", Typeface.BOLD)
            setShadowLayer(size / 8f, 0f, 2f, Color.BLACK)
        }
        val maxTextWidth = (target.width * MAX_TEXT_WIDTH_RATIO).coerceAtLeast(1f)
        val measuredWidth = paint.measureText(content.value)
        if (measuredWidth > maxTextWidth) {
            paint.textSize = (paint.textSize * maxTextWidth / measuredWidth).coerceAtLeast(1f)
            paint.setShadowLayer(paint.textSize / 8f, 0f, 2f, Color.BLACK)
        }
        val textWidth = paint.measureText(content.value)
        val metrics = paint.fontMetrics
        val padding = max(2f, paint.textSize / 6f)
        val bitmap = Bitmap.createBitmap(
            ceil(textWidth + padding * 2).toInt().coerceAtLeast(1),
            ceil(metrics.bottom - metrics.top + padding * 2).toInt().coerceAtLeast(1),
            Bitmap.Config.ARGB_8888
        )
        Canvas(bitmap).drawText(content.value, padding, padding - metrics.top, paint)
        return bitmap
    }

    private fun createImageWatermark(target: Bitmap, content: WatermarkContent.Image, spec: WatermarkSpec): Bitmap {
        val targetLength = (target.width.coerceAtMost(target.height) * spec.relativeSize).toInt().coerceAtLeast(1)
        val overlay = BitmapFileDecoder.decodeOriented(content.file, targetLength, targetLength)
        val scale = targetLength.toFloat() / overlay.width.coerceAtLeast(overlay.height)
        val scaled = Bitmap.createScaledBitmap(
            overlay,
            (overlay.width * scale).toInt().coerceAtLeast(1),
            (overlay.height * scale).toInt().coerceAtLeast(1),
            true
        )
        if (scaled !== overlay) overlay.recycle()
        return scaled
    }

    private fun rotateWatermark(watermark: Bitmap, degrees: Float): Bitmap {
        if (degrees == 0f) return watermark
        return Bitmap.createBitmap(
            watermark,
            0,
            0,
            watermark.width,
            watermark.height,
            Matrix().apply { postRotate(degrees) },
            true
        )
    }

    private fun drawWatermark(
        target: Bitmap,
        watermark: Bitmap,
        spec: WatermarkSpec,
        options: WatermarkRenderOptions
    ) {
        val canvas = Canvas(target)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { alpha = spec.alpha }
        if (options.placement == WatermarkPlacement.TILED) {
            drawTiled(canvas, target, watermark, paint, options.tileSpacingRatio)
            return
        }
        val margin = (target.width.coerceAtMost(target.height) * SINGLE_MARGIN_RATIO).toInt().coerceAtLeast(8)
        val customCenterX = spec.centerXRatio
        val customCenterY = spec.centerYRatio
        val x = if (customCenterX != null) {
            (target.width * customCenterX - watermark.width / 2f).toInt()
        } else when (spec.position) {
            WatermarkPosition.TOP_START,
            WatermarkPosition.CENTER_START,
            WatermarkPosition.BOTTOM_START -> margin

            WatermarkPosition.TOP_CENTER,
            WatermarkPosition.CENTER,
            WatermarkPosition.BOTTOM_CENTER -> (target.width - watermark.width) / 2

            WatermarkPosition.TOP_END,
            WatermarkPosition.CENTER_END,
            WatermarkPosition.BOTTOM_END -> target.width - margin - watermark.width
        }
        val y = if (customCenterY != null) {
            (target.height * customCenterY - watermark.height / 2f).toInt()
        } else when (spec.position) {
            WatermarkPosition.TOP_START,
            WatermarkPosition.TOP_CENTER,
            WatermarkPosition.TOP_END -> margin

            WatermarkPosition.CENTER_START,
            WatermarkPosition.CENTER,
            WatermarkPosition.CENTER_END -> (target.height - watermark.height) / 2

            WatermarkPosition.BOTTOM_START,
            WatermarkPosition.BOTTOM_CENTER,
            WatermarkPosition.BOTTOM_END -> target.height - margin - watermark.height
        }
        canvas.drawBitmap(
            watermark,
            x.coerceIn(0, (target.width - watermark.width).coerceAtLeast(0)).toFloat(),
            y.coerceIn(0, (target.height - watermark.height).coerceAtLeast(0)).toFloat(),
            paint,
        )
    }

    private fun drawTiled(
        canvas: Canvas,
        target: Bitmap,
        watermark: Bitmap,
        paint: Paint,
        spacingRatio: Float
    ) {
        val stepX = (watermark.width * (1f + spacingRatio)).toInt().coerceAtLeast(MIN_TILE_STEP)
        val stepY = (watermark.height * (1f + spacingRatio)).toInt().coerceAtLeast(MIN_TILE_STEP)
        var row = 0
        var y = -watermark.height / 2
        while (y < target.height) {
            var x = -watermark.width / 2 + if (row % 2 == 0) 0 else stepX / 2
            while (x < target.width) {
                canvas.drawBitmap(watermark, x.toFloat(), y.toFloat(), paint)
                x += stepX
            }
            y += stepY
            row++
        }
    }

    private const val MAX_TEXT_WIDTH_RATIO = 0.9f
    private const val SINGLE_MARGIN_RATIO = 0.025f
    private const val MIN_TILE_STEP = 24
}

private fun WatermarkOutputFormat.toImageOutputFormat(): ImageOutputFormat = when (this) {
    WatermarkOutputFormat.JPEG -> ImageOutputFormat.JPEG
    WatermarkOutputFormat.PNG -> ImageOutputFormat.PNG
    WatermarkOutputFormat.WEBP -> ImageOutputFormat.WEBP
}

private fun ImageOutputFormat.bitmapFormat(): Bitmap.CompressFormat = when (this) {
    ImageOutputFormat.JPEG -> Bitmap.CompressFormat.JPEG
    ImageOutputFormat.PNG -> Bitmap.CompressFormat.PNG
    ImageOutputFormat.WEBP -> Bitmap.CompressFormat.WEBP
    ImageOutputFormat.TIFF -> error("TIFF is not supported by Android Bitmap")
}
