package com.wkq.droplan.drop

import fi.iki.elonen.NanoHTTPD
import java.io.*
import java.security.MessageDigest
import java.util.Base64

/** droplan.v1：客户端每落盘一块才发送 next，有且仅有一块 64KiB 在途。 */
internal object WebSocketTransfer {
    fun accept(headers: Map<String, String>): String {
        fun reject(): Nothing = ShareEngine.fail(400, "invalid_websocket_handshake")
        if (!headers["upgrade"].equals("websocket", true) ||
            headers["connection"]?.split(',')?.none { it.trim().equals("upgrade", true) } != false ||
            headers["sec-websocket-version"] != "13" ||
            headers["sec-websocket-protocol"]?.split(',')?.map { it.trim() }?.contains("droplan.v1") != true) reject()
        val key = headers["sec-websocket-key"] ?: reject()
        val decoded = try { Base64.getDecoder().decode(key) } catch (_: IllegalArgumentException) { reject() }
        if (decoded.size != 16) reject()
        return Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1")
            .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray(Charsets.US_ASCII)))
    }

    class Response(
        private val accept: String, private val peer: InputStream, private val source: InputStream,
        private val length: Long?, private val progress: (Long) -> Unit,
        private val touch: () -> Unit, private val finish: (Boolean) -> Unit,
    ) : NanoHTTPD.Response(NanoHTTPD.Response.Status.SWITCH_PROTOCOL, "application/octet-stream", null, 0) {
        override fun send(outputStream: OutputStream) {
            val out = DataOutputStream(outputStream)
            var success = false
            try {
                out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                    "Sec-WebSocket-Accept: $accept\r\nSec-WebSocket-Protocol: droplan.v1\r\n" +
                    "Cache-Control: no-store\r\n\r\n").toByteArray(Charsets.US_ASCII))
                out.flush(); touch()
                val input = DataInputStream(peer)
                val block = ByteArray(64 * 1024)
                var count = 0L
                var done = false
                while (true) {
                    val first = input.readUnsignedByte()
                    val second = input.readUnsignedByte()
                    // 不接受分片、扩展、未掩码或大控制消息，分配内存前验证。
                    if ((first and 0xf0) != 0x80 || (second and 0x80) == 0 || (second and 0x7f) > 125)
                        throw IOException("invalid_websocket_frame")
                    val opcode = first and 15
                    val mask = ByteArray(4).also(input::readFully)
                    val payload = ByteArray(second and 127).also(input::readFully)
                    payload.indices.forEach { payload[it] = (payload[it].toInt() xor mask[it % 4].toInt()).toByte() }
                    touch()
                    if (opcode == 8) { frame(out, 8, byteArrayOf(3, -24)); break }
                    if (opcode == 9) { frame(out, 10, payload); continue }
                    if (opcode == 10) continue
                    if (opcode != 1) throw IOException("control_text_required")
                    if (done && payload.contentEquals("saved".toByteArray())) {
                        frame(out, 8, byteArrayOf(3, -24)); success = true; break
                    }
                    if (done || !payload.contentEquals("next".toByteArray())) throw IOException("invalid_transfer_command")
                    val remaining = length?.minus(count)
                    val n = if (remaining == 0L) -1 else source.read(block, 0,
                        if (remaining == null) block.size else minOf(remaining, block.size.toLong()).toInt())
                    if (n < 0) {
                        if (remaining != null && remaining > 0) throw EOFException()
                        frame(out, 1, "{\"done\":true,\"bytes\":\"$count\"}".toByteArray())
                        done = true
                    } else {
                        if (n == 0) throw IOException("source_stalled")
                        frame(out, 2, block, n); count += n; progress(count)
                    }
                    touch()
                }
            } catch (_: Exception) {
                runCatching { frame(out, 8, byteArrayOf(3, -13)) } // 1011，不泄露源信息。
            } finally { runCatching { source.close() }; finish(success) }
        }

        private fun frame(out: DataOutputStream, opcode: Int, bytes: ByteArray, length: Int = bytes.size) {
            out.writeByte(0x80 or opcode)
            when {
                length < 126 -> out.writeByte(length)
                length <= 65535 -> { out.writeByte(126); out.writeShort(length) }
                else -> { out.writeByte(127); out.writeLong(length.toLong()) }
            }
            out.write(bytes, 0, length); out.flush()
        }
    }
}
