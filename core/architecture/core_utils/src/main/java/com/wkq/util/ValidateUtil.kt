package com.wkq.util

import android.util.Patterns

/**
 * 常见输入校验工具。
 *
 * 这里的方法只做格式级校验，不代表邮箱、手机号、身份证等真实存在或已认证。
 */
object ValidateUtil {

    /** 邮箱格式校验。 */
    fun isEmail(value: String?): Boolean {
        return !value.isNullOrBlank() && Patterns.EMAIL_ADDRESS.matcher(value).matches()
    }

    /** URL 格式校验，支持常见 http/https/domain 形式。 */
    fun isUrl(value: String?): Boolean {
        return !value.isNullOrBlank() && Patterns.WEB_URL.matcher(value).matches()
    }

    /** 中国大陆手机号格式校验。 */
    fun isPhone(value: String?): Boolean {
        if (value.isNullOrBlank()) return false
        return Regex("^1[3-9]\\d{9}$").matches(value)
    }

    /** 是否全为数字。 */
    fun isNumeric(value: String?): Boolean {
        return !value.isNullOrBlank() && value.all { it.isDigit() }
    }

    /** 中国居民身份证基础格式校验，不包含完整校验位算法。 */
    fun isIdCard(value: String?): Boolean {
        if (value.isNullOrBlank()) return false
        return Regex("^\\d{15}$|^\\d{17}[0-9Xx]$").matches(value)
    }

    /** IPv4 地址格式校验。 */
    fun isIpv4(value: String?): Boolean {
        if (value.isNullOrBlank()) return false
        val parts = value.split('.')
        return parts.size == 4 && parts.all { part ->
            part.isNotEmpty() && part.length <= 3 && part.all(Char::isDigit) && part.toInt() in 0..255
        }
    }

    /** 是否匹配自定义正则。 */
    fun matches(value: String?, regex: Regex): Boolean {
        return !value.isNullOrBlank() && regex.matches(value)
    }

    /** 字符串长度是否在闭区间内。 */
    fun isLengthBetween(value: String?, min: Int, max: Int): Boolean {
        val length = value?.length ?: return false
        return length in min..max
    }

    /** 简单密码强度：至少包含大小写字母和数字，长度默认 8 位以上。 */
    fun isStrongPassword(value: String?, minLength: Int = 8): Boolean {
        if (value.isNullOrBlank() || value.length < minLength) return false
        return value.any(Char::isUpperCase) &&
            value.any(Char::isLowerCase) &&
            value.any(Char::isDigit)
    }

    fun isBlank(value: String?): Boolean = value.isNullOrBlank()

    fun isNotBlank(value: String?): Boolean = !value.isNullOrBlank()
}
