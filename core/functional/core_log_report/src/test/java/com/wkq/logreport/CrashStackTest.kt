package com.wkq.logreport

import org.junit.Assert.*
import org.junit.Test

/** 验证崩溃原因链可读且不外发敏感异常消息。 */
class CrashStackTest {
    @Test fun keepsCausePositionWithoutPrivateMessages() {
        val cause = IllegalStateException("token=private-value")
        cause.stackTrace = arrayOf(StackTraceElement("app.Camera", "shoot", "Camera.kt", 42))
        val wrapper = RuntimeException("/private/photo.jpg", cause)
        val text = CrashStack.render(wrapper)
        assertTrue(text.contains("Caused by java.lang.IllegalStateException"))
        assertTrue(text.contains("app.Camera.shoot:42"))
        assertFalse(text.contains("private-value"))
        assertFalse(text.contains("photo.jpg"))
    }
}
