package com.wkq.nas.transfer.data

import android.content.Context
import androidx.room.*
import com.wkq.nas.transfer.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Entity(tableName = "connections")
internal data class ConnectionEntity(@PrimaryKey val id: String, val protocol: String, val root: String,
    val username: String, val passwordEncrypted: String, val hostKeySha256: String,
    @ColumnInfo(defaultValue = "''") val displayName: String = "")

@Entity(tableName = "file_operations")
internal data class OperationEntity(@PrimaryKey val id: String, val batchId: String, val connectionId: String,
    val kind: String, val sourcePath: String, val targetPath: String, val state: String,
    val error: String?, val createdAt: Long) {
    fun snapshot() = FileOperation(id, batchId, connectionId, kind, sourcePath, targetPath, state,
        error?.let(TransferError::valueOf), createdAt)
}

@Entity(tableName = "transfer_tasks", indices = [Index("state"), Index("batchId")])
internal data class TaskEntity(@PrimaryKey val id: String, val batchId: String, val kind: String,
    val sourceId: String, val sourcePath: String, val targetId: String, val targetPath: String,
    val requestedTargetPath: String, val conflictPolicy: String, val expectedSize: Long,
    val expectedModifiedAt: Long, val networkRequired: Boolean, val state: String = "QUEUED", val bytesTransferred: Long = 0,
    val attempts: Int = 0, val retryAt: Long = 0, val digest: String = "", val error: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "0") val verifyContent: Boolean = false,
    @ColumnInfo(defaultValue = "-2") val targetExpectedSize: Long = -2,
    @ColumnInfo(defaultValue = "-1") val targetExpectedModified: Long = -1,
    @ColumnInfo(defaultValue = "''") val backupDigest: String = "") {
    fun snapshot() = TaskSnapshot(id, batchId, TransferKind.valueOf(kind), TaskState.valueOf(state),
        sourceId, sourcePath, targetId, targetPath, bytesTransferred, expectedSize, attempts,
        error?.let(TransferError::valueOf))
}

@Entity(tableName = "v2_records", primaryKeys = ["kind", "id"])
internal data class V2Record(val kind: String, val id: String, val payload: String)

/** 只投影有界前缀，兼容旧版单行大JSON而不撑满CursorWindow。 */
internal data class V2RecordHead(val kind: String, val id: String, val payload: String, val characters: Int)

@Dao
internal interface TransferDao {
    @Query("""SELECT COUNT(*) AS total,
        COALESCE(SUM(CASE WHEN state='SUCCEEDED' THEN 1 ELSE 0 END),0) AS succeeded,
        COALESCE(SUM(CASE WHEN state='SKIPPED' THEN 1 ELSE 0 END),0) AS skipped,
        COALESCE(SUM(CASE WHEN state='FAILED' THEN 1 ELSE 0 END),0) AS failed,
        COALESCE(SUM(CASE WHEN state='CANCELED' THEN 1 ELSE 0 END),0) AS canceled,
        COALESCE(SUM(bytesTransferred),0) AS transferredBytes
        FROM transfer_tasks WHERE batchId=:id""")
    fun observeBatchOutcome(id: String): Flow<BatchOutcome>

