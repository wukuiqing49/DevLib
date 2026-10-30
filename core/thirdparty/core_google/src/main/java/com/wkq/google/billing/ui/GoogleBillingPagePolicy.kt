package com.wkq.google.billing.ui

import com.wkq.google.billing.GoogleBillingCatalog
import com.wkq.google.billing.GoogleProduct
import com.wkq.google.billing.GoogleProductType

/** 页面报价与宿主套餐的匹配结果；没有真实报价的套餐只展示，不允许用兜底价格购买。 */
data class GoogleBillingPlanRow(val id: String, val config: GoogleBillingPlanConfig?, val product: GoogleProduct?)

/** 纯报价策略，可在 JVM 校验基础方案匹配、报价变化及重复套餐边界。 */
object GoogleBillingPagePolicy {
    /** 精确匹配 productId/basePlanId；未指明基础方案时保留各真实报价，不任意选取第一项。 */
    fun rows(plans: List<GoogleBillingPlanConfig>, catalog: GoogleBillingCatalog): List<GoogleBillingPlanRow> {
        val products = (catalog.subscriptions + catalog.oneTimeProducts).filter(::isPurchasable)
            .distinctBy { it.productType to it.productId }
        if (plans.isEmpty()) return products.map { GoogleBillingPlanRow(it.productType + ":" + it.productId, null, it) }
        return plans.distinctBy { it.planId }.flatMap { plan ->
            val matching = products.filter { product ->
                product.productType == plan.productType &&
                    if (plan.basePlanId.isNotBlank() || plan.productType != GoogleProductType.SUBS)
                        product.productId == plan.billingProductId
                    else product.productId.substringBefore(':') == plan.productId
            }
            if (matching.isEmpty()) listOf(GoogleBillingPlanRow(plan.planId, plan, null))
            else matching.map { GoogleBillingPlanRow(plan.planId + ":" + it.productId, plan, it) }
        }
    }

    /** 仅支持已经完整披露的标准周期；订阅必须具有有效 offerToken，不销售未建模的优惠。 */
    fun isPurchasable(product: GoogleProduct): Boolean = product.formattedPrice.isNotBlank() && when (product.productType) {
        GoogleProductType.IN_APP -> true
        GoogleProductType.SUBS -> product.offerToken.isNotBlank() &&
            Regex("P[1-9][0-9]*[DWMY]").matches(product.billingPeriod) &&
            product.billingPeriod.drop(1).dropLast(1).toIntOrNull()?.let { it > 0 } == true
        else -> false
    }

    /** 发起购买前重新查询必须保持同一价格、周期和报价令牌；变化后要求用户重新确认。 */
    fun sameQuote(previous: GoogleProduct, current: GoogleProduct): Boolean =
        previous.productId == current.productId && previous.productType == current.productType &&
            previous.offerToken == current.offerToken && previous.formattedPrice == current.formattedPrice &&
            previous.priceCurrencyCode == current.priceCurrencyCode && previous.priceAmountMicros == current.priceAmountMicros &&
            previous.billingPeriod == current.billingPeriod
}
