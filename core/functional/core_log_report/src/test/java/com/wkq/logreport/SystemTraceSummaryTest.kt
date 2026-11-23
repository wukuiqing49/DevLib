package com.wkq.logreport

import org.junit.Assert.*
import org.junit.Test

/** 覆盖系统栈导出时头信息和原生路径的隐私边界。 */
class SystemTraceSummaryTest {
    @Test fun keepsFramesAndDropsHeaders() {
        val result = SystemTraceSummary.frames("Cmd line: private-value\nprivate-thread tid=1\n  at app.Main.run(Main.kt:42)\n native: #00 pc 123 /data/private/lib.so")
        assertTrue(result.contains("Main.kt:42"))
        assertFalse(result.contains("private-value"))
        assertFalse(result.contains("private-thread"))
        assertFalse(result.contains("/data/private"))
    }
    @Test fun capsStackAndAcceptsAbsentTrace() {
        assertEquals(256, SystemTraceSummary.frames((1..500).joinToString("\n") { "at app.Main.run(Main.kt:$it)" }).lines().size)
        assertEquals("", SystemTraceSummary.frames("Cmd line: unavailable"))
    }
}
