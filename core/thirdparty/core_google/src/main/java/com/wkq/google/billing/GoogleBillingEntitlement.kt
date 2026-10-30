package com.wkq.google.billing

data class GoogleBillingCatalog(
    val subscriptions: List<GoogleProduct> = emptyList(),
    val oneTimeProducts: List<GoogleProduct> = emptyList(),
    val subscriptionsUnavailable: Boolean = false,
    val oneTimeUnavailable: Boolean = false
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
    val hasPendingPurchase: Boolean = false,
    val hasSubscription: Boolean = false,
    val hasLifetimeUnlock: Boolean = false,
    val activeSubscriptionIds: List<String> = emptyList(),
    val ownedOneTimeProductIds: List<String> = emptyList(),
    val source: String = "google_play",
    val errorMessage: String = ""
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

/** 一类商品查询失败不会抹掉另一类已取得的真实报价。 */
internal fun combineBillingCatalog(subscriptions: Result<List<GoogleProduct>>, oneTime: Result<List<GoogleProduct>>): Result<GoogleBillingCatalog> {
    if (subscriptions.isFailure && oneTime.isFailure) return Result.failure(subscriptions.exceptionOrNull()!!)
    return Result.success(GoogleBillingCatalog(subscriptions.getOrDefault(emptyList()), oneTime.getOrDefault(emptyList()),
        subscriptions.isFailure, oneTime.isFailure))
}
