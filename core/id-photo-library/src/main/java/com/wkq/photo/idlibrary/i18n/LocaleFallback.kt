package com.wkq.photo.idlibrary.i18n

import java.util.Locale

data class LocalizedValue(
    val value: String,
    val localeTag: String?,
    val translationStatus: String?,
)

object LocaleFallback {
    private val simplifiedChineseRegions = setOf("CN", "SG")
    private val traditionalChineseRegions = setOf("TW", "HK", "MO")

    fun candidateTags(locale: Locale): List<String> {
        val language = locale.language.lowercase(Locale.ROOT)
        if (language.isBlank() || language == "und") return listOf(FALLBACK_LANGUAGE)

        val exact = locale.toLanguageTag().takeUnless { it.equals("und", ignoreCase = true) }
        val script = locale.script.ifBlank {
            when {
                language != "zh" -> ""
                locale.country.uppercase(Locale.ROOT) in simplifiedChineseRegions -> "Hans"
                locale.country.uppercase(Locale.ROOT) in traditionalChineseRegions -> "Hant"
                else -> ""
            }
        }

        return buildList {
            exact?.let(::add)
            if (script.isNotBlank()) add("$language-${canonicalScript(script)}")
            add(language)
            add(FALLBACK_LANGUAGE)
        }.distinctBy { it.lowercase(Locale.ROOT) }
    }

    fun resolve(
        values: Collection<LocalizedValue>,
        locale: Locale,
        stableFallback: String,
    ): LocalizedValue {
        val byTag = values.associateBy { it.localeTag.orEmpty().lowercase(Locale.ROOT) }
        candidateTags(locale).forEach { candidate ->
            byTag[candidate.lowercase(Locale.ROOT)]?.let { return it }
        }
        return LocalizedValue(stableFallback, null, null)
    }

    fun isValidLanguageTag(value: String): Boolean {
        if (value.isBlank() || value.contains('_')) return false
        val locale = Locale.forLanguageTag(value)
        return locale.language.isNotBlank() &&
            locale.language != "und" &&
            locale.toLanguageTag() == value
    }

    private fun canonicalScript(value: String): String =
        value.lowercase(Locale.ROOT).replaceFirstChar { it.uppercase(Locale.ROOT) }

    private const val FALLBACK_LANGUAGE = "en"
}
