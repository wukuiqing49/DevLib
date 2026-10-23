package com.phototoolkit.engine

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
import com.wkq.photo.core.utils.AtomicFileOutput
import java.io.File
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

sealed interface NativeImageOperation {
    data class CompressJpeg(
        val targetKilobytes: Int,
        val maxQuality: Int = 95,
    ) : NativeImageOperation {
        init {
            require(targetKilobytes > 0)
            require(maxQuality in 1..100)
        }
    }

    data class Thumbnail(val longestEdge: Int) : NativeImageOperation {
        init { require(longestEdge > 0) }
    }

    data class ResizeTo(val spec: ImageResizeSpec) : NativeImageOperation
    data class SmartCrop(val width: Int, val height: Int) : NativeImageOperation {
        init { require(width > 0 && height > 0) }
    }
    data class Crop(val left: Int, val top: Int, val width: Int, val height: Int) : NativeImageOperation {
        init {
            require(left >= 0 && top >= 0)
            require(width > 0 && height > 0)
        }
    }
    data class Rotate(val degrees: Double) : NativeImageOperation {
        init { require(degrees in -360.0..360.0 && degrees != 0.0) }
    }
    data class Flip(val horizontal: Boolean) : NativeImageOperation
    data class Blur(val sigma: Double) : NativeImageOperation {
        init { require(sigma in 0.1..100.0) }
    }
    data object Sharpen : NativeImageOperation
    data object Grayscale : NativeImageOperation
    data object AutoEnhance : NativeImageOperation
    data object DocumentEnhance : NativeImageOperation
    data object BlackWhite : NativeImageOperation
    data object HistogramEqualize : NativeImageOperation
    data class Pad(
        val width: Int,
        val height: Int,
        val red: Int = 255,
        val green: Int = 255,
        val blue: Int = 255,
    ) : NativeImageOperation {
        init {
            require(width > 0 && height > 0)
            require(red in 0..255 && green in 0..255 && blue in 0..255)
        }
    }
    data class FlattenBackground(
        val red: Int = 255,
        val green: Int = 255,
        val blue: Int = 255,
    ) : NativeImageOperation {
        init { require(red in 0..255 && green in 0..255 && blue in 0..255) }
    }
    data class Adjust(val brightness: Double, val contrast: Double) : NativeImageOperation
    class ExtractPortrait(
        internal val alphaMask: ByteArray,
        internal val maskWidth: Int,
        internal val maskHeight: Int
    ) : NativeImageOperation {
        init {
            require(maskWidth > 0 && maskHeight > 0)
            require(alphaMask.size.toLong() == maskWidth.toLong() * maskHeight)
        }
    }
    class ReplaceBackground(
        internal val alphaMask: ByteArray,
        internal val maskWidth: Int,
        internal val maskHeight: Int,
        internal val red: Int,
        internal val green: Int,
        internal val blue: Int
    ) : NativeImageOperation {
        init {
            require(maskWidth > 0 && maskHeight > 0)
            require(alphaMask.size.toLong() == maskWidth.toLong() * maskHeight)
            require(red in 0..255 && green in 0..255 && blue in 0..255)
        }
    }
    data object StripMetadata : NativeImageOperation
    data object Convert : NativeImageOperation
}

object VipsOperationExecutor {

    private const val MAX_CONCURRENT_OPERATIONS = 2
    private val operationPermits = Semaphore(MAX_CONCURRENT_OPERATIONS, true)
    private val dimensionsPattern = Regex("^(\\d+)x(\\d+),")

