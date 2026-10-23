package com.wkq.nas.transfer.provider

import com.wkq.nas.transfer.*
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.SecurityUtils
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.sftp.FileAttributes
import net.schmizz.sshj.sftp.FileMode
import net.schmizz.sshj.sftp.OpenMode
import net.schmizz.sshj.transport.kex.DHGexSHA256
import net.schmizz.sshj.transport.kex.ECDHNistP
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import java.io.InputStream
import java.net.URI
import java.security.MessageDigest
import java.security.PublicKey
import android.util.Base64

/** 复用 SiteReport SftpUploader 的 sshj、Android JCE 配置与分块写入。 */
internal class SftpProvider(root: String, username: String, password: String, fingerprint: String) : StorageProvider {
    override val capabilities = StorageCapabilities(true, true, false, true, false, serverSideMove = true)
    private val uri = URI(root)
    private val prefix = uri.path.orEmpty().trimEnd('/')
    private val ssh: SSHClient
    private val sftp: net.schmizz.sshj.sftp.SFTPClient
    private val canonicalRoot: String
    init {
        if (fingerprint.isBlank()) throw TransferException(TransferError.HOST_KEY)
        SecurityUtils.setRegisterBouncyCastle(false)
        SecurityUtils.setSecurityProvider(null)
        val config = DefaultConfig().apply {
            keyExchangeFactories = listOf(DHGexSHA256.Factory(), ECDHNistP.Factory521(), ECDHNistP.Factory384(), ECDHNistP.Factory256())
        }
        ssh = SSHClient(config)
        var rejected = false
        try {
            ssh.connectTimeout = 15000
            ssh.timeout = 30000
            ssh.addHostKeyVerifier(object : HostKeyVerifier {
                override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
                    val encoded = Buffer.PlainBuffer().putPublicKey(key).compactData
                    val actual = Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(encoded), Base64.NO_WRAP).trimEnd('=')
                    val matches = MessageDigest.isEqual(actual.toByteArray(), fingerprint.removePrefix("SHA256:").trimEnd('=').toByteArray())
                    rejected = !matches
                    return matches
                }
                override fun findExistingAlgorithms(hostname: String, port: Int): List<String> = emptyList()
            })
            ssh.connect(uri.host, if (uri.port > 0) uri.port else 22)
            ssh.authPassword(username, password)
            sftp = ssh.newSFTPClient()
            canonicalRoot = sftp.canonicalize(prefix.ifEmpty { "/" }).trimEnd('/').ifEmpty { "/" }
        } catch (error: Exception) {
            ssh.close()
            if (rejected) throw TransferException(TransferError.HOST_KEY)
            throw error
        }
    }
    private fun path(relative: String) = "$prefix/${TransferPaths.normalize(relative)}".ifEmpty { "/" }
    private fun checkBoundary(relative: String) {
        var candidate = path(relative)
        while (sftp.statExistence(candidate) == null) candidate = candidate.substringBeforeLast('/').ifEmpty { "/" }
        val resolved = sftp.canonicalize(candidate).trimEnd('/').ifEmpty { "/" }
        require(canonicalRoot == "/" || resolved == canonicalRoot || resolved.startsWith("$canonicalRoot/")) { "Path escapes SFTP root" }
    }
    override fun stat(path: String): FileMetadata? {
        checkBoundary(path)
        return sftp.statExistence(path(path))?.let {
            FileMetadata(TransferPaths.normalize(path), it.type == FileMode.Type.DIRECTORY, it.size, it.mtime * 1000)
        }
    }
    override fun list(path: String): List<FileMetadata> {
        return buildList { visit(path, ::add) }
    }
    override fun visit(path: String, accept: (FileMetadata) -> Unit) {
        checkBoundary(path)
        // SSHJ 的过滤器逐个接收 READDIR 条目；返回 false 避免其额外累积完整列表。
        sftp.ls(path(path)) {
            if (it.attributes.type == FileMode.Type.SYMLINK) throw TransferException(TransferError.UNSUPPORTED)
            accept(FileMetadata(TransferPaths.join(path, it.name), it.attributes.type == FileMode.Type.DIRECTORY,
                it.attributes.size, it.attributes.mtime * 1000))
            false
        }
    }
    override fun read(path: String): InputStream {
        checkBoundary(path)
        val remote = sftp.open(path(path), setOf(OpenMode.READ))
        return object : InputStream() {
            private var offset = 0L
            override fun read(): Int { val one = ByteArray(1); return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 255 }
            override fun read(buffer: ByteArray, off: Int, len: Int): Int {
                val count = remote.read(offset, buffer, off, len)
                if (count > 0) offset += count
                return count
            }
            override fun close() = remote.close()
        }
    }
    override fun mkdirs(path: String) { checkBoundary(path); if (path.isNotEmpty()) sftp.mkdirs(path(path)) }
    override fun write(path: String, input: InputStream, size: Long, progress: (Long) -> Unit) {
        checkBoundary(path)
        sftp.open(path(path), setOf(OpenMode.WRITE, OpenMode.CREAT, OpenMode.EXCL)).use { remote ->
            var offset = 0L
            val buffer = ByteArray(32 * 1024)
            progress(0)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                remote.write(offset, buffer, 0, count)
                offset += count
                progress(offset)
            }
        }
    }
    override fun moveNew(source: String, target: String, check: () -> Unit) {
        check(); checkBoundary(source); checkBoundary(target)
        if (stat(target) != null) throw TransferException(TransferError.CONFLICT)
        sftp.rename(path(source), path(target))
    }
    override fun delete(path: String) { if (stat(path) != null) sftp.rm(path(path)) }
    override fun deleteEmptyDirectory(path: String) {
        require(TransferPaths.normalize(path).isNotEmpty())
        checkBoundary(path)
        sftp.rmdir(path(path))
    }
    override fun setModified(path: String, time: Long) {
        checkBoundary(path)
        if (time > 0) sftp.setattr(path(path), FileAttributes.Builder().withAtimeMtime(time / 1000, time / 1000).build())
    }
    override fun close() { try { sftp.close() } finally { ssh.close() } }
}
