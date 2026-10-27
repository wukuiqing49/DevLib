package com.wkq.iptc.upload.http

import android.content.Context
import com.wkq.iptc.upload.AtomicUploadPolicy
import okhttp3.HttpUrl.Companion.toHttpUrl
import android.net.Uri
import com.wkq.iptc.upload.*
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.awaitResponse
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

/** HTTP 上传适配；写请求不自动重放，取消会终止实际网络调用。 */
class HttpUploader(
    private val context: Context
) : UploadClient {

    private companion object {
        private const val TAG = "HttpUploader"
    }

    override val protocol: UploadProtocolType = UploadProtocolType.HTTP

    /**
     * �?host 字段规范化为�?scheme 的根 URL（末尾带 /）�?
     *
     * Retrofit 要求 baseUrl 必须�?/ 结尾，且路径不能被截断�?
     * 例：
     *   "192.168.1.1"        �?"http://192.168.1.1/"
     *   "http://example.com" �?"http://example.com/"
     *   "https://example.com/api" �?"https://example.com/api/"
     */
    private fun normalizeBaseUrl(config: HttpConfig): String {
        val withScheme = when {
            config.host.startsWith("http://") || config.host.startsWith("https://") -> config.host
            else -> "http://${config.host}"
        }
        // 端口非默认时拼入（host 字段本身若已含端口则跳过�?
        val schemeEndIndex = withScheme.indexOf("://")
        val contentPart = if (schemeEndIndex >= 0) withScheme.substring(schemeEndIndex + 3) else withScheme
        val withPort = if (!contentPart.contains(":") && config.port != 80 && config.port != 443) {
            // �?host 尾、path 前插入端�?
            val schemeEnd = if (schemeEndIndex >= 0) schemeEndIndex + 3 else 0
            val pathStart = withScheme.indexOf('/', schemeEnd).takeIf { it >= 0 } ?: withScheme.length
            withScheme.substring(0, pathStart) + ":${config.port}" + withScheme.substring(pathStart)
        } else {
            withScheme
        }
        return if (withPort.endsWith("/")) withPort else "$withPort/"
    }

    /**
     * 拼接上传目标完整 URL�?
     */
    private fun buildUploadUrl(config: HttpConfig): String {
        val base = normalizeBaseUrl(config).trimEnd('/')
        return when {
            config.remoteDir.isBlank() -> base
            config.remoteDir.startsWith("/") -> "$base${config.remoteDir}"
            else -> "$base/${config.remoteDir}"
        }
    }

    /** 配置认证与超时，禁止写请求自动重放或重定向泄漏凭据。 */
    private fun buildOkHttpClient(config: HttpConfig): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(config.connectTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(config.readTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .writeTimeout(config.readTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false)
            .followRedirects(false)
            .followSslRedirects(false)

        if (config.username.isNotBlank() && config.password.isNotBlank()) {
            val credential = Credentials.basic(config.username, config.password)
            // 主动注入：首次请求就携带凭证，不需要等 401 挑战
            builder.addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .header("Authorization", credential)
                    .build()
                chain.proceed(request)
            }
            // 兜底：服务端若仍返回 401，再次重试一�?
            builder.authenticator { _, response ->
                if (response.request.header("Authorization") != null) {
                    // 已带过凭证还�?401，停止重试避免无限循�?
                    return@authenticator null
                }
                response.request.newBuilder()
                    .header("Authorization", credential)
                    .build()
            }
        }

        return builder.addInterceptor(RetryInterceptor(maxRetry = 3)).build()
    }

    private fun createRetrofit(config: HttpConfig): Retrofit {
        return Retrofit.Builder()
            .baseUrl(normalizeBaseUrl(config))
            .client(buildOkHttpClient(config))
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    /** 上传并关闭响应体；提交结果不明时返回失败，由宿主核对后决定是否重试。 */
    override suspend fun upload(
        task: UploadTask,
        profile: UploadServerProfile,
        onProgress: ((bytesWritten: Long, totalBytes: Long) -> Unit)?
    ): UploadResult {
        AtomicUploadPolicy.validate(task, profile)?.let { return it }
        val config = profile.config as? HttpConfig
            ?: return invalidConfig("HTTP profile config is invalid")

        return withContext(Dispatchers.IO) {
            val uploadUrl = buildUploadUrl(config)
            val uri = Uri.parse(task.recordUri)
            val localSize = getUriLength(context, uri)
            if (localSize < 0) {
                return@withContext invalidConfig("Failed to get size of source file")
            }

            try {
                if (task.createOnly) {
                    // 仅适用于明确配置的资源型 PUT 端点，不能给普通 POST 表单添加条件头冒充防覆盖。
                    val resourceUrl = uploadUrl.toHttpUrl().newBuilder().addPathSegment(task.fileName).build()
                    val body = UriRequestBody(context, uri, "application/octet-stream".toMediaTypeOrNull(), localSize, onProgress = onProgress)
                    val requireReceipt = config.extraParams["__idempotent_receipt__"] == "true"
                    val digestHeader = if (requireReceipt) {
                        val digest = java.security.MessageDigest.getInstance("SHA-256")
                        val job = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]
                        val input = requireNotNull(context.contentResolver.openInputStream(uri)) { "Cannot read upload source" }
                        input.use {
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                if (job?.isActive == false) throw kotlinx.coroutines.CancellationException()
                                val count = it.read(buffer)
                                if (count < 0) break
                                digest.update(buffer, 0, count)
                            }
                        }
                        "sha-256=:${android.util.Base64.encodeToString(digest.digest(), android.util.Base64.NO_WRAP)}:"
                    } else null
                    val request = Request.Builder().url(resourceUrl).put(body)
                        .apply { config.headers.forEach { (name, value) -> header(name, value) } }
                        .header("If-None-Match", "*")
                        .header("Idempotency-Key", task.transferId)
                        .apply { digestHeader?.let { header("Content-Digest", it) } }
                        .build()
                    return@withContext buildOkHttpClient(config).newCall(request).awaitUploadResponse().use { response ->
                        // 仅在显式启用服务端回执约定时，接受“同一提交已完成”的条件冲突响应。
                        val receiptMatches = requireReceipt &&
                            response.header("X-CaptionMeta-Receipt") == "committed" &&
                            response.header("Idempotency-Key") == task.transferId &&
                            response.header("X-CaptionMeta-Object-Digest") == digestHeader &&
                            response.header("X-CaptionMeta-Resource")?.let(resourceUrl::resolve) == resourceUrl
                        when {
                            receiptMatches && ((response.isSuccessful && response.code != 202) || response.code in listOf(409, 412)) -> UploadResult.Success(resourceUrl.toString())
                            response.code == 412 || response.code == 409 -> AtomicUploadPolicy.remoteExists()
                            response.code == 202 -> transferFailure("HTTP upload is awaiting server commit", true)
                            requireReceipt && response.isSuccessful -> transferFailure("Missing or invalid committed upload receipt", false)
                            response.isSuccessful -> UploadResult.Success(resourceUrl.toString())
                            else -> transferFailure("Conditional HTTP PUT failed: ${response.code}", response.code >= 500)
                        }
                    }
                }
                val retrofit = createRetrofit(config)
                val service  = retrofit.create(HttpUploadService::class.java)

                val fileBody = UriRequestBody(
                    context = context,
                    uri = uri,
                    contentType = "application/octet-stream".toMediaTypeOrNull(),
                    contentLength = localSize,
                    onProgress = onProgress
                )
                // 支持在附加参数中通过 __file_field_name__ 自定义表单字段名（如 image），默认值为 "file"
                val fileFieldName = config.extraParams["__file_field_name__"] ?: "file"
                val filePart = MultipartBody.Part.createFormData(fileFieldName, task.fileName, fileBody)

                val partMap = config.extraParams.filterKeys { it !in setOf("__create_only_put__", "__idempotent_receipt__", "__file_field_name__") }.mapValues { entry ->
                    entry.value.toRequestBody("text/plain".toMediaTypeOrNull())
                }

                val response = service.uploadFile(
                    url     = uploadUrl,
                    headers = config.headers,
                    parts   = partMap,
                    file    = filePart
                ).awaitResponse()

                if (response.isSuccessful) {
                    // 读取返回的响应报�?
                    val bodyStr = response.body()?.use { it.string() }
                    // 解析 JSON 中的 data.url 字段以获取静态资源路�?
                    val remoteUrl = runCatching {
                        val json = com.google.gson.Gson().fromJson(bodyStr, Map::class.java)
                        val data = json["data"] as? Map<*, *>
                        data?.get("url") as? String
                    }.getOrNull() ?: uploadUrl

                    Log.i(TAG, "HTTP upload success: url=${UploadLogSanitizer.redactedUrl(remoteUrl)}")
                    UploadResult.Success(remoteUrl)
                } else {
                    response.errorBody()?.close()
                    transferFailure(
                        "HTTP upload failed: ${response.code()} ${response.message()}",
                        retryable = response.code() >= 500
                    )
                }
            } catch (throwable: Throwable) {
                if (throwable is kotlinx.coroutines.CancellationException) throw throwable
                Log.e(TAG, "HTTP upload failed: ${UploadLogSanitizer.throwable(throwable)}")
                translateThrowable("HTTP", throwable)
            }
        }
    }

    /** 通过 HEAD 检查可达性，取消原样传播且不提交文件。 */
    override suspend fun testConnection(profile: UploadServerProfile): UploadResult {
        val config = profile.config as? HttpConfig
            ?: return invalidConfig("HTTP profile config is invalid")

        return withContext(Dispatchers.IO) {
            val uploadUrl = buildUploadUrl(config)
            try {
                // 使用原生 OkHttpClient �?HEAD 请求测试可达性，避免�?Retrofit �?@Url 拼接
                val client = buildOkHttpClient(config)
                val request = Request.Builder()
                    .url(uploadUrl)
                    .head()
                    .apply {
                        config.headers.forEach { (k, v) -> header(k, v) }
                    }
                    .build()
                val response = client.newCall(request).awaitUploadResponse()
                response.use {
                    // 200 / 405 Method Not Allowed 均说明服务器可达
                    if (it.isSuccessful || it.code == 405) {
                        UploadResult.Success(uploadUrl)
                    } else if (it.code == 401) {
                        authFailure("HTTP authentication failed (401): check username and password")
                    } else {
                        connectionFailure("HTTP test failed with code: ${it.code}")
                    }
                }
            } catch (throwable: Throwable) {
                if (throwable is kotlinx.coroutines.CancellationException) throw throwable
                Log.e(TAG, "HTTP test connection failed: ${UploadLogSanitizer.throwable(throwable)}")
                translateThrowable("HTTP", throwable)
            }
        }
    }
}



