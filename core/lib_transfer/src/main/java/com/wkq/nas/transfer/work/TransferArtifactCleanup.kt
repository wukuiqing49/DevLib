package com.wkq.nas.transfer.work

import com.wkq.nas.transfer.*
import com.wkq.nas.transfer.data.*
import com.wkq.nas.transfer.provider.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CancellationException

/** 仅由持有队列锁的消费者调用；写入者退出后才允许删除该任务的精确 part 路径。 */
internal class TransferArtifactCleanup(private val dao: TransferDao,
    private val open: (ConnectionEntity) -> StorageProvider) {
    suspend fun run(network: Boolean, allowed: () -> Boolean = { true }) {
        val started = android.os.SystemClock.elapsedRealtime()
        for (candidate in dao.canceledCleanupTasks(network)) {
            currentCoroutineContext().ensureActive()
            if (!allowed() || android.os.SystemClock.elapsedRealtime() - started > 15000) break
            dao.saveV2(V2Record("transfer-cleanup-attempt", candidate.id, System.currentTimeMillis().toString()))
            try {
                val task = dao.task(candidate.id) ?: continue
                if (task.state != "CANCELED") continue
                require(task.id.matches(Regex("[a-zA-Z0-9_-]{1,64}")))
                val target = dao.connection(task.targetId) ?: throw TransferException(TransferError.NOT_FOUND)
                val part = TransferPaths.join(TransferPaths.parent(task.targetPath), ".nas-${task.id}.part")
                open(target).use { provider ->
                    val info = provider.stat(part)
                    if (info?.directory == true) throw TransferException(TransferError.VERIFICATION)
                    if (info != null) {
                        currentCoroutineContext().ensureActive()
                        if (!allowed()) throw TransferException(TransferError.NETWORK, true)
                        provider.delete(part)
                    }
                    if (provider.stat(part) != null) throw TransferException(TransferError.VERIFICATION)
                }
                dao.saveV2(V2Record("transfer-cleanup", task.id, "DONE"))
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                dao.saveV2(V2Record("transfer-cleanup", candidate.id, classify(error).first.name))
            }
        }
    }
}
