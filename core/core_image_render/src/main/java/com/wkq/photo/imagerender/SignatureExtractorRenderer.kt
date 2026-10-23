package com.wkq.photo.imagerender

import android.graphics.Bitmap
import android.graphics.Color
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
import kotlin.math.min
import kotlin.math.roundToInt

enum class SignatureInkColor {
    BLACK,
    BLUE,
    RED,
    BOLD,
}

object SignatureExtractorRenderer {

    fun extract(
        source: ImageSource,
        inkColor: SignatureInkColor,
        destination: File,
        thresholdFactor: Float = 0.85f,
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

        val w = srcBitmap.width
        val h = srcBitmap.height
        val pixels = IntArray(w * h)
        srcBitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        val outPixels = extractInkPixels(
            sourcePixels = pixels,
            width = w,
            height = h,
            inkColor = inkColor,
            thresholdFactor = thresholdFactor,
            checkActive = context::checkActive,
        )

        resultBitmap = Bitmap.createBitmap(outPixels, w, h, Bitmap.Config.ARGB_8888).apply {
            // 显式保留 Alpha，确保透明签名 PNG 不会在编码时被当作不透明位图。
            setHasAlpha(true)
        }
        context.checkActive()

        AtomicFileOutput.write(destination) { tempFile ->
            context.checkActive()
            FileOutputStream(tempFile).use { stream ->
                if (!checkNotNull(resultBitmap).compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                    throw OperationException(OperationErrorCode.ENCODE_FAILED, "Unable to encode signature output")
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

    /** 局部背景阈值计算独立出来，便于对阴影和笔迹边界进行像素级回归验证。 */
    internal fun extractInkPixels(
        sourcePixels: IntArray,
        width: Int,
        height: Int,
        inkColor: SignatureInkColor,
        thresholdFactor: Float = 0.85f,
        checkActive: () -> Unit = {},
    ): IntArray {
        require(width > 0 && height > 0 && sourcePixels.size == width * height)
        val blockColumns = (width + BACKGROUND_BLOCK_SIZE - 1) / BACKGROUND_BLOCK_SIZE
        val blockRows = (height + BACKGROUND_BLOCK_SIZE - 1) / BACKGROUND_BLOCK_SIZE
        val backgroundSums = LongArray(blockColumns * blockRows)
        val backgroundCounts = IntArray(blockColumns * blockRows)
        for ((index, p) in sourcePixels.withIndex()) {
            if (index % CANCELLATION_CHECK_INTERVAL == 0) checkActive()
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val lum = (r * 299 + g * 587 + b * 114) / 1000
            val x = index % width
            val y = index / width
            val block = (y / BACKGROUND_BLOCK_SIZE) * blockColumns + x / BACKGROUND_BLOCK_SIZE
            backgroundSums[block] += lum
            backgroundCounts[block] += 1
        }
        val localBackgrounds = IntArray(backgroundSums.size) { block ->
            val average = backgroundSums[block] / backgroundCounts[block].coerceAtLeast(1)
            // 预留亮度余量，保持浅色纸张阴影透明，同时保留真实笔迹的对比度。
            (average + BACKGROUND_LIGHTNESS_BIAS).coerceAtMost(255L).toInt()
        }
        val baseThreshold = (16f + (1f - thresholdFactor.coerceIn(0.5f, 0.98f)) * 80f).roundToInt()
        val (targetR, targetG, targetB) = when (inkColor) {
            SignatureInkColor.BLUE -> Triple(21, 101, 192)
            SignatureInkColor.RED -> Triple(211, 47, 47)
            SignatureInkColor.BOLD, SignatureInkColor.BLACK -> Triple(18, 18, 18)
        }
        val isBold = inkColor == SignatureInkColor.BOLD
        return IntArray(sourcePixels.size) { index ->
            if (index % CANCELLATION_CHECK_INTERVAL == 0) checkActive()
            val p = sourcePixels[index]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val lum = (r * 299 + g * 587 + b * 114) / 1000
            val x = index % width
            val y = index / width
            val localBackground = localBackgrounds[(y / BACKGROUND_BLOCK_SIZE) * blockColumns + x / BACKGROUND_BLOCK_SIZE]
            val contrast = localBackground - lum
            val contrastThreshold = maxOf(baseThreshold, localBackground / 10)
            if (contrast <= contrastThreshold) {
                0x00000000
            } else {
                val darkness = (contrast - contrastThreshold).toFloat() /
                    (localBackground - contrastThreshold).coerceAtLeast(1).toFloat()
                var alpha = (darkness.coerceIn(0f, 1f) * 255f).roundToInt()
                if (isBold) alpha = min(255, (alpha * 1.5f).toInt())
                alpha = alpha.coerceIn(0, 255)
                (alpha shl 24) or (targetR shl 16) or (targetG shl 8) or targetB
            }
        }
    }

    private const val CANCELLATION_CHECK_INTERVAL = 4_096
    private const val BACKGROUND_BLOCK_SIZE = 32
    private const val BACKGROUND_LIGHTNESS_BIAS = 32L
}
