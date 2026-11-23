package com.wkq.logreport

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** 验证磁盘失败后保留原报告并可原地重试。 */
class ReportFilesTest {
    /** 整理失败保持原事件与活动标记，同一目录重试并幂等封存。 */
    @Test fun failedSealRetainsActiveAndRetryUsesSameReport() {
        val folder = Files.createTempDirectory("report-seal").toFile()
        try {
            File(folder, "active").writeText("123")
            File(folder, "events.jsonl").writeText("saved-event")
            assertTrue(runCatching { ReportFiles.seal(folder) { throw java.io.IOException("disk full") } }.isFailure)
            assertTrue(File(folder, "active").exists())
            assertFalse(File(folder, "finished").exists())
            ReportFiles.seal(folder) { ReportFiles.atomic(File(folder, "capture.json"), "complete") }
            assertEquals("123", File(folder, "finished").readText())
            assertEquals("saved-event", File(folder, "events.jsonl").readText())
            ReportFiles.seal(folder) { error("idempotent seal must not repeat") }
        } finally { folder.deleteRecursively() }
    }
}
