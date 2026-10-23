package com.wkq.nativepdf

import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PdfOutputFileTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun cancellationBeforePublicationKeepsPreviousReport() {
        val report = folder.newFile("report.pdf").apply { writeText("previous") }
        var canceled = false
        assertThrows(java.util.concurrent.CancellationException::class.java) {
            writePdfOutput(report, checkActive = {
                if (canceled) throw java.util.concurrent.CancellationException()
            }) { output ->
                output.write("new report".toByteArray())
                canceled = true
            }
        }
        assertEquals("previous", report.readText())
        assertEquals(listOf("report.pdf"), folder.root.list()?.toList())
    }

    @Test fun writeFailurePreservesPreviousReportAndRemovesTemporaryFile() {
        val report = folder.newFile("report.pdf").apply { writeText("previous") }
        assertThrows(IOException::class.java) {
            writePdfOutput(report) { output ->
                output.write("partial".toByteArray())
                throw IOException("Simulated storage failure")
            }
        }
        assertEquals("previous", report.readText())
        assertEquals(listOf("report.pdf"), folder.root.list()?.toList())
    }

    @Test fun emptyOutputDoesNotReplacePreviousReport() {
        val report = folder.newFile("report.pdf").apply { writeText("previous") }
        assertThrows(IllegalStateException::class.java) { writePdfOutput(report) {} }
        assertEquals("previous", report.readText())
    }

    @Test fun failedReplacementLeavesExistingDestinationUntouched() {
        val destination = folder.newFolder("report.pdf")
        val existing = File(destination, "keep").apply { writeText("previous") }
        assertThrows(IllegalStateException::class.java) {
            writePdfOutput(destination) { it.write("complete".toByteArray()) }
        }
        assertEquals("previous", existing.readText())
        assertEquals(listOf("report.pdf"), folder.root.list()?.toList())
    }

    @Test fun replacementPublishesCompleteOutputOrPreservesPreviousReport() {
        val report = folder.newFile("report.pdf").apply { writeText("previous") }
        val supportsReplacement = supportsReplacement()
        if (supportsReplacement) {
            writePdfOutput(report) {
                assertEquals("previous", report.readText())
                it.write("complete".toByteArray())
            }
            assertEquals("complete", report.readText())
        } else {
            assertThrows(IllegalStateException::class.java) {
                writePdfOutput(report) { it.write("complete".toByteArray()) }
            }
            assertEquals("previous", report.readText())
        }
    }

    @Test fun concurrentWritersDoNotDeleteEachOthersTemporaryFiles() {
        val report = File(folder.root, "report.pdf")
        val supportsReplacement = supportsReplacement()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val first = executor.submit<Boolean> {
                runCatching { writePdfOutput(report) {
                    it.write("first".toByteArray())
                    started.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    it.write(" complete".toByteArray())
                } }.isSuccess
            }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            writePdfOutput(report) { it.write("second complete".toByteArray()) }
            assertEquals("second complete", report.readText())
            release.countDown()
            val result = first.get(5, TimeUnit.SECONDS)
            assertEquals(supportsReplacement, result)
            assertEquals(if (supportsReplacement) "first complete" else "second complete", report.readText())
            assertEquals(listOf("report.pdf"), folder.root.list()?.toList())
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    private fun supportsReplacement(): Boolean {
        val source = folder.newFile("rename-source")
        val target = folder.newFile("rename-target")
        return try { source.renameTo(target) } finally { source.delete(); target.delete() }
    }
}
