package com.wkq.util.exif

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import java.io.File

object CleanJpegExporter {

    fun isJpeg(file: File): Boolean {
        if (!file.isFile || file.length() < JPEG_HEADER_SIZE) return false
        return file.inputStream().use { input ->
            input.read() == JPEG_SOI_FIRST && input.read() == JPEG_SOI_SECOND
        }
    }

    fun exportCleanJpeg(sourceFile: File, outputFile: File): Result<File> {
        return exportJpeg(
            sourceFile = sourceFile,
            outputFile = outputFile,
            maxDimension = MAX_DECODE_SIZE,
            quality = JPEG_QUALITY
        )
    }

    fun exportJpeg(
        sourceFile: File,
        outputFile: File,
        maxDimension: Int,
        quality: Int
    ): Result<File> {
        return runCatching {
            require(maxDimension in 256..MAX_DECODE_SIZE) { "Unsupported JPEG dimension" }
            require(quality in 40..100) { "Unsupported JPEG quality" }
            val bitmap = decodeBitmap(sourceFile, maxDimension)
                ?: throw IllegalArgumentException("Unsupported image format")
            try {
                val jpegBitmap = bitmap.withOpaqueWhiteBackground()
                try {
                    outputFile.outputStream().buffered().use { output ->
                        if (!jpegBitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)) {
                            throw IllegalStateException("Failed to encode JPEG")
                        }
                    }
                } finally {
                    if (jpegBitmap !== bitmap) {
                        jpegBitmap.recycle()
                    }
                }
            } finally {
                bitmap.recycle()
            }
            outputFile
        }.onFailure {
            outputFile.delete()
        }
    }

    /**
     * 解码图片为 Bitmap。
     *
     * - API 28+：使用 [ImageDecoder]，显式指定 ALLOCATOR_SOFTWARE 保证能在软件 Canvas 绘制及压缩编码。
     * - API < 28：使用 [BitmapFactory]，首次尝试全量解码；若发生 OOM 则自动以
     *   inSampleSize = 2 / 4 重试，保证低端机不崩溃，同时尽量保留分辨率。
     */
    private fun decodeBitmap(file: File, maxDimension: Int): Bitmap? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val decoderResult = runCatching {
                val source = ImageDecoder.createSource(file)
                ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    // 必须指定为 ALLOCATOR_SOFTWARE，否则默认 HARDWARE 位图无法在软件 Canvas 绘制或编码
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    val w = info.size.width
                    val h = info.size.height
                    val max = maxOf(w, h)
                    if (max > maxDimension) {
                        val scale = maxDimension.toFloat() / max
                        decoder.setTargetSize((w * scale).toInt(), (h * scale).toInt())
                    }
                }
            }.getOrNull()
            if (decoderResult != null) return decoderResult
        }
        // 若 ImageDecoder 失败或 API < 28，降级至 BitmapFactory 兼容重试
        return decodeBitmapCompat(file, maxDimension)
    }

    /**
     * 兼容低版本的解码方案，最多尝试 3 次降采样（1x → 2x → 4x）。
     * 降采样只在 OOM 时才触发，正常情况下输出全分辨率 Bitmap。
     */
    private fun decodeBitmapCompat(file: File, maxDimension: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var baseSampleSize = 1
        while (
            bounds.outWidth / baseSampleSize > maxDimension ||
            bounds.outHeight / baseSampleSize > maxDimension
        ) {
            baseSampleSize *= 2
        }
        val sampleSizes = intArrayOf(
            baseSampleSize,
            baseSampleSize * 2,
            baseSampleSize * 4
        )
        for (sampleSize in sampleSizes) {
            var decodedBitmap: Bitmap? = null
            try {
                val opts = BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                    // 优先 ARGB_8888 保留完整色深；低版本不支持 RGB_565 alpha
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                decodedBitmap = BitmapFactory.decodeFile(file.absolutePath, opts) ?: continue
                val normalizedBitmap = decodedBitmap.normalizedForExifOrientation(file)
                if (normalizedBitmap !== decodedBitmap) {
                    decodedBitmap.recycle()
                }
                return normalizedBitmap
            } catch (_: OutOfMemoryError) {
                // 方向归一化也可能额外分配 Bitmap，内存不足时继续更低采样重试。
                decodedBitmap?.recycle()
            }
        }
        return null // 所有级别均失败
    }

    /** BitmapFactory 不会应用 EXIF 方向，回退解码后需在像素层归一化。 */
    private fun Bitmap.normalizedForExifOrientation(sourceFile: File): Bitmap {
        val orientation = runCatching {
            ExifInterface(sourceFile).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val matrix = Matrix().apply {
            when (orientation) {
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> postScale(-1f, 1f)
                ExifInterface.ORIENTATION_ROTATE_180 -> postRotate(180f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> postScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> {
                    postRotate(90f)
                    postScale(-1f, 1f)
                }
                ExifInterface.ORIENTATION_ROTATE_90 -> postRotate(90f)
                ExifInterface.ORIENTATION_TRANSVERSE -> {
                    postRotate(-90f)
                    postScale(-1f, 1f)
                }
                ExifInterface.ORIENTATION_ROTATE_270 -> postRotate(-90f)
            }
        }
        if (matrix.isIdentity) return this
        return Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
    }

    private fun Bitmap.withOpaqueWhiteBackground(): Bitmap {
        if (!hasAlpha()) return this
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(output).apply {
            drawColor(Color.WHITE)
            drawBitmap(this@withOpaqueWhiteBackground, 0f, 0f, null)
        }
        return output
    }

    private const val JPEG_QUALITY = 95
    private const val JPEG_HEADER_SIZE = 2L
    private const val JPEG_SOI_FIRST = 0xFF
    private const val JPEG_SOI_SECOND = 0xD8
    /** 解码上限：4096px，4096×3072×4bytes ≈ 48MB，防止超大图 OOM */
    private const val MAX_DECODE_SIZE = 4096
}
