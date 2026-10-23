package com.wkq.dev.entitlement
import org.junit.Assert.*
import org.junit.Test
class EntitlementPolicyTest {
 private val grant = EntitlementSnapshot("account-a", setOf("transfer"), EntitlementState.GRANTED, 100, 200)
 @Test fun grantsOnlyTheMatchingCapabilityWithinValidity() {
  assertTrue(EntitlementPolicy.allows(grant, "account-a", "transfer", 100))
  assertFalse(EntitlementPolicy.allows(grant, "account-a", "other", 150))
  assertFalse(EntitlementPolicy.allows(grant, "account-b", "transfer", 150))
  assertFalse(EntitlementPolicy.allows(grant, "account-a", "transfer", 99))
  assertFalse(EntitlementPolicy.allows(grant, "account-a", "transfer", 200))
 }
 @Test fun unknownDeniedAndMissingNeverGrant() {
  for (state in listOf(EntitlementState.UNKNOWN, EntitlementState.DENIED))
   assertFalse(EntitlementPolicy.allows(grant.copy(state = state), "account-a", "transfer", 150))
  assertFalse(EntitlementPolicy.allows(null, "account-a", "transfer", 150))
 }
}
