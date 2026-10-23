package com.wkq.google.billing
import org.junit.Assert.*
import org.junit.Test
class OfflineCachePolicyTest {
 @Test fun hostCanDisableOrShortenOfflineAuthorization() {
  val cached = CachedBillingEntitlement("a", 100, GoogleBillingEntitlement(state = GoogleBillingEntitlementState.PRO))
  assertFalse(cached.usableFor("a", 101, maxAgeMillis = 0))
  assertTrue(cached.usableFor("a", 109, maxAgeMillis = 10))
  assertFalse(cached.usableFor("a", 110, maxAgeMillis = 10))
  assertFalse(cached.usableFor("b", 109, maxAgeMillis = 10))
 }
}
