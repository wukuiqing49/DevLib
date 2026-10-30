package com.wkq.naslib

import java.io.FilterInputStream
import java.io.InputStream

/** 不存账号、不启动后台任务；每次 open 返回独立会话。所有方法为阻塞 IO。 */
object NasClient {
    fun open(connection: NasConnection): NasStorage = nasCall {
        val raw = when (connection.protocol) {
            NasProtocol.SMB -> SmbProvider(connection.root, connection.username, connection.password)
            NasProtocol.WEBDAV -> WebDavProvider(connection.root, connection.username, connection.password, connection.hostKeySha256)
            NasProtocol.SFTP -> SftpProvider(connection.root, connection.username, connection.password, connection.hostKeySha256)
        }
        GuardedStorage(raw)
    }

    fun test(connection: NasConnection): NasEntry = open(connection).use {
        it.stat("")?.takeIf { root -> root.directory } ?: throw NasException(NasError.NOT_FOUND)
    }

    /** 仅允许服务器根；结果不持久保存凭据，取消由宿主的 check 回调提供。 */
    fun listSmbShares(connection: NasConnection, check: () -> Unit = {}): List<String> {
        require(connection.protocol == NasProtocol.SMB && java.net.URI(connection.root).path.orEmpty() in setOf("", "/"))
        return open(connection).use { storage ->
            val names = sortedSetOf<String>()
            storage.visit("") { entry ->
                check()
                if (entry.directory && NasPaths.parent(entry.path).isEmpty()) names += entry.path
                require(names.size <= 10000)
            }
            names.toList()
        }
    }
}

private class GuardedStorage(private val delegate: NasStorage) : NasStorage {
    override val capabilities get() = delegate.capabilities
    override fun stat(path: String) = nasCall { delegate.stat(path) }
    override fun list(path: String) = nasCall { delegate.list(path) }
    override fun visit(path: String, accept: (NasEntry) -> Unit) = nasCall { delegate.visit(path) { entry -> caller { accept(entry) } } }
    override fun read(path: String): InputStream = nasCall {
        object : FilterInputStream(delegate.read(path)) {
            override fun read() = nasCall { super.read() }
            override fun read(buffer: ByteArray, offset: Int, length: Int) = nasCall { super.read(buffer, offset, length) }
            override fun close() = nasCall { super.close() }
        }
    }
    override fun mkdirs(path: String) = nasCall { delegate.mkdirs(path) }
    override fun write(path: String, input: InputStream, size: Long, progress: (Long) -> Unit) =
        nasCall {
            val source = object : FilterInputStream(input) {
                override fun read() = caller { super.read() }
                override fun read(buffer: ByteArray, offset: Int, length: Int) = caller { super.read(buffer, offset, length) }
            }
            delegate.write(path, source, size) { bytes -> caller { progress(bytes) } }
        }
    override fun moveNew(source: String, target: String, check: () -> Unit) = nasCall { delegate.moveNew(source, target) { caller(check) } }
    override fun delete(path: String) = nasCall { delegate.delete(path) }
    override fun deleteEmptyDirectory(path: String) = nasCall { delegate.deleteEmptyDirectory(path) }
    override fun freeBytes(path: String) = nasCall { delegate.freeBytes(path) }
    override fun setModified(path: String, time: Long) = nasCall { delegate.setModified(path, time) }
    override fun close() = nasCall { delegate.close() }
}
