package com.wkq.nativepdf.section

import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Color
import com.wkq.nativepdf.model.NativeReportData
import com.wkq.nativepdf.render.NativePdfPageManager
import com.wkq.nativepdf.render.drawContainImage
import com.wkq.nativepdf.render.drawFitImage
import com.wkq.nativepdf.render.drawSingleLineEllipsized
import com.wkq.nativepdf.render.drawTextLines
import com.wkq.nativepdf.render.measureTextHeight

internal fun drawSignOffStatement(page: NativePdfPageManager, data: NativeReportData) {
    val text = data.labels.signStatement
    val height = 76f
    page.ensureSpace(height)
    val rect = RectF(page.contentLeft, page.y, page.contentRight, page.y + height)
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = page.theme.fillColor
        style = Paint.Style.FILL
    }
    val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = page.theme.primaryColor
        strokeWidth = 1.2f
        style = Paint.Style.STROKE
    }
    page.canvas.drawRoundRect(rect, 10f, 10f, fill)
    page.canvas.drawRoundRect(rect, 10f, 10f, stroke)
    page.canvas.drawSingleLineEllipsized(
        data.company.name.ifBlank { data.labels.signStatementTitle },
        rect.left + 14f,
        rect.top + 24f,
        rect.width() - 28f,
        titlePaint(page, 11f)
    )
    page.canvas.drawTextLines(text, rect.left + 14f, rect.top + 50f, rect.width() - 28f, bodyPaint(page), 13f)
    page.setY(rect.bottom + 14f)
}

internal fun renderInlineSignatures(
    page: NativePdfPageManager,
    rows: List<com.wkq.nativepdf.model.NativeReportSignature>
) {
    rows.forEach { signature ->
        val rolePaint = titlePaint(page, 10f)
        val infoPaint = bodyPaint(page)
        val textWidth = page.contentWidth * 0.42f
        val roleHeight = signature.role
            .measureTextHeight(textWidth - 20f, rolePaint, 12f)
            .coerceAtLeast(12f)
        val infoOffset = if (signature.avatarPath.isNotBlank()) 34f else 0f
        val infoWidth = (textWidth - 20f - infoOffset).coerceAtLeast(72f)
        val infoHeight = signature.inlineSignatureInfo()
            .measureTextHeight(infoWidth, infoPaint, 12f)
            .coerceAtLeast(12f)
        val height = (40f + roleHeight + infoHeight).coerceAtLeast(112f)
        page.ensureSpace(height)
        val rect = RectF(page.contentLeft, page.y, page.contentRight, page.y + height)
        drawSignatureCard(page, rect)
        page.canvas.drawTextLines(
            signature.role,
            rect.left + 12f,
            rect.top + 20f,
            textWidth - 24f,
            rolePaint,
            12f
        )
        val infoTop = rect.top + 20f + roleHeight + 6f
        val avatarDrawn = drawSignatureAvatar(
            page,
            signature.avatarPath,
            RectF(rect.left + 12f, infoTop, rect.left + 40f, infoTop + 28f)
        )
        val infoLeft = if (avatarDrawn) rect.left + 46f else rect.left + 12f
        page.canvas.drawTextLines(
            signature.inlineSignatureInfo(),
            infoLeft,
            infoTop,
            (textWidth - (infoLeft - rect.left) - 10f).coerceAtLeast(72f),
            infoPaint,
            12f
        )
        page.canvas.drawContainImage(
            signature.signatureImagePath,
            RectF(rect.left + textWidth + 8f, rect.top + 12f, rect.right - 12f, rect.bottom - 12f),
            Paint(Paint.ANTI_ALIAS_FLAG)
        )
        page.moveY(height + 10f)
    }
}

