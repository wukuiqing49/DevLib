package com.wkq.util

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class FormatUtilTest {

    @Test
    fun formatFileSize_handlesCommonUnits() {
        assertEquals("0 B", FormatUtil.formatFileSize(-1))
        assertEquals("512 B", FormatUtil.formatFileSize(512))
        assertEquals("1 KB", FormatUtil.formatFileSize(1024))
        assertEquals("1.5 KB", FormatUtil.formatFileSize(1536))
        assertEquals("1 MB", FormatUtil.formatFileSize(1024 * 1024))
    }

    @Test
    fun formatDecimal_roundsHalfUpAndTrimsTrailingZeros() {
        assertEquals("1.24", FormatUtil.formatDecimal(1.235, 2))
        assertEquals("1", FormatUtil.formatDecimal(1.2, 0))
        assertEquals("1.2", FormatUtil.formatDecimal(1.2, 2))
    }

    @Test
    fun formatPercent_usesLocaleAndFractionLimit() {
        assertEquals("12.35%", FormatUtil.formatPercent(0.12345, 2, Locale.US))
        assertEquals("12%", FormatUtil.formatPercent(0.12345, 0, Locale.US))
    }

    @Test
    fun maskPhone_keepsShortInputUnchanged() {
        assertEquals("138****8000", FormatUtil.maskPhone("13800138000"))
        assertEquals("1234567", FormatUtil.maskPhone("1234567"))
    }

    @Test
    fun emptyHelpers_returnExpectedFallbacks() {
        assertEquals("", FormatUtil.nullToEmpty(null))
        assertEquals("--", FormatUtil.emptyToDefault(""))
        assertEquals("value", FormatUtil.emptyToDefault("value"))
    }

    @Test
    fun formatDuration_outputsMinuteOrHourText() {
        assertEquals("01:05", FormatUtil.formatDuration(65_000))
        assertEquals("01:01:01", FormatUtil.formatDuration(3_661_000))
        assertEquals("00:01:05", FormatUtil.formatDuration(65_000, alwaysShowHour = true))
    }

    @Test
    fun maskEmailAndDigitHelpers_cleanInput() {
        assertEquals("a****@example.com", FormatUtil.maskEmail("alice@example.com"))
        assertEquals("13800138000", FormatUtil.keepDigits("138-0013-8000"))
        assertEquals("+1.23", FormatUtil.formatSigned(1.234, 2))
        assertEquals("-1.23", FormatUtil.formatSigned(-1.234, 2))
    }
}
