package com.wkq.droplan.drop

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

class DropServerTest {
    @Test fun attachmentNamesKeepAsciiFallbackAndEncodeUnicodeWithoutHeaderInjection() {
        for (name in listOf("DropLAN-test.bin", "holiday photo (1).jpg", "中文\r\n\"/照片.png")) {
            val source = object : ShareSource by Source(8) { override val displayName = name }
            server(source).use { server ->
                val reply = call(server, "/api/v1/files/${source.id}", "HEAD", authorize(server))
                assertEquals(200, reply.status)
                val header = requireNotNull(reply.header("Content-Disposition"))
                assertTrue(header.all { it in ' '..'~' })
                assertFalse(header.contains('\r')); assertFalse(header.contains('\n'))
                val fallback = header.substringAfter("filename=\"").substringBefore('"')
                assertFalse(fallback.contains('/')); assertFalse(fallback.contains('\\'))
                assertTrue(fallback.endsWith(name.substringAfterLast('.')))
                if (name.all { it in ' '..'~' }) assertEquals(name, fallback)
                else {
                    assertTrue(header.contains("filename*=UTF-8''%E4%B8%AD%E6%96%87"))
                    assertFalse(header.contains("%0D")); assertFalse(header.contains("%0A"))
                }
            }
        }
    }

    @Test fun revocationCancelsOnlyUnfinishedTasksAndPreservesTerminalHistory() {
        var now = 1_000_000L
        ShareEngine(listOf(Source(8)), true, now = { now }).use { engine ->
            fun receiver(): Receiver {
                val pair = engine.pair(engine.entryToken)
                return engine.receiver(engine.pairing(pair.id, pair.ticket).cookie)
            }
            val owner = receiver(); val other = receiver()
            fun next() = engine.createTask(owner.id, Source().id, ShareEngine.randomToken())
            val sent = next(); engine.finish(engine.claim(owner.id, sent.id, sent.fileId) {}, true)
            val failed = next(); engine.finish(engine.claim(owner.id, failed.id, failed.fileId) {}, false)
            val expired = next(); now += 31_000; engine.tick()
            assertEquals(DownloadState.EXPIRED, engine.task(owner.id, expired.id).state)
            var cancellations = 0
            val streaming = next(); val lease = engine.claim(owner.id, streaming.id, streaming.fileId) { cancellations++ }
            val queued = next()
            val unrelated = engine.createTask(other.id, Source().id, ShareEngine.randomToken())
            engine.cancel(owner.id, sent.id)
            engine.revoke(owner.id); engine.revoke(owner.id)
            engine.finish(lease, false)
            val states = engine.senderTasks().associate { it.download.id to it.download.state }
            assertEquals(DownloadState.SENT, states[sent.id])
            assertEquals(DownloadState.FAILED, states[failed.id])
            assertEquals(DownloadState.EXPIRED, states[expired.id])
            assertEquals(DownloadState.CANCELLED, states[streaming.id])
            assertEquals(DownloadState.CANCELLED, states[queued.id])
            assertEquals(DownloadState.READY, states[unrelated.id])
            assertEquals(1, cancellations)
        }
    }

    @Test fun restoredTaskListIsAuthorizedOwnerScopedAndDoesNotRestartTransfers() {
        server(Source(8)).use { server ->
            assertEquals(401, call(server, "/api/v1/downloads").status)
            val a = authorize(server); val b = authorize(server)
            val first = task(server, a); val other = task(server, b)
            val before = call(server, "/api/v1/downloads", cookie = a).json().getJSONArray("tasks")
            assertEquals(1, before.length())
            assertEquals(first.getString("id"), before.getJSONObject(0).getString("id"))
            assertFalse(before.toString().contains(other.getString("id")))
            assertEquals("READY", before.getJSONObject(0).getString("state"))
            assertEquals(8, call(server, first.getString("url"), cookie = a).data.size)
            val after = call(server, "/api/v1/downloads", cookie = a).json().getJSONArray("tasks")
            assertEquals("SENT", after.getJSONObject(0).getString("state"))
            assertEquals("8", after.getJSONObject(0).getString("bytesSent"))
        }
    }

