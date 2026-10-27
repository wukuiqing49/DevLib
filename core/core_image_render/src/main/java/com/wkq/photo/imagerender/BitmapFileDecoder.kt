package com.wkq.photo.imagerender

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
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
import java.io.File
import kotlin.math.min

/** 文件图片解码与 EXIF 方向归一化。 */
object BitmapFileDecoder {

    /** 读取图片方向归一化后的尺寸，不分配完整像素。 */
    fun readOrientedDimensions(source: ImageSource): Pair<Int, Int> {
        val bounds = readBounds(source)
        val orientation = readOrientation(source)
        return if (orientation.rotationDegrees % 180 != 0) {
            bounds.second to bounds.first
        } else {
            bounds
        }
    }

    /** 兼容文件入口，返回的 Bitmap 由调用方释放。 */
    fun decodeOriented(
        file: File,
        maxWidth: Int = Int.MAX_VALUE,
        maxHeight: Int = Int.MAX_VALUE,
        mutable: Boolean = false
    ): Bitmap {
        return decodeOriented(
            source = ImageSource.FileSource(file),
            options = BitmapDecodeOptions(maxWidth, maxHeight, mutable)
        ).getOrThrow()
    }

    /** 按目标和实际采样预算解码；失败或取消时释放已分配图片。 */
    fun decodeOriented(
        source: ImageSource,
        options: BitmapDecodeOptions = BitmapDecodeOptions(),
        budget: OperationBudget = OperationBudget.DEFAULT,
        cancellation: OperationCancellation = OperationCancellation.NONE,
        onProgress: (OperationProgress) -> Unit = {}
    ): OperationResult<Bitmap> = OperationRunner.run(budget, cancellation, onProgress) { context ->
        context.report(OperationStage.VALIDATING, 0, 1)

        val bounds = readBounds(source)
        val orientation = readOrientation(source)
        val swapsDimensions = orientation.rotationDegrees % 180 != 0
        val orientedWidth = if (swapsDimensions) bounds.second else bounds.first
        val orientedHeight = if (swapsDimensions) bounds.first else bounds.second
        val targetSize = calculateTargetSize(
            orientedWidth,
            orientedHeight,
            min(options.maxWidth, budget.maxWidth),
            min(options.maxHeight, budget.maxHeight)
        )
        context.checkTotalPixels(targetSize.first.toLong() * targetSize.second)
        context.report(OperationStage.DECODING, 0, 1)
        val sampleSize = calculateSampleSize(orientedWidth, orientedHeight, targetSize.first, targetSize.second)
        val sampledWidth = (orientedWidth.toLong() + sampleSize - 1) / sampleSize
        val sampledHeight = (orientedHeight.toLong() + sampleSize - 1) / sampleSize
        context.checkTotalPixels(sampledWidth * sampledHeight)
        val bitmap = source.openFileDescriptor().use { descriptor ->
            BitmapFactory.decodeFileDescriptor(
                descriptor.fileDescriptor,
                null,
                BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                    inMutable = options.mutable
                }
            )
        } ?: throw OperationException(OperationErrorCode.UNSUPPORTED_FORMAT, "Unable to decode image: ${source.displayName}")

        var output = bitmap
        try {
            context.checkActive()
            output = applyOrientation(output, orientation)
            if (output.width > targetSize.first || output.height > targetSize.second) {
                val scaled = Bitmap.createScaledBitmap(output, targetSize.first, targetSize.second, true)
                if (scaled !== output) output.recycle()
                output = scaled
            }
            if (options.mutable && !output.isMutable) {
                val mutableOutput = checkNotNull(output.copy(Bitmap.Config.ARGB_8888, true)) { "Cannot create mutable bitmap" }
                output.recycle()
                output = mutableOutput
            }
            context.report(OperationStage.COMPLETED, 1, 1)
            OperationResult.Success(
                value = output,
                statistics = OperationStatistics(
                    durationMillis = context.elapsedMillis(), inputCount = 1, outputCount = 1,
                    inputBytes = (source as? ImageSource.FileSource)?.file?.length() ?: 0,
                    inputPixels = orientedWidth.toLong() * orientedHeight,
                    outputPixels = output.width.toLong() * output.height,
                    outputWidth = output.width, outputHeight = output.height
                )
            )
        } catch (error: Throwable) {
            if (!output.isRecycled) output.recycle()
            throw error
        }
    }

    /** 应用 EXIF 旋转与镜像，替换成功后释放原位图。 */
    private fun applyOrientation(bitmap: Bitmap, orientation: Orientation): Bitmap {
        if (!orientation.isFlipped && orientation.rotationDegrees == 0) return bitmap
        val matrix = Matrix().apply {
            if (orientation.isFlipped) postScale(-1f, 1f)
            if (orientation.rotationDegrees != 0) postRotate(orientation.rotationDegrees.toFloat())
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true).also { transformed ->
            if (transformed !== bitmap) bitmap.recycle()
        }
    }

    private fun calculateTargetSize(width: Int, height: Int, maxWidth: Int, maxHeight: Int): Pair<Int, Int> {
        val scale = min(1.0, min(maxWidth.toDouble() / width, maxHeight.toDouble() / height))
        return (width * scale).toInt().coerceAtLeast(1) to (height * scale).toInt().coerceAtLeast(1)
    }

    private fun calculateSampleSize(width: Int, height: Int, targetWidth: Int, targetHeight: Int): Int {
        var sample = 1
        while (sample <= Int.MAX_VALUE / 2) {
            val next = sample * 2
            if (width / next < targetWidth || height / next < targetHeight) break
            sample = next
        }
        return sample
    }

    private fun readBounds(source: ImageSource): Pair<Int, Int> {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        source.openFileDescriptor().use { descriptor ->
            BitmapFactory.decodeFileDescriptor(descriptor.fileDescriptor, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw OperationException(OperationErrorCode.UNSUPPORTED_FORMAT, "Unable to read image bounds: ${source.displayName}")
        }
        return bounds.outWidth to bounds.outHeight
    }

    private fun readOrientation(source: ImageSource): Orientation {
        return try {
            val exif = when (source) {
                is ImageSource.FileSource -> ExifInterface(source.file)
                else -> source.openFileDescriptor().use { descriptor ->
                    ExifInterface(descriptor.fileDescriptor)
                }
            }
            Orientation(exif.rotationDegrees, exif.isFlipped)
        } catch (_: Exception) {
            Orientation(rotationDegrees = 0, isFlipped = false)
        }
    }

    private data class Orientation(val rotationDegrees: Int, val isFlipped: Boolean)
}
