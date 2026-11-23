package com.wkq.logreport

/** 应用心跳观测结果，不等同于系统ANR判断。 */
internal data class StallObservation(val beat: Long, val durationMs: Long, val recovered: Boolean, val sample: Int = 1)

/** 可测试的主线程心跳状态，旧心跳回调不能清除新一轮检测。 */
internal class MainStallDetector(private val thresholdMs: Long = 3000) {
    private var generation = 0L
    private var pending: Long? = null
    private var started = 0L
    private var reported = false
    private var samples = 0

    /** 无待确认心跳时创建新编号。 */
    @Synchronized fun begin(now: Long): Long? {
        if (pending != null) return null
        pending = ++generation
        started = now
        samples = 0
        return pending
    }

    /** 首次超时及约5/10秒阶段各采一次，长时间阻塞最多保留三个现场。 */
    @Synchronized fun poll(now: Long): StallObservation? {
        val beat = pending ?: return null
        val thresholds = longArrayOf(thresholdMs, thresholdMs + 2000, thresholdMs + 7000)
        if (samples >= thresholds.size || now - started < thresholds[samples]) return null
        reported = true
        return StallObservation(beat, now - started, false, ++samples)
    }

    /** 恢复后保留阻塞总时长。 */
    @Synchronized fun acknowledge(beat: Long, now: Long): StallObservation? {
        if (pending != beat) return null
        val observation = if (reported) StallObservation(beat, now - started, true, samples) else null
        pending = null
        reported = false
        return observation
    }

    /** 后台或结束收集时撤销待确认心跳。 */
    @Synchronized fun reset() { generation++; pending = null; reported = false }
}
