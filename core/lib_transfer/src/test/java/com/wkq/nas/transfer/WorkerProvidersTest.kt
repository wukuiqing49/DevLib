package com.wkq.nas.transfer

import com.wkq.nas.transfer.data.ConnectionEntity
import com.wkq.nas.transfer.provider.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class WorkerProvidersTest {
    private fun connection(id: String = "a", root: String = "/test") = ConnectionEntity(id, "LOCAL", root, "", "", "")
    private fun provider(closes: AtomicInteger) = object : StorageProvider {
        override fun stat(path: String): FileMetadata? = null
        override fun list(path: String) = emptyList<FileMetadata>()
        override fun read(path: String) = error("unused")
        override fun mkdirs(path: String) = Unit
        override fun write(path: String, input: java.io.InputStream, size: Long, progress: (Long) -> Unit) = Unit
        override fun moveNew(source: String, target: String, check: () -> Unit) = Unit
        override fun delete(path: String) = Unit
        override fun close() { closes.incrementAndGet() }
    }
    @Test fun reusesOneSessionAndClosesAtScopeEndIncludingNullableResults() = runBlocking {
        val opens = AtomicInteger(); val closes = AtomicInteger()
        val open = { _: ConnectionEntity -> opens.incrementAndGet(); provider(closes) }
        withWorkerProviders(open) { repeat(100) { assertNull(usingProvider(connection(), open) { it.stat("") }) } }
        assertEquals(1, opens.get()); assertEquals(1, closes.get())
    }
    @Test fun invalidatesChangedIdentityAndFailures() = runBlocking {
        val opens = AtomicInteger(); val closes = AtomicInteger()
        val pool = WorkerProviders({ opens.incrementAndGet(); provider(closes) })
        pool.scope {
            pool.use(connection()) { }
            pool.use(connection(root = "/changed")) { }
            try { pool.use(connection(root = "/changed")) { throw java.io.IOException() }; fail() } catch (_: java.io.IOException) { }
            pool.use(connection(root = "/changed")) { }
        }
        assertEquals(3, opens.get()); assertEquals(3, closes.get())
    }
    @Test fun sameEndpointPairDoesNotLockItselfAndBusyCapacityFallsBack() = runBlocking {
        val opens = AtomicInteger(); val closes = AtomicInteger()
        val open = { _: ConnectionEntity -> opens.incrementAndGet(); provider(closes) }
        withTimeout(5000) {
            WorkerProviders(open, capacity = 1).scope {
                usingProviderPair(connection(), connection(), open) { a, b -> assertSame(a, b) }
                usingProviderPair(connection(), connection("b"), open) { a, b -> assertNotSame(a, b) }
            }
        }
        assertEquals(2, opens.get()); assertEquals(2, closes.get())
    }
    @Test fun cancelReleasesSessionAndSerializesConcurrentUseOfSameEndpoint() = runBlocking {
        val closes = AtomicInteger(); val active = AtomicInteger(); val maximum = AtomicInteger()
        val pool = WorkerProviders({ provider(closes) })
        pool.scope {
            coroutineScope { repeat(8) { launch(Dispatchers.Default) { pool.use(connection()) {
                maximum.updateAndGet { maxOf(it, active.incrementAndGet()) }; delay(5); active.decrementAndGet()
            } } } }
            try { pool.use(connection()) { throw CancellationException() } } catch (_: CancellationException) { }
        }
        assertEquals(1, maximum.get()); assertEquals(1, closes.get())
    }
}
