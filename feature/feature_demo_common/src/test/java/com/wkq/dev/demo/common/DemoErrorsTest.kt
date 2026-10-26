package com.wkq.dev.demo.common

import java.io.FileNotFoundException
import java.io.IOException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLHandshakeException
import org.junit.Assert.assertEquals
import org.junit.Test

class DemoErrorsTest {
    @Test
    fun wrappedNetworkFailureUsesCauseWithoutDisplayingCredentials() {
        assertEquals(
            DemoErrorKind.NETWORK,
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
