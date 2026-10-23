package com.wkq.photo.core.utils

import java.io.File
import java.util.ArrayDeque

data class TemporaryFileCleanupResult(
    val deletedFiles: Int = 0,
    val deletedDirectories: Int = 0,
    val reclaimedBytes: Long = 0,
    val skippedProtectedEntries: Int = 0,
    val failedEntries: Int = 0,
    val limitReached: Boolean = false,
) {
    operator fun plus(other: TemporaryFileCleanupResult) = TemporaryFileCleanupResult(
        deletedFiles = deletedFiles + other.deletedFiles,
        deletedDirectories = deletedDirectories + other.deletedDirectories,
        reclaimedBytes = reclaimedBytes + other.reclaimedBytes,
        skippedProtectedEntries = skippedProtectedEntries + other.skippedProtectedEntries,
        failedEntries = failedEntries + other.failedEntries,
        limitReached = limitReached || other.limitReached,
    )
}

/** 只清理明确目录中的过期文件，并跳过受保护路径和逃逸到目录外的链接。 */
object TemporaryFileCleaner {
    fun clean(
        roots: Iterable<File>,
        olderThanMillis: Long,
        protectedPaths: Collection<File> = emptyList(),
        nowMillis: Long = System.currentTimeMillis(),
        maxEntries: Int = DEFAULT_MAX_ENTRIES,
    ): TemporaryFileCleanupResult {
        require(olderThanMillis >= 0L)
        require(nowMillis >= 0L)
        require(maxEntries > 0)

        val cutoff = (nowMillis - olderThanMillis).coerceAtLeast(0L)
        val protected = protectedPaths.mapNotNull(::canonicalPathOrNull)
        var result = TemporaryFileCleanupResult()
        var visitedEntries = 0

        roots.distinctBy(File::getAbsolutePath).forEach { root ->
            if (result.limitReached || !root.exists()) return@forEach
            val canonicalRoot = canonicalPathOrNull(root)
            if (canonicalRoot == null) {
                result = result.copy(failedEntries = result.failedEntries + 1)
                return@forEach
            }
            val entries = ArrayDeque<PendingEntry>()
            if (root.isDirectory) {
                root.listFiles()?.forEach {
                    entries.addLast(PendingEntry(it, expanded = false, originalLastModified = it.lastModified()))
                }
            } else {
                entries.addLast(PendingEntry(root, expanded = false, originalLastModified = root.lastModified()))
            }

            while (entries.isNotEmpty()) {
                if (visitedEntries >= maxEntries) {
                    result = result.copy(limitReached = true)
                    break
                }
                val entry = entries.removeLast()
                if (!entry.expanded) visitedEntries++
                val canonicalEntry = canonicalPathOrNull(entry.file)
                if (canonicalEntry == null || !isWithin(canonicalEntry, canonicalRoot)) {
                    result = result.copy(failedEntries = result.failedEntries + 1)
                    continue
                }
                if (protected.any { isWithin(canonicalEntry, it) }) {
                    result = result.copy(skippedProtectedEntries = result.skippedProtectedEntries + 1)
                    continue
                }
                if (entry.file.isDirectory && !entry.expanded) {
                    entries.addLast(entry.copy(expanded = true))
                    entry.file.listFiles()?.forEach { child ->
                        entries.addLast(
                            PendingEntry(child, expanded = false, originalLastModified = child.lastModified())
                        )
                    }
                    continue
                }
                if (!entry.file.exists() || entry.originalLastModified > cutoff) continue

                if (entry.file.isDirectory) {
                    if (entry.file.listFiles().isNullOrEmpty()) {
                        result = if (entry.file.delete()) {
                            result.copy(deletedDirectories = result.deletedDirectories + 1)
                        } else {
                            result.copy(failedEntries = result.failedEntries + 1)
                        }
                    }
                } else {
                    val bytes = entry.file.length()
                    result = if (entry.file.delete()) {
                        result.copy(
                            deletedFiles = result.deletedFiles + 1,
                            reclaimedBytes = result.reclaimedBytes + bytes,
                        )
                    } else {
                        result.copy(failedEntries = result.failedEntries + 1)
                    }
                }
            }
        }
        return result
    }

    private fun canonicalPathOrNull(file: File): String? =
        runCatching { file.canonicalFile.absolutePath.trimEnd(File.separatorChar) }.getOrNull()

    private fun isWithin(path: String, parent: String): Boolean =
        path == parent || path.startsWith(parent + File.separator)

    private data class PendingEntry(
        val file: File,
        val expanded: Boolean,
        val originalLastModified: Long,
    )

    private const val DEFAULT_MAX_ENTRIES = 20_000
}