    @Query("""
        SELECT * FROM (
          SELECT batchId AS id, 'transfer' AS family, MIN(kind) AS kind, COUNT(*) AS total,
            SUM(CASE WHEN state IN ('SUCCEEDED','SKIPPED') THEN 1 ELSE 0 END) AS completed,
            SUM(CASE WHEN state='FAILED' THEN 1 ELSE 0 END) AS failed,
            SUM(CASE WHEN state IN ('QUEUED','RUNNING','COMMITTING','RETRY_WAIT','PAUSED') THEN 1 ELSE 0 END) AS active,
            SUM(CASE WHEN state='CANCELED' THEN 1 ELSE 0 END) AS canceled,
            SUM(bytesTransferred) AS bytes, SUM(CASE WHEN expectedSize>0 THEN expectedSize ELSE 0 END) AS totalBytes, MAX(createdAt) AS createdAt, MIN(sourceId) AS sourceId, MIN(targetId) AS targetId, MIN(sourcePath) AS sourcePath, MIN(targetPath) AS targetPath, SUM(CASE WHEN state='PAUSED' THEN 1 ELSE 0 END) AS paused
          FROM transfer_tasks WHERE batchId IN (
            SELECT batchId FROM transfer_tasks GROUP BY batchId HAVING NOT EXISTS (SELECT 1 FROM v2_records j WHERE j.kind='job-status' AND j.id=transfer_tasks.batchId) AND NOT EXISTS (SELECT 1 FROM v2_records h WHERE h.kind='hidden-task' AND (h.id='transfer:' || transfer_tasks.batchId OR h.id='v2:' || transfer_tasks.batchId))
            ORDER BY MAX(createdAt) DESC LIMIT :limit) AND NOT EXISTS (SELECT 1 FROM v2_records v WHERE v.kind='job-status' AND v.id=transfer_tasks.batchId) GROUP BY batchId
          UNION ALL
          SELECT batchId, 'operation', MIN(kind), COUNT(*), SUM(CASE WHEN state='SUCCEEDED' THEN 1 ELSE 0 END),
            SUM(CASE WHEN state='FAILED' THEN 1 ELSE 0 END), SUM(CASE WHEN state='RUNNING' THEN 1 ELSE 0 END), 0, 0, 0, MAX(createdAt), MIN(connectionId), MIN(connectionId), MIN(sourcePath), MIN(targetPath), 0
          FROM file_operations WHERE batchId IN (
            SELECT batchId FROM file_operations GROUP BY batchId HAVING NOT EXISTS (SELECT 1 FROM mutation_jobs j WHERE j.id=file_operations.batchId) AND NOT EXISTS (SELECT 1 FROM v2_records h WHERE h.kind='hidden-task' AND h.id='operation:' || file_operations.batchId)
            ORDER BY MAX(createdAt) DESC LIMIT :limit) AND NOT EXISTS (SELECT 1 FROM mutation_jobs j WHERE j.id=file_operations.batchId) GROUP BY batchId
          UNION ALL
          SELECT j.id, 'operation', COALESCE(MIN(o.kind),'MUTATION'), COUNT(o.id), SUM(CASE WHEN o.state='SUCCEEDED' THEN 1 ELSE 0 END),
            CASE WHEN j.state='FAILED' THEN MAX(1,SUM(CASE WHEN o.state='FAILED' THEN 1 ELSE 0 END)) ELSE SUM(CASE WHEN o.state='FAILED' THEN 1 ELSE 0 END) END,
            CASE WHEN j.state IN ('QUEUED','RUNNING') THEN 1 ELSE 0 END, 0, 0, 0, COALESCE(MAX(o.createdAt),0), j.connectionId, j.connectionId, COALESCE(MIN(o.sourcePath),''), COALESCE(MIN(o.targetPath),''), 0
          FROM mutation_jobs j LEFT JOIN file_operations o ON o.batchId=j.id
          WHERE j.id IN (SELECT m.id FROM mutation_jobs m LEFT JOIN file_operations f ON f.batchId=m.id WHERE NOT EXISTS (SELECT 1 FROM v2_records h WHERE h.kind='hidden-task' AND h.id='operation:' || m.id) GROUP BY m.id HAVING m.state!='SUCCEEDED' OR COUNT(f.id)>0 ORDER BY COALESCE(MAX(f.createdAt),0) DESC LIMIT :limit)
          GROUP BY j.id HAVING j.state!='SUCCEEDED' OR COUNT(o.id)>0
         ) AS history WHERE NOT EXISTS (SELECT 1 FROM v2_records hidden WHERE hidden.kind='hidden-task'
          AND (hidden.id=history.family || ':' || history.id OR hidden.id='v2:' || history.id))
          ORDER BY createdAt DESC LIMIT :limit
    """)
    fun observeUserTaskGroups(limit: Int): Flow<List<UserTaskGroup>>
    @Query("""
          SELECT batchId AS id, 'transfer' AS family, MIN(kind) AS kind, COUNT(*) AS total,
            SUM(CASE WHEN state IN ('SUCCEEDED','SKIPPED') THEN 1 ELSE 0 END) AS completed,
            SUM(CASE WHEN state='FAILED' THEN 1 ELSE 0 END) AS failed,
            SUM(CASE WHEN state IN ('QUEUED','RUNNING','COMMITTING','RETRY_WAIT','PAUSED') THEN 1 ELSE 0 END) AS active,
            SUM(CASE WHEN state='CANCELED' THEN 1 ELSE 0 END) AS canceled,
            SUM(bytesTransferred) AS bytes, SUM(CASE WHEN expectedSize>0 THEN expectedSize ELSE 0 END) AS totalBytes, MAX(createdAt) AS createdAt, MIN(sourceId) AS sourceId, MIN(targetId) AS targetId, MIN(sourcePath) AS sourcePath, MIN(targetPath) AS targetPath, SUM(CASE WHEN state='PAUSED' THEN 1 ELSE 0 END) AS paused
          FROM transfer_tasks GROUP BY batchId
    """)
    fun observeTransferGroups(): Flow<List<UserTaskGroup>>
    @Query("""
          SELECT batchId AS id, 'transfer' AS family, MIN(kind) AS kind, COUNT(*) AS total,
            SUM(CASE WHEN state IN ('SUCCEEDED','SKIPPED') THEN 1 ELSE 0 END) AS completed,
            SUM(CASE WHEN state='FAILED' THEN 1 ELSE 0 END) AS failed,
            SUM(CASE WHEN state IN ('QUEUED','RUNNING','COMMITTING','RETRY_WAIT','PAUSED') THEN 1 ELSE 0 END) AS active,
            SUM(CASE WHEN state='CANCELED' THEN 1 ELSE 0 END) AS canceled,
            SUM(bytesTransferred) AS bytes, SUM(CASE WHEN expectedSize>0 THEN expectedSize ELSE 0 END) AS totalBytes, MAX(createdAt) AS createdAt, MIN(sourceId) AS sourceId, MIN(targetId) AS targetId, MIN(sourcePath) AS sourcePath, MIN(targetPath) AS targetPath, SUM(CASE WHEN state='PAUSED' THEN 1 ELSE 0 END) AS paused
          FROM transfer_tasks WHERE batchId IN (:ids) GROUP BY batchId
    """)
    fun observeTransferGroupsFor(ids: List<String>): Flow<List<UserTaskGroup>>
    @Query("SELECT * FROM transfer_tasks WHERE batchId=:id ORDER BY createdAt, rowid LIMIT :limit")
    fun observeTransferBatch(id: String, limit: Int): Flow<List<TaskEntity>>
    @Query("SELECT * FROM file_operations WHERE batchId=:id ORDER BY createdAt, rowid LIMIT :limit")
    fun observeOperationBatch(id: String, limit: Int): Flow<List<OperationEntity>>
    @Query("SELECT * FROM transfer_tasks WHERE batchId=:id AND (:failedOnly=0 OR state='FAILED') ORDER BY createdAt, rowid LIMIT :limit")
    fun observeTransferBatchFiltered(id: String, limit: Int, failedOnly: Boolean): Flow<List<TaskEntity>>
    @Query("SELECT * FROM file_operations WHERE batchId=:id AND (:failedOnly=0 OR state='FAILED') ORDER BY createdAt, rowid LIMIT :limit")
    fun observeOperationBatchFiltered(id: String, limit: Int, failedOnly: Boolean): Flow<List<OperationEntity>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveV2Raw(record: V2Record)
    @Query("SELECT kind,id,substr(payload,1,32768) AS payload,length(payload) AS characters FROM v2_records WHERE kind=:kind ORDER BY id")
    suspend fun v2Heads(kind: String): List<V2RecordHead>
    @Query("SELECT id FROM v2_records WHERE kind=:kind ORDER BY id") suspend fun v2Ids(kind: String): List<String>
    @Query("SELECT kind,id,substr(payload,1,32768) AS payload,length(payload) AS characters FROM v2_records WHERE kind=:kind ORDER BY id")
    fun observeV2Heads(kind: String): Flow<List<V2RecordHead>>
    @Query("SELECT kind,id,substr(payload,1,32768) AS payload,length(payload) AS characters FROM v2_records WHERE kind='hidden-task' ORDER BY rowid DESC LIMIT :limit")
    fun observeHiddenRecordHeads(limit: Int): Flow<List<V2RecordHead>>
    @Query("SELECT kind,id,substr(payload,1,32768) AS payload,length(payload) AS characters FROM v2_records WHERE kind=:kind AND id=:id")
    suspend fun v2Head(kind: String, id: String): V2RecordHead?
    @Query("SELECT substr(payload,:start,32768) FROM v2_records WHERE kind=:kind AND id=:id")
    suspend fun v2Slice(kind: String, id: String, start: Int): String?
    @Query("DELETE FROM v2_records WHERE kind=:kind AND id=:id") suspend fun deleteV2Raw(kind: String, id: String)
    @Query("DELETE FROM v2_records WHERE kind=:kind") suspend fun deleteV2Kind(kind: String)
    @Transaction suspend fun saveV2(record: V2Record) = V2Payloads.save(this, record)
    @Transaction suspend fun v2Records(kind: String): List<V2Record> = v2Heads(kind).map { V2Payloads.expand(this, it) }
    @Transaction suspend fun v2Record(kind: String, id: String): V2Record? = v2Head(kind, id)?.let { V2Payloads.expand(this, it) }
    fun observeV2(kind: String): Flow<List<V2Record>> = observeV2Heads(kind).map { v2Records(kind) }
    @Transaction suspend fun deleteV2(kind: String, id: String) {
        deleteV2Kind(V2Payloads.chunkKind(kind, id))
        deleteV2Raw(kind, id)
    }

