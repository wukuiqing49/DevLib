package com.wkq.nas.transfer

import android.content.Context
import com.wkq.nas.transfer.data.*
import com.wkq.nas.transfer.provider.*
import androidx.room.withTransaction
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/** 页面使用的文件契约；变更必须提交原始预览，逐项结果持久化。 */
class FileClient(context: Context) {
    private data class ListingCandidate(val file: FileMetadata, val name: String)
    private val context = context.applicationContext
    private val dao = TransferDatabase.get(this.context).dao()
    private val factory = ProviderFactory(this.context)
    private val listingCache = DirectoryListingCache()
    private val pickerCache = DirectoryListingCache(capacity = 20000, ttlMillis = 15000, maxChars = 4 * 1024 * 1024)
    /** 显式刷新、回到前台或文件操作完成时使浏览快照失效。 */
    fun invalidateListingCache() { listingCache.invalidate(); pickerCache.invalidate() }
    fun observeConnections() = dao.observeConnections().map { rows -> rows.map {
        SavedConnection(it.id, it.displayName.ifEmpty { it.id }, ConnectionConfig(StorageProtocol.valueOf(it.protocol), it.root, it.username, it.hostKeySha256))
    } }
    fun observeOperationTotals() = dao.observeOperationTotals()
    fun observeTransferGroups() = dao.observeTransferGroups()
    fun observeTransferGroups(ids: List<String>) = dao.observeTransferGroupsFor(ids)
    fun observeStorageCompletion(id: String) = dao.observeStorageCompletion(id)
    fun observeUserTaskGroups(limit: Int) = TaskHistory(context).groups(limit)
    fun observeOperationBatchFiltered(id: String, limit: Int, failedOnly: Boolean) =
        dao.observeOperationBatchFiltered(id, limit, failedOnly).map { rows -> rows.map { it.snapshot() } }
    fun observeOperationBatch(id: String, limit: Int) = dao.observeOperationBatch(id, limit).map { rows -> rows.map { it.snapshot() } }
    fun observeOperations() = dao.observeOperations().map { rows -> rows.map { it.snapshot() } }
    fun observeOperationJobs() = dao.observeMutationJobs().map { rows -> rows.map {
        FileOperationJob(it.id, it.state, it.error?.let(TransferError::valueOf))
    } }
    suspend fun operationJobPage(limit: Int, offset: Int): List<FileOperationJob> {
        require(limit in 1..1000 && offset >= 0)
        return dao.mutationJobPage(limit, offset).map { FileOperationJob(it.id, it.state, it.error?.let(TransferError::valueOf)) }
    }
    suspend fun operationJobPreview(id: String): MutationPreview = withContext(Dispatchers.IO) {
        val job = dao.mutationJob(id) ?: throw TransferException(TransferError.NOT_FOUND)
        com.wkq.nas.transfer.work.MutationCodec.decode(job.payload)
    }
    suspend fun enqueueOperation(preview: MutationPreview): String = withContext(Dispatchers.IO) {
        require(preview.items.isNotEmpty() && preview.items.none { it.conflict })
        val id = UUID.randomUUID().toString()
        dao.insertMutationJob(MutationJobEntity(id, com.wkq.nas.transfer.work.MutationCodec.encode(preview), connectionId = preview.connectionId))
        com.wkq.nas.transfer.work.MutationWorker.schedule(context)
        id
    }
    suspend fun recoverInterruptedOperations() = mutations.withLock { withContext(Dispatchers.IO) { dao.recoverOperations() } }
    suspend fun removeConnection(id: String) = TransferDatabase.get(context).withTransaction {
        val v2 = dao.v2Records("profile").map { org.json.JSONObject(it.payload) }
        val recycle = dao.v2Records("recycle").map { org.json.JSONObject(it.payload) }
        val summaries = dao.v2Records("job-status").associate { it.id to org.json.JSONObject(it.payload) }
        val jobs = dao.v2Ids("job").mapNotNull { jobId ->
            val summary = summaries[jobId]
            if (summary != null && summary.optString("state") !in setOf("QUEUED", "RUNNING")) null
            else if (summary != null && (summary.optString("connection").isNotEmpty() ||
                summary.optString("left").isNotEmpty() && summary.optString("right").isNotEmpty())) summary
            else dao.v2Record("job", jobId)?.let { row ->
                val value = org.json.JSONObject(row.payload)
                val profile = value.optJSONObject("preview")?.optJSONObject("profile")
                value.put("left", profile?.optString("left").orEmpty()).put("right", profile?.optString("right").orEmpty())
            }
        }
        if (v2.any { it.getString("left") == id || it.getString("right") == id } || recycle.any { it.getString("connection") == id } || jobs.any {
            it.optString("state") in setOf("QUEUED", "RUNNING") &&
                listOf(it.optString("connection"), it.optString("left"), it.optString("right")).contains(id)
        })
            throw TransferException(TransferError.CONFLICT)
        listOf("favorite", "rule").forEach { kind -> dao.v2Records(kind).forEach { row ->
            if (org.json.JSONObject(row.payload).optString("connection") == id) {
                if (kind == "rule") com.wkq.nas.transfer.work.V2Worker.cancelRule(context, row.id)
                dao.deleteV2(kind, row.id)
            }
        } }
        if (dao.removeConnection(id) == 0) throw TransferException(TransferError.CONFLICT)
        dao.deleteV2("location", id)
        dao.deleteProfilesForConnection(id)
    }
    @Deprecated("Use capabilities(connectionId) for provider capabilities")
    fun capabilities(protocol: StorageProtocol) = StorageCapabilities(
        renameDirectories = protocol != StorageProtocol.SAF,
        deleteDirectories = protocol in setOf(StorageProtocol.LOCAL, StorageProtocol.SFTP),
        atomicRename = false,
        reliableModifiedTime = protocol in setOf(StorageProtocol.LOCAL, StorageProtocol.SMB, StorageProtocol.SFTP),
        quota = protocol == StorageProtocol.LOCAL)
    private suspend fun <T> use(id: String, invalidate: Boolean = true, action: suspend (StorageProvider, StorageProtocol) -> T): T = withContext(Dispatchers.IO) { providerCall {
        if (invalidate) invalidateListingCache()
        val connection = dao.connection(id) ?: throw TransferException(TransferError.NOT_FOUND)
        try {
            factory.open(connection).use { action(it, StorageProtocol.valueOf(connection.protocol)) }
        } finally {
            // 操作进行中可能产生新快照；成功、部分失败或取消后都不能继续展示旧目录。
            if (invalidate) invalidateListingCache()
        }
    } }
    suspend fun capabilities(id: String) = use(id, invalidate = false) { provider, _ -> provider.capabilities }
    suspend fun stat(id: String, path: String) = use(id, invalidate = false) { provider, _ -> provider.stat(TransferPaths.normalize(path)) }
    suspend fun operationPage(limit: Int = 200, offset: Int = 0): List<FileOperation> {
        require(limit in 1..1000 && offset >= 0)
        return dao.operationPage(limit, offset).map { it.snapshot() }
    }
    suspend fun clearFinishedOperations() = TransferDatabase.get(context).withTransaction {
        dao.clearFinishedOperations(); dao.clearFinishedMutationJobs()
    }
    suspend fun freeBytes(id: String, directory: String): Long? = use(id, invalidate = false) { provider, _ ->
        var path = TransferPaths.normalize(directory)
        while (provider.stat(path) == null && path.isNotEmpty()) path = TransferPaths.parent(path)
        provider.freeBytes(path)
    }
    suspend fun testConnection(id: String) = use(id) { provider, _ ->
        if (provider.stat("")?.directory != true) throw TransferException(TransferError.NOT_FOUND)
        val job = currentCoroutineContext()
        provider.visit("") { job.ensureActive() }; Unit
    }
    /** 读取有界前缀验证文件访问；不把前缀检查当作完整内容校验。 */
    suspend fun testRead(id: String, path: String) = use(id, invalidate = false) { provider, _ ->
        val normalized = TransferPaths.normalize(path)
        val before = provider.stat(normalized) ?: throw TransferException(TransferError.NOT_FOUND)
        require(!before.directory)
        provider.read(normalized).use { input ->
            val buffer = ByteArray(8192)
            var remaining = 65536
            while (remaining > 0) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer, 0, minOf(buffer.size, remaining))
                if (count < 0) break
                remaining -= count
            }
        }
        if (provider.stat(normalized) != before) throw TransferException(TransferError.SOURCE_CHANGED)
    }
    suspend fun testWrite(id: String, directory: String): StorageWriteTestResult = use(id) { provider, _ ->
        val normalized = TransferPaths.normalize(directory)
        require(provider.stat(normalized)?.directory == true)
        val path = TransferPaths.join(normalized, ".nas-test-${UUID.randomUUID()}.txt")
        if (provider.stat(path) != null) throw TransferException(TransferError.CONFLICT)
        val bytes = UUID.randomUUID().toString().toByteArray(Charsets.UTF_8)
        var failure: TransferError? = null
        var remaining = ""
        try {
            val job = currentCoroutineContext()
            provider.write(path, bytes.inputStream(), bytes.size.toLong()) { job.ensureActive() }
            val actual = provider.read(path).use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(64)
                while (true) {
                    job.ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > bytes.size) throw TransferException(TransferError.VERIFICATION)
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            if (!actual.contentEquals(bytes)) throw TransferException(TransferError.VERIFICATION)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            failure = classify(e).first
        } finally {
            withContext(NonCancellable) {
                try { if (provider.stat(path) != null) provider.delete(path); if (provider.stat(path) != null) remaining = path }
                catch (_: Exception) { remaining = path }
            }
        }
        StorageWriteTestResult(failure, remaining)
    }
    suspend fun list(id: String, directory: String) = use(id) { provider, _ ->
        provider.list(TransferPaths.normalize(directory)).filterNot { privatePart(it.path) }
            .sortedWith(compareBy<FileMetadata> { !it.directory }.thenBy { TransferPaths.name(it.path).lowercase() })
    }
    suspend fun createDirectory(id: String, parent: String, name: String) = use(id) { provider, _ ->
        val path = TransferPaths.join(parent, RenamePlanner.validName(name))
        if (provider.stat(path) != null) throw TransferException(TransferError.CONFLICT)
        provider.mkdirs(path)
    }
    /** 使用有界 Top-N 保留页数据；协议不支持分页时仍需枚举，但不在页面保留完整目录。 */
    suspend fun listPage(id: String, directory: String, limit: Int = 200, query: String = "", sort: Int = 0,
        filter: Int = 0, showHidden: Boolean = false): FilePage = listPageInternal(id, directory, limit, query, sort, filter, showHidden, null)

    /** 仅展示调用可使用缓存；原 listPage 保持每次读取最新目录的契约。 */
    suspend fun listPageCached(id: String, directory: String, limit: Int = 200, query: String = "", sort: Int = 0,
        filter: Int = 0, showHidden: Boolean = false): FilePage = listPageInternal(id, directory, limit, query, sort, filter, showHidden, listingCache)

    /** 选择页短期复用目录快照；提交时仍重新校验源和目标。 */
    suspend fun listPickerPage(id: String, directory: String, limit: Int = 200, query: String = "",
        filter: Int = 0): FilePage = listPageInternal(id, directory, limit, query, 0, filter, false, pickerCache)

    private suspend fun listPageInternal(id: String, directory: String, limit: Int, query: String, sort: Int,
        filter: Int, showHidden: Boolean, cache: DirectoryListingCache?): FilePage = withContext(Dispatchers.IO) { providerCall {
        require(limit in 1..100000)
        val connection = dao.connection(id) ?: throw TransferException(TransferError.NOT_FOUND)
        val root = TransferPaths.normalize(directory)
        val version = cache?.version() ?: 0L
        val cached = cache?.get(connection, root)
        val job = currentCoroutineContext()
        // 路径验证与文件名提取每项只做一次，不在堆比较及最终排序中反复分割路径。
        val order = compareBy<ListingCandidate> { !it.file.directory }.thenComparator { a, b -> when (sort) {
            1 -> b.file.size.compareTo(a.file.size); 2 -> b.file.modifiedAt.compareTo(a.file.modifiedAt)
            else -> a.name.compareTo(b.name, ignoreCase = true)
        } }.thenBy { it.file.path }
        val heap = java.util.PriorityQueue(limit.coerceAtMost(1000), order.reversed())
        var total = 0
        val accept: (FileMetadata) -> Unit = { file ->
            job.ensureActive()
            val name = TransferPaths.name(file.path)
            val extension = name.substringAfterLast('.', "").lowercase(java.util.Locale.ROOT)
            val acceptedType = when (filter) {
                1 -> file.directory
                2 -> !file.directory && (FilePreviewFormats.type(name) in setOf(FilePreviewType.TEXT, FilePreviewType.PDF) ||
                    extension in setOf("doc", "docx", "xls", "xlsx", "ppt", "pptx"))
                3 -> !file.directory && FilePreviewFormats.type(name) == FilePreviewType.IMAGE
                4 -> !file.directory && FilePreviewFormats.type(name) == FilePreviewType.VIDEO
                else -> true
            }
            if (!privatePart(file.path) && (showHidden || !name.startsWith('.')) && name.contains(query, true) && acceptedType) {
                total++
                val candidate = ListingCandidate(file, name)
                if (heap.size < limit) heap.add(candidate)
                else if (order.compare(candidate, heap.peek()) < 0) { heap.poll(); heap.add(candidate) }
            }
        }
        if (cached != null) cached.forEach(accept) else {
            val snapshot = ArrayList<FileMetadata>()
            var cacheable = cache != null
            var chars = 0L
            factory.open(connection).use { provider -> provider.visit(root) { file ->
                accept(file)
                if (cacheable) {
                    chars += file.path.length.toLong() + 64
                    if (snapshot.size < checkNotNull(cache).capacity && chars <= cache.maxChars) snapshot.add(file)
                    else { snapshot.clear(); cacheable = false }
                }
            } }
            if (cacheable) cache?.put(version, connection, root, snapshot)
        }
        FilePage(heap.toList().sortedWith(order).map { it.file }, total)
    } }
    suspend fun scan(id: String, root: String): List<FileMetadata> = use(id) { provider, _ -> walk(provider, root) }
    private suspend fun walk(provider: StorageProvider, root: String): List<FileMetadata> {
        val pending = ArrayDeque<String>().apply { add(TransferPaths.normalize(root)) }
        val seen = mutableSetOf<String>()
        val entries = mutableListOf<FileMetadata>()
        while (pending.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val directory = pending.removeFirst()
            require(seen.add(directory))
            val job = currentCoroutineContext()
            provider.visit(directory) {
                job.ensureActive()
                if (privatePart(it.path)) return@visit
                require(TransferPaths.parent(it.path) == directory)
                entries += it
                if (it.directory) pending.add(it.path)
                if (entries.size > 100000) throw TransferException(TransferError.UNSUPPORTED)
            }
        }
        return entries.sortedBy { it.path }
    }
    suspend fun analyze(id: String, root: String): StorageAnalysis = use(id) { provider, protocol ->
        val entries = walk(provider, root)
        val files = entries.filterNot { it.directory }
        val types = files.groupBy { TransferPaths.name(it.path).substringAfterLast('.', "").lowercase() }
            .mapValues { (_, values) -> values.sumOf { it.size.coerceAtLeast(0) } }
        val free = if (protocol == StorageProtocol.LOCAL) provider.freeBytes(root) else null
        StorageAnalysis(files.size, entries.count { it.directory }, files.sumOf { it.size.coerceAtLeast(0) },
            files.count { it.size < 0 }, types, free)
    }
    suspend fun previewRename(id: String, paths: List<String>, rule: RenameRule): MutationPreview = use(id) { provider, protocol ->
        val sources = selected(provider, paths)
        val names = RenamePlanner.names(sources, rule)
        renamePreview(id, provider, protocol, sources, sources.indices.map { TransferPaths.join(TransferPaths.parent(sources[it].path), names[it]) })
    }
    suspend fun previewRenameTo(id: String, path: String, name: String): MutationPreview = use(id) { provider, protocol ->
        val source = selected(provider, listOf(path))
        renamePreview(id, provider, protocol, source, listOf(TransferPaths.join(TransferPaths.parent(path), RenamePlanner.validName(name))))
    }
    suspend fun resolveConflicts(preview: MutationPreview, policy: ConflictPolicy): MutationPreview = use(preview.connectionId) { provider, protocol ->
        require(preview.kind in setOf("RENAME", "MOVE") && policy in setOf(ConflictPolicy.SKIP, ConflictPolicy.KEEP_BOTH))
        fun key(path: String) = if (protocol == StorageProtocol.SMB) path.lowercase(java.util.Locale.ROOT) else path
        val reserved = mutableSetOf<String>()
        val resolved = mutableListOf<MutationItem>()
        preview.items.forEach { item ->
            if (provider.stat(item.source.path) != item.source) throw TransferException(TransferError.SOURCE_CHANGED)
            var destination = item.targetPath
            val occupied = key(destination) in reserved || item.source.path != destination && provider.stat(destination) != null
            if (occupied) {
                if (policy == ConflictPolicy.SKIP) return@forEach
                val parent = TransferPaths.parent(destination)
                val name = TransferPaths.name(destination)
                val extension = if (!item.source.directory && name.contains('.') && !name.startsWith('.')) ".${name.substringAfterLast('.')}" else ""
                val stem = name.removeSuffix(extension)
                destination = (1..10000).asSequence().map { TransferPaths.join(parent, "$stem ($it)$extension") }
                    .firstOrNull { key(it) !in reserved && provider.stat(it) == null } ?: throw TransferException(TransferError.CONFLICT)
            }
            reserved += key(destination)
            resolved += item.copy(targetPath = destination, conflict = false)
        }
        preview.copy(items = resolved)
    }
    suspend fun previewMove(id: String, paths: List<String>, targetDirectory: String): MutationPreview = use(id) { provider, protocol ->
        val sources = selected(provider, paths)
        require(provider.stat(targetDirectory)?.directory == true)
        renamePreview(id, provider, protocol, sources, sources.map { TransferPaths.join(targetDirectory, TransferPaths.name(it.path)) }).copy(kind = "MOVE")
    }
    suspend fun previewOrganize(id: String, paths: List<String>, mode: OrganizeMode): MutationPreview = use(id) { provider, protocol ->
        val sources = selected(provider, paths)
        require(sources.none { it.directory })
        val formatter = java.text.SimpleDateFormat("yyyy-MM", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
        val targets = sources.map { source ->
            val name = TransferPaths.name(source.path)
            val group = when (mode) {
                OrganizeMode.TYPE -> name.substringAfterLast('.', "").lowercase().takeIf { it.matches(Regex("[a-z0-9]{1,16}")) } ?: "other"
                OrganizeMode.MONTH -> if (source.modifiedAt > 0) formatter.format(java.util.Date(source.modifiedAt)) else "undated"
            }
            TransferPaths.join(TransferPaths.join(TransferPaths.parent(source.path), group), name)
        }
        renamePreview(id, provider, protocol, sources, targets).copy(kind = "MOVE")
    }
    private fun selected(provider: StorageProvider, paths: List<String>): List<FileMetadata> {
        require(paths.isNotEmpty() && paths.size <= 100000 && paths.distinct().size == paths.size)
        return paths.map {
            val path = TransferPaths.normalize(it)
            require(path.isNotEmpty() && !privatePart(path))
            provider.stat(path) ?: throw TransferException(TransferError.NOT_FOUND)
        }
    }
    private fun renamePreview(id: String, provider: StorageProvider, protocol: StorageProtocol,
        sources: List<FileMetadata>, targets: List<String>): MutationPreview {
        if (sources.any { it.directory } && !provider.capabilities.renameDirectories) throw TransferException(TransferError.UNSUPPORTED)
        require(sources.none { parent -> parent.directory && sources.any { it.path.startsWith(parent.path + "/") } })
        fun key(path: String) = if (protocol == StorageProtocol.SMB) path.lowercase() else path
        val duplicate = targets.groupingBy(::key).eachCount()
        return MutationPreview(id, "RENAME", sources.indices.map { index ->
            val source = sources[index]
            val target = TransferPaths.normalize(targets[index])
            require(!source.directory || !target.startsWith(source.path + "/"))
            MutationItem(source, target, duplicate.getValue(key(target)) > 1 || source.path != target && provider.stat(target) != null)
        })
    }
    suspend fun previewDelete(id: String, paths: List<String>): MutationPreview = use(id) { provider, protocol ->
        val sources = selected(provider, paths)
        if (sources.any { it.directory } && !provider.capabilities.deleteDirectories) throw TransferException(TransferError.UNSUPPORTED)
        val all = sources.flatMap { if (it.directory) listOf(it) + walk(provider, it.path) else listOf(it) }
            .distinctBy { it.path }.sortedWith(compareByDescending<FileMetadata> { it.path.count { c -> c == '/' } }.thenBy { it.directory })
        MutationPreview(id, "DELETE", all.map { MutationItem(it, "") })
    }
    suspend fun execute(preview: MutationPreview, batchId: String = UUID.randomUUID().toString()): List<FileOperation> = mutations.withLock {
        use(preview.connectionId) { provider, protocol ->
            require(preview.kind in setOf("RENAME", "MOVE", "DELETE") && preview.items.isNotEmpty())
            require(preview.items.none { it.conflict })
            val paths = preview.items.map { it.source.path }
            val pathSet = paths.toHashSet()
            require(pathSet.size == paths.size)
            preview.items.forEach { item ->
                require(TransferPaths.normalize(item.source.path).isNotEmpty() && !privatePart(item.source.path))
                if (provider.stat(item.source.path) != item.source) throw TransferException(TransferError.SOURCE_CHANGED)
                if (preview.kind == "DELETE") {
                    if (item.source.directory) {
                        if (!provider.capabilities.deleteDirectories) throw TransferException(TransferError.UNSUPPORTED)
                        if (provider.list(item.source.path).any { it.path !in pathSet }) throw TransferException(TransferError.SOURCE_CHANGED)
                    }
                } else {
                    require(item.targetPath.isNotEmpty() && !privatePart(item.targetPath))
                    TransferPaths.normalize(item.targetPath)
                    require(!item.source.directory || !item.targetPath.startsWith(item.source.path + "/"))
                    if (item.source.directory && !provider.capabilities.renameDirectories) throw TransferException(TransferError.UNSUPPORTED)
                    if (item.source.path != item.targetPath && provider.stat(item.targetPath) != null) throw TransferException(TransferError.CONFLICT)
                }
            }
            val batch = batchId
            val records = preview.items.map { OperationEntity(UUID.randomUUID().toString(), batch, preview.connectionId,
                preview.kind, it.source.path, it.targetPath, "RUNNING", null, System.currentTimeMillis()) }
            dao.insertOperations(records)
            records.mapIndexed { index, record ->
                val item = preview.items[index]
                try {
                    currentCoroutineContext().ensureActive()
                    if (preview.kind == "DELETE") {
                        // 父目录 mtime 会被前面的子项删除改变；只在初始预检比对目录快照。
                        if (!item.source.directory && provider.stat(item.source.path) != item.source) throw TransferException(TransferError.SOURCE_CHANGED)
                        if (item.source.directory) provider.deleteEmptyDirectory(item.source.path) else provider.delete(item.source.path)
                    } else if (item.source.path != item.targetPath) {
                        provider.mkdirs(TransferPaths.parent(item.targetPath))
                        if (provider.stat(item.source.path) != item.source) throw TransferException(TransferError.SOURCE_CHANGED)
                        provider.moveNew(item.source.path, item.targetPath) {
                            if (provider.stat(item.source.path) != item.source) throw TransferException(TransferError.SOURCE_CHANGED)
                        }
                    }
                    dao.finishOperation(record.id, "SUCCEEDED", null)
                    record.copy(state = "SUCCEEDED").snapshot()
                } catch (error: Exception) {
                    if (error is CancellationException) {
                        withContext(NonCancellable) { records.drop(index).forEach { dao.finishOperation(it.id, "FAILED", "VERIFICATION") } }
                        throw error
                    }
                    val code = classify(error).first
                    dao.finishOperation(record.id, "FAILED", code.name)
                    record.copy(state = "FAILED", error = code.name).snapshot()
                }
            }
        }
    }
    companion object {
        private val mutations = Mutex()
        internal fun privatePart(path: String) = V2Client.internalPath(path) || TransferPaths.name(path).matches(Regex("\\.nas-[0-9a-fA-F-]{36}\\.(part|backup)"))
    }
}
