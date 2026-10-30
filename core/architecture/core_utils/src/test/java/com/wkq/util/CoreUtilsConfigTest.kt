package com.wkq.util

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CoreUtilsConfigTest {

    @Test
    fun validate_acceptsDefaultConfiguration() {
        CoreUtilsConfig().validate()
    }

    @Test
    fun validate_rejectsInvalidLogConfiguration() {
        assertValidationFails("logFilePrefix") {
            CoreUtilsConfig(logFilePrefix = " ").validate()
        }
        assertValidationFails("logMaxFileSize") {
            CoreUtilsConfig(logMaxFileSize = 0L).validate()
        }
        assertValidationFails("logCacheDays") {
            CoreUtilsConfig(logCacheDays = -1).validate()
        }
    }

    private fun assertValidationFails(expectedMessagePart: String, block: () -> Unit) {
        try {
            block()
            fail("Expected IllegalArgumentException")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message.orEmpty().contains(expectedMessagePart))
        }
    }
}
