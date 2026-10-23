package com.wkq.google.billing

import android.content.Context
import com.wkq.google.billing.ui.GoogleBillingPlanConfig

/** 独立支付初始化，不初始化登录、广告、分析或商业后台。 */
data class GoogleBillingRuntimeConfig(
    val billingSubscriptionIds: List<String> = emptyList(),
    val billingInAppProductIds: List<String> = emptyList(),
    val billingPlans: List<GoogleBillingPlanConfig> = emptyList(),
    val billingPurchaseAllowed: () -> Boolean = { true },
    val billingAccountIdProvider: () -> String = { "" },
    val requireAppAccount: Boolean = true,
    /** 指定一次性商品的购买选项；缺失时不回退到其他选项。 */
    val billingOneTimePurchaseOptionIds: Map<String, String> = emptyMap(),
    val offlineSubscriptionGraceMillis: Long = 24L * 60 * 60 * 1000,
    val retainLifetimeOffline: Boolean = false
)

object GoogleBillingRuntime {
    @Volatile private var config = GoogleBillingRuntimeConfig()
    fun initialize(context: Context, config: GoogleBillingRuntimeConfig) {
        this.config = config
        GoogleBillingManager.initialize(context.applicationContext)
    }
    internal fun currentConfig() = config
    internal fun requireConfig() = config
}
