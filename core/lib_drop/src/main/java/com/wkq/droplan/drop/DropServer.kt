package com.wkq.droplan.drop

import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.net.InetAddress
import java.net.URLEncoder
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min

/** 通过业务层适配 lib_html；协议库不依赖网页库。 */
data class StaticAsset(val contentType: String, val length: Long?, val open: () -> InputStream)
fun interface StaticAssetProvider { fun resolve(path: String): StaticAsset? }

/** 必须显式选择监听 IPv4，禁止默认监听所有接口。port=0 用于系统分配空闲端口。 */
class DropServer(
    private val bindAddress: String,
    port: Int,
    val engine: ShareEngine,
    private val assets: StaticAssetProvider,
    private val tls: javax.net.ssl.SSLContext? = null,
    private val preferredTransport: String = "HTTP",
) : Closeable {
    private val current = ThreadLocal<NanoHTTPD.ClientHandler>()
    private val active = ConcurrentHashMap<NanoHTTPD.ClientHandler, Activity>()
    private val clients = ConcurrentHashMap.newKeySet<NanoHTTPD.ClientHandler>()
    private val eventOwners = ConcurrentHashMap<String, NanoHTTPD.ClientHandler>()
    private val pool = ThreadPoolExecutor(0, 16, 30, TimeUnit.SECONDS, SynchronousQueue()) { runnable ->
        Thread(runnable, "droplan-http").apply { isDaemon = true }
    }
    private val timer = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "droplan-cleanup").apply { isDaemon = true } }
    private val closed = AtomicBoolean()
    private var started = false
    private val http: Httpd

    init {
        val octets = bindAddress.split('.').map { it.toIntOrNull() }
        require(octets.size == 4 && octets.all { it != null && it in 0..255 })
        val address = InetAddress.getByName(bindAddress)
        require(!address.isAnyLocalAddress && !address.isMulticastAddress)
        require(port in 0..65535)
        http = Httpd(bindAddress, port)
        tls?.let { context ->
            val protocols = context.supportedSSLParameters.protocols.filter { it == "TLSv1.2" || it == "TLSv1.3" }
            require(protocols.isNotEmpty())
            http.makeSecure(context.serverSocketFactory, protocols.toTypedArray())
        }
        http.setAsyncRunner(object : NanoHTTPD.AsyncRunner {
            override fun exec(code: NanoHTTPD.ClientHandler) {
                clients.add(code)
                try {
                    pool.execute {
                        current.set(code)
                        active[code] = Activity()
                        try { code.run() } finally {
                            current.remove(); clients.remove(code); active.remove(code)
                            eventOwners.entries.removeAll { it.value == code }
                        }
                    }
                } catch (_: RejectedExecutionException) { clients.remove(code); code.close() }
            }
            override fun closed(clientHandler: NanoHTTPD.ClientHandler) { clients.remove(clientHandler) }
            override fun closeAll() { clients.toList().forEach { it.close() }; clients.clear() }
        })
    }

    val port: Int get() = http.listeningPort
    val protocol: String get() = if (tls == null) "http" else "https"
    val baseUrl: String get() { check(started); return "$protocol://$bindAddress:$port" }
    /** 入口凭据只放 fragment；不得输出到日志、测试报告或外部剪贴服务。 */
    val entryUrl: String get() = "$baseUrl/#${engine.entryToken}"

    @Synchronized fun start() {
        check(!closed.get() && !started)
        try {
            http.start(15_000, true)
            started = true
            timer.scheduleAtFixedRate({
                engine.tick()
                val now = System.nanoTime()
                active.forEach { (client, state) ->
                    if (now - state.lastWrite.get() > TimeUnit.SECONDS.toNanos(30)) client.close()
                }
            }, 1, 1, TimeUnit.SECONDS)
        } catch (error: Exception) { close(); throw error }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        engine.close(); http.stop(); clients.toList().forEach { it.close() }
        timer.shutdownNow(); pool.shutdownNow(); active.clear()
    }

    private class Activity { val lastWrite = AtomicLong(System.nanoTime()) }

    private inner class Httpd(address: String, port: Int) : NanoHTTPD(address, port) {
        override fun useGzipWhenAccepted(r: Response): Boolean = false

        override fun serve(request: IHTTPSession): Response {
            val response = try {
                validateRequest(request)
                route(request)
            } catch (error: ProtocolFailure) {
                json(error.status, JSONObject().put("error", error.code)).also {
                    if (error.status == 429 || error.status == 503) it.addHeader("Retry-After", "3")
                }
            } catch (_: Exception) {
                // 不向浏览器或日志泄露 content URI、凭据、路径及提供者异常。
                json(500, JSONObject().put("error", "source_or_server_unavailable"))
            }
            response.closeConnection(true)
            response.addHeader("Cache-Control", "no-store")
            response.addHeader("X-Content-Type-Options", "nosniff")
            response.addHeader("Referrer-Policy", "no-referrer")
            response.addHeader("X-Frame-Options", "DENY")
            response.addHeader("Content-Security-Policy", "default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'self'; frame-src 'self'; img-src 'self'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'")
            return response
        }
    }

    private fun validateRequest(request: NanoHTTPD.IHTTPSession) {
        val expectedHost = "$bindAddress:$port"
        if (request.headers["host"] != expectedHost) ShareEngine.fail(403, "invalid_host")
        val origin = request.headers["origin"]
        if (origin != null && origin != "$protocol://$expectedHost") ShareEngine.fail(403, "invalid_origin")
        if (request.headers["sec-fetch-site"] in setOf("cross-site", "same-site")) ShareEngine.fail(403, "cross_site_request")
        if (request.uri.length > 1024 || (request.queryParameterString?.length ?: 0) > 1024) ShareEngine.fail(400, "request_too_large")
        if (request.method !in setOf(NanoHTTPD.Method.GET, NanoHTTPD.Method.HEAD, NanoHTTPD.Method.POST, NanoHTTPD.Method.DELETE))
            ShareEngine.fail(405, "method_not_allowed")
        if (request.method in setOf(NanoHTTPD.Method.POST, NanoHTTPD.Method.DELETE) && request.headers["x-droplan"] != "1")
            ShareEngine.fail(403, "csrf_required")
    }

    private fun route(request: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val path = request.uri
        val method = request.method
        if (!path.startsWith("/api/v1/")) {
            if (method != NanoHTTPD.Method.GET && method != NanoHTTPD.Method.HEAD) ShareEngine.fail(405, "method_not_allowed")
            val asset = assets.resolve(path) ?: ShareEngine.fail(404, "not_found")
            if (method == NanoHTTPD.Method.HEAD && asset.length == null) return unknownLengthHead(asset.contentType)
            val input = if (method == NanoHTTPD.Method.HEAD) ByteArrayInputStream(byteArrayOf()) else asset.open()
            return if (asset.length != null) NanoHTTPD.newFixedLengthResponse(status(200), asset.contentType, input, asset.length)
                else NanoHTTPD.newChunkedResponse(status(200), asset.contentType, input)
        }
        val api = path.removePrefix("/api/v1")
        if (api == "/pairings" && method == NanoHTTPD.Method.POST) {
            val pairing = engine.pair(body(request).optString("token"))
            return json(202, JSONObject().put("id", pairing.id).put("ticket", pairing.ticket).put("state", pairing.state))
        }
        if (api.startsWith("/pairings/") && method == NanoHTTPD.Method.GET) {
            val pairing = engine.pairing(api.removePrefix("/pairings/"), request.headers["x-pairing-ticket"] ?: "")
            return json(200, JSONObject().put("state", pairing.state)).also { response ->
                pairing.cookie?.let {
                    // 原生下载需要 /api/v1 Cookie。HTTP 环境不能冒用 Secure 属性。
                    response.addHeader("Set-Cookie", "droplan=$it; HttpOnly; SameSite=Strict; Path=/api/v1; Max-Age=3600" + if (tls != null) "; Secure" else "")
                }
            }
        }
        val receiver = engine.receiver(cookie(request))
        if (api == "/session" && method == NanoHTTPD.Method.GET) return json(200, JSONObject()
            .put("id", receiver.id).put("expiresAt", receiver.expires)
            .put("events", true).put("protocol", protocol).put("websocket", "droplan.v1").put("preferredTransport", preferredTransport)
            .put("comparison", "not_compared").put("maxActiveDownloads", engine.maxActiveDownloads))
        if (api == "/events" && method == NanoHTTPD.Method.GET) return events(receiver.id, cookie(request)!!)
        if (api == "/advice" && method == NanoHTTPD.Method.POST) {
            // 只计算本接收端提供的短期观测；不扫描、不访问第三方地址，也不切换网络。
            val payload = body(request)
            val samples = payload.optJSONArray("samples") ?: ShareEngine.fail(400, "invalid_measurement")
            if (samples.length() > 2) ShareEngine.fail(400, "invalid_measurement")
            val candidates = (0 until samples.length()).map { index ->
                val sample = samples.optJSONObject(index) ?: ShareEngine.fail(400, "invalid_measurement")
                val id = sample.optString("transport")
                val speed = sample.optDouble("bytesPerSecond", Double.NaN)
                if (id !in setOf("HTTP", "WEBSOCKET") || !speed.isFinite() || speed <= 0 || speed > 1e12)
                    ShareEngine.fail(400, "invalid_measurement")
                ConnectionCandidate(id, true, preparationSeconds = 0.0, bytesPerSecond = speed, measurement = Measurement.CURRENT)
            }
            if (candidates.distinctBy { it.id }.size != candidates.size) ShareEngine.fail(400, "invalid_measurement")
            val file = engine.files[payload.optString("fileId")]
            if (payload.has("fileId") && file == null) ShareEngine.fail(404, "not_found")
            val advice = ConnectionAdvisor.recommend(candidates, file?.length)
            return json(200, JSONObject().put("transport", advice?.candidate?.id ?: JSONObject.NULL)
                .put("compared", advice?.compared ?: false).put("scope", "current_connection")
                .put("estimatedSeconds", advice?.estimatedSeconds ?: JSONObject.NULL))
        }
        if (api == "/manifest" && method == NanoHTTPD.Method.GET) {
            val rawCursor = query(request, "cursor")
            val cursor = if (rawCursor == null) 0 else rawCursor.toIntOrNull() ?: ShareEngine.fail(400, "invalid_cursor")
            if (cursor !in 0..engine.files.size) ShareEngine.fail(400, "invalid_cursor")
            val page = engine.files.values.drop(cursor).take(50)
            val items = JSONArray()
            page.forEach { file -> items.put(JSONObject().put("id", file.id).put("name", file.displayName.take(1024))
                .put("size", file.length?.toString() ?: JSONObject.NULL).put("range", file.supportsRandomAccess && file.length != null)
                .put("stableVersion", file.contentVersion != null).put("bundle", file is SnapshotZipArchive)) }
            return json(200, JSONObject().put("items", items).put("nextCursor",
                if (cursor + page.size < engine.files.size) cursor + page.size else JSONObject.NULL))
        }
        if (api == "/downloads" && method == NanoHTTPD.Method.GET) {
            val items = JSONArray()
            engine.tasks(receiver.id).forEach { items.put(taskJson(it)) }
            return json(200, JSONObject().put("tasks", items))
        }
        if (api == "/downloads" && method == NanoHTTPD.Method.POST) {
            val payload = body(request)
            return json(201, taskJson(engine.createTask(receiver.id, payload.optString("fileId"), request.headers["idempotency-key"] ?: "")))
        }
        if (api.startsWith("/downloads/")) {
            val id = api.removePrefix("/downloads/")
            if (method == NanoHTTPD.Method.GET) return json(200, taskJson(engine.task(receiver.id, id)))
            if (method == NanoHTTPD.Method.DELETE) {
                engine.cancel(receiver.id, id); return json(200, JSONObject().put("state", "CANCELLED"))
            }
        }
        if (api.startsWith("/ws/files/") && method == NanoHTTPD.Method.GET) {
            if (request.headers["origin"] != baseUrl) ShareEngine.fail(403, "invalid_origin")
            val accept = WebSocketTransfer.accept(request.headers)
            val fileId = api.removePrefix("/ws/files/")
            val source = engine.files[fileId] ?: ShareEngine.fail(404, "not_found")
            val client = current.get()
            var stream: InputStream? = null
            val task = engine.claim(receiver.id, query(request, "task") ?: "", fileId) {
                client?.close(); runCatching { stream?.close() }
            }
            try {
                stream = BufferedInputStream(source.openStream(), 64 * 1024)
                engine.responseLength(task, source.length)
                return WebSocketTransfer.Response(accept, request.inputStream, stream, source.length,
                    { bytes -> engine.progress(task, bytes) },
                    { client?.let { active[it]?.lastWrite?.set(System.nanoTime()) } },
                    { success -> engine.finish(task, success) })
            } catch (error: Exception) { runCatching { stream?.close() }; engine.finish(task, false); throw error }
        }
        if (api.startsWith("/files/") && method in setOf(NanoHTTPD.Method.GET, NanoHTTPD.Method.HEAD))
            return file(request, receiver.id, api.removePrefix("/files/"))
        if (api == "/probe" && method == NanoHTTPD.Method.GET) return probe(receiver.id)
        ShareEngine.fail(404, "not_found")
    }

    /** 最多四路、每会话一路；独立于下载许可。每次连接发送完整快照，不伪装事件历史重放。 */
    private fun events(owner: String, sessionCookie: String): NanoHTTPD.Response {
        val client = current.get() ?: ShareEngine.fail(503, "events_unavailable")
        synchronized(eventOwners) {
            if (eventOwners.containsKey(owner) || eventOwners.size >= 4) ShareEngine.fail(429, "events_limit")
            eventOwners[owner] = client
        }
        return object : NanoHTTPD.Response(status(200), "text/event-stream", null, 0) {
            override fun send(output: OutputStream) {
                try {
                    output.write(("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream; charset=utf-8\r\n" +
                        "Cache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nReferrer-Policy: no-referrer\r\n" +
                        "Connection: close\r\n\r\nretry: 3000\n\n").toByteArray(Charsets.UTF_8))
                    output.flush()
                    var previous = ""; var lastHeartbeat = 0L
                    while (!closed.get() && eventOwners[owner] == client && !Thread.currentThread().isInterrupted) {
                        // 撤销与过期仅结束状态流，绝不因 SSE 断线取消文件下载。
                        engine.receiver(sessionCookie)
                        val array = JSONArray(); engine.tasks(owner).forEach { array.put(taskJson(it)) }
                        val snapshot = JSONObject().put("tasks", array).toString()
                        val now = System.nanoTime()
                        val frame = if (snapshot != previous) "event: snapshot\ndata: $snapshot\n\n"
                            else if (now - lastHeartbeat > TimeUnit.SECONDS.toNanos(5)) "event: heartbeat\ndata: {}\n\n" else null
                        if (frame != null) {
                            output.write(frame.toByteArray(Charsets.UTF_8)); output.flush()
                            active[client]?.lastWrite?.set(now); lastHeartbeat = now; previous = snapshot
                        }
                        Thread.sleep(1000)
                    }
                } catch (_: Exception) {
                    // 客户端断开、撤销、网络消失均在 finally 释放状态名额。
                } finally { eventOwners.remove(owner, client) }
            }
        }
    }

    private fun file(request: NanoHTTPD.IHTTPSession, owner: String, fileId: String): NanoHTTPD.Response {
        val source = engine.files[fileId] ?: ShareEngine.fail(404, "not_found")
        val size = source.length
        val etag = source.contentVersion?.let { "\"$it\"" }
        val ifRange = request.headers["if-range"]
        val range = if (source.supportsRandomAccess && size != null && (ifRange == null || ifRange == etag))
            HttpByteRange.parse(request.headers["range"], size) else RangeResult.Ignore
        val head = request.method == NanoHTTPD.Method.HEAD
        if (!head) {
            val task = engine.task(owner, query(request, "task") ?: "")
            if (task.fileId != fileId) ShareEngine.fail(404, "not_found")
        }
        // HEAD 的 Range 按 RFC 忽略，且不消耗下载名额。
        if (!head && range == RangeResult.Unsatisfiable) return json(416, JSONObject().put("error", "invalid_range"))
            .also { it.addHeader("Content-Range", "bytes */$size") }
        val partial = if (!head) range as? RangeResult.Partial else null
        val length = partial?.length ?: size
        var task: DownloadTask? = null
        var stream: ObservedInput? = null
        val client = current.get()
        val response = if (head) {
            if (size != null) NanoHTTPD.newFixedLengthResponse(status(200), safeMime(source.mimeType), ByteArrayInputStream(byteArrayOf()), size)
            else unknownLengthHead(safeMime(source.mimeType))
        } else {
            task = engine.claim(owner, query(request, "task") ?: "", fileId) { client?.close(); runCatching { stream?.close() } }
            engine.responseLength(task, length)
            try {
                stream = ObservedInput(BufferedInputStream(if (partial != null) source.openRange(partial.first, partial.length) else source.openStream(), 64 * 1024), length) {
                    bytes -> engine.progress(task, bytes)
                }
                TransferResponse(if (partial != null) 206 else 200, safeMime(source.mimeType), stream, length,
                    { success -> engine.finish(task, success) }, client)
            } catch (error: Exception) {
                runCatching { stream?.close() }; engine.finish(task, false); throw error
            }
        }
        response.addHeader("Accept-Ranges", if (source.supportsRandomAccess && size != null) "bytes" else "none")
        response.addHeader("Content-Disposition", attachmentHeader(source.displayName))
        etag?.let { response.addHeader("ETag", it) }
        partial?.let { response.addHeader("Content-Range", "bytes ${it.first}-${it.last}/$size") }
        return response
    }

    private fun probe(owner: String): NanoHTTPD.Response {
        val client = current.get()
        engine.startProbe(owner) { client?.close() }
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        val random = java.security.SecureRandom()
        var remaining = 32L * 1024 * 1024
        val input = object : InputStream() {
            override fun read(): Int { val b = ByteArray(1); return if (read(b) < 0) -1 else b[0].toInt() and 255 }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (len == 0) return 0
                if (remaining == 0L || System.nanoTime() >= end) return -1
                val count = min(min(len, 64 * 1024).toLong(), remaining).toInt()
                val block = ByteArray(count); random.nextBytes(block); System.arraycopy(block, 0, b, off, count)
                remaining -= count; return count
            }
        }
        return TransferResponse(200, "application/octet-stream", ObservedInput(input, null) {}, null,
            { engine.finishProbe() }, client)
    }

    private inner class TransferResponse(
        code: Int, mime: String, private val input: ObservedInput, length: Long?,
        private val finish: (Boolean) -> Unit, private val client: NanoHTTPD.ClientHandler?,
    ) : NanoHTTPD.Response(status(code), mime, input, length ?: -1L) {
        override fun send(outputStream: OutputStream) {
            var failed = false
            val output = object : FilterOutputStream(outputStream) {
                override fun write(b: Int) { try { out.write(b); touch() } catch (e: IOException) { failed = true; throw e } }
                override fun write(b: ByteArray, off: Int, len: Int) {
                    try { out.write(b, off, len); touch() } catch (e: IOException) { failed = true; throw e }
                }
                override fun flush() { try { out.flush() } catch (e: IOException) { failed = true; throw e } }
                private fun touch() { client?.let { active[it]?.lastWrite?.set(System.nanoTime()) } }
            }
            try { super.send(output) } finally {
                runCatching { input.close() }
                finish(!failed && !input.failed && input.complete)
            }
        }
    }

    private class ObservedInput(input: InputStream, private val length: Long?, val update: (Long) -> Unit) : FilterInputStream(input) {
        private var count = 0L
        private var eof = false
        var failed = false; private set
        val complete: Boolean get() = if (length != null) count == length else eof
        override fun read(): Int {
            val bytes = ByteArray(1)
            return if (read(bytes, 0, 1) < 0) -1 else bytes[0].toInt() and 255
        }
        override fun read(b: ByteArray, off: Int, len: Int): Int = try {
            if (len == 0) 0 else {
                val remaining = length?.minus(count)
                if (remaining == 0L) -1 else {
                    val read = `in`.read(b, off, if (remaining != null) min(len.toLong(), remaining).toInt() else len)
                    if (read < 0) { eof = true; if (remaining != null && remaining > 0) { failed = true; throw EOFException() } }
                    else { count += read; update(count) }
                    read
                }
            }
        } catch (error: IOException) { failed = true; throw error }
    }

    private fun body(request: NanoHTTPD.IHTTPSession): JSONObject {
        if (request.headers["transfer-encoding"] != null) ShareEngine.fail(400, "unsupported_transfer_encoding")
        if (request.headers["content-type"]?.substringBefore(';')?.trim() != "application/json") ShareEngine.fail(415, "json_required")
        val length = request.headers["content-length"]?.toLongOrNull() ?: ShareEngine.fail(411, "length_required")
        if (length !in 1..4096) ShareEngine.fail(413, "body_too_large")
        val bytes = ByteArray(length.toInt())
        var offset = 0
        while (offset < bytes.size) {
            val read = request.inputStream.read(bytes, offset, bytes.size - offset)
            if (read < 0) ShareEngine.fail(400, "incomplete_body")
            offset += read
        }
        return try { JSONObject(String(bytes, Charsets.UTF_8)) } catch (_: Exception) { ShareEngine.fail(400, "invalid_json") }
    }

    private fun taskJson(task: DownloadSnapshot): JSONObject = JSONObject().put("id", task.id).put("fileId", task.fileId)
        .put("state", task.state.name).put("queuePosition", task.queuePosition).put("bytesSent", task.bytesSent.toString())
        .put("permitExpiresAt", task.permitExpiresAt).put("bytesPerSecond", task.bytesPerSecond)
        .put("responseLength", task.responseLength?.toString() ?: JSONObject.NULL)
        .put("url", "/api/v1/files/${task.fileId}?task=${task.id}")

    private fun cookie(request: NanoHTTPD.IHTTPSession): String? = request.headers["cookie"]?.split(';')
        ?.map { it.trim() }?.firstOrNull { it.startsWith("droplan=") }?.removePrefix("droplan=")
    private fun query(request: NanoHTTPD.IHTTPSession, key: String): String? = request.parameters[key]?.singleOrNull()
    private fun unknownLengthHead(mime: String): NanoHTTPD.Response = object : NanoHTTPD.Response(status(200), mime, ByteArrayInputStream(byteArrayOf()), 0) {
        override fun sendContentLengthHeaderIfNotAlreadyPresent(pw: PrintWriter, defaultSize: Long): Long = 0
    }
    private fun json(code: Int, value: JSONObject): NanoHTTPD.Response = NanoHTTPD.newFixedLengthResponse(status(code), "application/json; charset=utf-8", value.toString())
    private fun status(code: Int): NanoHTTPD.Response.Status = NanoHTTPD.Response.Status.lookup(code)
    private fun safeMime(value: String): String = if (value.matches(Regex("[a-zA-Z0-9!#$&^_.+-]+/[a-zA-Z0-9!#$&^_.+-]+"))) value else "application/octet-stream"
    private fun attachmentHeader(value: String): String {
        val name = value.filter { it >= ' ' && it != '\u007f' }
            .replace('/', '_').replace('\\', '_').take(255).trim().ifEmpty { "download" }
        // 某些浏览器优先采用 filename；提供真实 ASCII 备用名并保留扩展名。
        val fallback = name.map { if (it in ' '..'~' && it !in "\"%:;<>|?*") it else '_' }
            .joinToString("").trim(' ', '.').ifEmpty { "download" }
        return "attachment; filename=\"$fallback\"; filename*=UTF-8''${encodedName(name)}"
    }
    private fun encodedName(value: String): String = URLEncoder.encode(value.filter { it >= ' ' && it != '\u007f' }.take(255), "UTF-8").replace("+", "%20")
}
