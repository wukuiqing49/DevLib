package com.wkq.google.billing

import org.junit.Assert.*
import org.junit.Test

/** 验证订阅查询不会混入永久购买、未知状态或其他宿主账号。 */
class GoogleSubscriptionPolicyTest {
    /** 永久权益已知但订阅查询失败时，不能撤销旧订阅状态。 */
    @Test(expected = IllegalStateException::class) fun partialFailureDoesNotBecomeUnsubscribed() {
        subscriptionStatus(combinePlayEntitlement(emptyList(), listOf("lifetime"), "query failed"))
    }
    /** 永久购买可以解锁功能，但不是订阅。 */
    @Test fun lifetimeIsNotSubscription() {
        val status = subscriptionStatus(combinePlayEntitlement(emptyList(), listOf("lifetime"), null))
        assertFalse(status.isActive)
        assertEquals("", status.planId)
    }
    /** 订阅返回实际订阅产品，客户端未知的到期时间保持 0。 */
    @Test fun subscriptionKeepsItsOwnProduct() {
        val status = subscriptionStatus(combinePlayEntitlement(listOf("monthly"), listOf("lifetime"), null))
        assertTrue(status.isActive)
        assertEquals("monthly", status.planId)
        assertEquals(0L, status.expireTimeMillis)
    }
    /** 查询失败不能表现为未订阅。 */
    @Test(expected = IllegalStateException::class) fun unknownDoesNotBecomeFree() {
        subscriptionStatus(GoogleBillingEntitlement())
    }
    /** 指定其他账号时应失败，不发起跨用户客户端查询。 */
    @Test(expected = IllegalArgumentException::class) fun rejectsAnotherAccount() {
        requireSubscriptionAccount("other", "current")
    }
    /** 空参数和当前账号可沿用当前会话查询。 */
    @Test fun acceptsCurrentOrImplicitAccount() {
        requireSubscriptionAccount("", "current")
        requireSubscriptionAccount("current", "current")
    }
}
