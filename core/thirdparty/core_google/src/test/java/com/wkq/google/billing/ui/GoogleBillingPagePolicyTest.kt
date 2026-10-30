package com.wkq.google.billing.ui

import com.wkq.google.billing.*
import org.junit.Assert.*
import org.junit.Test

/** 校验套餐与真实报价的边界，防止错误基础方案或变化报价被购买。 */
class GoogleBillingPagePolicyTest {
    private val product = GoogleProduct("pro:annual", GoogleProductType.SUBS, "Pro", "", "$12", "USD", 12_000_000, "offer", "P1Y")

    /** 指定基础方案不得被同商品的另一基础方案替代。 */
    @Test fun missingBasePlanRemainsUnavailable() {
        val plan = GoogleBillingPlanConfig("monthly", "pro", GoogleProductType.SUBS, "", "", basePlanId = "monthly")
        val rows = GoogleBillingPagePolicy.rows(listOf(plan), GoogleBillingCatalog(subscriptions = listOf(product)))
        assertEquals(1, rows.size)
        assertNull(rows.single().product)
    }

    /** 未指定基础方案时保留各个真实报价供显式选择。 */
    @Test fun unqualifiedPlanKeepsAllBasePlans() {
        val plan = GoogleBillingPlanConfig("pro", "pro", GoogleProductType.SUBS, "", "")
        val monthly = product.copy(productId = "pro:monthly", billingPeriod = "P1M")
        val rows = GoogleBillingPagePolicy.rows(listOf(plan), GoogleBillingCatalog(subscriptions = listOf(product, monthly)))
        assertEquals(2, rows.size)
        assertEquals(2, rows.map { it.id }.distinct().size)
    }

    /** 不允许没有报价令牌、未建模周期或空价格的订阅。 */
    @Test fun incompleteQuotesAreNotPurchasable() {
        assertFalse(GoogleBillingPagePolicy.isPurchasable(product.copy(offerToken = "")))
        assertFalse(GoogleBillingPagePolicy.isPurchasable(product.copy(billingPeriod = "P1Y2M")))
        assertFalse(GoogleBillingPagePolicy.isPurchasable(product.copy(billingPeriod = "P999999999999Y")))
        assertFalse(GoogleBillingPagePolicy.isPurchasable(product.copy(formattedPrice = "")))
        assertTrue(GoogleBillingPagePolicy.isPurchasable(product))
    }

    /** 同价格但不同报价令牌也必须要求用户重新确认。 */
    @Test fun offerAndPriceChangesInvalidateConfirmation() {
        assertFalse(GoogleBillingPagePolicy.sameQuote(product, product.copy(offerToken = "new")))
        assertFalse(GoogleBillingPagePolicy.sameQuote(product, product.copy(priceAmountMicros = 13_000_000)))
        assertTrue(GoogleBillingPagePolicy.sameQuote(product, product.copy()))
    }

    /** 未核对及待付款本身不构成 Pro。 */
    @Test fun unknownAndPendingDoNotGrantPro() {
        assertFalse(GooglePaidStatus().isKnown)
        assertFalse(GooglePaidStatus(hasPendingPurchase = true).isPro)
        GoogleBillingStatus.publish(GoogleBillingEntitlement(state = GoogleBillingEntitlementState.PRO), 1234L)
        assertTrue(GoogleBillingStatus.state.value.isPro)
        assertEquals(1234L, GoogleBillingStatus.state.value.checkedAtMillis)
        GoogleBillingStatus.reset()
        assertFalse(GoogleBillingStatus.state.value.isKnown)
    }
}
