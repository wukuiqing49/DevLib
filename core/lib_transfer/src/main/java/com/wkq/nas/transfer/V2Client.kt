package com.wkq.nas.transfer

import android.content.Context
import androidx.room.withTransaction
import com.wkq.nas.transfer.data.*
import com.wkq.nas.transfer.provider.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOn
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import com.wkq.nas.transfer.work.withTransferProducer

/** V2 复用已有 Provider 与复制队列；JSON 记录不含认证凭据。 */
class V2Client(context: Context, private val advancedAllowed: () -> Boolean = { true }) {
    private val context = context.applicationContext
    private val db = TransferDatabase.get(this.context)
    private val dao = db.dao()
    private val factory = ProviderFactory(this.context)
    private val files = FileClient(this.context)
    private val transfers = TransferClient.get(this.context)
    private val diagnostics = SyncDiagnostics(this.context)
    private fun requireAdvanced() {
        if (!advancedAllowed()) throw TransferException(TransferError.UNSUPPORTED)
    }
    private suspend fun put(kind: String, id: String, json: JSONObject) {
        db.withTransaction {
            val terminal = kind == "job" && json.getString("state") in setOf("SUCCEEDED", "FAILED", "CANCELED")
            // 结束时直接复制轻量元数据，不先序列化/解析可能有数万项的旧预览。
            val saved = JSONObject()
            json.keys().forEach { key ->
                if (!terminal || key !in setOf("preview", "items", "groups")) saved.put(key, json.get(key))
            }
            if (kind == "job") {
                val profile = json.optJSONObject("preview")?.optJSONObject("profile")
                saved.put("profileId", profile?.optString("id") ?: json.optString("profileId"))
                saved.put("left", profile?.optString("left") ?: json.optString("left"))
                saved.put("right", profile?.optString("right") ?: json.optString("right"))
            }
            dao.saveV2(V2Record(kind, id, saved.toString()))
            if (kind == "job") dao.saveV2(V2Record("job-status", id, JSONObject().put("state", json.getString("state"))
                .put("name", json.getString("name")).put("error", json.optString("error")).put("createdAt", json.optLong("createdAt"))
                .put("profile", saved.optString("profileId"))
                .put("left", saved.optJSONObject("preview")?.optJSONObject("profile")?.optString("left") ?: saved.optString("left"))
                .put("right", saved.optJSONObject("preview")?.optJSONObject("profile")?.optString("right") ?: saved.optString("right"))
                .put("connection", json.optString("connection")).put("rule", json.optString("rule"))
                .put("runSummary", saved.optJSONObject("runSummary")).toString()))
        }
        if (kind == "job") withContext(Dispatchers.IO) { diagnostics.event(DiagnosticEvent.RESULT, id, json.optString("profileId"),
            reason = json.optString("error").takeIf { it.isNotEmpty() }?.let { TransferError.valueOf(it) },
            outcome = DiagnosticOutcome.valueOf(json.getString("state"))) }
    }
    private suspend fun record(kind: String, id: String) = dao.v2Record(kind, id)?.let { JSONObject(it.payload) }
    private suspend fun <T> use(id: String, action: suspend (StorageProvider) -> T): T = withContext(Dispatchers.IO) {
        usingProvider(dao.connection(id) ?: throw TransferException(TransferError.NOT_FOUND), factory::open, action)
    }
    fun observeFavorites() = dao.observeV2("favorite").map { rows -> rows.map { row ->
        val p = JSONObject(row.payload); FavoriteLocation(row.id, p.getString("name"), p.getString("connection"), p.getString("path"))
    } }
    suspend fun favorite(name: String, connectionId: String, path: String): String = use(connectionId) { provider ->
        require(name.isNotBlank()); val normalized = TransferPaths.normalize(path)
        require(provider.stat(normalized)?.directory == true && !internalPath(normalized))
        val id = UUID.randomUUID().toString()
        put("favorite", id, JSONObject().put("name", name.trim()).put("connection", connectionId).put("path", normalized)); id
    }
    suspend fun removeFavorite(id: String) = dao.deleteV2("favorite", id)
    suspend fun renameFavorite(id: String, name: String) {
        require(name.isNotBlank() && name.length <= 100)
        val value = record("favorite", id) ?: throw TransferException(TransferError.NOT_FOUND)
        put("favorite", id, value.put("name", name.trim()))
    }
    suspend fun validateFavorite(location: FavoriteLocation) = use(location.connectionId) {
        if (it.stat(location.path)?.directory != true) throw TransferException(TransferError.NOT_FOUND)
    }
    suspend fun exportConnections(): String = ConnectionExchange.encode(files.observeConnections().first())
    suspend fun importConnections(text: String): List<String> = withContext(Dispatchers.IO) {
        val values = ConnectionExchange.decode(text)
        db.withTransaction { values.map { (name, config) ->
            val id = UUID.randomUUID().toString()
            transfers.saveConnection(id, config, displayName = name); id
        } }
    }
    suspend fun analyze(connectionId: String, root: String): DetailedAnalysis = use(connectionId) { provider ->
        val all = scan(provider, root).values.filterNot { it.directory }
        val duplicates = mutableListOf<DuplicateGroup>()
        all.filter { it.size >= 0 }.groupBy { it.size }.filterValues { it.size > 1 }.values.forEach { group ->
            val byDigest = mutableMapOf<String, MutableList<ContentStamp>>()
            group.forEach { file ->
                val content = stamp(provider, file)
                byDigest.getOrPut(content.digest) { mutableListOf() }.add(content)
            }
            byDigest.filterValues { it.size > 1 }.forEach { (hash, values) ->
                duplicates += DuplicateGroup(hash, values.sortedBy { it.metadata.path })
            }
        }
        // 页面已经按 200 项增量展示，底层不能截断，否则“加载更多”无法访问剩余结果。
        DetailedAnalysis(all.sortedWith(compareByDescending<FileMetadata> { it.size }.thenBy { it.path }), duplicates, all.size, all.count { it.size < 0 })
    }
    suspend fun cleanup(connectionId: String, groups: List<DuplicateGroup>, selected: Set<String>): String = withContext(Dispatchers.IO) {
        val candidates = AdvancedSyncPlanner.cleanup(groups, selected)
        // 留下的副本也必须重新核验，不能仅核实待回收项。
        use(connectionId) { provider -> groups.forEach { group -> group.files.forEach { verify(provider, it) } } }
        enqueue(JSONObject().put("type", "cleanup").put("connection", connectionId).put("name", "cleanup")
            .put("groups", JSONArray().apply { groups.forEach { group -> put(JSONArray().apply { group.files.forEach { put(V2Codec.stamp(it)) } }) } })
            .put("items", JSONArray().apply { candidates.forEach { put(V2Codec.stamp(it)) } }))
    }
    suspend fun saveRule(rule: ArchiveRule) {
        requireAdvanced()
        require(rule.id.isNotBlank() && rule.name.isNotBlank() && rule.minimumAgeDays in 0..36500)
        require(rule.intervalMinutes == 0L || rule.intervalMinutes in 15..525600)
        require(rule.extensions.isNotEmpty() && rule.extensions.all { it.matches(Regex("[a-z0-9]{1,16}")) })
        val root = TransferPaths.normalize(rule.root); val destination = TransferPaths.normalize(rule.destination)
        require(destination.isNotEmpty() && root != destination && !internalPath(destination))
        use(rule.connectionId) { require(it.stat(root)?.directory == true) }
        put("rule", rule.id, JSONObject().put("name", rule.name).put("connection", rule.connectionId).put("root", root)
            .put("destination", destination).put("age", rule.minimumAgeDays).put("extensions", JSONArray(rule.extensions.sorted())).put("interval", rule.intervalMinutes))
        com.wkq.nas.transfer.work.V2Worker.scheduleRule(context, rule)
    }
    suspend fun rules(): List<ArchiveRule> = dao.v2Records("rule").map { row ->
        val p = JSONObject(row.payload); val ext = p.getJSONArray("extensions")
        ArchiveRule(row.id, p.getString("name"), p.getString("connection"), p.getString("root"),
            (0 until ext.length()).map { ext.getString(it) }.toSet(), p.getInt("age"), p.getString("destination"), p.optLong("interval"))
    }
    suspend fun deleteRule(id: String) {
        com.wkq.nas.transfer.work.V2Worker.cancelRule(context, id)
        dao.deleteV2("rule", id); dao.deleteV2("rule-pending", id)
    }
    suspend fun previewArchive(rule: ArchiveRule): MutationPreview = use(rule.connectionId) { provider ->
        val threshold = System.currentTimeMillis() - rule.minimumAgeDays.toLong() * 86400000
        val items = scan(provider, rule.root).values.filter { f -> !f.directory &&
            !f.path.startsWith(rule.destination + "/") && f.path != rule.destination &&
            TransferPaths.name(f.path).substringAfterLast('.', "").lowercase(java.util.Locale.ROOT) in rule.extensions &&
            (rule.minimumAgeDays == 0 || f.modifiedAt > 0 && f.modifiedAt <= threshold) }
        require(items.isNotEmpty())
        val targets = items.map { TransferPaths.join(rule.destination, relative(rule.root, it.path)) }
        MutationPreview(rule.connectionId, "MOVE", items.indices.map { i ->
            MutationItem(items[i], targets[i], provider.stat(targets[i]) != null)
        })
    }
    fun observeProfiles() = dao.observeV2("profile").map { it.map { V2Codec.profile(JSONObject(it.payload)) } }
    suspend fun saveProfile(profile: AdvancedSyncProfile) {
        if (profile.mode != SyncMode.INCREMENTAL || profile.intervalMinutes > 0 || profile.deviceTrigger) requireAdvanced()
        require(profile.id.isNotBlank() && profile.name.isNotBlank())
        require(profile.leftRoot == TransferPaths.normalize(profile.leftRoot) && profile.rightRoot == TransferPaths.normalize(profile.rightRoot))
        require(profile.intervalMinutes == 0L || profile.intervalMinutes in 15..525600)
        require(profile.selectedPaths.size <= 10000 && profile.selectedPaths.all {
            it.isNotEmpty() && it == TransferPaths.normalize(it) && !internalPath(it)
        })
        require(profile.selectedPaths.isEmpty() || profile.mode == SyncMode.INCREMENTAL)
        require(!profile.deviceTrigger || listOf(profile.leftId, profile.rightId).any { dao.connection(it)?.protocol == StorageProtocol.SAF.name })
        val left = dao.connection(profile.leftId) ?: throw TransferException(TransferError.NOT_FOUND)
        val right = dao.connection(profile.rightId) ?: throw TransferException(TransferError.NOT_FOUND)
        StorageIdentity.validate(ConnectionConfig(StorageProtocol.valueOf(left.protocol), left.root), profile.leftRoot,
            ConnectionConfig(StorageProtocol.valueOf(right.protocol), right.root), profile.rightRoot, true)
        require(!internalPath(profile.leftRoot) && !internalPath(profile.rightRoot))
        val old = record("profile", profile.id)?.let(V2Codec::profile)
        require(old == null || old.copy(name = profile.name, intervalMinutes = profile.intervalMinutes,
            unmetered = profile.unmetered, charging = profile.charging, deviceTrigger = profile.deviceTrigger) == profile) { "Use new profile for new roots or mode" }
        put("profile", profile.id, V2Codec.profile(profile))
        com.wkq.nas.transfer.work.V2Worker.scheduleProfile(context, profile)
        if (old?.deviceTrigger != profile.deviceTrigger) {
            context.getSharedPreferences("device-sync", Context.MODE_PRIVATE).edit().remove(profile.id).commit()
            if (profile.deviceTrigger) com.wkq.nas.transfer.work.V2Worker.device(context)
        }
    }
    suspend fun deleteProfile(id: String) {
        require(activeJob(id) == null)
        com.wkq.nas.transfer.work.V2Worker.cancelProfile(context, id)
        dao.deleteV2("profile", id); dao.deleteV2("baseline", id); dao.deleteV2("pending", id)
        dao.deleteV2("profile-result", id); dao.deleteV2("schedule-error", id)
        dao.deleteV2("schedule-wait", id)
    }
    suspend fun migrateLegacy(id: String): AdvancedSyncProfile {
        val old = transfers.observeSyncProfiles().first().firstOrNull { it.id == id } ?: throw TransferException(TransferError.NOT_FOUND)
        val existing = record("profile", id)?.let(V2Codec::profile)
        if (existing != null) return existing
        val profile = AdvancedSyncProfile(old.id, old.name, old.sourceId, old.sourcePath, old.targetId, old.targetPath, SyncMode.INCREMENTAL)
        saveProfile(profile)
        return profile
    }
    suspend fun previewSync(profile: AdvancedSyncProfile): AdvancedSyncPreview = previewSync(profile) { }
    suspend fun previewSync(profile: AdvancedSyncProfile, progress: (SyncScanProgress) -> Unit): AdvancedSyncPreview = withContext(Dispatchers.IO) {
        val report: (SyncScanProgress) -> Unit = { synchronized(progress) { progress(it) } }
        diagnostics.event(DiagnosticEvent.SCAN_START, plan = profile.id)
        // 配置验证与根目录存在检查先于扫描，源离线不能解释成空目录。
        val leftConnection = dao.connection(profile.leftId) ?: throw TransferException(TransferError.NOT_FOUND)
        val rightConnection = dao.connection(profile.rightId) ?: throw TransferException(TransferError.NOT_FOUND)
        StorageIdentity.validate(ConnectionConfig(StorageProtocol.valueOf(leftConnection.protocol), leftConnection.root), profile.leftRoot,
            ConnectionConfig(StorageProtocol.valueOf(rightConnection.protocol), rightConnection.root), profile.rightRoot, true)
        suspend fun inspect(id: String, root: String, side: Int) = use(id) { p ->
            report(SyncScanProgress(side, SyncScanStage.LISTING, 0, 0))
            val files = scanSyncScope(p, root, profile.selectedPaths) { report(SyncScanProgress(side, SyncScanStage.LISTING, it, 0)) }
            var completed = 0
            report(SyncScanProgress(side, SyncScanStage.VERIFYING, 0, files.size))
            val stamps = sortedMapOf<String, ContentStamp>()
            files.forEach { (path, metadata) ->
                checkAdvancedJob(); stamps[path] = stamp(p, metadata)
                if (++completed % 64 == 0 || completed == files.size) report(SyncScanProgress(side, SyncScanStage.VERIFYING, completed, files.size))
            }
            stamps
        }
        // 每端一个独立会话，至多两路读取；同连接的 Worker 会话池仍串行保护。
        val (left, right) = coroutineScope {
            val left = async { inspect(profile.leftId, profile.leftRoot, 0) }
            val right = async { inspect(profile.rightId, profile.rightRoot, 1) }
            left.await() to right.await()
        }
        if (profile.selectedPaths.any { path -> path !in left }) throw TransferException(TransferError.SOURCE_CHANGED)
        val baselineJson = record("baseline", profile.id) ?: JSONObject()
        val baseline = baselineJson.keys().asSequence().associateWith { path ->
            val p = baselineJson.getJSONObject(path)
            SyncBaseline(p.optString("left").ifEmpty { null }, p.optString("right").ifEmpty { null })
        }
        val preview = AdvancedSyncPreview(profile, AdvancedSyncPlanner.plan(profile.mode, left, right,
            baseline.filterKeys(SyncSelection(profile.selectedPaths)::contains)))
        diagnostics.event(DiagnosticEvent.SCAN_END, plan = profile.id, count = preview.items.size.toLong())
        preview
    }
    suspend fun enqueueSync(preview: AdvancedSyncPreview, conflictWinner: Int = 0): String =
        enqueueSync(preview, conflictWinner) { }

