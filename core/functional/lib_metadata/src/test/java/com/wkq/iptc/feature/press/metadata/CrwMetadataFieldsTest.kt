package com.wkq.iptc.feature.press.metadata

import org.junit.Assert.*
import org.junit.Test

class CrwMetadataFieldsTest {
    @Test fun onlyCiffZeroTimestampIsAnonymous() {
        assertTrue(CrwMetadataFields.isEmptyCaptureDate("CanonRaw:DateTimeOriginal", "0000:00:00 00:00:00"))
        assertTrue(CrwMetadataFields.isEmptyCaptureDate("CanonRaw:Doc1:DateTimeOriginal", "0000:00:00 00:00:00"))
        assertFalse(CrwMetadataFields.isEmptyCaptureDate("ExifIFD:DateTimeOriginal", "0000:00:00 00:00:00"))
        assertFalse(CrwMetadataFields.isEmptyCaptureDate("CanonRaw:DateTimeOriginal", "1970:01:01 00:00:00"))
        assertFalse(CrwMetadataFields.isEmptyCaptureDate("CanonRaw:DateTimeOriginal", "2003:10:08 06:22:52"))
        assertFalse(CrwMetadataFields.isEmptyCaptureDate("CanonRaw:OwnerName", "0000:00:00 00:00:00"))
    }
}
