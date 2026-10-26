package com.wkq.dev.demo.google

import com.wkq.google.billing.GoogleBillingEntitlement
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 购买状态恢复的宿主状态模型，区分尚未请求、加载、成功与失败。 */
internal sealed interface PurchaseRecoveryState {
    /** 已就绪但尚无本次恢复结果，等待显式刷新。 */
    data object Idle : PurchaseRecoveryState

    /** 正在向计费服务查询购买状态。 */
    data object Loading : PurchaseRecoveryState

    /** 携带本次核对所得权益快照，具体商业授权仍由消费宿主判断。 */
    data class Ready(val entitlement: GoogleBillingEntitlement) : PurchaseRecoveryState

    /** 恢复查询失败或超时，允许后续显式重试。 */
    data object Failed : PurchaseRecoveryState
}

/** 前台恢复独立于页面回调，后发查询或新商品配置不会被旧查询覆盖。 */
internal class PurchaseStateRecovery(
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val query: suspend () -> GoogleBillingEntitlement,
) {
    private val mutable = MutableStateFlow<PurchaseRecoveryState>(PurchaseRecoveryState.Idle)
    val state = mutable.asStateFlow()
    var configured = false
        private set

    private var recovery: Job? = null

    /** 取消旧查询并标记配置可用，将展示状态重置为空闲。 */
    fun configure() {
        recovery?.cancel()
        configured = true
        mutable.value = PurchaseRecoveryState.Idle
    }

    /** 仅在已配置时启动恢复，取消旧查询并设置 25 秒超时；取消继续传播，其他失败映射为失败状态。 */
    fun refresh() {
        if (!configured) return
        recovery?.cancel()
        recovery =
            scope.launch {
                mutable.value = PurchaseRecoveryState.Loading
                try {
                    val entitlement = withTimeout(25_000) { withContext(dispatcher) { query() } }
                    ensureActive()
                    mutable.value = PurchaseRecoveryState.Ready(entitlement)
                } catch (_: TimeoutCancellationException) {
                    mutable.value = PurchaseRecoveryState.Failed
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    mutable.value = PurchaseRecoveryState.Failed
                }
            }
    }
}
