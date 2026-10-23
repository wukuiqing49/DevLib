package com.phototoolkit.engine

import android.content.Context
import android.util.Log
import com.wkq.photo.core.operation.OperationBudget
import com.wkq.photo.core.operation.OperationCancellation
import com.wkq.photo.core.operation.OperationErrorCode
import com.wkq.photo.core.operation.OperationException
import com.wkq.photo.core.operation.OperationProgress
import com.wkq.photo.core.operation.OperationResult
import com.wkq.photo.core.operation.OperationRunner
import com.wkq.photo.core.operation.OperationStage
import com.wkq.photo.core.operation.OperationStatistics
import com.wkq.photo.core.utils.AtomicFileOutput
import java.io.File

/**
 * Produces a single-person ID photo and an optional print sheet.
 *
 * This API performs disk IO, model loading and inference. Invoke it from a worker thread.
 */
object IdPhotoProcessor {
    private const val TAG = "IdPhotoProcessor"

    fun executeCreate(
        context: Context,
        request: IdPhotoRequest,
        budget: OperationBudget = OperationBudget.DEFAULT,
        cancellation: OperationCancellation = OperationCancellation.NONE,
        onProgress: (OperationProgress) -> Unit = {}
    ): OperationResult<IdPhotoResult.Success> = OperationRunner.run(budget, cancellation, onProgress) { operation ->
        operation.report(OperationStage.VALIDATING, 0, 1)
        operation.checkDimensions(request.spec.widthPx, request.spec.heightPx)
        operation.checkTotalPixels(request.spec.widthPx.toLong() * request.spec.heightPx)
        val output = File(request.outputPath)
        val success = AtomicFileOutput.write(output) { temporary ->
            when (val result = create(context, request.copy(outputPath = temporary.path))) {
                is IdPhotoResult.Success -> result
                is IdPhotoResult.Failure -> throw result.toOperationException()
            }
        }
        operation.report(OperationStage.COMPLETED, 1, 1)
        OperationResult.Success(
            success.copy(outputPath = output.path),
            OperationStatistics(
                durationMillis = operation.elapsedMillis(),
                inputCount = 1,
                outputCount = 1,
                inputBytes = File(request.inputPath).length(),
                outputBytes = output.length(),
                outputPixels = request.spec.widthPx.toLong() * request.spec.heightPx,
                outputWidth = request.spec.widthPx,
                outputHeight = request.spec.heightPx
            )
        )
    }

    fun executePrintSheet(
        request: IdPhotoSheetRequest,
        budget: OperationBudget = OperationBudget.DEFAULT,
        cancellation: OperationCancellation = OperationCancellation.NONE,
        onProgress: (OperationProgress) -> Unit = {}
    ): OperationResult<IdPhotoResult.Success> = OperationRunner.run(budget, cancellation, onProgress) { operation ->
        operation.report(OperationStage.VALIDATING, 0, 1)
        operation.checkDimensions(request.paperWidthPx, request.paperHeightPx)
        operation.checkTotalPixels(request.paperWidthPx.toLong() * request.paperHeightPx)
        val output = File(request.outputPath)
        val success = AtomicFileOutput.write(output) { temporary ->
            when (val result = createPrintSheet(request.copy(outputPath = temporary.path))) {
                is IdPhotoResult.Success -> result
                is IdPhotoResult.Failure -> throw result.toOperationException()
            }
        }
        operation.report(OperationStage.COMPLETED, 1, 1)
        OperationResult.Success(
            success.copy(outputPath = output.path),
            OperationStatistics(
                durationMillis = operation.elapsedMillis(),
                inputCount = 1,
                outputCount = 1,
                inputBytes = File(request.inputPath).length(),
                outputBytes = output.length(),
                outputPixels = request.paperWidthPx.toLong() * request.paperHeightPx,
                outputWidth = request.paperWidthPx,
                outputHeight = request.paperHeightPx
            )
        )
    }

