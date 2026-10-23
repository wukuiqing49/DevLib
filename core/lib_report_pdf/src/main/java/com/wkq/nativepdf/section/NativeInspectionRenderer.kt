package com.wkq.nativepdf.section

import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Color
import com.wkq.nativepdf.model.NativeReportData
import com.wkq.nativepdf.model.NativeReportItem
import com.wkq.nativepdf.model.NativeReportStatus
import com.wkq.nativepdf.render.NativePdfPageManager
import com.wkq.nativepdf.render.drawSingleLineEllipsized
import com.wkq.nativepdf.render.drawTextLines
import com.wkq.nativepdf.render.measureTextHeight

internal fun renderAcceptanceIssueTable(page: NativePdfPageManager, data: NativeReportData) {
    val headers = listOf(data.labels.totalItems, data.labels.issue, data.labels.ownerDue)
    val rows = data.issues.map { issue ->
        listOf(issue.itemTitle, issue.issueText.ifBlank { "-" }, issue.ownerText.ifBlank { "-" })
    }
    drawKeyValueTable(page, headers, rows)
}

internal fun renderClientRiskSummary(page: NativePdfPageManager, data: NativeReportData) {
    data.issues.take(4).forEachIndexed { index, issue ->
        val body = listOf(issue.issueText, issue.suggestionText)
            .filter { it.isNotBlank() }
            .joinToString("\n")
        val bodyHeight = body.measureTextHeight(page.contentWidth - 170f, bodyPaint(page), 13f)
        val height = (58f + bodyHeight).coerceAtLeast(92f)
        page.ensureSpace(height)
        val rect = RectF(page.contentLeft, page.y, page.contentRight, page.y + height)
        page.canvas.drawRoundRect(rect, 0f, 0f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        })
        page.canvas.drawLine(rect.left, rect.bottom, rect.right, rect.bottom, Paint().apply {
            color = page.theme.lineColor
            strokeWidth = 1f
        })
        val number = (index + 1).toString().padStart(2, '0')
        page.canvas.drawText(number, rect.left, rect.top + 34f, titlePaint(page, 18f))
        page.canvas.drawTextLines(issue.itemTitle, rect.left + 52f, rect.top + 25f, 112f, titlePaint(page, 11f), 13f)
        page.canvas.drawTextLines(body.ifBlank { "-" }, rect.left + 176f, rect.top + 24f, rect.width() - 176f, bodyPaint(page), 13f)
        page.setY(rect.bottom + 4f)
    }
    if (data.issues.size > 4) {
        drawParagraph(page, "+${data.issues.size - 4} ${data.labels.issues}")
    }
    page.moveY(12f)
}

internal fun renderCompactDetails(page: NativePdfPageManager, data: NativeReportData) {
    val headers = listOf(data.labels.totalItems, data.labels.conclusion, data.labels.photoEvidence)
    drawInspectionStatusTable(page, headers, data.items, minimal = false)
}

internal fun renderAppendixDetails(page: NativePdfPageManager, data: NativeReportData) {
    val headers = listOf(data.labels.totalItems, data.labels.conclusion, data.labels.photoEvidence)
    val appendixItems = data.items.map { item ->
        item.copy(title = listOf(item.group, item.title).filter { it.isNotBlank() }.joinToString(" / "))
    }
    drawInspectionStatusTable(page, headers, appendixItems, minimal = true)
}

internal fun drawIssueCard(
    page: NativePdfPageManager,
    rect: RectF,
    index: Int,
    title: String,
    body: String,
    status: NativeReportStatus
) {
    drawCard(page, rect)
    val color = when (status) {
        NativeReportStatus.SUCCESS -> page.theme.successColor
        NativeReportStatus.DANGER -> page.theme.dangerColor
        NativeReportStatus.WARNING -> page.theme.warningColor
        NativeReportStatus.INFO -> page.theme.accentColor
        NativeReportStatus.MUTED -> page.theme.mutedColor
    }
    val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.FILL
    }
    page.canvas.drawRoundRect(RectF(rect.left, rect.top, rect.left + 6f, rect.bottom), 6f, 6f, markerPaint)
    page.canvas.drawText("${page.labels.issue} ${index.toString().padStart(2, '0')}", rect.left + 18f, rect.top + 22f, mutedPaint(page))
    page.canvas.drawTextLines(title, rect.left + 18f, rect.top + 44f, page.contentWidth - 36f, titlePaint(page, 12f), 14f)
    page.canvas.drawTextLines(body, rect.left + 18f, rect.top + 78f, page.contentWidth - 36f, bodyPaint(page), 13f)
}

