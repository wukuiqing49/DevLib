package com.wkq.droplan.drop

import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.net.Socket
import java.security.MessageDigest

class WebSocketTest {
    private class Source : ShareSource {
        override val id = "ws_file_0123456789abcdef"
        override val displayName = "test.bin"
        override val mimeType = "application/octet-stream"
        override val length = 2L * 1024 * 1024 + 13
        override val supportsRandomAccess = false
        var closed = false
        override fun openStream() = object : InputStream() {
            var count = 0L
            override fun read(): Int = if (count == length) -1 else (count++ % 251).toInt()
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (count == length) return -1
                val n = minOf(len.toLong(), length - count).toInt()
                repeat(n) { b[off + it] = (count++ % 251).toByte() }; return n
            }
            override fun close() { closed = true }
        }
        override fun openRange(offset: Long, length: Long): InputStream = error("not supported")
    }
    private class Peer(val socket: Socket) : Closeable {
        val input = DataInputStream(socket.getInputStream())
        val output = DataOutputStream(socket.getOutputStream())
        fun text(text: String) {
            val b = text.toByteArray(); val mask = byteArrayOf(1, 2, 3, 4)
            output.writeByte(0x81); output.writeByte(0x80 or b.size); output.write(mask)
            b.indices.forEach { output.writeByte(b[it].toInt() xor mask[it % 4].toInt()) }; output.flush()
        }
        fun frame(): Pair<Int, ByteArray> {
            val opcode = input.readUnsignedByte() and 15
            val raw = input.readUnsignedByte()
            assertEquals(0, raw and 0x80)
            val length = when (raw) { 126 -> input.readUnsignedShort().toLong(); 127 -> input.readLong(); else -> raw.toLong() }
            assertTrue(length in 0..65536)
            return opcode to ByteArray(length.toInt()).also(input::readFully)
        }
        fun line(): String {
            val b = ByteArrayOutputStream()
            while (true) { val n = input.read(); if (n < 0 || n == 10) break; if (n != 13) b.write(n) }
            return b.toString("US-ASCII")
        }
        override fun close() { socket.close() }
    }
    private fun connect(server: DropServer, task: String, cookie: String?, origin: String = server.baseUrl): Pair<Peer, String> {
        val peer = Peer(Socket("127.0.0.1", server.port).apply { soTimeout = 5000 })
        peer.output.write(("GET /api/v1/ws/files/ws_file_0123456789abcdef?task=$task HTTP/1.1\r\n" +
            "Host: 127.0.0.1:${server.port}\r\nOrigin: $origin\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
            "Sec-WebSocket-Version: 13\r\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Protocol: droplan.v1\r\n" +
            (cookie?.let { "Cookie: droplan=$it\r\n" } ?: "") + "\r\n").toByteArray())
        peer.output.flush()
        val status = peer.line()
        while (peer.line().isNotEmpty()) { }
        return peer to status
    }
    @Test fun actualWebSocketBoundedTransferHashAuthorizationAndSharedQueue() {
        val source = Source()
        val engine = ShareEngine(listOf(source), true)
        DropServer("127.0.0.1", 0, engine, StaticAssetProvider { null }).use { server ->
            server.start()
            val pair = engine.pair(engine.entryToken)
            val cookie = engine.pairing(pair.id, pair.ticket).cookie!!
            val owner = engine.receiver(cookie).id
            val task = engine.createTask(owner, source.id, ShareEngine.randomToken())
            connect(server, task.id, null).let { (peer, status) -> peer.use { assertTrue(status.contains("401")) } }
            connect(server, task.id, cookie, "https://evil.example").let { (peer, status) -> peer.use { assertTrue(status.contains("403")) } }
            connect(server, task.id, cookie).let { (peer, status) -> peer.use {
                assertTrue(status.contains("101"))
                // WebSocket 与 HTTP 使用同一许可，不能重复占用同一个任务。
                try { engine.claim(owner, task.id, source.id) {}; fail() } catch (e: ProtocolFailure) { assertEquals(429, e.status) }
                val digest = MessageDigest.getInstance("SHA-256")
                var total = 0L
                while (true) {
                    peer.text("next")
                    val (opcode, bytes) = peer.frame()
                    if (opcode == 1) { assertTrue(String(bytes).contains("\"bytes\":\"${source.length}\"")); break }
                    assertEquals(2, opcode); total += bytes.size; digest.update(bytes)
                }
                assertEquals(source.length, total)
                val expected = MessageDigest.getInstance("SHA-256")
                var position = 0L
                val buffer = ByteArray(65536)
                while (position < source.length) {
                    val n = minOf(buffer.size.toLong(), source.length - position).toInt()
                    repeat(n) { buffer[it] = ((position + it) % 251).toByte() }; expected.update(buffer, 0, n); position += n
                }
                assertArrayEquals(expected.digest(), digest.digest())
                peer.text("saved"); assertEquals(8, peer.frame().first)
            } }
            // 观察到关闭帧后，服务线程的 finally 才会释放许可。
            val deadline = System.nanoTime() + 1_000_000_000
            while (engine.task(owner, task.id).state == DownloadState.STREAMING && System.nanoTime() < deadline) Thread.yield()
            assertEquals(DownloadState.SENT, engine.task(owner, task.id).state)
            assertTrue(source.closed)
        }
    }
    @Test fun oversizedClientFrameIsRejectedBeforeAllocation() {
        val source = Source(); val engine = ShareEngine(listOf(source), true)
        DropServer("127.0.0.1", 0, engine, StaticAssetProvider { null }).use { server ->
            server.start(); val pair = engine.pair(engine.entryToken); val cookie = engine.pairing(pair.id, pair.ticket).cookie!!
            val task = engine.createTask(engine.receiver(cookie).id, source.id, ShareEngine.randomToken())
            connect(server, task.id, cookie).first.use { peer ->
                peer.output.write(byteArrayOf(0x81.toByte(), 0xff.toByte())); peer.output.flush()
                assertEquals(8, peer.frame().first)
            }
        }
    }
}
