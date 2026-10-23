package com.wkq.photo.idlibrary.i18n

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocaleFallbackTest {
    @Test
    fun `简体中文地区会回退到 zh-Hans`() {
        assertEquals(
            listOf("zh-CN", "zh-Hans", "zh", "en"),
            LocaleFallback.candidateTags(Locale.forLanguageTag("zh-CN")),
        )
    }

    @Test
    fun `繁体中文地区会回退到 zh-Hant`() {
        assertEquals(
            listOf("zh-HK", "zh-Hant", "zh", "en"),
            LocaleFallback.candidateTags(Locale.forLanguageTag("zh-HK")),
        )
    }

    @Test
    fun `优先使用 script 翻译而不是英文`() {
        val result = LocaleFallback.resolve(
            values = listOf(
                LocalizedValue("Passport", "en", "human_verified"),
                LocalizedValue("护照", "zh-Hans", "human_verified"),
            ),
            locale = Locale.forLanguageTag("zh-SG"),
            stableFallback = "PASSPORT",
        )

        assertEquals("护照", result.value)
        assertEquals("zh-Hans", result.localeTag)
    }

    @Test
    fun `没有任何翻译时使用稳定代码`() {
        val result = LocaleFallback.resolve(
            values = emptyList(),
            locale = Locale.JAPANESE,
            stableFallback = "JP_PASSPORT",
        )

        assertEquals("JP_PASSPORT", result.value)
        assertNull(result.localeTag)
        assertNull(result.translationStatus)
    }

    @Test
    fun `只接受 BCP-47 风格标签`() {
        assertTrue(LocaleFallback.isValidLanguageTag("zh-Hans"))
        assertTrue(LocaleFallback.isValidLanguageTag("pt-BR"))
        assertFalse(LocaleFallback.isValidLanguageTag("zh_CN"))
        assertFalse(LocaleFallback.isValidLanguageTag("EN"))
        assertFalse(LocaleFallback.isValidLanguageTag(""))
    }
}
