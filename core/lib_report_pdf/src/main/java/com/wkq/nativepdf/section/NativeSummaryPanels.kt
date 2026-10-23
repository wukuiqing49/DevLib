package com.wkq.nativepdf.section

import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Color
import com.wkq.nativepdf.model.NativeReportData
import com.wkq.nativepdf.model.NativeReportStatus
import com.wkq.nativepdf.render.NativePdfPageManager
import com.wkq.nativepdf.render.drawSingleLineEllipsized
import com.wkq.nativepdf.render.drawTextLines
import com.wkq.nativepdf.render.measureTextHeight

internal fun drawMetricCards(page: NativePdfPageManager, cards: List<Pair<String, String>>) {
    val gap = 10f
    val width = (page.contentWidth - gap * 3) / 4f
    val height = 62f
    page.ensureSpace(height)
    cards.forEachIndexed { index, card ->
        val left = page.contentLeft + index * (width + gap)
        val rect = RectF(left, page.y, left + width, page.y + height)
        drawCard(page, rect)
        page.canvas.drawText(card.first, rect.left + 10f, rect.top + 20f, mutedPaint(page))
        page.canvas.drawText(card.second.ifBlank { "-" }, rect.left + 10f, rect.top + 47f, titlePaint(page, 18f))
    }
    page.moveY(height + 18f)
}

internal fun drawEngineeringDashboard(page: NativePdfPageManager, data: NativeReportData) {
    val height = 152f
    page.ensureSpace(height)
    val rect = RectF(page.contentLeft, page.y, page.contentRight, page.y + height)
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = page.theme.fillColor
        style = Paint.Style.FILL
    }
    val primary = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = page.theme.primaryColor
        style = Paint.Style.FILL
    }
    drawCard(page, rect)
    page.canvas.drawRoundRect(RectF(rect.left, rect.top, rect.left + 116f, rect.bottom), 8f, 8f, primary)
    page.canvas.drawText(data.labels.progress, rect.left + 18f, rect.top + 34f, whitePaint(page))
    page.canvas.drawText(data.summary.progressText.ifBlank { "-" }, rect.left + 18f, rect.top + 86f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 28f
        typeface = page.theme.titleTypeface
    })
    page.canvas.drawText(data.labels.totalItems, rect.left + 18f, rect.top + 120f, Paint(whitePaint(page)).apply { alpha = 210 })
    page.canvas.drawText(data.summary.totalCount.toString(), rect.left + 82f, rect.top + 120f, whitePaint(page))
    val rows = listOf(
        data.labels.conclusion to data.summary.conclusionText,
        data.labels.issues to data.summary.issueCount.toString(),
        data.labels.pending to data.summary.pendingCount.toString(),
        data.labels.photoEvidence to data.summary.photoCount.toString()
    )
    rows.forEachIndexed { index, row ->
        val top = rect.top + 22f + index * 30f
        page.canvas.drawSingleLineEllipsized(row.first, rect.left + 142f, top, 100f, mutedPaint(page))
        val valuePaint = Paint(titlePaint(page, 11f)).apply {
            if (index == 0) {
                color = statusColorFor(page, data.summary.conclusionStatus)
            }
        }
        page.canvas.drawSingleLineEllipsized(row.second.ifBlank { "-" }, rect.left + 252f, top, rect.right - 270f, valuePaint)
        page.canvas.drawLine(rect.left + 142f, top + 10f, rect.right - 18f, top + 10f, Paint().apply {
            color = page.theme.lineColor
            strokeWidth = 1f
        })
    }
    page.setY(rect.bottom + 16f)
}

