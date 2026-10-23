package com.wkq.nas.transfer

data class FavoriteLocation(val id: String, val name: String, val connectionId: String, val path: String)
data class ContentStamp(val metadata: FileMetadata, val digest: String)
data class DuplicateGroup(val digest: String, val files: List<ContentStamp>)
data class DetailedAnalysis(val largeFiles: List<FileMetadata>, val duplicates: List<DuplicateGroup>,
    val scannedFiles: Int, val unknownSizes: Int)
enum class SyncMode { INCREMENTAL, TWO_WAY, MIRROR }
enum class SyncWait { REVIEW, NETWORK, CHARGING, STORAGE, QUEUE }
enum class AdvancedAction { COPY_LEFT, COPY_RIGHT, RECYCLE_LEFT, RECYCLE_RIGHT, CONFLICT, SAME, TOMBSTONE }
data class AdvancedSyncProfile(val id: String, val name: String, val leftId: String, val leftRoot: String,
    val rightId: String, val rightRoot: String, val mode: SyncMode, val intervalMinutes: Long = 0,
    val unmetered: Boolean = true, val charging: Boolean = false,
    val selectedPaths: Set<String> = emptySet(), val deviceTrigger: Boolean = false)
data class SyncBaseline(val left: String?, val right: String?)
data class AdvancedSyncItem(val relative: String, val left: ContentStamp?, val right: ContentStamp?, val action: AdvancedAction)
data class AdvancedSyncPreview(val profile: AdvancedSyncProfile, val items: List<AdvancedSyncItem>)
data class ArchiveRule(val id: String, val name: String, val connectionId: String, val root: String,
    val extensions: Set<String>, val minimumAgeDays: Int, val destination: String, val intervalMinutes: Long = 0)
data class RecycleEntry(val id: String, val connectionId: String, val originalPath: String,
    val storedPath: String, val digest: String, val size: Long, val createdAt: Long, val state: String, val directory: Boolean = false)
data class V2Job(val id: String, val state: String, val error: String, val name: String, val createdAt: Long = 0, val profileId: String = "", val leftId: String = "", val rightId: String = "")
data class DiscoveredNas(val name: String, val protocol: StorageProtocol, val host: String, val port: Int)

/** 双向决策只依赖内容摘要与已成功提交的双端基线，不用 mtime 推断删除。 */
object AdvancedSyncPlanner {
    fun plan(mode: SyncMode, left: Map<String, ContentStamp>, right: Map<String, ContentStamp>,
        baseline: Map<String, SyncBaseline>): List<AdvancedSyncItem> {
        val paths = left.keys + right.keys + baseline.keys
        require(paths.size <= 100000)
        return paths.sorted().map { path ->
            require(TransferPaths.normalize(path) == path && path.isNotEmpty())
            val l = left[path]; val r = right[path]; val old = baseline[path]
            val lh = l?.digest; val rh = r?.digest
            val action = when {
                l?.metadata?.directory == true || r?.metadata?.directory == true -> when {
                    l != null && r != null && l.metadata.directory != r.metadata.directory -> AdvancedAction.CONFLICT
                    l != null && r != null -> AdvancedAction.SAME
                    l != null -> if (mode == SyncMode.TWO_WAY && old?.right == "directory") AdvancedAction.RECYCLE_LEFT else AdvancedAction.COPY_RIGHT
                    mode == SyncMode.TWO_WAY -> if (old?.left == "directory") AdvancedAction.RECYCLE_RIGHT else AdvancedAction.COPY_LEFT
                    mode == SyncMode.MIRROR -> AdvancedAction.RECYCLE_RIGHT
                    else -> AdvancedAction.SAME
                }
                lh == rh -> if (l == null) AdvancedAction.TOMBSTONE else AdvancedAction.SAME
                mode != SyncMode.TWO_WAY -> when {
                    l == null -> if (mode == SyncMode.MIRROR) AdvancedAction.RECYCLE_RIGHT else AdvancedAction.SAME
                    mode == SyncMode.INCREMENTAL && old != null && lh == old.left && rh == old.right -> AdvancedAction.SAME
                    mode == SyncMode.INCREMENTAL && r != null && (old == null || rh != old.right) -> AdvancedAction.CONFLICT
                    mode == SyncMode.INCREMENTAL && r == null && old?.right != null -> AdvancedAction.CONFLICT
                    else -> AdvancedAction.COPY_RIGHT
                }
                old == null -> when {
                    l == null -> AdvancedAction.COPY_LEFT
                    r == null -> AdvancedAction.COPY_RIGHT
                    else -> AdvancedAction.CONFLICT
                }
                lh == old.left && rh != old.right -> if (r == null) AdvancedAction.RECYCLE_LEFT else AdvancedAction.COPY_LEFT
                rh == old.right && lh != old.left -> if (l == null) AdvancedAction.RECYCLE_RIGHT else AdvancedAction.COPY_RIGHT
                else -> AdvancedAction.CONFLICT
            }
            AdvancedSyncItem(path, l, r, action)
        }

    }

    fun risky(preview: AdvancedSyncPreview) = preview.items.any {
        it.action in setOf(AdvancedAction.CONFLICT, AdvancedAction.RECYCLE_LEFT, AdvancedAction.RECYCLE_RIGHT) ||
            it.action == AdvancedAction.COPY_RIGHT && it.right != null && it.left?.metadata?.directory != true ||
            it.action == AdvancedAction.COPY_LEFT && it.left != null && it.right?.metadata?.directory != true
    }
    fun changed(preview: AdvancedSyncPreview) = preview.items.any { it.action !in setOf(AdvancedAction.SAME, AdvancedAction.TOMBSTONE) }
    fun inScope(path: String, selected: Set<String>) = selected.isEmpty() || selected.any { path == it || path.startsWith("$it/") || it.startsWith("$path/") }

    fun cleanup(groups: List<DuplicateGroup>, selected: Set<String>): List<ContentStamp> {
        require(selected.isNotEmpty())
        val all = groups.flatMap { it.files }
        val knownPaths = all.mapTo(HashSet()) { it.metadata.path }
        require(selected.all { it in knownPaths })
        groups.forEach { group -> require(group.files.any { it.metadata.path !in selected }) { "Keep at least one copy" } }
        return all.filter { it.metadata.path in selected }.distinctBy { it.metadata.path }
    }
}
