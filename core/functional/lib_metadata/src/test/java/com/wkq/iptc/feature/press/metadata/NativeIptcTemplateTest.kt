package com.wkq.iptc.feature.press.metadata

import com.wkq.dev.metadata.model.MetadataTemplate
import com.wkq.dev.metadata.model.MetadataFieldDefinitions as F
import org.junit.Assert.*
import org.junit.Test

class NativeIptcTemplateTest {
    @Test fun legacyFieldsMapToEnabledXmpFields() {
        val source = MetadataTemplate(id = "native", enabledFields = listOf(F.IIM_OBJECT_NAME, F.IIM_KEYWORDS),
            standardFields = mapOf(F.IIM_OBJECT_NAME to "原名标题", F.IIM_KEYWORDS to "a,b"))
        val result = NativeIptcTemplate.forXmp(source)
        assertEquals("原名标题", result.standardValue(F.DC_TITLE))
        assertEquals("a,b", result.standardValue(F.DC_SUBJECT))
        assertTrue(result.shouldWriteStandardField(F.DC_TITLE))
        assertTrue(result.shouldWriteStandardField(F.DC_SUBJECT))
    }

    @Test fun explicitXmpValueHasPriority() {
        val source = MetadataTemplate(id = "native", enabledFields = listOf(F.IIM_HEADLINE, F.PHOTOSHOP_HEADLINE),
            standardFields = mapOf(F.IIM_HEADLINE to "legacy", F.PHOTOSHOP_HEADLINE to "xmp"))
        assertEquals("xmp", NativeIptcTemplate.forXmp(source).standardValue(F.PHOTOSHOP_HEADLINE))
    }

    @Test fun disabledLegacyFieldIsNotReenabled() {
        val source = MetadataTemplate(id = "native", enabledFields = listOf("caption"),
            standardFields = mapOf(F.IIM_OBJECT_NAME to "disabled"))
        assertFalse(NativeIptcTemplate.forXmp(source).shouldWriteStandardField(F.DC_TITLE))
    }

    @Test(expected = IllegalArgumentException::class)
    fun unsupportedLegacyStructureIsNotSilentlyDropped() {
        NativeIptcTemplate.forXmp(MetadataTemplate(id = "native", enabledFields = listOf(F.IIM_OBJECT_TYPE),
            standardFields = mapOf(F.IIM_OBJECT_TYPE to "unsupported")))
    }

    @Test fun emptyBylineContactDoesNotBlockOtherwiseSupportedXmp() {
        val source = MetadataTemplate(id = "byline", enabledFields = listOf("email", "caption"),
            caption = "News caption", standardFields = mapOf(F.IIM_CONTACT to " "))
        assertEquals("News caption", NativeIptcTemplate.forXmp(source).caption)
    }

    @Test(expected = IllegalArgumentException::class)
    fun unsupportedDocumentStructureIsNotMistakenForEmptyText() {
        val source = MetadataTemplate(id = "document", enabledFields = listOf(F.IIM_OBJECT_TYPE),
            iptcDocument = com.wkq.dev.metadata.model.IptcDocument(fields = mapOf(
                F.IIM_OBJECT_TYPE to com.wkq.dev.metadata.model.IptcValue.struct(mapOf(
                    "value" to com.wkq.dev.metadata.model.IptcValue.text("unsupported")
                ))
            )))
        NativeIptcTemplate.forXmp(source)
    }
}