    fun create(context: Context, request: IdPhotoRequest): IdPhotoResult {
        val startedAt = android.os.SystemClock.elapsedRealtime()
        if (request.spec.editPolicy == IdPhotoEditPolicy.PROHIBITED) {
            return IdPhotoResult.Failure(IdPhotoFailure.EDITING_PROHIBITED)
        }
        val input = File(request.inputPath)
        if (!input.isFile) return IdPhotoResult.Failure(IdPhotoFailure.INVALID_INPUT)
        val output = File(request.outputPath)
        if (!output.parentFile.ensureDirectory()) return IdPhotoResult.Failure(IdPhotoFailure.OUTPUT_DIRECTORY_UNAVAILABLE)
        if (!request.outputFormat.acceptsExtension(output.extension)) {
            return IdPhotoResult.Failure(
                IdPhotoFailure.OUTPUT_VALIDATION_FAILED,
                "OUTPUT_EXTENSION_MISMATCH",
            )
        }

        val normalized = runCatching {
            File.createTempFile("id-photo-source-", ".png", context.cacheDir)
        }.getOrElse { return IdPhotoResult.Failure(IdPhotoFailure.TEMP_FILE_UNAVAILABLE) }

        try {
            val rotationError = VipsNative.autoRotate(input.absolutePath, normalized.absolutePath)
            if (rotationError.isNotEmpty()) return IdPhotoResult.Failure(IdPhotoFailure.NORMALIZATION_FAILED, rotationError)

            val analysis = runCatching {
                PortraitGeometryAnalyzer.analyze(context, normalized.absolutePath, request.spec)
            }.getOrElse { error ->
                return IdPhotoResult.Failure(error.toIdPhotoFailure(), error.message)
            }
            val compositionPlan = IdPhotoComplianceEvaluator.plan(
                request.spec,
                analysis.head,
                analysis.shoulders,
            )
            Log.d(
                TAG,
                "composition: faceTop=${"%.3f".format(java.util.Locale.ROOT, analysis.face.top.toDouble() / analysis.imageHeight)}, " +
                    "headTop=${"%.3f".format(java.util.Locale.ROOT, analysis.head.top / analysis.imageHeight)}, " +
                    "headHeight=${"%.3f".format(java.util.Locale.ROOT, analysis.head.height / analysis.imageHeight)}, " +
                    "targetTop=${"%.3f".format(java.util.Locale.ROOT, compositionPlan.faceTopRatio)}, " +
                    "targetHeight=${"%.3f".format(java.util.Locale.ROOT, compositionPlan.faceHeightRatio)}",
            )
            val sourceQuality = IdPhotoSourceQualityGate.evaluate(
                head = analysis.head,
                outputHeight = request.spec.heightPx,
                targetHeadHeightRatio = compositionPlan.faceHeightRatio,
                maxUpscaleRatio = request.spec.maxUpscaleRatio,
            )
            if (!sourceQuality.accepted) {
                return IdPhotoResult.Failure(
                    IdPhotoFailure.SOURCE_IMAGE_TOO_SMALL,
                    "requiredScale=${"%.3f".format(java.util.Locale.ROOT, sourceQuality.requiredScale)}, " +
                        "maxScale=${"%.3f".format(java.util.Locale.ROOT, request.spec.maxUpscaleRatio)}",
                )
            }
            val compositionMetrics = doubleArrayOf(Double.NaN, Double.NaN)
            val composeError = VipsNative.createIdPhoto(
                inputPath = normalized.absolutePath,
                outputPath = output.absolutePath,
                alphaMask = analysis.mask.bytes,
                maskWidth = analysis.mask.width,
                maskHeight = analysis.mask.height,
                faceLeft = analysis.head.left,
                faceTop = analysis.head.top,
                faceWidth = analysis.head.width,
                faceHeight = analysis.head.height,
                outputWidth = request.spec.widthPx,
                outputHeight = request.spec.heightPx,
                outputDpi = request.spec.outputDpi,
                faceHeightRatio = compositionPlan.faceHeightRatio,
                faceTopRatio = compositionPlan.faceTopRatio,
                autoComposition = request.spec.topPolicy == IdPhotoTopPolicy.AUTO_COMPOSITION,
                faceHeightMinRatio = request.spec.faceHeightMinRatio,
                faceHeightMaxRatio = request.spec.faceHeightMaxRatio,
                faceTopMinRatio = request.spec.faceTopMinRatio,
                faceTopMaxRatio = request.spec.faceTopMaxRatio,
                faceCenterXRatio = request.spec.faceCenterXRatio,
                portraitBottomRatio = request.spec.portraitBottomRatio,
                maxPortraitWidthRatio = request.spec.maxPortraitWidthRatio,
                preferBottomAlignment = request.spec.bottomPolicy == IdPhotoBottomPolicy.PREFER_TOUCH,
                compositionMetrics = compositionMetrics,
                strictComposition = request.spec.strictComposition,
                preserveBackground = request.spec.editPolicy == IdPhotoEditPolicy.RESTRICTED,
                red = request.backgroundColor.red,
                green = request.backgroundColor.green,
                blue = request.backgroundColor.blue
            )
            return if (composeError.isEmpty()) {
                request.spec.maxOutputBytes?.let { maximum ->
                    if (request.outputFormat == IdPhotoOutputFormat.JPEG && output.length() > maximum) {
                        val compressed = File.createTempFile("id-photo-compressed-", ".jpg", context.cacheDir)
                        try {
                            val compression = VipsNative.compressJpegToBytes(
                                output.absolutePath,
                                compressed.absolutePath,
                                maximum,
                                95,
                            )
                            if (!compression.targetMet) {
                                return IdPhotoResult.Failure(
                                    IdPhotoFailure.FILE_SIZE_CONSTRAINT_UNMET,
                                    compression.errorMessage,
                                )
                            }
                            compressed.copyTo(output, overwrite = true)
                        } finally {
                            compressed.delete()
                        }
                    }
                }
                val outputBounds = android.graphics.BitmapFactory.Options().also { options ->
                    options.inJustDecodeBounds = true
                    android.graphics.BitmapFactory.decodeFile(output.absolutePath, options)
                }
                if (outputBounds.outWidth != request.spec.widthPx || outputBounds.outHeight != request.spec.heightPx) {
                    return IdPhotoResult.Failure(IdPhotoFailure.OUTPUT_VALIDATION_FAILED)
                }
                request.spec.minOutputBytes?.let { minimum ->
                    if (output.length() < minimum) {
                        return IdPhotoResult.Failure(IdPhotoFailure.FILE_SIZE_CONSTRAINT_UNMET)
                    }
                }
                val actualShoulderLine = analysis.shoulders?.let { shoulders ->
                    if (compositionMetrics.all(Double::isFinite)) {
                        val sourceShoulderWithinHead =
                            (shoulders.centerY - analysis.head.top) / analysis.head.height
                        compositionMetrics[1] + sourceShoulderWithinHead * compositionMetrics[0]
                    } else {
                        compositionPlan.predictedShoulderLineRatio
                    }
                }
                val finalValidation = runCatching {
                    IdPhotoFinalValidator.validate(
                        context = context,
                        outputPath = output.absolutePath,
                        spec = request.spec,
                        backgroundColor = request.backgroundColor,
                        outputFormat = request.outputFormat,
                        predictedShoulderLineRatio = actualShoulderLine,
                    )
                }.getOrElse { error ->
                    output.delete()
                    return IdPhotoResult.Failure(IdPhotoFailure.OUTPUT_VALIDATION_FAILED, error.message)
                }
                request.diagnostics
                    ?.takeIf {
                        context.applicationInfo.flags and
                            android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
                    }
                    ?.let { diagnostics ->
                        runCatching {
                            IdPhotoDiagnosticsWriter.write(
                                request = diagnostics,
                                normalized = normalized,
                                output = output,
                                mask = analysis.mask,
                                face = analysis.face,
                                head = analysis.head,
                                shoulders = analysis.shoulders,
                                spec = request.spec,
                                plan = compositionPlan,
                                compositionMetrics = compositionMetrics,
                                report = finalValidation.second,
                                outputFormat = request.outputFormat,
                                durationMs = android.os.SystemClock.elapsedRealtime() - startedAt,
                            )
                        }.onFailure { error ->
                            Log.w(TAG, "Unable to write ID photo diagnostics", error)
                        }
                    }
                IdPhotoResult.Success(
                    output.absolutePath,
                    finalValidation.first.toIdPhotoFace(),
                    finalValidation.second,
                )
            } else {
                IdPhotoResult.Failure(IdPhotoFailure.COMPOSITION_FAILED, composeError)
            }
        } catch (error: Exception) {
            return IdPhotoResult.Failure(IdPhotoFailure.FACE_DETECTION_FAILED, error.message)
        } finally {
            normalized.delete()
        }
    }

