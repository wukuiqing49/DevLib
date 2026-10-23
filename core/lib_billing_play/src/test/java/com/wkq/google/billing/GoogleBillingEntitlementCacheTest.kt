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
}
