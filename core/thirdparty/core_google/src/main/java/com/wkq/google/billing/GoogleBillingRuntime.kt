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

/** 保存宿主显式传入的运行时配置；进程重建后必须由宿主再次初始化。 */
object GoogleBillingRuntime {
    @Volatile private var config = GoogleBillingRuntimeConfig()
    @Volatile var isInitialized: Boolean = false
        private set

    /** 仅使用 Application Context 初始化计费并重置对外快照，不发起购买。 */
    fun initialize(context: Context, config: GoogleBillingRuntimeConfig) {
        this.config = config
        isInitialized = true
        GoogleBillingStatus.reset()
        GoogleBillingManager.initialize(context.applicationContext)
    }
    /** 为可选支付 UI 提供套餐描述的副本，不暴露宿主账号回调。 */
    fun configuredPlans(): List<GoogleBillingPlanConfig> = config.billingPlans.toList()

    /** 引擎读取当前配置，不替代初始化校验。 */
    internal fun currentConfig() = config
    /** 兼容既有计费调用的内部配置入口。 */
    internal fun requireConfig() = config
}
