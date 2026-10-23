package com.phototoolkit.raw

import com.phototoolkit.engine.NativeImageOperation
import com.phototoolkit.engine.VipsNative
import com.phototoolkit.engine.VipsOperationExecutor
import com.wkq.photo.core.operation.ImageEncodeOptions
import com.wkq.photo.core.operation.ImageOutputFormat
import com.wkq.photo.core.operation.MetadataPolicy
import com.wkq.photo.core.operation.OperationBudget
import com.wkq.photo.core.operation.OperationCancellation
import com.wkq.photo.core.operation.OperationErrorCode
import com.wkq.photo.core.operation.OperationException
import com.wkq.photo.core.operation.OperationProgress
import com.wkq.photo.core.operation.OperationResult
import com.wkq.photo.core.operation.OperationRunner
import com.wkq.photo.core.operation.OperationStage
import com.wkq.photo.core.operation.OperationStatistics
import com.wkq.photo.core.operation.withOutputPath
import com.wkq.photo.core.operation.getOrThrow
import com.wkq.photo.core.utils.AtomicFileOutput
import org.json.JSONObject
import java.io.File

data class RawImageInfo(
    val make: String,
    val model: String,
    val rawWidth: Int,
    val rawHeight: Int,
    val imageWidth: Int,
    val imageHeight: Int,
    val iso: Float?,
    val shutterSeconds: Float?,
    val aperture: Float?,
    val focalLengthMm: Float?,
    val timestampSeconds: Long?,
)

enum class RawWhiteBalance(val nativeValue: Int) { CAMERA(0), AUTOMATIC(1), RAW(2) }
enum class RawOutputFormat(val nativeValue: Int, val extension: String) {
    JPEG(0, "jpg"),
    TIFF(1, "tiff"),
    WEBP(2, "webp"),
}

data class RawDevelopOptions(
    val format: RawOutputFormat = RawOutputFormat.JPEG,
    val quality: Int = 94,
    val whiteBalance: RawWhiteBalance = RawWhiteBalance.CAMERA,
    val autoBright: Boolean = true,
    val exposureEv: Double = 0.0,
    val halfSize: Boolean = false,
    val outputBits: Int = 8,
    val highlightRecovery: Int = 0,
    val noiseThreshold: Float = 0f,
) {
    init {
        require(quality in 1..100)
        require(exposureEv in -5.0..5.0)
        require(outputBits == 8 || outputBits == 16)
        require(highlightRecovery in 0..9)
        require(noiseThreshold in 0f..1_000f)
        require(outputBits == 8 || format == RawOutputFormat.TIFF) {
            "16-bit RAW output is only available for TIFF"
        }
    }
}

data class RawPipelineRequest(
    val source: File,
    val output: File,
    val previewOutput: File? = null,
    val developOptions: RawDevelopOptions = RawDevelopOptions(format = RawOutputFormat.TIFF),
    val postOperations: List<NativeImageOperation> = emptyList(),
    val encodeOptions: ImageEncodeOptions = ImageEncodeOptions(),
) {
    init {
        require(output.extension.equals(encodeOptions.format.extension, true) ||
            encodeOptions.format == ImageOutputFormat.JPEG && output.extension.equals("jpeg", true))
        require(previewOutput == null || previewOutput.extension.equals("jpg", true) ||
            previewOutput.extension.equals("jpeg", true))
        require(postOperations.none { it is NativeImageOperation.CompressJpeg }) {
            "Target-size compression belongs in the final encode stage"
        }
    }
}

data class RawPipelineOutput(
    val info: RawImageInfo,
    val preview: File?,
    val output: File,
    val appliedPostOperationCount: Int,
)

