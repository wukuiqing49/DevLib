package com.wkq.logreport

import org.junit.Assert.*
import org.junit.Test

/** 验证日志脱敏不会泄露典型凭据，同时保留诊断错误码。 */
class LogPrivacyTest {
    @Test fun removesPrivateValuesPreservesFailureCode() {
        val result = LogPrivacy.redact("response=0x2019 token=private-secret /storage/emulated/0/photo.jpg https://camera.local/path user@example.com")
        assertTrue(result.contains("0x2019"))
        listOf("private-secret", "photo.jpg", "camera.local", "user@example.com").forEach { assertFalse(result.contains(it)) }
    }
    @Test fun boundsOversizedMessages() {
        assertTrue(LogPrivacy.redact("x".repeat(10000)).length <= 2000)
    }
}
