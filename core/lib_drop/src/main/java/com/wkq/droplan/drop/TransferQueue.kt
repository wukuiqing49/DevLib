package com.wkq.droplan.drop

/** READY 也预留名额，防止浏览器导航前许可被其他请求抢占。 */
enum class DownloadState { QUEUED, READY, STREAMING, SENT, EXPIRED, CANCELLED, FAILED }

data class DownloadSnapshot(
    val id: String, val fileId: String, val state: DownloadState,
    val queuePosition: Int, val bytesSent: Long, val permitExpiresAt: Long,
    val bytesPerSecond: Double,
    val responseLength: Long? = null,
)

internal class DownloadTask(
    val id: String, val owner: String, val fileId: String, val version: String?,
    val created: Long, val key: String,
) {
    var state = DownloadState.QUEUED
    var expires = 0L
    var bytes = 0L
    var started = 0L
    var elapsed = 0L
    var cancel: (() -> Unit)? = null
    var responseLength: Long? = null
}

/** 所有调用均由 ShareEngine 的锁保护；按接收会话轮转，不按任务数量抢占。 */
internal class TransferQueue(private val maxActive: Int) {
    val tasks = linkedMapOf<String, DownloadTask>()
    private val turns = java.util.ArrayDeque<String>()

    fun tick(now: Long) {
        tasks.values.filter { it.state == DownloadState.READY && it.expires <= now }
            .forEach { it.state = DownloadState.EXPIRED }
        tasks.entries.removeAll { now - it.value.created > 30 * 60_000 && it.value.state !in occupied }
        val waitingOwners = tasks.values.filter { it.state in occupied || it.state == DownloadState.QUEUED }.map { it.owner }.toSet()
        turns.removeAll { it !in waitingOwners }
        waitingOwners.forEach { if (it !in turns) turns.addLast(it) }
        while (tasks.values.count { it.state in occupied } < maxActive) {
            val busy = tasks.values.filter { it.state in occupied }.map { it.owner }.toSet()
            val queued = tasks.values.filter { it.state == DownloadState.QUEUED && it.owner !in busy }
            val owners = queued.map { it.owner }.distinct()
            if (owners.isEmpty()) break
            var owner = turns.removeFirst().also { turns.addLast(it) }
            while (owner !in owners) owner = turns.removeFirst().also { turns.addLast(it) }
            val next = queued.first { it.owner == owner }
            next.state = DownloadState.READY
            next.expires = now + 30_000
        }
    }

    fun cancel(task: DownloadTask) {
        // 撤销访问只中止尚未结束的响应，不改写已发送、失败或过期的历史。
        if (task.state !in occupied && task.state != DownloadState.QUEUED) return
        task.state = DownloadState.CANCELLED
        task.cancel?.invoke()
        task.cancel = null
    }

    fun snapshot(task: DownloadTask, now: Long): DownloadSnapshot {
        val position = if (task.state == DownloadState.QUEUED)
            tasks.values.filter { it.state == DownloadState.QUEUED }.indexOf(task) + 1 else 0
        val elapsed = if (task.state == DownloadState.STREAMING) now - task.started else task.elapsed
        return DownloadSnapshot(task.id, task.fileId, task.state, position, task.bytes, task.expires,
            if (elapsed > 0) task.bytes * 1000.0 / elapsed else 0.0, task.responseLength)
    }

    companion object { val occupied = setOf(DownloadState.READY, DownloadState.STREAMING) }
}