internal fun drawAcceptanceDecisionPanel(page: NativePdfPageManager, data: NativeReportData) {
    val height = 150f
    page.ensureSpace(height)
    val rect = RectF(page.contentLeft, page.y, page.contentRight, page.y + height)
    val statusColor = statusColorFor(page, data.summary.conclusionStatus)
    page.canvas.drawRoundRect(rect, 12f, 12f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = statusColor
        alpha = 24
        style = Paint.Style.FILL
    })
    page.canvas.drawRoundRect(RectF(rect.left, rect.top, rect.left + 9f, rect.bottom), 8f, 8f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = statusColor
        style = Paint.Style.FILL
    })
    page.canvas.drawText(data.labels.conclusion, rect.left + 24f, rect.top + 30f, mutedPaint(page))
    page.canvas.drawTextLines(
        data.summary.conclusionText.ifBlank { data.labels.pendingConfirmation },
        rect.left + 24f,
        rect.top + 70f,
        rect.width() - 210f,
        Paint(titlePaint(page, 24f)).apply {
            color = statusColor
        },
        28f
    )
    val rightLeft = rect.right - 164f
    val stats = listOf(
        data.labels.issues to data.summary.issueCount.toString(),
        data.labels.pending to data.summary.pendingCount.toString(),
        data.labels.signatures to data.signatures.size.toString()
    )
    stats.forEachIndexed { index, stat ->
        val y = rect.top + 42f + index * 34f
        page.canvas.drawText(stat.first, rightLeft, y, mutedPaint(page))
        page.canvas.drawText(stat.second, rect.right - 38f, y, titlePaint(page, 15f))
    }
    page.setY(rect.bottom + 14f)
}

internal fun drawAcceptanceCheckpointStrip(page: NativePdfPageManager, data: NativeReportData) {
    val headers = listOf(data.labels.totalItems, data.labels.issues, data.labels.pending, data.labels.photoEvidence)
    val values = listOf(
        data.summary.totalCount.toString(),
        data.summary.issueCount.toString(),
        data.summary.pendingCount.toString(),
        data.summary.photoCount.toString()
    )
    val gap = 8f
    val width = (page.contentWidth - gap * 3f) / 4f
    val height = 54f
    page.ensureSpace(height)
    headers.forEachIndexed { index, header ->
        val left = page.contentLeft + index * (width + gap)
        val rect = RectF(left, page.y, left + width, page.y + height)
        page.canvas.drawRoundRect(rect, 8f, 8f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (index == 1) page.theme.warningColor else page.theme.fillColor
            alpha = if (index == 1) 36 else 255
            style = Paint.Style.FILL
        })
        page.canvas.drawText(header, rect.left + 9f, rect.top + 19f, mutedPaint(page))
        page.canvas.drawText(values[index], rect.left + 9f, rect.top + 43f, titlePaint(page, 16f))
    }
    page.moveY(height + 16f)
}

internal fun drawClientExecutivePanel(page: NativePdfPageManager, data: NativeReportData) {
    val height = 164f
    page.ensureSpace(height)
    val rect = RectF(page.contentLeft, page.y, page.contentRight, page.y + height)
    val statusColor = statusColorFor(page, data.summary.conclusionStatus)
    page.canvas.drawRoundRect(rect, 14f, 14f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = page.theme.primaryColor
        style = Paint.Style.FILL
    })
    page.canvas.drawRoundRect(RectF(rect.left, rect.top, rect.left + 12f, rect.bottom), 12f, 12f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = statusColor
        style = Paint.Style.FILL
    })
    page.canvas.drawRoundRect(RectF(rect.right - 154f, rect.top + 20f, rect.right - 22f, rect.top + 54f), 17f, 17f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = statusColor
        style = Paint.Style.FILL
    })
    page.canvas.drawSingleLineEllipsized(
        data.summary.conclusionText.ifBlank { data.labels.pendingConfirmation },
        rect.right - 142f,
        rect.top + 42f,
        108f,
        Paint(whitePaint(page)).apply {
            textSize = 11f
            typeface = page.theme.titleTypeface
        }
    )
    page.canvas.drawText(data.labels.deliveryConclusion, rect.left + 22f, rect.top + 32f, Paint(whitePaint(page)).apply { alpha = 210 })
    page.canvas.drawTextLines(
        data.summary.conclusionText.ifBlank { data.labels.pendingConfirmation },
        rect.left + 22f,
        rect.top + 76f,
        rect.width() - 44f,
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 25f
            typeface = page.theme.titleTypeface
        },
        30f
    )
    page.canvas.drawText(
        "${data.labels.progress} ${data.summary.progressText}   ${data.labels.issues} ${data.summary.issueCount}   ${data.labels.photoEvidence} ${data.summary.photoCount}",
        rect.left + 22f,
        rect.bottom - 24f,
        Paint(whitePaint(page)).apply { alpha = 220 }
    )
    page.setY(rect.bottom + 16f)
}

