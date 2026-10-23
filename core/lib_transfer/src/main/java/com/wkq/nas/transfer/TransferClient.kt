package com.wkq.nas.transfer

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.wkq.nas.transfer.data.*
import com.wkq.nas.transfer.provider.ProviderFactory
import com.wkq.nas.transfer.provider.StorageProvider
import com.wkq.nas.transfer.provider.hash
import com.wkq.nas.transfer.provider.WorkerProviders
import com.wkq.nas.transfer.security.CredentialVault
import com.wkq.nas.transfer.work.TransferScheduler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URI
import java.util.UUID

/** 调用方只传连接 ID；密码不会进入 WorkManager Data、任务表或进度。 */
class TransferClient private constructor(context: Context) {
    private val context = context.applicationContext
    private val dao = TransferDatabase.get(context).dao()
    private val providers = ProviderFactory(context)
    fun observeTasks(): Flow<List<TaskSnapshot>> = dao.observeTasks().map { tasks -> tasks.map { it.snapshot() } }
    fun observeTaskWindow(limit: Int, filter: Int = 0): Flow<List<TaskSnapshot>> {
        require(limit in 1..100000 && filter in 0..3)
        return dao.observeTaskWindow(limit, filter).map { tasks -> tasks.map { it.snapshot() }.reversed() }
    }
    fun observeTaskTotals(): Flow<TaskTotals> = dao.observeTaskTotals()
    fun observeRecentTasks(limit: Int = 1000): Flow<List<TaskSnapshot>> {
        require(limit in 1..10000)
        return dao.observeRecentTasks(limit).map { tasks -> tasks.map { it.snapshot() } }
    }
    suspend fun getTask(id: String): TaskSnapshot? = dao.task(id)?.snapshot()

    suspend fun saveConnection(id: String, config: ConnectionConfig, password: String = "", displayName: String = id,
        keepPassword: Boolean = false) = withContext(Dispatchers.IO) {
        require(id.isNotBlank())
        validateConnection(config)
        TransferDatabase.get(context).withTransaction {
            val existing = dao.connection(id)
            require(existing == null || existing.protocol == config.protocol.name && existing.root == config.root) {
                "Connection roots are immutable; use a new ID for a different destination"
            }
            dao.saveConnection(ConnectionEntity(id, config.protocol.name, config.root, config.username,
                if (keepPassword && password.isEmpty() && existing != null) existing.passwordEncrypted
                else if (password.isEmpty()) "" else CredentialVault.encrypt(password), config.hostKeySha256,
                displayName.trim().ifEmpty { id }))
        }
    }

    suspend fun enqueue(request: TransferRequest): String = enqueueBatch(listOf(request)).single()
    suspend fun previewTransfers(requests: List<TransferRequest>): List<TransferRequest> = withContext(Dispatchers.IO) {
        val sessions = mutableMapOf<String, StorageProvider>()
        try { requests.map { request ->
            currentCoroutineContext().ensureActive()
            val connection = dao.connection(request.targetId) ?: throw TransferException(TransferError.NOT_FOUND)
            val target = sessions.getOrPut(connection.id) { providers.open(connection) }.stat(TransferPaths.normalize(request.targetPath))
            request.copy(targetSnapshot = target, targetPreviewed = true)
        } } finally { sessions.values.forEach { runCatching { it.close() } } }
    }
    suspend fun batchDirectories(id: String): Map<String, Boolean> = withContext(Dispatchers.IO) {
        dao.v2Ids("batch-directory:$id").associateWith { true }
    }
    fun observeBatchLabels(): Flow<Map<String, String>> = dao.observeV2("batch-label").map { rows -> rows.associate { it.id to it.payload } }

