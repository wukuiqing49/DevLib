package com.phototoolkit.engine

import androidx.exifinterface.media.ExifInterface

enum class ImageResizeMode(internal val nativeValue: Int) {
    /** 等比缩放到目标边界内，输出尺寸可能小于目标宽高。 */
    CONTAIN(0),
    /** 等比缩放并居中裁剪，输出严格匹配目标宽高。 */
    COVER_CROP(1),
    /** 非等比拉伸，输出严格匹配目标宽高。 */
    STRETCH(2)
}

data class ImageResizeSpec(
    val width: Int,
    val height: Int,
    val mode: ImageResizeMode = ImageResizeMode.CONTAIN
) {
    init { require(width > 0 && height > 0) }
}

data class JpegCompressionResult(
    val errorMessage: String,
    val outputBytes: Long,
    val targetBytes: Long,
    val quality: Int,
    val width: Int,
    val height: Int
) {
    val isSuccess: Boolean get() = errorMessage.isEmpty()
    val targetMet: Boolean get() = isSuccess && outputBytes <= targetBytes
}

data class NativeRuntimeOptions(
    val concurrency: Int = 2,
    val maxCacheOperations: Int = 100,
    val maxCacheMemoryBytes: Long = 128L * 1024L * 1024L
) {
    init {
        require(concurrency in 1..16)
        require(maxCacheOperations in 0..10_000)
        require(maxCacheMemoryBytes in 0..2L * 1024L * 1024L * 1024L)
    }
}

/** libvips 的 Kotlin JNI 调用入口。所有路径均使用应用可访问的绝对路径。 */
object VipsNative {
    init { System.loadLibrary("vips_smoke") }

