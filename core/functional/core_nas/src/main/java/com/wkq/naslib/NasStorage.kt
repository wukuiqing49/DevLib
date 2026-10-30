package com.wkq.naslib

import java.io.Closeable
import java.io.InputStream

/** 阻塞式会话，请在 IO 线程串行使用，关闭返回的流并通过 use 关闭会话。
 * 写入/移动只创建新目标；删除和替换的预览、确认及恢复由宿主负责。
 */
interface NasStorage : Closeable {
    val capabilities: NasCapabilities
        get() = NasCapabilities(false, false, false, false, false)
    fun stat(path: String): NasEntry?
    fun list(path: String): List<NasEntry>
    fun visit(path: String, accept: (NasEntry) -> Unit) { list(path).forEach(accept) }
    fun read(path: String): InputStream
    fun mkdirs(path: String)
    fun write(path: String, input: InputStream, size: Long, progress: (Long) -> Unit)
    fun moveNew(source: String, target: String, check: () -> Unit = {})
    fun delete(path: String)
    fun deleteEmptyDirectory(path: String) { throw NasException(NasError.UNSUPPORTED) }
    fun freeBytes(path: String): Long? = null
    fun setModified(path: String, time: Long) = Unit
    override fun close() = Unit
}