    fun createPrintSheet(request: IdPhotoSheetRequest): IdPhotoResult {
        val input = File(request.inputPath)
        if (!input.isFile) return IdPhotoResult.Failure(IdPhotoFailure.INVALID_INPUT)
        val output = File(request.outputPath)
        if (!output.parentFile.ensureDirectory()) return IdPhotoResult.Failure(IdPhotoFailure.OUTPUT_DIRECTORY_UNAVAILABLE)
        val error = VipsNative.createIdPhotoSheet(
            inputPath = input.absolutePath,
            outputPath = output.absolutePath,
            paperWidth = request.paperWidthPx,
            paperHeight = request.paperHeightPx,
            columns = request.columns,
            rows = request.rows,
            copies = request.copies,
            margin = request.marginPx,
            gap = request.gapPx
        )
        return if (error.isEmpty()) IdPhotoResult.Success(output.absolutePath, null)
        else IdPhotoResult.Failure(IdPhotoFailure.SHEET_COMPOSITION_FAILED, error)
    }

    private fun File?.ensureDirectory(): Boolean = this == null || isDirectory || mkdirs()
}

private fun PortraitFace.toIdPhotoFace(): IdPhotoFace = IdPhotoFace(
    left = left.toDouble(),
    top = top.toDouble(),
    width = width.toDouble(),
    height = height.toDouble(),
    pitchDegrees = pitchDegrees,
    yawDegrees = yawDegrees,
    rollDegrees = rollDegrees,
)

