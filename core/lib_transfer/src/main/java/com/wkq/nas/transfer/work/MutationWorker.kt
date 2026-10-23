package com.wkq.nas.transfer.work

import android.content.Context
import androidx.work.*
import com.wkq.nas.transfer.*
import com.wkq.nas.transfer.data.TransferDatabase
import com.wkq.nas.transfer.provider.classify
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/** 预览已落库才调度。中断的破坏性操作标失败，绝不自动重放。 */
class MutationWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val channel = "nas-file-operations"
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        val title = applicationContext.getString(com.wkq.nas.transfer.R.string.fm_tasks)
        if (android.os.Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(android.app.NotificationChannel(channel, title, android.app.NotificationManager.IMPORTANCE_LOW))
        val notification = androidx.core.app.NotificationCompat.Builder(applicationContext, channel)
            .setContentIntent(com.wkq.nas.transfer.TaskNotificationRoute.pendingIntent(applicationContext)).setSmallIcon(android.R.drawable.stat_sys_upload).setContentTitle(title).setOngoing(true).setProgress(0, 0, true).build()
        return if (android.os.Build.VERSION.SDK_INT >= 29)
            ForegroundInfo(4103, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(4103, notification)
    }
    override suspend fun doWork(): Result = lock.withLock { withContext(Dispatchers.IO) {
        val dao = TransferDatabase.get(applicationContext).dao()
        dao.recoverMutationJobs()
        val client = FileClient(applicationContext)
        client.recoverInterruptedOperations()
        if (dao.nextMutationHead() == null) return@withContext Result.success()
        try { setForeground(getForegroundInfo()) }
        catch (error: Exception) { if (error is CancellationException) throw error; return@withContext Result.retry() }
        while (true) {
            currentCoroutineContext().ensureActive()
            val job = dao.nextMutationHead() ?: break
            if (dao.claimMutationJob(job.id) == 0) continue
            try {
                val loaded = dao.mutationJob(job.id) ?: throw TransferException(TransferError.NOT_FOUND)
                val results = client.execute(MutationCodec.decode(loaded.payload), job.id)
                dao.finishMutationJob(job.id, if (results.any { it.state == "FAILED" }) "FAILED" else "SUCCEEDED",
                    results.firstOrNull { it.error != null }?.error?.name)
            } catch (error: Exception) {
                withContext(NonCancellable) { dao.finishMutationJob(job.id, "FAILED", classify(error).first.name) }
                if (error is CancellationException) throw error
            }
        }
        Result.success()
    } }
    companion object {
        private val lock = Mutex()
        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork("nas-file-operations", ExistingWorkPolicy.APPEND_OR_REPLACE,
                OneTimeWorkRequestBuilder<MutationWorker>().build())
        }
    }
}

internal object MutationCodec {
    fun encode(preview: MutationPreview): String = JSONObject().apply {
        put("connection", preview.connectionId); put("kind", preview.kind)
        put("items", JSONArray().apply { preview.items.forEach { item -> put(JSONObject().apply {
            put("path", item.source.path); put("directory", item.source.directory); put("size", item.source.size)
            put("modified", item.source.modifiedAt); put("target", item.targetPath); put("conflict", item.conflict)
        }) } })
    }.toString()
    fun decode(payload: String): MutationPreview {
        val value = JSONObject(payload)
        val items = value.getJSONArray("items")
        return MutationPreview(value.getString("connection"), value.getString("kind"), (0 until items.length()).map { index ->
            val item = items.getJSONObject(index)
            MutationItem(FileMetadata(item.getString("path"), item.getBoolean("directory"), item.getLong("size"), item.getLong("modified")),
                item.getString("target"), item.getBoolean("conflict"))
        })
    }
}
