package com.phototoolkit.engine

import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import com.google.android.play.core.assetpacks.AssetPackManagerFactory
import com.google.android.play.core.assetpacks.AssetPackStateUpdateListener
import com.google.android.gms.tasks.Tasks
import com.google.android.play.core.assetpacks.model.AssetPackStatus
import org.tensorflow.lite.Interpreter
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.MappedByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** MODNet 离线人像抠图。模型输入为 NCHW 512x512，输出为软透明度蒙版。 */
object PortraitMatting {
    private const val MODEL_NAME = "modnet.tflite"
    private const val MODEL_PACK = "portrait_model_assets"
    private const val MODEL_SIZE = 512
    private const val MAX_MASK_SIDE = 2048
    private const val MAX_DECODED_ROI_SIDE = 2048
    private var interpreter: Interpreter? = null

    data class AlphaMask(val bytes: ByteArray, val width: Int, val height: Int)

    /** 人像模型或按需资源不可用，调用方可据此给出可恢复的提示。 */
    class ModelUnavailableException(cause: Throwable? = null) : IllegalStateException(
        "Portrait model is unavailable",
        cause,
    )

    /** 模型已执行但没有给出可用于合成的前景蒙版。 */
    class UnusableMaskException : IllegalStateException("Portrait mask does not contain a usable subject")

    /** 下载并初始化按需模型。请从后台线程调用。 */
    @Synchronized fun prepare(context: Context) { model(context) }

    @Synchronized fun isPrepared(): Boolean = interpreter != null

    @Synchronized fun createMask(context: Context, imagePath: String): AlphaMask {
        val bounds = decodeBounds(imagePath)
        return createMaskForRegion(
            context = context,
            source = decodeForModel(imagePath),
            region = PortraitRoi(0, 0, bounds.first, bounds.second),
            imageWidth = bounds.first,
            imageHeight = bounds.second,
        )
    }

    /** 只对头肩区域做高分辨率推理，再映射回完整原图坐标。 */
    @Synchronized fun createMask(
        context: Context,
        imagePath: String,
        face: PortraitFace,
        imageWidth: Int,
        imageHeight: Int,
    ): AlphaMask {
        val region = PortraitRoiPlanner.plan(face, imageWidth, imageHeight)
        return createMaskForRegion(
            context = context,
            source = decodeRegionForModel(imagePath, region),
            region = region,
            imageWidth = imageWidth,
            imageHeight = imageHeight,
        )
    }

    private fun createMaskForRegion(
        context: Context,
        source: Bitmap,
        region: PortraitRoi,
        imageWidth: Int,
        imageHeight: Int,
    ): AlphaMask {
        val scale = min(MODEL_SIZE.toFloat() / source.width, MODEL_SIZE.toFloat() / source.height)
        val drawWidth = max(1, (source.width * scale).toInt())
        val drawHeight = max(1, (source.height * scale).toInt())
        val offsetX = (MODEL_SIZE - drawWidth) / 2
        val offsetY = (MODEL_SIZE - drawHeight) / 2
        val inputBitmap = Bitmap.createBitmap(MODEL_SIZE, MODEL_SIZE, Bitmap.Config.ARGB_8888)
        Canvas(inputBitmap).apply {
            drawColor(Color.BLACK)
            drawBitmap(source, null, Rect(offsetX, offsetY, offsetX + drawWidth, offsetY + drawHeight), Paint(Paint.FILTER_BITMAP_FLAG))
        }
        if (source !== inputBitmap) source.recycle()

        val input = ByteBuffer.allocateDirect(4 * 3 * MODEL_SIZE * MODEL_SIZE).order(ByteOrder.nativeOrder())
        for (channel in 0..2) for (y in 0 until MODEL_SIZE) for (x in 0 until MODEL_SIZE) {
            val color = inputBitmap.getPixel(x, y)
            val value = when (channel) { 0 -> Color.red(color); 1 -> Color.green(color); else -> Color.blue(color) }
            input.putFloat(value / 127.5f - 1.0f)
        }
        inputBitmap.recycle()
        input.rewind()
        val output = ByteBuffer.allocateDirect(4 * MODEL_SIZE * MODEL_SIZE).order(ByteOrder.nativeOrder())
        model(context).run(input, output)
        output.rewind()
        val fullMask = ByteArray(MODEL_SIZE * MODEL_SIZE)
        for (index in fullMask.indices) fullMask[index] = (output.float.coerceIn(0f, 1f) * 255f).toInt().toByte()
        val regionMask = ByteArray(drawWidth * drawHeight)
        for (y in 0 until drawHeight) {
            System.arraycopy(fullMask, (y + offsetY) * MODEL_SIZE + offsetX, regionMask, y * drawWidth, drawWidth)
        }
        return requireUsableMask(
            mapRegionToImage(regionMask, drawWidth, drawHeight, region, imageWidth, imageHeight),
        )
    }

