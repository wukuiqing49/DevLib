package com.wkq.util

import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 日期时间格式化、解析和常见计算工具。
 *
 * 使用 java.text/java.util 实现，适合 Android 低版本兼容场景。
 */
object DateUtil {

    const val PATTERN_DATE = "yyyy-MM-dd"
    const val PATTERN_TIME = "HH:mm:ss"
    const val PATTERN_DATE_TIME = "yyyy-MM-dd HH:mm:ss"
    const val PATTERN_DATE_TIME_MILLIS = "yyyy-MM-dd HH:mm:ss.SSS"

    fun now(): Long = System.currentTimeMillis()

    /** 将毫秒时间戳格式化为字符串。 */
    fun format(
        millis: Long = now(),
        pattern: String = PATTERN_DATE_TIME,
        locale: Locale = Locale.getDefault()
    ): String {
        return SimpleDateFormat(pattern, locale).format(Date(millis))
    }

    /** 将 Date 格式化为字符串。 */
    fun format(
        date: Date,
        pattern: String = PATTERN_DATE_TIME,
        locale: Locale = Locale.getDefault()
    ): String {
        return SimpleDateFormat(pattern, locale).format(date)
    }

    /** 按指定格式解析日期，失败返回 null。 */
    fun parse(
        text: String?,
        pattern: String = PATTERN_DATE_TIME,
        locale: Locale = Locale.getDefault()
    ): Date? {
        if (text.isNullOrBlank()) return null
        return try {
            SimpleDateFormat(pattern, locale).parse(text)
        } catch (_: ParseException) {
            null
        }
    }

    /** 按指定格式解析日期并返回毫秒时间戳，失败返回 0。 */
    fun parseMillis(
        text: String?,
        pattern: String = PATTERN_DATE_TIME,
        locale: Locale = Locale.getDefault()
    ): Long {
        return parse(text, pattern, locale)?.time ?: 0L
    }

    /** 获取指定时间所在自然日的开始时间。 */
    fun startOfDay(millis: Long = now()): Long {
        return Calendar.getInstance().apply {
            timeInMillis = millis
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    /** 获取指定时间所在自然日的结束时间。 */
    fun endOfDay(millis: Long = now()): Long {
        return Calendar.getInstance().apply {
            timeInMillis = millis
            set(Calendar.HOUR_OF_DAY, 23)
            set(Calendar.MINUTE, 59)
            set(Calendar.SECOND, 59)
            set(Calendar.MILLISECOND, 999)
        }.timeInMillis
    }

    /** 增减天数。 */
    fun addDays(millis: Long, days: Int): Long {
        return Calendar.getInstance().apply {
            timeInMillis = millis
            add(Calendar.DAY_OF_YEAR, days)
        }.timeInMillis
    }

    /** 增减小时。 */
    fun addHours(millis: Long, hours: Int): Long {
        return Calendar.getInstance().apply {
            timeInMillis = millis
            add(Calendar.HOUR_OF_DAY, hours)
        }.timeInMillis
    }

    /** 增减分钟。 */
    fun addMinutes(millis: Long, minutes: Int): Long {
        return Calendar.getInstance().apply {
            timeInMillis = millis
            add(Calendar.MINUTE, minutes)
        }.timeInMillis
    }

    /** 判断是否是今天。 */
    fun isToday(millis: Long): Boolean {
        return startOfDay(millis) == startOfDay(now())
    }

    /** 判断两个时间是否在同一天。 */
    fun isSameDay(firstMillis: Long, secondMillis: Long): Boolean {
        return startOfDay(firstMillis) == startOfDay(secondMillis)
    }

    /** 计算两个时间相差的自然日数量。 */
    fun diffDays(startMillis: Long, endMillis: Long): Long {
        return (startOfDay(endMillis) - startOfDay(startMillis)) / (24L * 60L * 60L * 1000L)
    }

    /** 生成简短的相对时间文案。 */
    fun friendlyTime(millis: Long, nowMillis: Long = now()): String {
        val diff = (nowMillis - millis).coerceAtLeast(0L)
        val minute = 60L * 1000L
        val hour = 60L * minute
        val day = 24L * hour
        return when {
            diff < minute -> "just now"
            diff < hour -> "${diff / minute} minutes ago"
            diff < day -> "${diff / hour} hours ago"
            diff < 7L * day -> "${diff / day} days ago"
            else -> format(millis, PATTERN_DATE)
        }
    }
}
