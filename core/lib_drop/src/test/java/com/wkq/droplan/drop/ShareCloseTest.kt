package com.wkq.droplan.drop

import org.junit.Assert.*
import org.junit.Test

/** 验证流关闭失败不会中断其他连接和授权的清理。 */
class ShareCloseTest {
    /** 引擎取消回调失败时服务端仍释放监听端口。 */
    @Test fun serverStopsListeningWhenEngineCleanupFails() {
        val source = object : ShareSource {
            override val id = "fixture_file_0123456789"
            override val displayName = "test"
            override val mimeType = "application/octet-stream"
            override val length = 1L
            override val supportsRandomAccess = false
            /** 返回隔离测试流。 */
            override fun openStream() = byteArrayOf(1).inputStream()
            /** 测试不提供随机读取。 */
            override fun openRange(offset: Long, length: Long): java.io.InputStream = throw UnsupportedOperationException()
        }
        val engine = ShareEngine(listOf(source))
        val task = engine.createTask("owner", source.id, "key_first_0123456789")
        engine.claim("owner", task.id, source.id) { throw java.io.IOException("cleanup") }
        val server = DropServer("127.0.0.1", 0, engine, StaticAssetProvider { null })
        server.start()
        val port = java.net.URI(server.baseUrl).port
        try {
            assertTrue(runCatching { server.close() }.isFailure)
            assertTrue(runCatching {
                java.net.Socket().use { it.connect(java.net.InetSocketAddress("127.0.0.1", port), 1000) }
            }.isFailure)
        } finally { server.close() }
    }
    /** 一个下载的关闭回调抛错，其他下载仍应取消，重复关闭也安全。 */
    @Test fun closeCleansAllDownloadsBeforeRethrowing() {
        val source = object : ShareSource {
            override val id = "fixture_file_0123456789"
            override val displayName = "test"
            override val mimeType = "application/octet-stream"
            override val length = 1L
            override val supportsRandomAccess = false
            /** 返回隔离的一字节流。 */
            override fun openStream() = byteArrayOf(1).inputStream()
            /** 本夹具不提供随机访问。 */
            override fun openRange(offset: Long, length: Long): java.io.InputStream = throw UnsupportedOperationException()
        }
        val engine = ShareEngine(listOf(source))
        val first = engine.createTask("first", source.id, "key_first_0123456789")
        val second = engine.createTask("second", source.id, "key_second_0123456789")
        val failure = java.io.IOException("close failed")
        var secondClosed = false
        engine.claim("first", first.id, source.id) { throw failure }
        engine.claim("second", second.id, source.id) { secondClosed = true }
        assertSame(failure, runCatching { engine.close() }.exceptionOrNull())
        assertTrue(secondClosed)
        assertTrue(engine.senderTasks().isEmpty())
        assertTrue(engine.pendingPairings().isEmpty())
        engine.close()
    }
}