internal fun renderApprovalSignatures(
    page: NativePdfPageManager,
    rows: List<com.wkq.nativepdf.model.NativeReportSignature>
) {
    val infoPaint = bodyPaint(page)
    val rolePaint = mutedPaint(page)
    rows.chunked(2).forEach { row ->
        val columnCount = row.size.coerceAtLeast(1)
        val contentWidth = page.contentWidth - 32f
        val columnWidth = contentWidth / columnCount
        val roleHeights = row.map { signature ->
            signature.role
                .measureTextHeight(columnWidth - 20f, rolePaint, 12f)
                .coerceAtLeast(12f)
        }
        val infoHeights = row.map { signature ->
            val infoOffset = if (signature.avatarPath.isNotBlank()) 34f else 0f
            signature.approvalSignatureInfo().measureTextHeight(columnWidth - 20f - infoOffset, infoPaint, 12f)
        }
        val contentHeights = roleHeights.indices.map { index ->
            roleHeights[index] + (infoHeights.getOrNull(index) ?: 12f)
        }
        val height = (150f + (contentHeights.maxOrNull() ?: 24f)).coerceAtLeast(210f)
        page.ensureSpace(height)
        val rect = RectF(page.contentLeft, page.y, page.contentRight, page.y + height)
        page.canvas.drawRoundRect(rect, 10f, 10f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = page.theme.fillColor
            style = Paint.Style.FILL
        })
        page.canvas.drawText(page.labels.signOff, rect.left + 16f, rect.top + 26f, titlePaint(page, 12f))
        row.forEachIndexed { index, signature ->
            val left = rect.left + 16f + index * columnWidth
            if (index > 0) {
                page.canvas.drawLine(left, rect.top + 42f, left, rect.bottom - 14f, Paint().apply {
                    color = page.theme.lineColor
                    strokeWidth = 1f
                })
            }
            page.canvas.drawTextLines(
                signature.role,
                left + 10f,
                rect.top + 58f,
                columnWidth - 20f,
                rolePaint,
                12f
            )
            val infoTop = rect.top + 58f + roleHeights[index] + 8f
            val avatarDrawn = drawSignatureAvatar(
                page,
                signature.avatarPath,
                RectF(left + 10f, infoTop, left + 38f, infoTop + 28f)
            )
            val infoLeft = if (avatarDrawn) left + 44f else left + 10f
            page.canvas.drawTextLines(
                signature.approvalSignatureInfo(),
                infoLeft,
                infoTop,
                (columnWidth - 20f - (infoLeft - (left + 10f))).coerceAtLeast(72f),
                infoPaint,
                12f
            )
            page.canvas.drawContainImage(
                signature.signatureImagePath,
                RectF(
                    left + 10f,
                    rect.bottom - 80f,
                    left + columnWidth - 18f,
                    rect.bottom - 28f
                ),
                Paint(Paint.ANTI_ALIAS_FLAG)
            )
            page.canvas.drawLine(left + 10f, rect.bottom - 24f, left + columnWidth - 18f, rect.bottom - 24f, Paint().apply {
                color = page.theme.primaryColor
                alpha = 120
                strokeWidth = 1.2f
            })
        }
        page.setY(rect.bottom + 14f)
    }
}

internal fun drawSignatureAvatar(
    page: NativePdfPageManager,
    avatarPath: String,
    rect: RectF
): Boolean {
    if (avatarPath.isBlank()) return false
    return page.canvas.drawFitImage(avatarPath, rect, Paint(Paint.ANTI_ALIAS_FLAG), rect.width() / 2f)
}

internal fun com.wkq.nativepdf.model.NativeReportSignature.inlineSignatureInfo(): String {
    return listOf(signerName, signedAtText)
        .filter { it.isNotBlank() }
        .joinToString("\n")
        .ifBlank { "-" }
}

internal fun com.wkq.nativepdf.model.NativeReportSignature.approvalSignatureInfo(): String {
    return listOf(signerName, companyName, signedAtText)
        .filter { it.isNotBlank() }
        .joinToString("\n")
        .ifBlank { "-" }
}

internal fun drawSignatureCard(page: NativePdfPageManager, rect: RectF) {
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = page.theme.lineColor
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }
    val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = page.theme.primaryColor
        alpha = 90
        strokeWidth = 1.2f
    }
    page.canvas.drawRoundRect(rect, 8f, 8f, fill)
    page.canvas.drawRoundRect(rect, 8f, 8f, stroke)
    page.canvas.drawLine(rect.right - 180f, rect.bottom - 20f, rect.right - 16f, rect.bottom - 20f, line)
}
