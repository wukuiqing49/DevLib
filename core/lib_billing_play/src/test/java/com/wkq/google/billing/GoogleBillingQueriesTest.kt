package com.wkq.google.billing

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.yield
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class GoogleBillingQueriesTest {
    @Test fun hungSubscriptionCannotDiscardAvailableLifetime() = runTest {
        val subscription = async { boundedBillingQuery<List<String>>("subs") { awaitCancellation() } }
        val lifetime = async { boundedBillingQuery("inapp") { delay(50); Result.success(listOf("pro_lifetime")) } }
        val subs = subscription.await()
        val owned = lifetime.await()
        assertEquals(-3, (subs.exceptionOrNull() as GoogleBillingException).issue.code)
        assertTrue(combinePlayEntitlement(subs.getOrDefault(emptyList()), owned.getOrThrow(), "timeout").hasLifetimeUnlock)
    }
    @Test fun unexpectedSdkErrorDoesNotCancelOtherProductType() = runTest {
        val first = async { boundedBillingQuery<String>("subs") { throw IllegalStateException("sdk") } }
        val second = async { boundedBillingQuery("inapp") { delay(10); Result.success("available") } }
        assertTrue(first.await().isFailure)
        assertEquals("available", second.await().getOrThrow())
    }
    @Test fun parentCancellationStillPropagates() = runTest {
        var entered = false
        val request = async { boundedBillingQuery<String>("subs") { entered = true; awaitCancellation() } }
        yield()
        assertTrue(entered)
        request.cancel()
        request.join()
        assertTrue(request.isCancelled)
    }
}