private fun Throwable.toIdPhotoFailure(): IdPhotoFailure = when (message) {
    "NO_FACE" -> IdPhotoFailure.NO_FACE
    "MULTIPLE_FACES" -> IdPhotoFailure.MULTIPLE_FACES
    "HEAD_POSE_UNSUPPORTED" -> IdPhotoFailure.HEAD_POSE_UNSUPPORTED
    "FACE_MESH_FAILED" -> IdPhotoFailure.FACE_MESH_FAILED
    "SHOULDERS_NOT_DETECTED", "SHOULDER_GEOMETRY_INVALID" -> IdPhotoFailure.SHOULDERS_NOT_DETECTED
    "HEAD_GEOMETRY_UNRELIABLE" -> IdPhotoFailure.HEAD_GEOMETRY_UNRELIABLE
    else -> IdPhotoFailure.PORTRAIT_ANALYSIS_FAILED
}

private fun IdPhotoResult.Failure.toOperationException(): OperationException {
    val code = when (reason) {
        IdPhotoFailure.INVALID_INPUT -> OperationErrorCode.INVALID_INPUT
        IdPhotoFailure.OUTPUT_DIRECTORY_UNAVAILABLE,
        IdPhotoFailure.TEMP_FILE_UNAVAILABLE -> OperationErrorCode.IO_ERROR
        IdPhotoFailure.NORMALIZATION_FAILED,
        IdPhotoFailure.COMPOSITION_FAILED,
        IdPhotoFailure.SHEET_COMPOSITION_FAILED -> OperationErrorCode.NATIVE_FAILED
        IdPhotoFailure.NO_FACE,
        IdPhotoFailure.MULTIPLE_FACES,
        IdPhotoFailure.EYES_NOT_VISIBLE,
        IdPhotoFailure.HEAD_POSE_UNSUPPORTED,
        IdPhotoFailure.FACE_TOO_SMALL,
        IdPhotoFailure.SHOULDERS_NOT_DETECTED,
        IdPhotoFailure.HEAD_GEOMETRY_UNRELIABLE,
        IdPhotoFailure.SOURCE_IMAGE_TOO_SMALL,
        IdPhotoFailure.EDITING_PROHIBITED -> OperationErrorCode.INVALID_INPUT
        else -> OperationErrorCode.INTERNAL_ERROR
    }
    return OperationException(code, detail ?: reason.name)
}

