package com.wkq.nativepdf.render

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import androidx.core.graphics.withRotation
import com.wkq.nativepdf.model.NativePdfTheme
import com.wkq.nativepdf.model.NativeReportData
import com.wkq.nativepdf.model.NativeReportDetailLayout
import com.wkq.nativepdf.model.NativeReportFooterStyle
import com.wkq.nativepdf.model.NativeReportHeaderStyle
import com.wkq.nativepdf.model.NativeReportPhotoLayout
import com.wkq.nativepdf.model.NativeReportSignatureLayout
import com.wkq.nativepdf.model.NativeReportSummaryStyle
import com.wkq.nativepdf.model.NativeReportTemplate

internal class NativePdfPageManager(
    private val pdfDocument: PdfDocument,
    private val data: NativeReportData,
    private val template: NativeReportTemplate,
    private val checkActive: () -> Unit = {}
) {
    val pageWidth: Int = A4_WIDTH_PT
    val pageHeight: Int = A4_HEIGHT_PT
    val contentLeft: Float = 42f
    val contentRight: Float = pageWidth - 42f
    val contentTop: Float = 70f
    val contentBottom: Float = pageHeight - 68f
    val contentWidth: Float = contentRight - contentLeft
    val theme: NativePdfTheme = template.theme
    val templateId: String = template.id
    val showCoverImage: Boolean = template.showCoverImage
    val showCompanyLogo: Boolean = template.showCompanyLogo
    val headerStyle: NativeReportHeaderStyle = template.headerStyle
    val footerStyle: NativeReportFooterStyle = template.footerStyle
    val summaryStyle: NativeReportSummaryStyle = template.summaryStyle
    val detailLayout: NativeReportDetailLayout = template.detailLayout
    val photoLayout: NativeReportPhotoLayout = template.photoLayout
    val signatureLayout: NativeReportSignatureLayout = template.signatureLayout
    val headerText: String = template.headerText
    val footerText: String = template.footerText
    val inspectionNote: String = template.inspectionNote
    val labels = data.labels

    private val footerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = theme.mutedColor
        textSize = 8.5f
        typeface = theme.typeface
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = theme.lineColor
        strokeWidth = 1f
    }
    private val watermarkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = theme.primaryColor
        alpha = 30
        textSize = 34f
        typeface = theme.titleTypeface
        textAlign = Paint.Align.CENTER
    }

    private var page: PdfDocument.Page? = null
    private var currentCanvas: Canvas? = null
    private var pageIndex: Int = 0
    private var footerPageIndex: Int = 0
    private var headerEnabledForPage: Boolean = true
    private var footerEnabledForPage: Boolean = true
    private var watermarkEnabledForPage: Boolean = true
    private var contentClipSaveCount: Int = 0

    val canvas: Canvas
        get() {
            checkActive()
            return currentCanvas ?: error("No active PDF page.")
        }

    var y: Float = contentTop
        private set

    fun start() {
        if (page == null) startPage()
    }

    fun finish() {
        finishPage()
    }

    fun discardActivePage() {
        val activePage = page ?: return
        pdfDocument.finishPage(activePage)
        page = null
        currentCanvas = null
        contentClipSaveCount = 0
    }

    fun setY(value: Float) {
        y = value.coerceAtMost(contentBottom)
    }

    fun moveY(delta: Float) {
        y += delta
    }

    fun ensureSpace(requiredHeight: Float) {
        ensurePage()
        if (y + requiredHeight > contentBottom) {
            finishPage()
            startPage()
        }
    }

    fun newPage() {
        finishPage()
        startPage()
    }

    fun startNewPage(
        showHeader: Boolean = true,
        showFooter: Boolean = true,
        showWatermark: Boolean = true
    ) {
        finishPage()
        startPage(
            showHeader = showHeader,
            showFooter = showFooter,
            showWatermark = showWatermark
        )
    }

    fun ensurePage() {
        checkActive()
        if (page == null) startPage()
    }

    fun contentRect(height: Float): RectF {
        ensureSpace(height)
        return RectF(contentLeft, y, contentRight, y + height)
    }

    private fun startPage(
        showHeader: Boolean = true,
        showFooter: Boolean = true,
        showWatermark: Boolean = true
    ) {
        checkActive()
        pageIndex += 1
        headerEnabledForPage = showHeader
        footerEnabledForPage = showFooter
        watermarkEnabledForPage = showWatermark
        val info = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageIndex).create()
        page = pdfDocument.startPage(info)
        currentCanvas = page?.canvas
        canvas.drawColor(theme.pageBackgroundColor)
        if (template.showHeader && headerEnabledForPage) drawHeader()
        y = contentTop
        // 正文必须限制在内容区，避免超长文本或图片覆盖页眉、页脚。
        val clipTop = if (template.showHeader && headerEnabledForPage) contentTop - 8f else 0f
        val clipBottom = if (template.showFooter && footerEnabledForPage) contentBottom else pageHeight.toFloat()
        contentClipSaveCount = canvas.save()
        canvas.clipRect(0f, clipTop, pageWidth.toFloat(), clipBottom)
    }

    private fun finishPage() {
        val activePage = page ?: return
        if (contentClipSaveCount > 0) {
            canvas.restoreToCount(contentClipSaveCount)
            contentClipSaveCount = 0
        }
        if (template.showFooter && footerEnabledForPage) {
            footerPageIndex += 1
            drawFooter()
        }
        // 水印最后绘制，确保能覆盖正文、图片、页眉和页脚。
        if (template.showWatermark && watermarkEnabledForPage) {
            drawWatermark()
        }
        pdfDocument.finishPage(activePage)
        page = null
        currentCanvas = null
    }

    private fun drawHeader() {
        val left = headerText.ifBlank { data.projectName.ifBlank { data.title } }
        val right = data.reportId
        val rightWidth = footerPaint.measureText(right)
        val leftTextX = drawHeaderLogo()
        val leftAreaWidth = (contentRight - leftTextX - rightWidth - 18f)
            .coerceAtLeast(contentWidth * 0.45f)
        when (headerStyle) {
            NativeReportHeaderStyle.MINIMAL -> {
                canvas.drawSingleLineEllipsized(left, leftTextX, 34f, leftAreaWidth, footerPaint)
                canvas.drawSingleLineEllipsized(right, contentRight - rightWidth, 34f, rightWidth, footerPaint)
            }
            NativeReportHeaderStyle.ACCEPTANCE -> {
                val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = theme.accentColor
                    strokeWidth = 3f
                }
                val issueText = "${data.labels.issues} ${data.summary.issueCount}"
                val issueWidth = footerPaint.measureText(issueText)
                canvas.drawLine(contentLeft, 17f, contentRight, 17f, accentPaint)
                canvas.drawSingleLineEllipsized(
                    left,
                    leftTextX,
                    39f,
                    (contentRight - leftTextX - issueWidth - 18f).coerceAtLeast(contentWidth * 0.45f),
                    footerPaint
                )
                canvas.drawSingleLineEllipsized(issueText, contentRight - issueWidth, 39f, issueWidth, footerPaint)
                canvas.drawLine(contentLeft, 53f, contentRight, 53f, linePaint)
            }
            NativeReportHeaderStyle.CLIENT_COVER -> {
                canvas.drawSingleLineEllipsized(data.company.name.ifBlank { left }, leftTextX, 39f, leftAreaWidth, footerPaint)
                canvas.drawSingleLineEllipsized(right, contentRight - rightWidth, 39f, rightWidth, footerPaint)
                canvas.drawLine(contentLeft, 53f, contentRight, 53f, linePaint)
            }
            NativeReportHeaderStyle.PREMIUM -> {
                val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = theme.accentColor
                    strokeWidth = 2f
                }
                val brand = data.company.name.ifBlank { left }
                canvas.drawLine(contentLeft, 15f, contentRight, 15f, accentPaint)
                canvas.drawSingleLineEllipsized(brand, leftTextX, 38f, leftAreaWidth, footerPaint)
                canvas.drawSingleLineEllipsized(right, contentRight - rightWidth, 38f, rightWidth, footerPaint)
                canvas.drawLine(contentLeft, 53f, contentRight, 53f, linePaint)
            }
            NativeReportHeaderStyle.ENGINEERING -> {
                val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = theme.primaryColor
                    strokeWidth = 3f
                }
                canvas.drawLine(contentLeft, 17f, contentRight, 17f, accentPaint)
                canvas.drawSingleLineEllipsized(left, leftTextX, 39f, leftAreaWidth, footerPaint)
                canvas.drawSingleLineEllipsized(right, contentRight - rightWidth, 39f, rightWidth, footerPaint)
                canvas.drawLine(contentLeft, 53f, contentRight, 53f, linePaint)
            }
        }
    }

    private fun drawHeaderLogo(): Float {
        if (!showCompanyLogo || data.company.logoPath.isBlank()) return contentLeft
        val rect = RectF(contentLeft, 22f, contentLeft + 28f, 48f)
        return if (canvas.drawContainImage(data.company.logoPath, rect, Paint(Paint.ANTI_ALIAS_FLAG), 6f)) {
            rect.right + 10f
        } else {
            contentLeft
        }
    }

    private fun drawFooter() {
        val y = pageHeight - 34f
        val left = footerText.ifBlank { data.generatedAtText.ifBlank { data.company.name } }
        // 封面不显示页脚，也不占用正文页码；正文从第 1 页开始连续编号。
        val pageText = data.labels.pageNumberFormat.format(footerPageIndex)
        when (footerStyle) {
            NativeReportFooterStyle.MINIMAL -> {
                val pageWidth = footerPaint.measureText(pageText)
                val leftTextX = drawFooterLogo(y)
                canvas.drawSingleLineEllipsized(
                    left,
                    leftTextX,
                    y,
                    (contentRight - leftTextX - pageWidth - 18f).coerceAtLeast(24f),
                    footerPaint
                )
                canvas.drawSingleLineEllipsized(pageText, contentRight - pageWidth, y, pageWidth, footerPaint)
            }
            NativeReportFooterStyle.BRANDED -> {
                val brand = data.company.name.ifBlank { left }
                val columnWidth = (contentWidth - 24f) / 3f
                canvas.drawLine(contentLeft, y - 20f, contentRight, y - 20f, linePaint)
                val brandTextX = drawFooterLogo(y)
                canvas.drawSingleLineEllipsized(
                    brand,
                    brandTextX,
                    y,
                    (columnWidth - (brandTextX - contentLeft)).coerceAtLeast(24f),
                    footerPaint
                )
                canvas.drawSingleLineEllipsized(left, contentLeft + columnWidth + 12f, y, columnWidth, footerPaint)
                canvas.drawSingleLineEllipsized(pageText, contentRight - columnWidth, y, columnWidth, footerPaint)
            }
            NativeReportFooterStyle.STANDARD -> {
                val columnWidth = (contentWidth - 24f) / 3f
                canvas.drawLine(contentLeft, y - 20f, contentRight, y - 20f, linePaint)
                val center = data.reportId
                val leftTextX = drawFooterLogo(y)
                canvas.drawSingleLineEllipsized(
                    left,
                    leftTextX,
                    y,
                    (columnWidth - (leftTextX - contentLeft)).coerceAtLeast(24f),
                    footerPaint
                )
                canvas.drawSingleLineEllipsized(center, contentLeft + columnWidth + 12f, y, columnWidth, footerPaint)
                canvas.drawSingleLineEllipsized(pageText, contentRight - columnWidth, y, columnWidth, footerPaint)
            }
        }
    }

    private fun drawFooterLogo(baseline: Float): Float {
        if (!showCompanyLogo || data.company.logoPath.isBlank()) return contentLeft
        val rect = RectF(contentLeft, baseline - 16f, contentLeft + 18f, baseline + 2f)
        return if (canvas.drawContainImage(data.company.logoPath, rect, Paint(Paint.ANTI_ALIAS_FLAG), 4f)) {
            rect.right + 7f
        } else {
            contentLeft
        }
    }

    private fun drawWatermark() {
        val text = template.watermarkText.ifBlank {
            data.company.name.ifBlank { data.projectName }.ifBlank { data.title }
        }
        if (text.isBlank()) return
        canvas.withRotation(-28f, pageWidth / 2f, pageHeight / 2f) {
            val spacingX = watermarkPaint.measureText(text).coerceAtLeast(180f) + 90f
            val spacingY = 150f
            var y = -pageHeight.toFloat()
            while (y <= pageHeight * 2f) {
                var x = -pageWidth.toFloat()
                while (x <= pageWidth * 2f) {
                    canvas.drawText(text, x, y, watermarkPaint)
                    x += spacingX
                }
                y += spacingY
            }
        }
    }

    companion object {
        const val A4_WIDTH_PT = 595
        const val A4_HEIGHT_PT = 842
    }
}
