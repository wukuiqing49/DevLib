package com.wkq.google.billing

import org.junit.Assert.*
import org.junit.Test

class GoogleBillingEntitlementTest {
    @Test fun lifetimeSurvivesExpiredSubscription() {
        val result = combinePlayEntitlement(emptyList(), listOf("pro_lifetime"), null)
        assertTrue(result.isPro)
        assertTrue(result.hasLifetimeUnlock)
        assertFalse(result.hasSubscription)
    }
    @Test fun validLifetimeSurvivesSubscriptionQueryFailure() {
        assertTrue(combinePlayEntitlement(emptyList(), listOf("pro_lifetime"), "unavailable").isPro)
    }
    @Test fun noPurchaseWithPartialFailureIsUnknown() {
        assertFalse(combinePlayEntitlement(emptyList(), emptyList(), "unavailable").isKnown)
    }
    @Test fun bothEmptySuccessfulQueriesRevokeEntitlement() {
        assertEquals(GoogleBillingEntitlementState.FREE, combinePlayEntitlement(emptyList(), emptyList(), null).state)
    }
}
