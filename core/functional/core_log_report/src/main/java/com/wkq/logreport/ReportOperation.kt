package com.wkq.logreport

import java.util.concurrent.atomic.AtomicBoolean

/** 一次业务操作的结构化更新；字段只允许稳定标识、状态和数量。 */
data class OperationUpdate(val id: String, val name: String, val stage: String, val outcome: String, val elapsedMs: Long, val count: Int?, val errorStack: String? = null)

/** 可跨协程传递的操作记录；终态仅提交一次，日志故障不改变业务执行。 */
class ReportOperation internal constructor(
    val id: String,
    private val name: String,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val emit: (OperationUpdate) -> Unit,
) {
    companion object {
        /** 无活动采集或平台不可用时返回空记录器，不影响业务。 */
        fun disabled(): ReportOperation = ReportOperation("", "disabled", emit = {})
    }

    private val started = clock()
    private val ended = AtomicBoolean()

    init { publish("requested", "STARTED", null) }

    /** 记录实际到达的中间阶段，不将请求应答推定为最终成功。 */
    @Synchronized fun stage(stage: String, count: Int? = null) {
        if (!ended.get()) publish(stage, "STAGE", count)
    }

    /** 将脱敏原因链绑定到本次操作，避免并行任务之间混淆异常。 */
    @Synchronized fun error(failure: Throwable) {
        if (!ended.get()) publish("exception." + failure.javaClass.simpleName, "STAGE", null, CrashStack.render(failure))
    }

    /** 完成、跳过、取消、失败与未知使用独立终态；拒绝重复收尾。 */
    @Synchronized fun finish(outcome: String, reason: String, count: Int? = null) {
        if (outcome !in setOf("SUCCEEDED", "SKIPPED", "CANCELLED", "FAILED", "UNKNOWN")) return
        if (ended.compareAndSet(false, true)) publish(reason, outcome, count)
    }

    /** 使用源码标识构造更新，防止记录器异常传播到业务调用。 */
    private fun publish(stage: String, outcome: String, count: Int?, errorStack: String? = null) {
        runCatching { emit(OperationUpdate(id, name, stage, outcome, (clock() - started).coerceAtLeast(0), count, errorStack)) }
    }
}
