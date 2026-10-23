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
import java.io.File

enum class CompositionDirection { HORIZONTAL, VERTICAL }

data class ImageCompositionRequest(
    val inputs: List<File>,
    val output: File,
    val direction: CompositionDirection,
    val spacingPx: Int = 0,
    val backgroundRed: Int = 255,
    val backgroundGreen: Int = 255,
    val backgroundBlue: Int = 255,
    val quality: Int = 94,
) {
    init {
        require(inputs.size in 2..50)
        require(inputs.all(File::isFile))
        require(output.extension.equals("jpg", true) || output.extension.equals("jpeg", true))
        require(spacingPx in 0..1000)
        require(backgroundRed in 0..255 && backgroundGreen in 0..255 && backgroundBlue in 0..255)
        require(quality in 1..100)
    }
}

/** 多图拼接统一入口，使用 libvips 流式组合并复用 native 并发限制。 */
object VipsCompositionExecutor {
    private val dimensionsPattern = Regex("^(\\d+)x(\\d+),")

    fun execute(
        request: ImageCompositionRequest,
        budget: OperationBudget = OperationBudget.DEFAULT,
        cancellation: OperationCancellation = OperationCancellation.NONE,
        onProgress: (OperationProgress) -> Unit = {},
    ): OperationResult<File> {
        val initError = VipsNative.initialize()
        if (initError.isNotEmpty()) {
            return OperationResult.Failure(OperationErrorCode.NATIVE_FAILED, initError)
        }
        val dimensions = try {
            request.inputs.map { input ->
                val match = dimensionsPattern.find(VipsNative.imageInfo(input.absolutePath))
                    ?: throw OperationException(
                        OperationErrorCode.UNSUPPORTED_FORMAT,
                        "Unable to inspect image: ${input.name}",
                    )
                match.groupValues[1].toInt() to match.groupValues[2].toInt()
            }
        } catch (error: OperationException) {
            return OperationResult.Failure(error.code, error.message.orEmpty(), error)
        }
        val spacingTotal = request.spacingPx.toLong() * (request.inputs.size - 1)
        val canvasWidth = when (request.direction) {
            CompositionDirection.HORIZONTAL -> dimensions.sumOf { it.first.toLong() } + spacingTotal
            CompositionDirection.VERTICAL -> dimensions.maxOf { it.first }.toLong()
        }
        val canvasHeight = when (request.direction) {
            CompositionDirection.HORIZONTAL -> dimensions.maxOf { it.second }.toLong()
            CompositionDirection.VERTICAL -> dimensions.sumOf { it.second.toLong() } + spacingTotal
        }
        if (canvasWidth > budget.maxWidth || canvasHeight > budget.maxHeight ||
            canvasWidth * canvasHeight > budget.maxTotalPixels
        ) {
            return OperationResult.Failure(
                OperationErrorCode.BUDGET_EXCEEDED,
                "Composition canvas ${canvasWidth}x${canvasHeight} exceeds the operation budget",
            )
        }
        val x = IntArray(request.inputs.size)
        val y = IntArray(request.inputs.size)
        var offset = 0
        dimensions.forEachIndexed { index, (width, height) ->
            if (request.direction == CompositionDirection.HORIZONTAL) {
                x[index] = offset
                y[index] = ((canvasHeight.toInt() - height) / 2).coerceAtLeast(0)
                offset += width + request.spacingPx
            } else {
                x[index] = ((canvasWidth.toInt() - width) / 2).coerceAtLeast(0)
                y[index] = offset
                offset += height + request.spacingPx
            }
        }
        return VipsOperationExecutor.execute(
            inputFiles = request.inputs,
            outputFile = request.output,
            encodeOptions = ImageEncodeOptions(
                ImageOutputFormat.JPEG,
                request.quality,
                MetadataPolicy.STRIP_ALL,
            ),
            budget = budget,
            cancellation = cancellation,
            onProgress = onProgress,
        ) { temporary, _ ->
            VipsNative.composePoster(
                null,
                request.inputs.map(File::getAbsolutePath).toTypedArray(),
                x,
                y,
                dimensions.map { it.first }.toIntArray(),
                dimensions.map { it.second }.toIntArray(),
                IntArray(request.inputs.size) { 1 },
                temporary.absolutePath,
                canvasWidth.toInt(),
                canvasHeight.toInt(),
                request.backgroundRed,
                request.backgroundGreen,
                request.backgroundBlue,
            )
        }
    }
}
