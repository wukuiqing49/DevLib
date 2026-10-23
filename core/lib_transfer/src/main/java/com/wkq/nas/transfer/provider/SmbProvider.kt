package com.wkq.nas.transfer.provider

import com.wkq.nas.transfer.*
import jcifs.config.PropertyConfiguration
import jcifs.context.BaseContext
import jcifs.smb.NtlmPasswordAuthenticator
import jcifs.smb.SmbFile
import java.io.InputStream
import java.util.Properties

/** 复用 SiteReport SmbUploader 的 jcifs-ng SMB2/3 配置与流式传输。 */
internal class SmbProvider(root: String, username: String, password: String) : StorageProvider {
    override val capabilities = StorageCapabilities(true, false, false, true, false, serverSideMove = true)
    private val base = java.net.URI(root).let {
        java.net.URI("smb", it.authority, it.path.trimEnd('/') + "/", null, null).toASCIIString()
    }.also {
        if (it.contains('?') || it.contains('#')) throw TransferException(TransferError.INVALID_PATH)
    }
    private val cifs = BaseContext(PropertyConfiguration(Properties().apply {
        setProperty("jcifs.smb.client.minVersion", "SMB202")
        setProperty("jcifs.smb.client.maxVersion", "SMB311")
        // 优先协商签名，保留访客与既有服务器兼容性；签名不等于传输加密。
        setProperty("jcifs.smb.client.signingPreferred", "true")
        setProperty("jcifs.smb.client.dfs.disabled", "true")
        setProperty("jcifs.resolveOrder", "DNS")
        setProperty("jcifs.smb.client.connTimeout", "15000")
        setProperty("jcifs.smb.client.soTimeout", "30000")
        setProperty("jcifs.smb.client.responseTimeout", "30000")
    }))
    private val credentials = cifs.withCredentials(NtlmPasswordAuthenticator(null, username, password))
    private fun file(path: String): SmbFile {
        val normalized = TransferPaths.normalize(path)
        if (normalized.contains('?') || normalized.contains('#')) throw TransferException(TransferError.INVALID_PATH)
        val escaped = java.net.URI(null, null, "/$normalized", null, null).rawPath.removePrefix("/")
        return SmbFile(base + escaped, credentials)
    }
    override fun stat(path: String): FileMetadata? = file(path).use {
        if (!it.exists()) null else FileMetadata(TransferPaths.normalize(path), it.isDirectory, if (it.isDirectory) 0 else it.length(), it.lastModified())
    }
    override fun list(path: String): List<FileMetadata> = SmbFile(file(path).url.toString().trimEnd('/') + "/", credentials).use { directory ->
        directory.listFiles().map { child -> child.use {
            FileMetadata(TransferPaths.join(path, it.name.trimEnd('/')), it.isDirectory, if (it.isDirectory) 0 else it.length(), it.lastModified())
        } }
    }
    override fun read(path: String): InputStream {
        val resource = file(path)
        return object : java.io.FilterInputStream(resource.inputStream) {
            override fun close() { try { super.close() } finally { resource.close() } }
        }
    }
    override fun mkdirs(path: String) {
        if (path.isEmpty()) return
        SmbFile(file(path).url.toString().trimEnd('/') + "/", credentials).use { if (!it.exists()) it.mkdirs() }
    }
    override fun write(path: String, input: InputStream, size: Long, progress: (Long) -> Unit) = file(path).use {
        // createNewFile 使用 SMB FILE_CREATE；临时文件名属于单个任务。
        it.createNewFile()
        it.outputStream.use { output -> copyBytes(input, output, progress) }
        Unit
    }
    override fun moveNew(source: String, target: String, check: () -> Unit) = file(source).use { part -> file(target).use { destination ->
        check()
        if (destination.exists()) throw TransferException(TransferError.CONFLICT)
        part.renameTo(destination, false)
    } }
    override fun delete(path: String) = file(path).use { if (it.exists()) it.delete() }
    override fun setModified(path: String, time: Long) { if (time > 0) file(path).use { it.setLastModified(time) } }
    override fun close() { credentials.close(); cifs.close() }
}