    /** 初始化 libvips。首次调用任意功能时会自动执行。 */
    @JvmStatic external fun initialize(): String
    @JvmStatic fun configureRuntime(options: NativeRuntimeOptions = NativeRuntimeOptions()): String =
        configureRuntimeNative(options.concurrency, options.maxCacheOperations, options.maxCacheMemoryBytes)
    @JvmStatic private external fun configureRuntimeNative(
        concurrency: Int,
        maxCacheOperations: Int,
        maxCacheMemoryBytes: Long
    ): String
    /** 返回正在加载的 libvips 版本。 */
    @JvmStatic external fun version(): String
    /** 读取图片宽、高、波段数和像素格式。 */
    @JvmStatic external fun imageInfo(inputPath: String): String
    /** 等比缩放图片，scale 例如 0.5。 */
    @JvmStatic external fun resize(inputPath: String, outputPath: String, scale: Double): String
    /** 按明确的尺寸模式缩放；新业务优先使用此方法。 */
    @JvmStatic fun resizeTo(inputPath: String, outputPath: String, spec: ImageResizeSpec): String {
        return resizeToNative(inputPath, outputPath, spec.width, spec.height, spec.mode.nativeValue)
    }
    @JvmStatic private external fun resizeToNative(
        inputPath: String,
        outputPath: String,
        width: Int,
        height: Int,
        mode: Int
    ): String
    /** 生成指定最大宽度的缩略图。 */
    @JvmStatic external fun thumbnail(inputPath: String, outputPath: String, width: Int): String
    /** 按像素坐标裁剪区域。 */
    @JvmStatic external fun crop(inputPath: String, outputPath: String, left: Int, top: Int, width: Int, height: Int): String
    /** 顺时针旋转，angle 仅支持 90、180、270。 */
    @JvmStatic external fun rotate(inputPath: String, outputPath: String, angle: Int): String
    /** 按输出文件扩展名转换格式，例如 .jpg、.png、.webp。 */
    @JvmStatic external fun convert(inputPath: String, outputPath: String): String
    /** 按输出扩展名编码，并统一应用质量与 metadata 策略。 */
    @JvmStatic external fun encode(
        inputPath: String,
        outputPath: String,
        quality: Int,
        metadataPolicy: Int
    ): String
    /** 水平或垂直翻转图片。*/
    @JvmStatic external fun flip(inputPath: String, outputPath: String, horizontal: Boolean): String
    /** 高斯模糊，sigma 越大模糊越明显。*/
    @JvmStatic external fun blur(inputPath: String, outputPath: String, sigma: Double): String
    /** 对图片执行锐化。*/
    @JvmStatic external fun sharpen(inputPath: String, outputPath: String): String
    /** 根据画面亮度和离散程度自适应调整曝光与对比度。 */
    @JvmStatic external fun autoEnhance(inputPath: String, outputPath: String): String
    /** 调整亮度与对比度。brightness 为偏移量，contrast 为倍率。*/
    @JvmStatic external fun adjust(inputPath: String, outputPath: String, brightness: Double, contrast: Double): String
    /** 反相图片颜色。*/
    @JvmStatic external fun invert(inputPath: String, outputPath: String): String
    /** 转换为灰度图。*/
    @JvmStatic external fun grayscale(inputPath: String, outputPath: String): String
    /** 计算所有像素通道的平均值。*/
    @JvmStatic external fun average(inputPath: String): String
    /** 读取平均值、最小值和最大值，用于图片质量和曝光分析。 */
    @JvmStatic external fun statistics(inputPath: String): String
    /** 按 EXIF 方向旋转真实像素，并清除输出文件的方向依赖。 */
    @JvmStatic fun autoRotate(inputPath: String, outputPath: String): String {
        val orientation = runCatching {
            ExifInterface(inputPath).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
            .takeIf { it in ExifInterface.ORIENTATION_NORMAL..ExifInterface.ORIENTATION_ROTATE_270 }
            ?: ExifInterface.ORIENTATION_NORMAL
        return autoRotateWithOrientation(inputPath, outputPath, orientation)
    }

    @JvmStatic private external fun autoRotateWithOrientation(
        inputPath: String,
        outputPath: String,
        orientation: Int,
    ): String
    /** 使用注意力算法智能裁剪到指定尺寸。 */
    @JvmStatic external fun smartCrop(inputPath: String, outputPath: String, width: Int, height: Int): String
    /** 任意角度旋转图片，angle 为顺时针角度。 */
    @JvmStatic external fun freeRotate(inputPath: String, outputPath: String, angle: Double): String
    /** 中值滤波，可消除椒盐噪声；size 必须为奇数。 */
    @JvmStatic external fun median(inputPath: String, outputPath: String, size: Int): String
    /** Sobel 边缘检测。 */
    @JvmStatic external fun sobel(inputPath: String, outputPath: String): String
    /** Canny 边缘检测。 */
    @JvmStatic external fun canny(inputPath: String, outputPath: String): String
    /** 直方图均衡化，增强暗部和亮部层次。 */
    @JvmStatic external fun histEqual(inputPath: String, outputPath: String): String
    /** 输出可预览的灰度直方图图片。 */
    @JvmStatic external fun histogram(inputPath: String, outputPath: String): String
    /** Gamma 校正；exponent 小于 1 提亮，大于 1 压暗。 */
    @JvmStatic external fun gamma(inputPath: String, outputPath: String, exponent: Double): String
    /** 将灰度亮度映射为伪彩色，便于热力图和分析展示。 */
    @JvmStatic external fun falseColour(inputPath: String, outputPath: String): String
    /** 提取指定颜色通道，RGB 图片可使用 0、1、2。 */
    @JvmStatic external fun extractBand(inputPath: String, outputPath: String, band: Int): String
    /** 将两张图片横向或纵向拼接，自动补齐不同尺寸。 */
    @JvmStatic external fun join(inputPath: String, secondPath: String, outputPath: String, horizontal: Boolean): String
    /** 将第二张图片缩放后插入第一张图片右下角。 */
    @JvmStatic external fun pictureInPicture(inputPath: String, secondPath: String, outputPath: String): String
    /** 按混合模式合成两张图片：0=叠加、1=正片叠底、2=滤色、3=差值。 */
    @JvmStatic external fun blend(inputPath: String, secondPath: String, outputPath: String, mode: Int): String
    /** 在固定画布上合成背景和有序 PNG 图层；sampling 0=nearest、1=lanczos。 */
    @JvmStatic external fun composePoster(
        backgroundPath: String?,
        layerPaths: Array<String>,
        layerLeft: IntArray,
        layerTop: IntArray,
        layerWidth: IntArray,
        layerHeight: IntArray,
        layerSampling: IntArray,
        outputPath: String,
        canvasWidth: Int,
        canvasHeight: Int,
        backgroundRed: Int,
        backgroundGreen: Int,
        backgroundBlue: Int
    ): String
    /** 将 JPEG 压缩到目标 KB；成功返回空字符串，无法达到目标时返回错误且保留旧输出。 */
    @JvmStatic external fun compressJpeg(inputPath: String, outputPath: String, targetKb: Int): String
    /** 将 JPEG 压缩到目标 KB，并返回最终质量、尺寸和文件大小。 */
    @JvmStatic external fun compressJpegDetailed(
        inputPath: String,
        outputPath: String,
        targetKb: Int
    ): JpegCompressionResult
    /** 精确按字节上限压缩 JPEG，quality 作为允许使用的最高质量。 */
    @JvmStatic external fun compressJpegToBytes(
        inputPath: String,
        outputPath: String,
        targetBytes: Long,
        maxQuality: Int
    ): JpegCompressionResult
    /** 生成 1080x1080 的居中商品主图。 */
    @JvmStatic external fun productSquare(inputPath: String, outputPath: String): String
    /** 灰度、直方图均衡与锐化，输出保留层次的清晰扫描件。 */
    @JvmStatic external fun ocrEnhance(inputPath: String, outputPath: String): String
    /** 使用局部亮度阈值生成适合文档的纯黑白图像。 */
    @JvmStatic external fun blackWhite(inputPath: String, outputPath: String): String
    /** 按证件照比例居中裁剪为 413x591 像素，不依赖源图的色彩解释标签。 */
    @JvmStatic external fun idPhotoCrop(inputPath: String, outputPath: String): String
    /** 重新编码并去除 EXIF、GPS 等元数据。 */
    @JvmStatic external fun stripMetadata(inputPath: String, outputPath: String): String
    /** 返回两张图片的粗略视觉相似度百分比。 */
    @JvmStatic external fun visualSimilarity(inputPath: String, secondPath: String): String
    /** 用 MODNet 输出的人像软蒙版合成指定 RGB 纯色背景。 */
    @JvmStatic external fun replaceBackground(inputPath: String, outputPath: String, alphaMask: ByteArray, maskWidth: Int, maskHeight: Int, red: Int, green: Int, blue: Int): String
    /** 用 MODNet 输出的人像软蒙版导出带透明通道的 PNG 前景。 */
    @JvmStatic external fun extractPortrait(inputPath: String, outputPath: String, alphaMask: ByteArray, maskWidth: Int, maskHeight: Int): String
    /**
     * Uses an alpha mask and the detected face rectangle to compose a fixed-size ID photo.
     * Face coordinates use the same pixel space as inputPath.
     */
    @JvmStatic external fun createIdPhoto(
        inputPath: String,
        outputPath: String,
        alphaMask: ByteArray,
        maskWidth: Int,
        maskHeight: Int,
        faceLeft: Double,
        faceTop: Double,
        faceWidth: Double,
        faceHeight: Double,
        outputWidth: Int,
        outputHeight: Int,
        outputDpi: Int,
        faceHeightRatio: Double,
        faceTopRatio: Double,
        autoComposition: Boolean,
        faceHeightMinRatio: Double,
        faceHeightMaxRatio: Double,
        faceTopMinRatio: Double,
        faceTopMaxRatio: Double,
        faceCenterXRatio: Double,
        portraitBottomRatio: Double,
        maxPortraitWidthRatio: Double,
        preferBottomAlignment: Boolean,
        compositionMetrics: DoubleArray,
        strictComposition: Boolean,
        preserveBackground: Boolean,
        red: Int,
        green: Int,
        blue: Int
    ): String

    /** Places ID photos in a fixed grid on a white print sheet. */
    @JvmStatic external fun createIdPhotoSheet(
        inputPath: String,
        outputPath: String,
        paperWidth: Int,
        paperHeight: Int,
        columns: Int,
        rows: Int,
        copies: Int,
        margin: Int,
        gap: Int
    ): String
}
