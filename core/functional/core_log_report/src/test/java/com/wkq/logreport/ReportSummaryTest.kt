package com.wkq.logreport

import org.junit.Assert.*
import org.junit.Test

/** 验证摘要对未知结果和旧附件的判定，不将无异常推定为成功。 */
class ReportSummaryTest {
    @Test fun distinguishesUnfinishedSkippedAndUnknown() {
        val events = listOf(
            SummaryEvent(0, "operation", "requested", "a", "download", "STARTED"),
            SummaryEvent(1, "operation", "empty_selection", "b", "select", "SKIPPED", 2, 0),
            SummaryEvent(2, "operation", "ack_missing", "c", "shoot", "UNKNOWN", 20))
        val text = ReportSummary.render("report", "buildId=build-1", "interrupted", events, listOf("Counters unknown"), "reason=4", "Failure:42", 1)
        assertTrue(text.contains("UNRECORDED"))
        assertTrue(text.contains("outcome=SKIPPED"))
        assertTrue(text.contains("outcome=UNKNOWN"))
        assertTrue(text.contains("ack_missing"))
        assertTrue(text.contains("build-1"))
        assertTrue(text.contains("1970-01-01"))
    }
    @Test fun nativeExitIsReadableAndDoesNotClaimNoErrors() {
        val text = ReportSummary.render("native", "buildId=sample", "interrupted", emptyList(), emptyList(), "time=1 reason=5 status=11", "", 0)
        assertTrue(text.contains("NATIVE_CRASH"))
        assertFalse(text.contains("No explicit error event"))
    }
    @Test fun legacyWithoutOperationsOrEvidenceStaysUnknown() {
        val text = ReportSummary.render("old", "unknown", "finished", emptyList(), listOf("unknown"), "", "", 0)
        assertTrue(text.contains("No correlated operations"))
        assertTrue(text.contains("unavailable"))
        assertFalse(text.contains("outcome=SUCCEEDED"))
    }
}
