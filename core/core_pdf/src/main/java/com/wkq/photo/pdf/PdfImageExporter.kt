package com.wkq.photo.pdf

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.wkq.photo.core.operation.OperationBudget
import com.wkq.photo.core.operation.OperationCancellation
import com.wkq.photo.core.operation.OperationErrorCode
import com.wkq.photo.core.operation.OperationException
import com.wkq.photo.core.operation.OperationProgress
import com.wkq.photo.core.operation.OperationResult
import com.wkq.photo.core.operation.OperationRunner
import com.wkq.photo.core.operation.OperationStage
import com.wkq.photo.core.operation.OperationStatistics
import com.wkq.photo.core.operation.getOrThrow
import com.wkq.photo.core.operation.withOutputPath
import com.wkq.photo.core.utils.AtomicFileOutput
import com.wkq.photo.imagerender.BitmapDecodeOptions
import com.wkq.photo.imagerender.BitmapFileDecoder
import com.wkq.photo.imagerender.ImageSource
import java.io.File
import java.io.OutputStream
import kotlin.math.ceil
import kotlin.math.min

enum class PdfPageSize(val portraitWidth: Int, val portraitHeight: Int) {
    A3(842, 1191),
    A4(595, 842),
    A5(420, 595),
    LETTER(612, 792),
    LEGAL(612, 1008)
}

data class PdfPageDimensions(val widthPoints: Int, val heightPoints: Int) {
    init {
        require(widthPoints in 1..MAX_PDF_PAGE_POINTS)
        require(heightPoints in 1..MAX_PDF_PAGE_POINTS)
    }
}

enum class PdfImageScaleMode { FIT, FILL_CROP, STRETCH }

data class PdfRenderOptions(
    val customPageSize: PdfPageDimensions? = null,
    val scaleMode: PdfImageScaleMode = PdfImageScaleMode.FIT,
    val backgroundColor: Int = Color.WHITE,
    val showPageNumbers: Boolean = false,
    val pageNumberColor: Int = Color.DKGRAY,
    val rasterScale: Int = DEFAULT_PDF_RASTER_SCALE
) {
    init { require(rasterScale in 1..6) }
}

data class PdfImageExportRequest(
    val imageFiles: List<File>,
    val outputFile: File,
    val pageSize: PdfPageSize = PdfPageSize.A4,
    val landscape: Boolean = false,
    val marginPoints: Int = 24
) {
    init {
        require(imageFiles.isNotEmpty())
        require(imageFiles.all(File::isFile)) { "All PDF sources must exist" }
        require(outputFile.extension.equals("pdf", ignoreCase = true)) { "PDF output must use .pdf" }
        require(marginPoints >= 0)
        require(marginPoints.toLong() * 2 < minOf(pageSize.portraitWidth, pageSize.portraitHeight)) {
            "PDF margin must leave a drawable page area"
        }
    }
}

sealed interface PdfOutputTarget {
    val displayName: String

    data class FileTarget(val file: File) : PdfOutputTarget {
        override val displayName: String = file.name
    }

    data class UriTarget(
        val contentResolver: ContentResolver,
        val uri: Uri,
        override val displayName: String = uri.lastPathSegment ?: "document.pdf"
    ) : PdfOutputTarget

    data class ParcelFileDescriptorTarget(
        val descriptor: ParcelFileDescriptor,
        override val displayName: String = "document.pdf"
    ) : PdfOutputTarget
}

data class PdfOperationRequest(
    val imageSources: List<ImageSource>,
    val outputTarget: PdfOutputTarget,
    val pageSize: PdfPageSize = PdfPageSize.A4,
    val landscape: Boolean = false,
    val marginPoints: Int = 24
)

data class PdfExportOutput(
    val target: PdfOutputTarget,
    val pageCount: Int
)

data class PdfGridOperationRequest(
    val imageSources: List<ImageSource>,
    val outputTarget: PdfOutputTarget,
    val pageSize: PdfPageSize = PdfPageSize.A4,
    val landscape: Boolean = false,
    val marginPoints: Int = 24,
    val columns: Int = 2,
    val rows: Int = 2,
    val gapPoints: Int = 12,
    val header: String? = null,
    val footer: String? = null,
) {
    init {
        require(columns in 1..6 && rows in 1..6)
        require(gapPoints >= 0)
        require(header?.length ?: 0 <= 200)
        require(footer?.length ?: 0 <= 200)
    }
}

