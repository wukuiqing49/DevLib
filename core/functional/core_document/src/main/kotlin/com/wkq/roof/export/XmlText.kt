package com.wkq.roof.export

/** 过滤 XML 1.0 非法码点，保留换行、制表符及完整的补充平面字符。 */
internal fun String.validXmlText(): String = buildString {
    var index = 0
    while (index < this@validXmlText.length) {
        val point = this@validXmlText.codePointAt(index)
        if (point == 0x9 || point == 0xA || point == 0xD ||
            point in 0x20..0xD7FF || point in 0xE000..0xFFFD || point in 0x10000..0x10FFFF
        ) appendCodePoint(point)
        index += Character.charCount(point)
    }
}
