package com.wkq.droplan.drop

import java.io.Closeable
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

data class PairingSnapshot(val id: String, val state: String, val expiresAt: Long)
data class ReceiverSnapshot(val id: String, val expiresAt: Long)
data class SharedFileSnapshot(val name: String, val size: Long?)
data class SenderTaskSnapshot(val owner: String, val name: String, val download: DownloadSnapshot)
internal data class Receiver(val id: String, val expires: Long) {
    var rateWindow = 0L
    var requests = 0
}
internal class Pairing(val id: String, val ticket: String, val expires: Long) {
    var state = "PENDING"
    var cookie: String? = null
}
internal class ProtocolFailure(val status: Int, val code: String) : RuntimeException(code)

/** 仅内存保存授权；本机审批接口不通过 HTTP 暴露。 */
class ShareEngine(
    sources: List<ShareSource>,
    val autoApprove: Boolean = false,
    val maxActiveDownloads: Int = 2,
    private val now: () -> Long = System::currentTimeMillis,
) : Closeable {
    internal val files = sources.associateBy { it.id }
    internal val entryToken = randomToken()
    private val pairings = linkedMapOf<String, Pairing>()
    private val receivers = linkedMapOf<String, Receiver>()
    private val queue = TransferQueue(maxActiveDownloads)
    private var closed = false
    private var paused = false
    private var attemptWindow = 0L
    private var attempts = 0
    private var probeOwner: String? = null
    private var probeCancel: (() -> Unit)? = null
    private var lastProbeAt = 0L

    init {
        require(sources.isNotEmpty() && sources.size <= 1000)
        require(files.size == sources.size && files.keys.all { it.matches(Regex("[A-Za-z0-9_-]{16,128}")) })
        require(sources.all { it.length == null || it.length!! >= 0 })
        require(sources.all { it.contentVersion == null || it.contentVersion!!.matches(Regex("[a-fA-F0-9]{64}")) })
        require(maxActiveDownloads in 1..2)
    }

    @Synchronized internal fun tick() {
        val time = now()
        receivers.filterValues { it.expires <= time }.keys.toList().forEach { revokeCookie(it) }
        pairings.entries.removeAll { it.value.expires <= time }
        queue.tick(time)
    }

    @Synchronized internal fun pair(token: String): Pairing {
        ensureOpen(); tick()
        if (now() - attemptWindow >= 60_000) { attemptWindow = now(); attempts = 0 }
        if (++attempts > 30) fail(429, "pairing_rate_limit")
        if (!MessageDigest.isEqual(token.toByteArray(), entryToken.toByteArray())) fail(403, "invalid_entry")
        if (pairings.size >= 32 || receivers.size >= 16) fail(429, "receiver_limit")
        val pairing = Pairing(randomToken(), randomToken(), now() + 120_000)
        pairings[pairing.id] = pairing
        if (autoApprove) approve(pairing.id)
        return pairing
    }

    @Synchronized fun pendingPairings(): List<PairingSnapshot> {
        tick()
        return pairings.values.filter { it.state == "PENDING" }.map { PairingSnapshot(it.id, it.state, it.expires) }
    }

    @Synchronized fun approve(id: String): Boolean {
        ensureOpen(); tick()
        val pair = pairings[id]?.takeIf { it.state == "PENDING" } ?: return false
        if (receivers.size >= 16) return false
        val cookie = randomToken()
        receivers[cookie] = Receiver(randomToken(), now() + 60 * 60_000)
        pair.cookie = cookie
        pair.state = "APPROVED"
        return true
    }

    @Synchronized fun reject(id: String) { pairings[id]?.takeIf { it.state == "PENDING" }?.state = "REJECTED" }

    @Synchronized internal fun pairing(id: String, ticket: String): Pairing {
        ensureOpen(); tick()
        val pair = pairings[id] ?: fail(404, "not_found")
        if (!MessageDigest.isEqual(pair.ticket.toByteArray(), ticket.toByteArray())) fail(404, "not_found")
        return pair
    }

    @Synchronized internal fun receiver(cookie: String?): Receiver {
        ensureOpen(); tick()
        val receiver = receivers[cookie] ?: fail(401, "authorization_required")
        if (now() - receiver.rateWindow >= 1000) { receiver.rateWindow = now(); receiver.requests = 0 }
        if (++receiver.requests > 60) fail(429, "request_rate_limit")
        return receiver
    }

    @Synchronized fun receivers(): List<ReceiverSnapshot> {
        tick(); return receivers.values.map { ReceiverSnapshot(it.id, it.expires) }
    }

    @Synchronized fun revoke(receiverId: String) {
        receivers.filterValues { it.id == receiverId }.keys.toList().forEach { revokeCookie(it) }
    }

    private fun revokeCookie(cookie: String) {
        val receiver = receivers.remove(cookie) ?: return
        queue.tasks.values.filter { it.owner == receiver.id }.forEach(queue::cancel)
        pairings.entries.removeAll { it.value.cookie == cookie }
        if (probeOwner == receiver.id) { probeCancel?.invoke(); probeOwner = null; probeCancel = null }
    }

    @Synchronized fun pauseNewDownloads(paused: Boolean) { this.paused = paused }

    /** 仅本机 Binder 消费；不增加接收端读取其他会话的 HTTP API。 */
    @Synchronized fun senderTasks(): List<SenderTaskSnapshot> {
        tick()
        return queue.tasks.values.map { SenderTaskSnapshot(it.owner, files[it.fileId]?.displayName.orEmpty(), queue.snapshot(it, now())) }
    }

    @Synchronized fun sharedFiles(): List<SharedFileSnapshot> = files.values.map { SharedFileSnapshot(it.displayName, it.length) }

    @Synchronized internal fun responseLength(task: DownloadTask, length: Long?) { task.responseLength = length }

    @Synchronized internal fun createTask(owner: String, fileId: String, key: String): DownloadSnapshot {
        ensureOpen(); tick()
        val source = files[fileId] ?: fail(404, "not_found")
        if (!key.matches(Regex("[A-Za-z0-9_-]{16,128}"))) fail(400, "invalid_idempotency_key")
        queue.tasks.values.firstOrNull { it.owner == owner && it.key == key }?.let {
            if (it.fileId != fileId) fail(409, "idempotency_conflict")
            return queue.snapshot(it, now())
        }
        if (paused) fail(503, "sharing_paused")
        // 新下载优先于探测，不让测速继续抢占文件传输带宽。
        probeCancel?.invoke(); probeCancel = null; probeOwner = null
        if (queue.tasks.size >= 128) {
            queue.tasks.values.firstOrNull { it.state !in TransferQueue.occupied && it.state != DownloadState.QUEUED }
                ?.let { queue.tasks.remove(it.id) }
        }
        if (queue.tasks.size >= 128 || queue.tasks.values.count { it.owner == owner &&
                (it.state in TransferQueue.occupied || it.state == DownloadState.QUEUED) } >= 16) fail(429, "queue_full")
        val task = DownloadTask(randomToken(), owner, fileId, source.contentVersion, now(), key)
        queue.tasks[task.id] = task
        queue.tick(now())
        return queue.snapshot(task, now())
    }

    @Synchronized internal fun task(owner: String, id: String): DownloadSnapshot {
        tick(); return queue.snapshot(ownedTask(owner, id), now())
    }

    @Synchronized internal fun tasks(owner: String): List<DownloadSnapshot> {
        ensureOpen(); tick()
        return queue.tasks.values.filter { it.owner == owner }.map { queue.snapshot(it, now()) }
    }

    @Synchronized internal fun cancel(owner: String, id: String) { queue.cancel(ownedTask(owner, id)); queue.tick(now()) }

    @Synchronized internal fun claim(owner: String, id: String, fileId: String, cancel: () -> Unit): DownloadTask {
        ensureOpen(); tick()
        val task = ownedTask(owner, id)
        if (task.fileId != fileId) fail(404, "not_found")
        if (files[fileId]?.contentVersion != task.version) fail(409, "source_changed")
        // 重复 GET / Range 重试同样重新竞争名额，STREAMING 不能重入。
        if (task.state in setOf(DownloadState.SENT, DownloadState.FAILED)) {
            task.state = DownloadState.QUEUED; queue.tick(now())
        }
        if (task.state != DownloadState.READY) fail(429, "download_not_ready")
        task.state = DownloadState.STREAMING
        task.bytes = 0; task.started = now(); task.cancel = cancel
        return task
    }

    @Synchronized internal fun progress(task: DownloadTask, bytes: Long) {
        if (closed || task.state != DownloadState.STREAMING) throw java.io.IOException("transfer_cancelled")
        task.bytes = bytes
    }

    @Synchronized internal fun finish(task: DownloadTask, success: Boolean) {
        task.cancel = null
        task.elapsed = now() - task.started
        if (task.state == DownloadState.STREAMING) task.state = if (success) DownloadState.SENT else DownloadState.FAILED
        queue.tick(now())
    }

    @Synchronized internal fun startProbe(owner: String, cancel: () -> Unit) {
        ensureOpen(); tick()
        if (probeOwner != null || queue.tasks.values.any { it.state in TransferQueue.occupied } ||
            (lastProbeAt != 0L && now() - lastProbeAt < 10_000)) fail(429, "probe_busy")
        lastProbeAt = now(); probeOwner = owner; probeCancel = cancel
    }
    @Synchronized internal fun finishProbe() { probeOwner = null; probeCancel = null }

    private fun ownedTask(owner: String, id: String) = queue.tasks[id]?.takeIf { it.owner == owner } ?: fail(404, "not_found")
    private fun ensureOpen() { if (closed) fail(503, "sharing_ended") }

    /** 结束所有授权与传输；单个流关闭失败不阻止其余清理，最后抛出聚合错误。 */
    @Synchronized override fun close() {
        if (closed) return
        closed = true
        var failure: Exception? = null
        queue.tasks.values.toList().forEach { task ->
            try { queue.cancel(task) } catch (error: Exception) {
                if (failure == null) failure = error else if (failure !== error) failure!!.addSuppressed(error)
            }
        }
        try { probeCancel?.invoke() } catch (error: Exception) {
            if (failure == null) failure = error else if (failure !== error) failure!!.addSuppressed(error)
        } finally { probeCancel = null; probeOwner = null }
        receivers.clear(); pairings.clear(); queue.tasks.clear()
        failure?.let { throw it }
    }

    companion object {
        fun newFileId(): String = randomToken()
        internal fun randomToken(): String = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(ByteArray(24).also(SecureRandom()::nextBytes))
        internal fun fail(status: Int, code: String): Nothing = throw ProtocolFailure(status, code)
    }
}