    fun execute(
        inputFile: File,
        outputFile: File,
        operation: NativeImageOperation,
        encodeOptions: ImageEncodeOptions = ImageEncodeOptions(),
        budget: OperationBudget = OperationBudget.DEFAULT,
        cancellation: OperationCancellation = OperationCancellation.NONE,
        onProgress: (OperationProgress) -> Unit = {}
    ): OperationResult<File> = execute(
        inputFiles = listOf(inputFile),
        outputFile = outputFile,
        encodeOptions = encodeOptions,
        budget = budget,
        cancellation = cancellation,
        onProgress = onProgress
    ) { temporary, _ ->
        when (operation) {
            is NativeImageOperation.CompressJpeg -> {
                if (encodeOptions.format != ImageOutputFormat.JPEG) {
                    throw OperationException(
                        OperationErrorCode.INVALID_INPUT,
                        "Target-size compression only supports JPEG output",
                    )
                }
                if (encodeOptions.metadataPolicy == MetadataPolicy.PRESERVE) {
                    throw OperationException(
                        OperationErrorCode.INVALID_INPUT,
                        "Target-size JPEG compression cannot preserve source metadata",
                    )
                }
                VipsNative.compressJpegToBytes(
                    inputFile.path,
                    temporary.path,
                    operation.targetKilobytes.toLong() * 1024L,
                    operation.maxQuality,
                ).errorMessage
            }
            NativeImageOperation.Convert -> encode(inputFile, temporary, encodeOptions)
            NativeImageOperation.StripMetadata -> encode(
                inputFile,
                temporary,
                encodeOptions.copy(metadataPolicy = MetadataPolicy.STRIP_ALL),
            )
            else -> transformThenEncode(inputFile, temporary, operation, encodeOptions)
        }
    }

    fun compressJpegToBytes(
        inputFile: File,
        outputFile: File,
        targetBytes: Long,
        maxQuality: Int = 95,
        budget: OperationBudget = OperationBudget.DEFAULT,
        cancellation: OperationCancellation = OperationCancellation.NONE,
        onProgress: (OperationProgress) -> Unit = {},
    ): OperationResult<File> {
        require(targetBytes > 0)
        require(maxQuality in 1..100)
        return execute(
            inputFiles = listOf(inputFile),
            outputFile = outputFile,
            encodeOptions = ImageEncodeOptions(
                format = ImageOutputFormat.JPEG,
                quality = maxQuality,
                metadataPolicy = MetadataPolicy.STRIP_ALL,
            ),
            budget = budget,
            cancellation = cancellation,
            onProgress = onProgress,
        ) { temporary, _ ->
            VipsNative.compressJpegToBytes(
                inputFile.path,
                temporary.path,
                targetBytes,
                maxQuality,
            ).errorMessage
        }
    }

    fun execute(
        inputFiles: List<File>,
        outputFile: File,
        encodeOptions: ImageEncodeOptions = ImageEncodeOptions(),
        budget: OperationBudget = OperationBudget.DEFAULT,
        cancellation: OperationCancellation = OperationCancellation.NONE,
        onProgress: (OperationProgress) -> Unit = {},
        operation: (temporaryOutput: File, options: ImageEncodeOptions) -> String
    ): OperationResult<File> = OperationRunner.run(budget, cancellation, onProgress) { context ->
        context.report(OperationStage.VALIDATING, 0, 1)
        if (inputFiles.isEmpty() || inputFiles.any { !it.isFile }) {
            throw OperationException(OperationErrorCode.SOURCE_NOT_FOUND, "Native input file does not exist")
        }
        val expectedExtension = encodeOptions.format.extension
        if (!outputFile.extension.equals(expectedExtension, ignoreCase = true) &&
            !(expectedExtension == "jpg" && outputFile.extension.equals("jpeg", ignoreCase = true))
        ) {
            throw OperationException(
                OperationErrorCode.INVALID_INPUT,
                "Output extension .${outputFile.extension} does not match ${encodeOptions.format}"
            )
        }
        val initError = VipsNative.initialize()
        if (initError.isNotEmpty()) throw OperationException(OperationErrorCode.NATIVE_FAILED, initError)

        var totalInputPixels = 0L
        inputFiles.forEach { input ->
            val dimensions = dimensionsPattern.find(VipsNative.imageInfo(input.absolutePath))
                ?: throw OperationException(OperationErrorCode.UNSUPPORTED_FORMAT, "Unable to inspect image: ${input.name}")
            val width = dimensions.groupValues[1].toInt()
            val height = dimensions.groupValues[2].toInt()
            context.checkDimensions(width, height)
            val imagePixels = width.toLong() * height
            if (totalInputPixels > context.budget.maxTotalPixels - imagePixels) {
                context.checkTotalPixels(Long.MAX_VALUE)
            }
            totalInputPixels += imagePixels
            context.checkTotalPixels(totalInputPixels)
        }

        withOutputPath(outputFile) {
            acquirePermit(context)
            try {
                context.report(OperationStage.PROCESSING, 0, 1)
                AtomicFileOutput.write(outputFile) { temporary ->
                    context.checkActive()
                    val nativeError = operation(temporary, encodeOptions)
                    if (nativeError.isNotEmpty()) {
                        throw OperationException(OperationErrorCode.NATIVE_FAILED, nativeError)
                    }
                    context.checkActive()
                    if (!temporary.isFile || temporary.length() == 0L) {
                        throw OperationException(OperationErrorCode.ENCODE_FAILED, "Native operation produced no output")
                    }
                    val outputDimensions = dimensionsPattern.find(VipsNative.imageInfo(temporary.absolutePath))
                        ?: throw OperationException(
                            OperationErrorCode.ENCODE_FAILED,
                            "Unable to inspect native output: ${temporary.name}",
                        )
                    val outputWidth = outputDimensions.groupValues[1].toInt()
                    val outputHeight = outputDimensions.groupValues[2].toInt()
                    context.checkDimensions(outputWidth, outputHeight)
                    context.checkTotalPixels(outputWidth.toLong() * outputHeight)
                }
            } finally {
                operationPermits.release()
            }
        }
        context.report(OperationStage.COMPLETED, 1, 1)
        val outputDimensions = dimensionsPattern.find(VipsNative.imageInfo(outputFile.absolutePath))
        val outputWidth = outputDimensions?.groupValues?.get(1)?.toIntOrNull()
        val outputHeight = outputDimensions?.groupValues?.get(2)?.toIntOrNull()
        OperationResult.Success(
            value = outputFile,
            statistics = OperationStatistics(
                durationMillis = context.elapsedMillis(),
                inputCount = inputFiles.size,
                outputCount = 1,
                inputBytes = inputFiles.sumOf(File::length),
                outputBytes = outputFile.length(),
                inputPixels = totalInputPixels,
                outputPixels = if (outputWidth != null && outputHeight != null) outputWidth.toLong() * outputHeight else 0,
                outputWidth = outputWidth,
                outputHeight = outputHeight
            )
        )
    }