internal fun drawPremiumBoardroomPanel(page: NativePdfPageManager, data: NativeReportData) {
    val height = 212f
    page.ensureSpace(height)
    val rect = RectF(page.contentLeft, page.y, page.contentRight, page.y + height)
    val statusColor = statusColorFor(page, data.summary.conclusionStatus)
    page.canvas.drawRoundRect(rect, 18f, 18f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = page.theme.primaryColor
        style = Paint.Style.FILL
    })
    page.canvas.drawRoundRect(RectF(rect.left, rect.top, rect.left + 12f, rect.bottom), 12f, 12f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = statusColor
        style = Paint.Style.FILL
    })
    page.canvas.drawRoundRect(RectF(rect.left + 24f, rect.top + 48f, rect.left + 184f, rect.top + 84f), 18f, 18f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = statusColor
        style = Paint.Style.FILL
    })
    page.canvas.drawSingleLineEllipsized(
        data.summary.conclusionText.ifBlank { data.labels.pendingConfirmation },
        rect.left + 40f,
        rect.top + 71f,
        128f,
        Paint(whitePaint(page)).apply {
            textSize = 12f
            typeface = page.theme.titleTypeface
        }
    )
    page.canvas.drawCircle(rect.right - 28f, rect.top + 24f, 112f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = page.theme.accentColor
        alpha = 46
        style = Paint.Style.FILL
    })
    page.canvas.drawText(data.labels.deliveryConclusion, rect.left + 24f, rect.top + 34f, Paint(whitePaint(page)).apply {
        alpha = 215
    })
    page.canvas.drawTextLines(
        data.summary.conclusionText.ifBlank { data.labels.pendingConfirmation },
        rect.left + 24f,
        rect.top + 110f,
        rect.width() - 48f,
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 22f
            typeface = page.theme.titleTypeface
        },
        25f
    )
    val metrics = listOf(
        data.labels.progress to data.summary.progressText,
        data.labels.totalItems to data.summary.totalCount.toString(),
        data.labels.issues to data.summary.issueCount.toString(),
        data.labels.signatures to data.signatures.size.toString()
    )
    val gap = 8f
    val width = (rect.width() - 48f - gap * 3f) / 4f
    metrics.forEachIndexed { index, metric ->
        val left = rect.left + 24f + index * (width + gap)
        val top = rect.bottom - 72f
        val card = RectF(left, top, left + width, rect.bottom - 20f)
        page.canvas.drawRoundRect(card, 10f, 10f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            alpha = 34
            style = Paint.Style.FILL
        })
        page.canvas.drawSingleLineEllipsized(metric.first, card.left + 9f, card.top + 18f, card.width() - 18f, Paint(whitePaint(page)).apply {
            alpha = 205
            textSize = 8.5f
        })
        page.canvas.drawSingleLineEllipsized(metric.second.ifBlank { "-" }, card.left + 9f, card.top + 42f, card.width() - 18f, Paint(whitePaint(page)).apply {
            textSize = 15f
            typeface = page.theme.titleTypeface
        })
    }
    page.setY(rect.bottom + 18f)
}

internal fun drawPremiumDeliverySnapshot(page: NativePdfPageManager, data: NativeReportData) {
    val rows = listOf(
        data.labels.projectName to data.projectName,
        data.labels.siteLocation to data.location,
        data.labels.reportId to data.reportId,
        data.labels.generatedAt to data.generatedAtText,
        data.labels.photoEvidence to data.summary.photoCount.toString()
    ) + data.metadata.take(2)
    val height = 46f + rows.size * 24f
    page.ensureSpace(height)
    val rect = RectF(page.contentLeft, page.y, page.contentRight, page.y + height)
    page.canvas.drawRoundRect(rect, 14f, 14f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = page.theme.fillColor
        style = Paint.Style.FILL
    })
    page.canvas.drawRoundRect(RectF(rect.left, rect.top, rect.left + 7f, rect.bottom), 7f, 7f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = page.theme.accentColor
        style = Paint.Style.FILL
    })
    page.canvas.drawText(data.labels.reportInfo, rect.left + 22f, rect.top + 28f, titlePaint(page, 12f))
    rows.forEachIndexed { index, row ->
        val y = rect.top + 56f + index * 24f
        page.canvas.drawSingleLineEllipsized(row.first, rect.left + 22f, y, 122f, mutedPaint(page))
        page.canvas.drawSingleLineEllipsized(row.second.ifBlank { "-" }, rect.left + 154f, y, rect.width() - 176f, bodyPaint(page))
    }
    page.setY(rect.bottom + 18f)
}

