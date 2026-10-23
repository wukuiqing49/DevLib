package com.wkq.photo.core.operation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OperationContractsTest {

    @Test
    fun mapsCancellationToStableErrorCode() {
        val result = OperationRunner.run<Unit>(
            cancellation = OperationCancellation { true }
        ) { error("must not run") }

        assertTrue(result is OperationResult.Failure)
        assertEquals(OperationErrorCode.CANCELLED, (result as OperationResult.Failure).code)
    }

    @Test
    fun rejectsDimensionsAndTotalPixelsBeyondBudget() {
        val result = OperationRunner.run<Unit>(
            budget = OperationBudget(
                maxWidth = 100,
                maxHeight = 100,
                maxTotalPixels = 10_000,
                maxPages = 1,
                timeoutMillis = 1_000
            )
        ) { context ->
            context.checkDimensions(101, 100)
            OperationResult.Success(Unit, OperationStatistics(0))
        }

        assertEquals(OperationErrorCode.BUDGET_EXCEEDED, (result as OperationResult.Failure).code)
    }

    @Test
    fun validatesEncodeOptions() {
        val options = ImageEncodeOptions(
            format = ImageOutputFormat.PNG,
            quality = 100,
            metadataPolicy = MetadataPolicy.STRIP_ALL
        )

        assertEquals(ImageOutputFormat.PNG, options.format)
        assertEquals(MetadataPolicy.STRIP_ALL, options.metadataPolicy)
    }
}
