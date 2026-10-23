package com.wkq.google.billing

data class GoogleProduct(
    val productId: String,
    val productType: String,
    val title: String,
    val description: String,
    val formattedPrice: String,
    val priceCurrencyCode: String = "",
    val priceAmountMicros: Long = 0L,
    val offerToken: String = "",
    val billingPeriod: String = ""
)

data class GooglePurchase(
    val products: List<String>,
    val purchaseToken: String,
    val purchaseTimeMillis: Long,
    val isAcknowledged: Boolean,
    val purchaseState: Int,
    val obfuscatedAccountId: String = ""
)

data class GoogleBillingResponse(
    val isSuccess: Boolean,
    val responseCode: Int,
    val message: String = "",
    /** 订单已由 Google Play 接收，但本地确认将在后续补偿任务继续完成。 */
    val requiresPurchaseRecovery: Boolean = false
)
