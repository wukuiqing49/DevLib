package com.wkq.logreport

/** 摘要只使用结构化证据，保留未记录终态，避免把缺失证据推断为成功。 */
internal data class SummaryEvent(val time: Long, val kind: String, val code: String, val operationId: String = "", val operation: String = "", val outcome: String = "", val duration: Long = 0, val count: Int? = null, val errorStack: String = "")

/** 提供可测试的报告摘要，不读取Android环境或原始业务数据。 */
internal object ReportSummary {
    /** 将记录时间转换为可直接阅读的UTC时间。 */
    private fun timeLabel(time: Long): String = java.time.Instant.ofEpochMilli(time).toString()

    /** 将系统退出原因码翻译为可直接判断的分类，同时保留原始证据。 */
    private fun systemDescription(system: String): String = system.lineSequence().joinToString("\n") { line ->
        val reason = Regex("reason=(\\d+)").find(line)?.groupValues?.get(1)?.toIntOrNull()
        val label = when (reason) {
            1 -> "EXIT_SELF / 进程主动退出"
            2 -> "SIGNALED / 信号终止"
            3 -> "LOW_MEMORY / 低内存终止"
            4 -> "JAVA_CRASH / Java层崩溃"
            5 -> "NATIVE_CRASH / 原生崩溃"
            6 -> "ANR / 系统无响应"
            10 -> "USER_REQUESTED / 用户或工具请求结束"
            11 -> "USER_STOPPED / 用户停止"
            else -> "UNCLASSIFIED / 未明确分类"
        }
        "$label ($line)"
    }

    /** 输出基本环境、错误证据、操作终态及数据缺口；旧报告缺字段时明确显示unknown。 */
    fun render(id: String, environment: String, state: String, events: List<SummaryEvent>, coverage: List<String>, system: String, crash: String, invalidLines: Int): String {
        val operations = events.filter { it.operationId.isNotBlank() }.groupBy { it.operationId }
        val failures = events.filter { it.kind == "exception" || it.outcome in setOf("FAILED", "UNKNOWN") }
        return buildString {
            appendLine("Report summary / 日志排查摘要")
            appendLine("Report ID / 报告编号 = $id")
            appendLine("State / 会话状态 = $state")
            appendLine("Environment / 环境 = $environment")
            appendLine("Events / 已读取事件 = "+events.size)
            appendLine("Invalid event lines / 损坏记录行 = $invalidLines")
            appendLine()
            appendLine("Evidence availability / 证据完整性")
            coverage.forEach { appendLine("- $it") }
            appendLine()
            appendLine("System exit / 系统退出记录")
            appendLine(if (system.isBlank()) "unavailable / 未取得退出记录" else systemDescription(system))
            appendLine("Java crash stack / Java崩溃栈")
            appendLine(crash.ifBlank { "unavailable / 未取得" })
            appendLine()
            appendLine("Errors and unknown results / 异常与未知结果")
            if (crash.isNotBlank()) appendLine("Java crash evidence available / 已有Java崩溃证据，见上方调用栈")
            val systemFailure = Regex("reason=[456](?: |$)").containsMatchIn(system)
            if (systemFailure) appendLine("System failure recorded / 系统已记录崩溃或ANR，见系统退出分类")
            if (failures.isEmpty() && crash.isBlank() && !systemFailure && events.none { it.kind == "stall" }) appendLine("No explicit error event recorded / 当前记录未包含明确错误事件")
            failures.takeLast(20).forEach { appendLine("- "+timeLabel(it.time)+" "+it.operationId+" "+it.operation+" "+it.outcome+" "+it.code) }
            events.filter { it.kind == "stall" || it.kind == "stall_recovered" }.takeLast(10).forEach {
                appendLine("App-observed stall / 应用观测卡顿（非系统ANR结论） " + timeLabel(it.time) + " " + it.code + " durationMs=" + it.duration)
            }
            events.filter { it.errorStack.isNotBlank() }.takeLast(10).forEach {
                appendLine((if (it.kind == "stall") "Observed main stack / 观测主线程栈 " else "Operation stack / 操作异常栈 ") + it.operationId + " " + it.operation)
                appendLine(it.errorStack)
            }
            appendLine()
            appendLine("Operation results / 操作结果")
            operations.forEach { (key, updates) ->
                val last = updates.last()
                val terminal = last.outcome !in setOf("STARTED", "STAGE", "")
                appendLine("- $key "+last.operation+" outcome="+(if (terminal) last.outcome else "UNRECORDED / 未记录终态")+" last="+last.code+" durationMs="+last.duration+(last.count?.let { " count=$it" } ?: ""))
            }
            if (operations.isEmpty()) appendLine("No correlated operations / 缺少带编号的操作记录")
            appendLine()
            appendLine("Recent activity / 最近操作（UTC）")
            events.filter { it.kind in setOf("page", "navigation", "control", "business", "operation", "capture_gap", "exception") }.takeLast(30).forEach {
                appendLine("- "+timeLabel(it.time)+" "+it.kind+" "+it.operationId+" "+it.code)
            }
        }
    }
}
