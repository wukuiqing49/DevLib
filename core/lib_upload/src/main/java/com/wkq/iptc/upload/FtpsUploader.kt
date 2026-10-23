package com.wkq.iptc.upload

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPReply
import org.apache.commons.net.ftp.FTPSClient
import org.apache.commons.net.util.TrustManagerUtils
import java.io.ByteArrayInputStream

class FtpsUploader(
    private val context: Context
) : UploadClient {

    private companion object {
        private const val TAG = "FtpsUploader"
    }

    override val protocol: UploadProtocolType = UploadProtocolType.FTPS

    override suspend fun upload(
        task: UploadTask,
        profile: UploadServerProfile,
        onProgress: ((bytesWritten: Long, totalBytes: Long) -> Unit)?
    ): UploadResult {
        AtomicUploadPolicy.validate(task, profile)?.let { return it }
        val config = profile.config as? FtpsConfig
            ?: return invalidConfig("FTPS profile config is invalid")
        return withContext(Dispatchers.IO) {
            runTransfer(
                config = config,
                sourceUri = task.recordUri,
                fileName = task.fileName,
                resumeOffset = task.resumeOffset,
                validateOnly = false,
                onProgress = onProgress
            )
        }
    }

    override suspend fun testConnection(profile: UploadServerProfile): UploadResult {
        val config = profile.config as? FtpsConfig
            ?: return invalidConfig("FTPS profile config is invalid")
        return withContext(Dispatchers.IO) {
            runTransfer(
                config = config,
                sourceUri = null,
                fileName = null,
                resumeOffset = 0L,
                validateOnly = true,
                onProgress = null
            )
        }
    }

    @SuppressLint("TrustAllX509TrustManager")
    private fun runTransfer(
        config: FtpsConfig,
        sourceUri: String?,
        fileName: String?,
        resumeOffset: Long,
        validateOnly: Boolean,
        onProgress: ((bytesWritten: Long, totalBytes: Long) -> Unit)?
    ): UploadResult {
        if (config.host.isBlank() || config.username.isBlank() || config.password.isBlank()) {
            return invalidConfig("FTPS config is incomplete")
        }
        val client = FTPSClient(config.securityMode == FtpsSecurityMode.IMPLICIT)
        return try {
            if (config.allowInsecureCertificate) {
                // 仅用于用户显式开启的兼容模式；默认路径仍校验证书链和主机名。
                client.trustManager = TrustManagerUtils.getAcceptAllTrustManager()
                client.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
            } else {
                // Commons Net 默认仅检查有效期，必须显式使用系统信任链并校验主机名。
                client.trustManager = null
                client.isEndpointCheckingEnabled = true
            }
            client.connectTimeout = config.connectTimeoutMs
            client.defaultTimeout = config.connectTimeoutMs
            @Suppress("DEPRECATION")
            client.setDataTimeout(config.readTimeoutMs)
            client.controlEncoding = Charsets.UTF_8.name()
            client.connect(config.host, config.port)
            if (!FTPReply.isPositiveCompletion(client.replyCode)) {
                return connectionFailure("FTPS server refused connection (reply ${client.replyCode})")
            }
            if (!client.login(config.username, config.password)) {
                return authFailure("FTPS authentication failed")
            }
            client.execPBSZ(0)
            client.execPROT("P")
            // 图片和压缩包必须按二进制传输，避免 ASCII 模式改写字节。
            client.setFileType(FTP.BINARY_FILE_TYPE)
            if (config.passiveMode) {
                client.setIpAddressFromPasvResponse(false)
                client.enterLocalPassiveMode()
            } else {
                client.enterLocalActiveMode()
            }
            ensureRemoteDir(client, config.remoteDir)
            if (validateOnly) {
                testWritable(client)?.let { return it }
                UploadResult.Success(config.remoteDir)
            } else {
                val safeFileName = fileName ?: return invalidConfig("FTPS upload file name is missing")
                val safeSourceUri = sourceUri ?: return invalidConfig("FTPS upload source is missing")
                val remotePath = buildRemotePath(config.remoteDir, safeFileName)
                val partName = "$safeFileName.part"
                val uri = Uri.parse(safeSourceUri)
                val localSize = getUriLength(context, uri)
                if (localSize < 0) {
                    return transferFailure("FTPS upload source size is invalid", retryable = false)
                }

                val remotePartSize = runCatching {
                    client.listFiles(partName).firstOrNull()?.size ?: 0L
                }.getOrElse { 0L }
                // 零偏移表示调用方要求重传，不能使用旧的远端残片。
                val actualOffset = if (resumeOffset > 0L && remotePartSize in 1..localSize) remotePartSize else 0L

                Log.i(TAG, "FTPS upload: local=${localSize}B, " +
                    "resumeOffset=${resumeOffset}B, remotePartSize=${remotePartSize}B, actualOffset=${actualOffset}B")

                val success = context.contentResolver.openInputStream(uri)?.use { input ->
                    if (actualOffset > 0L) {
                        client.restartOffset = actualOffset
                        input.safeSkip(actualOffset)

                        val progressInput = ProgressInputStream(input, actualOffset) { bytesWritten ->
                            onProgress?.invoke(bytesWritten, localSize)
                        }
                        client.appendFile(partName, progressInput)
                    } else {
                        val progressInput = ProgressInputStream(input, 0L) { bytesWritten ->
                            onProgress?.invoke(bytesWritten, localSize)
                        }
                        client.storeFile(partName, progressInput)
                    }
                } ?: false

                if (!success) {
                    Log.w(
                        TAG,
                        "FTPS .part upload failed: replyCode=${client.replyCode}"
                    )
                    val directSuccess = uploadDirect(client, uri, safeFileName, localSize, onProgress)
                    if (!directSuccess) {
                        return transferFailure(
                            "FTPS upload failed (reply ${client.replyCode})",
                            retryable = client.replyCode >= 400 && client.replyCode < 500
                        )
                    }
                    Log.i(TAG, "FTPS direct upload success after .part failed: remotePath=${UploadLogSanitizer.redactedPath(remotePath)}, size=$localSize")
                    return UploadResult.Success(remotePath)
                }

                val remoteSize = client.listFiles(partName).firstOrNull()?.size ?: -1L
                if (remoteSize != localSize) {
                    return transferFailure(
                        "FTPS remote file size mismatch: local=$localSize, remote=$remoteSize",
                        retryable = true
                    )
                }
                if (!client.rename(partName, safeFileName)) {
                    val directSuccess = uploadDirect(client, uri, safeFileName, localSize, onProgress)
                    if (!directSuccess) {
                        return transferFailure(
                            "FTPS rename .part to final failed (reply ${client.replyCode})",
                            retryable = true
                        )
                    }
                    Log.i(TAG, "FTPS direct upload success after rename failed: remotePath=${UploadLogSanitizer.redactedPath(remotePath)}, size=$localSize")
                    return UploadResult.Success(remotePath)
                }
                Log.i(TAG, "FTPS upload success: remotePath=${UploadLogSanitizer.redactedPath(remotePath)}, size=$localSize")
                UploadResult.Success(remotePath)
            }
        } catch (throwable: Throwable) {
            Log.e(
                TAG,
                "runTransfer failed: validateOnly=$validateOnly, ${UploadLogSanitizer.endpoint(config)}, " +
                    UploadLogSanitizer.throwable(throwable)
            )
            translateThrowable("FTPS", throwable)
        } finally {
            disconnectQuietly(client)
        }
    }

    private fun ensureRemoteDir(client: FTPSClient, remoteDir: String) {
        val normalized = remoteDir.trim().replace('\\', '/')
        if (normalized.isBlank() || normalized == ".") {
            return
        }

        if (normalized.startsWith("/")) {
            if (!client.changeWorkingDirectory("/")) {
                throw RemoteDirectoryAccessException("Unable to enter FTPS root directory")
            }
        }

        normalized.trim('/').split('/').forEach { segment ->
            if (segment.isBlank()) return@forEach
            if (!client.changeWorkingDirectory(segment)) {
                if (!client.makeDirectory(segment)) {
                    throw RemoteDirectoryAccessException("Unable to create remote directory: $segment")
                }
                if (!client.changeWorkingDirectory(segment)) {
                    throw RemoteDirectoryAccessException("Unable to enter remote directory: $segment")
                }
            }
        }
    }

    private fun testWritable(client: FTPSClient): UploadResult.Failure? {
        val fileName = "press_iptc_test_${System.currentTimeMillis()}.tmp"
        val bytes = "ok".toByteArray(Charsets.UTF_8)
        return runCatching {
            client.restartOffset = 0L
            val success = ByteArrayInputStream(bytes).use { input ->
                client.storeFile(fileName, input)
            }
            if (!success) {
                return transferFailure(
                    "FTPS upload test failed (reply ${client.replyCode})",
                    retryable = client.replyCode >= 400 && client.replyCode < 500
                ) as UploadResult.Failure
            }
            runCatching { client.deleteFile(fileName) }
            null
        }.getOrElse {
            Log.w(
                TAG,
                "FTPS upload test failed: type=${it.javaClass.simpleName}, replyCode=${client.replyCode}"
            )
            UploadResult.Failure(
                UploadFailure(
                    code = UploadErrorCode.TRANSFER_FAILED,
                    message = "FTPS upload test failed",
                    retryable = client.replyCode >= 400 && client.replyCode < 500
                )
            )
        }
    }

    private fun uploadDirect(
        client: FTPSClient,
        uri: Uri,
        remotePath: String,
        localSize: Long,
        onProgress: ((bytesWritten: Long, totalBytes: Long) -> Unit)?
    ): Boolean {
        return runCatching {
            // 回退到完整上传时清除失败续传可能遗留的 REST 偏移。
            client.restartOffset = 0L
            context.contentResolver.openInputStream(uri)?.use { input ->
                val progressInput = ProgressInputStream(input, 0L) { bytesWritten ->
                    onProgress?.invoke(bytesWritten, localSize)
                }
                client.storeFile(remotePath, progressInput)
            } ?: false
        }.getOrElse {
            Log.w(
                TAG,
                "FTPS direct upload threw: ${it.javaClass.name}; " +
                    "replyCode=${client.replyCode}"
            )
            false
        }.also { success ->
            if (!success) {
                Log.w(
                    TAG,
                    "FTPS direct upload failed: remotePath=${UploadLogSanitizer.redactedPath(remotePath)}, " +
                        "replyCode=${client.replyCode}"
                )
            }
        }
    }

    private fun disconnectQuietly(client: FTPSClient) {
        // QUIT 失败也必须关闭套接字，避免断网或服务器主动断开时泄漏连接。
        try {
            if (client.isConnected) runCatching { client.logout() }
        } finally {
            runCatching { client.disconnect() }
        }
    }
}