    @Test fun sseGlobalLimitAndDisconnectLeaveDownloadsAvailable() {
        server(Source(8)).use { server ->
            val cookies = (1..5).map { authorize(server) }
            val pending = task(server, cookies[0])
            val connections = cookies.take(4).map { cookie ->
                (URL(server.baseUrl + "/api/v1/events").openConnection() as HttpURLConnection).apply {
                    readTimeout = 5000; setRequestProperty("Cookie", cookie)
                    assertEquals(200,responseCode); assertTrue(inputStream.bufferedReader().readLine().startsWith("retry:"))
                }
            }
            try { assertEquals(429,call(server,"/api/v1/events",cookie=cookies[4]).status) }
            finally { connections.forEach { it.disconnect() } }
            assertEquals(8,call(server,pending.getString("url"),cookie=cookies[0]).data.size)
        }
    }
    @Test fun preparedZipUsesTheSameAuthorizationQueueAndRangeProtocol() {
        val parent = java.nio.file.Files.createTempDirectory("droplan-http-zip").toFile()
        try {
            SnapshotZipArchive.prepare(java.io.File(parent,"ready"),listOf(Source(4096)),SnapshotZipArchive.Mode.STORE,1024*1024).use { archive ->
                DropServer("127.0.0.1",0,ShareEngine(listOf(archive),true),StaticAssetProvider { null }).use { server ->
                    server.start(); val a=authorize(server); val b=authorize(server)
                    assertEquals(401,call(server,"/api/v1/files/${archive.id}").status)
                    val task=call(server,"/api/v1/downloads","POST",a,
                        JSONObject().put("fileId",archive.id).toString(),mapOf("Idempotency-Key" to ShareEngine.randomToken())).json()
                    assertEquals(404,call(server,task.getString("url"),cookie=b).status)
                    val reply=call(server,task.getString("url"),cookie=a,headers=mapOf("Range" to "bytes=10-99"))
                    assertEquals(206,reply.status)
                    val snapshot = server.engine.senderTasks().single().download
                    assertEquals(90L, snapshot.responseLength)
                    assertEquals(90L, snapshot.bytesSent)
                    assertEquals(DownloadState.SENT, snapshot.state)
                    assertEquals("90",call(server,"/api/v1/downloads/${task.getString("id")}",cookie=a).json().getString("responseLength"))
                    assertArrayEquals(archive.openRange(10,90).use { it.readBytes() },reply.data)
                    assertEquals("\"${archive.contentVersion}\"",reply.header("ETag"))
                }
            }
        } finally { parent.delete() }
    }
    @Test fun sseIsAuthorizedOwnerScopedLimitedAndDoesNotConsumeDownloadSlots() {
        server(Source(8)).use { server ->
            assertEquals(401, call(server, "/api/v1/events").status)
            val a = authorize(server); val b = authorize(server)
            val aTask = task(server, a); val bTask = task(server, b)
            val connection = URL(server.baseUrl + "/api/v1/events").openConnection() as HttpURLConnection
            connection.readTimeout = 5000; connection.setRequestProperty("Cookie", a)
            try {
                assertEquals(200, connection.responseCode)
                assertTrue(connection.contentType.startsWith("text/event-stream"))
                val input = connection.inputStream.bufferedReader()
                var payload: String? = null
                repeat(12) { if (payload == null) { val line = input.readLine(); if (line.startsWith("data:")) payload = line.removePrefix("data:").trim() } }
                val tasks = JSONObject(payload!!).getJSONArray("tasks")
                assertEquals(1, tasks.length()); assertEquals(aTask.getString("id"), tasks.getJSONObject(0).getString("id"))
                assertFalse(payload!!.contains(bTask.getString("id")))
                assertEquals(429, call(server, "/api/v1/events", cookie = a).status)
                assertEquals(8, call(server, bTask.getString("url"), cookie = b).data.size)
                server.engine.revoke(server.engine.receiver(a.substringAfter('=')).id)
                // 撤销在下一次检查时关闭状态流。
                var ended = false
                repeat(12) { if (!ended) ended = input.readLine() == null }
                assertTrue(ended)
            } finally { connection.disconnect() }
        }
    }
    @Test fun configuredSingleDownloadLimitIsAdvertisedAndEnforced() {
        val source = Source()
        DropServer("127.0.0.1", 0, ShareEngine(listOf(source), true, 1), StaticAssetProvider { null }).use { server ->
            server.start()
            val first = authorize(server)
            val second = authorize(server)
            assertEquals(1, call(server, "/api/v1/session", cookie = first).json().getInt("maxActiveDownloads"))
            assertEquals("READY", task(server, first).getString("state"))
            assertEquals("QUEUED", task(server, second).getString("state"))
        }
    }
    private class Source(override val length: Long? = 5L * 1024 * 1024 * 1024, override val supportsRandomAccess: Boolean = true) : ShareSource {
        override val id = "file_0123456789abcdef"
        override val displayName = "中文\r\n<script>.bin"
        override val mimeType = "application/octet-stream"
        override val contentVersion = "a".repeat(64)
        override fun openStream() = stream(0, length ?: 123456)
        override fun openRange(offset: Long, length: Long) = stream(offset, length)
        fun stream(offset: Long, count: Long) = object : InputStream() {
            var position = offset
            var remaining = count
            override fun read(): Int { if (remaining == 0L) return -1; remaining--; return (position++ % 251).toInt() }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (len == 0) return 0
                if (remaining == 0L) return -1
                val n = minOf(len.toLong(), remaining).toInt()
                for (i in 0 until n) b[off + i] = (position++ % 251).toByte()
                remaining -= n; return n
            }
        }
    }
    private data class Reply(val status: Int, val data: ByteArray, val headers: Map<String?, List<String>>) {
        fun json() = JSONObject(String(data))
        fun header(name: String) = headers.entries.firstOrNull { it.key.equals(name, true) }?.value?.firstOrNull()
    }
    private fun call(server: DropServer, path: String, method: String = "GET", cookie: String? = null,
                     body: String? = null, headers: Map<String, String> = emptyMap()): Reply {
        val connection = URL(server.baseUrl + path).openConnection() as HttpURLConnection
        connection.connectTimeout = 5000; connection.readTimeout = 10000
        connection.requestMethod = method
        cookie?.let { connection.setRequestProperty("Cookie", it) }
        connection.setRequestProperty("X-DropLAN", "1")
        headers.forEach { (key, value) -> connection.setRequestProperty(key, value) }
        if (body != null) {
            connection.doOutput = true; connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(body.toByteArray()) }
        }
        return try {
            val status = connection.responseCode
            Reply(status, (if (status < 400) connection.inputStream else connection.errorStream)?.use { it.readBytes() } ?: byteArrayOf(), connection.headerFields)
        } finally { connection.disconnect() }
    }
    private fun server(source: ShareSource = Source(), auto: Boolean = true) = DropServer("127.0.0.1", 0, ShareEngine(listOf(source), auto),
        StaticAssetProvider { path -> if (path == "/") StaticAsset("text/html", 5) { ByteArrayInputStream("hello".toByteArray()) } else null }).apply { start() }
    private fun authorize(server: DropServer): String {
        val pair = call(server, "/api/v1/pairings", "POST", body = JSONObject().put("token", server.entryUrl.substringAfter('#')).toString()).json()
        if (pair.getString("state") == "PENDING") assertTrue(server.engine.approve(pair.getString("id")))
        val result = call(server, "/api/v1/pairings/" + pair.getString("id"), headers = mapOf("X-Pairing-Ticket" to pair.getString("ticket")))
        assertEquals("APPROVED", result.json().getString("state"))
        assertTrue(result.header("Set-Cookie")!!.contains("HttpOnly"))
        return result.header("Set-Cookie")!!.substringBefore(';')
    }
    private fun task(server: DropServer, cookie: String, key: String = ShareEngine.randomToken()) = call(server, "/api/v1/downloads", "POST", cookie,
        JSONObject().put("fileId", "file_0123456789abcdef").toString(), mapOf("Idempotency-Key" to key)).json()

    @Test fun realHttpApprovalAuthorizationAndOwnership() {
        server(auto = false).use { server ->
            assertEquals("hello", String(call(server, "/").data))
            assertEquals(401, call(server, "/api/v1/manifest").status)
            assertEquals(403, call(server, "/api/v1/pairings", "POST", body = "{\"token\":\"bad\"}").status)
            val a = authorize(server); val b = authorize(server)
            assertEquals(1, call(server, "/api/v1/manifest", cookie = a).json().getJSONArray("items").length())
            val key = ShareEngine.randomToken(); val first = task(server, a, key)
            assertEquals(first.getString("id"), task(server, a, key).getString("id"))
            assertEquals(404, call(server, "/api/v1/downloads/" + first.getString("id"), cookie = b).status)
            // JDK HttpURLConnection 会静默移除 Origin；用原始报文核验真实防护。
            java.net.Socket("127.0.0.1", server.port).use { socket ->
                socket.soTimeout = 5000
                socket.getOutputStream().write(("GET /api/v1/session HTTP/1.1\r\nHost: 127.0.0.1:${server.port}\r\n" +
                    "Cookie: $a\r\nOrigin: http://attacker.invalid\r\nConnection: close\r\n\r\n").toByteArray())
                assertTrue(socket.getInputStream().bufferedReader().readLine().contains("403"))
            }
            server.engine.revoke(server.engine.receivers().first().id)
            assertEquals(401, call(server, "/api/v1/session", cookie = a).status)
        }
    }

    @Test fun fiveGiBMetadataLargeOffsetRangeAndIfRange() {
        val source = Source()
        server(source).use { server ->
            val cookie = authorize(server)
            val head = call(server, "/api/v1/files/${source.id}", "HEAD", cookie)
            assertEquals("5368709120", head.header("Content-Length")); assertEquals(0, head.data.size)
            val download = task(server, cookie)
            val offset = 4294967296L; val count = 1024L * 1024
            val response = call(server, download.getString("url"), cookie = cookie,
                headers = mapOf("Range" to "bytes=$offset-${offset + count - 1}", "If-Range" to "\"${source.contentVersion}\""))
            assertEquals(206, response.status); assertEquals(count.toInt(), response.data.size)
            val digest = MessageDigest.getInstance("SHA-256")
            assertArrayEquals(digest.digest(source.stream(offset, count).readBytes()), digest.digest(response.data))
            assertEquals("bytes $offset-${offset + count - 1}/${source.length}", response.header("Content-Range"))
            assertFalse(response.header("Content-Disposition")!!.contains('\r'))
            assertEquals(416, call(server, download.getString("url"), cookie = cookie, headers = mapOf("Range" to "bytes=99999999999-")).status)
        }
        server(Source(1024)).use { server ->
            val cookie = authorize(server); val download = task(server, cookie)
            val response = call(server, download.getString("url"), cookie = cookie,
                headers = mapOf("Range" to "bytes=100-199", "If-Range" to "\"changed\""))
            assertEquals(200, response.status); assertEquals(1024, response.data.size)
        }
    }

    @Test fun unknownLengthStreamingAndSequentialRangeFallback() {
        server(Source(null, false)).use { server ->
            val cookie = authorize(server); val download = task(server, cookie)
            val head = call(server, "/api/v1/files/file_0123456789abcdef", "HEAD", cookie)
            assertNull(head.header("Content-Length"))
            val response = call(server, download.getString("url"), cookie = cookie, headers = mapOf("Range" to "bytes=5-10"))
            assertEquals(200, response.status); assertEquals(123456, response.data.size)
            assertEquals("chunked", response.header("Transfer-Encoding"))
        }
    }

    @Test fun boundedProbeAndBusyRejection() {
        server(Source(1024)).use { server ->
            val cookie = authorize(server)
            val probe = call(server, "/api/v1/probe", cookie = cookie)
            assertEquals(200, probe.status); assertTrue(probe.data.size <= 32 * 1024 * 1024)
            assertNull(probe.header("Content-Encoding"))
            task(server, cookie)
            assertEquals(429, call(server, "/api/v1/probe", cookie = cookie).status)
        }
    }

    @Test fun schedulerLimitsDuplicateClaimsExpiryCancelAndPause() {
        var now = 1_000_000L
        val engine = ShareEngine(listOf(Source(1024)), true, now = { now })
        fun receiver(): Receiver {
            val pair = engine.pair(engine.entryToken)
            return engine.receiver(engine.pairing(pair.id, pair.ticket).cookie)
        }
        engine.use {
            val a = receiver(); val b = receiver(); val c = receiver()
            val a1 = engine.createTask(a.id, Source().id, ShareEngine.randomToken())
            val a2 = engine.createTask(a.id, Source().id, ShareEngine.randomToken())
            val b1 = engine.createTask(b.id, Source().id, ShareEngine.randomToken())
            val c1 = engine.createTask(c.id, Source().id, ShareEngine.randomToken())
            assertEquals(DownloadState.READY, a1.state); assertEquals(DownloadState.QUEUED, a2.state)
            assertEquals(DownloadState.READY, b1.state); assertEquals(DownloadState.QUEUED, c1.state)
            var cancelled = false
            val lease = engine.claim(a.id, a1.id, Source().id) { cancelled = true }
            try { engine.claim(a.id, a1.id, Source().id) {}; fail() } catch (e: ProtocolFailure) { assertEquals(429, e.status) }
            engine.revoke(a.id); assertTrue(cancelled)
            engine.finish(lease, false)
            assertEquals(DownloadState.READY, engine.task(c.id, c1.id).state)
            now += 31_000; engine.tick()
            assertEquals(DownloadState.EXPIRED, engine.task(b.id, b1.id).state)
            engine.pauseNewDownloads(true)
            try { engine.createTask(c.id, Source().id, ShareEngine.randomToken()); fail() } catch (e: ProtocolFailure) { assertEquals(503, e.status) }
        }
    }

    @Test fun receiverAdviceRequiresAuthorizationAndOnlyComparesDistinctObservedTransports() {
        server().use { server ->
            val sample = """{"samples":[{"transport":"HTTP","bytesPerSecond":100},{"transport":"WEBSOCKET","bytesPerSecond":200}]}"""
            assertEquals(401, call(server,"/api/v1/advice","POST",body=sample).status)
            val cookie = authorize(server)
            val reply = call(server,"/api/v1/advice","POST",cookie,sample).json()
            assertEquals("WEBSOCKET",reply.getString("transport")); assertTrue(reply.getBoolean("compared"))
            assertEquals("current_connection",reply.getString("scope"))
            assertTrue(reply.isNull("estimatedSeconds"))
            val one = call(server,"/api/v1/advice","POST",cookie,"""{"samples":[{"transport":"HTTP","bytesPerSecond":100}]}""").json()
            assertFalse(one.getBoolean("compared"))
            for (bad in listOf(sample.replace("WEBSOCKET","HTTP"),sample.replace("200","-1"),sample.replace("WEBSOCKET","https://remote.invalid"))) {
                assertEquals(400,call(server,"/api/v1/advice","POST",cookie,bad).status)
            }
            assertEquals(403,call(server,"/api/v1/advice","POST",cookie,sample,mapOf("X-DropLAN" to "0")).status)
            assertTrue(server.engine.senderTasks().isEmpty())
        }
    }

    @Test fun unknownLengthAdviceDoesNotInventDurationOrCompareDuplicatePaths() {
        val a = ConnectionCandidate("HTTP",true,preparationSeconds=0.0,bytesPerSecond=100.0,measurement=Measurement.CURRENT)
        val b = a.copy(id="WEBSOCKET",bytesPerSecond=200.0)
        val advice = ConnectionAdvisor.recommend(listOf(a,b),null)!!
        assertEquals("WEBSOCKET",advice.candidate.id); assertNull(advice.estimatedSeconds); assertTrue(advice.compared)
        assertFalse(ConnectionAdvisor.recommend(listOf(a,a),100)!!.compared)
        assertNull(ConnectionAdvisor.recommend(listOf(a.copy(reachable=false)),100))
        assertNull(ConnectionAdvisor.recommend(listOf(a.copy(bytesPerSecond=Double.MIN_VALUE)),Long.MAX_VALUE)!!.estimatedSeconds)
    }

    @Test fun totalTimeRecommendationDoesNotInventFastestOrSwitchActiveTransfer() {
        val advice = ConnectionAdvisor.recommend(listOf(
            ConnectionCandidate("lan", true, preparationSeconds = 0.0, bytesPerSecond = 10.0),
            ConnectionCandidate("hotspot", true, preparationSeconds = 30.0, bytesPerSecond = 100.0)), 100)
        assertEquals("lan", advice!!.candidate.id); assertFalse(advice.compared)
        assertFalse(ConnectionAdvisor.canSwitch(1, 1, true)); assertFalse(ConnectionAdvisor.canSwitch(0, 1, false))
    }

    @Test fun realConcurrentDownloadsCannotBypassSlotsAndRevocationClosesStreams() {
        val entered = java.util.concurrent.CountDownLatch(2)
        val resume = java.util.concurrent.CountDownLatch(1)
        val open = java.util.concurrent.atomic.AtomicInteger()
        val closed = java.util.concurrent.atomic.AtomicInteger()
        val source = object : ShareSource by Source(65536) {
            override fun openStream(): InputStream {
                open.incrementAndGet()
                return object : InputStream() {
                    private val ended = java.util.concurrent.atomic.AtomicBoolean()
                    private var remaining = 65536
                    override fun read(): Int { val bytes = ByteArray(1); return if (read(bytes, 0, 1) < 0) -1 else 0 }
                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        entered.countDown()
                        resume.await(5, java.util.concurrent.TimeUnit.SECONDS)
                        if (ended.get()) throw java.io.IOException("cancelled")
                        if (remaining == 0) return -1
                        val n = minOf(len, remaining); java.util.Arrays.fill(b, off, off + n, 0.toByte()); remaining -= n
                        return n
                    }
                    override fun close() { if (ended.compareAndSet(false, true)) closed.incrementAndGet() }
                }
            }
        }
        val clients = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            server(source).use { server ->
                val a = authorize(server); val b = authorize(server); val c = authorize(server)
                val aTask = task(server, a); val bTask = task(server, b); val cTask = task(server, c)
                assertEquals("QUEUED", cTask.getString("state"))
                val first = clients.submit<Reply> { call(server, aTask.getString("url"), cookie = a) }
                val second = clients.submit<Reply> { call(server, bTask.getString("url"), cookie = b) }
                assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
                assertEquals(2, open.get())
                assertEquals(429, call(server, aTask.getString("url"), cookie = a).status)
                assertEquals(429, call(server, cTask.getString("url"), cookie = c).status)
                server.engine.revoke(server.engine.receiver(a.substringAfter('=')).id)
                assertTrue(closed.get() >= 1)
                resume.countDown()
                runCatching { first.get(5, java.util.concurrent.TimeUnit.SECONDS) }
                assertEquals(65536, second.get(5, java.util.concurrent.TimeUnit.SECONDS).data.size)
                assertEquals("READY", call(server, "/api/v1/downloads/" + cTask.getString("id"), cookie = c).json().getString("state"))
            }
        } finally { resume.countDown(); clients.shutdownNow() }
    }

    @Test fun pendingPairingExpiryBodyLimitCsrfAndUnknownStaticHead() {
        var now = 1_000_000L
        val engine = ShareEngine(listOf(Source(1)), now = { now })
        val pair = engine.pair(engine.entryToken)
        assertEquals("PENDING", pair.state)
        try { engine.receiver(null); fail() } catch (error: ProtocolFailure) { assertEquals(401, error.status) }
        now += 121_000
        assertFalse(engine.approve(pair.id)); engine.close()
        DropServer("127.0.0.1", 0, ShareEngine(listOf(Source(1))), StaticAssetProvider {
            StaticAsset("text/plain", null) { ByteArrayInputStream(byteArrayOf(1)) }
        }).use { server ->
            server.start()
            assertNull(call(server, "/", "HEAD").header("Content-Length"))
            assertEquals(413, call(server, "/api/v1/pairings", "POST", body = "a".repeat(4097)).status)
            assertEquals(403, call(server, "/api/v1/pairings", "POST", body = "{}", headers = mapOf("X-DropLAN" to "0")).status)
            java.net.Socket("127.0.0.1", server.port).use { socket ->
                socket.soTimeout = 5000
                socket.getOutputStream().write("GET / HTTP/1.1\r\nHost: attacker.invalid\r\nConnection: close\r\n\r\n".toByteArray())
                assertTrue(socket.getInputStream().bufferedReader().readLine().contains("403"))
            }
        }
    }
}
