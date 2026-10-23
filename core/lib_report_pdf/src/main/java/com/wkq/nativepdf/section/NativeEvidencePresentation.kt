package com.wkq.nativepdf.section

import com.wkq.nativepdf.model.NativeReportEvidenceType

internal fun evidenceCardTitle(groupTitle: String, index: Int, count: Int): String {
    return if (count > 1) "$groupTitle (${index + 1}/$count)" else groupTitle
}

internal fun String.fixedCaptionHeight(): Float {
    return if (isBlank()) 0f else PHOTO_CAPTION_HEIGHT
}

internal fun com.wkq.nativepdf.model.NativeReportPhoto.isBeforeEvidence(): Boolean {
    return evidenceType == NativeReportEvidenceType.BEFORE
}

internal fun com.wkq.nativepdf.model.NativeReportPhoto.isAfterEvidence(): Boolean {
    return evidenceType == NativeReportEvidenceType.AFTER
}

internal fun com.wkq.nativepdf.model.NativeReportPhoto.compactPhotoCaption(): String {
    return listOf(
        description.takeIf { it.isNotBlank() && it != itemTitle },
        evidenceTypeText.takeIf { it.isNotGenericSitePhoto() },
        locationText.takeIf { it.isNotBlank() },
        timeText.takeIf { it.isNotBlank() }
    )
        .filterNotNull()
        .distinct()
        .joinToString(" · ")
}

internal fun com.wkq.nativepdf.model.NativeReportPhoto.beforeAfterCaption(label: String): String {
    return listOf(
        label.takeIf { it.isNotBlank() },
        locationText.takeIf { it.isNotBlank() },
        timeText.takeIf { it.isNotBlank() }
    )
        .filterNotNull()
        .distinct()
        .joinToString(" · ")
}

internal fun String?.isNotGenericSitePhoto(): Boolean {
    if (isNullOrBlank()) return false
    val normalized = trim().lowercase()
    return normalized != "site photo" && normalized != "photo"
}