    /**
     * 避免将模型异常、输入不匹配产生的全透明/全不透明蒙版继续导出为“成功”的空图片。
     * 阈值只排除退化结果，不限制人物在画面中的正常占比。
     */
    internal fun requireUsableMask(mask: AlphaMask): AlphaMask {
        var transparentPixels = 0
        var foregroundPixels = 0
        var minAlpha = 255
        var maxAlpha = 0
        mask.bytes.forEach { value ->
            val alpha = unsigned(value)
            minAlpha = min(minAlpha, alpha)
            maxAlpha = max(maxAlpha, alpha)
            if (alpha <= TRANSPARENT_ALPHA) transparentPixels++
            if (alpha >= FOREGROUND_ALPHA) foregroundPixels++
        }
        val minimumPixels = max(MINIMUM_MASK_PIXELS, mask.bytes.size / MINIMUM_MASK_DIVISOR)
        val hasUsableForeground =
            maxAlpha - minAlpha >= MINIMUM_ALPHA_RANGE &&
                transparentPixels >= minimumPixels &&
                foregroundPixels >= minimumPixels
        if (!hasUsableForeground) throw UnusableMaskException()
        return mask
    }

    private fun decodeForModel(path: String): Bitmap {
        val bounds = decodeBounds(path)
        var sample = 1
        while (bounds.first / sample > MAX_DECODED_ROI_SIDE || bounds.second / sample > MAX_DECODED_ROI_SIDE) {
            sample *= 2
        }
        return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 })
            ?: throw IllegalArgumentException("无法解码图片")
    }

    private fun decodeRegionForModel(path: String, region: PortraitRoi): Bitmap {
        var sample = 1
        while (region.width / sample > MAX_DECODED_ROI_SIDE || region.height / sample > MAX_DECODED_ROI_SIDE) {
            sample *= 2
        }
        val decoder = BitmapRegionDecoder.newInstance(path, false)
            ?: throw IllegalArgumentException("无法读取图片区域")
        return try {
            decoder.decodeRegion(
                Rect(region.left, region.top, region.right, region.bottom),
                BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                },
            ) ?: throw IllegalArgumentException("无法解码图片区域")
        } finally {
            decoder.recycle()
        }
    }

    private fun decodeBounds(path: String): Pair<Int, Int> {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "无法读取图片" }
        return bounds.outWidth to bounds.outHeight
    }

    private fun mapRegionToImage(
        regionMask: ByteArray,
        regionMaskWidth: Int,
        regionMaskHeight: Int,
        region: PortraitRoi,
        imageWidth: Int,
        imageHeight: Int,
    ): AlphaMask {
        val scale = minOf(
            1.0,
            MAX_MASK_SIDE.toDouble() / imageWidth,
            MAX_MASK_SIDE.toDouble() / imageHeight,
            regionMaskWidth.toDouble() / region.width,
            regionMaskHeight.toDouble() / region.height,
        )
        val fullWidth = max(1, (imageWidth * scale).roundToInt())
        val fullHeight = max(1, (imageHeight * scale).roundToInt())
        val targetLeft = (region.left * scale).roundToInt().coerceIn(0, fullWidth - 1)
        val targetTop = (region.top * scale).roundToInt().coerceIn(0, fullHeight - 1)
        val targetRight = (region.right * scale).roundToInt().coerceIn(targetLeft + 1, fullWidth)
        val targetBottom = (region.bottom * scale).roundToInt().coerceIn(targetTop + 1, fullHeight)
        val targetWidth = targetRight - targetLeft
        val targetHeight = targetBottom - targetTop
        val scaledRegion = resizeMask(regionMask, regionMaskWidth, regionMaskHeight, targetWidth, targetHeight)
        val result = ByteArray(fullWidth * fullHeight)
        for (y in 0 until targetHeight) {
            System.arraycopy(
                scaledRegion,
                y * targetWidth,
                result,
                (targetTop + y) * fullWidth + targetLeft,
                targetWidth,
            )
        }
        return AlphaMask(result, fullWidth, fullHeight)
    }

    private fun resizeMask(
        source: ByteArray,
        sourceWidth: Int,
        sourceHeight: Int,
        targetWidth: Int,
        targetHeight: Int,
    ): ByteArray {
        if (sourceWidth == targetWidth && sourceHeight == targetHeight) return source
        val result = ByteArray(targetWidth * targetHeight)
        for (y in 0 until targetHeight) {
            val sourceY = ((y + 0.5) * sourceHeight / targetHeight - 0.5).coerceIn(0.0, sourceHeight - 1.0)
            val y0 = sourceY.toInt()
            val y1 = min(y0 + 1, sourceHeight - 1)
            val yWeight = sourceY - y0
            for (x in 0 until targetWidth) {
                val sourceX = ((x + 0.5) * sourceWidth / targetWidth - 0.5).coerceIn(0.0, sourceWidth - 1.0)
                val x0 = sourceX.toInt()
                val x1 = min(x0 + 1, sourceWidth - 1)
                val xWeight = sourceX - x0
                val top = unsigned(source[y0 * sourceWidth + x0]) * (1.0 - xWeight) +
                    unsigned(source[y0 * sourceWidth + x1]) * xWeight
                val bottom = unsigned(source[y1 * sourceWidth + x0]) * (1.0 - xWeight) +
                    unsigned(source[y1 * sourceWidth + x1]) * xWeight
                result[y * targetWidth + x] = (top * (1.0 - yWeight) + bottom * yWeight)
                    .roundToInt()
                    .coerceIn(0, 255)
                    .toByte()
            }
        }
        return result
    }

    private fun unsigned(value: Byte): Int = value.toInt() and 0xFF

    private fun model(context: Context): Interpreter {
        interpreter?.let { return it }
        val mapped = openModel(context.applicationContext)
        return Interpreter(mapped, Interpreter.Options().setNumThreads(4)).also { interpreter = it }
    }

    private fun openModel(context: Context): MappedByteBuffer {
        // Debug/Internal 直装包携带本地模型，不能先触发 Play Asset Delivery。
        if (shouldUseBundledModel(context)) openBundledModel(context)?.let { return it }
        findAssetPackModel(context)?.let { return mapFile(it) }
        openBundledModel(context)?.let { return it }
        throw ModelUnavailableException()
    }

    private fun findAssetPackModel(context: Context): File? {
        val manager = AssetPackManagerFactory.getInstance(context)
        manager.getPackLocation(MODEL_PACK)?.let { location ->
            return File(location.assetsPath(), MODEL_NAME).takeIf { it.isFile }
        }

        val completed = CountDownLatch(1)
        var failed = false
        val listener = AssetPackStateUpdateListener { state ->
            if (state.name() != MODEL_PACK) return@AssetPackStateUpdateListener
            when (state.status()) {
                AssetPackStatus.COMPLETED -> completed.countDown()
                AssetPackStatus.FAILED, AssetPackStatus.CANCELED -> {
                    failed = true
                    completed.countDown()
                }
            }
        }
        manager.registerListener(listener)
        return try {
            Tasks.await(
                manager.fetch(listOf(MODEL_PACK)),
                MODEL_DOWNLOAD_TIMEOUT_SECONDS,
                TimeUnit.SECONDS,
            )
            manager.getPackLocation(MODEL_PACK)?.let { location ->
                File(location.assetsPath(), MODEL_NAME).takeIf { it.isFile }
            } ?: run {
                if (!completed.await(MODEL_DOWNLOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS) || failed) null
                else manager.getPackLocation(MODEL_PACK)?.let { location -> File(location.assetsPath(), MODEL_NAME).takeIf { it.isFile } }
            }
        } catch (_: Exception) {
            null
        } finally {
            manager.unregisterListener(listener)
        }
    }

    private fun openBundledModel(context: Context): MappedByteBuffer? = try {
        val descriptor = context.assets.openFd(MODEL_NAME)
        try {
            FileInputStream(descriptor.fileDescriptor).channel.use { channel ->
                channel.map(FileChannel.MapMode.READ_ONLY, descriptor.startOffset, descriptor.declaredLength)
            }
        } finally {
            descriptor.close()
        }
    } catch (_: Exception) {
        null
    }

    private fun mapFile(file: File): MappedByteBuffer = FileInputStream(file).channel.use { channel ->
        channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size())
    }

    private fun isDebuggable(context: Context): Boolean =
        context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    private fun shouldUseBundledModel(context: Context): Boolean {
        if (isDebuggable(context)) return true
        val flags = android.content.pm.PackageManager.GET_META_DATA
        return runCatching {
            context.packageManager
                .getApplicationInfo(context.packageName, flags)
                .metaData
                ?.getBoolean("com.wkq.photo.PORTRAIT_LOCAL_MODEL", false)
                ?: false
        }.getOrDefault(false)
    }

    private const val TRANSPARENT_ALPHA = 16
    private const val FOREGROUND_ALPHA = 239
    private const val MINIMUM_ALPHA_RANGE = 24
    private const val MINIMUM_MASK_PIXELS = 16
    private const val MINIMUM_MASK_DIVISOR = 5_000
    private const val MODEL_DOWNLOAD_TIMEOUT_SECONDS = 90L
}
