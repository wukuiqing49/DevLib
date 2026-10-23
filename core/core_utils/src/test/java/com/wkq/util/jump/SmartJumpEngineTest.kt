package com.wkq.util.jump

import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SmartJumpEngineTest {

    @Test
    fun registerAndReadHandlers_concurrentlyKeepsAConsistentSnapshot() {
        val executor = Executors.newFixedThreadPool(2)
        val start = CountDownLatch(1)
        val done = CountDownLatch(2)

        try {
            executor.execute {
                start.await()
                repeat(100) { SmartJumpEngine.registerHandler(TestHandler()) }
                done.countDown()
            }
            executor.execute {
                start.await()
                repeat(100) { SmartJumpEngine.getHandlers() }
                done.countDown()
            }

            start.countDown()
            assertTrue(done.await(5, TimeUnit.SECONDS))
        } finally {
            executor.shutdownNow()
            SmartJumpEngine.unregisterHandler(TestHandler::class.java)
        }
    }

    private class TestHandler : JumpHandler {
        override fun canHandle(url: String): Boolean = false

        override fun convertToScheme(url: String): String? = null
    }
}