    suspend fun enqueueBatch(requests: List<TransferRequest>, batchId: String = UUID.randomUUID().toString(), label: String = ""): List<String> = withContext(Dispatchers.IO) {
        SyncDiagnostics(context).event(DiagnosticEvent.ACTION, batchId, count = requests.size.toLong(),
            endpoint = requests.firstOrNull()?.sourceId.orEmpty(), target = requests.firstOrNull()?.targetId.orEmpty(), kind = requests.firstOrNull()?.kind, trigger = DiagnosticTrigger.MANUAL)
        require(requests.isNotEmpty() && requests.size <= 100000 && batchId.isNotBlank())
        val endpoints = mutableMapOf<String, ConnectionEntity>()
        suspend fun endpoint(id: String) = endpoints[id] ?: (dao.connection(id) ?: throw TransferException(TransferError.NOT_FOUND)).also { endpoints[id] = it }
        val sessions = mutableMapOf<String, StorageProvider>()
        val directories = mutableSetOf<String>()
        val tasks = try { requests.map { request ->
            currentCoroutineContext().ensureActive()
            val source = endpoint(request.sourceId)
            val target = endpoint(request.targetId)
            val sourcePath = TransferPaths.normalize(request.sourcePath)
            val targetPath = TransferPaths.normalize(request.targetPath)
            require(targetPath.isNotEmpty())
            val pool = currentCoroutineContext()[WorkerProviders]
            val info = (if (pool != null) pool.use(source) { it.stat(sourcePath) }
                else sessions.getOrPut(source.id) { providers.open(source) }.stat(sourcePath))
                ?: throw TransferException(TransferError.NOT_FOUND)
            require(request.conflictPolicy != ConflictPolicy.REPLACE || request.targetPreviewed) { "Replacement requires a confirmed preview" }
            require(sourcePath.isNotEmpty() || info.directory)
            validateDestination(source, sourcePath, target, targetPath, info.directory)
            if (request.expectedSize >= 0 && info.size != request.expectedSize ||
                request.expectedModifiedAt > 0 && info.modifiedAt != request.expectedModifiedAt) throw TransferException(TransferError.SOURCE_CHANGED)
            if (info.directory) directories.add(source.id + ":" + sourcePath)
            TaskEntity(UUID.randomUUID().toString(), batchId, request.kind.name, source.id, sourcePath, target.id, targetPath,
                targetPath, request.conflictPolicy.name, info.size, info.modifiedAt,
                source.protocol !in LOCAL_PROTOCOLS || target.protocol !in LOCAL_PROTOCOLS,
                verifyContent = request.verifyContent || TransferPreferences(context).verifyContent,
                targetExpectedSize = if (!request.targetPreviewed) -2 else request.targetSnapshot?.size ?: -1,
                targetExpectedModified = request.targetSnapshot?.modifiedAt ?: -1)
        }
        } finally { sessions.values.forEach { runCatching { it.close() } } }
        TransferDatabase.get(context).withTransaction {
            dao.insertTasks(tasks)
            directories.forEach { dao.saveV2(V2Record("batch-directory:$batchId", it, "true")) }
            if (label.isNotBlank()) dao.saveV2(V2Record("batch-label", batchId, label.take(256)))
        }
        SyncDiagnostics(context).event(DiagnosticEvent.ENQUEUED, batchId, count = tasks.size.toLong())
        tasks.map { it.networkRequired }.distinct().forEach { TransferScheduler.enqueue(context, it) }
        tasks.map { it.id }
    }