internal fun drawClientSnapshot(page: NativePdfPageManager, data: NativeReportData) {
    val rows = listOf(
        data.labels.projectName to data.projectName,
        data.labels.siteLocation to data.location,
        data.labels.reportId to data.reportId,
        data.labels.generatedAt to data.generatedAtText
    ) + data.metadata.take(2)
    val height = 34f + rows.size * 24f
    page.ensureSpace(height)
    val rect = RectF(page.contentLeft, page.y, page.contentRight, page.y + height)
    page.canvas.drawRoundRect(rect, 0f, 0f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    })
    page.canvas.drawLine(rect.left, rect.top, rect.right, rect.top, Paint().apply { color = page.theme.lineColor; strokeWidth = 1f })
    page.canvas.drawLine(rect.left, rect.bottom, rect.right, rect.bottom, Paint().apply { color = page.theme.lineColor; strokeWidth = 1f })
    rows.forEachIndexed { index, row ->
        val y = rect.top + 28f + index * 24f
        page.canvas.drawText(row.first, rect.left, y, mutedPaint(page))
        page.canvas.drawTextLines(row.second.ifBlank { "-" }, rect.left + 128f, y, rect.width() - 128f, bodyPaint(page), 12f)
    }
    page.setY(rect.bottom + 18f)
}

internal fun drawExecutiveSummaryPanel(page: NativePdfPageManager, data: NativeReportData) {
    val height = 116f
    page.ensureSpace(height)
    val rect = RectF(page.contentLeft, page.y, page.contentRight, page.y + height)
    val statusColor = statusColorFor(page, data.summary.conclusionStatus)
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = statusColor
        alpha = 28
        style = Paint.Style.FILL
    }
    val accent = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = statusColor
        style = Paint.Style.FILL
    }
    page.canvas.drawRoundRect(rect, 12f, 12f, fill)
    page.canvas.drawRoundRect(RectF(rect.left, rect.top, rect.left + 7f, rect.bottom), 7f, 7f, accent)
    page.canvas.drawText(data.labels.deliveryConclusion, rect.left + 20f, rect.top + 26f, mutedPaint(page))
    page.canvas.drawText(
        data.summary.conclusionText.ifBlank { data.labels.pendingConfirmation },
        rect.left + 20f,
        rect.top + 58f,
        Paint(titlePaint(page, 22f)).apply {
            color = statusColor
        }
    )
    page.canvas.drawTextLines(
        listOf(data.projectName, data.location, data.reportId).filter { it.isNotBlank() }.joinToString("\n"),
        rect.left + 245f,
        rect.top + 30f,
        rect.width() - 265f,
        bodyPaint(page),
        15f
    )
    page.setY(rect.bottom + 16f)
}

internal fun drawMetadataPanel(page: NativePdfPageManager, data: NativeReportData) {
    val rows = listOf(
        data.labels.reportId to data.reportId,
        data.labels.projectName to data.projectName,
        data.labels.siteLocation to data.location,
        data.labels.generatedAt to data.generatedAtText,
        data.labels.photoEvidence to data.summary.photoCount.toString()
    ) + data.metadata
    val valueWidth = page.contentWidth - 150f
    val rowHeights = rows.map { row ->
        row.second.measureTextHeight(valueWidth, bodyPaint(page), 12f).coerceAtLeast(22f) + 8f
    }
    val height = 38f + rowHeights.sum()
    page.ensureSpace(height)
    val rect = RectF(page.contentLeft, page.y, page.contentRight, page.y + height)
    drawCard(page, rect)
    page.canvas.drawText(data.labels.reportInfo, rect.left + 14f, rect.top + 22f, titlePaint(page, 11f))
    var rowTop = rect.top + 48f
    rows.forEachIndexed { index, row ->
        val y = rowTop
        page.canvas.drawText(row.first, rect.left + 14f, y, mutedPaint(page))
        page.canvas.drawTextLines(row.second.ifBlank { "-" }, rect.left + 130f, y, valueWidth, bodyPaint(page), 12f)
        rowTop += rowHeights[index]
    }
    page.setY(rect.bottom + 16f)
}

