package com.wkq.google.billing

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleBillingEntitlementCacheTest {
    private val checkedAt = 1_800_000_000_000L
    private val cached = CachedBillingEntitlement("account-a", checkedAt,
        GoogleBillingEntitlement(state = GoogleBillingEntitlementState.PRO))

    @Test fun sameAccountCanUseRecentEntitlement() {
        assertTrue(cached.usableFor("account-a", checkedAt + 60_000))
    }
    @Test fun anotherAccountAndSignedOutCannotUseCache() {
        assertFalse(cached.usableFor("account-b", checkedAt))
        assertFalse(cached.usableFor("", checkedAt))
    }
    @Test fun gracePeriodDoesNotExtendIndefinitely() {
        assertFalse(cached.usableFor("account-a", checkedAt + CachedBillingEntitlement.OFFLINE_GRACE_MS))
    }
    @Test fun clockBeforeVerificationRejectsCache() {
        assertFalse(cached.usableFor("account-a", checkedAt - 1))
    }
    @Test fun unknownEntitlementIsNeverReusable() {
        assertFalse(cached.copy(entitlement = GoogleBillingEntitlement()).usableFor("account-a", checkedAt))
    }

    @Test fun cacheExpiresWhileAQueryIsRunning() {
        val expiresAt = checkedAt + CachedBillingEntitlement.OFFLINE_GRACE_MS
        assertTrue(cached.usableFor("account-a", expiresAt - 1))
        assertFalse(cached.usableFor("account-a", expiresAt + 10_000))
    }

    @Test fun corruptNegativeTimestampCannotOverflowIntoValidCache() {
        assertFalse(cached.copy(checkedAt = Long.MIN_VALUE).usableFor("account-a", checkedAt))
    }

    @Test fun knownFreeEntitlementCanBeCachedForTheSameAccount() {
        val free = cached.copy(entitlement = GoogleBillingEntitlement(state = GoogleBillingEntitlementState.FREE))
        assertTrue(free.usableFor("account-a", checkedAt))
        assertFalse(free.usableFor("account-b", checkedAt))
    }

    @Test fun subscriptionHasAnExplicitSevenDayOfflineBoundary() {
        val sevenDays = 7L * 24 * 60 * 60 * 1000
        assertTrue(cached.usableFor("account-a", checkedAt + sevenDays - 1, sevenDays))
        assertFalse(cached.usableFor("account-a", checkedAt + sevenDays, sevenDays))
    }

    @Test fun retainedLifetimeSurvivesGraceButNeverCrossesCacheScope() {
        val lifetime = cached.copy(entitlement = cached.entitlement.copy(hasLifetimeUnlock = true))
        val later = checkedAt + 365L * 24 * 60 * 60 * 1000
        assertTrue(lifetime.usableFor("account-a", later, retainLifetime = true))
        assertFalse(lifetime.usableFor("account-b", later, retainLifetime = true))
        assertFalse(lifetime.usableFor("account-a", later))
    }

    @Test fun freshFreeResultDoesNotInheritLifetimeRetention() {
        val revoked = cached.copy(entitlement = GoogleBillingEntitlement(state = GoogleBillingEntitlementState.FREE))
        assertFalse(revoked.entitlement.isPro)
        assertFalse(revoked.usableFor("account-a", checkedAt + OFFLINE_YEAR, retainLifetime = true))
    }

    companion object { private const val OFFLINE_YEAR = 365L * 24 * 60 * 60 * 1000 }
}
