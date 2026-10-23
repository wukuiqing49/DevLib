package com.wkq.nativepdf.section

import com.wkq.nativepdf.model.NativeReportEvidenceType
import com.wkq.nativepdf.model.NativeReportPhoto
import org.junit.Assert.*
import org.junit.Test

class NativeEvidencePresentationTest {
    @Test fun captionsOmitGenericPhotoLabelsAndRepeatedItemTitles() {
        val photo = NativeReportPhoto("missing.jpg", itemTitle = "Pump", description = "Pump",
            evidenceTypeText = " Site Photo ", locationText = "Room 1", timeText = "10:30")
        assertEquals("Room 1 · 10:30", photo.compactPhotoCaption())
    }

    @Test fun captionsKeepLongLocalizedEvidenceAndDeduplicateMetadata() {
        val description = "现场整改证据 العربية ".repeat(30)
        val photo = NativeReportPhoto("", description = description,
            evidenceTypeText = "整改后", locationText = "整改后")
        assertEquals("$description · 整改后", photo.compactPhotoCaption())
    }

    @Test fun beforeAfterGroupingUsesEvidenceTypeRatherThanTranslatedLabel() {
        val before = NativeReportPhoto("", evidenceType = NativeReportEvidenceType.BEFORE,
            evidenceTypeText = "Avant correction")
        assertTrue(before.isBeforeEvidence())
        assertFalse(before.isAfterEvidence())
        val after = before.copy(evidenceType = NativeReportEvidenceType.AFTER)
        assertTrue(after.isAfterEvidence())
        assertFalse(after.isBeforeEvidence())
    }

    @Test fun comparisonCaptionsRetainLocationAndTimeWithoutDuplicates() {
        val photo = NativeReportPhoto("", locationText = "Level 2", timeText = "11:00")
        assertEquals("Before · Level 2 · 11:00", photo.beforeAfterCaption("Before"))
        assertEquals("Level 2 · 11:00", photo.beforeAfterCaption("Level 2"))
    }

    @Test fun blankCaptionsDoNotReserveSpaceAndMultiplePhotosKeepTheirSequence() {
        assertEquals(0f, NativeReportPhoto("").compactPhotoCaption().fixedCaptionHeight(), 0f)
        assertEquals(PHOTO_CAPTION_HEIGHT, "Evidence".fixedCaptionHeight(), 0f)
        assertEquals("Pump", evidenceCardTitle("Pump", 0, 1))
        assertEquals("Pump (2/3)", evidenceCardTitle("Pump", 1, 3))
    }
}
