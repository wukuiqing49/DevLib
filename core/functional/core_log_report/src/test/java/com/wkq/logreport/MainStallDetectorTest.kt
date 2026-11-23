package com.wkq.logreport

import org.junit.Assert.*
import org.junit.Test

/** 观测心跳只产生一次卡顿与一次恢复，后台旧回调不能干扰新会话。 */
class MainStallDetectorTest {
    /** 同次阻塞分阶段有限采样，恢复后计算持续时间。 */
    @Test fun capturesStallAndRecoveryDuration() {
        val detector = MainStallDetector(3000)
        val beat = checkNotNull(detector.begin(100))
        assertNull(detector.poll(3099))
        assertEquals(3000L, detector.poll(3100)?.durationMs)
        assertEquals(2, detector.poll(5100)?.sample)
        assertEquals(3, detector.poll(10100)?.sample)
        assertNull(detector.poll(30100))
        val recovery = checkNotNull(detector.acknowledge(beat, 31100))
        assertTrue(recovery.recovered)
        assertEquals(31000L, recovery.durationMs)
    }
    /** 后台重置后丢弃旧心跳，不影响下一轮卡顿判定。 */
    @Test fun backgroundResetRejectsOldHeartbeat() {
        val detector = MainStallDetector()
        val old = checkNotNull(detector.begin(0))
        detector.reset()
        val next = checkNotNull(detector.begin(1000))
        assertNull(detector.acknowledge(old, 5000))
        assertEquals(next, detector.poll(5000)?.beat)
    }
}
