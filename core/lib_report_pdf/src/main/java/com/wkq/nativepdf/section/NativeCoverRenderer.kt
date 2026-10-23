package com.wkq.nativepdf.section

import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Color
import com.wkq.nativepdf.model.NativeReportData
import com.wkq.nativepdf.model.NativeReportHeaderStyle
import com.wkq.nativepdf.render.NativePdfPageManager
import com.wkq.nativepdf.render.drawCoverImage
import com.wkq.nativepdf.render.drawContainImage
import com.wkq.nativepdf.render.drawSingleLineEllipsized
import com.wkq.nativepdf.render.drawTextLines
import java.io.File

internal class CoverSectionRenderer : NativeSectionRenderer {
    override fun render(page: NativePdfPageManager, data: NativeReportData) {
        if (page.headerStyle == NativeReportHeaderStyle.PREMIUM) {
            renderPremiumCover(page, data)
            return
        }
        page.startNewPage(showHeader = false, showFooter = false)
        page.canvas.drawCoverImage(
            data.coverImagePath,
            RectF(0f, 0f, page.pageWidth.toFloat(), page.pageHeight.toFloat()),
            0f,
            Paint(Paint.ANTI_ALIAS_FLAG)
        )
        // 封面只展示封面图片并独占一页，项目说明由后续 Summary 区域从新页开始绘制。
        page.setY(page.contentBottom)
    }

