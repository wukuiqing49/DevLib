package com.phototoolkit.engine

import com.wkq.photo.core.operation.ImageEncodeOptions
import com.wkq.photo.core.operation.ImageOutputFormat
import com.wkq.photo.core.operation.MetadataPolicy
import com.wkq.photo.core.operation.OperationBudget
import com.wkq.photo.core.operation.OperationCancellation
import com.wkq.photo.core.operation.OperationProgress
import com.wkq.photo.core.operation.OperationResult
import java.io.File

enum class PosterLayerSampling(internal val nativeValue: Int) {
    NEAREST(0),
    LANCZOS(1)
}

data class NativePosterCanvas(
    val width: Int,
    val height: Int,
    val backgroundRed: Int = 255,
    val backgroundGreen: Int = 255,
    val backgroundBlue: Int = 255
) {
    init {
        require(width > 0 && height > 0)
        require(backgroundRed in 0..255 && backgroundGreen in 0..255 && backgroundBlue in 0..255)
    }
}

data class NativePosterLayer(
    val file: File,
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
    val sampling: PosterLayerSampling = PosterLayerSampling.LANCZOS
) {
    init {
        require(file.isFile)
        require(left >= 0 && top >= 0)
        require(width > 0 && height > 0)
    }
}

/** 文件级海报图层合成，避免在 Kotlin 堆中创建整张高分辨率 Bitmap。 */
object VipsPosterRenderer {
    private const val MAX_LAYERS = 16

    fun render(
        background: File?,
        layers: List<NativePosterLayer>,
        output: File,
        canvas: NativePosterCanvas,
        encodeOptions: ImageEncodeOptions = ImageEncodeOptions(
            format = ImageOutputFormat.PNG,
            quality = 100,
            metadataPolicy = MetadataPolicy.STRIP_ALL
        ),
        budget: OperationBudget = OperationBudget.DEFAULT,
        cancellation: OperationCancellation = OperationCancellation.NONE,
        onProgress: (OperationProgress) -> Unit = {}
    ): OperationResult<File> {
        require(layers.isNotEmpty())
        require(layers.size <= MAX_LAYERS)
        require(background == null || background.isFile)
        require(layers.all { it.left + it.width <= canvas.width && it.top + it.height <= canvas.height })
        val inputs = buildList {
            background?.let(::add)
            addAll(layers.map(NativePosterLayer::file))
        }
        return VipsOperationExecutor.execute(
            inputFiles = inputs,
            outputFile = output,
            encodeOptions = encodeOptions,
            budget = budget,
            cancellation = cancellation,
            onProgress = onProgress
        ) { temporary, _ ->
            VipsNative.composePoster(
                backgroundPath = background?.absolutePath,
                layerPaths = layers.map { it.file.absolutePath }.toTypedArray(),
                layerLeft = layers.map(NativePosterLayer::left).toIntArray(),
                layerTop = layers.map(NativePosterLayer::top).toIntArray(),
                layerWidth = layers.map(NativePosterLayer::width).toIntArray(),
                layerHeight = layers.map(NativePosterLayer::height).toIntArray(),
                layerSampling = layers.map { it.sampling.nativeValue }.toIntArray(),
                outputPath = temporary.absolutePath,
                canvasWidth = canvas.width,
                canvasHeight = canvas.height,
                backgroundRed = canvas.backgroundRed,
                backgroundGreen = canvas.backgroundGreen,
                backgroundBlue = canvas.backgroundBlue
            )
        }
    }
}
