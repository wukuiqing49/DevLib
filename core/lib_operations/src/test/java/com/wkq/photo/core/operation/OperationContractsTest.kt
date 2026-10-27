package com.wkq.photo.core.operation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OperationContractsTest {

    /** 协程取消应保持异常身份，不被转换为 INTERNAL_ERROR。 */
    @Test fun propagatesCoroutineCancellation() {
        val cancelled = java.util.concurrent.CancellationException("cancel")
        val result = runCatching { OperationRunner.run<Unit> { throw cancelled } }
        org.junit.Assert.assertSame(cancelled, result.exceptionOrNull())
    }
    /** 中断映射为稳定取消代码，线程标记保持。 */
    @Test fun threadInterruptionIsCancellation() {
        try {
            Thread.currentThread().interrupt()
            val result = OperationRunner.run<Unit> { error("must not execute") }
            assertEquals(OperationErrorCode.CANCELLED, (result as OperationResult.Failure).code)
            assertTrue(Thread.currentThread().isInterrupted)
        } finally { Thread.interrupted() }
    }
    /** 溢出后的负页数不允许绕过预算。 */
    @Test fun rejectsNegativePageCount() {
        val result = OperationRunner.run<Unit> { context ->
            context.checkPages(-1)
            OperationResult.Success(Unit, OperationStatistics(0))
        }
        assertEquals(OperationErrorCode.BUDGET_EXCEEDED, (result as OperationResult.Failure).code)
    }


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
