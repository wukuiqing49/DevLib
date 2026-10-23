package com.wkq.nas.transfer

import android.content.Context
import androidx.room.withTransaction
import com.wkq.nas.transfer.data.TransferDatabase
import com.wkq.nas.transfer.data.V2Record
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn

data class HiddenTaskRecord(val key: String, val name: String, val source: String)

/** 移除展示记录但保留检查点和替换备份，历史管理不能破坏恢复能力。 */
class TaskHistory(context: Context) {
    private val db = TransferDatabase.get(context.applicationContext)
    private val dao = db.dao()
    fun observeHidden() = dao.observeV2("hidden-task").map { rows -> rows.map { it.id }.toSet() }
    /** 隐藏只影响展示；恢复显示不执行文件操作，也不重新运行任务。 */
    suspend fun restore(keys: Set<String>) = db.withTransaction {
        require(keys.isNotEmpty() && keys.size <= 10000)
        require(keys.all { it.substringBefore(':') in setOf("transfer", "operation", "v2") && it.substringAfter(':').isNotBlank() })
        keys.forEach { dao.deleteV2("hidden-task", it) }
    }
    fun observeHiddenRecords(limit: Int = 200): kotlinx.coroutines.flow.Flow<List<HiddenTaskRecord>> {
        require(limit in 1..10000)
        return dao.observeHiddenRecordHeads(limit).map { rows ->
        rows.map { row ->
            val id = row.id.substringAfter(':')
            val first = dao.firstTaskInBatch(id)
            val operation = if (first == null) dao.firstOperationInBatch(id) else null
            val status = dao.v2Record("job-status", id)?.let { JSONObject(it.payload) }
            HiddenTaskRecord(row.id, dao.v2Record("batch-label", id)?.payload
                ?: status?.optString("name").orEmpty(), first?.sourcePath ?: operation?.sourcePath.orEmpty())
        }
    }.flowOn(Dispatchers.IO)
    }
    suspend fun hide(keys: Set<String>) = db.withTransaction {
        require(keys.isNotEmpty() && keys.size <= 10000)
        keys.forEach { key ->
            val family = key.substringBefore(':'); val id = key.substringAfter(':')
            require(id.isNotBlank() && family in setOf("transfer", "v2", "operation"))
            if (family == "operation" && dao.batchHasActiveOperations(id))
                throw TransferException(TransferError.CONFLICT)
            if (dao.batchHasUnfinishedTasks(id))
                throw TransferException(TransferError.CONFLICT)
            val job = dao.v2Record("job-status", id)?.let { JSONObject(it.payload) }
            if (job?.optString("state") in setOf("QUEUED", "RUNNING") ||
                dao.mutationJob(id)?.state in setOf("QUEUED", "RUNNING")) throw TransferException(TransferError.CONFLICT)
            if (family == "v2" && job == null || family == "transfer" && !dao.batchHasTasks(id))
                throw TransferException(TransferError.NOT_FOUND)
        }
        keys.forEach { dao.saveV2(V2Record("hidden-task", it, "{}")) }
    }
    internal fun groups(limit: Int) = combine(dao.observeUserTaskGroups(limit), observeHidden()) { rows, hidden ->
        rows.filter { "${it.family}:${it.id}" !in hidden && "v2:${it.id}" !in hidden }
    }
}
