package com.wkq.google.billing

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class GoogleBillingPersistenceTest {
    private val context get() = RuntimeEnvironment.getApplication()
    @Test fun anonymousScopePersistsWithoutAnAppAccountAndNeverUsesEmail() = runBlocking {
        val first = GoogleBillingEntitlementCache.scope(context, false, "")
        assertTrue(first.startsWith("play-install:"))
        assertEquals(first, GoogleBillingEntitlementCache.scope(context, false, "other-app-profile"))
        assertTrue(File(context.noBackupFilesDir, "billing_cache_scope").isFile)
    }
    @Test fun accountRequiredModeKeepsStrictAccountIsolation() = runBlocking {
        assertEquals("", GoogleBillingEntitlementCache.scope(context, true, ""))
        assertEquals("account-a", GoogleBillingEntitlementCache.scope(context, true, "account-a"))
    }
    @Test fun reinstallScopeCannotReuseOldEntitlement() = runBlocking {
        val old = GoogleBillingEntitlementCache.scope(context, false, "")
        File(context.noBackupFilesDir, "billing_cache_scope").delete()
        val fresh = GoogleBillingEntitlementCache.scope(context, false, "")
        assertNotEquals(old, fresh)
        val cached = CachedBillingEntitlement(old, 100L, GoogleBillingEntitlement(state = GoogleBillingEntitlementState.PRO))
        assertFalse(cached.usableFor(fresh, 200L))
    }
    @Test fun persistenceDoesNotExtendVerificationTimeAndSuccessfulFreeRevokesPro() = runBlocking {
        val pro = GoogleBillingEntitlement(state = GoogleBillingEntitlementState.PRO, hasLifetimeUnlock = true,
            ownedOneTimeProductIds = listOf("pro_lifetime"))
        GoogleBillingEntitlementCache.write(context, CachedBillingEntitlement("install", 100L, pro))
        val restored = GoogleBillingEntitlementCache.read(context)!!
        assertEquals(100L, restored.checkedAt)
        assertTrue(restored.entitlement.hasLifetimeUnlock)
        assertFalse(restored.usableFor("install", 100L + CachedBillingEntitlement.OFFLINE_GRACE_MS))
        GoogleBillingEntitlementCache.write(context, CachedBillingEntitlement("install", 200L,
            GoogleBillingEntitlement(state = GoogleBillingEntitlementState.FREE)))
        assertFalse(GoogleBillingEntitlementCache.read(context)!!.entitlement.isPro)
    }
    @Test fun damagedCacheCannotGrantPro() = runBlocking {
        File(context.noBackupFilesDir, "billing_entitlement.json").writeText("broken")
        assertNull(GoogleBillingEntitlementCache.read(context))
    }
}
