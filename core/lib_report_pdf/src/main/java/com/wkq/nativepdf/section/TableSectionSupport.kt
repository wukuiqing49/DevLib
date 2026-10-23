package com.wkq.nativepdf.section

import android.graphics.Paint
import android.graphics.RectF
import com.wkq.nativepdf.render.NativePdfPageManager
import com.wkq.nativepdf.render.drawTextLines
import com.wkq.nativepdf.render.measureTextHeight

/** 表格类版式的独立绘制实现，避免主渲染器继续膨胀。 */
internal object TableSectionSupport {
    fun drawKeyValueTable(
        page: NativePdfPageManager,
        headers: List<String>,
        rows: List<List<String>>
    ) {
        if (rows.isEmpty()) return
        val columnWidth = page.contentWidth / headers.size.coerceAtLeast(1)
        val headerHeight = 28f
        val minRowHeight = 42f
        val cellPaint = bodyPaint(page)
        val lineHeight = 12f
        val headerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = page.theme.primaryColor
            style = Paint.Style.FILL
        }
        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = page.theme.lineColor
            strokeWidth = 1f
        }
        fun drawHeader() {
            page.ensureSpace(headerHeight + minRowHeight)
            val top = page.y
            page.canvas.drawRoundRect(
                RectF(page.contentLeft, top, page.contentRight, top + headerHeight),
                6f,
                6f,
                headerPaint
            )
            headers.forEachIndexed { index, header ->
                page.canvas.drawText(header, page.contentLeft + index * columnWidth + 8f, top + 19f, whitePaint(page))
            }
            page.moveY(headerHeight)
        }
        drawHeader()
        rows.forEach { row ->
            val rowHeight = row.take(headers.size)
                .maxOfOrNull { cell -> cell.measureTextHeight(columnWidth - 14f, cellPaint, lineHeight) + 22f }
                ?.coerceAtLeast(minRowHeight)
                ?: minRowHeight
            if (page.y + rowHeight > page.contentBottom) {
                page.newPage()
                drawHeader()
            }
            val rowTop = page.y
            page.canvas.drawLine(page.contentLeft, rowTop + rowHeight, page.contentRight, rowTop + rowHeight, linePaint)
            row.take(headers.size).forEachIndexed { index, cell ->
                page.canvas.drawTextLines(
                    cell,
                    page.contentLeft + index * columnWidth + 8f,
                    rowTop + 15f,
                    columnWidth - 14f,
                    cellPaint,
                    lineHeight
                )
            }
            page.moveY(rowHeight)
        }
        page.moveY(14f)
    }

    fun drawMinimalAppendixTable(
        page: NativePdfPageManager,
        headers: List<String>,
        rows: List<List<String>>
    ) {
        if (rows.isEmpty()) return
        val columnWidth = page.contentWidth / headers.size.coerceAtLeast(1)
        val headerHeight = 24f
        val lineHeight = 11f
        val cellPaint = bodyPaint(page)
        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = page.theme.lineColor
            strokeWidth = 1f
        }
        fun drawHeader() {
            page.ensureSpace(headerHeight + 36f)
            val top = page.y
            page.canvas.drawLine(page.contentLeft, top, page.contentRight, top, linePaint)
            headers.forEachIndexed { index, header ->
                page.canvas.drawText(header.uppercase(), page.contentLeft + index * columnWidth, top + 17f, mutedPaint(page))
            }
            page.canvas.drawLine(page.contentLeft, top + headerHeight, page.contentRight, top + headerHeight, linePaint)
            page.moveY(headerHeight)
        }
        drawHeader()
        rows.forEach { row ->
            val rowHeight = row.take(headers.size)
                .maxOfOrNull { cell -> cell.measureTextHeight(columnWidth - 10f, cellPaint, lineHeight) + 18f }
                ?.coerceAtLeast(34f)
                ?: 34f
            if (page.y + rowHeight > page.contentBottom) {
                page.newPage()
                drawHeader()
            }
            val rowTop = page.y
            row.take(headers.size).forEachIndexed { index, cell ->
                page.canvas.drawTextLines(
                    cell,
                    page.contentLeft + index * columnWidth,
                    rowTop + 14f,
                    columnWidth - 10f,
                    cellPaint,
                    lineHeight
                )
            }
            page.canvas.drawLine(page.contentLeft, rowTop + rowHeight, page.contentRight, rowTop + rowHeight, linePaint)
            page.moveY(rowHeight)
        }
        page.moveY(16f)
    }
}
