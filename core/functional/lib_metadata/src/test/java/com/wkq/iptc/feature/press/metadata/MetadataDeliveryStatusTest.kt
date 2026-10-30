package com.wkq.iptc.feature.press.metadata

import org.junit.Assert.*
import org.junit.Test

class MetadataDeliveryStatusTest {
    @Test fun onlyStructuredWarningsAllowPartialDelivery() {
        assertNull(MetadataDeliveryStatus.warning(emptyList()))
        for (message in listOf(null, "", "Metadata verification failed", "OPTIONAL_METADATA_SKIPPED:",
            "OPTIONAL_METADATA_SKIPPED:gps\nerror", "OPTIONAL_METADATA_SKIPPED:../private")) {
            assertFalse(MetadataDeliveryStatus.isPartialSuccess(message))
        }
        assertTrue(MetadataDeliveryStatus.isPartialSuccess(MetadataDeliveryStatus.warning(listOf("gps", "caption"))))
    }
    @Test fun warningsNeverIncludeUntrustedNamesAndCanBeMerged() {
        val warning = MetadataDeliveryStatus.warning(listOf("caption", "private path/value"))
        assertFalse(warning!!.contains("private"))
        assertTrue(MetadataDeliveryStatus.isPartialSuccess(warning))
        val merged = MetadataDeliveryStatus.withWarning(warning, "existingXmp")
        assertTrue(merged.contains("caption"))
        assertTrue(merged.contains("existingXmp"))
        assertTrue(MetadataDeliveryStatus.isPartialSuccess(merged))
    }
}