enum class PdfPageOrientation { PORTRAIT, LANDSCAPE }

data class PdfPageConfig(
    val size: PdfPageSize = PdfPageSize.A4,
    val customSize: PdfPageDimensions? = null,
    val orientation: PdfPageOrientation = PdfPageOrientation.PORTRAIT,
    val marginPoints: Int = 24,
) {
    init { require(marginPoints >= 0) }
}

sealed interface PdfDocumentLayout {
    data object SinglePerPage : PdfDocumentLayout

    data class Grid(
        val columns: Int = 2,
        val rows: Int = 2,
        val gapPoints: Int = 12,
    ) : PdfDocumentLayout {
        init {
            require(columns in 1..6 && rows in 1..6)
            require(gapPoints >= 0)
        }
    }
}

data class PdfPageDecoration(
    val header: String? = null,
    val footer: String? = null,
    val showPageNumbers: Boolean = false,
    val pageNumberColor: Int = Color.DKGRAY,
) {
    init {
        require(header?.length ?: 0 <= 200)
        require(footer?.length ?: 0 <= 200)
    }
}

data class PdfDocumentRequest(
    val imageSources: List<ImageSource>,
    val outputTarget: PdfOutputTarget,
    val layout: PdfDocumentLayout = PdfDocumentLayout.SinglePerPage,
    val page: PdfPageConfig = PdfPageConfig(),
    val decoration: PdfPageDecoration = PdfPageDecoration(),
    val scaleMode: PdfImageScaleMode = PdfImageScaleMode.FIT,
    val backgroundColor: Int = Color.WHITE,
    val rasterScale: Int = DEFAULT_PDF_RASTER_SCALE,
) {
    init {
        require(imageSources.isNotEmpty())
        require(rasterScale in 1..6)
    }
}

/** 将有序图片绘制为单图单页 PDF。调用方应在后台线程执行。 */
object PdfImageExporter {

    /** 单页和网格共用的文档入口；旧 execute/executeGrid 继续作为兼容 API。 */
    fun executeDocument(
        request: PdfDocumentRequest,
        budget: OperationBudget = OperationBudget.DEFAULT,
        cancellation: OperationCancellation = OperationCancellation.NONE,
        onProgress: (OperationProgress) -> Unit = {},
    ): OperationResult<PdfExportOutput> {
        val layout = request.layout
        val options = PdfRenderOptions(
            customPageSize = request.page.customSize,
            scaleMode = request.scaleMode,
            backgroundColor = request.backgroundColor,
            showPageNumbers = request.decoration.showPageNumbers,
            pageNumberColor = request.decoration.pageNumberColor,
            rasterScale = request.rasterScale,
        )
        val grid = when (layout) {
            PdfDocumentLayout.SinglePerPage -> PdfDocumentLayout.Grid(columns = 1, rows = 1, gapPoints = 0)
            is PdfDocumentLayout.Grid -> layout
        }
        return executeGrid(
            request = PdfGridOperationRequest(
                imageSources = request.imageSources,
                outputTarget = request.outputTarget,
                pageSize = request.page.size,
                landscape = request.page.orientation == PdfPageOrientation.LANDSCAPE,
                marginPoints = request.page.marginPoints,
                columns = grid.columns,
                rows = grid.rows,
                gapPoints = grid.gapPoints,
                header = request.decoration.header,
                footer = request.decoration.footer,
            ),
            options = options,
            budget = budget,
            cancellation = cancellation,
            onProgress = onProgress,
        )
    }

    fun create(
        request: PdfImageExportRequest,
        onProgress: (completed: Int, total: Int) -> Unit = { _, _ -> },
        isCancelled: () -> Boolean = { false }
    ): File {
        return createInternal(request, PdfRenderOptions(), onProgress, isCancelled)
    }

    fun create(
        request: PdfImageExportRequest,
        options: PdfRenderOptions,
        onProgress: (completed: Int, total: Int) -> Unit = { _, _ -> },
        isCancelled: () -> Boolean = { false }
    ): File {
        return createInternal(request, options, onProgress, isCancelled)
    }

