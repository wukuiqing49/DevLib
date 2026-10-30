package com.wkq.google.billing

import com.wkq.google.model.SubscriptionStatus

/** Play 客户端只能查询当前配置账号；空参数表示当前会话，不支持远程查询任意用户。 */
internal fun requireSubscriptionAccount(requested: String, current: String) {
    require(requested.isBlank() || requested.trim() == current) {
        "Subscription query is limited to the current billing account"
    }
}

/** 把已知权益投影为订阅状态；永久购买不算订阅，未知结果不能降级为未订阅。 */
internal fun subscriptionStatus(entitlement: GoogleBillingEntitlement): SubscriptionStatus {
    check(entitlement.isKnown) { "Subscription state is unknown; retry or retain the previous state" }
    check(entitlement.hasSubscription || entitlement.errorMessage.isBlank()) {
        "Subscription query may be incomplete; retain the previous state"
    }
    return SubscriptionStatus(
        isActive = entitlement.hasSubscription,
        expireTimeMillis = 0L,
        planId = entitlement.activeSubscriptionIds.firstOrNull().orEmpty(),
        source = entitlement.source,
    )
}
