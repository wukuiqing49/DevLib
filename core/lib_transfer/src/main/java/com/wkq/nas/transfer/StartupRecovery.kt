package com.wkq.nas.transfer

import android.content.Context
import com.wkq.nas.transfer.provider.classify
import kotlinx.coroutines.CancellationException
import com.wkq.nas.transfer.work.MutationWorker

/** 各任务族独立恢复，单个配置或调度失败不能阻断其他持久队列。 */
class StartupRecovery(context: Context) {
    private val context = context.applicationContext
    suspend fun recover() = runRecoverySteps(listOf(
        { V2Client(context).recoverSchedules() },
        { FileClient(context).recoverInterruptedOperations() },
        { TransferClient.get(context).recoverPendingTransfers() },
        { MutationWorker.schedule(context) }
    )) { error ->
        try {
            SyncDiagnostics(context).event(DiagnosticEvent.RECOVERY,
                reason = classify(error).first, outcome = DiagnosticOutcome.FAILED)
        } catch (canceled: CancellationException) { throw canceled }
        catch (_: Exception) { android.util.Log.w("NasRecovery", "RECOVERY_DIAGNOSTIC_FAILED") }
    }
}

internal suspend fun runRecoverySteps(steps: List<suspend () -> Unit>, failed: (Exception) -> Unit) {
    steps.forEach { step ->
        try { step() }
        catch (canceled: CancellationException) { throw canceled }
        catch (error: Exception) { failed(error) }
    }
}
