package com.wkq.nativepdf.section

import android.graphics.Paint
import android.graphics.RectF
import com.wkq.nativepdf.model.NativeReportData
import com.wkq.nativepdf.render.NativePdfPageManager
import com.wkq.nativepdf.render.drawContainImage
import com.wkq.nativepdf.render.drawImagePlaceholder
import com.wkq.nativepdf.render.drawSingleLineEllipsized
import com.wkq.nativepdf.render.drawTextLines
import com.wkq.nativepdf.render.imageAspectRatio
import com.wkq.nativepdf.render.measureTextHeight

internal fun renderHighlightEvidence(page: NativePdfPageManager, data: NativeReportData) {
    val first = data.photos.firstOrNull()
    if (first != null) {
        val caption = first.compactPhotoCaption()
        val captionPaint = mutedPaint(page)
        val imageWidth = page.contentWidth - 20f
        val imageHeight = adaptiveEvidenceHeight(first.filePath, imageWidth, 300f, 500f)
        val captionTopOffset = imageHeight + 28f
        val captionHeight = caption.fixedCaptionHeight()
        val rectHeight = captionTopOffset + captionHeight + 18f
        page.ensureSpace(rectHeight)
        val rect = RectF(page.contentLeft, page.y, page.contentRight, page.y + rectHeight)
        drawCard(page, rect)
        val imageRect = RectF(rect.left + 10f, rect.top + 10f, rect.right - 10f, rect.top + 10f + imageHeight)
        val drawn = page.canvas.drawContainImage(
            first.filePath,
            imageRect,
            Paint(Paint.ANTI_ALIAS_FLAG)
        )
        if (!drawn) {
            page.canvas.drawImagePlaceholder(imageRect, first.evidenceTypeText.ifBlank { page.labels.photoEvidence })
        }
        drawPhotoCaption(page, caption, rect.left + 12f, rect.top + captionTopOffset, rect.width() - 24f, captionPaint)
        page.setY(rect.bottom + 12f)
    }
    data.photos.drop(1).forEach { photo ->
        renderSingleEvidenceCard(page, photo)
    }
}

internal fun renderBeforeAfterEvidence(page: NativePdfPageManager, data: NativeReportData) {
    val grouped = data.photos.groupBy { it.itemTitle.ifBlank { data.labels.sitePhoto } }
    val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    grouped.forEach { (title, photos) ->
        val beforePhotos = photos.filter { it.isBeforeEvidence() }
        val afterPhotos = photos.filter { it.isAfterEvidence() }
        val general = photos.filterNot { it.isBeforeEvidence() || it.isAfterEvidence() }
        if (beforePhotos.isEmpty() && afterPhotos.isEmpty()) {
            general.forEachIndexed { index, photo ->
                renderSingleEvidenceCard(page, photo, evidenceCardTitle(title, index, general.size))
            }
            return@forEach
        }
        val pairCount = minOf(beforePhotos.size, afterPhotos.size)
        repeat(pairCount) { pairIndex ->
            val before = beforePhotos[pairIndex]
            val after = afterPhotos[pairIndex]
            val pairTitle = if (pairCount > 1) "$title (${pairIndex + 1}/$pairCount)" else title
            val titleHeight = pairTitle.measureTextHeight(page.contentWidth - 28f, titlePaint(page, 11f), 13f)
            val imageTopOffset = 28f + titleHeight
            val labelHeight = PHOTO_CAPTION_HEIGHT + 20f
            val gap = 12f
            val imageWidth = (page.contentWidth - 40f - gap) / 2f
            val beforeHeight = adaptiveEvidenceHeight(before.filePath, imageWidth, 210f, 330f)
            val afterHeight = adaptiveEvidenceHeight(after.filePath, imageWidth, 210f, 330f)
            val imageHeight = maxOf(beforeHeight, afterHeight)
            val height = imageTopOffset + imageHeight + labelHeight + 18f
            page.ensureSpace(height)
            val rect = RectF(page.contentLeft, page.y, page.contentRight, page.y + height)
            drawCard(page, rect)
            page.canvas.drawTextLines(pairTitle, rect.left + 14f, rect.top + 22f, page.contentWidth - 28f, titlePaint(page, 11f), 13f)
            val imageTop = rect.top + imageTopOffset
            val beforeRect = RectF(rect.left + 14f, imageTop, rect.left + 14f + imageWidth, imageTop + imageHeight)
            val afterRect = RectF(beforeRect.right + gap, imageTop, beforeRect.right + gap + imageWidth, imageTop + imageHeight)
            val beforeDrawn = page.canvas.drawContainImage(before.filePath, beforeRect, imagePaint)
            val afterDrawn = page.canvas.drawContainImage(after.filePath, afterRect, imagePaint)
            if (!beforeDrawn) {
                page.canvas.drawImagePlaceholder(beforeRect, data.labels.beforeCorrection)
            }
            if (!afterDrawn) {
                page.canvas.drawImagePlaceholder(afterRect, data.labels.afterCorrection)
            }
            drawSmallLabel(page, before.beforeAfterCaption(data.labels.beforeCorrection), beforeRect.left, beforeRect.bottom + 18f, "")
            drawSmallLabel(page, after.beforeAfterCaption(data.labels.afterCorrection), afterRect.left, afterRect.bottom + 18f, "")
            page.setY(rect.bottom + 12f)
        }
        beforePhotos.drop(pairCount).forEachIndexed { index, photo ->
            renderSingleEvidenceCard(page, photo, evidenceCardTitle(title, index, beforePhotos.size - pairCount))
        }
        afterPhotos.drop(pairCount).forEachIndexed { index, photo ->
            renderSingleEvidenceCard(page, photo, evidenceCardTitle(title, index, afterPhotos.size - pairCount))
        }
        general.forEachIndexed { index, photo ->
            renderSingleEvidenceCard(page, photo, evidenceCardTitle(title, index, general.size))
        }
    }
}

