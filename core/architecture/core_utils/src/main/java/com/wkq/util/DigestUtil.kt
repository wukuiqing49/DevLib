package com.wkq.util

import android.util.Base64
import java.security.MessageDigest

/** 常用摘要与 Base64 编解码工具。摘要方法返回小写十六进制字符串。 */
object DigestUtil {

    /** 计算文本的 MD5 摘要。MD5 不应用于安全敏感场景。 */
    fun md5(text: String): String = digest(text, "MD5")

    /** 计算文本的 SHA-1 摘要。SHA-1 不应用于安全敏感场景。 */
    fun sha1(text: String): String = digest(text, "SHA-1")

    /** 计算文本的 SHA-256 摘要。 */
    fun sha256(text: String): String = digest(text, "SHA-256")

    /** 按 UTF-8 编码为不换行的 Base64 字符串。 */
    fun base64Encode(text: String): String {
        return Base64.encodeToString(text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
    }

    /** 按 UTF-8 解码 Base64；格式非法时返回空字符串。 */
    fun base64Decode(text: String): String {
        return runCatching {
            String(Base64.decode(text, Base64.NO_WRAP), Charsets.UTF_8)
        }.getOrDefault("")
    }

    private fun digest(text: String, algorithm: String): String {
        val bytes = MessageDigest.getInstance(algorithm).digest(text.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
