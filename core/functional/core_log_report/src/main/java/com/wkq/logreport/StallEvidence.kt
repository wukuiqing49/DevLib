package com.wkq.logreport

import java.io.File

/** 独立于事件队列持久化卡顿现场，最多四份；封存屏障只在IO线程等待当前写入。 */
internal class StallEvidence(private val folder: File) {
    private var closed = false
    private var serial = 0

    /** 看门狗线程直接原子落盘，不被日志队列拥塞拖延；禁止写线程名和消息。 */
    @Synchronized fun record(observation: StallObservation, text: String): Boolean {
        if (closed) return false
        val header = "formatVersion=1\nclassification=APP_OBSERVED_NOT_SYSTEM_ANR\nstallId=${observation.beat}\nsample=${observation.sample}\ndurationMs=${observation.durationMs}\nsampledAt=${System.currentTimeMillis()}\n"
        ReportFiles.atomic(File(folder, "main-stall-${serial++ % 4}.txt"), header + text.take(120 * 1024))
        return true
    }

    /** IO封存前阻止晚到写入，保证导出文件与已封存目录一致。 */
    @Synchronized fun close() { closed = true }
}

/** 采样Java线程状态和方法位置，不输出任意线程名、异常消息、对象内容或锁地址。 */
internal object ThreadSnapshot {
    /** 主线程始终优先，随后最多23个线程，每个线程最多32帧。 */
    fun capture(main: Thread): String {
        val all = Thread.getAllStackTraces()
        val selected = all.entries.sortedWith(compareBy<Map.Entry<Thread, Array<StackTraceElement>>> { if (it.key === main) 0 else 1 }
            .thenBy { if (it.key.state == Thread.State.BLOCKED) 0 else 1 }.thenBy { it.key.id }).take(24)
        return buildString {
            appendLine("threadsObserved=${all.size} threadsIncluded=${selected.size}")
            selected.forEach { (thread, stack) ->
                appendLine("threadId=${thread.id} main=${thread === main} state=${thread.state}")
                stack.take(32).forEach { frame -> appendLine(LogPrivacy.redact(frame.className + "." + frame.methodName + ":" + frame.lineNumber)) }
            }
            appendLine("Locks: owner/object not observable via this snapshot; BLOCKED does not identify the owner")
        }
    }
}
