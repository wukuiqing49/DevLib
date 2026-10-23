package com.phototoolkit.engine

import kotlin.math.min

data class IdPhotoSheetLayout(
    val paperWidthPx: Int,
    val paperHeightPx: Int,
    val columns: Int,
    val rows: Int,
    val copies: Int,
    val marginPx: Int,
    val gapPx: Int,
) {
    init {
        require(paperWidthPx > 0 && paperHeightPx > 0)
        require(columns in 1..20 && rows in 1..20)
        require(copies in 1..min(columns * rows, 20))
        require(marginPx >= 0 && gapPx >= 0)
    }

    fun toRequest(inputPath: String, outputPath: String): IdPhotoSheetRequest = IdPhotoSheetRequest(
        inputPath = inputPath,
        outputPath = outputPath,
        paperWidthPx = paperWidthPx,
        paperHeightPx = paperHeightPx,
        columns = columns,
        rows = rows,
        copies = copies,
        marginPx = marginPx,
        gapPx = gapPx,
    )
}

object IdPhotoPrintSheetPlanner {
    const val FOUR_BY_SIX_WIDTH_PX = 1800
    const val FOUR_BY_SIX_HEIGHT_PX = 1200
    const val DEFAULT_MARGIN_PX = 48
    const val DEFAULT_GAP_PX = 24

    /** 保持证件照原始方向和像素尺寸，避免冲印时改变毫米规格。 */
    fun planFourBySix(photoWidthPx: Int, photoHeightPx: Int): IdPhotoSheetLayout? = plan(
        photoWidthPx = photoWidthPx,
        photoHeightPx = photoHeightPx,
        paperWidthPx = FOUR_BY_SIX_WIDTH_PX,
        paperHeightPx = FOUR_BY_SIX_HEIGHT_PX,
        marginPx = DEFAULT_MARGIN_PX,
        gapPx = DEFAULT_GAP_PX,
    )

    fun plan(
        photoWidthPx: Int,
        photoHeightPx: Int,
        paperWidthPx: Int,
        paperHeightPx: Int,
        marginPx: Int,
        gapPx: Int,
    ): IdPhotoSheetLayout? {
        require(photoWidthPx > 0 && photoHeightPx > 0)
        require(paperWidthPx > 0 && paperHeightPx > 0)
        require(marginPx >= 0 && gapPx >= 0)

        val usableWidth = paperWidthPx - marginPx * 2
        val usableHeight = paperHeightPx - marginPx * 2
        if (usableWidth < photoWidthPx || usableHeight < photoHeightPx) return null

        val columns = ((usableWidth + gapPx) / (photoWidthPx + gapPx)).coerceAtMost(20)
        val rows = ((usableHeight + gapPx) / (photoHeightPx + gapPx)).coerceAtMost(20)
        if (columns < 1 || rows < 1) return null

        return IdPhotoSheetLayout(
            paperWidthPx = paperWidthPx,
            paperHeightPx = paperHeightPx,
            columns = columns,
            rows = rows,
            copies = min(columns * rows, 20),
            marginPx = marginPx,
            gapPx = gapPx,
        )
    }
}
