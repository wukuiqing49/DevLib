package com.wkq.google.billing

import java.io.Serializable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 不携带订单令牌的客户端付费快照；未知结果不能用来撤销宿主已有授权。 */
data class GooglePaidStatus(
    val state: GoogleBillingEntitlementState = GoogleBillingEntitlementState.UNKNOWN,
    val subscriptionIds: List<String> = emptyList(),
    val oneTimeProductIds: List<String> = emptyList(),
    val hasPendingPurchase: Boolean = false,
    val source: String = "",
    val checkedAtMillis: Long = 0L,
) : Serializable {
    val isKnown: Boolean get() = state != GoogleBillingEntitlementState.UNKNOWN
    val isPro: Boolean get() = state == GoogleBillingEntitlementState.PRO
    val isOffline: Boolean get() = source == "google_play_offline_cache"
}

/** 进程内可观察的购买结果；宿主重启后应显式恢复运行时配置并查询，不代替可信服务端授权。 */
object GoogleBillingStatus {
    private val mutableState = MutableStateFlow(GooglePaidStatus())
    val state = mutableState.asStateFlow()

    /** 主动在线核对；内部处理离线回退，返回值明确标识是否为缓存。 */
    suspend fun refresh(): GooglePaidStatus {
        GoogleBillingManager.refreshEntitlement()
        return state.value
    }

    /** 配置更换时清除旧账号及商品快照，避免将前一配置的 Pro 暴露给新宿主。 */
    internal fun reset() {
        mutableState.value = GooglePaidStatus()
    }

    /** 在计费引擎串行核对期间发布不含敏感订单信息的快照，缓存时间不伪装为在线验单时间。 */
    internal fun publish(entitlement: GoogleBillingEntitlement, checkedAtMillis: Long = System.currentTimeMillis()): GoogleBillingEntitlement {
        mutableState.value = GooglePaidStatus(
            entitlement.state, entitlement.activeSubscriptionIds.toList(),
            entitlement.ownedOneTimeProductIds.toList(), entitlement.hasPendingPurchase,
            entitlement.source,
            if (entitlement.isKnown && entitlement.source == "google_play") checkedAtMillis else 0L,
        )
        return entitlement
    }
}