    fun configureRuntime(
        options: NativeRuntimeOptions = NativeRuntimeOptions(),
        budget: OperationBudget = OperationBudget.DEFAULT,
        cancellation: OperationCancellation = OperationCancellation.NONE,
        onProgress: (OperationProgress) -> Unit = {}
    ): OperationResult<NativeRuntimeOptions> = OperationRunner.run(budget, cancellation, onProgress) { context ->
        context.report(OperationStage.VALIDATING, 0, 1)
        var acquiredPermits = 0
        try {
            repeat(MAX_CONCURRENT_OPERATIONS) {
                acquirePermit(context)
                acquiredPermits++
            }
            val error = VipsNative.configureRuntime(options)
            if (error.isNotEmpty()) throw OperationException(OperationErrorCode.NATIVE_FAILED, error)
        } finally {
            if (acquiredPermits > 0) operationPermits.release(acquiredPermits)
        }
        context.report(OperationStage.COMPLETED, 1, 1)
        OperationResult.Success(options, OperationStatistics(durationMillis = context.elapsedMillis()))
    }

    private fun acquirePermit(context: com.wkq.photo.core.operation.OperationContext) {
        try {
            while (!operationPermits.tryAcquire(50, TimeUnit.MILLISECONDS)) context.checkActive()
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw OperationException(OperationErrorCode.CANCELLED, "Native operation interrupted", error)
        }
    }

