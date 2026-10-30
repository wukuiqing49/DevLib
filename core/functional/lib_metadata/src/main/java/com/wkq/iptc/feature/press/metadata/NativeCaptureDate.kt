package com.wkq.iptc.feature.press.metadata

import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 严格解析相机时间，拒绝溢出日期、尾随文本及缺失分量。 */
internal object NativeCaptureDate {
    private fun formatter() = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).apply {
        isLenient = false
    }

    fun parse(value: String): Date? {
        if (!value.matches(Regex("[0-9]{4}:[0-9]{2}:[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2}"))) return null
        val position = ParsePosition(0)
        return formatter().parse(value, position)?.takeIf { position.index == value.length }
    }

    fun preserveOrNow(value: String, now: Date = Date()): String =
        if (parse(value) != null) value else formatter().format(now)
}
