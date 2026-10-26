package com.wkq.dev.demo.common

import java.io.IOException
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** 验证历史写入失败和协程取消不会阻断宿主忙碌状态复位。 */
class DemoExecutionTest {
    /** 正常完成只持久化一次，随后解除忙碌，不产生错误提示。 */
    @Test fun successResetsAfterPersist() = runBlocking {
        val events = mutableListOf<String>()
        finishDemoOperation({ events += "persist" }, { fail("Unexpected failure") }, { events += "reset" })
        assertEquals(listOf("persist", "reset"), events)
    }

    /** 存储故障交给单独提示回调，并确保解除忙碌状态。 */
    @Test fun persistenceFailureReportsAndResets() = runBlocking {
        val error = IOException("disk full")
        var busy = true
        var warning: Exception? = null
        finishDemoOperation({ throw error }, { warning = it }, { busy = false })
        assertSame(error, warning)
        assertFalse(busy)
    }

    /** 即使失败提示回调自身抛错，finally 仍保证忙碌状态复位。 */
    @Test fun reportingFailureStillResets() = runBlocking {
        var reset = false
        try {
            finishDemoOperation({ throw IOException() }, { throw IllegalStateException() }, { reset = true })
            fail("Expected callback failure")
        } catch (_: IllegalStateException) {
            assertTrue(reset)
        }
    }

    /** 取消中的任务仍执行挂起式历史写入，完成后保持取消状态。 */
    @Test fun cancelledTaskFinishesHistoryAndResets() = runBlocking {
        var persisted = false
        var reset = false
        var warned = false
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                finishDemoOperation({ delay(1); persisted = true }, { warned = true }, { reset = true })
            }
        }
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertTrue(persisted)
        assertTrue(reset)
        assertFalse(warned)
    }

    /** 取消与磁盘故障同时发生时提示历史失败，但不把原任务变成成功或留下忙碌状态。 */
    @Test fun cancelledTaskWithStorageFailureStaysCancelled() = runBlocking {
        var warned = false
        var reset = false
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                finishDemoOperation({ throw IOException() }, { warned = true }, { reset = true })
            }
        }
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertTrue(warned)
        assertTrue(reset)
    }

    /** 收尾主动抛出的取消异常不能伪装成普通存储失败，但仍必须复位。 */
    @Test fun explicitCancellationPropagatesAndResets() = runBlocking {
        var reset = false
        try {
            finishDemoOperation({ throw CancellationException() }, { fail("Cancellation swallowed") }, { reset = true })
            fail("Expected cancellation")
        } catch (_: CancellationException) {
            assertTrue(reset)
        }
    }
}
