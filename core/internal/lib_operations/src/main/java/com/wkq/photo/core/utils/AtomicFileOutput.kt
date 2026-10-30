package com.wkq.photo.core.utils

import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 在目标文件同目录完成事务式写入，写入或替换失败时尽量保留原文件。
 */
object AtomicFileOutput {

    private val outputLocks = ConcurrentHashMap<String, ReentrantLock>()

    fun <T> write(outputFile: File, block: (temporaryFile: File) -> T): T {
        val target = outputFile.canonicalFile
        val lock = outputLocks.computeIfAbsent(target.path) { ReentrantLock() }
        return lock.withLock { writeLocked(target, block) }
    }

    fun recover(outputFile: File): Boolean {
        val target = outputFile.canonicalFile
        val lock = outputLocks.computeIfAbsent(target.path) { ReentrantLock() }
        return lock.withLock { recoverLocked(target) }
    }

    private fun <T> writeLocked(target: File, block: (temporaryFile: File) -> T): T {
        val parent = requireNotNull(target.parentFile) { "Output file must have a parent directory" }
        if (!parent.exists() && !parent.mkdirs()) {
            throw IOException("Unable to create output directory: ${parent.absolutePath}")
        }
        require(parent.isDirectory) { "Output parent is not a directory: ${parent.absolutePath}" }
        require(!target.isDirectory) { "Output path is a directory: ${target.absolutePath}" }

        recoverLocked(target)
        val temporary = temporaryFile(target)
        if (temporary.exists() && !temporary.delete()) {
            throw IOException("Unable to clear stale temporary output: ${temporary.absolutePath}")
        }
        if (!temporary.createNewFile()) {
            throw IOException("Unable to create temporary output: ${temporary.absolutePath}")
        }
        return try {
            val result = block(temporary)
            require(temporary.isFile) { "Output writer did not create a file" }
            replace(target, temporary)
            result
        } finally {
            temporary.delete()
        }
    }

    private fun replace(target: File, temporary: File) {
        if (!target.exists()) {
            if (!temporary.renameTo(target)) {
                throw IOException("Unable to finalize output: ${target.absolutePath}")
            }
            return
        }

        val backup = backupFile(target)
        if (backup.exists() && !backup.delete()) {
            throw IOException("Unable to clear stale output backup: ${backup.absolutePath}")
        }
        if (!target.renameTo(backup)) {
            throw IOException("Unable to preserve existing output: ${target.absolutePath}")
        }
        if (temporary.renameTo(target)) {
            backup.delete()
            return
        }

        val restored = backup.renameTo(target)
        val message = if (restored) {
            "Unable to finalize output; existing output was restored: ${target.absolutePath}"
        } else {
            "Unable to finalize output or restore backup: ${backup.absolutePath}"
        }
        throw IOException(message)
    }

    private fun recoverLocked(target: File): Boolean {
        val temporary = temporaryFile(target)
        val backup = backupFile(target)
        if (target.exists()) {
            temporary.delete()
            legacyTemporaryFile(target).delete()
            backup.delete()
            return false
        }
        if (!backup.exists()) {
            temporary.delete()
            legacyTemporaryFile(target).delete()
            return false
        }
        if (!backup.renameTo(target)) {
            throw IOException("Unable to recover interrupted output: ${target.absolutePath}")
        }
        temporary.delete()
        legacyTemporaryFile(target).delete()
        return true
    }

    private fun temporaryFile(target: File): File =
        requireNotNull(target.parentFile) { "Output file must have a parent directory" }
            .resolve(
                if (target.extension.isBlank()) ".${target.name}.photokit.tmp"
                else ".${target.name}.photokit.tmp.${target.extension}"
            )

    private fun legacyTemporaryFile(target: File): File =
        requireNotNull(target.parentFile) { "Output file must have a parent directory" }
            .resolve(".${target.name}.photokit.tmp")

    private fun backupFile(target: File): File =
        requireNotNull(target.parentFile) { "Output file must have a parent directory" }
            .resolve(".${target.name}.photokit.bak")
}
