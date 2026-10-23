package com.wkq.util

import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.absoluteValue

/**
 * 数字、大小、脱敏与展示文案格式化工具。
 */
object FormatUtil {

    /** 格式化文件大小，自动选择 B/KB/MB/GB/TB/PB。 */
    fun formatFileSize(bytes: Long, fractionDigits: Int = 2): String {
        if (bytes < 0) return "0 B"
        if (bytes < 1024) return "$bytes B"

        val units = arrayOf("KB", "MB", "GB", "TB", "PB")
        var value = bytes.toDouble()
        var unitIndex = -1
        while (value >= 1024 && unitIndex < units.lastIndex) {
            value /= 1024
            unitIndex++
        }
        return "${formatDecimal(value, fractionDigits)} ${units[unitIndex]}"
    }

    /** 四舍五入格式化小数，并去掉多余的尾随 0。 */
    fun formatDecimal(value: Double, fractionDigits: Int = 2): String {
        val digits = fractionDigits.coerceAtLeast(0)
        val pattern = if (digits == 0) {
            "0"
        } else {
            "0." + "#".repeat(digits)
        }
        return DecimalFormat(pattern).apply {
            roundingMode = RoundingMode.HALF_UP
        }.format(value)
    }

    /** 使用 Locale 格式化数字分组，例如 12,345。 */
    fun formatNumber(value: Number, locale: Locale = Locale.getDefault()): String {
        return NumberFormat.getNumberInstance(locale).format(value)
    }

    /** 格式化百分比，value=0.12 会显示为 12%。 */
    fun formatPercent(value: Double, fractionDigits: Int = 2, locale: Locale = Locale.getDefault()): String {
        return NumberFormat.getPercentInstance(locale).apply {
            minimumFractionDigits = 0
            maximumFractionDigits = fractionDigits.coerceAtLeast(0)
        }.format(value)
    }

    /** 手机号脱敏，默认保留前三后四。 */
    fun maskPhone(phone: String, start: Int = 3, end: Int = 4): String {
        val text = phone.trim()
        if (text.length <= start + end) return text
        return text.take(start) + "****" + text.takeLast(end)
    }

    /** 邮箱脱敏，保留首字符和域名。 */
    fun maskEmail(email: String): String {
        val text = email.trim()
        val atIndex = text.indexOf('@')
        if (atIndex <= 0) return text
        val name = text.substring(0, atIndex)
        val domain = text.substring(atIndex)
        return name.take(1) + "****" + domain
    }

    /** 格式化时长，输出 HH:mm:ss 或 mm:ss。 */
    fun formatDuration(millis: Long, alwaysShowHour: Boolean = false): String {
        val totalSeconds = (millis / 1000L).coerceAtLeast(0L)
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0 || alwaysShowHour) {
            "%02d:%02d:%02d".format(Locale.US, hours, minutes, seconds)
        } else {
            "%02d:%02d".format(Locale.US, minutes, seconds)
        }
    }

    /** 保留数字字符，常用于手机号、验证码输入清洗。 */
    fun keepDigits(value: String?): String {
        return value.orEmpty().filter(Char::isDigit)
    }

    /** 带符号格式化数字，正数前加 +。 */
    fun formatSigned(value: Number, fractionDigits: Int = 2): String {
        val doubleValue = value.toDouble()
        val prefix = if (doubleValue > 0) "+" else if (doubleValue < 0) "-" else ""
        return prefix + formatDecimal(doubleValue.absoluteValue, fractionDigits)
    }

    fun nullToEmpty(value: String?): String = value.orEmpty()

    fun emptyToDefault(value: String?, defaultValue: String = "--"): String {
        return if (value.isNullOrBlank()) defaultValue else value
    }
}
