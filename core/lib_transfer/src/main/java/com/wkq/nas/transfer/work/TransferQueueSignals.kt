package com.wkq.nas.transfer.work

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** 仅合并正在运行的消费者唤醒；Worker退出后恢复持久调度，绝不以KEEP代替退出握手。 */
internal object TransferQueueSignals {
    private class State { var running = false; var dirty = false; var producers = 0; val revision = MutableStateFlow(0L) }
    private val states = arrayOf(State(), State())
    private fun state(network: Boolean) = states[if (network) 1 else 0]
    @Synchronized fun start(network: Boolean) { state(network).apply { running = true; dirty = false; revision.value++ } }
    @Synchronized fun finish(network: Boolean): Boolean = state(network).let { it.running = false; it.dirty }
    @Synchronized fun signal(network: Boolean): Boolean = state(network).let {
        it.revision.value++; if (it.running) { it.dirty = true; false } else true
    }
    @Synchronized fun producer(network: Boolean, delta: Int) { state(network).apply { producers += delta; check(producers >= 0); revision.value++ } }
    @Synchronized fun hasProducer(network: Boolean) = state(network).producers > 0
    fun revision(network: Boolean) = state(network).revision.value
    suspend fun awaitChange(network: Boolean, revision: Long) { withTimeoutOrNull(1000) { state(network).revision.first { it != revision } } }
}

/** 每个高级作业只登记实际用到的队列，结束即唤醒空闲消费者释放锁与会话。 */
internal class TransferQueueProducer : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<TransferQueueProducer>
    private val networks = mutableSetOf<Boolean>()
    @Synchronized fun register(network: Boolean) { if (networks.add(network)) TransferQueueSignals.producer(network, 1) }
    @Synchronized fun close() { networks.forEach { TransferQueueSignals.producer(it, -1) }; networks.clear() }
}
internal suspend fun <T> withTransferProducer(block: suspend () -> T): T {
    val producer = TransferQueueProducer()
    return try { withContext(producer) { block() } } finally { producer.close() }
}
