package com.wkq.logreport

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** 核对教程依赖真实采集和附件状态，不把准备中或错误状态当作可操作结果。 */
class LogReportGuidePolicyTest {
    /** 空历史且采集关闭时说明如何开始，恢复历史未完成时不抢占页面。 */
    @Test fun emptyHistoryNeedsStableIndex() {
        assertNull(LogReportGuidePolicy.overview(ReportPageState()))
        assertEquals("start", LogReportGuidePolicy.overview(ReportPageState(historyLoading = false)))
    }

    /** 正在记录时说明停止，即使已有旧报告也不误导用户去分享旧记录。 */
    @Test fun recordingTakesPriorityOverHistory() {
        assertEquals("stop", LogReportGuidePolicy.overview(ReportPageState(ReportPhase.RECORDING,
            historyLoading = false, reports = listOf(ReportEntry("old", 1, 1, false, false)))))
    }

    /** 已封存报告使用真实历史入口，不推定当前采集或发送已经完成。 */
    @Test fun idleHistoryUsesSavedItem() {
        assertEquals("history", LogReportGuidePolicy.overview(ReportPageState(historyLoading = false,
            reports = listOf(ReportEntry("saved", 1, 1, false, false)))))
    }

    /** 启动、整理、失败和索引错误时不盖住重试及恢复操作。 */
    @Test fun transientAndFailedStatesSuppressOverview() {
        for (phase in listOf(ReportPhase.STARTING, ReportPhase.FINALIZING, ReportPhase.FAILED)) {
            assertNull(LogReportGuidePolicy.overview(ReportPageState(phase, historyLoading = false)))
        }
        assertNull(LogReportGuidePolicy.overview(ReportPageState(historyLoading = false, historyError = true)))
        assertNull(LogReportGuidePolicy.overview(ReportPageState(historyLoading = false, operationError = true)))
    }

    /** 只有真实附件准备成功才能介绍分享；删档、加载和失败时均抑制。 */
    @Test fun sharingRequiresAvailableAttachment() {
        val ready = ReportDetailState(attachment = ReportAttachment("saved", File("report.zip"), "summary", 1))
        assertTrue(LogReportGuidePolicy.detail(ready))
        assertFalse(LogReportGuidePolicy.detail(ReportDetailState()))
        assertFalse(LogReportGuidePolicy.detail(ready.copy(loading = true)))
        assertFalse(LogReportGuidePolicy.detail(ready.copy(error = true)))
        assertFalse(LogReportGuidePolicy.detail(ready.copy(deleted = true)))
        assertFalse(LogReportGuidePolicy.detail(ready.copy(deleteError = true)))
    }
}