    /** 保留原调用契约，为提交前的完整复核提供真实进度，不复用展示快照。 */
    suspend fun enqueueSync(preview: AdvancedSyncPreview, conflictWinner: Int = 0,
        progress: (SyncScanProgress) -> Unit): String = withContext(Dispatchers.IO) {
        if (preview.profile.mode != SyncMode.INCREMENTAL) requireAdvanced()
        require(conflictWinner in 0..2)
        activeJob(preview.profile.id)?.let { return@withContext it.id }
        val fresh = previewSync(preview.profile, progress)
        if (fresh != preview) throw TransferException(TransferError.SOURCE_CHANGED)
        require(conflictWinner != 0 || preview.items.none { it.action == AdvancedAction.CONFLICT })
        val id = enqueue(JSONObject().put("type", "sync").put("name", preview.profile.name).put("winner", conflictWinner)
            .put("runSummary", JSONObject().put("mode", preview.profile.mode.name)
                .put("left", preview.profile.leftId).put("right", preview.profile.rightId)
                .put("leftRoot", preview.profile.leftRoot).put("rightRoot", preview.profile.rightRoot)
                .put("copyLeft", preview.items.count { it.action == AdvancedAction.COPY_LEFT })
                .put("copyRight", preview.items.count { it.action == AdvancedAction.COPY_RIGHT })
                .put("recycle", preview.items.count { it.action in setOf(AdvancedAction.RECYCLE_LEFT, AdvancedAction.RECYCLE_RIGHT) })
                .put("conflicts", preview.items.count { it.action == AdvancedAction.CONFLICT }))
            .put("preview", V2Codec.preview(preview)))
        db.withTransaction {
            dao.deleteV2("schedule-wait", preview.profile.id); dao.deleteV2("schedule-error", preview.profile.id)
            dao.deleteV2("pending", preview.profile.id)
        }
        id
    }
    private suspend fun enqueue(json: JSONObject): String {
        val id = UUID.randomUUID().toString()
        json.put("state", "QUEUED").put("error", "").put("createdAt", System.currentTimeMillis())
        db.withTransaction {
            if (dao.v2Records("job-status").any { JSONObject(it.payload).getString("state") in setOf("QUEUED", "RUNNING") })
                throw TransferException(TransferError.CONFLICT)
            put("job", id, json)
        }
        com.wkq.nas.transfer.work.V2Worker.schedule(context)
        withContext(Dispatchers.IO) { diagnostics.event(DiagnosticEvent.ENQUEUED, id, json.optJSONObject("preview")?.optJSONObject("profile")?.optString("id").orEmpty()) }
        return id
    }
    /** 新任务轻量快照在结束后保留；旧任务没有快照时返回null，不用当前配置伪造历史。 */
    suspend fun syncRunSummary(id: String): Map<String, String>? = withContext(Dispatchers.IO) {
        record("job", id)?.optJSONObject("runSummary")?.let { value -> value.keys().asSequence().associateWith { value.get(it).toString() } }
    }
    fun observeJobs() = kotlinx.coroutines.flow.combine(dao.observeV2("job-status"), TaskHistory(context).observeHidden()) { rows, hidden -> rows.filter { "v2:${it.id}" !in hidden }.map {
        val p = JSONObject(it.payload); V2Job(it.id, p.getString("state"), p.optString("error"), p.getString("name"), p.optLong("createdAt"), p.optString("profile"), p.optString("left"), p.optString("right"))
    } }.flowOn(Dispatchers.IO)
    suspend fun activeJob(profileId: String) = observeJobs().first().firstOrNull { it.profileId == profileId && it.state in setOf("QUEUED", "RUNNING") }
    suspend fun rememberUnchanged(profile: AdvancedSyncProfile) {
        rememberUnchanged(previewSync(profile))
    }
    /** 保存本次已验证的无变化快照，不再次读取全部内容；后续操作仍重新扫描。 */
    suspend fun rememberUnchanged(preview: AdvancedSyncPreview): Unit = withContext(Dispatchers.IO) {
        require(!AdvancedSyncPlanner.changed(preview))
        val profile = preview.profile
        val baseline = JSONObject()
        preview.items.forEach { baseline.put(it.relative, JSONObject().put("left", it.left?.digest.orEmpty()).put("right", it.right?.digest.orEmpty())) }
        db.withTransaction {
            put("baseline", profile.id, baseline)
            put("profile-result", profile.id, JSONObject().put("state", "UNCHANGED").put("time", System.currentTimeMillis()))
            dao.deleteV2("pending", profile.id)
            dao.deleteV2("schedule-wait", profile.id); dao.deleteV2("schedule-error", profile.id)
        }
    }
    fun observeProfileResults() = dao.observeV2("profile-result").map { rows -> rows.associate { row ->
        val p = JSONObject(row.payload); row.id to (p.getString("state") to p.getLong("time"))
    } }
    fun observeWaiting() = kotlinx.coroutines.flow.combine(dao.observeV2Heads("pending"), dao.observeV2("schedule-wait")) { pending, waiting ->
        waiting.associate { it.id to SyncWait.valueOf(JSONObject(it.payload).getString("state")) } + pending.associate { it.id to SyncWait.REVIEW }
    }
    fun observeScheduleErrors() = dao.observeV2("schedule-error").map { rows -> rows.associate { row ->
        row.id to TransferError.valueOf(JSONObject(row.payload).getString("error"))
    } }
    suspend fun recycles(): List<RecycleEntry> = dao.v2Records("recycle").map { V2Codec.recycle(JSONObject(it.payload)) }
    suspend fun restore(entry: RecycleEntry): String = use(entry.connectionId) { provider ->
        val actual = checkedRecycle(entry)
        require(actual.state in setOf("READY", "PREPARING", "RESTORED"))
        if (if (entry.directory) provider.stat(entry.storedPath)?.directory != true else provider.stat(entry.storedPath)?.size != entry.size || digest(provider, entry.storedPath) != entry.digest)
            throw TransferException(TransferError.VERIFICATION)
        var target = entry.originalPath
        if (provider.stat(target) != null) target = (1..10000).asSequence().map { "${entry.originalPath}.restored-$it" }
            .firstOrNull { provider.stat(it) == null } ?: throw TransferException(TransferError.CONFLICT)
        provider.mkdirs(TransferPaths.parent(target))
        if (entry.directory) provider.mkdirs(target) else {
            val job = currentCoroutineContext()
            provider.read(entry.storedPath).use { provider.write(target, it, entry.size) { job.ensureActive() } }
            if (digest(provider, target) != entry.digest) throw TransferException(TransferError.VERIFICATION)
        }
        put("recycle", entry.id, V2Codec.recycle(entry.copy(state = "RESTORED")))
        target // 保留回收副本，恢复失败时仍可重试。
    }
    suspend fun purge(entry: RecycleEntry) = use(entry.connectionId) { provider ->
        val actual = checkedRecycle(entry)
        require(actual.state in setOf("READY", "RESTORED"))
        if (!entry.directory && provider.stat(entry.storedPath) != null && digest(provider, entry.storedPath) != entry.digest)
            throw TransferException(TransferError.VERIFICATION)
        if (provider.stat(entry.storedPath) != null) {
            if (entry.directory) provider.deleteEmptyDirectory(entry.storedPath) else provider.delete(entry.storedPath)
        }
        dao.deleteV2("recycle", entry.id)
    }
    private suspend fun checkedRecycle(entry: RecycleEntry): RecycleEntry {
        val actual = record("recycle", entry.id)?.let(V2Codec::recycle) ?: throw TransferException(TransferError.NOT_FOUND)
        require(entry.copy(state = actual.state) == actual && entry.storedPath == ".nas-recycle/${entry.id}/content")
        return actual
    }
    /** Worker 串行调用；已开始的操作中断后标失败，人工重新扫描，绝不重放删除。 */
    internal suspend fun runPending() = withWorkerProviders(factory::open) {
        dao.v2Ids("job").forEach { id ->
            val p = try { jobSummary(id) ?: record("job", id) ?: return@forEach }
            catch (error: Exception) {
                if (error is CancellationException) throw error
                finishJob(id, JSONObject().put("name", "sync"), "FAILED", classify(error).first.name)
                return@forEach
            }
            if (p.getString("state") in setOf("RUNNING", "CANCELED")) {
                val interrupted = p.getString("state") == "RUNNING"
                diagnostics.event(DiagnosticEvent.RECOVERY, id)
                transfers.controlBatch(id, 2)
                if (interrupted) finishJob(id, p, "FAILED", "VERIFICATION")
            }
        }
        dao.v2Ids("job").forEach { id ->
            val summary = jobSummary(id)
            if (summary != null && summary.getString("state") != "QUEUED") return@forEach
            var p = summary ?: JSONObject().put("name", "sync").put("state", "QUEUED")
            try {
                p = record("job", id) ?: throw TransferException(TransferError.NOT_FOUND)
                if (p.getString("state") != "QUEUED") return@forEach
                val claimed = db.withTransaction {
                    if (jobSummary(id)?.optString("state")?.let { it != "QUEUED" } == true) false
                    else { put("job", id, p.put("state", "RUNNING")); true }
                }
                if (!claimed) return@forEach
                diagnostics.event(DiagnosticEvent.WORKER_START, id)
                if (p.getString("type") == "archive" || p.getString("type") == "sync" &&
                    V2Codec.preview(p.getJSONObject("preview")).profile.mode != SyncMode.INCREMENTAL) requireAdvanced()
                withTransferProducer { withContext(AdvancedJobControl({ runBlocking { checkJob(id) } })) {
                    if (p.getString("type") == "sync") executeSync(V2Codec.preview(p.getJSONObject("preview")), p.getInt("winner"), id)
                    else if (p.getString("type") == "archive") executeArchive(p, id)
                    else executeCleanup(p, id)
                } }
                if (finishJob(id, p, "SUCCEEDED")) {
                    p.optJSONObject("preview")?.optJSONObject("profile")?.optString("id")?.let { profileId ->
                        put("profile-result", profileId, JSONObject().put("state", "SUCCEEDED").put("time", System.currentTimeMillis()).put("run", id))
                    }
                }
            } catch (e: Exception) {
                withContext(NonCancellable) { finishJob(id, p, "FAILED", classify(e).first.name) }
                if (e is CancellationException) throw e
            }
        }
    }
    private suspend fun executeCleanup(p: JSONObject, jobId: String) {
        val connection = p.getString("connection"); val groups = p.getJSONArray("groups")
        val items = p.getJSONArray("items")
        use(connection) { provider ->
            for (i in 0 until groups.length()) { val group = groups.getJSONArray(i)
                for (j in 0 until group.length()) verify(provider, checkNotNull(V2Codec.stamp(group.getJSONObject(j))))
            }
            for (i in 0 until items.length()) { checkJob(jobId); recycle(connection, provider, checkNotNull(V2Codec.stamp(items.getJSONObject(i)))) }
        }
    }
    private suspend fun executeSync(preview: AdvancedSyncPreview, winner: Int, jobId: String) {
        if (previewSync(preview.profile) != preview) throw TransferException(TransferError.SOURCE_CHANGED)
        val profile = preview.profile
        // 在首个变更前拒绝不支持的目录回收与类型冲突。
        preview.items.forEach { item ->
            if (item.left != null && item.right != null && item.left.metadata.directory != item.right.metadata.directory)
                throw TransferException(TransferError.UNSUPPORTED)
            if (item.action == AdvancedAction.RECYCLE_LEFT && item.left?.metadata?.directory == true)
                use(profile.leftId) { if (!it.capabilities.deleteDirectories) throw TransferException(TransferError.UNSUPPORTED) }
            if (item.action == AdvancedAction.RECYCLE_RIGHT && item.right?.metadata?.directory == true)
                use(profile.rightId) { if (!it.capabilities.deleteDirectories) throw TransferException(TransferError.UNSUPPORTED) }
        }
        val ordered = preview.items.sortedWith(compareBy<AdvancedSyncItem> {
            if (it.action in setOf(AdvancedAction.RECYCLE_LEFT, AdvancedAction.RECYCLE_RIGHT)) 1 else 0
        }.thenBy { if (it.action in setOf(AdvancedAction.RECYCLE_LEFT, AdvancedAction.RECYCLE_RIGHT)) -it.relative.count { c -> c == '/' } else it.relative.count { c -> c == '/' } })
        ordered.forEach { item ->
            checkJob(jobId)
            currentCoroutineContext().ensureActive()
            val action = if (item.action == AdvancedAction.CONFLICT) when (winner) {
                1 -> if (item.left == null) AdvancedAction.RECYCLE_RIGHT else AdvancedAction.COPY_RIGHT
                2 -> if (profile.mode != SyncMode.TWO_WAY) AdvancedAction.SAME else if (item.right == null) AdvancedAction.RECYCLE_LEFT else AdvancedAction.COPY_LEFT
                else -> throw TransferException(TransferError.CONFLICT)
            } else item.action
            when (action) {
                AdvancedAction.COPY_LEFT, AdvancedAction.COPY_RIGHT -> {
                    val toRight = action == AdvancedAction.COPY_RIGHT
                    val source = checkNotNull(if (toRight) item.left else item.right)
                    val target = if (toRight) item.right else item.left
                    val sourceId = if (toRight) profile.leftId else profile.rightId
                    val targetId = if (toRight) profile.rightId else profile.leftId
                    val destination = TransferPaths.join(if (toRight) profile.rightRoot else profile.leftRoot, item.relative)
                    use(sourceId) { verify(it, source) }
                    if (target != null && !source.metadata.directory) use(targetId) { recycle(targetId, it, target) }
                    if (target != null && source.metadata.directory != target.metadata.directory) throw TransferException(TransferError.CONFLICT)
                    val request = TransferRequest(sourceId, source.metadata.path, targetId, destination, ConflictPolicy.FAIL,
                        TransferKind.SYNC, source.metadata.size, source.metadata.modifiedAt, verifyContent = true)
                    val id = transfers.enqueueBatch(listOf(request), jobId).single()
                    val finished: TaskSnapshot
                    try {
                        finished = awaitTransfer(id, jobId)
                    } catch (e: Exception) {
                        withContext(NonCancellable) { transfers.cancel(id) }
                        throw e
                    }
                    if (finished.state != TaskState.SUCCEEDED && finished.state != TaskState.SKIPPED)
                        throw TransferException(finished.error ?: TransferError.VERIFICATION)
                    use(targetId) { provider ->
                        if (source.metadata.directory) require(provider.stat(destination)?.directory == true)
                        else if (digest(provider, destination) != source.digest) throw TransferException(TransferError.VERIFICATION)
                    }
                }
                AdvancedAction.RECYCLE_LEFT -> use(profile.leftId) { recycle(profile.leftId, it, checkNotNull(item.left)) }
                AdvancedAction.RECYCLE_RIGHT -> use(profile.rightId) { recycle(profile.rightId, it, checkNotNull(item.right)) }
                else -> Unit
            }
        }
        checkJob(jobId)
        val result = previewSync(profile)
        if (profile.mode == SyncMode.TWO_WAY && result.items.any { it.left?.digest != it.right?.digest })
            throw TransferException(TransferError.SOURCE_CHANGED)
        val baseline = JSONObject()
        result.items.forEach { item -> baseline.put(item.relative, JSONObject().put("left", item.left?.digest.orEmpty()).put("right", item.right?.digest.orEmpty())) }
        put("baseline", profile.id, baseline)
        dao.deleteV2("pending", profile.id)
    }
    internal suspend fun recycle(connectionId: String, provider: StorageProvider, stamp: ContentStamp) {
        checkAdvancedJob(force = true)
        require(!internalPath(stamp.metadata.path))
        if (stamp.metadata.directory) {
            if (!provider.capabilities.deleteDirectories) throw TransferException(TransferError.UNSUPPORTED)
            require(provider.stat(stamp.metadata.path)?.directory == true && provider.list(stamp.metadata.path).isEmpty())
        } else verify(provider, stamp)
        val id = UUID.randomUUID().toString()
        val stored = ".nas-recycle/$id/content"
        val entry = RecycleEntry(id, connectionId, stamp.metadata.path, stored, stamp.digest, stamp.metadata.size,
            System.currentTimeMillis(), "PREPARING", stamp.metadata.directory)
        put("recycle", id, V2Codec.recycle(entry))
        provider.mkdirs(TransferPaths.parent(stored))
        if (stamp.metadata.directory) {
            provider.mkdirs(stored)
            checkAdvancedJob(force = true)
            provider.deleteEmptyDirectory(stamp.metadata.path)
        } else {
            val job = currentCoroutineContext(); val control = job[AdvancedJobControl]
            provider.freeBytes(TransferPaths.parent(stored))?.let { if (it < stamp.metadata.size) throw TransferException(TransferError.SPACE) }
            provider.read(stamp.metadata.path).use { provider.write(stored, it, stamp.metadata.size) { job.ensureActive(); control?.check() } }
            if (digest(provider, stored) != stamp.digest) throw TransferException(TransferError.VERIFICATION)
            verify(provider, stamp)
            checkAdvancedJob(force = true)
            provider.delete(stamp.metadata.path)
        }
        put("recycle", id, V2Codec.recycle(entry.copy(state = "READY")))
    }
    suspend fun cancelJob(id: String) {
        db.withTransaction {
            val p = jobSummary(id) ?: record("job", id) ?: throw TransferException(TransferError.NOT_FOUND)
            require(p.getString("state") in setOf("QUEUED", "RUNNING"))
            put("job", id, p.put("state", "CANCELED"))
        }
    }
    private suspend fun checkJob(id: String) {
        currentCoroutineContext().ensureActive()
        if (jobSummary(id)?.getString("state") != "RUNNING") throw TransferException(TransferError.VERIFICATION)
    }
    /** 同时观察父任务取消与文件终态，不让快速小文件等待固定轮询间隔。 */
    internal suspend fun awaitTransfer(taskId: String, jobId: String): TaskSnapshot =
        kotlinx.coroutines.flow.combine(dao.observeTask(taskId), dao.observeJobStatus(jobId)) { task, parent ->
            if (parent == null || JSONObject(parent.payload).optString("state") != "RUNNING")
                throw TransferException(TransferError.VERIFICATION)
            task?.snapshot() ?: throw TransferException(TransferError.NOT_FOUND)
        }.first { it.state in TERMINAL }
    private suspend fun jobSummary(id: String) = record("job-status", id)?.also { it.put("profileId", it.optString("profile")) }
    /** 与取消共用事务，完成/失败不能覆盖已落库的取消决定。 */
    private suspend fun finishJob(id: String, value: JSONObject, state: String, error: String = ""): Boolean = db.withTransaction {
        if (jobSummary(id)?.optString("state") == "CANCELED") false
        else { put("job", id, value.put("state", state).put("error", error)); true }
    }
    suspend fun scheduleErrors(): Map<String, TransferError> = dao.v2Records("schedule-error").associate {
        it.id to TransferError.valueOf(JSONObject(it.payload).getString("error"))
    }
    internal suspend fun periodicRule(id: String) {
        val rule = rules().firstOrNull { it.id == id && it.intervalMinutes > 0 } ?: return
        requireAdvanced()
        dao.deleteV2("schedule-error", "rule:$id")
        if (dao.v2Records("job-status").any { val p = JSONObject(it.payload)
            p.optString("rule") == id && p.optString("state") in setOf("QUEUED", "RUNNING") }) return
        val preview = try { previewArchive(rule) } catch (e: IllegalArgumentException) {
            // 无匹配文件不是失败；配置本身已在保存时验证。
            return
        }
        if (preview.items.any { it.conflict }) {
            put("rule-pending", id, JSONObject().put("conflicts", true)); return
        }
        val stamps = use(rule.connectionId) { provider -> preview.items.map { item -> stamp(provider, item.source) to item.targetPath } }
        enqueue(JSONObject().put("type", "archive").put("name", rule.name).put("rule", id).put("connection", rule.connectionId)
            .put("items", JSONArray().apply { stamps.forEach { (source, target) -> put(JSONObject().put("source", V2Codec.stamp(source)).put("target", target)) } }))
        dao.deleteV2("rule-pending", id)
    }
    private suspend fun executeArchive(p: JSONObject, jobId: String) {
        val id = p.getString("connection"); val items = p.getJSONArray("items")
        for (i in 0 until items.length()) {
            checkJob(jobId)
            val item = items.getJSONObject(i); val source = checkNotNull(V2Codec.stamp(item.getJSONObject("source")))
            val target = item.getString("target")
            use(id) { provider -> verify(provider, source); if (provider.stat(target) != null) throw TransferException(TransferError.CONFLICT) }
            val taskId = transfers.enqueueBatch(listOf(TransferRequest(id, source.metadata.path, id, target, ConflictPolicy.FAIL,
                TransferKind.COPY, source.metadata.size, source.metadata.modifiedAt, verifyContent = true)), jobId).single()
            try {
                val task = awaitTransfer(taskId, jobId)
                if (task.state != TaskState.SUCCEEDED) throw TransferException(task.error ?: TransferError.VERIFICATION)
            } catch (e: Exception) { withContext(NonCancellable) { transfers.cancel(taskId) }; throw e }
            checkJob(jobId)
            use(id) { provider ->
                if (digest(provider, target) != source.digest) throw TransferException(TransferError.VERIFICATION)
                recycle(id, provider, source)
            }
        }
    }
    suspend fun pendingRules(): List<String> = dao.v2Ids("rule-pending")
    internal suspend fun periodic(id: String, trigger: DiagnosticTrigger = DiagnosticTrigger.PERIODIC) {
        diagnostics.event(DiagnosticEvent.ACTION, plan = id, kind = TransferKind.SYNC, trigger = trigger)
        val profile = record("profile", id)?.let(V2Codec::profile) ?: return
        requireAdvanced()
        val remote = listOf(profile.leftId, profile.rightId).any { dao.connection(it)?.protocol !in setOf("LOCAL", "SAF") }
        if (remote && !TransferConnectivity.current(context).allowed(!profile.unmetered)) {
            put("schedule-wait", id, JSONObject().put("state", SyncWait.NETWORK.name))
            diagnostics.event(DiagnosticEvent.WAITING, plan = id, reason = TransferError.NETWORK)
            throw TransferException(TransferError.NETWORK, true)
        }
        if (activeJob(id) != null) return
        if (observeJobs().first().any { it.state in setOf("QUEUED", "RUNNING") }) {
            put("schedule-wait", id, JSONObject().put("state", SyncWait.QUEUE.name))
            diagnostics.event(DiagnosticEvent.WAITING, plan=id, reason=TransferError.CONFLICT, trigger=trigger)
            throw TransferException(TransferError.CONFLICT, true)
        }
        val preview = previewSync(profile)
        dao.deleteV2("schedule-error", id)
        dao.deleteV2("schedule-wait", id)
        if (!AdvancedSyncPlanner.changed(preview)) { rememberUnchanged(preview); return }
        if (AdvancedSyncPlanner.risky(preview)) put("pending", id, V2Codec.preview(preview)) else enqueueSync(preview)
    }
    suspend fun pendingProfiles(): List<String> = dao.v2Ids("pending")
    internal suspend fun deviceAvailable() {
        diagnostics.event(DiagnosticEvent.DEVICE_SIGNAL, trigger = DiagnosticTrigger.DEVICE)
        val memory = context.getSharedPreferences("device-sync", Context.MODE_PRIVATE)
        observeProfiles().first().filter { it.deviceTrigger }.forEach { profile ->
            try {
                val safIds = listOf(profile.leftId, profile.rightId).filter { dao.connection(it)?.protocol == "SAF" }
                val available = safIds.isNotEmpty() && safIds.all { StorageProbe(context).check(it).error == null }
                val previous = memory.getBoolean(profile.id, false)
                memory.edit().putBoolean(profile.id, available).commit()
                if (!available) put("schedule-wait", profile.id, JSONObject().put("state", SyncWait.STORAGE.name))
                if (available && !previous) {
                    val battery = context.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
                    val status = battery?.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1)
                    if (profile.charging && status !in setOf(android.os.BatteryManager.BATTERY_STATUS_CHARGING, android.os.BatteryManager.BATTERY_STATUS_FULL)) {
                        put("schedule-wait", profile.id, JSONObject().put("state", SyncWait.CHARGING.name))
                    }
                    com.wkq.nas.transfer.work.V2Worker.scheduleDevice(context, profile)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                memory.edit().putBoolean(profile.id, false).commit()
                put("schedule-error", profile.id, JSONObject().put("error", classify(e).first.name))
            }
        }
    }
    internal suspend fun devicePeriodic(id: String) {
        val profile = record("profile", id)?.let(V2Codec::profile) ?: return
        if (profile.deviceTrigger) periodic(id, DiagnosticTrigger.DEVICE)
    }
    suspend fun recoverSchedules() {
        val steps = mutableListOf<suspend () -> Unit>()
        dao.v2Ids("profile").forEach { id -> steps += {
            record("profile", id)?.let { com.wkq.nas.transfer.work.V2Worker.scheduleProfile(context, V2Codec.profile(it)) }
            Unit
        } }
        dao.v2Ids("rule").forEach { id -> steps += {
            record("rule", id)?.let { p ->
                val ext = p.getJSONArray("extensions")
                com.wkq.nas.transfer.work.V2Worker.scheduleRule(context, ArchiveRule(id, p.getString("name"), p.getString("connection"),
                    p.getString("root"), (0 until ext.length()).map { ext.getString(it) }.toSet(), p.getInt("age"),
                    p.getString("destination"), p.optLong("interval")))
            }
            Unit
        } }
        steps += { com.wkq.nas.transfer.work.V2Worker.schedule(context) }
        runRecoverySteps(steps) { error ->
            try { diagnostics.event(DiagnosticEvent.RECOVERY, reason = classify(error).first, outcome = DiagnosticOutcome.FAILED) }
            catch (canceled: CancellationException) { throw canceled }
            catch (_: Exception) { android.util.Log.w("NasRecovery", "SCHEDULE_RECOVERY_FAILED") }
        }
    }
    private suspend fun scan(provider: StorageProvider, root: String): Map<String, FileMetadata> =
        scanSyncScope(provider, root)
    private suspend fun stamp(provider: StorageProvider, f: FileMetadata): ContentStamp {
        if (f.directory) return ContentStamp(f, "directory")
        require(f.size >= 0)
        val hash = digest(provider, f.path)
        if (provider.stat(f.path) != f) throw TransferException(TransferError.SOURCE_CHANGED)
        return ContentStamp(f, hash)
    }
    private suspend fun verify(provider: StorageProvider, stamp: ContentStamp) {
        if (provider.stat(stamp.metadata.path) != stamp.metadata) throw TransferException(TransferError.SOURCE_CHANGED)
        if (!stamp.metadata.directory && digest(provider, stamp.metadata.path) != stamp.digest) throw TransferException(TransferError.SOURCE_CHANGED)
        if (provider.stat(stamp.metadata.path) != stamp.metadata) throw TransferException(TransferError.SOURCE_CHANGED)
    }
    private suspend fun digest(provider: StorageProvider, path: String): String {
        val job = currentCoroutineContext(); val control = job[AdvancedJobControl]
        return provider.read(path).use { hash(it) { job.ensureActive(); control?.check() } }
    }
    suspend fun previewContent(connectionId: String, path: String, maxBytes: Int = 1024 * 1024): ByteArray = use(connectionId) { provider ->
        require(maxBytes in 1..4 * 1024 * 1024 && !internalPath(path))
        val metadata = provider.stat(path) ?: throw TransferException(TransferError.NOT_FOUND)
        require(!metadata.directory && metadata.size in 0..maxBytes.toLong())
        val job = currentCoroutineContext()
        val output = java.io.ByteArrayOutputStream()
        provider.read(path).use { input -> val buffer = ByteArray(8192)
            while (true) { job.ensureActive(); val n = input.read(buffer); if (n < 0) break
                require(output.size() + n <= maxBytes); output.write(buffer, 0, n) }
        }
        if (provider.stat(path) != metadata) throw TransferException(TransferError.SOURCE_CHANGED)
        output.toByteArray()
    }
    companion object {
        private val TERMINAL = setOf(TaskState.SUCCEEDED, TaskState.SKIPPED, TaskState.FAILED, TaskState.CANCELED, TaskState.PAUSED)
        internal fun internalPath(path: String) = TransferPaths.normalize(path).split('/').any { it == ".nas-recycle" }
        private fun relative(root: String, path: String): String {
            val r = TransferPaths.normalize(root); val p = TransferPaths.normalize(path)
            require(r.isEmpty() || p.startsWith("$r/")); return if (r.isEmpty()) p else p.removePrefix("$r/")
        }
    }
}