    private fun renderSimpleCover(page: NativePdfPageManager, data: NativeReportData) {
        page.startNewPage(showHeader = false, showFooter = false)
        val theme = page.theme
        val canvas = page.canvas
        val primaryPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = theme.primaryColor
            style = Paint.Style.FILL
        }
        val softPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = theme.fillColor
            style = Paint.Style.FILL
        }
        canvas.drawRect(0f, 0f, page.pageWidth.toFloat(), page.pageHeight.toFloat(), Paint().apply {
            color = Color.WHITE
        })
        canvas.drawRect(0f, 0f, page.pageWidth.toFloat(), 12f, primaryPaint)
        canvas.drawCircle(page.pageWidth - 28f, 40f, 110f, softPaint)
        canvas.drawCircle(page.pageWidth - 8f, 18f, 58f, Paint(softPaint).apply {
            color = theme.primaryColor
            alpha = 18
        })

        canvas.drawText(data.labels.siteReport, page.contentLeft, 76f, mutedPaint(page))
        val titleEndY = canvas.drawTextLines(
            data.title,
            page.contentLeft,
            126f,
            page.contentWidth - 60f,
            titlePaint(page, 30f),
            36f
        )
        val reportY = maxOf(218f, titleEndY + 20f).coerceAtMost(258f)
        val dividerY = reportY + 26f
        canvas.drawText(data.reportId, page.contentLeft, reportY, bodyPaint(page))
        canvas.drawLine(page.contentLeft, dividerY, page.contentRight, dividerY, Paint().apply {
            color = theme.lineColor
            strokeWidth = 1f
        })

        val metricsTop = dividerY + 36f
        drawCoverMetrics(page, data, metricsTop, compact = true)
        drawCoverProjectInfo(page, data, metricsTop + 128f, 190f)

        if (page.showCompanyLogo && data.company.logoPath.isValidImageFile()) {
            canvas.drawContainImage(
                data.company.logoPath,
                RectF(page.contentRight - 92f, 684f, page.contentRight, 742f),
                Paint(Paint.ANTI_ALIAS_FLAG)
            )
        }
        canvas.drawSingleLineEllipsized(
            data.company.name.ifBlank { "SiteReport" },
            page.contentLeft,
            724f,
            page.contentWidth - 112f,
            titlePaint(page, 11f)
        )
        canvas.drawText("${data.labels.generatedAt} ${data.generatedAtText}", page.contentLeft, 748f, mutedPaint(page))
        page.setY(770f)
    }

    private fun renderEngineeringCover(page: NativePdfPageManager, data: NativeReportData) {
        page.startNewPage(showHeader = false, showFooter = false)
        val theme = page.theme
        val canvas = page.canvas
        val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        val primaryPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = theme.primaryColor
            style = Paint.Style.FILL
        }

        canvas.drawLine(page.contentLeft, 48f, page.contentRight, 48f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = theme.primaryColor
            strokeWidth = 2.5f
        })
        canvas.drawSingleLineEllipsized(
            data.company.name.ifBlank { data.labels.siteInspectionReport },
            page.contentLeft,
            78f,
            page.contentWidth - 146f,
            titlePaint(page, 11f)
        )
        canvas.drawSingleLineEllipsized(
            data.reportId,
            page.contentRight - 126f,
            78f,
            126f,
            mutedPaint(page)
        )

        canvas.drawText(data.labels.siteInspectionReport, page.contentLeft, 118f, mutedPaint(page))
        val titleEndY = canvas.drawTextLines(
            data.title,
            page.contentLeft,
            158f,
            page.contentWidth - 205f,
            titlePaint(page, 29f),
            34f
        )
        drawCoverConclusionBadge(
            page,
            data.summary.conclusionText,
            data.summary.conclusionStatus,
            page.contentRight - 190f,
            142f
        )

        val projectInfoTop = maxOf(230f, titleEndY + 20f).coerceAtMost(258f)
        canvas.drawSingleLineEllipsized(data.projectName, page.contentLeft, projectInfoTop, page.contentWidth - 104f, bodyPaint(page))
        canvas.drawSingleLineEllipsized(data.location.ifBlank { "-" }, page.contentLeft, projectInfoTop + 23f, page.contentWidth - 104f, mutedPaint(page))
        canvas.drawText(data.createdAtText, page.contentLeft, projectInfoTop + 46f, mutedPaint(page))
        if (page.showCompanyLogo && data.company.logoPath.isValidImageFile()) {
            canvas.drawContainImage(
                data.company.logoPath,
                RectF(page.contentRight - 86f, projectInfoTop - 8f, page.contentRight, projectInfoTop + 48f),
                imagePaint
            )
        }

        val coverTop = maxOf(318f, projectInfoTop + 74f)
        val coverRect = RectF(page.contentLeft, coverTop, page.contentRight, coverTop + 216f)
        if (page.showCoverImage && data.coverImagePath.isValidImageFile()) {
            canvas.drawCoverImage(data.coverImagePath, coverRect, 14f, imagePaint)
        } else {
            drawCoverArtwork(page, coverRect, data.labels.siteInspectionReport, CoverArtworkStyle.ENGINEERING)
        }

        drawCoverMetrics(page, data, coverRect.bottom + 24f, compact = true)
        val footerPanel = RectF(page.contentLeft, 696f, page.contentRight, 756f)
        canvas.drawRoundRect(footerPanel, 12f, 12f, primaryPaint)
        canvas.drawText(
            "${data.labels.generatedAt} ${data.generatedAtText}",
            footerPanel.left + 18f,
            footerPanel.top + 36f,
            Paint(whitePaint(page)).apply { textSize = 9.5f }
        )
        val footerMeta = "${data.labels.totalItems} ${data.summary.totalCount}   ${data.labels.issues} ${data.summary.issueCount}   ${data.labels.photoEvidence} ${data.summary.photoCount}"
        canvas.drawSingleLineEllipsized(
            footerMeta,
            footerPanel.left + 220f,
            footerPanel.top + 36f,
            footerPanel.width() - 238f,
            Paint(whitePaint(page)).apply { textSize = 9.5f }
        )
        page.setY(770f)
    }

    private fun renderAcceptanceCover(page: NativePdfPageManager, data: NativeReportData) {
        page.startNewPage(showHeader = false, showFooter = false)
        val canvas = page.canvas
        val theme = page.theme
        canvas.drawColor(theme.fillColor)
        canvas.drawText(data.labels.acceptanceRectification, page.contentLeft, 72f, mutedPaint(page))
        val titleEndY = canvas.drawTextLines(data.title, page.contentLeft, 118f, page.contentWidth, titlePaint(page, 29f), 34f)
        val reportY = maxOf(204f, titleEndY + 16f).coerceAtMost(224f)
        canvas.drawText(data.reportId, page.contentLeft, reportY, bodyPaint(page))

        val decisionTop = maxOf(242f, reportY + 34f).coerceAtMost(260f)
        val decisionRect = RectF(page.contentLeft, decisionTop, page.contentRight, decisionTop + 118f)
        canvas.drawRoundRect(decisionRect, 14f, 14f, Paint().apply {
            color = Color.WHITE
        })
        canvas.drawText(data.labels.conclusion, decisionRect.left + 20f, decisionRect.top + 30f, mutedPaint(page))
        canvas.drawText(
            data.summary.conclusionText.ifBlank { data.labels.pendingConfirmation },
            decisionRect.left + 20f,
            decisionRect.top + 78f,
            Paint(titlePaint(page, 26f)).apply {
                color = statusColorFor(page, data.summary.conclusionStatus)
            }
        )
        val metricsTop = decisionRect.bottom + 30f
        drawCoverMetrics(page, data, metricsTop, compact = true)

        val evidenceTop = metricsTop + 116f
        val evidenceRect = RectF(page.contentLeft, evidenceTop, page.contentRight, evidenceTop + 202f)
        if (page.showCoverImage && data.coverImagePath.isValidImageFile()) {
            canvas.drawCoverImage(data.coverImagePath, evidenceRect, 12f, Paint(Paint.ANTI_ALIAS_FLAG))
        } else {
            drawCoverArtwork(page, evidenceRect, data.labels.conclusion, CoverArtworkStyle.ACCEPTANCE)
        }
        canvas.drawSingleLineEllipsized(
            "${data.labels.issues} ${data.summary.issueCount}  |  ${data.labels.pending} ${data.summary.pendingCount}  |  ${data.labels.signatures} ${data.signatures.size}",
            page.contentLeft,
            evidenceRect.bottom + 40f,
            page.contentWidth,
            titlePaint(page, 10f)
        )
        page.setY(770f)
    }

    private fun renderClientCover(page: NativePdfPageManager, data: NativeReportData) {
        page.startNewPage(showHeader = false, showFooter = false)
        val canvas = page.canvas
        val theme = page.theme
        val primary = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = theme.primaryColor }
        canvas.drawRect(0f, 0f, page.pageWidth.toFloat(), 248f, primary)
        canvas.drawSingleLineEllipsized(
            data.company.name.ifBlank { "SiteReport" },
            page.contentLeft,
            62f,
            page.contentWidth - 110f,
            whitePaint(page)
        )
        val titleEndY = canvas.drawTextLines(
            data.title,
            page.contentLeft,
            118f,
            page.contentWidth - 120f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = 28f
                typeface = theme.titleTypeface
            },
            34f
        )
        if (page.showCompanyLogo && data.company.logoPath.isValidImageFile()) {
            canvas.drawContainImage(
                data.company.logoPath,
                RectF(page.contentRight - 92f, 48f, page.contentRight, 122f),
                Paint(Paint.ANTI_ALIAS_FLAG)
            )
        }

        val imageTop = maxOf(282f, titleEndY + 34f).coerceAtMost(320f)
        val imageHeight = if (imageTop > 282f) 216f else 240f
        val imageRect = RectF(page.contentLeft, imageTop, page.contentRight, imageTop + imageHeight)
        if (page.showCoverImage && data.coverImagePath.isValidImageFile()) {
            canvas.drawCoverImage(data.coverImagePath, imageRect, 14f, Paint(Paint.ANTI_ALIAS_FLAG))
        } else {
            drawCoverArtwork(page, imageRect, data.labels.siteReport, CoverArtworkStyle.CLIENT)
        }
        val summaryTop = imageRect.bottom + 24f
        val summaryRect = RectF(page.contentLeft, summaryTop, page.contentRight, summaryTop + 158f)
        canvas.drawRoundRect(summaryRect, 14f, 14f, Paint().apply { color = theme.fillColor })
        canvas.drawText(data.labels.deliveryConclusion, summaryRect.left + 20f, summaryRect.top + 30f, mutedPaint(page))
        canvas.drawText(
            data.summary.conclusionText.ifBlank { data.labels.pendingConfirmation },
            summaryRect.left + 20f,
            summaryRect.top + 70f,
            titlePaint(page, 24f)
        )
        canvas.drawSingleLineEllipsized(
            "${data.labels.progress} ${data.summary.progressText}  |  ${data.labels.issues} ${data.summary.issueCount}  |  ${data.labels.photoEvidence} ${data.summary.photoCount}",
            summaryRect.left + 20f,
            summaryRect.top + 112f,
            summaryRect.width() - 40f,
            bodyPaint(page)
        )
        canvas.drawText(data.reportId, page.contentLeft, 754f, mutedPaint(page))
        canvas.drawText(
            data.generatedAtText,
            page.contentRight - mutedPaint(page).measureText(data.generatedAtText),
            754f,
            mutedPaint(page)
        )
        page.setY(770f)
    }

    private fun renderPremiumCover(page: NativePdfPageManager, data: NativeReportData) {
        page.startNewPage(showHeader = false, showFooter = false)
        val canvas = page.canvas
        val theme = page.theme
        val primary = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = theme.primaryColor
            style = Paint.Style.FILL
        }
        val accent = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = theme.accentColor
            style = Paint.Style.FILL
        }
        canvas.drawColor(Color.rgb(250, 250, 252))
        canvas.drawRect(0f, 0f, page.pageWidth.toFloat(), 314f, primary)
        canvas.drawCircle(page.pageWidth - 24f, 36f, 146f, Paint(accent).apply { alpha = 58 })
        canvas.drawCircle(page.contentLeft + 32f, 286f, 96f, Paint(accent).apply { alpha = 30 })
        canvas.drawRoundRect(
            RectF(page.contentLeft, 58f, page.contentLeft + 128f, 86f),
            14f,
            14f,
            Paint(accent)
        )
        canvas.drawText(data.labels.premiumReport, page.contentLeft + 16f, 77f, Paint(whitePaint(page)).apply {
            textSize = 9.5f
            typeface = theme.titleTypeface
        })
        canvas.drawSingleLineEllipsized(
            data.company.name.ifBlank { "SiteReport" },
            page.contentLeft,
            122f,
            page.contentWidth - 112f,
            Paint(whitePaint(page)).apply { alpha = 218 }
        )
        if (page.showCompanyLogo && data.company.logoPath.isValidImageFile()) {
            canvas.drawContainImage(
                data.company.logoPath,
                RectF(page.contentRight - 90f, 60f, page.contentRight, 126f),
                Paint(Paint.ANTI_ALIAS_FLAG)
            )
        }
        val titleBottom = canvas.drawTextLines(
            data.title,
            page.contentLeft,
            174f,
            page.contentWidth - 46f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = 30f
                typeface = theme.titleTypeface
            },
            36f
        )
        canvas.drawSingleLineEllipsized(
            data.reportId,
            page.contentLeft,
            maxOf(258f, titleBottom + 18f),
            page.contentWidth,
            Paint(whitePaint(page)).apply {
                alpha = 210
                textSize = 11f
            }
        )

        val heroTop = 348f
        val heroRect = RectF(page.contentLeft, heroTop, page.contentRight, heroTop + 178f)
        if (page.showCoverImage && data.coverImagePath.isValidImageFile()) {
            canvas.drawCoverImage(data.coverImagePath, heroRect, 18f, Paint(Paint.ANTI_ALIAS_FLAG))
        } else {
            drawCoverArtwork(page, heroRect, data.labels.siteInspectionReport, CoverArtworkStyle.CLIENT)
        }

        val decisionRect = RectF(page.contentLeft, heroRect.bottom + 22f, page.contentRight, heroRect.bottom + 132f)
        canvas.drawRoundRect(decisionRect, 16f, 16f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        })
        canvas.drawRoundRect(
            RectF(decisionRect.left, decisionRect.top, decisionRect.left + 8f, decisionRect.bottom),
            8f,
            8f,
            accent
        )
        canvas.drawText(data.labels.deliveryConclusion, decisionRect.left + 22f, decisionRect.top + 30f, mutedPaint(page))
        canvas.drawTextLines(
            data.summary.conclusionText.ifBlank { data.labels.pendingConfirmation },
            decisionRect.left + 22f,
            decisionRect.top + 72f,
            decisionRect.width() - 44f,
            titlePaint(page, 22f),
            26f
        )

        val metricsTop = decisionRect.bottom + 24f
        drawCoverMetrics(page, data, metricsTop, compact = true)
        canvas.drawSingleLineEllipsized(
            "${data.labels.generatedAt} ${data.generatedAtText}",
            page.contentLeft,
            764f,
            page.contentWidth,
            mutedPaint(page)
        )
        page.setY(770f)
    }
}

