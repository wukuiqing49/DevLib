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
    /** 宿主决定离线缓存时长；0 表示禁止离线授权，默认沿用迁入基线的 24 小时。 */
    val offlineCacheMaxAgeMillis: Long = 24L * 60 * 60 * 1000
)

object GoogleBillingRuntime {
    @Volatile private var config = GoogleBillingRuntimeConfig()
    fun initialize(context: Context, config: GoogleBillingRuntimeConfig) {
        require(config.offlineCacheMaxAgeMillis >= 0) { "offlineCacheMaxAgeMillis must be non-negative" }
        this.config = config
        GoogleBillingManager.initialize(context.applicationContext)
    }
    internal fun currentConfig() = config
    internal fun requireConfig() = config
}
