package com.wkq.google.model

/** 当前账号的已知订阅状态；到期时间为 0 表示客户端未知，不能据此判断已过期。 */
data class SubscriptionStatus(
    val isActive: Boolean = false,
    val expireTimeMillis: Long = 0L,
    val planId: String = "",
    val source: String = ""
)
