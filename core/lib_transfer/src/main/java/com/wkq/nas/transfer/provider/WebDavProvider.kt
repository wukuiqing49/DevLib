package com.wkq.nas.transfer.provider

import com.wkq.nas.transfer.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import org.xmlpull.v1.XmlPullParser
import android.util.Xml
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/** 合并 YuanBaoTv 的 GET/PROPFIND 与 SiteReport 的 PUT/大小校验/MOVE 流程。 */
internal class WebDavProvider(root: String, username: String, password: String, certificateSha256: String = "") : StorageProvider {
    override val capabilities = StorageCapabilities(true, false, false, false, false, serverSideMove = true)
    private val base = (root.trimEnd('/') + "/").toHttpUrl()
    private val auth = if (username.isNotEmpty()) Credentials.basic(username, password) else null
    private val client = WebDavCertificates.configure(OkHttpClient.Builder(), root, certificateSha256).connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS).writeTimeout(30, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build()
    private fun url(path: String): HttpUrl = base.newBuilder().apply {
        TransferPaths.normalize(path).split('/').filter(String::isNotEmpty).forEach(::addPathSegment)
    }.build()
    private fun request(path: String) = Request.Builder().url(url(path)).apply { auth?.let { header("Authorization", it) } }
    private fun checked(request: Request): Response {
        val response = try { client.newCall(request).execute() }
        catch (error: java.io.IOException) {
            // HTTP 响应头尚未读完的 EOF 属于对端断流；不把本地源文件 IO 一概归为网络错误。
            val interruptedResponse = generateSequence<Throwable>(error) { it.cause }.take(8)
                .any { it is java.io.EOFException }
            if (interruptedResponse && classify(error).first == TransferError.UNKNOWN)
                throw TransferException(TransferError.NETWORK, true).apply { initCause(error) }
            throw error
        }
        if (response.isSuccessful) return response
        val code = response.code
        response.close()
        throw when (code) {
            401 -> TransferException(TransferError.AUTHENTICATION)
            403 -> TransferException(TransferError.PERMISSION)
            404 -> TransferException(TransferError.NOT_FOUND)
            409, 412 -> TransferException(TransferError.CONFLICT)
            507 -> TransferException(TransferError.SPACE)
            429, in 500..599 -> TransferException(TransferError.NETWORK, true)
            else -> TransferException(TransferError.UNSUPPORTED)
        }
    }
    override fun stat(path: String): FileMetadata? = try {
        propfind(path, "0").firstOrNull { it.path == TransferPaths.normalize(path) }
            ?: throw TransferException(TransferError.VERIFICATION)
    } catch (error: TransferException) { if (error.error == TransferError.NOT_FOUND) null else throw error }
    override fun list(path: String) = propfind(path, "1").filter { it.path != TransferPaths.normalize(path) }
    override fun visit(path: String, accept: (FileMetadata) -> Unit) {
        val root = TransferPaths.normalize(path)
        propfind(path, "1") { if (it.path != root) accept(it) }
    }
    private fun propfind(path: String, depth: String, accept: ((FileMetadata) -> Unit)? = null): List<FileMetadata> {
        val xml = "<?xml version=\"1.0\"?><d:propfind xmlns:d=\"DAV:\"><d:prop><d:resourcetype/><d:getcontentlength/><d:getlastmodified/></d:prop></d:propfind>"
        return checked(request(path).header("Depth", depth).method("PROPFIND", xml.toRequestBody("application/xml".toMediaType())).build()).use { response ->
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            parser.setInput(response.body?.charStream() ?: throw TransferException(TransferError.VERIFICATION))
            val result = mutableListOf<FileMetadata>()
            val seen = hashSetOf<String>()
            var count = 0
            var href = ""
            var directory = false
            var size = -1L
            var modified = -1L
            var propStatus = ""
            var inPropstat = false
            var validProperties = false
            var responseStatus = ""
            var propDirectory = false
            var propSize = -1L
            var propModified = -1L
            var inResponse = false
            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == XmlPullParser.START_TAG && parser.namespace == "DAV:") when (parser.name) {
                    "response" -> { inResponse = true; href = ""; directory = false; size = -1; modified = -1; validProperties = false; responseStatus = "" }
                    "propstat" -> { inPropstat = true; propDirectory = false; propSize = -1; propModified = -1; propStatus = "" }
                    "href" -> if (inResponse) href = parser.nextText()
                    "collection" -> propDirectory = true
                    "getcontentlength" -> propSize = parser.nextText().toLongOrNull() ?: -1
                    "getlastmodified" -> propModified = parseDate(parser.nextText())
                    "status" -> if (inPropstat) propStatus = parser.nextText() else responseStatus = parser.nextText()
                }
                if (parser.eventType == XmlPullParser.END_TAG && parser.name == "propstat" && parser.namespace == "DAV:") {
                    if (propStatus.contains(" 200 ") || propStatus.isEmpty()) {
                        validProperties = true
                        directory = directory || propDirectory
                        if (propSize >= 0) size = propSize
                        if (propModified > 0) modified = propModified
                    }
                    inPropstat = false
                }
                if (parser.eventType == XmlPullParser.END_TAG && parser.name == "response" && parser.namespace == "DAV:") {
                    if (validProperties || responseStatus.contains(" 200 ")) {
                        if (href.isBlank()) throw TransferException(TransferError.VERIFICATION)
                        val location = url(path).resolve(href) ?: throw TransferException(TransferError.INVALID_PATH)
                        require(location.scheme == base.scheme && location.host == base.host && location.port == base.port)
                        val basePath = base.pathSegments.filter(String::isNotEmpty)
                        val segments = location.pathSegments.filter(String::isNotEmpty)
                        require(segments.take(basePath.size) == basePath)
                        val relative = TransferPaths.normalize(segments.drop(basePath.size).joinToString("/"))
                        val requested = TransferPaths.normalize(path)
                        require(relative == requested || TransferPaths.parent(relative) == requested)
                        if (++count > 100000) throw TransferException(TransferError.UNSUPPORTED)
                        if (seen.add(relative)) {
                            val metadata = FileMetadata(relative, directory, if (directory) 0 else size, modified)
                            if (accept == null) result.add(metadata) else accept(metadata)
                        }
                    }
                    inResponse = false
                }
                parser.next()
            }
            result
        }
    }
    override fun read(path: String): InputStream {
        val response = checked(request(path).get().build())
        val body = response.body ?: run { response.close(); throw TransferException(TransferError.VERIFICATION) }
        return object : java.io.FilterInputStream(body.byteStream()) {
            override fun close() { try { super.close() } finally { response.close() } }
        }
    }
    override fun mkdirs(path: String) {
        var current = ""
        TransferPaths.normalize(path).split('/').filter(String::isNotEmpty).forEach { part ->
            current = TransferPaths.join(current, part)
            val existing = stat(current)
            if (existing == null) checked(request(current).method("MKCOL", null).build()).close()
            else if (!existing.directory) throw TransferException(TransferError.CONFLICT)
        }
    }
    override fun write(path: String, input: InputStream, size: Long, progress: (Long) -> Unit) {
        val body = object : RequestBody() {
            override fun contentType() = "application/octet-stream".toMediaType()
            override fun contentLength() = size
            override fun isOneShot() = true
            override fun writeTo(sink: BufferedSink) { copyBytes(input, sink.outputStream(), progress) }
        }
        checked(request(path).header("If-None-Match", "*").put(body).build()).close()
    }
    override fun moveNew(source: String, target: String, check: () -> Unit) {
        check()
        checked(request(source).header("Destination", url(target).toString()).header("Overwrite", "F").method("MOVE", null).build()).close()
    }
    override fun delete(path: String) {
        try { checked(request(path).delete().build()).close() }
        catch (error: TransferException) { if (error.error != TransferError.NOT_FOUND) throw error }
    }
    override fun close() { client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
    private fun parseDate(value: String): Long = runCatching {
        SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).apply { timeZone = TimeZone.getTimeZone("GMT") }.parse(value)?.time ?: -1
    }.getOrDefault(-1)
}
