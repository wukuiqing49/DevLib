package com.wkq.photo.core.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class TemporaryFileCleanerTest {
    @Test
    fun deletesOnlyExpiredFilesAndEmptyDirectories() {
        val root = Files.createTempDirectory("temporary-cleaner").toFile()
        val expiredDirectory = root.resolve("expired").apply { mkdirs() }
        val expired = expiredDirectory.resolve("old.bin").apply {
            writeBytes(ByteArray(16))
            setLastModified(1_000L)
        }
        expiredDirectory.setLastModified(1_000L)
        val fresh = root.resolve("fresh.bin").apply {
            writeBytes(ByteArray(8))
            setLastModified(9_500L)
        }

        val result = TemporaryFileCleaner.clean(
            roots = listOf(root),
            olderThanMillis = 2_000L,
            nowMillis = 10_000L,
        )

        assertFalse(expired.exists())
        assertFalse(expiredDirectory.exists())
        assertTrue(fresh.exists())
        assertTrue(root.exists())
        assertEquals(1, result.deletedFiles)
        assertEquals(1, result.deletedDirectories)
        assertEquals(16L, result.reclaimedBytes)
    }

    @Test
    fun preservesProtectedDirectoryAndItsChildren() {
        val root = Files.createTempDirectory("temporary-cleaner-protected").toFile()
        val protectedDirectory = root.resolve("running-task").apply { mkdirs() }
        val protectedFile = protectedDirectory.resolve("input.jpg").apply {
            writeText("active")
            setLastModified(1_000L)
        }
        protectedDirectory.setLastModified(1_000L)
        val stale = root.resolve("stale.jpg").apply {
            writeText("stale")
            setLastModified(1_000L)
        }

        val result = TemporaryFileCleaner.clean(
            roots = listOf(root),
            olderThanMillis = 0L,
            protectedPaths = listOf(protectedDirectory),
            nowMillis = 10_000L,
        )

        assertTrue(protectedFile.exists())
        assertTrue(protectedDirectory.exists())
        assertFalse(stale.exists())
        assertTrue(result.skippedProtectedEntries > 0)
    }

    @Test
    fun stopsAtConfiguredTraversalLimit() {
        val root = Files.createTempDirectory("temporary-cleaner-limit").toFile()
        repeat(5) { index ->
            root.resolve("$index.tmp").apply {
                writeText(index.toString())
                setLastModified(1_000L)
            }
        }

        val result = TemporaryFileCleaner.clean(
            roots = listOf(root),
            olderThanMillis = 0L,
            nowMillis = 10_000L,
            maxEntries = 2,
        )

        assertTrue(result.limitReached)
        assertEquals(2, result.deletedFiles)
        assertEquals(3, root.listFiles().orEmpty().size)
    }
}
