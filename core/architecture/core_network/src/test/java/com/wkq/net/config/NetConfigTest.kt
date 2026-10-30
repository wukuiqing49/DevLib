package com.wkq.net.config

import org.junit.Assert.assertThrows
import org.junit.Test

class NetConfigTest {

    @Test
    fun rejectsNegativeTimeoutsDuringConfiguration() {
        assertThrows(IllegalArgumentException::class.java) {
            NetConfig.Builder().setConnectTimeout(-1L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            NetConfig.Builder().setReadTimeout(-1L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            NetConfig.Builder().setWriteTimeout(-1L)
        }
    }

    @Test
    fun rejectsBlankDynamicBaseUrlKey() {
        assertThrows(IllegalArgumentException::class.java) {
            NetConfig.Builder().putBaseUrl("  ", "https://example.com")
        }
    }
}