object RawOperationExecutor {
    /** RAW 检查、预览、显影和 libvips 后处理的一体化入口。 */
    fun executePipeline(
        request: RawPipelineRequest,
        budget: OperationBudget = OperationBudget.DEFAULT,
        cancellation: OperationCancellation = OperationCancellation.NONE,
        onProgress: (OperationProgress) -> Unit = {},
    ): OperationResult<RawPipelineOutput> = OperationRunner.run(budget, cancellation, onProgress) { context ->
        val totalUnits = 3 + request.postOperations.size
        context.report(OperationStage.VALIDATING, 0, totalUnits)
        val info = inspect(request.source, remainingBudget(context), cancellation).getOrThrow()
        context.report(OperationStage.DECODING, 1, totalUnits)
        request.previewOutput?.let {
            extractPreview(request.source, it, remainingBudget(context), cancellation).getOrThrow()
        }
        context.report(OperationStage.DECODING, 2, totalUnits)

        val temporaryFiles = mutableListOf<File>()
        try {
            val developed = File.createTempFile("raw-pipeline-", ".tiff", request.output.parentFile).also(temporaryFiles::add)
            develop(
                source = request.source,
                output = developed,
                options = request.developOptions.copy(format = RawOutputFormat.TIFF),
                budget = remainingBudget(context),
                cancellation = cancellation,
            ).getOrThrow()

            var working = developed
            request.postOperations.forEachIndexed { index, operation ->
                val transformed = File.createTempFile("raw-post-$index-", ".tiff", request.output.parentFile)
                    .also(temporaryFiles::add)
                VipsOperationExecutor.execute(
                    inputFile = working,
                    outputFile = transformed,
                    operation = operation,
                    encodeOptions = ImageEncodeOptions(ImageOutputFormat.TIFF, 100, MetadataPolicy.PRESERVE),
                    budget = remainingBudget(context),
                    cancellation = cancellation,
                ).getOrThrow()
                working = transformed
                context.report(OperationStage.PROCESSING, 3 + index, totalUnits)
            }
            VipsOperationExecutor.execute(
                inputFile = working,
                outputFile = request.output,
                operation = NativeImageOperation.Convert,
                encodeOptions = request.encodeOptions,
                budget = remainingBudget(context),
                cancellation = cancellation,
            ).getOrThrow()
            val outputPixels = VipsNative.imageInfo(request.output.absolutePath)
                .let(DIMENSIONS::find)
                ?.let { it.groupValues[1].toLong() * it.groupValues[2].toLong() }
                ?: 0L
            context.report(OperationStage.COMPLETED, totalUnits, totalUnits)
            OperationResult.Success(
                RawPipelineOutput(info, request.previewOutput, request.output, request.postOperations.size),
                OperationStatistics(
                    durationMillis = context.elapsedMillis(),
                    inputCount = 1,
                    outputCount = 1 + if (request.previewOutput == null) 0 else 1,
                    inputBytes = request.source.length(),
                    outputBytes = request.output.length() + (request.previewOutput?.length() ?: 0L),
                    inputPixels = info.imageWidth.toLong() * info.imageHeight,
                    outputPixels = outputPixels,
                ),
            )
        } finally {
            temporaryFiles.forEach(File::delete)
        }
    }
    fun inspect(
        source: File,
        budget: OperationBudget = OperationBudget.DEFAULT,
        cancellation: OperationCancellation = OperationCancellation.NONE,
        onProgress: (OperationProgress) -> Unit = {},
    ): OperationResult<RawImageInfo> = OperationRunner.run(budget, cancellation, onProgress) { context ->
        context.report(OperationStage.VALIDATING, 0, 1)
        requireSource(source)
        context.checkActive()
        val raw = RawNative.inspectDetails(source.absolutePath)
        val json = runCatching { JSONObject(raw) }.getOrElse {
            throw OperationException(OperationErrorCode.UNSUPPORTED_FORMAT, raw.ifBlank { "Unable to inspect RAW" }, it)
        }
        val info = RawImageInfo(
            make = json.optString("make"),
            model = json.optString("model"),
            rawWidth = json.getInt("rawWidth"),
            rawHeight = json.getInt("rawHeight"),
            imageWidth = json.getInt("imageWidth"),
            imageHeight = json.getInt("imageHeight"),
            iso = json.optFloatOrNull("iso"),
            shutterSeconds = json.optFloatOrNull("shutterSeconds"),
            aperture = json.optFloatOrNull("aperture"),
            focalLengthMm = json.optFloatOrNull("focalLengthMm"),
            timestampSeconds = json.optLongOrNull("timestampSeconds"),
        )
        context.checkDimensions(info.imageWidth, info.imageHeight)
        context.checkTotalPixels(info.imageWidth.toLong() * info.imageHeight)
        context.report(OperationStage.COMPLETED, 1, 1)
        OperationResult.Success(
            info,
            OperationStatistics(
                durationMillis = context.elapsedMillis(),
                inputCount = 1,
                inputBytes = source.length(),
                inputPixels = info.imageWidth.toLong() * info.imageHeight,
            ),
        )
    }

