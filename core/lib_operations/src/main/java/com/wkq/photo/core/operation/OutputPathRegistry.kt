package com.wkq.photo.core.operation

import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** 防止两个任务同时写入同一个输出文件。 */
object OutputPathRegistry {
    private val activePaths = ConcurrentHashMap.newKeySet<String>()

    fun acquire(output: File): Lease {
        val path = runCatching { output.canonicalPath }.getOrElse { output.absolutePath }
        if (!activePaths.add(path)) {
            throw OperationException(
                OperationErrorCode.OUTPUT_CONFLICT,
                "Output is already being written: $path",
            )
        }
        return Lease(path)
    }

    class Lease internal constructor(private val path: String) : AutoCloseable {
        private var closed = false

        override fun close() {
            if (!closed) {
                closed = true
                activePaths.remove(path)
            }
        }
    }
}

inline fun <T> withOutputPath(output: File, block: () -> T): T =
    OutputPathRegistry.acquire(output).use { block() }
