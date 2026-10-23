package com.wkq.nativepdf.section

import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Color
import com.wkq.nativepdf.model.NativeReportData
import com.wkq.nativepdf.model.NativeReportDetailLayout
import com.wkq.nativepdf.model.NativeReportPhotoLayout
import com.wkq.nativepdf.model.NativeReportSignatureLayout
import com.wkq.nativepdf.model.NativeReportStatus
import com.wkq.nativepdf.model.NativeReportSummaryStyle
import com.wkq.nativepdf.render.NativePdfPageManager
import com.wkq.nativepdf.render.drawContainImage
import com.wkq.nativepdf.render.drawTextLines
import com.wkq.nativepdf.render.measureTextHeight

internal class SummarySectionRenderer : NativeSectionRenderer {
    override fun render(page: NativePdfPageManager, data: NativeReportData) {
        drawSectionTitle(
            page,
            when (page.summaryStyle) {
                NativeReportSummaryStyle.PREMIUM -> data.labels.clientDecisionSummary
                NativeReportSummaryStyle.EXECUTIVE -> data.labels.clientDecisionSummary
                NativeReportSummaryStyle.COMPACT -> data.labels.reportSummary
                else -> data.labels.projectSummary
            },
            minFollowingHeight = 150f
        )
        if (page.inspectionNote.isNotBlank()) {
            drawInspectionNote(page, page.inspectionNote)
        }
        when (page.summaryStyle) {
            NativeReportSummaryStyle.PREMIUM -> {
                drawPremiumBoardroomPanel(page, data)
                drawPremiumDeliverySnapshot(page, data)
            }
            NativeReportSummaryStyle.COMPACT -> {
                drawCompactSummaryPanel(page, data)
                drawMetadataPanel(page, data)
            }
            NativeReportSummaryStyle.DECISION -> {
                drawAcceptanceDecisionPanel(page, data)
                drawAcceptanceCheckpointStrip(page, data)
            }
            NativeReportSummaryStyle.EXECUTIVE -> {
                drawClientExecutivePanel(page, data)
                drawClientSnapshot(page, data)
            }
            NativeReportSummaryStyle.METRIC_CARDS -> {
                drawEngineeringDashboard(page, data)
                drawMetadataPanel(page, data)
            }
        }
    }
}

internal class IssueSummarySectionRenderer : NativeSectionRenderer {
    override fun render(page: NativePdfPageManager, data: NativeReportData) {
        drawSectionTitle(
            page,
            when (page.summaryStyle) {
                NativeReportSummaryStyle.DECISION -> data.labels.rectificationFocus
                NativeReportSummaryStyle.PREMIUM -> data.labels.riskFocus
                NativeReportSummaryStyle.EXECUTIVE -> data.labels.riskFocus
                else -> data.labels.issueSummary
            },
            minFollowingHeight = if (data.issues.isEmpty()) 72f else 100f
        )
        if (data.issues.isEmpty()) {
            drawPositiveStatement(page, data.labels.noIssues)
            return
        }
        when (page.summaryStyle) {
            NativeReportSummaryStyle.DECISION -> {
                renderAcceptanceIssueTable(page, data)
                return
            }
            NativeReportSummaryStyle.PREMIUM -> {
                renderClientRiskSummary(page, data)
                return
            }
            NativeReportSummaryStyle.EXECUTIVE -> {
                renderClientRiskSummary(page, data)
                return
            }
            else -> Unit
        }
        data.issues.forEachIndexed { index, issue ->
            val body = listOf(
                "${data.labels.issue}: ${issue.issueText}",
                "${data.labels.recommendation}: ${issue.suggestionText}",
                "${data.labels.ownerDue}: ${issue.ownerText}"
            ).joinToString("\n")
            val bodyHeight = body.measureTextHeight(page.contentWidth - 32f, bodyPaint(page), 13f)
            val height = (78f + bodyHeight).coerceAtLeast(120f)
            page.ensureSpace(height)
            val rect = RectF(page.contentLeft, page.y, page.contentRight, page.y + height)
            drawIssueCard(page, rect, index + 1, issue.itemTitle, body, issue.status)
            page.setY(rect.bottom + 12f)
        }
    }
}

