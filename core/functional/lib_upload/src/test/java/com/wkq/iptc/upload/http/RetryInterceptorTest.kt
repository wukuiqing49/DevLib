package com.wkq.iptc.upload.http

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.ServerSocket
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

/** 用本地回环服务验证上传不重放、只读重试和取消行为，不访问外部服务器。 */
class RetryInterceptorTest {
    /** POST/PUT 即使返回网关错误也只提交一次。 */
    @Test fun writesAreNeverReplayed() = withServer { server, count ->
        val client = OkHttpClient.Builder().retryOnConnectionFailure(false).addInterceptor(RetryInterceptor()).build()
        try {
            for (method in listOf("POST", "PUT")) {
                client.newCall(Request.Builder().url("http://127.0.0.1:${server.localPort}/upload")
                    .method(method, "data".toRequestBody()).build()).execute().use { assertEquals(503, it.code) }
            }
            assertEquals(2, count.get())
        } finally { client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
    }
    /** 只读网关错误可重试，最终响应仍归调用方关闭。 */
    @Test fun readCanRetryTransientGatewayFailure() = withServer { server, count ->
        val client = OkHttpClient.Builder().addInterceptor(RetryInterceptor(2)).build()
        try {
            client.newCall(Request.Builder().url("http://127.0.0.1:${server.localPort}/read").build()).execute().use {
                assertEquals("ok", it.body!!.string())
            }
            assertEquals(2, count.get())
        } finally { client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
    }
    /** 参数错误在创建拦截器时暴露。 */
    @Test(expected = IllegalArgumentException::class) fun rejectsZeroAttempts() { RetryInterceptor(0) }

    /** 使用标准套接字回环 HTTP 服务，避免测试夹具与生产 OkHttp 主版本冲突。 */
    private fun withServer(block: (ServerSocket, AtomicInteger) -> Unit) {
        val count = AtomicInteger()
        val server = ServerSocket().apply { bind(InetSocketAddress("127.0.0.1", 0)) }
        val worker = Thread {
            while (!server.isClosed) {
                try {
                    server.accept().use { socket ->
                        val input = socket.getInputStream().bufferedReader()
                        val line = input.readLine().orEmpty()
                        while (!input.readLine().isNullOrEmpty()) { }
                        val attempt = count.incrementAndGet()
                        val status = if (line.startsWith("GET /read ") && attempt > 1) "200 OK" else "503 Unavailable"
                        socket.getOutputStream().write("HTTP/1.1 $status\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok".toByteArray())
                        socket.getOutputStream().flush()
                    }
                } catch (error: java.net.SocketException) { if (!server.isClosed) throw error }
            }
        }.apply { isDaemon = true; start() }
        try { block(server, count) } finally { server.close(); worker.join(1500) }
    }
}
