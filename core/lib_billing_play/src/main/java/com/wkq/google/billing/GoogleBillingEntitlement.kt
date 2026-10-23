package com.wkq.google.billing

data class GoogleBillingCatalog(
    val subscriptions: List<GoogleProduct> = emptyList(),
    val oneTimeProducts: List<GoogleProduct> = emptyList(),
    val issues: List<GoogleBillingIssue> = emptyList()
) {
    val isEmpty: Boolean
        get() = subscriptions.isEmpty() && oneTimeProducts.isEmpty()
}

enum class GoogleBillingEntitlementState {
    FREE,
    PRO,
    UNKNOWN
}

data class GoogleBillingEntitlement(
    val state: GoogleBillingEntitlementState = GoogleBillingEntitlementState.UNKNOWN,
    val hasSubscription: Boolean = false,
    val hasLifetimeUnlock: Boolean = false,
    val activeSubscriptionIds: List<String> = emptyList(),
    val ownedOneTimeProductIds: List<String> = emptyList(),
    val source: String = "google_play",
    val errorMessage: String = "",
    val verifiedAt: Long = 0L,
    val offlineUntil: Long = 0L
) {
    val isPro: Boolean
        get() = state == GoogleBillingEntitlementState.PRO

    val isKnown: Boolean
        get() = state != GoogleBillingEntitlementState.UNKNOWN

    val activePlanId: String
        get() = activeSubscriptionIds.firstOrNull()
            ?: ownedOneTimeProductIds.firstOrNull()
            .orEmpty()
}

/** 任一有效购买可授权；两类查询均成功且为空时才撤销权益。 */
internal fun combinePlayEntitlement(subscriptions: List<String>, lifetime: List<String>, error: String?): GoogleBillingEntitlement {
    val owned = subscriptions.isNotEmpty() || lifetime.isNotEmpty()
    return GoogleBillingEntitlement(
        state = when {
            owned -> GoogleBillingEntitlementState.PRO
            error != null -> GoogleBillingEntitlementState.UNKNOWN
            else -> GoogleBillingEntitlementState.FREE
        },
        hasSubscription = subscriptions.isNotEmpty(), hasLifetimeUnlock = lifetime.isNotEmpty(),
        activeSubscriptionIds = subscriptions, ownedOneTimeProductIds = lifetime,
        source = "google_play", errorMessage = error.orEmpty()
    )
}

/** 仅包含操作名与 Play 错误码，不含订单 token 或账号资料。 */
data class GoogleBillingIssue(val operation: String, val code: Int)
class GoogleBillingException(val issue: GoogleBillingIssue) : Exception("${issue.operation}: ${issue.code}")
