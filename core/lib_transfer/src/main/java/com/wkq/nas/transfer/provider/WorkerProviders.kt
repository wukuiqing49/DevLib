package com.wkq.nas.transfer.provider

import com.wkq.nas.transfer.data.ConnectionEntity
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** 会话仅存活于一次 Worker；同一连接串行使用，凭据/根目录/信任变化后重建。 */
internal class WorkerProviders(private val open: (ConnectionEntity) -> StorageProvider,
    private val capacity: Int = 4) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<WorkerProviders>
    private class Slot {
        val lock = Mutex()
        var users = 0
        var connection: ConnectionEntity? = null
        var provider: StorageProvider? = null
        fun clear() { provider?.let { runCatching { it.close() } }; provider = null; connection = null }
    }
    private val guard = Mutex()
    private val slots = linkedMapOf<String, Slot>()
    suspend fun <T> use(connection: ConnectionEntity, action: suspend (StorageProvider) -> T): T {
        val slot = guard.withLock {
            var entry = slots[connection.id]
            if (entry == null) {
                if (slots.size >= capacity) slots.entries.firstOrNull { it.value.users == 0 }?.let {
                    it.value.clear(); slots.remove(it.key)
                }
                if (slots.size < capacity) entry = Slot().also { slots[connection.id] = it }
            }
            entry?.also { it.users++ }
        }
        if (slot == null) return open(connection).use { action(it) }
        try {
            return slot.lock.withLock {
                try {
                    if (slot.connection != connection || slot.provider == null) {
                        slot.clear(); slot.provider = open(connection); slot.connection = connection
                    }
                    action(checkNotNull(slot.provider))
                } catch (error: Throwable) { slot.clear(); throw error }
            }
        } finally { withContext(NonCancellable) { guard.withLock { slot.users-- } } }
    }
    suspend fun <T> scope(action: suspend () -> T): T = try { withContext(this) { action() } }
        finally { slots.values.forEach { it.clear() }; slots.clear() }
}

internal suspend fun <T> withWorkerProviders(open: (ConnectionEntity) -> StorageProvider,
    action: suspend () -> T): T = withContext(Dispatchers.IO) { WorkerProviders(open).scope(action) }

internal suspend fun <T> usingProvider(connection: ConnectionEntity, open: (ConnectionEntity) -> StorageProvider,
    action: suspend (StorageProvider) -> T): T {
    val pool = currentCoroutineContext()[WorkerProviders]
    return if (pool != null) pool.use(connection, action) else open(connection).use { action(it) }
}

internal suspend fun <T> usingProviderPair(source: ConnectionEntity, target: ConnectionEntity,
    open: (ConnectionEntity) -> StorageProvider, action: suspend (StorageProvider, StorageProvider) -> T): T =
    usingProvider(source, open) { from ->
        if (source == target) action(from, from) else usingProvider(target, open) { to -> action(from, to) }
    }