data class IdPhotoRequest(
    val inputPath: String,
    val outputPath: String,
    val spec: IdPhotoSpec = IdPhotoSpec.STANDARD_413_X_591,
    val backgroundColor: IdPhotoColor = IdPhotoColor.BLUE,
    val outputFormat: IdPhotoOutputFormat = IdPhotoOutputFormat.JPEG,
    val diagnostics: IdPhotoDiagnosticsRequest? = null,
)

data class IdPhotoSheetRequest(
    val inputPath: String,
    val outputPath: String,
    val paperWidthPx: Int,
    val paperHeightPx: Int,
    val columns: Int,
    val rows: Int,
    val copies: Int,
    val marginPx: Int = 24,
    val gapPx: Int = 16
) {
    init {
        require(paperWidthPx > 0 && paperHeightPx > 0)
        require(columns in 1..20 && rows in 1..20)
        require(copies in 1..(columns * rows))
        require(marginPx >= 0 && gapPx >= 0)
    }
}

data class IdPhotoSpec(
    val widthPx: Int,
    val heightPx: Int,
    val faceHeightRatio: Double = 0.68,
    /** 没有明确顶部数据时不设顶部目标，由人物整体边界自动构图。 */
    val faceTopRatio: Double = 0.0,
    val topPolicy: IdPhotoTopPolicy = IdPhotoTopPolicy.AUTO_COMPOSITION,
    val minFaceWidthRatio: Float = 0.12f,
    val maxHeadPoseDegrees: Float = 15f,
    val faceHeightMinRatio: Double = faceHeightRatio,
    val faceHeightMaxRatio: Double = faceHeightRatio,
    val faceTopMinRatio: Double = if (topPolicy == IdPhotoTopPolicy.AUTO_COMPOSITION) 0.0 else faceTopRatio,
    val faceTopMaxRatio: Double = if (topPolicy == IdPhotoTopPolicy.AUTO_COMPOSITION) 0.89 else faceTopRatio,
    val faceHeightConstraint: IdPhotoConstraintLevel = IdPhotoConstraintLevel.SOFT,
    val faceTopConstraint: IdPhotoConstraintLevel = IdPhotoConstraintLevel.SOFT,
    val eyeLineRatio: Double? = null,
    val eyeLineTolerance: Double = 0.04,
    val eyeLineConstraint: IdPhotoConstraintLevel = IdPhotoConstraintLevel.SOFT,
    val minEyeOpenProbability: Float? = null,
    val allowedBackgroundColors: List<IdPhotoColor> = emptyList(),
    val allowedOutputFormats: Set<IdPhotoOutputFormat> = setOf(IdPhotoOutputFormat.JPEG),
    val minOutputBytes: Long? = null,
    val maxOutputBytes: Long? = null,
    val headMeasurement: IdPhotoHeadMeasurement = IdPhotoHeadMeasurement.CROWN_TO_CHIN,
    val faceCenterXRatio: Double = 0.5,
    val faceCenterXTolerance: Double = 0.035,
    val faceCenterConstraint: IdPhotoConstraintLevel = IdPhotoConstraintLevel.SOFT,
    val shoulderLineRatio: Double? = 0.90,
    val shoulderLineTolerance: Double = 0.055,
    val shoulderLineConstraint: IdPhotoConstraintLevel = IdPhotoConstraintLevel.SOFT,
    val maxUpscaleRatio: Double = 1.35,
    val portraitBottomRatio: Double = 1.0,
    val maxPortraitWidthRatio: Double = 1.25,
    val bottomPolicy: IdPhotoBottomPolicy = IdPhotoBottomPolicy.PREFER_TOUCH,
    val strictComposition: Boolean = false,
    val outputDpi: Int = 300,
    val delivery: IdPhotoDelivery = IdPhotoDelivery.DIGITAL,
    val dimensionMode: IdPhotoDimensionMode = IdPhotoDimensionMode.EXACT,
    val editPolicy: IdPhotoEditPolicy = IdPhotoEditPolicy.ALLOWED,
) {
    init {
        require(widthPx > 0 && heightPx > 0)
        require(faceHeightRatio in 0.1..0.9)
        require(faceTopRatio in 0.0..<0.9)
        require(minFaceWidthRatio in 0.01f..0.9f)
        require(maxHeadPoseDegrees in 1f..45f)
        require(faceHeightMinRatio in 0.1..faceHeightRatio)
        require(faceHeightMaxRatio in faceHeightRatio..0.9)
        require(faceTopMinRatio in 0.0..faceTopRatio)
        require(faceTopMaxRatio in faceTopRatio..<0.9)
        require(eyeLineRatio == null || eyeLineRatio in 0.0..1.0)
        require(eyeLineTolerance in 0.0..0.5)
        require(minEyeOpenProbability == null || minEyeOpenProbability in 0f..1f)
        require(allowedOutputFormats.isNotEmpty())
        require(minOutputBytes == null || minOutputBytes > 0)
        require(maxOutputBytes == null || maxOutputBytes > 0)
        require(minOutputBytes == null || maxOutputBytes == null || minOutputBytes <= maxOutputBytes)
        require(faceCenterXRatio in 0.0..1.0)
        require(faceCenterXTolerance in 0.0..0.25)
        require(shoulderLineRatio == null || shoulderLineRatio in 0.5..1.0)
        require(shoulderLineTolerance in 0.0..0.25)
        require(maxUpscaleRatio in 1.0..4.0)
        require(portraitBottomRatio in 0.5..1.0)
        require(maxPortraitWidthRatio in 0.5..3.0)
        require(outputDpi in 72..1200)
    }

    companion object {
        /** 一寸证件照默认构图：25 x 35 mm 在 300dpi 下约为 295 x 413 px，只保留头肩区域。 */
        val STANDARD_413_X_591 = IdPhotoSpec(widthPx = 295, heightPx = 413)
    }
}

