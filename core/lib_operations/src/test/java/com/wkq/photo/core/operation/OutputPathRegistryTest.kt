package com.wkq.photo.core.operation

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class OutputPathRegistryTest {
    @Test
    fun rejectsConcurrentLeaseAndAllowsReuseAfterClose() {
        val output = File("build/test-output/shared.jpg")
        val first = OutputPathRegistry.acquire(output)
        try {
            try {
                OutputPathRegistry.acquire(output)
                fail("Expected an output conflict")
            } catch (error: OperationException) {
                assertEquals(OperationErrorCode.OUTPUT_CONFLICT, error.code)
            }
        } finally {
            first.close()
        }

        OutputPathRegistry.acquire(output).close()
    }
}
