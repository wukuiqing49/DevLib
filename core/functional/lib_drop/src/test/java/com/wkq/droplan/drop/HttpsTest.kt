package com.wkq.droplan.drop

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.net.URL
import java.nio.file.Files
import java.security.KeyStore
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

class HttpsTest {
    @Test fun tlsCertificateTrustPairingRangeAndSecureCookie() {
        val directory = Files.createTempDirectory("droplan-tls-test")
        val file = directory.resolve("test-only.p12").toFile()
        try {
            // 仅测试身份，临时生成并删除，不与产品共享私钥或信任配置。
            val keytool = java.io.File(System.getProperty("java.home"), "bin/keytool.exe").let {
                if (it.exists()) it else java.io.File(System.getProperty("java.home"), "bin/keytool")
            }
            val process = ProcessBuilder(keytool.path, "-genkeypair", "-alias", "test-only", "-keyalg", "RSA",
                "-keysize", "2048", "-validity", "2", "-dname", "CN=DropLAN test only",
                "-ext", "SAN=ip:127.0.0.1", "-storetype", "PKCS12", "-keystore", file.path,
                "-storepass", "test-only-password", "-noprompt").redirectErrorStream(true).start()
            process.inputStream.use { it.copyTo(java.io.OutputStream.nullOutputStream()) }
            assertEquals(0, process.waitFor())
            val password = "test-only-password".toCharArray()
            val tls = TlsIdentity.load(file.inputStream(), password, "127.0.0.1")
            assertTrue(password.all { it == '\u0000' })
            val identity = KeyStore.getInstance("PKCS12").apply {
                file.inputStream().use { load(it, "test-only-password".toCharArray()) }
            }
            val trust = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null, null); setCertificateEntry("test-only", identity.getCertificate("test-only"))
            }
            val managers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(trust) }
            val client = SSLContext.getInstance("TLS").apply { init(null, managers.trustManagers, null) }
            val source = object : ShareSource {
                override val id = "tls_file_0123456789abcdef"
                override val displayName = "test.bin"
                override val mimeType = "application/octet-stream"
                override val length = 5L * 1024 * 1024 * 1024
                override val supportsRandomAccess = true
                override fun openStream() = object : java.io.InputStream() {
                    var position = 0L
                    override fun read(): Int = if (position == length) -1 else (position++ % 251).toInt()
                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        if (position == length) return -1
                        val n = minOf(len.toLong(), length - position).toInt()
                        repeat(n) { b[off + it] = (position++ % 251).toByte() }; return n
                    }
                }
                override fun openRange(offset: Long, length: Long) = ByteArrayInputStream(ByteArray(length.toInt()) { ((offset + it) % 251).toByte() })
            }
            DropServer("127.0.0.1", 0, ShareEngine(listOf(source), true), StaticAssetProvider { null }, tls).use { server ->
                server.start()
                fun request(path: String, body: JSONObject? = null, cookie: String? = null, range: String? = null): Pair<HttpsURLConnection, ByteArray> {
                    val c = URL(server.baseUrl + path).openConnection() as HttpsURLConnection
                    c.sslSocketFactory = client.socketFactory // 仅信任此测试证书，保留默认 hostname verifier。
                    c.connectTimeout = 5000; c.readTimeout = 5000
                    cookie?.let { c.setRequestProperty("Cookie", it) }
                    range?.let { c.setRequestProperty("Range", it) }
                    if (body != null) {
                        c.requestMethod = "POST"; c.doOutput = true
                        c.setRequestProperty("Content-Type", "application/json"); c.setRequestProperty("X-DropLAN", "1")
                        c.setRequestProperty("Idempotency-Key", ShareEngine.randomToken())
                        c.outputStream.use { it.write(body.toString().toByteArray()) }
                    }
                    val bytes = c.inputStream.use { it.readBytes() }
                    c.disconnect()
                    return c to bytes
                }
                // 默认系统信任必须拒绝测试自签名证书，不能关闭证书校验。
                val untrusted = URL(server.baseUrl + "/").openConnection() as HttpsURLConnection
                untrusted.connectTimeout = 5000; untrusted.readTimeout = 5000
                try { untrusted.inputStream.close(); fail("Untrusted certificate accepted") }
                catch (_: javax.net.ssl.SSLHandshakeException) { } finally { untrusted.disconnect() }
                val pair = JSONObject(String(request("/api/v1/pairings", JSONObject().put("token", server.entryUrl.substringAfter('#'))).second))
                val approval = URL(server.baseUrl + "/api/v1/pairings/" + pair.getString("id")).openConnection() as HttpsURLConnection
                approval.sslSocketFactory = client.socketFactory
                approval.setRequestProperty("X-Pairing-Ticket", pair.getString("ticket"))
                approval.inputStream.use { it.readBytes() }
                val header = approval.getHeaderField("Set-Cookie")
                assertTrue(header.contains("; Secure")); approval.disconnect()
                val cookie = header.substringBefore(';')
                assertEquals("https", JSONObject(String(request("/api/v1/session", cookie = cookie).second)).getString("protocol"))
                val task = JSONObject(String(request("/api/v1/downloads", JSONObject().put("fileId", source.id), cookie).second))
                val offset = 4L * 1024 * 1024 * 1024 + 17
                val response = request("/api/v1/files/${source.id}?task=${task.getString("id")}", cookie = cookie, range = "bytes=$offset-${offset + 65535}")
                assertEquals(206, response.first.responseCode)
                assertEquals(65536, response.second.size)
                assertArrayEquals(ByteArray(65536) { ((offset + it) % 251).toByte() }, response.second)
                val wsTask = JSONObject(String(request("/api/v1/downloads", JSONObject().put("fileId", source.id), cookie).second))
                (client.socketFactory.createSocket("127.0.0.1", server.port) as javax.net.ssl.SSLSocket).use { socket ->
                    socket.soTimeout = 5000
                    socket.sslParameters = socket.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
                    val out = java.io.DataOutputStream(socket.outputStream)
                    val input = java.io.DataInputStream(socket.inputStream)
                    out.write(("GET /api/v1/ws/files/${source.id}?task=${wsTask.getString("id")} HTTP/1.1\r\n" +
                        "Host: 127.0.0.1:${server.port}\r\nOrigin: ${server.baseUrl}\r\nCookie: $cookie\r\n" +
                        "Upgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Version: 13\r\n" +
                        "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Protocol: droplan.v1\r\n\r\n").toByteArray())
                    out.flush()
                    fun line(): String {
                        val text = StringBuilder()
                        while (true) { val c = input.read(); if (c < 0 || c == 10) break; if (c != 13) text.append(c.toChar()) }
                        return text.toString()
                    }
                    assertTrue(line().contains("101")); while (line().isNotEmpty()) { }
                    out.write(byteArrayOf(0x81.toByte(), 0x84.toByte(), 0, 0, 0, 0)); out.writeBytes("next"); out.flush()
                    assertEquals(0x82, input.readUnsignedByte()); assertEquals(127, input.readUnsignedByte())
                    assertEquals(65536, input.readLong())
                    val bytes = ByteArray(65536); input.readFully(bytes)
                    assertArrayEquals(ByteArray(65536) { (it % 251).toByte() }, bytes)
                }
            }
            val wrongIpPassword = "test-only-password".toCharArray()
            try { TlsIdentity.load(file.inputStream(), wrongIpPassword, "192.168.1.2"); fail("SAN mismatch accepted") }
            catch (_: IllegalArgumentException) { }
            assertTrue(wrongIpPassword.all { it == '\u0000' })
        } finally { file.delete(); Files.deleteIfExists(directory) }
    }

    @Test fun oversizedIdentityIsRejectedAndPasswordCleared() {
        val password = "secret".toCharArray()
        try { TlsIdentity.load(ByteArrayInputStream(ByteArray(TlsIdentity.MAX_BYTES + 1)), password, "127.0.0.1"); fail() }
        catch (_: IllegalArgumentException) { }
        assertTrue(password.all { it == '\u0000' })
    }
}
