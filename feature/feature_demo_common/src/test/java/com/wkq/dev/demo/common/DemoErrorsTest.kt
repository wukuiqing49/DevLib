package com.wkq.dev.demo.common

import java.io.FileNotFoundException
import java.io.IOException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLHandshakeException
import org.junit.Assert.assertEquals
import org.junit.Test

/** 宿主错误分类不显示可能携带凭据的底层消息。 */
class DemoErrorsTest {
    /** 引擎预算、冲突和取消应得到可操作的资源提示。 */
    @Test fun engineFailuresHaveSpecificKinds() {
        val cases = listOf(
            com.wkq.photo.core.operation.OperationErrorCode.BUDGET_EXCEEDED to DemoErrorKind.BUDGET,
            com.wkq.photo.core.operation.OperationErrorCode.OUTPUT_CONFLICT to DemoErrorKind.CONFLICT,
            com.wkq.photo.core.operation.OperationErrorCode.CANCELLED to DemoErrorKind.CANCELLED)
        cases.forEach { (code, expected) ->
            assertEquals(expected, demoErrorKind(com.wkq.photo.core.operation.OperationException(code, "private")))
        }
    }
    /** 明确的套接字异常仍归为网络故障。 */
    @Test fun socketFailureRemainsNetworkError() {
        assertEquals(DemoErrorKind.NETWORK, demoErrorKind(java.net.SocketException("private")))
    }
    /** 包装后的普通 IO 不能误报为网络错误。 */
    @Test
    fun wrappedFileFailureUsesCauseWithoutDisplayingCredentials() {
        assertEquals(
            DemoErrorKind.IO,
            demoErrorKind(
                IllegalStateException("https://user:secret@fixture", IOException("token"))
            ),
        )
    }

    @Test
    fun timeoutIsMoreSpecificThanNetwork() {
        assertEquals(DemoErrorKind.TIMEOUT, demoErrorKind(SocketTimeoutException("private host")))
    }

    @Test
    fun certificateFailureIsMoreSpecificThanNetwork() {
        assertEquals(
            DemoErrorKind.CERTIFICATE,
            demoErrorKind(SSLHandshakeException("private certificate")),
        )
    }

    @Test
    fun missingFileIsNotTreatedAsNetwork() {
        assertEquals(DemoErrorKind.NOT_FOUND, demoErrorKind(FileNotFoundException("private path")))
    }

    @Test
    fun invalidInputIsActionable() {
        assertEquals(DemoErrorKind.INPUT, demoErrorKind(NumberFormatException("secret input")))
    }

    @Test
    fun permissionFailureIsActionable() {
        assertEquals(DemoErrorKind.PERMISSION, demoErrorKind(SecurityException("private URI")))
    }

    @Test
    fun cyclicCausesCannotHangErrorReporting() {
        val a = Exception("a")
        val b = Exception("b")
        a.initCause(b)
        b.initCause(a)
        assertEquals(DemoErrorKind.UNKNOWN, demoErrorKind(a))
    }
}
