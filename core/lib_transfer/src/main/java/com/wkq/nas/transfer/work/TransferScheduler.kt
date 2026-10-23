package com.wkq.nas.transfer.work

import android.content.Context
import androidx.work.*
import java.util.concurrent.TimeUnit

internal object TransferScheduler {
    private fun request(context: Context, network: Boolean) = OneTimeWorkRequestBuilder<TransferQueueWorker>()
            .setInputData(workDataOf(TransferQueueWorker.NETWORK_KEY to network))
            // WorkManager CONNECTED 要求互联网验证，会阻塞可用的离线局域网。
            // 实际网络和计费策略由 Worker 在认领文件前、每个文件之间检查。
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.NOT_REQUIRED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .addTag("nas-transfer-queue").build()
    suspend fun enqueue(context: Context, network: Boolean) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        kotlinx.coroutines.currentCoroutineContext()[TransferQueueProducer]?.register(network)
        if (!TransferQueueSignals.signal(network)) return@withContext
        // 没有活跃消费者时持久追加；入库后进程退出仍由启动恢复补调度。
        val manager = WorkManager.getInstance(context.applicationContext)
        val name = "nas-transfer-${if (network) "network" else "local"}"
        // 升级时旧网络约束仍保存在 WorkManager 中，会阻塞追加链；仅替换这种旧链。
        val legacy = manager.getWorkInfosForUniqueWork(name).get().any {
            !it.state.isFinished && it.constraints.requiredNetworkType != NetworkType.NOT_REQUIRED
        }
        manager.enqueueUniqueWork(name, if (legacy) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.APPEND_OR_REPLACE,
            request(context, network)).result.get()
        Unit
    }
    suspend fun restartNetwork(context: Context) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork("nas-transfer-network", ExistingWorkPolicy.REPLACE, request(context, true)).result.get()
        Unit
    }
    /** 独立唤醒工作绕过原队列的退避等待，执行仍受共享队列锁保护。 */
    suspend fun wakeStorage(context: Context, network: Boolean) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork("nas-storage-wakeup-${if (network) "network" else "local"}",
            ExistingWorkPolicy.REPLACE, request(context, network)).result.get()
        Unit
    }
}