internal class InspectionDetailsSectionRenderer : NativeSectionRenderer {
    override fun render(page: NativePdfPageManager, data: NativeReportData) {
        drawSectionTitle(
            page,
            when (page.detailLayout) {
                NativeReportDetailLayout.APPENDIX -> data.labels.detailAppendix
                NativeReportDetailLayout.CARD -> if (page.summaryStyle == NativeReportSummaryStyle.DECISION) data.labels.acceptanceDetails else data.labels.inspectionDetails
                else -> data.labels.inspectionDetails
            },
            minFollowingHeight = 96f
        )
        if (page.detailLayout == NativeReportDetailLayout.COMPACT) {
            renderCompactDetails(page, data)
            return
        }
        if (page.detailLayout == NativeReportDetailLayout.APPENDIX) {
            renderAppendixDetails(page, data)
            return
        }
        data.items.forEach { item ->
            val horizontalPadding = 18f
            val statusColumnWidth = 104f
            val statusRightInset = 16f
            val columnGap = 12f
            val titleWidth = page.contentWidth - horizontalPadding * 2f - statusColumnWidth - statusRightInset - columnGap
            val titlePaint = titlePaint(page, 11f)
            val bodyPaint = bodyPaint(page)
            val statusText = item.statusText.ifBlank { "-" }
            val titleHeight = item.title.measureTextHeight(titleWidth, titlePaint, 13f)
            val badgeHeight = statusText.measureTextHeight(
                statusColumnWidth - 18f,
                Paint(whitePaint(page)).apply { textSize = 10.5f },
                11f
            ) + 12f
            val photoHeight = item.photoCountText.measureTextHeight(statusColumnWidth, mutedPaint(page), 11f)
            val titleBlockBottom = 44f + titleHeight
            val rightColumnBottom = 16f + badgeHeight + 10f + photoHeight
            val recordTopOffset = maxOf(titleBlockBottom, rightColumnBottom) + 14f
            val recordHeight = item.recordText.measureTextHeight(page.contentWidth - 56f, bodyPaint, 13f)
            val height = recordTopOffset + recordHeight + 30f
            page.ensureSpace(height)
            val rect = RectF(page.contentLeft, page.y, page.contentRight, page.y + height)
            if (page.summaryStyle == NativeReportSummaryStyle.DECISION) {
                drawAcceptanceDetailCard(page, rect, item.status)
            } else {
                drawInspectionCard(page, rect, item.status)
            }
            val contentLeft = rect.left + horizontalPadding
            val contentRight = rect.right - horizontalPadding
            val statusLeft = contentRight - statusColumnWidth - statusRightInset
            page.canvas.drawText(item.group.ifBlank { data.labels.totalItems }, contentLeft, rect.top + 22f, mutedPaint(page))
            val titleBottom = page.canvas.drawTextLines(item.title, contentLeft, rect.top + 44f, titleWidth, titlePaint, 13f)
            val statusBottom = drawBadge(page, statusText, item.status, statusLeft, rect.top + 16f, statusColumnWidth)
            val photoTop = statusBottom + 10f
            val photoBottom = page.canvas.drawTextLines(item.photoCountText, statusLeft, photoTop, statusColumnWidth, mutedPaint(page), 11f)
            val recordTop = maxOf(titleBottom, photoBottom) + 14f
            drawRecordBackground(page, RectF(contentLeft, recordTop - 10f, contentRight, rect.bottom - 12f))
            page.canvas.drawTextLines(item.recordText, contentLeft + 10f, recordTop + 6f, page.contentWidth - 56f, bodyPaint, 13f)
            page.setY(rect.bottom + 10f)
        }
    }
}

