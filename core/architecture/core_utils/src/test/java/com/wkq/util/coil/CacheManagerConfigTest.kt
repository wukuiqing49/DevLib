package com.wkq.util.coil

import org.junit.Assert.fail
import org.junit.Test

class CacheManagerConfigTest {

    @Test
    fun validateConfiguration_acceptsValidValues() {
        CacheManager.validateConfiguration(20, 0.25, 200, 200)
    }

    @Test
    fun validateConfiguration_rejectsOutOfRangeValues() {
        assertValidationFails { CacheManager.validateConfiguration(0, 0.25, 200, 200) }
        assertValidationFails { CacheManager.validateConfiguration(20, 0.0, 200, 200) }
        assertValidationFails { CacheManager.validateConfiguration(20, 1.1, 200, 200) }
        assertValidationFails { CacheManager.validateConfiguration(20, 0.25, 0, 200) }
        assertValidationFails { CacheManager.validateConfiguration(20, 0.25, 200, 0) }
    }

    private fun assertValidationFails(block: () -> Unit) {
        try {
            block()
            fail("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }
}