    fun observeSyncProfiles() = dao.observeSyncProfiles().map { rows -> rows.map {
        SavedSyncProfile(it.id, it.name, it.sourceId, it.sourcePath, it.targetId, it.targetPath, ConflictPolicy.valueOf(it.conflictPolicy))
    } }
    suspend fun saveSyncProfile(profile: SavedSyncProfile) {
        require(profile.id.isNotBlank() && profile.name.isNotBlank())
        val source = dao.connection(profile.sourceId) ?: throw TransferException(TransferError.NOT_FOUND)
        val target = dao.connection(profile.targetId) ?: throw TransferException(TransferError.NOT_FOUND)
        val from = TransferPaths.normalize(profile.sourcePath)
        val to = TransferPaths.normalize(profile.targetPath)
        validateDestination(source, from, target, to, true)
        dao.saveSyncProfile(SyncProfileEntity(profile.id, profile.name.trim(), source.id, from, target.id, to, profile.conflictPolicy.name))
    }
    suspend fun deleteSyncProfile(id: String) = dao.deleteSyncProfile(id)
    fun observeBatchOutcome(id: String) = dao.observeBatchOutcome(id)
    fun observeBatch(id: String, limit: Int) = dao.observeTransferBatch(id, limit).map { rows -> rows.map { it.snapshot() } }
    fun observeBatchFiltered(id: String, limit: Int, failedOnly: Boolean) =
        dao.observeTransferBatchFiltered(id, limit, failedOnly).map { rows -> rows.map { it.snapshot() } }
    suspend fun taskPage(limit: Int = 200, offset: Int = 0): List<TaskSnapshot> {
        require(limit in 1..1000 && offset >= 0)
        return dao.taskPage(limit, offset).map { it.snapshot() }
    }
    suspend fun clearFinishedTasks() = withContext(Dispatchers.IO) {
        dao.clearFinishedTasks()
        dao.cleanedCanceledTasks().forEach { task ->
            if (task.conflictPolicy != ConflictPolicy.REPLACE.name) {
                dao.deleteCanceledTask(task.id); dao.deleteV2("transfer-cleanup", task.id); dao.deleteV2("transfer-cleanup-attempt", task.id); return@forEach
            }
            val connection = dao.connection(task.targetId) ?: return@forEach
            try { providers.open(connection).use { target ->
                val backup = TransferPaths.join(TransferPaths.parent(task.targetPath), ".nas-${task.id}.backup")
                if (target.stat(backup) == null) { dao.deleteCanceledTask(task.id); dao.deleteV2("transfer-cleanup", task.id); dao.deleteV2("transfer-cleanup-attempt", task.id) }
            } } catch (error: Exception) { if (error is CancellationException) throw error /* 无法核实备份时保留恢复记录。 */ }
        }
    }
    suspend fun restoreReplacement(id: String) = withContext(Dispatchers.IO) {
        val task = dao.task(id) ?: throw TransferException(TransferError.NOT_FOUND)
        require(task.conflictPolicy == ConflictPolicy.REPLACE.name && task.state in setOf("FAILED", "CANCELED"))
        val connection = dao.connection(task.targetId) ?: throw TransferException(TransferError.NOT_FOUND)
        providers.open(connection).use { target ->
            val backup = TransferPaths.join(TransferPaths.parent(task.targetPath), ".nas-${task.id}.backup")
            if (target.stat(backup) == null) throw TransferException(TransferError.NOT_FOUND)
            if (task.backupDigest.isEmpty() || target.read(backup).use { hash(it) } != task.backupDigest)
                throw TransferException(TransferError.VERIFICATION)
            var destination = task.targetPath
            if (target.stat(destination) != null) {
                val parent = TransferPaths.parent(destination)
                val name = TransferPaths.name(destination)
                destination = (1..10000).asSequence().map { TransferPaths.join(parent, "$name (restored $it)") }
                    .firstOrNull { target.stat(it) == null } ?: throw TransferException(TransferError.CONFLICT)
            }
            target.moveNew(backup, destination)
            destination
        }
    }
    suspend fun retryFailed() {
        var offset = 0
        while (true) {
            val page = dao.taskPage(500, offset)
            page.filter { it.state == "FAILED" }.forEach { resume(it.id) }
            if (page.size < 500) break
            offset += page.size
        }
    }