    @Query("SELECT * FROM transfer_tasks WHERE (:filter=0 OR (:filter=1 AND state IN ('QUEUED','RUNNING','COMMITTING','RETRY_WAIT','PAUSED')) OR (:filter=2 AND state IN ('SUCCEEDED','SKIPPED')) OR (:filter=3 AND state='FAILED')) ORDER BY createdAt DESC, rowid DESC LIMIT :limit")
    fun observeTaskWindow(limit: Int, filter: Int): Flow<List<TaskEntity>>
    @Query("SELECT COUNT(*) AS total, COALESCE(SUM(CASE WHEN state IN ('QUEUED','RUNNING','COMMITTING','RETRY_WAIT','PAUSED') THEN 1 ELSE 0 END),0) AS active, COALESCE(SUM(CASE WHEN state IN ('SUCCEEDED','SKIPPED') THEN 1 ELSE 0 END),0) AS completed, COALESCE(SUM(CASE WHEN state='FAILED' THEN 1 ELSE 0 END),0) AS failed FROM transfer_tasks")
    fun observeTaskTotals(): Flow<TaskTotals>
    @Insert suspend fun insertMutationJobRaw(job: MutationJobEntity)
    @Transaction suspend fun insertMutationJob(job: MutationJobEntity) {
        if (job.payload.length > 32768) {
            saveV2(V2Record("mutation-plan", job.id, job.payload))
            insertMutationJobRaw(job.copy(payload = V2Payloads.MUTATION_MARKER))
        } else insertMutationJobRaw(job)
    }
    @Query("SELECT id,substr(payload,1,32768) AS payload,length(payload) AS characters,state,error,connectionId FROM mutation_jobs WHERE state='QUEUED' ORDER BY rowid LIMIT 1")
    suspend fun nextMutationHead(): MutationJobHead?
    @Transaction suspend fun nextMutationJob(): MutationJobEntity? = nextMutationHead()?.let { V2Payloads.mutation(this, it) }
    @Query("UPDATE mutation_jobs SET state='RUNNING' WHERE id=:id AND state='QUEUED'") suspend fun claimMutationJob(id: String): Int
    @Query("UPDATE mutation_jobs SET state=:state, error=:error WHERE id=:id") suspend fun finishMutationJob(id: String, state: String, error: String?)
    @Query("UPDATE mutation_jobs SET state='FAILED', error='VERIFICATION' WHERE state='RUNNING'") suspend fun recoverMutationJobs()
    @Query("SELECT id, state, error FROM mutation_jobs ORDER BY rowid DESC LIMIT 1000") fun observeMutationJobs(): Flow<List<MutationJobStatus>>
    @Query("SELECT id,substr(payload,1,32768) AS payload,length(payload) AS characters,state,error,connectionId FROM mutation_jobs WHERE id=:id")
    suspend fun mutationHead(id: String): MutationJobHead?
    @Query("SELECT substr(payload,:start,32768) FROM mutation_jobs WHERE id=:id") suspend fun mutationSlice(id: String, start: Int): String?
    @Transaction suspend fun mutationJob(id: String): MutationJobEntity? = mutationHead(id)?.let { V2Payloads.mutation(this, it) }
    @Query("SELECT id, state, error FROM mutation_jobs ORDER BY rowid DESC LIMIT :limit OFFSET :offset") suspend fun mutationJobPage(limit: Int, offset: Int): List<MutationJobStatus>
    @Query("SELECT * FROM transfer_tasks WHERE state NOT IN ('SUCCEEDED','SKIPPED','FAILED','CANCELED') OR id IN (SELECT id FROM transfer_tasks WHERE state IN ('SUCCEEDED','SKIPPED','FAILED','CANCELED') ORDER BY createdAt DESC, rowid DESC LIMIT :limit) ORDER BY createdAt, rowid")
    fun observeRecentTasks(limit: Int): Flow<List<TaskEntity>>
    @Query("SELECT * FROM transfer_tasks ORDER BY createdAt DESC, rowid DESC LIMIT :limit OFFSET :offset")
    suspend fun taskPage(limit: Int, offset: Int): List<TaskEntity>
    @Query("SELECT COUNT(*) AS total, COALESCE(SUM(CASE WHEN state='RUNNING' THEN 1 ELSE 0 END),0) AS active, COALESCE(SUM(CASE WHEN state='SUCCEEDED' THEN 1 ELSE 0 END),0) AS completed, COALESCE(SUM(CASE WHEN state='FAILED' THEN 1 ELSE 0 END),0) AS failed FROM file_operations")
    fun observeOperationTotals(): Flow<TaskTotals>
    @Query("SELECT * FROM file_operations ORDER BY createdAt DESC, rowid DESC LIMIT :limit OFFSET :offset")
    suspend fun operationPage(limit: Int, offset: Int): List<OperationEntity>
    @Query("DELETE FROM transfer_tasks WHERE state IN ('SUCCEEDED','SKIPPED')") suspend fun clearFinishedTasks(): Int
    @Query("SELECT * FROM transfer_tasks WHERE state='CANCELED' AND networkRequired=:network AND NOT EXISTS (SELECT 1 FROM v2_records c WHERE c.kind='transfer-cleanup' AND c.id=transfer_tasks.id AND c.payload='DONE') ORDER BY COALESCE((SELECT CAST(a.payload AS INTEGER) FROM v2_records a WHERE a.kind='transfer-cleanup-attempt' AND a.id=transfer_tasks.id),0), createdAt LIMIT :limit")
    suspend fun canceledCleanupTasks(network: Boolean, limit: Int = 16): List<TaskEntity>
    @Query("SELECT COUNT(*) FROM transfer_tasks WHERE state='CANCELED' AND networkRequired=:network AND NOT EXISTS (SELECT 1 FROM v2_records c WHERE c.kind='transfer-cleanup' AND c.id=transfer_tasks.id AND c.payload='DONE')")
    suspend fun pendingCleanupCount(network: Boolean): Int
    @Query("SELECT * FROM transfer_tasks WHERE state='CANCELED' AND NOT EXISTS (SELECT 1 FROM v2_records c WHERE c.kind='transfer-cleanup' AND c.id=transfer_tasks.id AND c.payload='DONE') ORDER BY createdAt DESC LIMIT :limit")
    fun observeCleanupTasks(limit: Int): Flow<List<TaskEntity>>
    @Query("SELECT * FROM transfer_tasks WHERE state='CANCELED' AND EXISTS (SELECT 1 FROM v2_records c WHERE c.kind='transfer-cleanup' AND c.id=transfer_tasks.id AND c.payload='DONE')")
    suspend fun cleanedCanceledTasks(): List<TaskEntity>
    @Query("SELECT * FROM transfer_tasks WHERE state='CANCELED' AND conflictPolicy='REPLACE'") suspend fun canceledReplacements(): List<TaskEntity>
    @Query("DELETE FROM transfer_tasks WHERE id=:id AND state='CANCELED'") suspend fun deleteCanceledTask(id: String): Int
    @Query("DELETE FROM file_operations WHERE state != 'RUNNING'") suspend fun clearFinishedOperations(): Int
    @Query("SELECT id FROM mutation_jobs WHERE state IN ('SUCCEEDED','FAILED')") suspend fun finishedMutationIds(): List<String>
    @Query("DELETE FROM mutation_jobs WHERE state IN ('SUCCEEDED','FAILED')") suspend fun clearFinishedMutationJobsRaw(): Int
    @Transaction suspend fun clearFinishedMutationJobs(): Int {
        finishedMutationIds().forEach { deleteV2("mutation-plan", it) }
        return clearFinishedMutationJobsRaw()
    }
    @Query("DELETE FROM sync_profiles WHERE sourceId=:id OR targetId=:id") suspend fun deleteProfilesForConnection(id: String)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveSyncProfile(profile: SyncProfileEntity)
    @Query("SELECT * FROM sync_profiles ORDER BY name, id") fun observeSyncProfiles(): Flow<List<SyncProfileEntity>>
    @Query("DELETE FROM sync_profiles WHERE id=:id") suspend fun deleteSyncProfile(id: String)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveConnection(connection: ConnectionEntity)
    @Query("SELECT * FROM connections WHERE id=:id") suspend fun connection(id: String): ConnectionEntity?
    @Query("SELECT * FROM connections ORDER BY displayName, id") fun observeConnections(): Flow<List<ConnectionEntity>>
    @Query("DELETE FROM connections WHERE id=:id AND NOT EXISTS (SELECT 1 FROM transfer_tasks WHERE (sourceId=:id OR targetId=:id) AND (state NOT IN ('SUCCEEDED','SKIPPED','CANCELED') OR (state='CANCELED' AND (conflictPolicy='REPLACE' OR NOT EXISTS (SELECT 1 FROM v2_records c WHERE c.kind='transfer-cleanup' AND c.id=transfer_tasks.id AND c.payload='DONE'))))) AND NOT EXISTS (SELECT 1 FROM mutation_jobs WHERE connectionId=:id AND state IN ('QUEUED','RUNNING'))")
    suspend fun removeConnection(id: String): Int
    @Insert suspend fun insertOperations(operations: List<OperationEntity>)
    @Query("UPDATE file_operations SET state=:state, error=:error WHERE id=:id") suspend fun finishOperation(id: String, state: String, error: String?)
    @Query("UPDATE file_operations SET state='FAILED', error='VERIFICATION' WHERE state='RUNNING'") suspend fun recoverOperations()
    @Query("SELECT * FROM file_operations ORDER BY createdAt DESC, rowid DESC LIMIT 1000") fun observeOperations(): Flow<List<OperationEntity>>
    @Query("SELECT * FROM transfer_tasks WHERE batchId=:id") suspend fun tasksForBatch(id: String): List<TaskEntity>
    @Query("SELECT EXISTS(SELECT 1 FROM transfer_tasks WHERE batchId=:id)") suspend fun batchHasTasks(id: String): Boolean
    @Query("SELECT EXISTS(SELECT 1 FROM transfer_tasks WHERE batchId=:id AND state NOT IN ('SUCCEEDED','SKIPPED','FAILED','CANCELED'))")
    suspend fun batchHasUnfinishedTasks(id: String): Boolean
    @Query("SELECT EXISTS(SELECT 1 FROM file_operations WHERE batchId=:id AND state IN ('QUEUED','RUNNING'))")
    suspend fun batchHasActiveOperations(id: String): Boolean
    @Query("SELECT * FROM transfer_tasks WHERE batchId=:id ORDER BY createdAt, rowid LIMIT 1") suspend fun firstTaskInBatch(id: String): TaskEntity?
    @Query("SELECT * FROM file_operations WHERE batchId=:id ORDER BY createdAt, rowid LIMIT 1") suspend fun firstOperationInBatch(id: String): OperationEntity?
    @Insert suspend fun insertTasks(tasks: List<TaskEntity>)
    @Query("SELECT * FROM transfer_tasks ORDER BY createdAt, rowid") fun observeTasks(): Flow<List<TaskEntity>>
    @Query("SELECT COUNT(*) || ':' || COALESCE(SUM(rowid),0) FROM transfer_tasks WHERE targetId=:id AND state='SUCCEEDED'")
    fun observeStorageCompletion(id: String): Flow<String>
    @Query("SELECT * FROM transfer_tasks WHERE id=:id") suspend fun task(id: String): TaskEntity?
    @Query("SELECT * FROM transfer_tasks WHERE id=:id") fun observeTask(id: String): Flow<TaskEntity?>
    @Query("SELECT * FROM v2_records WHERE kind='job-status' AND id=:id") fun observeJobStatus(id: String): Flow<V2Record?>
    @Query("SELECT * FROM transfer_tasks WHERE networkRequired=:network AND (state='QUEUED' OR (state='RETRY_WAIT' AND retryAt<=:now)) ORDER BY createdAt, rowid LIMIT 1")
    suspend fun next(now: Long, network: Boolean): TaskEntity?
    @Query("UPDATE transfer_tasks SET state='RUNNING', attempts=attempts+1, error=NULL WHERE id=:id AND state IN ('QUEUED','RETRY_WAIT')")
    suspend fun claim(id: String): Int
    @Query("UPDATE transfer_tasks SET state='RETRY_WAIT', error=:error, retryAt=:retryAt WHERE id=:id AND state IN ('QUEUED','RETRY_WAIT')")
    suspend fun waitForStorage(id: String, error: String, retryAt: Long): Int
    @Query("UPDATE transfer_tasks SET state='RETRY_WAIT', error=:error, retryAt=:retryAt, attempts=MAX(0,attempts-1) WHERE id=:id AND state IN ('RUNNING','COMMITTING')")
    suspend fun interruptedStorage(id: String, error: String, retryAt: Long): Int
    @Query("UPDATE transfer_tasks SET retryAt=0 WHERE state='RETRY_WAIT' AND error IN ('STORAGE_UNAVAILABLE','PERMISSION')")
    suspend fun wakeStorageTasks()
    @Query("UPDATE transfer_tasks SET state='QUEUED', attempts=MAX(0,attempts-1) WHERE networkRequired=:network AND state IN ('RUNNING','COMMITTING')") suspend fun recoverInterrupted(network: Boolean)
    @Query("UPDATE transfer_tasks SET state='QUEUED', attempts=MAX(0,attempts-1), error=NULL, retryAt=0 WHERE id=:id AND state IN ('RUNNING','COMMITTING')")
    suspend fun deferInterrupted(id: String): Int
    @Query("UPDATE transfer_tasks SET bytesTransferred=:bytes WHERE id=:id AND state='RUNNING'") suspend fun progress(id: String, bytes: Long)
    @Query("UPDATE transfer_tasks SET targetPath=:path WHERE id=:id AND state='RUNNING'") suspend fun target(id: String, path: String): Int
    @Query("UPDATE transfer_tasks SET digest=:digest, state='COMMITTING' WHERE id=:id AND state='RUNNING'") suspend fun beginCommit(id: String, digest: String): Int
    @Query("UPDATE transfer_tasks SET backupDigest=:digest WHERE id=:id AND state='COMMITTING'") suspend fun backupDigest(id: String, digest: String): Int
    @Query("UPDATE transfer_tasks SET state=:state, error=:error, retryAt=:retryAt WHERE id=:id AND state IN ('RUNNING','COMMITTING')")
    suspend fun finish(id: String, state: String, error: String? = null, retryAt: Long = 0): Int
    @Query("UPDATE transfer_tasks SET state='PAUSED' WHERE id=:id AND state IN ('QUEUED','RUNNING','RETRY_WAIT')") suspend fun pause(id: String): Int
    @Query("UPDATE transfer_tasks SET state='CANCELED' WHERE id=:id AND state IN ('QUEUED','RUNNING','RETRY_WAIT','PAUSED','FAILED')") suspend fun cancel(id: String): Int
    @Query("UPDATE transfer_tasks SET state='QUEUED', attempts=0, retryAt=0, error=NULL WHERE id=:id AND state IN ('PAUSED','FAILED')") suspend fun resume(id: String): Int
    @Query("SELECT COUNT(*) FROM transfer_tasks WHERE state IN ('QUEUED','RETRY_WAIT')") suspend fun pendingCount(): Int
    @Query("SELECT COUNT(*) FROM transfer_tasks WHERE networkRequired=:network AND state IN ('QUEUED','RETRY_WAIT','RUNNING','COMMITTING')") suspend fun activeCount(network: Boolean): Int
}

