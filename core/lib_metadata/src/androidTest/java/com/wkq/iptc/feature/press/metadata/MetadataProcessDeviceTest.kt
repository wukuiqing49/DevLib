package com.wkq.iptc.feature.press.metadata

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** 独立测试包中自暂停子进程，验证超时/取消不再等待其输出管道。 */
@RunWith(AndroidJUnit4::class)
class MetadataProcessDeviceTest {
    @Test fun foregroundProtectionSurvivesScreenOffAndCommandGaps() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val activity = instrumentation.startActivitySync(android.content.Intent(context,
            MetadataDeviceStartActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        // 熄屏状态连续运行超过 OEM 冻结窗口，中间模拟多次命令间歇。
        try {
        MetadataExecutionService.protect(context) { _ ->
            for (key in listOf("KEYCODE_HOME", "KEYCODE_SLEEP")) {
                instrumentation.uiAutomation.executeShellCommand("input keyevent $key").use { descriptor ->
                    android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
                }
            }
        }
        repeat(45) {
            MetadataExecutionService.protect(context) { checkRunning ->
                checkRunning()
                assertTrue(serviceIsForeground())
            }
            delay(1_000)
        }
        delay(2_500)
        assertFalse(serviceIsForeground())
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }

    @Test fun foregroundProtectionCleansUpAfterFailure() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val failure = runCatching {
            MetadataExecutionService.protect(context) { checkRunning ->
                checkRunning()
                assertTrue(serviceIsForeground())
                MetadataExecutionService.protect(context) { nested ->
                    nested()
                    assertTrue(serviceIsForeground())
                }
                assertTrue(serviceIsForeground())
                error("test failure")
            }
        }.exceptionOrNull()
        assertEquals("test failure", failure?.message)
        delay(2_500)
        assertFalse("异常后不能残留前台服务", serviceIsForeground())
    }

    @Test fun foregroundProtectionCleansUpAfterCancellation() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val entered = CompletableDeferred<Unit>()
        val job = launch(Dispatchers.IO) {
            MetadataExecutionService.protect(context) { _ ->
                assertTrue(serviceIsForeground())
                entered.complete(Unit)
                awaitCancellation()
            }
        }
        withTimeout(10_000) { entered.await() }
        job.cancelAndJoin()
        delay(2_500)
        assertFalse("取消后不能残留前台服务", serviceIsForeground())
    }

    @Suppress("DEPRECATION")
    private fun serviceIsForeground(): Boolean {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName.endsWith(".test"))
        val manager = context.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        return manager.getRunningServices(100).any {
            it.service.className == MetadataExecutionService::class.java.name && it.foreground
        }
    }

    @Test fun stoppedChildTimesOutPromptly() = runBlocking {
        withDirectory { directory ->
            val started = android.os.SystemClock.elapsedRealtime()
            val failure = runCatching {
                MetadataProcessRunner.run(ProcessBuilder("/system/bin/sh", "-c", "kill -STOP \u0024\u0024"),
                    directory, timeoutMillis = 500,
                    clock = { android.os.SystemClock.elapsedRealtime() }, terminate = { it.destroyForcibly() })
            }.exceptionOrNull()
            assertEquals("Metadata operation timed out", failure?.message)
            assertTrue(android.os.SystemClock.elapsedRealtime() - started < 5_000)
            assertTrue(directory.listFiles()!!.isEmpty())
        }
    }

    @Test fun stoppedChildCancellationReturnsPromptly() = runBlocking {
        withDirectory { directory ->
            val started = android.os.SystemClock.elapsedRealtime()
            val job = launch(Dispatchers.IO) {
                MetadataProcessRunner.run(ProcessBuilder("/system/bin/sh", "-c", "kill -STOP \u0024\u0024"),
                    directory, terminate = { it.destroyForcibly() })
            }
            delay(250)
            job.cancelAndJoin()
            assertTrue(android.os.SystemClock.elapsedRealtime() - started < 5_000)
            assertTrue(directory.listFiles()!!.isEmpty())
        }
    }

    private suspend fun withDirectory(action: suspend (File) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName.endsWith(".test"))
        val directory = File(context.cacheDir, "process-regression-${java.util.UUID.randomUUID()}")
        check(directory.mkdirs())
        try { action(directory) } finally { directory.deleteRecursively() }
    }
}
