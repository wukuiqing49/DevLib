package com.wkq.google.billing

import com.android.billingclient.api.Purchase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleBillingPolicyTest {

    @Test
    fun obfuscatedAccountId_isStableAndDoesNotExposeRawId() {
        val rawAccountId = "user@example.com"

        val first = obfuscateBillingAccountId(rawAccountId)
        val second = obfuscateBillingAccountId("  $rawAccountId  ")

        assertEquals(first, second)
        assertEquals(64, first.length)
        assertFalse(first.contains(rawAccountId))
    }

    @Test
    fun currentAccountFilter_acceptsMatchingAndLegacyPurchasesOnly() {
        val accountId = obfuscateBillingAccountId("account-a")
        val purchases = listOf(
            purchase("matching", accountId),
            purchase("legacy", ""),
            purchase("other", obfuscateBillingAccountId("account-b"))
        )

        val filtered = purchases.filterCurrentAccountPurchases(accountId)

        assertEquals(listOf("matching", "legacy"), filtered.map { it.purchaseToken })
    }

    @Test
    fun currentAccountFilter_rejectsAllPurchasesWithoutLoggedInAccount() {
        val purchases = listOf(purchase("legacy", ""))

        assertTrue(purchases.filterCurrentAccountPurchases("").isEmpty())
    }

    @Test
    fun currentAccountFilter_acceptsLegacyAndCommercialIdsDuringMigration() {
        val purchases = listOf(
            purchase("legacy-account", "legacy-id"),
            purchase("commercial-account", "commercial-id"),
            purchase("unrelated", "another-id"),
            purchase("without-account", "")
        )

        val filtered = purchases.filterCurrentAccountPurchases(
            setOf("legacy-id", "commercial-id")
        )

        assertEquals(
            listOf("legacy-account", "commercial-account", "without-account"),
            filtered.map { it.purchaseToken }
        )
    }

    @Test
    fun purchasedFilter_doesNotGrantPendingOrUnknownPurchases() {
        val purchases = listOf(
            purchase("purchased", "", Purchase.PurchaseState.PURCHASED),
            purchase("pending", "", Purchase.PurchaseState.PENDING),
            purchase("unknown", "", Purchase.PurchaseState.UNSPECIFIED_STATE)
        )

        assertEquals(listOf("purchased"), purchases.filterPurchased().map { it.purchaseToken })
    }

    @Test fun anonymousPlayModeDoesNotRequireAppAccount() {
        val purchases = listOf(purchase("play-owned", ""), purchase("previous-app-account", "old"))
        assertEquals(purchases, purchases.filterCurrentAccountPurchases(emptySet(), requireAppAccount = false))
        assertTrue(purchases.filterCurrentAccountPurchases(emptySet(), requireAppAccount = true).isEmpty())
    }

    private fun purchase(
        token: String,
        accountId: String,
        state: Int = Purchase.PurchaseState.PURCHASED
    ): GooglePurchase {
        return GooglePurchase(
            products = listOf("pro_monthly"),
            purchaseToken = token,
            purchaseTimeMillis = 1L,
            isAcknowledged = true,
            purchaseState = state,
            obfuscatedAccountId = accountId
        )
    }
}
