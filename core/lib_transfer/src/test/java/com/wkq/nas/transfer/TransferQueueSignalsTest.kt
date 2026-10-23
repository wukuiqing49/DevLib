package com.wkq.nas.transfer

import com.wkq.nas.transfer.work.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class TransferQueueSignalsTest {
    @Test fun runningConsumerCoalescesSignalsAndExitHandshakeCannotLoseLastEnqueue() {
        assertTrue(TransferQueueSignals.signal(false))
        TransferQueueSignals.start(false)
        repeat(100) { assertFalse(TransferQueueSignals.signal(false)) }
        assertTrue(TransferQueueSignals.finish(false))
        assertTrue(TransferQueueSignals.signal(false))
        TransferQueueSignals.start(false); assertFalse(TransferQueueSignals.finish(false))
    }
    @Test fun producerLifetimeWakesIdleConsumerAndOnlyRegistersUsedQueues() = runBlocking {
        val version = TransferQueueSignals.revision(false)
        withTransferProducer {
            currentCoroutineContext()[TransferQueueProducer]!!.register(false)
            currentCoroutineContext()[TransferQueueProducer]!!.register(false)
            assertTrue(TransferQueueSignals.hasProducer(false)); assertFalse(TransferQueueSignals.hasProducer(true))
            withTimeout(500) { TransferQueueSignals.awaitChange(false, version) }
        }
        assertFalse(TransferQueueSignals.hasProducer(false))
        try { withTransferProducer { currentCoroutineContext()[TransferQueueProducer]!!.register(true); throw CancellationException() } }
        catch (_: CancellationException) { }
        assertFalse(TransferQueueSignals.hasProducer(true))
    }
}
