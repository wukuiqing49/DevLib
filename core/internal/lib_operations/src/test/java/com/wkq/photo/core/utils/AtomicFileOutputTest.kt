package com.wkq.photo.core.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class AtomicFileOutputTest {

    @Test
    fun replacesExistingFileAfterSuccessfulWrite() {
        val root = Files.createTempDirectory("atomic-output-test").toFile()
        val output = root.resolve("result.txt").apply { writeText("old") }

        AtomicFileOutput.write(output) { temporary -> temporary.writeText("new") }

        assertEquals("new", output.readText())
        assertFalse(root.listFiles().orEmpty().any { ".photokit." in it.name })
    }

    @Test
    fun preservesExistingFileWhenWriterFails() {
        val root = Files.createTempDirectory("atomic-output-failure-test").toFile()
        val output = root.resolve("result.txt").apply { writeText("old") }

        runCatching {
            AtomicFileOutput.write(output) { temporary ->
                temporary.writeText("partial")
                error("write failed")
            }
        }

        assertEquals("old", output.readText())
    }

    @Test
    fun recoversBackupLeftByInterruptedReplacement() {
        val root = Files.createTempDirectory("atomic-recovery-test").toFile()
        val output = root.resolve("output.txt")
        root.resolve(".output.txt.photokit.bak").writeText("old")
        root.resolve(".output.txt.photokit.tmp").writeText("partial")

        assertTrue(AtomicFileOutput.recover(output))
        assertEquals("old", output.readText())
        assertFalse(root.resolve(".output.txt.photokit.bak").exists())
        assertFalse(root.resolve(".output.txt.photokit.tmp").exists())
    }

    @Test
    fun serializesWritersForTheSameCanonicalPath() {
        val root = Files.createTempDirectory("atomic-concurrency-test").toFile()
        val output = root.resolve("output.txt")
        val start = CountDownLatch(1)
        val active = AtomicInteger()
        val maxActive = AtomicInteger()
        val executor = Executors.newFixedThreadPool(2)

        val futures = listOf("first", "second").map { value ->
            executor.submit {
                start.await()
                AtomicFileOutput.write(output) { temporary ->
                    val current = active.incrementAndGet()
                    maxActive.updateAndGet { maxOf(it, current) }
                    try {
                        Thread.sleep(30)
                        temporary.writeText(value)
                    } finally {
                        active.decrementAndGet()
                    }
                }
            }
        }
        start.countDown()
        futures.forEach { it.get(5, TimeUnit.SECONDS) }
        executor.shutdownNow()

        assertEquals(1, maxActive.get())
        assertTrue(output.readText() in setOf("first", "second"))
    }

    @Test
    fun temporaryFileRetainsTargetExtensionForFormatBasedEncoders() {
        val root = Files.createTempDirectory("atomic-extension-test").toFile()
        val output = root.resolve("output.jpg")

        AtomicFileOutput.write(output) { temporary ->
            assertEquals("jpg", temporary.extension)
            temporary.writeText("encoded")
        }

        assertEquals("encoded", output.readText())
    }
}