data class IdPhotoColor(val red: Int, val green: Int, val blue: Int) {
    init {
        require(red in 0..255 && green in 0..255 && blue in 0..255)
    }

    companion object {
        val BLUE = IdPhotoColor(67, 142, 219)
        val WHITE = IdPhotoColor(255, 255, 255)
        val RED = IdPhotoColor(204, 51, 51)
    }
}

data class IdPhotoFace(
    val left: Double,
    val top: Double,
    val width: Double,
    val height: Double,
    val yawDegrees: Float,
    val rollDegrees: Float,
    val pitchDegrees: Float = 0f,
)

sealed interface IdPhotoResult {
    data class Success(
        val outputPath: String,
        val face: IdPhotoFace?,
        val compliance: IdPhotoComplianceReport? = null,
    ) : IdPhotoResult
    data class Failure(val reason: IdPhotoFailure, val detail: String? = null) : IdPhotoResult
}

enum class IdPhotoFailure {
    INVALID_INPUT,
    OUTPUT_DIRECTORY_UNAVAILABLE,
    TEMP_FILE_UNAVAILABLE,
    NORMALIZATION_FAILED,
    NO_FACE,
    MULTIPLE_FACES,
    EYES_NOT_VISIBLE,
    HEAD_POSE_UNSUPPORTED,
    FACE_TOO_SMALL,
    FACE_DETECTION_FAILED,
    FACE_MESH_FAILED,
    SHOULDERS_NOT_DETECTED,
    HEAD_GEOMETRY_UNRELIABLE,
    PORTRAIT_ANALYSIS_FAILED,
    PORTRAIT_MASK_FAILED,
    SOURCE_IMAGE_TOO_SMALL,
    COMPOSITION_FAILED,
    SHEET_COMPOSITION_FAILED,
    EDITING_PROHIBITED,
    FILE_SIZE_CONSTRAINT_UNMET,
    OUTPUT_VALIDATION_FAILED,
}