    private fun transformThenEncode(
        input: File,
        output: File,
        operation: NativeImageOperation,
        options: ImageEncodeOptions,
    ): String {
        val intermediate = File.createTempFile("photokit-transform-", ".tiff", output.parentFile)
        return try {
            val transformError = when (operation) {
                is NativeImageOperation.Thumbnail -> VipsNative.thumbnail(
                    input.path,
                    intermediate.path,
                    operation.longestEdge,
                )
                is NativeImageOperation.ResizeTo -> VipsNative.resizeTo(input.path, intermediate.path, operation.spec)
                is NativeImageOperation.SmartCrop -> VipsNative.smartCrop(
                    input.path,
                    intermediate.path,
                    operation.width,
                    operation.height,
                )
                is NativeImageOperation.Crop -> VipsNative.crop(
                    input.path,
                    intermediate.path,
                    operation.left,
                    operation.top,
                    operation.width,
                    operation.height,
                )
                is NativeImageOperation.Rotate -> {
                    val exactAngle = operation.degrees.toInt()
                    if (operation.degrees == exactAngle.toDouble() && exactAngle % 90 == 0) {
                        VipsNative.rotate(input.path, intermediate.path, exactAngle)
                    } else {
                        VipsNative.freeRotate(input.path, intermediate.path, operation.degrees)
                    }
                }
                is NativeImageOperation.Flip -> VipsNative.flip(
                    input.path,
                    intermediate.path,
                    operation.horizontal,
                )
                is NativeImageOperation.Blur -> VipsNative.blur(
                    input.path,
                    intermediate.path,
                    operation.sigma,
                )
                NativeImageOperation.Sharpen -> VipsNative.sharpen(input.path, intermediate.path)
                NativeImageOperation.Grayscale -> VipsNative.grayscale(input.path, intermediate.path)
                NativeImageOperation.AutoEnhance -> VipsNative.autoEnhance(input.path, intermediate.path)
                NativeImageOperation.DocumentEnhance -> VipsNative.ocrEnhance(input.path, intermediate.path)
                NativeImageOperation.BlackWhite -> VipsNative.blackWhite(input.path, intermediate.path)
                NativeImageOperation.HistogramEqualize -> VipsNative.histEqual(input.path, intermediate.path)
                is NativeImageOperation.Pad -> {
                    val dimensions = inspectDimensions(input)
                    if (operation.width < dimensions.first || operation.height < dimensions.second) {
                        "Pad canvas ${operation.width}x${operation.height} is smaller than input ${dimensions.first}x${dimensions.second}"
                    } else {
                        VipsNative.composePoster(
                            null,
                            arrayOf(input.path),
                            intArrayOf((operation.width - dimensions.first) / 2),
                            intArrayOf((operation.height - dimensions.second) / 2),
                            intArrayOf(dimensions.first),
                            intArrayOf(dimensions.second),
                            intArrayOf(1),
                            intermediate.path,
                            operation.width,
                            operation.height,
                            operation.red,
                            operation.green,
                            operation.blue,
                        )
                    }
                }
                is NativeImageOperation.FlattenBackground -> {
                    val dimensions = inspectDimensions(input)
                    VipsNative.composePoster(
                        null,
                        arrayOf(input.path),
                        intArrayOf(0),
                        intArrayOf(0),
                        intArrayOf(dimensions.first),
                        intArrayOf(dimensions.second),
                        intArrayOf(1),
                        intermediate.path,
                        dimensions.first,
                        dimensions.second,
                        operation.red,
                        operation.green,
                        operation.blue,
                    )
                }
                is NativeImageOperation.Adjust -> VipsNative.adjust(
                    input.path,
                    intermediate.path,
                    operation.brightness,
                    operation.contrast,
                )
                is NativeImageOperation.ExtractPortrait -> VipsNative.extractPortrait(
                    input.path,
                    intermediate.path,
                    operation.alphaMask,
                    operation.maskWidth,
                    operation.maskHeight,
                )
                is NativeImageOperation.ReplaceBackground -> VipsNative.replaceBackground(
                    input.path,
                    intermediate.path,
                    operation.alphaMask,
                    operation.maskWidth,
                    operation.maskHeight,
                    operation.red,
                    operation.green,
                    operation.blue,
                )
                is NativeImageOperation.CompressJpeg,
                NativeImageOperation.Convert,
                NativeImageOperation.StripMetadata -> error("Operation must not use the transform path")
            }
            if (transformError.isNotEmpty()) transformError else encode(intermediate, output, options)
        } finally {
            intermediate.delete()
        }
    }

    private fun encode(input: File, output: File, options: ImageEncodeOptions): String =
        VipsNative.encode(
            input.path,
            output.path,
            options.quality,
            when (options.metadataPolicy) {
                MetadataPolicy.PRESERVE -> 0
                MetadataPolicy.STRIP_SENSITIVE -> 1
                MetadataPolicy.STRIP_ALL -> 2
            },
        )

    private fun inspectDimensions(input: File): Pair<Int, Int> {
        val match = dimensionsPattern.find(VipsNative.imageInfo(input.path))
            ?: throw OperationException(
                OperationErrorCode.UNSUPPORTED_FORMAT,
                "Unable to inspect native image dimensions: ${input.name}",
            )
        return match.groupValues[1].toInt() to match.groupValues[2].toInt()
    }
}