private enum class CoverArtworkStyle {
    ENGINEERING,
    ACCEPTANCE,
    CLIENT
}

private fun drawCoverArtwork(
    page: NativePdfPageManager,
    rect: RectF,
    label: String,
    artworkStyle: CoverArtworkStyle
) {
    val canvas = page.canvas
    canvas.drawRoundRect(rect, 12f, 12f, Paint().apply {
        color = page.theme.fillColor
    })

    when (artworkStyle) {
        CoverArtworkStyle.ENGINEERING -> {
            val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = page.theme.primaryColor
                alpha = 24
                strokeWidth = 0.8f
            }
            var x = rect.left + 24f
            while (x < rect.right) {
                canvas.drawLine(x, rect.top, x, rect.bottom, gridPaint)
                x += 28f
            }
            var y = rect.top + 24f
            while (y < rect.bottom) {
                canvas.drawLine(rect.left, y, rect.right, y, gridPaint)
                y += 28f
            }
            val structurePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = page.theme.primaryColor
                alpha = 150
                style = Paint.Style.STROKE
                strokeWidth = 2f
            }
            val structure = RectF(rect.left + 54f, rect.top + 52f, rect.right - 54f, rect.bottom - 58f)
            canvas.drawRect(structure, structurePaint)
            canvas.drawLine(structure.left, structure.centerY(), structure.right, structure.centerY(), structurePaint)
            canvas.drawLine(structure.centerX(), structure.top, structure.centerX(), structure.bottom, structurePaint)
            canvas.drawText(label, rect.left + 24f, rect.bottom - 22f, mutedPaint(page))
        }

        CoverArtworkStyle.ACCEPTANCE -> {
            val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = page.theme.primaryColor
                style = Paint.Style.STROKE
                strokeWidth = 5f
            }
            val centerX = rect.left + 82f
            val centerY = rect.centerY()
            canvas.drawCircle(centerX, centerY, 44f, circlePaint)
            canvas.drawLine(centerX - 22f, centerY, centerX - 5f, centerY + 18f, circlePaint)
            canvas.drawLine(centerX - 5f, centerY + 18f, centerX + 26f, centerY - 22f, circlePaint)
            canvas.drawRect(rect.right - 18f, rect.top, rect.right, rect.bottom, Paint().apply {
                color = page.theme.accentColor
                alpha = 150
            })
            canvas.drawText(label, rect.left + 150f, centerY + 6f, titlePaint(page, 17f))
        }

        CoverArtworkStyle.CLIENT -> {
            canvas.drawCircle(rect.right - 38f, rect.top + 26f, 118f, Paint().apply {
                color = page.theme.primaryColor
                alpha = 30
            })
            canvas.drawCircle(rect.left + 34f, rect.bottom + 12f, 92f, Paint().apply {
                color = page.theme.accentColor
                alpha = 30
            })
            val bandTop = rect.centerY() - 34f
            canvas.drawRoundRect(
                RectF(rect.left + 28f, bandTop, rect.right - 28f, bandTop + 68f),
                10f,
                10f,
                Paint().apply {
                    color = Color.WHITE
                    alpha = 210
                }
            )
            canvas.drawText(label, rect.left + 48f, bandTop + 42f, titlePaint(page, 17f))
        }
    }
}

internal fun String?.isValidImageFile(): Boolean =
    !this.isNullOrBlank() && File(this).isFile
