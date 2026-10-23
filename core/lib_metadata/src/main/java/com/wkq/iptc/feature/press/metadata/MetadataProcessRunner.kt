package com.wkq.iptc.feature.press.metadata

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import java.io.File

/** 输出写入私有临时文件，避免被冻结的子进程让阻塞管道拖住超时和取消。 */
internal object MetadataProcessRunner {
    suspend fun run(
        builder: ProcessBuilder,
        directory: File,
        timeoutMillis: Long = 120_000,
        outputLimit: Long = 16L * 1024 * 1024,
        clock: () -> Long = { System.nanoTime() / 1_000_000 },
        checkRunning: () -> Unit = {},
        start: (ProcessBuilder, File, File) -> Process = { command, output, errors ->
            command.redirectOutput(output).redirectError(errors).start()
        },
        terminate: (Process) -> Unit = { it.destroy() },
        allowReadWarnings: Boolean = false
    ): ByteArray {
        val output = File.createTempFile("metadata_stdout_", ".tmp", directory)
        var errors: File? = null
        var process: Process? = null
        try {
            errors = File.createTempFile("metadata_stderr_", ".tmp", directory)
            currentCoroutineContext().ensureActive()
            checkRunning()
            process = start(builder, output, errors)
            process.outputStream.close()
            val started = clock()
            while (true) {
                currentCoroutineContext().ensureActive()
                checkRunning()
                if (clock() - started >= timeoutMillis) {
                    throw java.util.concurrent.TimeoutException("Metadata operation timed out")
                }
                check(output.length() <= outputLimit && errors.length() <= outputLimit) {
                    "Metadata output exceeds limit"
                }
                val exit = try { process.exitValue() } catch (_: IllegalThreadStateException) { null }
                if (exit != null) {
                    // 只读允许明确的 Warning 行；未知诊断和非零退出仍拒绝，写入保持严格。
                    val warningsOnly = allowReadWarnings && errors.bufferedReader().useLines { lines ->
                        lines.filter(String::isNotBlank).all { it.startsWith("Warning: ") || it.startsWith("Warning (minor): ") }
                    }
                    check(exit == 0 && (errors.length() == 0L || warningsOnly)) {
                        "Metadata operation failed or reported warnings (exit=$exit, stderrBytes=${errors.length()})"
                    }
                    return output.inputStream().use { input ->
                        val bytes = input.readBytes()
                        check(bytes.size.toLong() <= outputLimit) { "Metadata output exceeds limit" }
                        bytes
                    }
                }
                delay(25)
            }
        } finally {
            process?.let { child ->
                // 每项清理独立执行，不能覆盖超时/取消原因，亦不等待被冻结的子进程。
                runCatching { terminate(child) }
                // 给系统最多 250ms 回收句柄；不可无限 waitFor（冻结进程可能不退出）。
                runCatching {
                    for (attempt in 0 until 25) {
                        try { child.exitValue(); break } catch (_: IllegalThreadStateException) { Thread.sleep(10) }
                    }
                }
                runCatching { child.inputStream.close() }
                runCatching { child.errorStream.close() }
                runCatching { child.outputStream.close() }
            }
            // Windows JVM 的进程句柄释放可能稍晚于 exitValue；Android 通常可立即 unlink。
            for (attempt in 0 until 25) {
                val outputDeleted = !output.exists() || output.delete()
                val errorsDeleted = errors?.let { !it.exists() || it.delete() } ?: true
                if (outputDeleted && errorsDeleted) break
                Thread.sleep(10)
            }
        }
    }
}