@Entity(tableName = "sync_profiles")
internal data class SyncProfileEntity(@PrimaryKey val id: String, val name: String,
    val sourceId: String, val sourcePath: String, val targetId: String, val targetPath: String, val conflictPolicy: String)

@Entity(tableName = "mutation_jobs")
internal data class MutationJobEntity(@PrimaryKey val id: String, val payload: String, val state: String = "QUEUED", val error: String? = null,
    val connectionId: String = "")

internal data class MutationJobStatus(val id: String, val state: String, val error: String?)
internal data class MutationJobHead(val id: String, val payload: String, val characters: Int,
    val state: String, val error: String?, val connectionId: String)

@Database(entities = [ConnectionEntity::class, TaskEntity::class, OperationEntity::class, SyncProfileEntity::class, MutationJobEntity::class, V2Record::class], version = 4, exportSchema = true)
internal abstract class TransferDatabase : RoomDatabase() {
    abstract fun dao(): TransferDao
    companion object {
        @Volatile private var instance: TransferDatabase? = null
        fun get(context: Context): TransferDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, TransferDatabase::class.java, "nas-transfers.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).build().also { instance = it }
        }
        internal val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS v2_records (kind TEXT NOT NULL, id TEXT NOT NULL, payload TEXT NOT NULL, PRIMARY KEY(kind, id))")
            }
        }
        internal val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE transfer_tasks ADD COLUMN verifyContent INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE transfer_tasks ADD COLUMN targetExpectedSize INTEGER NOT NULL DEFAULT -2")
                db.execSQL("ALTER TABLE transfer_tasks ADD COLUMN targetExpectedModified INTEGER NOT NULL DEFAULT -1")
                db.execSQL("ALTER TABLE transfer_tasks ADD COLUMN backupDigest TEXT NOT NULL DEFAULT ''")
                db.execSQL("CREATE TABLE IF NOT EXISTS sync_profiles (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, sourceId TEXT NOT NULL, sourcePath TEXT NOT NULL, targetId TEXT NOT NULL, targetPath TEXT NOT NULL, conflictPolicy TEXT NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS mutation_jobs (id TEXT NOT NULL PRIMARY KEY, payload TEXT NOT NULL, state TEXT NOT NULL, error TEXT, connectionId TEXT NOT NULL)")
            }
        }
        internal val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE connections ADD COLUMN displayName TEXT NOT NULL DEFAULT ''")
                db.execSQL("CREATE TABLE IF NOT EXISTS file_operations (id TEXT NOT NULL PRIMARY KEY, batchId TEXT NOT NULL, connectionId TEXT NOT NULL, kind TEXT NOT NULL, sourcePath TEXT NOT NULL, targetPath TEXT NOT NULL, state TEXT NOT NULL, error TEXT, createdAt INTEGER NOT NULL)")
            }
        }
    }
}
