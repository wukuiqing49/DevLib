package com.wkq.logreport

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** 只在前台采集期间检查主线程，异常信息明确标记为应用观测卡顿。 */
internal class MainStallWatchdog(private val enabled: () -> Boolean, private val record: (StallObservation, String) -> Unit) : Closeable {
    private val detector = MainStallDetector()
    private val main = Handler(Looper.getMainLooper())
    private val timer = Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "LogReport-heartbeat").apply { isDaemon = true } }

    init { timer.scheduleWithFixedDelay({ runCatching { tick() } }, 500, 500, TimeUnit.MILLISECONDS) }

    /** 后台不采样，阻塞期间分阶段记录有界栈帧及主动现场。 */
    private fun tick() {
        if (!enabled()) { detector.reset(); return }
        val now = SystemClock.elapsedRealtime()
        detector.begin(now)?.let { beat ->
            main.post { detector.acknowledge(beat, SystemClock.elapsedRealtime())?.let { runCatching { record(it, "") } } }
        }
        detector.poll(now)?.let { observation ->
            val frames = Looper.getMainLooper().thread.stackTrace.take(48).joinToString("\n") { it.className + "." + it.methodName + ":" + it.lineNumber }
            record(observation, frames)
        }
    }

    /** 结束采集立即停止定时采样，不等待主线程恢复。 */
    override fun close() { detector.reset(); timer.shutdownNow() }
}
