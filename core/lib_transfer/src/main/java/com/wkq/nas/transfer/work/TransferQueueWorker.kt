package com.wkq.nas.transfer.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.wkq.nas.transfer.*
import com.wkq.nas.transfer.R
import com.wkq.nas.transfer.data.TransferDatabase
import com.wkq.nas.transfer.provider.ProviderFactory
import com.wkq.nas.transfer.provider.classify
import com.wkq.nas.transfer.provider.withWorkerProviders
import com.wkq.nas.transfer.provider.usingProviderPair
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Room 持有队列，WorkManager 持有执行约束和退避；单文件失败不会使后续队列失效。 */
class TransferQueueWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val dao = TransferDatabase.get(context).dao()
    private val providers = ProviderFactory(context)
    private val network = inputData.getBoolean(NETWORK_KEY, true)
    private val notifications = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    override suspend fun getForegroundInfo(): ForegroundInfo = foreground(0)

    override suspend fun doWork(): Result = queueLocks[if (network) 1 else 0].withLock {
        if (TransferPreferences(applicationContext).parallelQueues) consumeQueue()
        else queueLock.withLock { consumeQueue() }
    }
    private suspend fun consumeQueue(): Result {
        TransferQueueSignals.start(network)
        try { return runQueue() }
        finally {
            withContext(NonCancellable + Dispatchers.IO) {
                // 与signal同一握手；退出边界入队要么被当前消费者看到，要么留下持久后继。
                if (TransferQueueSignals.finish(network) && (dao.activeCount(network) > 0 || dao.pendingCleanupCount(network) > 0))
                    TransferScheduler.enqueue(applicationContext, network)
            }
        }
    }
    private suspend fun runQueue(): Result =
        withWorkerProviders(providers::open) {
            val diagnostics = SyncDiagnostics(applicationContext)
            diagnostics.event(DiagnosticEvent.WORKER_START, id.toString())
            dao.recoverInterrupted(network)
            if (dao.activeCount(network) == 0 && dao.pendingCleanupCount(network) == 0) return@withWorkerProviders Result.success()
            if (!networkAllowed()) { diagnostics.event(DiagnosticEvent.WAITING, id.toString(), reason = TransferError.NETWORK); return@withWorkerProviders Result.retry() }
            try { setForeground(foreground(0)) }
            catch (error: Exception) {
                if (error is CancellationException) throw error
                return@withWorkerProviders Result.retry()
            }
            val cleanup = TransferArtifactCleanup(dao, providers::open)
            val started = SystemClock.elapsedRealtime()
            val job = currentCoroutineContext()
            while (SystemClock.elapsedRealtime() - started < 8 * 60 * 1000) {
                job.ensureActive()
                if (!networkAllowed()) break
                val revision = TransferQueueSignals.revision(network)
                val pending = dao.next(System.currentTimeMillis(), network)
                if (pending == null) {
                    if (dao.activeCount(network) == 0 && TransferQueueSignals.hasProducer(network)) {
                        TransferQueueSignals.awaitChange(network, revision)
                        continue
                    }
                    break
                }
                // 未挂载或撤销授权不消耗文件传输的失败重试次数。
                val external = listOf(pending.sourceId, pending.targetId).distinct().filter { dao.connection(it)?.protocol == "SAF" }
                var unavailable: TransferError? = null
                for (endpoint in external) {
                    val probe = StorageProbe(applicationContext).check(endpoint)
                    unavailable = probe.error ?: if (endpoint == pending.targetId && probe.readOnly) TransferError.PERMISSION else null
                    if (unavailable != null) break
                }
                if (unavailable != null) {
                    dao.waitForStorage(pending.id, requireNotNull(unavailable).name, System.currentTimeMillis() + 60000)
                    diagnostics.event(DiagnosticEvent.WAITING, pending.batchId, reason = unavailable)
                    continue
                }
                if (dao.claim(pending.id) == 0) continue
                val task = checkNotNull(dao.task(pending.id))
                diagnostics.event(DiagnosticEvent.FILE_START, task.batchId, endpoint = task.sourceId, file = task.sourcePath, bytes = task.expectedSize, target = task.targetId, attempt = task.attempts)
                var lastControlCheck = 0L
                var lastProgress = 0L
                var lastPercent = -1
                fun checkControl() {
                    job.ensureActive()
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastControlCheck >= 250) {
                        lastControlCheck = now
                        if (!networkAllowed()) throw NetworkPolicyChanged()
                        val state = runBlocking { dao.task(task.id)?.state }
                        if (state !in setOf("RUNNING", "COMMITTING")) throw TaskStopped()
                    }
                }
                try {
                    val source = dao.connection(task.sourceId) ?: throw TransferException(TransferError.NOT_FOUND)
                    val target = dao.connection(task.targetId) ?: throw TransferException(TransferError.NOT_FOUND)
                    val state = usingProviderPair(source, target, providers::open) { from, to ->
                        TransferExecutor().execute(task, from, to, ::checkControl,
                            progress = { bytes ->
                                val now = SystemClock.elapsedRealtime()
                                if (now - lastProgress >= 250 || bytes == task.expectedSize) {
                                    lastProgress = now
                                    runBlocking { dao.progress(task.id, bytes) }
                                    val percent = if (task.expectedSize > 0) (bytes.toDouble() / task.expectedSize * 100).toInt().coerceIn(0, 100) else 0
                                    if (percent != lastPercent) {
                                        lastPercent = percent
                                        if (Build.VERSION.SDK_INT < 33 || androidx.core.content.ContextCompat.checkSelfPermission(applicationContext,
                                                android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
                                            notifications.notify(notificationId(), foreground(percent).notification)
                                    }
                                }
                            }, chooseTarget = { path ->
                                if (runBlocking { dao.target(task.id, path) } == 0) throw TaskStopped()
                            }, beginCommit = { digest ->
                                diagnostics.event(DiagnosticEvent.COMMIT, task.batchId, file = task.sourcePath, detail = true)
                                job.ensureActive()
                                if (runBlocking { dao.beginCommit(task.id, digest) } == 0) throw TaskStopped()
                            }, verifyContent = task.verifyContent, prepareBackup = { digest ->
                                if (runBlocking { dao.backupDigest(task.id, digest) } == 0) throw TaskStopped()
                            })
                    }
                    dao.finish(task.id, state.name)
                    diagnostics.event(DiagnosticEvent.FILE_END, task.batchId, file = task.sourcePath, bytes = dao.task(task.id)?.bytesTransferred ?: -1,
                        outcome = DiagnosticOutcome.valueOf(state.name), verifyRequested = task.verifyContent)
                } catch (_: NetworkPolicyChanged) {
                    dao.deferInterrupted(task.id)
                    break
                } catch (error: CancellationException) {
                    withContext(NonCancellable) { dao.deferInterrupted(task.id) }
                    throw error
                } catch (_: TaskStopped) {
                    // 暂停/取消已通过条件更新落库，不能被传输错误回写覆盖。
                } catch (error: Exception) {
                    val (code, retryable) = classify(error)
                    val missingStorage = external.map { StorageProbe(applicationContext).check(it) }.firstOrNull { it.error != null }
                    if (missingStorage != null) {
                        dao.interruptedStorage(task.id, requireNotNull(missingStorage.error).name, System.currentTimeMillis() + 60000)
                        diagnostics.event(DiagnosticEvent.WAITING, task.batchId, reason = missingStorage.error)
                        continue
                    }
                    diagnostics.event(DiagnosticEvent.FILE_END, task.batchId, file = task.sourcePath, reason = code, outcome = DiagnosticOutcome.FAILED)
                    val retry = retryable && task.attempts < MAX_ATTEMPTS
                    val delay = (10000L * (1L shl (task.attempts - 1).coerceIn(0, 8))).coerceAtMost(15 * 60 * 1000L)
                    dao.finish(task.id, if (retry) "RETRY_WAIT" else "FAILED", code.name, if (retry) System.currentTimeMillis() + delay else 0)
                }
            }
            cleanup.run(network, ::networkAllowed)
            if (dao.activeCount(network) > 0 || dao.pendingCleanupCount(network) > 0) Result.retry() else Result.success()
        }

    private class NetworkPolicyChanged : Exception()
    private fun notificationId() = if (network) 4101 else 4102
    private fun networkAllowed(): Boolean = !network || TransferConnectivity.current(applicationContext)
        .allowed(TransferPreferences(applicationContext).allowMetered)
    private fun foreground(progress: Int): ForegroundInfo {
        if (Build.VERSION.SDK_INT >= 26) notifications.createNotificationChannel(NotificationChannel(
            CHANNEL, applicationContext.getString(R.string.transfer_channel_name), NotificationManager.IMPORTANCE_LOW))
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setContentIntent(TaskNotificationRoute.pendingIntent(applicationContext)).setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(applicationContext.getString(R.string.transfer_notification_title))
            .setContentText(applicationContext.getString(R.string.transfer_notification_progress, progress))
            .setProgress(100, progress, false).setOngoing(true).build()
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(notificationId(), notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(notificationId(), notification)
    }
    companion object {
        internal const val NETWORK_KEY = "network"
        private const val CHANNEL = "nas-file-transfers"
        private const val MAX_ATTEMPTS = 5
        private val queueLock = Mutex()
        private val queueLocks = arrayOf(Mutex(), Mutex())
    }
}
