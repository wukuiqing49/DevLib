package com.wkq.iptc.upload

import android.content.Context
import android.net.Uri
import okhttp3.MediaType
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.IOException

/**
 * 从 Content Uri 流式读取内容并写入 OkHttp，同时报告上传进度。
 */
class UriRequestBody(
    private val context: Context,
    private val uri: Uri,
    private val contentType: MediaType?,
    private val contentLength: Long,
    private val skipOffset: Long = 0L,
    private val onProgress: ((bytesWritten: Long, totalBytes: Long) -> Unit)? = null
) : RequestBody() {

    init { require(contentLength >= 0 && skipOffset in 0..contentLength) }

    /** URI 源可能变化，禁止网络层自动重放此上传体。 */
    override fun isOneShot(): Boolean = true

    override fun contentType(): MediaType? = contentType

    override fun contentLength(): Long = contentLength - skipOffset

    @Throws(IOException::class)
    /** 流式写入并验证偏移和声明长度，源文件变化时明确失败而不提交截断内容。 */
    override fun writeTo(sink: BufferedSink) {
        val inputStream = context.contentResolver.openInputStream(uri)
            ?: throw IOException("Unable to open input stream for URI: $uri")
        inputStream.use { input ->
            if (skipOffset > 0L) {
                if (input.safeSkip(skipOffset) != skipOffset) throw IOException("Upload source is shorter than resume offset")
            }
            val buffer = ByteArray(8192)
            var bytesWritten = skipOffset
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                if (read > contentLength - bytesWritten) throw IOException("Upload source grew during transfer")
                sink.write(buffer, 0, read)
                bytesWritten += read
                onProgress?.invoke(bytesWritten, contentLength)
            }
            if (bytesWritten != contentLength) throw IOException("Upload source size changed during transfer")
        }
    }
}

