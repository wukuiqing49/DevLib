package com.wkq.nas.transfer

import com.wkq.nas.transfer.provider.StorageProvider
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** 只进入选中路径及其祖先，避免局部同步反复遍历无关子树；全目录同步保持完整扫描。 */
internal suspend fun scanSyncScope(provider: StorageProvider, root: String,
    selected: Set<String> = emptySet(), progress: ((Int) -> Unit)? = null): Map<String, FileMetadata> {
    val normalized = TransferPaths.normalize(root)
    require(!V2Client.internalPath(normalized))
    val selection = SyncSelection(selected)
    if (provider.stat(normalized)?.directory != true) throw TransferException(TransferError.NOT_FOUND)
    val pending = ArrayDeque<String>().apply { add(normalized) }
    val result = sortedMapOf<String, FileMetadata>()
    val seen = mutableSetOf<String>()
    val job = currentCoroutineContext()
    while (pending.isNotEmpty()) {
        job.ensureActive()
        val parent = pending.removeFirst()
        require(seen.add(parent))
        provider.visit(parent) { file ->
            job.ensureActive(); job[AdvancedJobControl]?.check()
            require(TransferPaths.parent(file.path) == parent)
            val path = TransferPaths.normalize(file.path)
            require(normalized.isEmpty() || path.startsWith("$normalized/"))
            val relative = if (normalized.isEmpty()) path else path.removePrefix("$normalized/")
            if (!FileClient.privatePart(file.path) && selection.contains(relative)) {
                if (relative !in result && result.size >= 100000) throw TransferException(TransferError.UNSUPPORTED)
                result[relative] = file
                if (file.directory) pending.add(file.path)
                if (result.size % 64 == 0) progress?.invoke(result.size)
            }
        }
    }
    progress?.invoke(result.size)
    return result
}
