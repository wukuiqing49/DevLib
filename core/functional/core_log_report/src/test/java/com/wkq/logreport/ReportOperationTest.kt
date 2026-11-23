package com.wkq.logreport

import org.junit.Assert.*
import org.junit.Test

/** 验证并行操作和重复收尾不会混淆排查结果。 */
class ReportOperationTest {
    @Test fun keepsIdentityElapsedAndExactlyOneTerminal() {
        var clock = 100L
        val events = mutableListOf<OperationUpdate>()
        val trace = ReportOperation("operation-1", "select", { clock }, events::add)
        clock = 140
        trace.stage("resolved", 3)
        trace.finish("SKIPPED", "stale_snapshot", 3)
        trace.finish("SUCCEEDED", "late_callback")
        trace.stage("late_stage")
        assertEquals(3, events.size)
        assertTrue(events.all { it.id == "operation-1" })
        assertEquals(40L, events.last().elapsedMs)
        assertEquals("SKIPPED", events.last().outcome)
    }
    @Test fun concurrentFinishIsUniqueAndOtherOperationIndependent() {
        val events = java.util.Collections.synchronizedList(mutableListOf<OperationUpdate>())
        val first = ReportOperation("first", "export", emit = { events.add(it) })
        val second = ReportOperation("second", "export", emit = { events.add(it) })
        val workers = (1..12).map { Thread { first.finish("FAILED", "failure") }.apply { start() } }
        workers.forEach(Thread::join)
        second.finish("SUCCEEDED", "saved")
        assertEquals(1, events.count { it.id == "first" && it.outcome == "FAILED" })
        assertEquals(1, events.count { it.id == "second" && it.outcome == "SUCCEEDED" })
    }
    @Test fun errorStackStaysWithOperationAndExcludesMessage() {
        val events = mutableListOf<OperationUpdate>()
        val trace = ReportOperation("failed-operation", "export", emit = { events.add(it) })
        trace.error(IllegalStateException("token=private"))
        trace.finish("FAILED", "verification")
        val error = events.single { it.errorStack != null }
        assertEquals("failed-operation", error.id)
        assertTrue(error.errorStack!!.contains("IllegalStateException"))
        assertFalse(error.errorStack!!.contains("token=private"))
    }
    @Test fun recorderFailureDoesNotEscapeIntoBusiness() {
        val trace = ReportOperation("test", "export", emit = { error("disk unavailable") })
        trace.stage("sending")
        trace.finish("UNKNOWN", "ack_missing")
    }
}