    suspend fun pause(id: String): Boolean = withContext(Dispatchers.IO) {
        val task = dao.task(id); val changed = dao.pause(id) > 0
        if (changed) SyncDiagnostics(context).event(DiagnosticEvent.CONTROL, task?.batchId.orEmpty(), outcome = DiagnosticOutcome.PAUSED)
        changed
    }
    /** 批次控制仍通过文件级条件更新，提交中的文件不被强行中断。 */
    suspend fun controlBatch(batchId: String, action: Int): Int = withContext(Dispatchers.IO) {
        require(action in 0..3)
        var changed = 0
        val cleanupQueues = mutableSetOf<Boolean>()
        try { dao.tasksForBatch(batchId).forEach { task ->
            currentCoroutineContext().ensureActive()
            val applied = when (action) {
                0 -> pause(task.id)
                1 -> if (task.state == "PAUSED") resume(task.id) else false
                2 -> cancelInternal(task.id, false)
                else -> if (task.state == "FAILED") resume(task.id) else false
            }
            if (applied) { changed++; if (action == 2) cleanupQueues.add(task.networkRequired) }
        } } finally {
            withContext(kotlinx.coroutines.NonCancellable) { cleanupQueues.forEach { TransferScheduler.enqueue(context, it) } }
        }
        changed
    }
    suspend fun cancel(id: String): Boolean = withContext(Dispatchers.IO) { cancelInternal(id, true) }
    private suspend fun cancelInternal(id: String, schedule: Boolean): Boolean {
        val task = dao.task(id); val changed = dao.cancel(id) > 0
        if (changed) SyncDiagnostics(context).event(DiagnosticEvent.CONTROL, task?.batchId.orEmpty(), outcome = DiagnosticOutcome.CANCELED)
        if (changed && schedule && task != null) TransferScheduler.enqueue(context, task.networkRequired)
        return changed
    }
    fun observePendingCleanup(limit: Int = 200): Flow<List<TransferCleanupStatus>> {
        require(limit in 1..10000)
        return dao.observeCleanupTasks(limit).map { rows -> rows.map { task ->
            TransferCleanupStatus(task.id, task.targetPath, task.targetId,
                dao.v2Record("transfer-cleanup", task.id)?.payload?.let { code -> TransferError.entries.firstOrNull { it.name == code } })
        } }
    }
    suspend fun retryCleanup() = withContext(Dispatchers.IO) {
        listOf(false, true).forEach { if (dao.pendingCleanupCount(it) > 0) TransferScheduler.wakeStorage(context, it) }
    }
    suspend fun resume(id: String): Boolean = withContext(Dispatchers.IO) {
        if (dao.resume(id) == 0) return@withContext false
        SyncDiagnostics(context).event(DiagnosticEvent.CONTROL, dao.task(id)?.batchId.orEmpty(), outcome = DiagnosticOutcome.QUEUED)
        TransferScheduler.enqueue(context, checkNotNull(dao.task(id)).networkRequired)
        true
    }

    /** 应用启动时补调度已落库任务，修复“入库后、调度前”进程退出的窗口。 */
    suspend fun recoverPendingTransfers() = withContext(Dispatchers.IO) {
        listOf(false, true).forEach { if (dao.activeCount(it) > 0 || dao.pendingCleanupCount(it) > 0) TransferScheduler.enqueue(context, it) }
    }
    suspend fun storageChanged() = withContext(Dispatchers.IO) {
        dao.wakeStorageTasks()
        listOf(false, true).forEach { if (dao.activeCount(it) > 0 || dao.pendingCleanupCount(it) > 0) TransferScheduler.wakeStorage(context, it) }
    }
    suspend fun updateNetworkPolicy(allowMetered: Boolean) = withContext(Dispatchers.IO) {
        TransferPreferences(context).allowMetered = allowMetered
        TransferScheduler.restartNetwork(context)
    }

