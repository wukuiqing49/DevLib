package com.wkq.nas.transfer

import com.wkq.nas.transfer.data.ConnectionEntity

/** 仅供浏览筛选/加载更多复用；短期、单目录、有界，绝不作为变更或同步的检查依据。 */
internal class DirectoryListingCache(private val clock: () -> Long = { android.os.SystemClock.elapsedRealtime() },
    val capacity: Int = 4096, private val ttlMillis: Long = 5000, val maxChars: Int = 2 * 1024 * 1024) {
    private data class Snapshot(val connection: ConnectionEntity, val root: String,
        val files: List<FileMetadata>, val at: Long)
    private var snapshot: Snapshot? = null
    private var generation = 0L
    @Synchronized fun invalidate() { generation++; snapshot = null }
    @Synchronized fun version() = generation
    @Synchronized fun get(connection: ConnectionEntity, root: String): List<FileMetadata>? {
        val value = snapshot ?: return null
        return value.files.takeIf { value.connection == connection && value.root == root && clock() - value.at in 0 until ttlMillis }
    }
    @Synchronized fun put(version: Long, connection: ConnectionEntity, root: String, files: List<FileMetadata>) {
        if (version == generation && files.size <= capacity && files.sumOf { it.path.length.toLong() + 64 } <= maxChars) snapshot = Snapshot(connection, root, files.toList(), clock())
    }
}
