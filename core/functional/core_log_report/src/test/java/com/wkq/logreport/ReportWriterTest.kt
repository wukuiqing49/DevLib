package com.wkq.logreport

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** 在IO被阻塞时验证生产者和整理控制不会跟随等待。 */
class ReportWriterTest {
    /** 写盘堵塞且队列满时生产者及时返回，封存仍按顺序执行。 */
    @Test fun fullQueueDoesNotBlockProducerAndSealFollowsAcceptedEvents() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val done = CountDownLatch(1)
        val producer = CountDownLatch(1)
        val order = java.util.Collections.synchronizedList(mutableListOf<String>())
        ReportWriter(1).use { writer ->
            assertTrue(writer.offer({ entered.countDown(); release.await(5, TimeUnit.SECONDS); order.add("blocked") }, { throw AssertionError(it) }))
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            assertTrue(writer.offer({ order.add("accepted") }, {}))
            Thread { assertFalse(writer.offer({ error("must reject") }, {})); writer.control({ order.add("seal"); done.countDown() }); producer.countDown() }.start()
            assertTrue("producer waited for IO", producer.await(1, TimeUnit.SECONDS))
            assertEquals(1L, done.count)
            release.countDown()
            assertTrue(done.await(5, TimeUnit.SECONDS))
            assertEquals(listOf("blocked", "accepted", "seal"), order.toList())
        }
    }
    /** 整理失败通知调用方，后续控制仍能完成。 */
    @Test fun controlFailureIsReportedAndNextControlStillRuns() {
        val failed = CountDownLatch(1)
        val next = CountDownLatch(1)
        ReportWriter(1).use { writer ->
            writer.control({ throw java.io.IOException("disk full") }, { failed.countDown() })
            writer.control({ next.countDown() })
            assertTrue(failed.await(3, TimeUnit.SECONDS))
            assertTrue(next.await(3, TimeUnit.SECONDS))
        }
    }
}
