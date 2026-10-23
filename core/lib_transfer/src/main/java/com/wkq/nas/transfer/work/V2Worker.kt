package com.wkq.nas.transfer.work

import android.content.Context
import androidx.work.*
import com.wkq.nas.transfer.AdvancedSyncProfile
import com.wkq.nas.transfer.V2Client
import com.wkq.nas.transfer.SyncDiagnostics
import com.wkq.nas.transfer.DiagnosticEvent
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

class V2Worker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val channel = "nas-v2"
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        val title = applicationContext.getString(com.wkq.nas.transfer.R.string.fm_tasks)
        if (android.os.Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(android.app.NotificationChannel(channel, title, android.app.NotificationManager.IMPORTANCE_LOW))
        val notification = androidx.core.app.NotificationCompat.Builder(applicationContext, channel).setContentIntent(com.wkq.nas.transfer.TaskNotificationRoute.pendingIntent(applicationContext)).setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(title).setOngoing(true).setProgress(0, 0, true).build()
        return if (android.os.Build.VERSION.SDK_INT >= 29) ForegroundInfo(4104, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(4104, notification)
    }
    override suspend fun doWork(): Result = lock.withLock { withContext(Dispatchers.IO) {
        try {
            val client = V2Client(applicationContext)
            if (inputData.getBoolean("device", false) && client.observeProfiles().first().none { it.deviceTrigger }) return@withContext Result.success()
            setForeground(getForegroundInfo())
            SyncDiagnostics(applicationContext).event(DiagnosticEvent.WORKER_START, id.toString())
            inputData.getBoolean("device", false).let { if (it) client.deviceAvailable() }
            inputData.getString("profile")?.let { client.periodic(it) }
            inputData.getString("deviceProfile")?.let { client.devicePeriodic(it) }
            inputData.getString("rule")?.let { client.periodicRule(it) }
            client.runPending()
            Result.success()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            val (code, retryable) = com.wkq.nas.transfer.provider.classify(e)
            // 错误仅持久化错误码，不记录端点、路径或认证信息。
            (inputData.getString("profile") ?: inputData.getString("deviceProfile") ?: inputData.getString("rule")?.let { "rule:$it" })?.let { id ->
                val dao = com.wkq.nas.transfer.data.TransferDatabase.get(applicationContext).dao()
                dao.saveV2(com.wkq.nas.transfer.data.V2Record("schedule-error", id,
                    org.json.JSONObject().put("error", code.name).toString()))
            }
            if (retryable) Result.retry() else Result.failure()
        }
    } }
    companion object {
        private val lock = Mutex()
        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork("nas-v2-jobs", ExistingWorkPolicy.APPEND_OR_REPLACE,
                OneTimeWorkRequestBuilder<V2Worker>().build())
        }
        fun device(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork("nas-device-check", ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<V2Worker>().setInputData(workDataOf("device" to true)).build())
        }
        fun scheduleDevice(context: Context, profile: AdvancedSyncProfile) {
            WorkManager.getInstance(context).enqueueUniqueWork("nas-v2-device-${profile.id}", ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<V2Worker>().setInputData(workDataOf("deviceProfile" to profile.id))
                    .setConstraints(Constraints.Builder().setRequiresCharging(profile.charging).setRequiresBatteryNotLow(true).build()).build())
        }
        fun cancelRule(context: Context, id: String) { WorkManager.getInstance(context).cancelUniqueWork("nas-v2-rule-$id") }
        fun scheduleRule(context: Context, rule: com.wkq.nas.transfer.ArchiveRule) {
            if (rule.intervalMinutes == 0L) { cancelRule(context, rule.id); return }
            val request = PeriodicWorkRequestBuilder<V2Worker>(rule.intervalMinutes, TimeUnit.MINUTES)
                .setInitialDelay(rule.intervalMinutes, TimeUnit.MINUTES).setInputData(workDataOf("rule" to rule.id))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED)
                    .setRequiresCharging(true).setRequiresBatteryNotLow(true).build()).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("nas-v2-rule-${rule.id}", ExistingPeriodicWorkPolicy.UPDATE, request)
        }
        fun cancelProfile(context: Context, id: String) {
            WorkManager.getInstance(context).cancelUniqueWork("nas-v2-profile-$id")
            WorkManager.getInstance(context).cancelUniqueWork("nas-v2-device-$id")
        }
        fun scheduleProfile(context: Context, p: AdvancedSyncProfile) {
            if (!p.deviceTrigger) WorkManager.getInstance(context).cancelUniqueWork("nas-v2-device-${p.id}")
            if (p.intervalMinutes == 0L) { WorkManager.getInstance(context).cancelUniqueWork("nas-v2-profile-${p.id}"); return }
            val request = PeriodicWorkRequestBuilder<V2Worker>(p.intervalMinutes, TimeUnit.MINUTES)
                .setInitialDelay(p.intervalMinutes, TimeUnit.MINUTES)
                .setInputData(workDataOf("profile" to p.id))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                    .setRequiresCharging(p.charging).setRequiresBatteryNotLow(true).build()).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("nas-v2-profile-${p.id}", ExistingPeriodicWorkPolicy.UPDATE, request)
        }
    }
}
