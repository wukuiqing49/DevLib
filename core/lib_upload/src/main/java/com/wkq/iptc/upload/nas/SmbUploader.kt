package com.wkq.iptc.upload.nas

import android.content.Context
import android.net.Uri
import com.wkq.iptc.upload.*
import android.util.Log
import jcifs.CIFSContext
import jcifs.context.SingletonContext
import jcifs.smb.NtlmPasswordAuthenticator
import jcifs.smb.SmbFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SmbUploader(
    private val context: Context
) : UploadClient {

    private companion object {
        private const val TAG = "SmbUploader"
    }

    override val protocol: UploadProtocolType = UploadProtocolType.SMB

    private fun getCifsContext(config: SmbConfig): CIFSContext {
        val prop = java.util.Properties().apply {
            setProperty("jcifs.smb.client.minVersion", "SMB202")
            setProperty("jcifs.smb.client.maxVersion", "SMB311")
            setProperty("jcifs.smb.client.dfs.disabled", "true")
            setProperty("jcifs.resolveOrder", "DNS")
        }
        val cifsConfig = jcifs.config.PropertyConfiguration(prop)
        val baseContext = jcifs.context.BaseContext(cifsConfig)
        return if (config.username.isNotBlank()) {
            val auth = NtlmPasswordAuthenticator(null, config.username, config.password)
            baseContext.withCredentials(auth)
        } else {
            baseContext
        }
    }

    /**
     * 构建 SMB URL。
     * @param task 非 null 时包含上传文件名；null 时仅定位目录用于连接测试。
     * @param suffix 文件名后缀，用于生成 `.part` 临时路径。
     */
    private fun buildUploadUrl(config: SmbConfig, task: UploadTask?, suffix: String = ""): String {
        val baseUrl = if (config.host.startsWith("smb://")) {
            config.host.trimEnd('/')
        } else {
            "smb://${config.host.trimEnd('/')}"
        }
        return buildString {
            append(baseUrl)
            if (config.remoteDir.isNotBlank()) {
                if (!config.remoteDir.startsWith("/")) append("/")
                append(config.remoteDir.trimEnd('/'))
            }
            if (task != null) {
                append("/")
                append(task.fileName)
                append(suffix)
            } else {
                append("/")
            }
        }
    }

    override suspend fun upload(
        task: UploadTask,
        profile: UploadServerProfile,
        onProgress: ((bytesWritten: Long, totalBytes: Long) -> Unit)?
    ): UploadResult {
        com.wkq.iptc.upload.AtomicUploadPolicy.validate(task, profile)?.let { return it }
        val config = profile.config as? SmbConfig
            ?: return invalidConfig("SMB profile config is invalid")

        return withContext(Dispatchers.IO) {
            val uri = Uri.parse(task.recordUri)
            val localSize = getUriLength(context, uri)
            if (localSize < 0) {
                return@withContext invalidConfig("Failed to get size of source file")
            }

            val partName = com.wkq.iptc.upload.AtomicUploadPolicy.temporaryName(task.fileName, task.createOnly, task.transferId)
            val partUrl   = buildUploadUrl(config, task.copy(fileName = partName))
            val finalUrl  = buildUploadUrl(config, task)
            val cifsCtx   = getCifsContext(config)
            val partFile  = SmbFile(partUrl, cifsCtx)
            val finalFile = SmbFile(finalUrl, cifsCtx)

            try {
                if (task.createOnly && finalFile.exists()) return@withContext com.wkq.iptc.upload.AtomicUploadPolicy.remoteExists()
                // 1. 纭繚鐖剁洰褰曞瓨鍦?
                val parentUrl = partFile.parent
                if (parentUrl != null) {
                    val parentDir = SmbFile(parentUrl, cifsCtx)
                    if (!parentDir.exists()) {
                        parentDir.mkdirs()
                    }
                }

                // 写入 `.part` 临时文件；SMB 当前使用全量覆盖上传。
                partFile.getOutputStream().use { out ->
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        copyStreamWithProgress(input, out, localSize, onProgress)
                    } ?: throw java.io.IOException("Failed to open source stream")
                }

                // 校验远端文件大小。
                val remoteSize = runCatching { partFile.length() }.getOrElse { -1L }
                if ((task.createOnly && remoteSize < 0) || (remoteSize >= 0 && remoteSize != localSize)) {
                    // 大小不一致时清理临时文件，等待后续重试。
                    runCatching { partFile.delete() }
                    return@withContext transferFailure(
                        "SMB remote file size mismatch: local=$localSize, remote=$remoteSize",
                        retryable = true
                    )
                }

                // 受保护任务由服务器拒绝同名提交；旧调用保留覆盖行为。
                if (!task.createOnly) runCatching { if (finalFile.exists()) finalFile.delete() }
                partFile.renameTo(finalFile, !task.createOnly)

                Log.i(TAG, "SMB upload success: path=${UploadLogSanitizer.redactedUrl(finalUrl)}, size=$localSize")
                UploadResult.Success(finalUrl)
            } catch (throwable: Throwable) {
                // 上传失败时清理 `.part` 文件，避免占用远端空间。
                runCatching { if (partFile.exists()) partFile.delete() }
                if (task.createOnly && runCatching { finalFile.exists() }.getOrDefault(false)) {
                    return@withContext com.wkq.iptc.upload.AtomicUploadPolicy.remoteExists()
                }
                Log.e(TAG, "SMB upload failed: ${UploadLogSanitizer.throwable(throwable)}")
                translateThrowable("SMB", throwable)
            }
        }
    }

    override suspend fun testConnection(profile: UploadServerProfile): UploadResult {
        val config = profile.config as? SmbConfig
            ?: return invalidConfig("SMB profile config is invalid")

        return withContext(Dispatchers.IO) {
            val dirUrl = buildUploadUrl(config, null)
            try {
                val cifsContext = getCifsContext(config)
                val smbDir = SmbFile(dirUrl, cifsContext)
                if (!smbDir.exists()) {
                    smbDir.mkdirs()
                }
                UploadResult.Success(dirUrl)
            } catch (throwable: Throwable) {
                Log.e(TAG, "SMB connection test failed: ${UploadLogSanitizer.throwable(throwable)}")
                translateThrowable("SMB", throwable)
            }
        }
    }
}