internal fun drawInspectionStatusTable(
    page: NativePdfPageManager,
    headers: List<String>,
    items: List<NativeReportItem>,
    minimal: Boolean
) {
    if (items.isEmpty()) return
    val titleWidth = page.contentWidth * 0.56f
    val statusWidth = page.contentWidth * 0.22f
    val photoWidth = page.contentWidth - titleWidth - statusWidth
    val headerHeight = if (minimal) 26f else 30f
    val minRowHeight = if (minimal) 38f else 44f
    val lineHeight = 12f
    val cellPaint = bodyPaint(page)
    val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = page.theme.lineColor
        strokeWidth = 1f
    }
    val headerFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = page.theme.primaryColor
        style = Paint.Style.FILL
    }

    fun drawHeader() {
        page.ensureSpace(headerHeight + minRowHeight)
        val top = page.y
        if (minimal) {
            page.canvas.drawLine(page.contentLeft, top, page.contentRight, top, linePaint)
            page.canvas.drawLine(page.contentLeft, top + headerHeight, page.contentRight, top + headerHeight, linePaint)
        } else {
            page.canvas.drawRoundRect(
                RectF(page.contentLeft, top, page.contentRight, top + headerHeight),
                6f,
                6f,
                headerFill
            )
        }
        val headerTextPaint = if (minimal) mutedPaint(page) else whitePaint(page)
        page.canvas.drawSingleLineEllipsized(headers.getOrElse(0) { "" }, page.contentLeft + 8f, top + 19f, titleWidth - 16f, headerTextPaint)
        val statusHeader = headers.getOrElse(1) { "" }
        val statusHeaderX = page.contentLeft + titleWidth + statusWidth / 2f
        page.canvas.drawText(
            statusHeader,
            statusHeaderX - headerTextPaint.measureText(statusHeader) / 2f,
            top + 19f,
            headerTextPaint
        )
        val photoHeader = headers.getOrElse(2) { "" }
        val photoHeaderX = page.contentLeft + titleWidth + statusWidth + photoWidth / 2f
        page.canvas.drawText(
            photoHeader,
            photoHeaderX - headerTextPaint.measureText(photoHeader) / 2f,
            top + 19f,
            headerTextPaint
        )
        page.moveY(headerHeight)
    }

    drawHeader()
    items.forEach { item ->
        val titleHeight = item.title.measureTextHeight(titleWidth - 16f, cellPaint, lineHeight)
        val photoHeight = item.photoCountText.measureTextHeight(photoWidth - 12f, cellPaint, lineHeight)
        val rowHeight = (maxOf(titleHeight, photoHeight, 24f) + 18f).coerceAtLeast(minRowHeight)
        if (page.y + rowHeight > page.contentBottom) {
            page.newPage()
            drawHeader()
        }
        val rowTop = page.y
        page.canvas.drawTextLines(
            item.title,
            page.contentLeft + 8f,
            rowTop + 16f,
            titleWidth - 16f,
            cellPaint,
            lineHeight
        )
        drawCenteredStatusBadge(
            page = page,
            text = item.statusText.ifBlank { "-" },
            status = item.status,
            cell = RectF(
                page.contentLeft + titleWidth,
                rowTop,
                page.contentLeft + titleWidth + statusWidth,
                rowTop + rowHeight
            )
        )
        val photoText = item.photoCountText.ifBlank { "-" }
        val photoX = page.contentLeft + titleWidth + statusWidth + photoWidth / 2f
        page.canvas.drawText(
            photoText,
            photoX - cellPaint.measureText(photoText) / 2f,
            rowTop + rowHeight / 2f - (cellPaint.ascent() + cellPaint.descent()) / 2f,
            cellPaint
        )
        page.canvas.drawLine(page.contentLeft, rowTop + rowHeight, page.contentRight, rowTop + rowHeight, linePaint)
        page.moveY(rowHeight)
    }
    page.moveY(if (minimal) 16f else 14f)
}