    suspend fun previewSync(sourceId: String, sourceDirectory: String, targetId: String, targetDirectory: String): SyncPreview = withContext(Dispatchers.IO) {
        val source = dao.connection(sourceId) ?: throw TransferException(TransferError.NOT_FOUND)
        val target = dao.connection(targetId) ?: throw TransferException(TransferError.NOT_FOUND)
        val sourceRoot = TransferPaths.normalize(sourceDirectory)
        val targetRoot = TransferPaths.normalize(targetDirectory)
        validateDestination(source, sourceRoot, target, targetRoot, true)
        val sourceFiles = providers.open(source).use {
            listOf(it.stat(sourceRoot) ?: throw TransferException(TransferError.NOT_FOUND)) + scan(it, sourceRoot, allowMissing = false)
        }
        val targetFiles = providers.open(target).use {
            listOfNotNull(it.stat(targetRoot)) + scan(it, targetRoot, allowMissing = true)
        }
        var items = SyncPlanner.plan(sourceFiles, targetFiles, sourceRoot, targetRoot)
        if (TransferPreferences(context).verifyContent) {
            providers.open(source).use { from -> providers.open(target).use { to ->
                items = items.map { item ->
                    currentCoroutineContext().ensureActive()
                    if (item.action != SyncAction.SKIP || item.source.directory) item
                    else {
                        val beforeSource = from.stat(item.source.path)
                        val beforeTarget = to.stat(item.targetPath)
                        val job = currentCoroutineContext()
                        val sourceHash = from.read(item.source.path).use { hash(it) { job.ensureActive() } }
                        val targetHash = to.read(item.targetPath).use { hash(it) { job.ensureActive() } }
                        if (from.stat(item.source.path) != beforeSource || to.stat(item.targetPath) != beforeTarget)
                            throw TransferException(TransferError.SOURCE_CHANGED)
                        if (sourceHash == targetHash) item else item.copy(action = SyncAction.CONFLICT)
                    }
                }
            } }
        }
        SyncPreview(sourceId, targetId, sourceRoot, targetRoot, items)
    }

    /** 预览中的 SKIP 基于大小/mtime 快速比较；提交前重新检查源快照，目标冲突由 Worker 再检查。 */
    suspend fun enqueueSync(preview: SyncPreview, conflicts: ConflictPolicy = ConflictPolicy.FAIL): List<String> {
        val current = previewSync(preview.sourceId, preview.sourceDirectory, preview.targetId, preview.targetDirectory)
        if (current != preview) throw TransferException(TransferError.SOURCE_CHANGED)
        val requests = preview.items.filter { it.action != SyncAction.SKIP }.map {
            TransferRequest(preview.sourceId, it.source.path, preview.targetId, it.targetPath,
                conflicts, TransferKind.SYNC, it.source.size, it.source.modifiedAt,
                targetSnapshot = it.targetSnapshot, targetPreviewed = true)
        }
        return if (requests.isEmpty()) emptyList() else enqueueBatch(requests)
    }

    private suspend fun scan(provider: StorageProvider, root: String, allowMissing: Boolean): List<FileMetadata> {
        val metadata = provider.stat(root) ?: if (allowMissing) return emptyList() else throw TransferException(TransferError.NOT_FOUND)
        require(metadata.directory)
        val directories = ArrayDeque<String>().apply { add(root) }
        val visited = mutableSetOf<String>()
        val entries = mutableListOf<FileMetadata>()
        while (directories.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val directory = directories.removeFirst()
            if (!visited.add(directory)) throw TransferException(TransferError.INVALID_PATH)
            val job = currentCoroutineContext()
            provider.visit(directory) { entry ->
                job.ensureActive()
                if (FileClient.privatePart(entry.path)) return@visit
                require(TransferPaths.parent(entry.path) == directory)
                if (entries.size >= 100000) throw TransferException(TransferError.UNSUPPORTED)
                entries += entry
                if (entry.directory) directories.add(entry.path)
            }
        }
        return entries.sortedBy { it.path }
    }

