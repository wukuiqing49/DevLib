package com.wkq.dev.demo.google

import com.wkq.google.billing.GoogleBillingEntitlement
import com.wkq.google.billing.GoogleBillingEntitlementState
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PurchaseStateRecoveryTest {
    @Test
    fun noHostConfigurationDoesNotConnectToPlay() = runTest {
        var calls = 0
        val recovery =
            PurchaseStateRecovery(this, StandardTestDispatcher(testScheduler)) {
                calls++
                GoogleBillingEntitlement()
            }
        recovery.refresh()
        advanceUntilIdle()
        assertEquals(0, calls)
        assertEquals(PurchaseRecoveryState.Idle, recovery.state.value)
    }

    @Test
    fun returningToForegroundRefreshesEvenWhenPurchaseCallbackWasMissed() = runTest {
        var current = GoogleBillingEntitlement(state = GoogleBillingEntitlementState.FREE)
        val recovery =
            PurchaseStateRecovery(this, StandardTestDispatcher(testScheduler)) { current }
        recovery.configure()
        recovery.refresh()
        advanceUntilIdle()
        assertEquals(
            GoogleBillingEntitlementState.FREE,
            (recovery.state.value as PurchaseRecoveryState.Ready).entitlement.state,
        )
        current =
            GoogleBillingEntitlement(
                state = GoogleBillingEntitlementState.PRO,
                hasSubscription = true,
            )
        // 模拟 Play 界面期间没有任何 UI 回调，回来后只触发 refresh。
        recovery.refresh()
        advanceUntilIdle()
        assertEquals(current, (recovery.state.value as PurchaseRecoveryState.Ready).entitlement)
    }

    @Test
    fun slowOldQueryCannotOverwriteNewerPurchaseState() = runTest {
        val old = CompletableDeferred<Unit>()
        var calls = 0
        val recovery =
            PurchaseStateRecovery(this, StandardTestDispatcher(testScheduler)) {
                if (++calls == 1)
                    withContext(NonCancellable) {
                        old.await()
                        GoogleBillingEntitlement(state = GoogleBillingEntitlementState.FREE)
                    }
                else GoogleBillingEntitlement(state = GoogleBillingEntitlementState.PRO)
            }
        recovery.configure()
        recovery.refresh()
        runCurrent()
        recovery.refresh()
        runCurrent()
        old.complete(Unit)
        advanceUntilIdle()
        assertEquals(
            GoogleBillingEntitlementState.PRO,
            (recovery.state.value as PurchaseRecoveryState.Ready).entitlement.state,
        )
    }

    @Test
    fun reconfiguringProductsDiscardsEarlierResult() = runTest {
        val old = CompletableDeferred<Unit>()
        val recovery =
            PurchaseStateRecovery(this, StandardTestDispatcher(testScheduler)) {
                withContext(NonCancellable) {
                    old.await()
                    GoogleBillingEntitlement(state = GoogleBillingEntitlementState.PRO)
                }
            }
        recovery.configure()
        recovery.refresh()
        runCurrent()
        recovery.configure()
        old.complete(Unit)
        advanceUntilIdle()
        assertEquals(PurchaseRecoveryState.Idle, recovery.state.value)
    }

    @Test
    fun unavailablePlayDoesNotBecomeFreeEntitlement() = runTest {
        val recovery =
            PurchaseStateRecovery(this, StandardTestDispatcher(testScheduler)) {
                throw IllegalStateException("secret fixture")
            }
        recovery.configure()
        recovery.refresh()
        advanceUntilIdle()
        assertEquals(PurchaseRecoveryState.Failed, recovery.state.value)
    }

    @Test
    fun timeoutEndsLoadingAndAllowsRetry() = runTest {
        var timeout = true
        val recovery =
            PurchaseStateRecovery(this, StandardTestDispatcher(testScheduler)) {
                if (timeout) delay(30_000)
                GoogleBillingEntitlement(state = GoogleBillingEntitlementState.FREE)
            }
        recovery.configure()
        recovery.refresh()
        advanceUntilIdle()
        assertEquals(PurchaseRecoveryState.Failed, recovery.state.value)
        timeout = false
        recovery.refresh()
        advanceUntilIdle()
        assertTrue(recovery.state.value is PurchaseRecoveryState.Ready)
    }
}