internal fun drawInspectionNote(page: NativePdfPageManager, note: String) {
    val textHeight = note.measureTextHeight(page.contentWidth - 28f, bodyPaint(page), 13f)
    val height = (42f + textHeight).coerceAtLeast(72f)
    page.ensureSpace(height)
    val rect = RectF(page.contentLeft, page.y, page.contentRight, page.y + height)
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = page.theme.fillColor
        style = Paint.Style.FILL
    }
    val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = page.theme.accentColor
        strokeWidth = 1.2f
        style = Paint.Style.STROKE
    }
    page.canvas.drawRoundRect(rect, 10f, 10f, fill)
    page.canvas.drawRoundRect(rect, 10f, 10f, line)
    page.canvas.drawTextLines(note, rect.left + 14f, rect.top + 24f, rect.width() - 28f, bodyPaint(page), 13f)
    page.setY(rect.bottom + 14f)
}

internal fun drawCompactSummaryPanel(page: NativePdfPageManager, data: NativeReportData) {
    val rows = listOf(
        data.labels.conclusion to data.summary.conclusionText,
        data.labels.progress to data.summary.progressText,
        data.labels.totalItems to data.summary.totalCount.toString(),
        data.labels.photoEvidence to data.summary.photoCount.toString()
    )
    val height = 34f + rows.size * 24f
    page.ensureSpace(height)
    val rect = RectF(page.contentLeft, page.y, page.contentRight, page.y + height)
    drawCard(page, rect)
    rows.forEachIndexed { index, row ->
        val y = rect.top + 28f + index * 24f
        page.canvas.drawSingleLineEllipsized(row.first, rect.left + 14f, y, 112f, mutedPaint(page))
        val valuePaint = if (index == 0) {
            Paint(titlePaint(page, 11f)).apply {
                color = statusColorFor(page, data.summary.conclusionStatus)
            }
        } else {
            bodyPaint(page)
        }
        page.canvas.drawSingleLineEllipsized(row.second.ifBlank { "-" }, rect.left + 135f, y, rect.width() - 149f, valuePaint)
    }
    page.setY(rect.bottom + 14f)
}

internal fun drawCoverConclusionBadge(
    page: NativePdfPageManager,
    text: String,
    status: NativeReportStatus,
    left: Float,
    top: Float
) {
    val color = when (status) {
        NativeReportStatus.SUCCESS -> page.theme.successColor
        NativeReportStatus.DANGER -> page.theme.dangerColor
        NativeReportStatus.WARNING -> page.theme.warningColor
        NativeReportStatus.INFO -> page.theme.accentColor
        NativeReportStatus.MUTED -> page.theme.mutedColor
    }
    val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.FILL
    }
    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = Color.WHITE
        textSize = 12.5f
        typeface = page.theme.titleTypeface
    }
    val displayText = text.ifBlank { page.labels.pendingConfirmation }
    val badgeWidth = (textPaint.measureText(displayText) + 28f).coerceIn(72f, 118f)
    val rect = RectF(left, top, left + badgeWidth, top + 32f)
    page.canvas.drawRoundRect(rect, 16f, 16f, bgPaint)
    val measuredTextWidth = textPaint.measureText(displayText)
    val textX = if (measuredTextWidth <= rect.width() - 20f) {
        rect.left + (rect.width() - measuredTextWidth) / 2f
    } else {
        rect.left + 10f
    }
    page.canvas.drawSingleLineEllipsized(displayText, textX, rect.top + 21f, rect.width() - 20f, textPaint)
}