    private fun createInternal(
        request: PdfImageExportRequest,
        options: PdfRenderOptions,
        onProgress: (completed: Int, total: Int) -> Unit,
        isCancelled: () -> Boolean
    ): File {
        val result = execute(
            request = PdfOperationRequest(
                imageSources = request.imageFiles.map { ImageSource.FileSource(it) },
                outputTarget = PdfOutputTarget.FileTarget(request.outputFile),
                pageSize = request.pageSize,
                landscape = request.landscape,
                marginPoints = request.marginPoints
            ),
            options = options,
            cancellation = OperationCancellation(isCancelled),
            onProgress = { progress ->
                if (progress.stage == OperationStage.PROCESSING || progress.stage == OperationStage.COMPLETED) {
                    onProgress(progress.completedUnits, progress.totalUnits)
                }
            }
        )
        result.getOrThrow()
        return request.outputFile
    }

    fun execute(
        request: PdfOperationRequest,
        options: PdfRenderOptions = PdfRenderOptions(),
        budget: OperationBudget = OperationBudget.DEFAULT,
        cancellation: OperationCancellation = OperationCancellation.NONE,
        onProgress: (OperationProgress) -> Unit = {}
    ): OperationResult<PdfExportOutput> = OperationRunner.run(budget, cancellation, onProgress) { context ->
        val totalPages = request.imageSources.size
        context.report(OperationStage.VALIDATING, 0, totalPages)
        if (totalPages == 0) {
            throw OperationException(OperationErrorCode.INVALID_INPUT, "PDF requires at least one image")
        }
        context.checkPages(totalPages)
        val (pageWidth, pageHeight) = resolvePageDimensions(request, options)
        if (request.marginPoints < 0 || request.marginPoints.toLong() * 2 >= minOf(pageWidth, pageHeight)) {
            throw OperationException(OperationErrorCode.INVALID_INPUT, "PDF margin must leave a drawable page area")
        }
        if (request.outputTarget is PdfOutputTarget.FileTarget &&
            !request.outputTarget.file.extension.equals("pdf", ignoreCase = true)
        ) {
            throw OperationException(OperationErrorCode.INVALID_INPUT, "PDF output must use .pdf")
        }

        val decodeWidth = safeDecodeDimension(pageWidth, request.marginPoints, options.rasterScale)
        val decodeHeight = safeDecodeDimension(pageHeight, request.marginPoints, options.rasterScale)
        context.checkDimensions(decodeWidth, decodeHeight)
        context.checkTotalPixels(decodeWidth.toLong() * decodeHeight * totalPages)

        val renderDocument = {
            writeOutput(request.outputTarget) { stream ->
            val document = PdfDocument()
            try {
                request.imageSources.forEachIndexed { index, source ->
                    context.checkActive()
                    val bitmap = BitmapFileDecoder.decodeOriented(
                        source = source,
                        options = BitmapDecodeOptions(maxWidth = decodeWidth, maxHeight = decodeHeight),
                        budget = budget,
                        cancellation = cancellation
                    ).getOrThrow()
                    try {
                        context.checkActive()
                        val page = document.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, index + 1).create())
                        try {
                            page.canvas.drawColor(options.backgroundColor)
                            drawImage(page.canvas, bitmap, pageWidth, pageHeight, request.marginPoints, options.scaleMode)
                            if (options.showPageNumbers) {
                                drawPageNumber(page.canvas, index + 1, pageWidth, pageHeight, request.marginPoints, options.pageNumberColor)
                            }
                        } finally {
                            document.finishPage(page)
                        }
                    } finally {
                        bitmap.recycle()
                    }
                    context.report(OperationStage.PROCESSING, index + 1, totalPages)
                }
                context.report(OperationStage.ENCODING, totalPages, totalPages)
                document.writeTo(stream)
            } finally {
                document.close()
            }
        }
        }
        val fileTarget = request.outputTarget as? PdfOutputTarget.FileTarget
        if (fileTarget == null) renderDocument() else withOutputPath(fileTarget.file, renderDocument)
        context.report(OperationStage.COMPLETED, totalPages, totalPages)
        OperationResult.Success(
            value = PdfExportOutput(request.outputTarget, totalPages),
            statistics = OperationStatistics(
                durationMillis = context.elapsedMillis(),
                inputCount = totalPages,
                outputCount = 1,
                inputBytes = request.imageSources.sumOf { (it as? ImageSource.FileSource)?.file?.length() ?: 0L },
                outputBytes = outputSize(request.outputTarget),
                outputPixels = decodeWidth.toLong() * decodeHeight * totalPages,
                pageCount = totalPages,
                outputWidth = pageWidth,
                outputHeight = pageHeight
            )
        )
    }

    fun executeGrid(
        request: PdfGridOperationRequest,
        options: PdfRenderOptions = PdfRenderOptions(),
        budget: OperationBudget = OperationBudget.DEFAULT,
        cancellation: OperationCancellation = OperationCancellation.NONE,
        onProgress: (OperationProgress) -> Unit = {},
    ): OperationResult<PdfExportOutput> = OperationRunner.run(budget, cancellation, onProgress) { context ->
        val totalImages = request.imageSources.size
        context.report(OperationStage.VALIDATING, 0, totalImages)
        if (totalImages == 0) throw OperationException(OperationErrorCode.INVALID_INPUT, "PDF requires at least one image")
        val imagesPerPage = request.columns * request.rows
        val pageCount = ceil(totalImages.toDouble() / imagesPerPage).toInt()
        context.checkPages(pageCount)
        val portraitWidth = options.customPageSize?.widthPoints ?: request.pageSize.portraitWidth
        val portraitHeight = options.customPageSize?.heightPoints ?: request.pageSize.portraitHeight
        val pageWidth = if (request.landscape) portraitHeight else portraitWidth
        val pageHeight = if (request.landscape) portraitWidth else portraitHeight
        val textReserve = GRID_TEXT_RESERVE
        val contentTop = request.marginPoints + if (request.header.isNullOrBlank()) 0 else textReserve
        val contentBottom = pageHeight - request.marginPoints - if (request.footer.isNullOrBlank()) 0 else textReserve
        val contentWidth = pageWidth - request.marginPoints * 2
        val contentHeight = contentBottom - contentTop
        val cellWidth = (contentWidth - request.gapPoints * (request.columns - 1)) / request.columns
        val cellHeight = (contentHeight - request.gapPoints * (request.rows - 1)) / request.rows
        if (cellWidth <= 0 || cellHeight <= 0) {
            throw OperationException(OperationErrorCode.INVALID_INPUT, "PDF grid leaves no drawable image area")
        }
        val decodeWidth = (cellWidth * options.rasterScale).coerceAtLeast(1)
        val decodeHeight = (cellHeight * options.rasterScale).coerceAtLeast(1)
        context.checkDimensions(decodeWidth, decodeHeight)
        context.checkTotalPixels(decodeWidth.toLong() * decodeHeight * totalImages)

        val renderDocument = {
            writeOutput(request.outputTarget) { stream ->
                val document = PdfDocument()
                try {
                    repeat(pageCount) { pageIndex ->
                        context.checkActive()
                        val page = document.startPage(
                            PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageIndex + 1).create()
                        )
                        try {
                            page.canvas.drawColor(options.backgroundColor)
                            request.header?.takeIf(String::isNotBlank)?.let {
                                drawGridText(page.canvas, it, pageWidth, request.marginPoints.toFloat(), Paint.Align.CENTER)
                            }
                            request.footer?.takeIf(String::isNotBlank)?.let {
                                drawGridText(
                                    page.canvas,
                                    it,
                                    pageWidth,
                                    (pageHeight - request.marginPoints).toFloat(),
                                    Paint.Align.CENTER,
                                )
                            }
                            for (cellIndex in 0 until imagesPerPage) {
                                val imageIndex = pageIndex * imagesPerPage + cellIndex
                                if (imageIndex >= totalImages) break
                                context.checkActive()
                                val column = cellIndex % request.columns
                                val row = cellIndex / request.columns
                                val left = request.marginPoints + column * (cellWidth + request.gapPoints)
                                val top = contentTop + row * (cellHeight + request.gapPoints)
                                val bitmap = BitmapFileDecoder.decodeOriented(
                                    request.imageSources[imageIndex],
                                    BitmapDecodeOptions(decodeWidth, decodeHeight),
                                    budget,
                                    cancellation,
                                ).getOrThrow()
                                try {
                                    drawImageIntoRect(
                                        page.canvas,
                                        bitmap,
                                        RectF(left.toFloat(), top.toFloat(), (left + cellWidth).toFloat(), (top + cellHeight).toFloat()),
                                        options.scaleMode,
                                    )
                                } finally {
                                    bitmap.recycle()
                                }
                                context.report(OperationStage.PROCESSING, imageIndex + 1, totalImages)
                            }
                            if (options.showPageNumbers) {
                                drawPageNumber(page.canvas, pageIndex + 1, pageWidth, pageHeight, request.marginPoints, options.pageNumberColor)
                            }
                        } finally {
                            document.finishPage(page)
                        }
                    }
                    context.report(OperationStage.ENCODING, totalImages, totalImages)
                    document.writeTo(stream)
                } finally {
                    document.close()
                }
            }
        }
        val fileTarget = request.outputTarget as? PdfOutputTarget.FileTarget
        if (fileTarget == null) renderDocument() else withOutputPath(fileTarget.file, renderDocument)
        context.report(OperationStage.COMPLETED, totalImages, totalImages)
        OperationResult.Success(
            PdfExportOutput(request.outputTarget, pageCount),
            OperationStatistics(
                durationMillis = context.elapsedMillis(),
                inputCount = totalImages,
                outputCount = 1,
                inputBytes = request.imageSources.sumOf { (it as? ImageSource.FileSource)?.file?.length() ?: 0L },
                outputBytes = outputSize(request.outputTarget),
                outputPixels = decodeWidth.toLong() * decodeHeight * totalImages,
                pageCount = pageCount,
                outputWidth = pageWidth,
                outputHeight = pageHeight,
            ),
        )
    }

    private fun writeOutput(target: PdfOutputTarget, block: (OutputStream) -> Unit) {
        when (target) {
            is PdfOutputTarget.FileTarget -> AtomicFileOutput.write(target.file) { temporary ->
                temporary.outputStream().buffered().use(block)
            }
            is PdfOutputTarget.UriTarget -> target.contentResolver.openOutputStream(target.uri, "w")
                ?.buffered()
                ?.use(block)
                ?: throw OperationException(OperationErrorCode.IO_ERROR, "Unable to open PDF URI: ${target.uri}")
            is PdfOutputTarget.ParcelFileDescriptorTarget -> ParcelFileDescriptor.AutoCloseOutputStream(
                ParcelFileDescriptor.dup(target.descriptor.fileDescriptor)
            ).buffered().use(block)
        }
    }

    private fun outputSize(target: PdfOutputTarget): Long = when (target) {
        is PdfOutputTarget.FileTarget -> target.file.length()
        is PdfOutputTarget.UriTarget -> runCatching {
            target.contentResolver.openAssetFileDescriptor(target.uri, "r")?.use { it.length.coerceAtLeast(0) } ?: 0
        }.getOrDefault(0)
        is PdfOutputTarget.ParcelFileDescriptorTarget -> target.descriptor.statSize.coerceAtLeast(0)
    }

    private fun resolvePageDimensions(
        request: PdfImageExportRequest,
        options: PdfRenderOptions
    ): Pair<Int, Int> {
        val portraitWidth = options.customPageSize?.widthPoints ?: request.pageSize.portraitWidth
        val portraitHeight = options.customPageSize?.heightPoints ?: request.pageSize.portraitHeight
        return if (request.landscape) portraitHeight to portraitWidth else portraitWidth to portraitHeight
    }

    private fun resolvePageDimensions(
        request: PdfOperationRequest,
        options: PdfRenderOptions
    ): Pair<Int, Int> {
        val portraitWidth = options.customPageSize?.widthPoints ?: request.pageSize.portraitWidth
        val portraitHeight = options.customPageSize?.heightPoints ?: request.pageSize.portraitHeight
        return if (request.landscape) portraitHeight to portraitWidth else portraitWidth to portraitHeight
    }

    private fun drawImage(
        canvas: Canvas,
        bitmap: Bitmap,
        pageWidth: Int,
        pageHeight: Int,
        margin: Int,
        scaleMode: PdfImageScaleMode
    ) {
        val availableWidth = (pageWidth - margin * 2).coerceAtLeast(1)
        val availableHeight = (pageHeight - margin * 2).coerceAtLeast(1)
        val pageRect = RectF(
            margin.toFloat(),
            margin.toFloat(),
            (pageWidth - margin).toFloat(),
            (pageHeight - margin).toFloat()
        )
        when (scaleMode) {
            PdfImageScaleMode.STRETCH -> canvas.drawBitmap(bitmap, null, pageRect, IMAGE_PAINT)
            PdfImageScaleMode.FILL_CROP -> canvas.drawBitmap(
                bitmap,
                calculateCropRect(bitmap, availableWidth, availableHeight),
                pageRect,
                IMAGE_PAINT
            )
            PdfImageScaleMode.FIT -> {
                val scale = min(availableWidth.toFloat() / bitmap.width, availableHeight.toFloat() / bitmap.height)
                val width = bitmap.width * scale
                val height = bitmap.height * scale
                val left = (pageWidth - width) / 2f
                val top = (pageHeight - height) / 2f
                canvas.drawBitmap(bitmap, null, RectF(left, top, left + width, top + height), IMAGE_PAINT)
            }
        }
    }

    private fun drawImageIntoRect(
        canvas: Canvas,
        bitmap: Bitmap,
        target: RectF,
        scaleMode: PdfImageScaleMode,
    ) {
        when (scaleMode) {
            PdfImageScaleMode.STRETCH -> canvas.drawBitmap(bitmap, null, target, IMAGE_PAINT)
            PdfImageScaleMode.FILL_CROP -> canvas.drawBitmap(
                bitmap,
                calculateCropRect(bitmap, target.width().toInt(), target.height().toInt()),
                target,
                IMAGE_PAINT,
            )
            PdfImageScaleMode.FIT -> {
                val scale = min(target.width() / bitmap.width, target.height() / bitmap.height)
                val width = bitmap.width * scale
                val height = bitmap.height * scale
                val left = target.left + (target.width() - width) / 2f
                val top = target.top + (target.height() - height) / 2f
                canvas.drawBitmap(bitmap, null, RectF(left, top, left + width, top + height), IMAGE_PAINT)
            }
        }
    }

    private fun drawGridText(canvas: Canvas, value: String, pageWidth: Int, baseline: Float, align: Paint.Align) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.DKGRAY
            textAlign = align
            textSize = GRID_TEXT_SIZE
        }
        canvas.drawText(value, pageWidth / 2f, baseline, paint)
    }

    private fun calculateCropRect(bitmap: Bitmap, targetWidth: Int, targetHeight: Int): Rect {
        val sourceRatio = bitmap.width.toFloat() / bitmap.height
        val targetRatio = targetWidth.toFloat() / targetHeight
        return if (sourceRatio > targetRatio) {
            val cropWidth = (bitmap.height * targetRatio).toInt().coerceAtLeast(1)
            val left = (bitmap.width - cropWidth) / 2
            Rect(left, 0, left + cropWidth, bitmap.height)
        } else {
            val cropHeight = (bitmap.width / targetRatio).toInt().coerceAtLeast(1)
            val top = (bitmap.height - cropHeight) / 2
            Rect(0, top, bitmap.width, top + cropHeight)
        }
    }

    private fun drawPageNumber(
        canvas: Canvas,
        pageNumber: Int,
        pageWidth: Int,
        pageHeight: Int,
        margin: Int,
        color: Int
    ) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            textAlign = Paint.Align.CENTER
            textSize = PAGE_NUMBER_TEXT_SIZE
        }
        val baseline = pageHeight - (margin / 3f).coerceAtLeast(PAGE_NUMBER_MIN_BOTTOM)
        canvas.drawText(pageNumber.toString(), pageWidth / 2f, baseline, paint)
    }

    private fun safeDecodeDimension(pageDimension: Int, margin: Int, rasterScale: Int): Int {
        return ((pageDimension - margin * 2L) * rasterScale)
            .coerceIn(1L, Int.MAX_VALUE.toLong())
            .toInt()
    }

    private val IMAGE_PAINT = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private const val PAGE_NUMBER_TEXT_SIZE = 10f
    private const val PAGE_NUMBER_MIN_BOTTOM = 6f
    private const val GRID_TEXT_RESERVE = 22
    private const val GRID_TEXT_SIZE = 11f
}

private const val MAX_PDF_PAGE_POINTS = 14_400
private const val DEFAULT_PDF_RASTER_SCALE = 3
