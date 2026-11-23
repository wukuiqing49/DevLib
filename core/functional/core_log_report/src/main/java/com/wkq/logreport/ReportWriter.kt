package com.wkq.logreport

import java.io.Closeable
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** 有界事件队列及独立控制队列；调用线程不等待磁盘或队列空位。 */
internal class ReportWriter(capacity: Int = 512) : Closeable {
    private val worker = ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, ArrayBlockingQueue(capacity), { task ->
        Thread(task, "LogReport-writer").apply { isDaemon = true }
    }, ThreadPoolExecutor.AbortPolicy()).apply { prestartCoreThread() }
    private val controls = Executors.newSingleThreadExecutor { task -> Thread(task, "LogReport-control").apply { isDaemon = true } }

    /** 满队列立即返回false，由所属会话记录丢失。 */
    fun offer(action: () -> Unit, failed: (Throwable) -> Unit): Boolean = try {
        worker.execute { execute(action, failed) }
        true
    } catch (_: java.util.concurrent.RejectedExecutionException) { false }

    /** 可靠地插入停止或导出屏障；等待空位仅发生于独立控制线程。 */
    fun control(action: () -> Unit, failed: (Throwable) -> Unit = {}) {
        try {
            controls.execute {
                try {
                    check(!worker.isShutdown)
                    worker.queue.put(Runnable { execute(action, failed) })
                } catch (error: Exception) { runCatching { failed(error) } }
            }
        } catch (error: java.util.concurrent.RejectedExecutionException) { runCatching { failed(error) } }
    }

    /** 控制动作抛错仍通知调用方，后续队列可以继续处理。 */
    private fun execute(action: () -> Unit, failed: (Throwable) -> Unit) {
        try { action() } catch (error: Throwable) { runCatching { failed(error) } }
    }

    /** 测试或宿主显式关闭时终止后台队列。 */
    override fun close() { controls.shutdownNow(); worker.shutdownNow() }
}