internal class PhotoEvidenceSectionRenderer : NativeSectionRenderer {
    override fun render(page: NativePdfPageManager, data: NativeReportData) {
        drawSectionTitle(
            page,
            data.labels.photoEvidence,
            minFollowingHeight = if (data.photos.isEmpty()) 42f else 205f
        )
        if (data.photos.isEmpty()) {
            drawParagraph(page, data.labels.noPhotos)
            return
        }
        val hasBeforeAfter = data.photos.any { it.isBeforeEvidence() || it.isAfterEvidence() }
        if (page.photoLayout == NativeReportPhotoLayout.BEFORE_AFTER || hasBeforeAfter) {
            renderBeforeAfterEvidence(page, data)
            return
        }
        if (page.photoLayout == NativeReportPhotoLayout.HIGHLIGHT) {
            renderHighlightEvidence(page, data)
            return
        }
        data.photos.groupBy { it.itemTitle.ifBlank { data.labels.sitePhoto } }
            .forEach { (groupTitle, photos) ->
                photos.forEachIndexed { index, photo ->
                    val title = if (photos.size > 1) {
                        "$groupTitle (${index + 1}/${photos.size})"
                    } else {
                        groupTitle
                    }
                    renderSingleEvidenceCard(page, photo, title)
                }
        }
    }
}

internal class SignatureSectionRenderer : NativeSectionRenderer {
    override fun render(page: NativePdfPageManager, data: NativeReportData) {
        drawSectionTitle(page, data.labels.signOff, minFollowingHeight = 170f)
        drawSignOffStatement(page, data)
        val rows = data.signatures.ifEmpty {
            listOf(com.wkq.nativepdf.model.NativeReportSignature(role = data.labels.inspector, signerName = data.labels.notSet))
        }
        if (page.signatureLayout == NativeReportSignatureLayout.INLINE) {
            renderInlineSignatures(page, rows)
            return
        }
        if (page.signatureLayout == NativeReportSignatureLayout.APPROVAL_BLOCK) {
            renderApprovalSignatures(page, rows)
            return
        }
        rows.forEach { signature ->
            val infoOffset = if (signature.avatarPath.isNotBlank()) 46f else 0f
            val infoWidth = (page.contentWidth - 220f - infoOffset).coerceAtLeast(80f)
            val infoText = listOf(
                signature.signerName,
                signature.companyName,
                signature.signedAtText,
                signature.locationText
            ).filter { it.isNotBlank() }.joinToString("\n")
            val infoHeight = infoText.measureTextHeight(infoWidth, bodyPaint(page), 14f)
            val height = (58f + infoHeight).coerceAtLeast(118f)
            page.ensureSpace(height)
            val rect = RectF(page.contentLeft, page.y, page.contentRight, page.y + height)
            drawSignatureCard(page, rect)
            page.canvas.drawText(signature.role, rect.left + 14f, rect.top + 25f, titlePaint(page, 12f))
            val avatarDrawn = drawSignatureAvatar(
                page,
                signature.avatarPath,
                RectF(rect.left + 14f, rect.top + 44f, rect.left + 50f, rect.top + 80f)
            )
            val infoLeft = if (avatarDrawn) rect.left + 60f else rect.left + 14f
            page.canvas.drawTextLines(infoText, infoLeft, rect.top + 50f, infoWidth, bodyPaint(page), 14f)
            page.canvas.drawContainImage(signature.signatureImagePath, RectF(rect.right - 180f, rect.top + 16f, rect.right - 16f, rect.bottom - 16f), Paint(Paint.ANTI_ALIAS_FLAG))
            page.setY(rect.bottom + 12f)
        }
    }
}

