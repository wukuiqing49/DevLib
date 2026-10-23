package com.wkq.photo.qrcode

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PointF
import com.google.zxing.BinaryBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.Result
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.multi.GenericMultipleBarcodeReader
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.king.zxing.util.CodeUtils
import com.wkq.photo.core.operation.getOrThrow
import java.io.File

/**
 * 本地二维码与条形码识别、生成入口，隔离上游 ZXingLite 类型。
 */
object QrCodeKit {

    const val DEFAULT_SIZE_PX = 600
    private const val MIN_SIZE_PX = 128
    private const val MAX_SIZE_PX = 2048

    enum class QrCodeStyle(val color: Int) {
        CLASSIC(Color.BLACK),
        OCEAN(Color.rgb(20, 96, 178)),
        FOREST(Color.rgb(25, 120, 80)),
        BERRY(Color.rgb(156, 48, 88))
    }

    /** 纠错等级越高，越适合在二维码中心放置 Logo。 */
    enum class ErrorCorrection(internal val value: ErrorCorrectionLevel) {
        STANDARD(ErrorCorrectionLevel.M),
        LOGO_RECOMMENDED(ErrorCorrectionLevel.H)
    }

    /**
     * 产品页面使用的二维码配置。
     * 颜色和 Logo 仍会在生成后回读验证，避免导出无法扫码的图片。
     */
    data class QrCodeRequest(
        val content: String,
        val sizePx: Int = DEFAULT_SIZE_PX,
        val foregroundColor: Int = QrCodeStyle.CLASSIC.color,
        val backgroundColor: Int = Color.WHITE,
        val logo: Bitmap? = null,
        val errorCorrection: ErrorCorrection = if (logo == null) ErrorCorrection.STANDARD else ErrorCorrection.LOGO_RECOMMENDED,
        val quietZone: Int = 4
    )

    enum class BarcodeType(internal val format: BarcodeFormat) {
        CODE_128(BarcodeFormat.CODE_128),
        EAN_13(BarcodeFormat.EAN_13),
        UPC_A(BarcodeFormat.UPC_A)
    }

    data class RecognitionResult(
        val content: String,
        val format: String,
        val points: List<PointF> = emptyList(),
    )

    fun recognize(imageFile: File): String? =
        imageFile.takeIf(File::isFile)?.let { CodeUtils.parseQRCode(it.absolutePath) }

    fun recognize(bitmap: Bitmap): String? = CodeUtils.parseQRCode(bitmap)

    fun recognizeAny(imageFile: File): RecognitionResult? =
        imageFile.takeIf(File::isFile)
            ?.let { CodeUtils.parseCodeResult(it.absolutePath) }
            ?.let { result ->
                RecognitionResult(
                    content = result.text,
                    format = result.barcodeFormat.name,
                    points = result.resultPoints.orEmpty().map { PointF(it.x, it.y) },
                )
            }

    fun recognizeAny(bitmap: Bitmap): RecognitionResult? =
        CodeUtils.parseCodeResult(bitmap)?.let { result ->
            RecognitionResult(
                content = result.text,
                format = result.barcodeFormat.name,
                points = result.resultPoints.orEmpty().map { PointF(it.x, it.y) },
            )
        }

    /** 返回图片中的全部可识别二维码和条形码。 */
    fun recognizeAll(bitmap: Bitmap): List<RecognitionResult> {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val binaryBitmap = BinaryBitmap(
            HybridBinarizer(RGBLuminanceSource(bitmap.width, bitmap.height, pixels))
        )
        val reader = MultiFormatReader()
        return try {
            GenericMultipleBarcodeReader(reader).decodeMultiple(
                binaryBitmap,
                mapOf(DecodeHintType.TRY_HARDER to true),
            ).map(::toRecognitionResult)
        } catch (_: NotFoundException) {
            emptyList()
        } finally {
            reader.reset()
        }
    }

    private fun toRecognitionResult(result: Result): RecognitionResult = RecognitionResult(
        content = result.text,
        format = result.barcodeFormat.name,
        points = result.resultPoints.orEmpty().map { PointF(it.x, it.y) },
    )

    fun create(
        content: String,
        sizePx: Int = DEFAULT_SIZE_PX,
        style: QrCodeStyle = QrCodeStyle.CLASSIC,
        logo: Bitmap? = null
    ): Bitmap = create(
        QrCodeRequest(content = content, sizePx = sizePx, foregroundColor = style.color, logo = logo)
    )

    /** 生成并验证二维码，验证失败时不返回不可扫码的图片。 */
    fun create(request: QrCodeRequest): Bitmap {
        val content = request.content.trim()
        require(content.isNotEmpty())
        require(request.sizePx in MIN_SIZE_PX..MAX_SIZE_PX)
        require(request.quietZone in 4..8)
        return QRCodeGenerator.generate(
            QRCodeRequest(
                content = content,
                sizePx = request.sizePx,
                colorStyle = QRColorStyle.Solid(request.foregroundColor, request.backgroundColor),
                logo = request.logo,
                quietZoneModules = request.quietZone,
                errorCorrection = if (request.errorCorrection == ErrorCorrection.LOGO_RECOMMENDED) {
                    QRErrorCorrection.HIGH
                } else {
                    QRErrorCorrection.MEDIUM
                }
            )
        ).getOrThrow()
    }

    fun createBarcode(
        content: String,
        type: BarcodeType,
        widthPx: Int,
        heightPx: Int
    ): Bitmap {
        require(content.isNotBlank())
        require(widthPx in 256..4096)
        require(heightPx in 80..1024)
        return checkNotNull(CodeUtils.createBarCode(content.trim(), type.format, widthPx, heightPx))
    }

}
