package com.wkq.dev.entitlement

enum class EntitlementState { GRANTED, DENIED, UNKNOWN }
data class EntitlementSnapshot(
    val accountScope: String,
    val capabilities: Set<String>,
    val state: EntitlementState,
    val verifiedAtMillis: Long,
    val expiresAtMillis: Long,
)
/** 宿主提供核验结果与离线有效期；未核验、账号变化、时钟回拨或过期均不授予能力。 */
object EntitlementPolicy {
    fun allows(snapshot: EntitlementSnapshot?, scope: String, capability: String, nowMillis: Long): Boolean =
        snapshot != null && scope.isNotBlank() && snapshot.accountScope == scope &&
            snapshot.state == EntitlementState.GRANTED && capability in snapshot.capabilities &&
            snapshot.verifiedAtMillis >= 0 && nowMillis >= snapshot.verifiedAtMillis &&
            nowMillis < snapshot.expiresAtMillis
}
