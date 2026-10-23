package com.wkq.photo.qrcode

import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.king.zxing.util.CodeUtils
import com.wkq.photo.core.operation.OperationBudget
import com.wkq.photo.core.operation.OperationCancellation
import com.wkq.photo.core.operation.OperationErrorCode
import com.wkq.photo.core.operation.OperationException
import com.wkq.photo.core.operation.OperationProgress
import com.wkq.photo.core.operation.OperationResult
import com.wkq.photo.core.operation.OperationRunner
import com.wkq.photo.core.operation.OperationStage
import com.wkq.photo.core.operation.OperationStatistics

object QRCodeGenerator {
    fun generate(
        payload: QRPayload,
        options: QRCodeRequest = QRCodeRequest(content = payload.encode()),
        budget: OperationBudget = defaultBudget(options.sizePx),
        cancellation: OperationCancellation = OperationCancellation.NONE,
        onProgress: (OperationProgress) -> Unit = {}
    ): OperationResult<Bitmap> = generate(
        request = options.copy(content = payload.encode()),
        budget = budget,
        cancellation = cancellation,
        onProgress = onProgress
    )

    fun generate(
        request: QRCodeRequest,
        budget: OperationBudget = defaultBudget(request.sizePx),
        cancellation: OperationCancellation = OperationCancellation.NONE,
        onProgress: (OperationProgress) -> Unit = {}
    ): OperationResult<Bitmap> = OperationRunner.run(budget, cancellation, onProgress) { context ->
        require(request.content.isNotBlank()) { "QR content is empty" }
        context.report(OperationStage.VALIDATING, 0, 1)
        context.checkDimensions(request.sizePx, request.sizePx)
        context.checkTotalPixels(request.sizePx.toLong() * request.sizePx)
        context.report(OperationStage.PROCESSING, 0, 2)

        val correction = if (request.logo != null || request.backgroundImage != null) {
            ErrorCorrectionLevel.H
        } else if (request.errorCorrection == QRErrorCorrection.HIGH) {
            ErrorCorrectionLevel.H
        } else {
            ErrorCorrectionLevel.M
        }
        val matrix = try {
            QRCodeWriter().encode(
                request.content,
                BarcodeFormat.QR_CODE,
                1,
                1,
                mapOf(
                    EncodeHintType.CHARACTER_SET to "UTF-8",
                    EncodeHintType.ERROR_CORRECTION to correction,
                    EncodeHintType.MARGIN to 0
                )
            )
        } catch (error: Exception) {
            throw OperationException(OperationErrorCode.ENCODE_FAILED, "QR content is too large or unsupported", error)
        }
        context.report(OperationStage.PROCESSING, 1, 2)
        val veilCandidates = if (request.backgroundImage == null) {
            listOf(request.backgroundVeilAlpha)
        } else {
            listOf(request.backgroundVeilAlpha, 0.9f, 0.94f).distinct()
        }
        var bitmap: Bitmap? = null
        for (veilAlpha in veilCandidates) {
            context.checkActive()
            bitmap?.recycle()
            bitmap = QRCodeRenderer.render(matrix, request.copy(backgroundVeilAlpha = veilAlpha), context)
            if (!request.verifyScannable || recognizes(bitmap, request.content)) break
        }
        val output = checkNotNull(bitmap)
        context.report(OperationStage.PROCESSING, 2, 2)
        if (request.verifyScannable && !recognizes(output, request.content)) {
            output.recycle()
            throw OperationException(
                OperationErrorCode.ENCODE_FAILED,
                "QR style cannot be scanned; increase contrast or remove the image"
            )
        }
        context.report(OperationStage.COMPLETED, 1, 1)
        OperationResult.Success(
            value = output,
            statistics = OperationStatistics(
                durationMillis = context.elapsedMillis(),
                inputCount = 1,
                outputCount = 1,
                outputPixels = request.sizePx.toLong() * request.sizePx,
                outputWidth = request.sizePx,
                outputHeight = request.sizePx
            )
        )
    }

    private fun defaultBudget(sizePx: Int) = OperationBudget(
        maxWidth = 2048,
        maxHeight = 2048,
        maxTotalPixels = 2048L * 2048L,
        maxPages = 1,
        timeoutMillis = if (sizePx > 1024) 15_000 else 8_000
    )

    private fun recognizes(bitmap: Bitmap, expectedContent: String): Boolean =
        runCatching { CodeUtils.parseCodeResult(bitmap)?.text == expectedContent }.getOrDefault(false)
}