internal fun drawSectionTitle(
    page: NativePdfPageManager,
    title: String,
    minFollowingHeight: Float = 72f
) {
    val titleBlockHeight = 46f
    page.ensureSpace(titleBlockHeight + minFollowingHeight)
    val paint = titlePaint(page, 16f)
    page.canvas.drawText(title, page.contentLeft, page.y + 18f, paint)
    val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = page.theme.lineColor
        strokeWidth = 1f
    }
    page.canvas.drawLine(page.contentLeft, page.y + 30f, page.contentRight, page.y + 30f, linePaint)
    page.moveY(46f)
}

internal fun drawParagraph(page: NativePdfPageManager, text: String) {
    val paint = bodyPaint(page)
    val height = text.measureTextHeight(page.contentWidth, paint, 14f)
    page.ensureSpace(height)
    val endY = page.canvas.drawTextLines(text, page.contentLeft, page.y, page.contentWidth, paint, 14f)
    page.setY(endY + 12f)
}

internal fun drawPositiveStatement(page: NativePdfPageManager, text: String) {
    val height = 72f
    page.ensureSpace(height)
    val rect = RectF(page.contentLeft, page.y, page.contentRight, page.y + height)
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = page.theme.successColor
        alpha = 24
        style = Paint.Style.FILL
    }
    val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = page.theme.successColor
        strokeWidth = 1.2f
        style = Paint.Style.STROKE
    }
    page.canvas.drawRoundRect(rect, 10f, 10f, fill)
    page.canvas.drawRoundRect(rect, 10f, 10f, stroke)
    page.canvas.drawText(text, rect.left + 18f, rect.top + 42f, titlePaint(page, 12f))
    page.setY(rect.bottom + 14f)
}

internal fun drawCard(page: NativePdfPageManager, rect: RectF) {
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = page.theme.fillColor
        style = Paint.Style.FILL
    }
    val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = page.theme.lineColor
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }
    page.canvas.drawRoundRect(rect, 8f, 8f, fill)
    page.canvas.drawRoundRect(rect, 8f, 8f, stroke)
}

internal fun drawBadge(
    page: NativePdfPageManager,
    text: String,
    status: NativeReportStatus,
    x: Float,
    y: Float,
    maxWidth: Float
): Float {
    val color = when (status) {
        NativeReportStatus.SUCCESS -> page.theme.successColor
        NativeReportStatus.DANGER -> page.theme.dangerColor
        NativeReportStatus.WARNING -> page.theme.warningColor
        NativeReportStatus.INFO -> page.theme.accentColor
        NativeReportStatus.MUTED -> page.theme.mutedColor
    }
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.FILL
    }
    val textPaint = Paint(whitePaint(page)).apply { textSize = 10.5f }
    val badgeWidth = (textPaint.measureText(text.ifBlank { "-" }) + 22f).coerceIn(58f, maxWidth)
    val textWidth = (badgeWidth - 18f).coerceAtLeast(24f)
    val textHeight = text.measureTextHeight(textWidth, textPaint, 11f)
    val badgeLeft = x + (maxWidth - badgeWidth) / 2f
    val rect = RectF(badgeLeft, y, badgeLeft + badgeWidth, y + textHeight + 12f)
    page.canvas.drawRoundRect(rect, 11f, 11f, paint)
    textPaint.textAlign = Paint.Align.CENTER
    page.canvas.drawText(
        text,
        rect.centerX(),
        rect.centerY() - (textPaint.ascent() + textPaint.descent()) / 2f,
        textPaint
    )
    return rect.bottom
}

internal fun titlePaint(page: NativePdfPageManager, size: Float): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = page.theme.titleColor
    textSize = size
    typeface = page.theme.titleTypeface
}

internal fun bodyPaint(page: NativePdfPageManager): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = page.theme.bodyColor
    textSize = 9.5f
    typeface = page.theme.typeface
}

internal fun mutedPaint(page: NativePdfPageManager): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = page.theme.mutedColor
    textSize = 8.5f
    typeface = page.theme.typeface
}

internal fun whitePaint(page: NativePdfPageManager): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = android.graphics.Color.WHITE
    textSize = 8.5f
    typeface = page.theme.titleTypeface
}