internal fun renderSingleEvidenceCard(
    page: NativePdfPageManager,
    photo: com.wkq.nativepdf.model.NativeReportPhoto,
    title: String = photo.itemTitle
) {
    val caption = photo.compactPhotoCaption()
    val width = page.contentWidth
    val captionHeight = caption.fixedCaptionHeight()
    val titlePaint = titlePaint(page, 12f)
    val titleHeight = title
        .measureTextHeight(width - 24f, titlePaint, 14f)
        .coerceAtLeast(14f)
    val imageTopOffset = 30f + titleHeight
    val imageWidth = width - 16f
    val imageHeight = singleEvidenceImageHeight(photo, imageWidth)
    val captionTopOffset = imageTopOffset + imageHeight + 18f
    val height = captionTopOffset + captionHeight + 16f
    page.ensureSpace(height)
    val rect = RectF(page.contentLeft, page.y, page.contentRight, page.y + height)
    drawCard(page, rect)
    page.canvas.drawTextLines(title, rect.left + 12f, rect.top + 22f, width - 24f, titlePaint, 14f)
    val imageRect = RectF(
        rect.left + 8f,
        rect.top + imageTopOffset,
        rect.right - 8f,
        rect.top + imageTopOffset + imageHeight
    )
    val drawn = page.canvas.drawContainImage(
        photo.filePath,
        imageRect,
        Paint(Paint.ANTI_ALIAS_FLAG)
    )
    if (!drawn) {
        page.canvas.drawImagePlaceholder(imageRect, photo.evidenceTypeText.ifBlank { page.labels.photoEvidence })
    }
    drawPhotoCaption(page, caption, rect.left + 8f, rect.top + captionTopOffset, width - 16f, mutedPaint(page))
    page.setY(rect.bottom + 12f)
}

internal fun drawPhotoGroupHeader(
    page: NativePdfPageManager,
    title: String,
    count: Int,
    minFollowingHeight: Float = 150f
) {
    val height = 32f
    page.ensureSpace(height + minFollowingHeight)
    val top = page.y
    page.canvas.drawTextLines(title, page.contentLeft, top + 18f, page.contentWidth - 86f, titlePaint(page, 11f), 13f)
    val countText = count.toString()
    page.canvas.drawText(
        countText,
        page.contentRight - mutedPaint(page).measureText(countText),
        top + 18f,
        mutedPaint(page)
    )
    page.canvas.drawLine(page.contentLeft, top + 27f, page.contentRight, top + 27f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = page.theme.lineColor
        strokeWidth = 1f
    })
    page.moveY(height)
}

internal fun adaptiveEvidenceHeight(
    filePath: String?,
    imageWidth: Float,
    minHeight: Float,
    maxHeight: Float
): Float {
    val aspectRatio = imageAspectRatio(filePath)?.takeIf { it > 0f } ?: (4f / 3f)
    return (imageWidth / aspectRatio).coerceIn(minHeight, maxHeight)
}

internal fun singleEvidenceImageHeight(
    photo: com.wkq.nativepdf.model.NativeReportPhoto,
    imageWidth: Float
): Float {
    return adaptiveEvidenceHeight(photo.filePath, imageWidth, 340f, 560f)
}

internal fun singleEvidenceCardHeight(photo: com.wkq.nativepdf.model.NativeReportPhoto): Float {
    val imageWidth = NativePdfPageWidth.contentWidth - 16f
    val imageHeight = singleEvidenceImageHeight(photo, imageWidth)
    return 44f + imageHeight + 18f + photo.compactPhotoCaption().fixedCaptionHeight() + 16f
}

internal fun drawPhotoCaption(
    page: NativePdfPageManager,
    caption: String,
    x: Float,
    y: Float,
    maxWidth: Float,
    paint: Paint
) {
    if (caption.isBlank()) return
    page.canvas.drawSingleLineEllipsized(caption, x, y + 11f, maxWidth, Paint(paint).apply {
        textSize = PHOTO_CAPTION_TEXT_SIZE
    })
}

internal fun drawSmallLabel(page: NativePdfPageManager, label: String, x: Float, y: Float, meta: String) {
    page.canvas.drawSingleLineEllipsized(label, x, y, (page.contentWidth - 40f - 12f) / 2f, titlePaint(page, 9f))
    if (meta.isNotBlank()) {
        page.canvas.drawText(meta, x, y + 14f, mutedPaint(page))
    }
}

internal object NativePdfPageWidth {
    const val contentWidth: Float = 595f - 42f * 2f
}

internal const val PHOTO_CAPTION_HEIGHT = 18f
internal const val PHOTO_CAPTION_TEXT_SIZE = 8.5f
