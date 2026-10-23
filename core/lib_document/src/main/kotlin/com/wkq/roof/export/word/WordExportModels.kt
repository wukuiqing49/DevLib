package com.wkq.roof.export.word

data class WordReportTheme(
    val primaryColor: String = "0F5CC0",
    val accentColor: String = "14B8A6",
    val titleColor: String = "111827",
    val bodyColor: String = "1F2937",
    val mutedColor: String = "64748B",
    val tableHeaderFill: String = "E8F0FC"
)

enum class WordPageOrientation { PORTRAIT, LANDSCAPE }
