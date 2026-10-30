package com.wkq.google.billing.ui

import com.wkq.google.billing.GoogleProductType
import java.io.Serializable

enum class GoogleSubscriptionReplacementMode {
    CHARGE_FULL_PRICE,
    DEFERRED
}

data class GoogleBillingPlanConfig(
    val planId: String,
    val productId: String,
    val productType: String,
    val title: String,
    val description: String,
    val basePlanId: String = "",
    val badge: String = "",
    val fallbackPrice: String = "",
    /** 以旧订阅 product ID 为键的明确替换规则，避免依赖套餐排列顺序。 */
    val replacementModes: Map<String, GoogleSubscriptionReplacementMode> = emptyMap()
) : Serializable {
    val billingProductId: String
        get() = if (productType == GoogleProductType.SUBS && basePlanId.isNotBlank()) {
            "$productId:$basePlanId"
        } else {
            productId
        }
}

data class GoogleBillingPageConfig(
    val plans: List<GoogleBillingPlanConfig>,
    val statusTitle: String = "",
    val statusDescription: String = "",
    val productsTitle: String = "",
    val productsDescription: String = "",
    val priceDisclosure: String = "",
    val emptyText: String = "",
    val purchaseButtonText: String = "",
    val restoreButtonText: String = "",
    val redeemButtonText: String = "",
    val openRedeemPageOnClick: Boolean = false
) : Serializable
