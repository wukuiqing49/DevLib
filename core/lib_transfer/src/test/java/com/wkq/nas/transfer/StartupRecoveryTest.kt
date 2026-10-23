package com.wkq.nas.transfer

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class StartupRecoveryTest {
    @Test fun failedFamilyDoesNotPreventOtherQueuesFromRecovering() = runBlocking {
        val visited = mutableListOf<Int>()
        val errors = mutableListOf<Exception>()
        runRecoverySteps(listOf(
            { visited += 1; throw TransferException(TransferError.INVALID_PATH) },
            { visited += 2 }, { visited += 3 }
        )) { errors.add(it) }
        assertEquals(listOf(1, 2, 3), visited)
        assertEquals(1, errors.size)
    }
    @Test fun cancellationStopsRecoveryAndIsNotReportedAsFailure() = runBlocking {
        var later = false
        var reported = false
        try {
            runRecoverySteps(listOf({ throw CancellationException() }, { later = true })) { reported = true }
            fail("Cancellation")
        } catch (_: CancellationException) { }
        assertFalse(later); assertFalse(reported)
    }
}
