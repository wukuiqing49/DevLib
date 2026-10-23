package com.wkq.iptc.upload

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.hierynomus.sshj.transport.kex.DHGroups
import com.hierynomus.sshj.transport.kex.ExtInfoClientFactory
import com.hierynomus.sshj.transport.kex.ExtendedDHGroups
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.SecurityUtils
import net.schmizz.sshj.sftp.FileMode
import net.schmizz.sshj.sftp.OpenMode
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.transport.kex.DHGexSHA1
import net.schmizz.sshj.transport.kex.DHGexSHA256
import net.schmizz.sshj.transport.kex.ECDHNistP
import net.schmizz.sshj.transport.TransportException
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import net.schmizz.sshj.userauth.UserAuthException

import java.io.IOException
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.PublicKey

class SftpUploader(
    private val context: Context
) : UploadClient {

    private companion object {
        private const val TAG = "SftpUploader"
        private const val MIN_READ_TIMEOUT_MS = 90_000
        /** 分块写入，避免整张图片进入内存。 */
        private const val CHUNK_SIZE = 32 * 1024
    }

    override val protocol: UploadProtocolType = UploadProtocolType.SFTP

    override suspend fun upload(
        task: UploadTask,
        profile: UploadServerProfile,
        onProgress: ((bytesWritten: Long, totalBytes: Long) -> Unit)?
    ): UploadResult {
        AtomicUploadPolicy.validate(task, profile)?.let { return it }
        val config = profile.config as? SftpConfig
            ?: return invalidConfig("SFTP profile config is invalid")
        val uri = Uri.parse(task.recordUri)
        val localSize = getUriLength(context, uri)
        return withContext(Dispatchers.IO) {
            runTransfer(
                config = config,
                sourceUri = task.recordUri,
                fileName = task.fileName,
                resumeOffset = task.resumeOffset,
                validateOnly = false,
                createOnly = task.createOnly,
                transferId = task.transferId,
                onProgress = { bytesWritten ->
                    onProgress?.invoke(bytesWritten, localSize)
                }
            )
        }
    }

    override suspend fun testConnection(profile: UploadServerProfile): UploadResult {
        val config = profile.config as? SftpConfig
            ?: return invalidConfig("SFTP profile config is invalid")
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

    /**
     * 支持从任务记录的偏移量继续上传。
     *
     * @param onProgress 已上传字节回调；传入 null 时不报告进度。
     */
    suspend fun uploadWithResume(
        task: UploadTask,
        profile: UploadServerProfile,
        onProgress: ((uploadedBytes: Long) -> Unit)?
    ): UploadResult {
        AtomicUploadPolicy.validate(task, profile)?.let { return it }
        val config = profile.config as? SftpConfig
            ?: return invalidConfig("SFTP profile config is invalid")
        return withContext(Dispatchers.IO) {
            runTransfer(
                config = config,
                sourceUri = task.recordUri,
                fileName = task.fileName,
                resumeOffset = task.resumeOffset,
                validateOnly = false,
                onProgress = onProgress,
                createOnly = task.createOnly,
                transferId = task.transferId
            )
        }
    }

    private fun runTransfer(
        config: SftpConfig,
        sourceUri: String?,
        fileName: String?,
        resumeOffset: Long,
        validateOnly: Boolean,
        onProgress: ((uploadedBytes: Long) -> Unit)?,
        createOnly: Boolean = false,
        transferId: String = ""
    ): UploadResult {
        if (config.host.isBlank() || config.username.isBlank() || config.password.isBlank()) {
            return invalidConfig("SFTP config is incomplete")
        }
        if (config.port == 21) {
            return invalidConfig("SFTP uses SSH port. Port 21 is FTP; please use port 22 or the server's SFTP port")
        }
        return try {
            createSshClient().use { ssh ->
                ssh.connectTimeout = config.connectTimeoutMs
                ssh.timeout = maxOf(config.readTimeoutMs, MIN_READ_TIMEOUT_MS)
                if (config.allowInsecureHostKey) {
                    ssh.addHostKeyVerifier(PromiscuousVerifier())
                } else {
                    ssh.addHostKeyVerifier(FirstUseHostKeyVerifier())
                }

                sftpStage("connect") {
                    ssh.connect(config.host, config.port)
                }
                sftpStage("authenticate") {
                    ssh.authPassword(config.username, config.password)
                }
                sftpStage("open SFTP subsystem") {
                    ssh.newSFTPClient()
                }.use { sftp ->
                    val remoteDir = sftpStage("prepare remote directory") {
                        resolveRemoteDir(sftp, config.remoteDir)
                    }
                    if (validateOnly) {
                        UploadResult.Success(remoteDir)
                    } else {
                        val safeFileName = fileName ?: return invalidConfig("SFTP upload file name is missing")
                        val safeSourceUri = sourceUri ?: return invalidConfig("SFTP upload source is missing")
                        val remotePath = buildSftpRemotePath(remoteDir, safeFileName)
                        val partPath = buildSftpRemotePath(remoteDir, AtomicUploadPolicy.temporaryName(safeFileName, createOnly, transferId))
                        if (createOnly && sftp.statExistence(remotePath) != null) return AtomicUploadPolicy.remoteExists()
                        val uri = Uri.parse(safeSourceUri)
                        val localSize = getUriLength(context, uri)
                        if (localSize < 0) {
                            return transferFailure("SFTP upload source size is invalid", retryable = false)
                        }

                        // 绾喖鐣剧€圭偤妾紒顓濈炊鐠у嘲顫愭担宥囩枂閿?
                        val remotePartSize = runCatching {
                            sftp.statExistence(partPath)?.getSize() ?: 0L
                        }.getOrElse { 0L }
                        val actualOffset = minOf(resumeOffset, remotePartSize, localSize)

                        Log.i(TAG, "SFTP upload: local=${localSize}B, " +
                            "resumeOffset=${resumeOffset}B, remotePartSize=${remotePartSize}B, actualOffset=${actualOffset}B")

                        val partUploadResult = runCatching {
                            sftpStage("upload file") {
                                if (actualOffset == 0L) {
                                    runCatching { sftp.rm(partPath) }
                                }
                                uploadFile(sftp, uri, partPath, actualOffset, onProgress)
                            }
                        }
                        if (partUploadResult.isFailure) {
                            if (createOnly) throw requireNotNull(partUploadResult.exceptionOrNull())
                            Log.w(
                                TAG,
                                "SFTP .part upload failed, falling back to direct upload: " +
                                    "${partUploadResult.exceptionOrNull()?.javaClass?.name}: " +
                                    partUploadResult.exceptionOrNull()?.message.orEmpty()
                            )
                            val directResult = runCatching {
                                runCatching { sftp.rm(remotePath) }
                                uploadFile(sftp, uri, remotePath, 0L, onProgress)
                            }
                            if (directResult.isSuccess) {
                                Log.i(TAG, "SFTP direct upload success after .part failed: remotePath=${UploadLogSanitizer.redactedPath(remotePath)}, size=$localSize")
                                return UploadResult.Success(remotePath)
                            }
                            throw partUploadResult.exceptionOrNull() ?: directResult.exceptionOrNull()
                                ?: IOException("SFTP upload failed")
                        }

                        // 閺嶏繝鐛欐径褍鐨?
                        val remoteAttributes = sftpStage("verify remote file") {
                            sftp.statExistence(partPath)
                        } ?: return transferFailure(
                            message = "SFTP upload finished but temporary remote file was not found",
                            retryable = true
                        )
                        val remoteSize = remoteAttributes.getSize()
                        if (remoteSize != localSize) {
                            return transferFailure(
                                message = "SFTP remote file size mismatch: local=$localSize, remote=$remoteSize",
                                retryable = true
                            )
                        }

                        // 校验完成后将临时文件切换为最终文件名。
                        sftpStage("rename to final") {
                            // 标准 SFTP v3 rename 不带覆盖扩展；不能回退到删除目标或直接写入。
                            if (!createOnly) runCatching { sftp.rm(remotePath) }
                            try { sftp.rename(partPath, remotePath) }
                            catch (error: IOException) {
                                if (createOnly && sftp.statExistence(remotePath) != null) {
                                    runCatching { sftp.rm(partPath) }
                                    return AtomicUploadPolicy.remoteExists()
                                }
                                throw error
                            }
                        }
                        Log.i(TAG, "upload success: remotePath=${UploadLogSanitizer.redactedPath(remotePath)}, size=$localSize")
                        UploadResult.Success(remotePath)
                    }
                }
            }
        } catch (throwable: Throwable) {
            Log.e(
                TAG,
                "runTransfer failed: validateOnly=$validateOnly, ${UploadLogSanitizer.endpoint(config)}, " +
                    UploadLogSanitizer.throwable(throwable)
            )
            when {
                throwable is SocketTimeoutException -> UploadResult.Failure(
                    UploadFailure(
                        code = UploadErrorCode.TIMEOUT,
                        message = "SFTP connection timed out",
                        retryable = true
                    )
                )

                throwable is SftpStageTimeoutException -> UploadResult.Failure(
                    UploadFailure(
                        code = UploadErrorCode.TIMEOUT,
                        message = "SFTP ${throwable.stage} timed out",
                        retryable = true
                    )
                )

                // 登录失败与主机密钥校验失败分别映射，便于界面给出准确提示。
                throwable is UserAuthException -> authFailure(
                    "SFTP authentication failed. Please check the username and password, or confirm that the server allows password login."
                )

                throwable is HostKeyChangedException -> UploadResult.Failure(
                    UploadFailure(
                        code = UploadErrorCode.HOST_KEY_CHANGED,
                        message = "SFTP host key changed. If the server was reinstalled or its key regenerated, clear the saved fingerprint in Settings > Upload Config.",
                        retryable = false
                    )
                )

                throwable is TransportException && throwable.message.orEmpty().contains("host key", ignoreCase = true) -> {
                    UploadResult.Failure(
                        UploadFailure(
                            code = UploadErrorCode.HOST_KEY_REJECTED,
                            message = "SFTP host key verification failed.",
                            retryable = false
                        )
                    )
                }

                else -> translateThrowable("SFTP", throwable)
            }
        }
    }

    private fun createSshClient(): SSHClient {
        // On Android, forcing sshj onto the platform JCE provider is more stable than BC.
        SecurityUtils.setRegisterBouncyCastle(false)
        SecurityUtils.setSecurityProvider(null)
        val config = DefaultConfig().apply {
            // 显式选择 Android 平台可稳定支持的密钥交换算法。
            setKeyExchangeFactories(
                listOf(
                    DHGexSHA256.Factory(),
                    ECDHNistP.Factory521(),
                    ECDHNistP.Factory384(),
                    ECDHNistP.Factory256(),
                    DHGexSHA1.Factory(),
                    DHGroups.Group14SHA256(),
                    DHGroups.Group14SHA1(),
                    DHGroups.Group15SHA512(),
                    DHGroups.Group16SHA512(),
                    ExtendedDHGroups.Group14SHA256AtSSH(),
                    ExtendedDHGroups.Group15SHA256(),
                    ExtendedDHGroups.Group15SHA256AtSSH(),
                    ExtendedDHGroups.Group15SHA384AtSSH(),
                    ExtendedDHGroups.Group16SHA256(),
                    ExtendedDHGroups.Group16SHA384AtSSH(),
                    ExtendedDHGroups.Group16SHA512AtSSH(),
                    ExtendedDHGroups.Group18SHA512AtSSH(),
                    ExtInfoClientFactory()
                )
            )
        }
        return SSHClient(config)
    }

    private inline fun <T> sftpStage(stage: String, block: () -> T): T {
        return try {
            block()
        } catch (throwable: SocketTimeoutException) {
            throw SftpStageTimeoutException(stage, throwable)
        }
    }

    private fun resolveRemoteDir(sftp: SFTPClient, remoteDir: String): String {
        val normalized = remoteDir.trim().replace('\\', '/').trimEnd('/')
        if (normalized.isBlank() || normalized == ".") {
            return "."
        }
        if (normalized == "/") {
            return "/"
        }

        val candidates = buildList {
            add(normalized)
            if (normalized.startsWith("/")) {
                add(normalized.trimStart('/').ifBlank { "." })
            }
        }.distinct()

        var lastFailure: Throwable? = null
        for (candidate in candidates) {
            try {
                ensureRemoteDir(sftp, candidate)
                return candidate
            } catch (throwable: Throwable) {
                lastFailure = throwable
            }
        }
        throw lastFailure ?: IllegalStateException("Unable to resolve SFTP remote directory")
    }

    private fun ensureRemoteDir(sftp: SFTPClient, remoteDir: String) {
        val existing = runCatching { sftp.statExistence(remoteDir) }.getOrNull()
        if (existing?.type == FileMode.Type.DIRECTORY) {
            return
        }
        sftp.mkdirs(remoteDir)
    }

    private fun buildSftpRemotePath(remoteDir: String, fileName: String): String {
        val normalizedDir = remoteDir.trim().trimEnd('/')
        return when {
            normalizedDir.isBlank() || normalizedDir == "." -> fileName
            normalizedDir == "/" -> "/$fileName"
            else -> "$normalizedDir/$fileName"
        }
    }

    private fun testWritable(sftp: SFTPClient, remoteDir: String): UploadResult.Failure? {
        val probePath = buildSftpRemotePath(remoteDir, "press_iptc_probe_${System.currentTimeMillis()}.tmp")
        return runCatching {
            sftp.open(probePath, setOf(OpenMode.WRITE, OpenMode.CREAT, OpenMode.TRUNC)).use { remoteFile ->
                val bytes = "ok".toByteArray(Charsets.UTF_8)
                remoteFile.write(0L, bytes, 0, bytes.size)
            }
            runCatching { sftp.rm(probePath) }
        }.fold(
            onSuccess = { null },
            onFailure = {
                UploadResult.Failure(
                    UploadFailure(
                        code = UploadErrorCode.DIRECTORY_ACCESS_DENIED,
                        message = "SFTP directory is not writable",
                        retryable = false
                    )
                )
            }
        )
    }

    private fun uploadFile(
        sftp: SFTPClient,
        uri: Uri,
        remotePath: String,
        offset: Long,
        onProgress: ((uploadedBytes: Long) -> Unit)?
    ) {
        val openModes = if (offset > 0L) {
            setOf(OpenMode.WRITE, OpenMode.CREAT)
        } else {
            setOf(OpenMode.WRITE, OpenMode.CREAT, OpenMode.TRUNC)
        }
        sftp.open(remotePath, openModes).use { remoteFile ->
            val buf = ByteArray(CHUNK_SIZE)
            var fileOffset = offset
            context.contentResolver.openInputStream(uri)?.use { input ->
                if (offset > 0L) {
                    input.safeSkip(offset)
                }
                var read: Int
                while (input.read(buf).also { read = it } != -1) {
                    remoteFile.write(fileOffset, buf, 0, read)
                    fileOffset += read
                    onProgress?.invoke(fileOffset)
                }
            } ?: throw IOException("Failed to open source stream")
        }
    }
    /**
     * TOFU 主机密钥校验：首次连接保存指纹，后续连接必须匹配。
     * 指纹变化时抛出 [HostKeyChangedException]，由上层提示用户确认服务器变更。
     */
    private class FirstUseHostKeyVerifier : HostKeyVerifier {
        override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
            val fingerprint = key.sha256Fingerprint()
            val saved = UploadSecretStore.loadHostFingerprint(hostname, port).ifBlank { null }
            when {
                saved == null -> {
                    // 首次连接保存服务器指纹。
                    UploadSecretStore.saveHostFingerprint(hostname, port, fingerprint)
                    return true
                }
                saved == fingerprint -> return true
                else -> {
                    // 指纹变化可能意味着服务器重装，也可能是中间人攻击，必须阻断连接。
                    throw HostKeyChangedException(
                        hostname = hostname,
                        port = port,
                        savedFingerprint = saved,
                        currentFingerprint = fingerprint
                    )
                }
            }
        }

        override fun findExistingAlgorithms(hostname: String, port: Int): List<String> = emptyList()

        private fun PublicKey.sha256Fingerprint(): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(encoded)
            return Base64.encodeToString(digest, Base64.NO_WRAP)
        }
    }

    /** SFTP 主机密钥发生变化，调用方应要求用户重新确认服务器身份。 */
    class HostKeyChangedException(
        val hostname: String,
        val port: Int,
        val savedFingerprint: String,
        val currentFingerprint: String
    ) : IOException(
        "SFTP host key changed. If the server has been reinstalled or its key regenerated, please clear the saved fingerprint in Settings > Upload Config."
    )

    private class SftpStageTimeoutException(
        val stage: String,
        cause: SocketTimeoutException
    ) : IOException(cause)
}