internal fun statusColorFor(page: NativePdfPageManager, status: NativeReportStatus): Int {
    return when (status) {
        NativeReportStatus.SUCCESS -> page.theme.successColor
        NativeReportStatus.DANGER -> page.theme.dangerColor
        NativeReportStatus.WARNING -> page.theme.warningColor
        NativeReportStatus.INFO -> page.theme.accentColor
        NativeReportStatus.MUTED -> page.theme.mutedColor
    }
}

internal fun drawCenteredStatusBadge(
    page: NativePdfPageManager,
    text: String,
    status: NativeReportStatus,
    cell: RectF
) {
    val statusColor = statusColorFor(page, status)
    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 10f
        typeface = page.theme.titleTypeface
        textAlign = Paint.Align.CENTER
    }
    val width = (textPaint.measureText(text) + 20f).coerceIn(54f, cell.width() - 12f)
    val height = 25f
    val rect = RectF(
        cell.centerX() - width / 2f,
        cell.centerY() - height / 2f,
        cell.centerX() + width / 2f,
        cell.centerY() + height / 2f
    )
    page.canvas.drawRoundRect(rect, height / 2f, height / 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = statusColor
        style = Paint.Style.FILL
    })
    page.canvas.drawText(
        text,
        rect.centerX(),
        rect.centerY() - (textPaint.ascent() + textPaint.descent()) / 2f,
        textPaint
    )
}

internal fun drawKeyValueTable(page: NativePdfPageManager, headers: List<String>, rows: List<List<String>>) {
    TableSectionSupport.drawKeyValueTable(page, headers, rows)
}

internal fun drawMinimalAppendixTable(page: NativePdfPageManager, headers: List<String>, rows: List<List<String>>) {
    TableSectionSupport.drawMinimalAppendixTable(page, headers, rows)
}

internal fun drawInspectionCard(page: NativePdfPageManager, rect: RectF, status: NativeReportStatus) {
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = page.theme.lineColor
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }
    val statusColor = when (status) {
        NativeReportStatus.SUCCESS -> page.theme.successColor
        NativeReportStatus.DANGER -> page.theme.dangerColor
        NativeReportStatus.WARNING -> page.theme.warningColor
        NativeReportStatus.INFO -> page.theme.accentColor
        NativeReportStatus.MUTED -> page.theme.mutedColor
    }
    val statusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = statusColor
        style = Paint.Style.FILL
    }
    page.canvas.drawRoundRect(rect, 9f, 9f, fill)
    page.canvas.drawRoundRect(rect, 9f, 9f, stroke)
    page.canvas.drawRoundRect(RectF(rect.left, rect.top, rect.left + 6f, rect.bottom), 6f, 6f, statusPaint)
}

internal fun drawAcceptanceDetailCard(page: NativePdfPageManager, rect: RectF, status: NativeReportStatus) {
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = page.theme.fillColor
        style = Paint.Style.FILL
    }
    val strokeColor = when (status) {
        NativeReportStatus.SUCCESS -> page.theme.successColor
        NativeReportStatus.DANGER -> page.theme.dangerColor
        NativeReportStatus.WARNING -> page.theme.warningColor
        NativeReportStatus.INFO -> page.theme.accentColor
        NativeReportStatus.MUTED -> page.theme.mutedColor
    }
    page.canvas.drawRoundRect(rect, 6f, 6f, fill)
    page.canvas.drawRoundRect(rect, 6f, 6f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = strokeColor
        alpha = 170
        style = Paint.Style.STROKE
        strokeWidth = 1.4f
    })
    page.canvas.drawCircle(rect.left + 12f, rect.top + 18f, 4f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = strokeColor
        style = Paint.Style.FILL
    })
}

internal fun drawRecordBackground(page: NativePdfPageManager, rect: RectF) {
    if (rect.height() <= 6f) return
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = page.theme.fillColor
        style = Paint.Style.FILL
    }
    val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = page.theme.lineColor
        strokeWidth = 1f
    }
    page.canvas.drawRoundRect(rect, 6f, 6f, fill)
    page.canvas.drawLine(rect.left + 8f, rect.top, rect.right - 8f, rect.top, line)
}
