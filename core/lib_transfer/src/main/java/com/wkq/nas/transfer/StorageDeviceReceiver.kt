package com.wkq.nas.transfer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.*

/** 广播仅作探测信号；授权身份与目录实际访问仍由任务核验。 */
class StorageDeviceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action in setOf(Intent.ACTION_MEDIA_MOUNTED, Intent.ACTION_MEDIA_UNMOUNTED, Intent.ACTION_MEDIA_REMOVED, Intent.ACTION_MEDIA_BAD_REMOVAL)) {
            com.wkq.nas.transfer.work.V2Worker.device(context)
            val pending = goAsync()
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                try { withTimeout(8000) { TransferClient.get(context.applicationContext).storageChanged() } }
                catch (_: Exception) { /* 队列已持久化，下次启动或挂载信号继续恢复。 */ }
                finally { pending.finish() }
            }
        }
    }
}
