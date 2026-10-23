package com.wkq.photo.imagerender

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import com.wkq.photo.core.operation.ImageOutputFormat
import com.wkq.photo.core.operation.OperationBudget
import com.wkq.photo.core.operation.OperationCancellation
import com.wkq.photo.core.operation.OperationErrorCode
import com.wkq.photo.core.operation.OperationException
import com.wkq.photo.core.operation.OperationProgress
import com.wkq.photo.core.operation.OperationResult
import com.wkq.photo.core.operation.OperationRunner
import com.wkq.photo.core.operation.OperationStatistics
import com.wkq.photo.core.operation.getOrThrow
import com.wkq.photo.core.utils.AtomicFileOutput
import java.io.File
import java.io.FileOutputStream

enum class PolaroidStyle {
    CLASSIC_WHITE,
    VINTAGE_BLACK,
    FILM_BORDER,
    WARM_RETRO,
}

object PolaroidFrameRenderer {

    fun render(
        source: ImageSource,
        style: PolaroidStyle,
        dateLabel: String?,
        destination: File,
        format: ImageOutputFormat = ImageOutputFormat.JPEG,
        budget: OperationBudget = OperationBudget.DEFAULT,
        cancellation: OperationCancellation = OperationCancellation.NONE,
        onProgress: (OperationProgress) -> Unit = {},
    ): OperationResult<File> = OperationRunner.run(budget, cancellation, onProgress) { context ->
        val srcBitmap = BitmapFileDecoder.decodeOriented(
            source = source,
            options = BitmapDecodeOptions(maxWidth = 1600, maxHeight = 1600),
        ).getOrThrow()
        var resultBitmap: Bitmap? = null
        try {
        context.checkActive()

        val srcW = srcBitmap.width.toFloat()
        val srcH = srcBitmap.height.toFloat()

        val marginSide = srcW * 0.08f
        val marginTop = srcW * 0.08f
        val marginBottom = srcW * 0.25f

        val canvasW = (srcW + marginSide * 2).toInt()
        val canvasH = (srcH + marginTop + marginBottom).toInt()

        resultBitmap = Bitmap.createBitmap(canvasW, canvasH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(checkNotNull(resultBitmap))
        context.checkActive()

        // 1. 绘制相框底板
        val framePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        when (style) {
            PolaroidStyle.CLASSIC_WHITE, PolaroidStyle.WARM_RETRO -> {
                framePaint.color = if (style == PolaroidStyle.WARM_RETRO) 0xFFFAF4E8.toInt() else 0xFFFDFDFD.toInt()
                canvas.drawRect(0f, 0f, canvasW.toFloat(), canvasH.toFloat(), framePaint)
            }
            PolaroidStyle.VINTAGE_BLACK, PolaroidStyle.FILM_BORDER -> {
                framePaint.color = 0xFF18181A.toInt()
                canvas.drawRect(0f, 0f, canvasW.toFloat(), canvasH.toFloat(), framePaint)
            }
        }

        // 2. 如果是胶片齿孔风格，在左右两侧绘制小矩形孔洞
        if (style == PolaroidStyle.FILM_BORDER) {
            val holePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF333336.toInt() }
            val holeW = marginSide * 0.45f
            val holeH = holeW * 1.4f
            val holeSpacing = holeH * 1.8f
            var y = marginTop * 0.5f
            while (y < canvasH - holeH) {
                context.checkActive()
                canvas.drawRoundRect(RectF(marginSide * 0.25f, y, marginSide * 0.25f + holeW, y + holeH), 4f, 4f, holePaint)
                canvas.drawRoundRect(RectF(canvasW - marginSide * 0.7f, y, canvasW - marginSide * 0.7f + holeW, y + holeH), 4f, 4f, holePaint)
                y += holeSpacing
            }
        }

        // 3. 绘制照片微阴影与照片本体
        val photoRect = RectF(marginSide, marginTop, marginSide + srcW, marginTop + srcH)
        val photoShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x22000000.toInt()
            setShadowLayer(4f, 0f, 2f, 0x33000000.toInt())
        }
        canvas.drawRect(photoRect, photoShadowPaint)
        canvas.drawBitmap(srcBitmap, marginSide, marginTop, null)
        context.checkActive()

        // 4. 在底部留白区绘制手写/打卡时间
        val displayDate = dateLabel?.takeIf(String::isNotBlank) ?: "PhotoKit • Memories"
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = when (style) {
                PolaroidStyle.VINTAGE_BLACK, PolaroidStyle.FILM_BORDER -> 0xFFE0E0E0.toInt()
                PolaroidStyle.WARM_RETRO -> 0xFF8D6E63.toInt()
                else -> 0xFF555555.toInt()
            }
            textSize = marginSide * 0.62f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.SERIF, Typeface.ITALIC)
        }
        val textY = marginTop + srcH + (marginBottom / 2f) + textPaint.textSize * 0.35f
        canvas.drawText(displayDate, canvasW / 2f, textY, textPaint)
        context.checkActive()

        // 写入文件
        AtomicFileOutput.write(destination) { tempFile ->
            context.checkActive()
            FileOutputStream(tempFile).use { stream ->
                val compressFormat = if (format == ImageOutputFormat.PNG) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
                if (!checkNotNull(resultBitmap).compress(compressFormat, 92, stream)) {
                    throw OperationException(OperationErrorCode.ENCODE_FAILED, "Unable to encode polaroid output")
                }
            }
        }

        OperationResult.Success(
            destination,
            OperationStatistics(
                durationMillis = context.elapsedMillis(),
                inputCount = 1,
                outputCount = 1,
                outputBytes = destination.length(),
            ),
        )
        } finally {
            if (!srcBitmap.isRecycled) srcBitmap.recycle()
            resultBitmap?.takeUnless(Bitmap::isRecycled)?.recycle()
        }
    }
}
