package com.wkq.iptc.upload.http

import java.net.ServerSocket
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test

/** 用隔离回环服务验证取消会终止真实 OkHttp 调用，不需要设备和外部网络。 */
class UploadHttpCallTest {
    /** 服务端尚未响应时取消协程，底层 Call 必须同步进入取消态。 */
    @Test fun cancellationCancelsTheActualCall() = runBlocking {
        val accepted = CountDownLatch(1)
        val release = CountDownLatch(1)
        val server = ServerSocket().apply { bind(InetSocketAddress("127.0.0.1", 0)) }
        val worker = Thread {
            server.accept().use {
                accepted.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
        }.apply { isDaemon = true; start() }
        val client = OkHttpClient()
        try {
            val call = client.newCall(Request.Builder().url("http://127.0.0.1:${server.localPort}/").build())
            val job = launch(Dispatchers.IO) { call.awaitUploadResponse().close() }
            assertTrue(withContext(Dispatchers.IO) { accepted.await(3, TimeUnit.SECONDS) })
            job.cancelAndJoin()
            assertTrue(call.isCanceled())
        } finally {
            release.countDown()
            server.close()
            worker.join(1500)
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }
}
