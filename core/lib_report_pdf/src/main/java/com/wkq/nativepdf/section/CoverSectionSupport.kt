package com.wkq.nativepdf.section

import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import com.wkq.nativepdf.model.NativeReportData
import com.wkq.nativepdf.render.NativePdfPageManager
import com.wkq.nativepdf.render.drawSingleLineEllipsized

/** 封面专用的指标和项目信息绘制，避免封面实现与通用章节渲染器耦合。 */
internal fun drawCoverMetrics(
    page: NativePdfPageManager,
    data: NativeReportData,
    top: Float,
    @Suppress("UNUSED_PARAMETER") compact: Boolean
) {
    val cards = listOf(
        data.labels.progress to data.summary.progressText,
        data.labels.totalItems to data.summary.totalCount.toString(),
        data.labels.issues to data.summary.issueCount.toString(),
        data.labels.pending to data.summary.pendingCount.toString()
    )
    val gap = 10f
    val width = (page.contentWidth - gap * 3f) / 4f
    cards.forEachIndexed { index, item ->
        val left = page.contentLeft + index * (width + gap)
        val rect = RectF(left, top, left + width, top + 82f)
        page.canvas.drawRoundRect(rect, 10f, 10f, Paint().apply { color = Color.WHITE })
        page.canvas.drawSingleLineEllipsized(item.first, rect.left + 10f, rect.top + 24f, rect.width() - 20f, mutedPaint(page))
        page.canvas.drawSingleLineEllipsized(
            item.second.ifBlank { "-" },
            rect.left + 10f,
            rect.top + 58f,
            rect.width() - 20f,
            titlePaint(page, 18f)
        )
    }
}

internal fun drawCoverProjectInfo(
    page: NativePdfPageManager,
    data: NativeReportData,
    top: Float,
    height: Float
) {
    val rect = RectF(page.contentLeft, top, page.contentRight, top + height)
    page.canvas.drawRoundRect(rect, 12f, 12f, Paint().apply { color = page.theme.fillColor })
    val rows = listOf(
        data.labels.project to data.projectName,
        data.labels.location to data.location,
        data.labels.createdAt to data.createdAtText,
        data.labels.conclusion to data.summary.conclusionText
    )
    rows.forEachIndexed { index, row ->
        val y = rect.top + 32f + index * 38f
        page.canvas.drawText(row.first, rect.left + 16f, y, mutedPaint(page))
        page.canvas.drawSingleLineEllipsized(
            row.second.ifBlank { "-" },
            rect.left + 100f,
            y,
            rect.width() - 116f,
            bodyPaint(page)
        )
    }
}
