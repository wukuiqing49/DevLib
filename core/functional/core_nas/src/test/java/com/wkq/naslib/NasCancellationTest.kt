package com.wkq.naslib

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.concurrent.CancellationException

/** NAS 取消、中断和调用方异常不能变成普通可重试协议故障。 */
class NasCancellationTest {
    /** 流复制回调取消保持同一异常，停止后续写入。 */
    @Test fun callerCancellationIsPreserved() {
        val cancellation = CancellationException("cancel")
        val output = ByteArrayOutputStream()
        val result = runCatching { nasCall { copyBytes(byteArrayOf(1, 2).inputStream(), output) { throw cancellation } } }
        assertSame(cancellation, result.exceptionOrNull())
        assertEquals(0, output.size())
    }
    /** 线程中断时不消费输入，也不清除线程标记。 */
    @Test fun interruptedCopyStopsBeforeReading() {
        val output = ByteArrayOutputStream()
        try {
            Thread.currentThread().interrupt()
            val result = runCatching { nasCall { copyBytes(byteArrayOf(1).inputStream(), output) {} } }
            assertTrue(result.exceptionOrNull() is java.io.InterruptedIOException)
            assertEquals(0, output.size())
            assertTrue(Thread.currentThread().isInterrupted)
        } finally { Thread.interrupted() }
    }
}
