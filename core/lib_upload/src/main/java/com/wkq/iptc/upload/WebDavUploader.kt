package com.wkq.iptc.upload

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * WebDAV 上传客户端。
 *
 * 先通过 PUT 写入 `.part` 临时文件，再用 MOVE 切换为最终文件名，并通过
 * PROPFIND 对比远端与本地文件大小。支持 HTTP/HTTPS、显式允许自签名证书和
 * Basic Auth。
 */
class WebDavUploader(
    private val context: Context
) : UploadClient {

    private companion object {
        private const val TAG = "WebDavUploader"
        private val OCTET_STREAM = "application/octet-stream".toMediaTypeOrNull()
    }

    override val protocol: UploadProtocolType = UploadProtocolType.WEBDAV

    override suspend fun upload(
        task: UploadTask,
        profile: UploadServerProfile,
        onProgress: ((bytesWritten: Long, totalBytes: Long) -> Unit)?
    ): UploadResult {
        AtomicUploadPolicy.validate(task, profile)?.let { return it }
        val config = profile.config as? WebDavConfig
            ?: return invalidConfig("WebDAV profile config is invalid")
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

    override suspend fun testConnection(profile: UploadServerProfile): UploadResult {
        val config = profile.config as? WebDavConfig
            ?: return invalidConfig("WebDAV profile config is invalid")
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

    private fun runTransfer(
        config: WebDavConfig,
        sourceUri: String?,
        fileName: String?,
        resumeOffset: Long,
        validateOnly: Boolean,
        onProgress: ((bytesWritten: Long, totalBytes: Long) -> Unit)?,
        createOnly: Boolean = false,
        transferId: String = ""
    ): UploadResult {
        if (config.host.isBlank()) {
            return invalidConfig("WebDAV host is empty")
        }

        val client = buildClient(config)
        val baseUrl = buildBaseUrl(config)

        return try {
            // 杩炴帴娴嬭瘯锛氬 remoteDir 鐩綍鍋?PROPFIND 楠岃瘉鍙揪鎬?
            val dirUrl = "$baseUrl${config.remoteDir.trimEnd('/')}/"
            val propfindResult = propfind(client, dirUrl, config)
            if (propfindResult != null) {
                return propfindResult
            }

            if (validateOnly) {
                Log.i(TAG, "WebDAV connection test success: url=${UploadLogSanitizer.redactedUrl(dirUrl)}")
                return UploadResult.Success(config.remoteDir)
            }

            val safeFileName = fileName ?: return invalidConfig("WebDAV upload file name is missing")
            val safeSourceUri = sourceUri ?: return invalidConfig("WebDAV upload source is missing")
            val remotePath = buildRemotePath(config.remoteDir, safeFileName)
            val directoryUrl = baseUrl.toHttpUrl().newBuilder()
                .addPathSegments(config.remoteDir.trim('/'))
            val partUrl = directoryUrl.build().newBuilder()
                .addPathSegment(AtomicUploadPolicy.temporaryName(safeFileName, createOnly, transferId)).build().toString()
            val finalUrl = directoryUrl.build().newBuilder().addPathSegment(safeFileName).build().toString()
            val uri = Uri.parse(safeSourceUri)
            val localSize = getUriLength(context, uri)
            if (localSize < 0) {
                return transferFailure("WebDAV upload source size is invalid", retryable = false)
            }

            // 纭畾瀹為檯缁紶璧峰浣嶇疆锛?
            val remotePartSize = getContentLength(client, partUrl, config)
            val actualOffset = if (remotePartSize > 0L) {
                minOf(resumeOffset, remotePartSize, localSize)
            } else {
                0L
            }

            Log.i(TAG, "WebDAV upload: local=${localSize}B, " +
                "resumeOffset=${resumeOffset}B, remotePartSize=${remotePartSize}B, actualOffset=${actualOffset}B")

            // 先 PUT 到 `.part` 临时文件。
            var putResult = putFile(client, partUrl, uri, localSize, actualOffset, config, onProgress)
            
            // 服务端不支持 Range PUT 时，删除临时文件并从头上传。
            if (putResult != null && actualOffset > 0L) {
                val errorCode = putResult.error.code
                val shouldFallback = errorCode == UploadErrorCode.INVALID_CONFIG ||
                        putResult.error.message.contains("400") ||
                        putResult.error.message.contains("501") ||
                        putResult.error.message.contains("405")
                
                if (shouldFallback) {
                    Log.w(TAG, "WebDAV Server does not support Range PUT. Falling back to full upload. Error: ${UploadLogSanitizer.message(putResult.error.message)}")
                    // 删除远端已存在的部分文件。
                    runCatching {
                        val deleteRequest = Request.Builder()
                            .url(partUrl)
                            .delete()
                            .applyAuth(config)
                            .build()
                        client.newCall(deleteRequest).execute().use { }
                    }
                    // 从 0 开始重新全量上传。
                    putResult = putFile(client, partUrl, uri, localSize, 0L, config, onProgress)
                }
            }

            if (putResult != null) {
                return putResult
            }

            // 校验远端文件大小。
            val remoteSize = getContentLength(client, partUrl, config)
            if ((createOnly && remoteSize < 0) || (remoteSize >= 0 && remoteSize != localSize)) {
                return transferFailure(
                    "WebDAV remote file size mismatch: local=$localSize, remote=$remoteSize",
                    retryable = true
                )
            }

            // 3. MOVE 閲嶅懡鍚嶄负鏈€缁堟枃浠讹紙鍘熷瓙鎬э級
            val moveResult = moveFile(client, partUrl, finalUrl, config, createOnly)
            if (moveResult != null) {
                if (createOnly && moveResult.error.code == UploadErrorCode.REMOTE_EXISTS) {
                    runCatching {
                        client.newCall(Request.Builder().url(partUrl).delete().applyAuth(config).build())
                            .execute().use { check(it.isSuccessful || it.code == 404) }
                    }.onFailure { Log.w(TAG, "Unable to remove this upload temporary file") }
                }
                return moveResult
            }

            Log.i(TAG, "WebDAV upload success: remotePath=${UploadLogSanitizer.redactedPath(remotePath)}, size=$localSize")
            UploadResult.Success(remotePath)
        } catch (throwable: Throwable) {
            Log.e(
                TAG,
                "runTransfer failed: validateOnly=$validateOnly, ${UploadLogSanitizer.endpoint(config)}, " +
                    UploadLogSanitizer.throwable(throwable)
            )
            when (throwable) {
                is UnknownHostException -> connectionFailure("WebDAV host not found")
                is SocketTimeoutException -> UploadResult.Failure(
                    UploadFailure(
                        code = UploadErrorCode.TIMEOUT,
                        message = "WebDAV connection timed out",
                        retryable = true
                    )
                )
                else -> translateThrowable("WebDAV", throwable)
            }
        }
    }

    /**
     * 用 PROPFIND 检查目录可达性，返回非 null 表示失败。
     */
    private fun propfind(
        client: OkHttpClient,
        url: String,
        config: WebDavConfig,
        createOnly: Boolean = false
    ): UploadResult.Failure? {
        val request = Request.Builder()
            .url(url)
            .method("PROPFIND", null)
            .header("Depth", "0")
            .applyAuth(config)
            .build()
        val response = client.newCall(request).execute()
        response.use {
            return when {
                it.isSuccessful || it.code == 207 -> null // 207 Multi-Status 鏄?WebDAV 姝ｅ父鍝嶅簲
                it.code == 401 -> return authFailure("WebDAV authentication failed (401)")
                it.code == 403 -> return UploadResult.Failure(
                    UploadFailure(
                        code = UploadErrorCode.DIRECTORY_ACCESS_DENIED,
                        message = "WebDAV access denied (403)",
                        retryable = false
                    )
                )
                it.code == 404 -> {
                    // remoteDir 不存在时尝试创建。
                    val mkcolResult = mkcol(client, url, config)
                    mkcolResult // null 琛ㄧず鎴愬姛
                }
                else -> transferFailure(
                    "WebDAV PROPFIND failed: code=${it.code}",
                    retryable = it.code >= 500
                )
            }
        }
    }

    /**
     * 用 MKCOL 创建目录，返回非 null 表示失败。
     */
    private fun mkcol(
        client: OkHttpClient,
        url: String,
        config: WebDavConfig
    ): UploadResult.Failure? {
        val request = Request.Builder()
            .url(url)
            .method("MKCOL", null)
            .applyAuth(config)
            .build()
        val response = client.newCall(request).execute()
        response.use {
            return if (it.isSuccessful || it.code == 201 || it.code == 405 /* 宸插瓨鍦?*/) {
                null
            } else {
                UploadResult.Failure(
                    UploadFailure(
                        code = UploadErrorCode.DIRECTORY_ACCESS_DENIED,
                        message = "WebDAV MKCOL failed: code=${it.code}",
                        retryable = false
                    )
                )
            }
        }
    }

    /**
     * PUT 上传文件内容，返回非 null 表示失败。
     */
    private fun putFile(
        client: OkHttpClient,
        url: String,
        uri: Uri,
        contentLength: Long,
        actualOffset: Long,
        config: WebDavConfig,
        onProgress: ((bytesWritten: Long, totalBytes: Long) -> Unit)?
    ): UploadResult.Failure? {
        val body = UriRequestBody(
            context = context,
            uri = uri,
            contentType = OCTET_STREAM,
            contentLength = contentLength,
            skipOffset = actualOffset,
            onProgress = onProgress
        )
        val request = Request.Builder()
            .url(url)
            .put(body)
            .apply {
                if (actualOffset > 0L) {
                    header("Content-Range", "bytes $actualOffset-${contentLength - 1}/$contentLength")
                }
            }
            .applyAuth(config)
            .build()
        val response = client.newCall(request).execute()
        response.use {
            return if (it.isSuccessful || it.code == 201 || it.code == 204) {
                null
            } else if (it.code == 401) {
                authFailure("WebDAV authentication failed on PUT (401)")
            } else {
                transferFailure(
                    "WebDAV PUT failed: code=${it.code}",
                    retryable = it.code >= 500
                )
            }
        }
    }

    /**
     * 用 MOVE 将 `.part` 重命名为正式路径，返回非 null 表示失败。
     */
    private fun moveFile(
        client: OkHttpClient,
        sourceUrl: String,
        destUrl: String,
        config: WebDavConfig,
        createOnly: Boolean = false
    ): UploadResult.Failure? {
        val request = Request.Builder()
            .url(sourceUrl)
            .method("MOVE", null)
            .header("Destination", destUrl)
            .header("Overwrite", if (createOnly) "F" else "T")
            .applyAuth(config)
            .build()
        val response = client.newCall(request).execute()
        response.use {
            if (createOnly && it.code == 412) return AtomicUploadPolicy.remoteExists()
            return if (it.isSuccessful || it.code == 201 || it.code == 204) {
                null
            } else {
                transferFailure(
                    "WebDAV MOVE failed: code=${it.code}",
                    retryable = it.code >= 500
                )
            }
        }
    }

    /**
     * 通过 HEAD 获取远端文件大小，失败时返回 -1。
     */
    private fun getContentLength(
        client: OkHttpClient,
        url: String,
        config: WebDavConfig
    ): Long {
        return runCatching {
            val request = Request.Builder()
                .url(url)
                .head()
                .applyAuth(config)
                .build()
            client.newCall(request).execute().use { response ->
                response.header("Content-Length")?.toLongOrNull() ?: -1L
            }
        }.getOrElse { -1L }
    }

    /** 鏋勯€?WebDAV 鏍?URL锛坔ttp:// 鎴?https://锛夈€?*/
    private fun buildBaseUrl(config: WebDavConfig): String {
        val scheme = if (config.useHttps) "https" else "http"
        val defaultPort = if (config.useHttps) 443 else 80
        return if (config.port == defaultPort) {
            "$scheme://${config.host}"
        } else {
            "$scheme://${config.host}:${config.port}"
        }
    }

    /** 构建 OkHttpClient，并按配置启用自签名证书兼容模式。 */
    private fun buildClient(config: WebDavConfig): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(config.connectTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(config.readTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .writeTimeout(config.readTimeoutMs.toLong(), TimeUnit.MILLISECONDS)

        if (config.useHttps && config.allowInsecureCert) {
            // 仅供用户显式选择的自签名证书兼容场景使用。
            val trustAll = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, arrayOf<TrustManager>(trustAll), SecureRandom())
            builder.sslSocketFactory(sslContext.socketFactory, trustAll)
            builder.hostnameVerifier { _, _ -> true }
        }

        return builder.addInterceptor(com.wkq.iptc.upload.http.RetryInterceptor(maxRetry = 3)).build()
    }

    /** 用户名和密码都非空时，为请求添加 Basic Auth。 */
    private fun Request.Builder.applyAuth(config: WebDavConfig): Request.Builder {
        if (config.username.isNotBlank() && config.password.isNotBlank()) {
            header("Authorization", Credentials.basic(config.username, config.password))
        }
        return this
    }
}