    /** 使用临时登录列举SMB共享；不保存凭据或放宽连接根目录校验。 */
    suspend fun listSmbShares(server: ConnectionConfig, password: String): List<String> = withContext(Dispatchers.IO) {
        val uri = URI(server.root)
        require(server.protocol == StorageProtocol.SMB && uri.scheme == "smb" && !uri.host.isNullOrBlank())
        require(uri.userInfo == null && uri.query == null && uri.fragment == null && uri.path.orEmpty() in setOf("", "/"))
        val draft = ConnectionEntity("share-picker", "SMB", server.root, server.username,
            if (password.isEmpty()) "" else CredentialVault.encrypt(password), "")
        try {
            providers.open(draft).use { provider ->
                val names = sortedSetOf<String>()
                val job = currentCoroutineContext()
                provider.visit("") { file ->
                    job.ensureActive()
                    if (file.directory && TransferPaths.parent(file.path).isEmpty()) names += file.path
                    require(names.size <= 10000)
                }
                names.toList()
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            val (reason, retryable) = com.wkq.nas.transfer.provider.classify(error)
            throw TransferException(reason, retryable)
        }
    }

    /** 测试未保存的配置，不写入数据库；编辑时可沿用原凭据。 */
    suspend fun testConnectionDraft(config: ConnectionConfig, password: String, existingId: String? = null) = withContext(Dispatchers.IO) {
        validateConnection(config)
        val existing = existingId?.let { dao.connection(it) }
        if (existingId != null) require(existing != null && existing.protocol == config.protocol.name && existing.root == config.root)
        val encrypted = if (password.isEmpty() && existing != null) existing.passwordEncrypted
            else if (password.isEmpty()) "" else CredentialVault.encrypt(password)
        val draft = ConnectionEntity("draft", config.protocol.name, config.root, config.username, encrypted, config.hostKeySha256)
        try {
            providers.open(draft).use { provider ->
                if (provider.stat("")?.directory != true) throw TransferException(TransferError.NOT_FOUND)
                provider.list("")
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            val (error, retryable) = com.wkq.nas.transfer.provider.classify(e)
            throw TransferException(error, retryable)
        }
        Unit
    }

    private fun validateConnection(config: ConnectionConfig) {
        when (config.protocol) {
            StorageProtocol.LOCAL -> require(File(config.root).isAbsolute && File(config.root).isDirectory)
            StorageProtocol.SAF -> require(Uri.parse(config.root).scheme == "content" && android.provider.DocumentsContract.isTreeUri(Uri.parse(config.root)))
            else -> {
                val uri = URI(config.root)
                require(!uri.host.isNullOrBlank() && uri.userInfo == null && uri.query == null && uri.fragment == null)
                val scheme = when (config.protocol) { StorageProtocol.SMB -> "smb"; StorageProtocol.SFTP -> "sftp"; else -> "https" }
                require(uri.scheme == scheme)
                TransferPaths.normalize(uri.path.orEmpty().trim('/'))
                if (config.protocol == StorageProtocol.SMB) require(uri.path.orEmpty().trim('/').isNotEmpty())
                if (config.protocol == StorageProtocol.SFTP) require(config.hostKeySha256.isNotBlank())
                if (config.protocol == StorageProtocol.WEBDAV) WebDavCertificates.validate(config.hostKeySha256)
            }
        }
    }
    private fun validateDestination(source: ConnectionEntity, sourcePath: String, target: ConnectionEntity, targetPath: String, directory: Boolean) {
        fun config(row: ConnectionEntity) = ConnectionConfig(StorageProtocol.valueOf(row.protocol), row.root)
        StorageIdentity.validate(config(source), sourcePath, config(target), targetPath, directory)
    }

    companion object {
        private val LOCAL_PROTOCOLS = setOf("LOCAL", "SAF")
        @Volatile private var instance: TransferClient? = null
        fun get(context: Context): TransferClient = instance ?: synchronized(this) {
            instance ?: TransferClient(context).also { instance = it }
        }
    }
}
