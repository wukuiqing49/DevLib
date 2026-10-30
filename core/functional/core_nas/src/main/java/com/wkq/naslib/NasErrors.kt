package com.wkq.naslib

import java.io.InputStream
import java.io.OutputStream
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** 有界流式复制，保留 SiteReport UploadIo 的进度做法，补充每块取消检查。 */
internal fun copyBytes(input: InputStream, output: OutputStream, progress: (Long) -> Unit): Long {
    val buffer = ByteArray(64 * 1024)
    var bytes = 0L
    progress(0)
    while (true) {
        if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException("NAS operation interrupted")
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
        android.system.OsConstants.EACCES, android.system.OsConstants.EPERM,
        android.system.OsConstants.EROFS -> TransferError.PERMISSION to false
        android.system.OsConstants.ENODEV, android.system.OsConstants.ENXIO -> TransferError.STORAGE_UNAVAILABLE to true
        android.system.OsConstants.ENOENT -> TransferError.NOT_FOUND to false
        android.system.OsConstants.ECONNREFUSED, android.system.OsConstants.ECONNRESET,
        android.system.OsConstants.ENETUNREACH, android.system.OsConstants.EHOSTUNREACH,
        android.system.OsConstants.ETIMEDOUT, android.system.OsConstants.EPIPE -> TransferError.NETWORK to true
        else -> null
    }
    return when (error) {
    is NasException -> error.error to error.retryable
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


/** 宿主可对读取流后续抛出的异常沿用同一分类，不依赖具体协议实现类型。 */
object NasErrors {
    /** 分类底层协议异常；取消与线程中断由调用边界传播，不用于建议重试。 */
    fun classify(error: Exception): NasException {
        val (code, retryable) = com.wkq.naslib.classify(error)
        return if (error is NasException) error else NasException(code, retryable).apply { initCause(error) }
    }
}

/** 保留宿主取消和中断，协议故障才转换成可重试错误。 */
internal inline fun <T> nasCall(action: () -> T): T = try { action() }
catch (error: Exception) {
    if (error is CallerFailure) throw error.original
    if (error is kotlinx.coroutines.CancellationException || error is IllegalArgumentException) throw error
    if (Thread.currentThread().isInterrupted && error is java.io.InterruptedIOException) throw error
    throw NasErrors.classify(error)
}

/** 宿主回调的暂停/取消/业务异常不属于协议错误，必须保持原实例与类型。 */
internal class CallerFailure(val original: Exception) : RuntimeException()
internal inline fun <T> caller(action: () -> T): T = try { action() }
catch (error: Exception) { throw CallerFailure(error) }