    fun extractPreview(
        source: File,
        output: File,
        budget: OperationBudget = OperationBudget.DEFAULT,
        cancellation: OperationCancellation = OperationCancellation.NONE,
        onProgress: (OperationProgress) -> Unit = {},
    ): OperationResult<File> = OperationRunner.run(budget, cancellation, onProgress) { context ->
        context.report(OperationStage.VALIDATING, 0, 1)
        requireSource(source)
        require(output.extension.equals("jpg", true) || output.extension.equals("jpeg", true))
        context.checkActive()
        withOutputPath(output) {
            AtomicFileOutput.write(output) { temporary ->
                context.report(OperationStage.PROCESSING, 0, 1)
                val error = RawNative.extractPreview(source.absolutePath, temporary.absolutePath)
                if (error.isNotEmpty()) throw OperationException(OperationErrorCode.NATIVE_FAILED, error)
                if (temporary.length() == 0L) {
                    throw OperationException(OperationErrorCode.ENCODE_FAILED, "RAW preview is empty")
                }
            }
        }
        context.checkActive()
        context.report(OperationStage.COMPLETED, 1, 1)
        OperationResult.Success(
            output,
            OperationStatistics(
                durationMillis = context.elapsedMillis(),
                inputCount = 1,
                outputCount = 1,
                inputBytes = source.length(),
                outputBytes = output.length(),
            ),
        )
    }

    fun develop(
        source: File,
        output: File,
        options: RawDevelopOptions = RawDevelopOptions(),
        budget: OperationBudget = OperationBudget.DEFAULT,
        cancellation: OperationCancellation = OperationCancellation.NONE,
        onProgress: (OperationProgress) -> Unit = {},
    ): OperationResult<File> = OperationRunner.run(budget, cancellation, onProgress) { context ->
        context.report(OperationStage.VALIDATING, 0, 1)
        requireSource(source)
        val validExtension = output.extension.equals(options.format.extension, true) ||
            options.format == RawOutputFormat.JPEG && output.extension.equals("jpeg", true) ||
            options.format == RawOutputFormat.TIFF && output.extension.equals("tif", true)
        require(validExtension) { "RAW output extension does not match ${options.format}" }
        val info = when (val result = inspect(source, budget, cancellation)) {
            is OperationResult.Success -> result.value
            is OperationResult.Failure -> throw OperationException(result.code, result.message, result.cause)
        }
        val scaleDivisor = if (options.halfSize) 2 else 1
        val outputWidth = (info.imageWidth / scaleDivisor).coerceAtLeast(1)
        val outputHeight = (info.imageHeight / scaleDivisor).coerceAtLeast(1)
        context.checkDimensions(outputWidth, outputHeight)
        context.checkTotalPixels(outputWidth.toLong() * outputHeight)
        withOutputPath(output) {
            AtomicFileOutput.write(output) { temporary ->
                context.checkActive()
                context.report(OperationStage.PROCESSING, 0, 1)
                val error = RawNative.develop(
                    source.absolutePath,
                    temporary.absolutePath,
                    options.format.nativeValue,
                    options.quality,
                    options.whiteBalance.nativeValue,
                    options.autoBright,
                    options.exposureEv,
                    options.halfSize,
                    options.outputBits,
                    options.highlightRecovery,
                    options.noiseThreshold,
                )
                if (error.isNotEmpty()) throw OperationException(OperationErrorCode.NATIVE_FAILED, error)
                if (temporary.length() == 0L) {
                    throw OperationException(OperationErrorCode.ENCODE_FAILED, "RAW development produced no output")
                }
            }
        }
        context.checkActive()
        context.report(OperationStage.COMPLETED, 1, 1)
        OperationResult.Success(
            output,
            OperationStatistics(
                durationMillis = context.elapsedMillis(),
                inputCount = 1,
                outputCount = 1,
                inputBytes = source.length(),
                outputBytes = output.length(),
                inputPixels = info.imageWidth.toLong() * info.imageHeight,
                outputPixels = outputWidth.toLong() * outputHeight,
                outputWidth = outputWidth,
                outputHeight = outputHeight,
            ),
        )
    }

    private fun requireSource(source: File) {
        if (!source.isFile) throw java.io.FileNotFoundException("RAW source does not exist: ${source.absolutePath}")
    }

    private fun JSONObject.optFloatOrNull(key: String): Float? =
        if (!has(key) || isNull(key)) null else getDouble(key).toFloat().takeIf { it > 0f }

    private fun JSONObject.optLongOrNull(key: String): Long? =
        if (!has(key) || isNull(key)) null else getLong(key).takeIf { it > 0L }

    private fun remainingBudget(context: com.wkq.photo.core.operation.OperationContext) = context.budget.copy(
        timeoutMillis = (context.budget.timeoutMillis - context.elapsedMillis()).coerceAtLeast(1L),
    )

    private val DIMENSIONS = Regex("^(\\d+)x(\\d+),")
}
