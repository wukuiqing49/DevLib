package com.wkq.logreport

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** 验证主动现场有界、封存后的迟到采样被拒绝及线程名隐私。 */
class StallEvidenceTest {
    /** 多次卡顿只保留四份原子现场，结束后不再修改报告。 */
    @Test fun boundedSnapshotsAreDurableAndClosedReportCannotChange() {
        val folder = Files.createTempDirectory("stall-evidence").toFile()
        try {
            val evidence = StallEvidence(folder)
            repeat(8) { assertTrue(evidence.record(StallObservation(it.toLong(), 3000, false), "main=true state=BLOCKED")) }
            assertEquals(4, folder.listFiles()!!.size)
            assertTrue(folder.listFiles()!!.all { it.readText().contains("APP_OBSERVED_NOT_SYSTEM_ANR") })
            val contents = folder.listFiles()!!.associate { it.name to it.readText() }
            evidence.close()
            assertFalse(evidence.record(StallObservation(99, 6000, false), "late"))
            assertEquals(contents, folder.listFiles()!!.associate { it.name to it.readText() })
        } finally { folder.deleteRecursively() }
    }

    /** 保存被阻塞线程状态和主线程位置，线程任意名称不进入附件。 */
    @Test fun capturesBlockedThreadWithoutArbitraryThreadName() {
        val lock = Any()
        val entered = CountDownLatch(1)
        val worker = Thread({ entered.countDown(); synchronized(lock) { } }, "private-user-thread-name")
        try {
            synchronized(lock) {
                worker.start()
                assertTrue(entered.await(3, TimeUnit.SECONDS))
                repeat(50) { if (worker.state != Thread.State.BLOCKED) Thread.sleep(10) }
                val text = ThreadSnapshot.capture(Thread.currentThread())
                assertTrue(text.contains("main=true"))
                assertTrue(text.contains("state=BLOCKED"))
                assertFalse(text.contains("private-user-thread-name"))
            }
        } finally { worker.join(3000) }
    }
}
