package com.wkq.logreport

import org.junit.Assert.*
import org.junit.Test

/** 针对常见敏感日志格式验证值全部移除且错误码仍可保留。 */
class LogPrivacyFormatsTest {
    /** JSON与转义JSON凭据值不进入可分享文本。 */
    @Test fun jsonAndEscapedValuesAreRemoved() {
        val text = """{"password":"hello world", "access_token":"abc.def", "client_secret":"private-value", "response":"0x2019"}"""
        val output = LogPrivacy.redact(text)
        listOf("hello world", "abc.def", "private-value").forEach { assertFalse(output.contains(it)) }
        assertTrue(output.contains("0x2019"))
        assertFalse(LogPrivacy.redact(text.replace("\"", "\\\"")).contains("hello world"))
    }
    /** 授权头、Cookie和含空格密码被移除，保留非敏感错误码。 */
    @Test fun authorizationCookiesAndQuotedPasswordsAreRemoved() {
        val text = "Authorization: Bearer abc.def ghi\nCookie: session=private-cookie\nresponse=0x2019 password='a password with spaces'"
        val output = LogPrivacy.redact(text)
        listOf("abc.def", "ghi", "private-cookie", "a password with spaces").forEach { assertFalse(output.contains(it)) }
        assertTrue(output.contains("0x2019"))
    }
    /** 路径和地址被替换，超长凭据截断前完成脱敏。 */
    @Test fun pathsAddressesAndTruncatedCredentialsAreRemoved() {
        val text = """{"path":"/storage/My Photos/private.jpg"} C:\Users\Private\file.jpg 192.168.1.12 AA:BB:CC:DD:EE:FF"""
        val output = LogPrivacy.redact(text)
        listOf("private.jpg", "Private", "192.168.1.12", "AA:BB").forEach { assertFalse(output.contains(it)) }
        assertFalse(LogPrivacy.redact("token=" + "x".repeat(20000)).contains("xxxxx"))
    }
}
