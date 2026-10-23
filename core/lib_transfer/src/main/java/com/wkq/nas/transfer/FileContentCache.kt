package com.wkq.nas.transfer

import android.content.Context
import com.wkq.nas.transfer.data.TransferDatabase
import com.wkq.nas.transfer.provider.ProviderFactory
import com.wkq.nas.transfer.provider.classify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.NonCancellable
import java.io.File
import java.util.UUID

/** 原始文件不改动；仅在专用缓存完成流式下载后才交给预览或外部应用。 */
class FileContentCache(context: Context) {
    private val context = context.applicationContext
    private val dao = TransferDatabase.get(this.context).dao()
    private val factory = ProviderFactory(this.context)
    private val root get() = File(context.cacheDir, "shared")

    suspend fun prepare(connectionId: String, paths: List<String>, progress: (Long, Long, Int) -> Unit = { _, _, _ -> }): List<File> =
        prepareBounded(connectionId, paths, Long.MAX_VALUE, progress)

    /** 大小未知的文本也在读取过程中限制下载量，不能等整文件下载后才判断。 */
    suspend fun preparePreview(connectionId: String, path: String, maximumBytes: Long,
        progress: (Long, Long, Int) -> Unit = { _, _, _ -> }): File {
        require(maximumBytes > 0)
        return prepareBounded(connectionId, listOf(path), maximumBytes, progress).single()
    }

    suspend fun prepareThumbnail(connectionId: String, path: String, maximumBytes: Long): File =
        background.withPermit { prepareBounded(connectionId, listOf(path), maximumBytes, { _, _, _ -> }, foreground = false).single() }

    private suspend fun prepareBounded(connectionId: String, paths: List<String>, maximumBytes: Long,
        progress: (Long, Long, Int) -> Unit, foreground: Boolean = true): List<File> =
        if (foreground) interactive.withPermit { prepareFiles(connectionId, paths, maximumBytes, progress) }
        else prepareFiles(connectionId, paths, maximumBytes, progress)

    private suspend fun prepareFiles(connectionId: String, paths: List<String>, maximumBytes: Long,
        progress: (Long, Long, Int) -> Unit): List<File> = withContext(Dispatchers.IO) {
        require(paths.isNotEmpty() && paths.size <= 1000 && paths.distinct().size == paths.size)
        val connection = dao.connection(connectionId) ?: throw TransferException(TransferError.NOT_FOUND)
        val directory = lock.withLock {
            if (!root.isDirectory && !root.mkdirs()) throw TransferException(TransferError.SPACE)
            clearExpired()
            File(root, UUID.randomUUID().toString()).also {
                if (!it.mkdir()) throw TransferException(TransferError.SPACE)
                active.add(it.absolutePath)
            }
        }
        try {
            val marker = File(directory, ".preparing").apply { if (!createNewFile()) throw TransferException(TransferError.SPACE) }
            val result = factory.open(connection).use { provider ->
                val sources = paths.map { path ->
                    val normalized = TransferPaths.normalize(path)
                    require(normalized.isNotEmpty())
                    val file = provider.stat(normalized) ?: throw TransferException(TransferError.NOT_FOUND)
                    if (file.directory) throw TransferException(TransferError.UNSUPPORTED)
                    file
                }
                val total = sources.fold(0L) { sum, file -> if (sum < 0 || file.size < 0 || Long.MAX_VALUE - sum < file.size) -1L else sum + file.size }
                if (total > maximumBytes) throw TransferException(TransferError.UNSUPPORTED)
                if (total >= 0 && total > directory.usableSpace - RESERVE) throw TransferException(TransferError.SPACE)
                var done = 0L
                val job = currentCoroutineContext()
                sources.mapIndexed { index, source ->
                    job.ensureActive()
                    // 子目录隔离同名文件，且不使用远程路径作为本机路径。
                    val parent = File(directory, index.toString()).apply { if (!mkdir()) throw TransferException(TransferError.SPACE) }
                    val name = safeName(source.path)
                    val file = File(parent, name)
                    progress(done, total, index)
                    provider.read(source.path).use { input -> file.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var written = 0L
                        while (true) {
                            job.ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (count == 0) continue
                            if (done > maximumBytes - count) throw TransferException(TransferError.UNSUPPORTED)
                            if (file.usableSpace < RESERVE + count) throw TransferException(TransferError.SPACE)
                            output.write(buffer, 0, count)
                            written += count; done += count
                            if (source.size >= 0 && written > source.size) throw TransferException(TransferError.SOURCE_CHANGED)
                            progress(done, total, index)
                        }
                        if (source.size >= 0 && written != source.size) throw TransferException(TransferError.SOURCE_CHANGED)
                    } }
                    if (provider.stat(source.path) != source) throw TransferException(TransferError.SOURCE_CHANGED)
                    file
                }
            }
            if (!marker.delete()) throw TransferException(TransferError.PERMISSION)
            if (!directory.setLastModified(System.currentTimeMillis())) throw TransferException(TransferError.PERMISSION)
            result
        } catch (error: Throwable) {
            directory.deleteRecursively()
            if (error is CancellationException || error is TransferException || error is IllegalArgumentException) throw error
            if (error is Exception) { val (code, retryable) = classify(error); throw TransferException(code, retryable) }
            throw error
        } finally { withContext(NonCancellable) { lock.withLock { active.remove(directory.absolutePath) } } }
    }

    suspend fun clear(): Unit = lock.withLock { withContext(Dispatchers.IO) {
        if (root.exists()) {
            val files = root.listFiles() ?: throw TransferException(TransferError.PERMISSION)
            var failed = false
            if (files.any { it.absolutePath in active }) throw TransferException(TransferError.CONFLICT)
            files.forEach { if (!it.deleteRecursively()) failed = true }
            if (failed) throw TransferException(TransferError.PERMISSION)
        }
    } }

    /** 仅短暂锁住租约表，清理跳过正在准备的目录，不等待网络下载。 */
    suspend fun pruneExpired(): Unit = lock.withLock { withContext(Dispatchers.IO) { clearExpired() } }

    private fun clearExpired() {
        if (!root.exists()) return
        val cutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1000
        val entries = root.listFiles() ?: throw TransferException(TransferError.PERMISSION)
        var failed = false
        entries.filter { it.absolutePath !in active && (it.lastModified() < cutoff || File(it, ".preparing").exists()) }.forEach {
            if (!it.deleteRecursively()) failed = true
        }
        if (failed) throw TransferException(TransferError.PERMISSION)
    }
    companion object {
        private const val RESERVE = 16L * 1024 * 1024
        private val lock = Mutex()
        private val active = mutableSetOf<String>()
        private val interactive = Semaphore(2)
        private val background = Semaphore(1)
        internal fun safeName(path: String): String {
            val raw = TransferPaths.name(path).replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").ifBlank { "file" }
            val suffix = raw.substringAfterLast('.', "").takeIf { it.length in 1..24 }?.let { ".$it" }.orEmpty()
            var stem = if (suffix.isEmpty()) raw else raw.removeSuffix(suffix)
            while ((stem + suffix).toByteArray(Charsets.UTF_8).size > 200 && stem.isNotEmpty()) stem = stem.dropLast(if (stem.last().isLowSurrogate() && stem.length > 1 && stem[stem.lastIndex - 1].isHighSurrogate()) 2 else 1)
            return stem.ifBlank { "file" } + suffix
        }
    }
}
