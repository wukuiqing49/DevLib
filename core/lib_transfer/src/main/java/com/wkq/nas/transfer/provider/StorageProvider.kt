package com.wkq.nas.transfer.provider

import android.content.Context
import com.wkq.nas.transfer.*
import com.wkq.nas.transfer.data.ConnectionEntity
import com.wkq.nas.transfer.security.CredentialVault
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.MessageDigest

internal interface StorageProvider : Closeable {
    val capabilities: StorageCapabilities
        get() = StorageCapabilities(false, false, false, false, false)
    fun stat(path: String): FileMetadata?
    fun list(path: String): List<FileMetadata>
    fun visit(path: String, accept: (FileMetadata) -> Unit) { list(path).forEach(accept) }
    fun read(path: String): InputStream
    fun mkdirs(path: String)
    fun write(path: String, input: InputStream, size: Long, progress: (Long) -> Unit)
    fun moveNew(source: String, target: String, check: () -> Unit = {})
    fun delete(path: String)
    fun deleteEmptyDirectory(path: String) { throw TransferException(TransferError.UNSUPPORTED) }
    fun freeBytes(path: String): Long? = null
    fun setModified(path: String, time: Long) = Unit
    override fun close() = Unit
}

internal class ProviderFactory(private val context: Context) {
    fun open(connection: ConnectionEntity): StorageProvider {
        val password = try {
            if (connection.passwordEncrypted.isEmpty()) "" else CredentialVault.decrypt(connection.passwordEncrypted)
        } catch (_: Exception) { throw TransferException(TransferError.AUTHENTICATION) }
        return when (StorageProtocol.valueOf(connection.protocol)) {
            StorageProtocol.LOCAL -> LocalProvider(connection.root)
            StorageProtocol.SAF -> SafProvider(context, connection.root)
            StorageProtocol.SMB -> SmbProvider(connection.root, connection.username, password)
            StorageProtocol.WEBDAV -> WebDavProvider(connection.root, connection.username, password, connection.hostKeySha256)
            StorageProtocol.SFTP -> SftpProvider(connection.root, connection.username, password, connection.hostKeySha256)
        }
    }
}

/** 有界流式复制，保留 SiteReport UploadIo 的进度做法，补充每块取消检查。 */
internal fun copyBytes(input: InputStream, output: OutputStream, progress: (Long) -> Unit): Long {
    val buffer = ByteArray(64 * 1024)
    var bytes = 0L
    progress(0)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        if (count == 0) continue
        output.write(buffer, 0, count)
        bytes += count
        progress(bytes)
    }
    output.flush()
    return bytes
}

internal fun hash(input: InputStream, check: () -> Unit = {}): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(64 * 1024)
    while (true) {
        check()
        val count = input.read(buffer)
        if (count < 0) break
        if (count > 0) digest.update(buffer, 0, count)
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

/** 保留外层明确错误；只穿透未知包装，避免网络异常被误报成权限或通用失败。 */
internal fun classify(error: Exception): Pair<TransferError, Boolean> {
    var cause: Throwable? = error
    var tls = false
    repeat(8) {
        val current = cause ?: return@repeat
        tls = tls || current is javax.net.ssl.SSLException
        knownFailure(current)?.let { return it }
        cause = current.cause
    }
    // TLS 断流也可能包装连接重置，只有明确的证书异常才引导用户检查证书。
    return if (tls) TransferError.NETWORK to true
        else TransferError.UNKNOWN to (error is java.io.IOException)
}

private fun knownFailure(error: Throwable): Pair<TransferError, Boolean>? {
    if (error is android.system.ErrnoException) return when (error.errno) {
        android.system.OsConstants.ENOSPC -> TransferError.SPACE to false
        android.system.OsConstants.EACCES, android.system.OsConstants.EROFS -> TransferError.PERMISSION to false
        android.system.OsConstants.ENODEV, android.system.OsConstants.ENXIO -> TransferError.STORAGE_UNAVAILABLE to true
        android.system.OsConstants.ENOENT -> TransferError.NOT_FOUND to false
        android.system.OsConstants.ECONNREFUSED, android.system.OsConstants.ECONNRESET,
        android.system.OsConstants.ENETUNREACH, android.system.OsConstants.EHOSTUNREACH,
        android.system.OsConstants.ETIMEDOUT, android.system.OsConstants.EPIPE -> TransferError.NETWORK to true
        else -> null
    }
    return when (error) {
    is TransferException -> error.error to error.retryable
    is SecurityException -> TransferError.PERMISSION to false
    is java.io.FileNotFoundException -> TransferError.NOT_FOUND to false
    is java.security.cert.CertificateException, is javax.net.ssl.SSLPeerUnverifiedException -> TransferError.CERTIFICATE to false
    is SocketTimeoutException, is UnknownHostException, is java.net.SocketException -> TransferError.NETWORK to true
    is jcifs.smb.SmbAuthException, is net.schmizz.sshj.userauth.UserAuthException -> TransferError.AUTHENTICATION to false
    is jcifs.smb.SmbException -> when (error.ntStatus) {
        0xC000007F.toInt() -> TransferError.SPACE to false
        0xC0000022.toInt() -> TransferError.PERMISSION to false
        0xC0000034.toInt(), 0xC000003A.toInt() -> TransferError.NOT_FOUND to false
        0xC0000035.toInt() -> TransferError.CONFLICT to false
        else -> TransferError.NETWORK to true
    }
    is net.schmizz.sshj.sftp.SFTPException -> when (error.statusCode.toString()) {
        "NO_SUCH_FILE", "NO_SUCH_PATH" -> TransferError.NOT_FOUND to false
        "PERMISSION_DENIED", "WRITE_PROTECT" -> TransferError.PERMISSION to false
        "NO_SPACE_ON_FILESYSTEM", "QUOTA_EXCEEDED" -> TransferError.SPACE to false
        "FILE_ALREADY_EXISTS" -> TransferError.CONFLICT to false
        "OP_UNSUPPORTED" -> TransferError.UNSUPPORTED to false
        else -> TransferError.NETWORK to true
    }
    is net.schmizz.sshj.transport.TransportException -> TransferError.NETWORK to true
    is IllegalArgumentException -> TransferError.INVALID_PATH to false
    else -> null
    }
}

/** 文件访问边界统一错误，但取消与调用方参数契约保持原样。 */
internal suspend fun <T> providerCall(action: suspend () -> T): T = try { action() }
catch (error: Exception) {
    if (error is kotlinx.coroutines.CancellationException || error is IllegalArgumentException || error is TransferException) throw error
    val (code, retryable) = classify(error)
    throw TransferException(code, retryable).apply { initCause(error) }
}
