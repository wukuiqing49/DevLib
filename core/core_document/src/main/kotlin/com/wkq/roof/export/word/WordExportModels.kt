package com.wkq.roof.export.word

/** Word 段落颜色与字号配置，保持旧调用默认值。 */
data class WordReportTheme(
    val primaryColor: String = "0F5CC0",
    val accentColor: String = "14B8A6",
    val titleColor: String = "111827",
    val bodyColor: String = "1F2937",
    val mutedColor: String = "64748B",
    val tableHeaderFill: String = "E8F0FC",
    val bodySize: Int = 11,
    val titleSize: Int = 20
) {
    init {
        require(bodySize in 8..18 && titleSize in 16..32)
        require(listOf(primaryColor, accentColor, titleColor, bodyColor, mutedColor, tableHeaderFill).all { it.matches(Regex("[0-9A-Fa-f]{6}")) })
    }
}

enum class WordPageOrientation { PORTRAIT, LANDSCAPE }
