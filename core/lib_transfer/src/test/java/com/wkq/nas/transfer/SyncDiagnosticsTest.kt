package com.wkq.nas.transfer

import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipInputStream
import org.json.JSONObject

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SyncDiagnosticsTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val store get() = SyncDiagnostics(context)
    @Before fun setup() { store.clear() }
    @After fun cleanup() { store.clear() }
    private fun entries(run: String = ""): Map<String, String> {
        val output = ByteArrayOutputStream(); store.export(output, run)
        val result = linkedMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(output.toByteArray())).use { zip ->
            while (true) { val entry = zip.nextEntry ?: break; result[entry.name] = zip.readBytes().toString(Charsets.UTF_8) }
        }; return result
    }
    @Test fun exportRedactsValuesAndRetainsCrossInstanceSequenceAndReferences() {
        store.event(DiagnosticEvent.ENQUEUED, "run-secret", endpoint = "192.168.1.5/private", file = "private/photo.jpg")
        SyncDiagnostics(context).event(DiagnosticEvent.FILE_END, "run-secret", file = "private/photo.jpg", outcome = DiagnosticOutcome.SUCCEEDED)
        val values = entries(); assertEquals(setOf("manifest.json", "summary.txt", "events.jsonl"), values.keys)
        val text = values.values.joinToString(); assertFalse(text.contains("192.168.1.5")); assertFalse(text.contains("photo.jpg")); assertFalse(text.contains("run-secret"))
        val rows = values.getValue("events.jsonl").trim().lines().map(::JSONObject)
        assertTrue(rows[1].getLong("sequence") > rows[0].getLong("sequence"))
        assertEquals(rows[0].getString("run"), rows[1].getString("run"))
        assertEquals(rows[0].getString("file"), rows[1].getString("file"))
    }
    @Test fun runExportDoesNotIncludeOtherRunsAndDetailedEventsRequireOptIn() {
        store.event(DiagnosticEvent.ENQUEUED, "first")
        store.event(DiagnosticEvent.ENQUEUED, "second")
        store.event(DiagnosticEvent.COMMIT, "first", detail = true)
        assertEquals(1, entries("first").getValue("events.jsonl").trim().lines().size)
        store.detailed(true); store.event(DiagnosticEvent.COMMIT, "first", detail = true)
        assertEquals(2, entries("first").getValue("events.jsonl").trim().lines().size)
        store.detailed(false)
        assertEquals(0L, store.detailedUntil())
    }
    @Test fun retentionReportsTruncationAndWriteFailureDoesNotThrow() {
        val directory = File(context.filesDir, "sync-diagnostics").apply { mkdirs() }
        File(directory, "old.jsonl").apply { writeText("{}"); setLastModified(System.currentTimeMillis() - 8 * 86400000L) }
        store.event(DiagnosticEvent.ACTION)
        assertTrue(JSONObject(entries().getValue("manifest.json")).getBoolean("truncated"))
        store.clear(); assertTrue(directory.delete()); directory.writeText("block directory")
        store.event(DiagnosticEvent.RESULT)
        assertTrue(store.summary().contains("gap=true"))
        assertTrue(directory.delete()); directory.mkdirs()
    }
    @Test fun exportDoesNotTrustUnknownFieldsOrMalformedReferenceValues() {
        val directory = File(context.filesDir, "sync-diagnostics").apply { mkdirs() }
        File(directory, "000.jsonl").writeText("{\"event\":\"PROBE\",\"run\":\"192.168.0.2/private\",\"password\":\"secret\",\"reason\":\"raw uri error\"}\n")
        val text = entries().getValue("events.jsonl")
        assertFalse(text.contains("192.168")); assertFalse(text.contains("password")); assertFalse(text.contains("secret")); assertFalse(text.contains("raw uri"))
        assertEquals("PROBE", JSONObject(text).getString("event"))
    }
}
