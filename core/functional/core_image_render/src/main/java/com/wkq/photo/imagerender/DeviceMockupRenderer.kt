package com.wkq.photo.imagerender

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
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
import kotlin.math.max

enum class MockupBackground {
    DAWN,
    SLATE,
    AURORA,
    TRANSPARENT,
}

object DeviceMockupRenderer {

    fun render(
        source: ImageSource,
        background: MockupBackground,
        destination: File,
        format: ImageOutputFormat = ImageOutputFormat.PNG,
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

        val paddingH = srcW * 0.16f
        val paddingV = srcH * 0.14f
        val canvasW = (srcW + paddingH * 2).toInt()
        val canvasH = (srcH + paddingV * 2).toInt()

        resultBitmap = Bitmap.createBitmap(canvasW, canvasH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(checkNotNull(resultBitmap))
        context.checkActive()

        // 1. 绘制背景
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        when (background) {
            MockupBackground.DAWN -> {
                bgPaint.shader = LinearGradient(
                    0f, 0f, canvasW.toFloat(), canvasH.toFloat(),
                    intArrayOf(0xFFFFE259.toInt(), 0xFFFFA751.toInt(), 0xFFFF5E62.toInt()),
                    null,
                    Shader.TileMode.CLAMP,
                )
                canvas.drawRect(0f, 0f, canvasW.toFloat(), canvasH.toFloat(), bgPaint)
            }
            MockupBackground.SLATE -> {
                bgPaint.shader = LinearGradient(
                    0f, 0f, 0f, canvasH.toFloat(),
                    intArrayOf(0xFF2C3E50.toInt(), 0xFF1A1A24.toInt()),
                    null,
                    Shader.TileMode.CLAMP,
                )
                canvas.drawRect(0f, 0f, canvasW.toFloat(), canvasH.toFloat(), bgPaint)
            }
            MockupBackground.AURORA -> {
                bgPaint.shader = LinearGradient(
                    0f, 0f, canvasW.toFloat(), canvasH.toFloat(),
                    intArrayOf(0xFF7F7FD5.toInt(), 0xFF86A8E7.toInt(), 0xFF91EAE4.toInt()),
                    null,
                    Shader.TileMode.CLAMP,
                )
                canvas.drawRect(0f, 0f, canvasW.toFloat(), canvasH.toFloat(), bgPaint)
            }
            MockupBackground.TRANSPARENT -> {
                // 透明底
            }
        }
        context.checkActive()

        // 2. 绘制多层悬浮阴影
        val phoneLeft = paddingH
        val phoneTop = paddingV
        val phoneRight = phoneLeft + srcW
        val phoneBottom = phoneTop + srcH
        val phoneRect = RectF(phoneLeft, phoneTop, phoneRight, phoneBottom)
        val cornerRadius = srcW * 0.08f

        val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x44000000.toInt()
            style = Paint.Style.FILL
            setShadowLayer(paddingH * 0.45f, 0f, paddingV * 0.25f, 0x55000000.toInt())
        }
        canvas.drawRoundRect(phoneRect, cornerRadius, cornerRadius, shadowPaint)

        // 3. 绘制设备外壳深色边框
        val bezelThickness = max(4f, srcW * 0.022f)
        val bezelRect = RectF(
            phoneLeft - bezelThickness,
            phoneTop - bezelThickness,
            phoneRight + bezelThickness,
            phoneBottom + bezelThickness,
        )
        val bezelCorner = cornerRadius + bezelThickness
        val bezelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF1C1C1E.toInt()
            style = Paint.Style.FILL
        }
        canvas.drawRoundRect(bezelRect, bezelCorner, bezelCorner, bezelPaint)

        // 4. 绘制高光微边
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x33FFFFFF.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }
        canvas.drawRoundRect(bezelRect, bezelCorner, bezelCorner, strokePaint)
        context.checkActive()

        // 5. 裁剪屏幕内容（圆角）并绘制源图
        canvas.save()
        val path = Path().apply {
            addRoundRect(phoneRect, cornerRadius, cornerRadius, Path.Direction.CW)
        }
        canvas.clipPath(path)
        canvas.drawBitmap(srcBitmap, phoneLeft, phoneTop, null)
        canvas.restore()

        // 6. 绘制顶部灵动摄像头小孔 (Dynamic Pill)
        val pillWidth = srcW * 0.26f
        val pillHeight = srcW * 0.06f
        val pillLeft = phoneRect.centerX() - pillWidth / 2f
        val pillTop = phoneTop + srcW * 0.028f
        val pillRect = RectF(pillLeft, pillTop, pillLeft + pillWidth, pillTop + pillHeight)
        val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            style = Paint.Style.FILL
        }
        canvas.drawRoundRect(pillRect, pillHeight / 2f, pillHeight / 2f, pillPaint)
        context.checkActive()

        // 写入产物
        AtomicFileOutput.write(destination) { tempFile ->
            context.checkActive()
            FileOutputStream(tempFile).use { stream ->
                val compressFormat = when (format) {
                    ImageOutputFormat.JPEG -> Bitmap.CompressFormat.JPEG
                    ImageOutputFormat.WEBP -> Bitmap.CompressFormat.WEBP
                    else -> Bitmap.CompressFormat.PNG
                }
                if (!checkNotNull(resultBitmap).compress(compressFormat, 95, stream)) {
                    throw OperationException(OperationErrorCode.ENCODE_FAILED, "Unable to encode device mockup output")
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
