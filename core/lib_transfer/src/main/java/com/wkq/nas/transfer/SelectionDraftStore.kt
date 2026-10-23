package com.wkq.nas.transfer

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.*
import java.util.UUID
import java.util.zip.CheckedInputStream
import java.util.zip.CheckedOutputStream
import java.util.zip.CRC32

/** 私有、限额、带校验的选择草稿；只恢复选择，不恢复执行授权。 */
class SelectionDraftStore(context: Context, private val clock: () -> Long = System::currentTimeMillis) {
    private val directory = File(context.applicationContext.noBackupFilesDir, "selection-drafts")
    suspend fun save(owner: String, paths: Collection<String>): String = withContext(Dispatchers.IO) { lock.withLock {
        require(paths.size in 1..100000 && paths.distinct().size == paths.size)
        require(owner.length <= 4096)
        directory.mkdirs()
        val token = UUID.randomUUID().toString()
        val atomic = AtomicFile(File(directory, token))
        val stream = atomic.startWrite()
        try {
            val checked = CheckedOutputStream(BufferedOutputStream(stream), CRC32())
            val output = DataOutputStream(checked)
            output.writeInt(0x4e415344); output.writeLong(clock()); output.writeUTF(owner); output.writeInt(paths.size)
            for (path in paths) {
                currentCoroutineContext().ensureActive()
                require(TransferPaths.normalize(path) == path)
                val data = path.toByteArray(Charsets.UTF_8)
                require(data.size <= 8192)
                output.writeInt(data.size); output.write(data)
                require(output.size() <= MAX_BYTES - 8)
            }
            val checksum = checked.checksum.value
            output.writeLong(checksum); output.flush()
            atomic.finishWrite(stream)
            // 成功提交后才淘汰旧草稿；失败或取消的保存不能挤掉可恢复选择。
            val existing = directory.listFiles().orEmpty().filter { it.isFile && it.name != token }.sortedByDescending { it.lastModified() }
            var bytes = 0L
            existing.forEachIndexed { index, file ->
                bytes += file.length()
                if (index >= 31 || bytes > 48L * 1024 * 1024 || clock() - file.lastModified() > TTL) file.delete()
            }
            token
        } catch (error: Exception) { atomic.failWrite(stream); throw error }
    } }

    suspend fun load(token: String, owner: String): List<String>? = withContext(Dispatchers.IO) { lock.withLock {
        if (!token.matches(Regex("[a-f0-9-]{36}"))) return@withLock null
        try {
            val file = File(directory, token)
            // 与写入使用同一原子协议，恢复提交被进程终止打断时留下的备份。
            AtomicFile(file).openRead().use { stream ->
                require(stream.channel.size() in 1..MAX_BYTES)
                val checked = CheckedInputStream(BufferedInputStream(stream), CRC32())
                val input = DataInputStream(checked)
                require(input.readInt() == 0x4e415344)
                require(clock() - input.readLong() in 0..TTL)
                require(input.readUTF() == owner)
                val count = input.readInt(); require(count in 1..100000)
                val paths = ArrayList<String>(count)
                repeat(count) {
                    currentCoroutineContext().ensureActive()
                    val size = input.readInt(); require(size in 0..8192 && size.toLong() + 8 <= input.available())
                    val bytes = ByteArray(size); input.readFully(bytes)
                    val path = bytes.toString(Charsets.UTF_8)
                    require(path.toByteArray(Charsets.UTF_8).contentEquals(bytes) && TransferPaths.normalize(path) == path)
                    paths.add(path)
                }
                val checksum = checked.checksum.value
                require(input.readLong() == checksum && input.read() == -1 && paths.distinct().size == paths.size)
                paths
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            null
        }
    } }

    companion object {
        private val lock = Mutex()
        private const val TTL = 7L * 24 * 60 * 60 * 1000
        private const val MAX_BYTES = 16L * 1024 * 1024
    }
}
