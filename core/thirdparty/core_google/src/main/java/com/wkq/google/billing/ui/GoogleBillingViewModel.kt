package com.wkq.google.billing.ui

import androidx.lifecycle.viewModelScope
import com.wkq.base.BaseViewModel
import com.wkq.google.R
import com.wkq.google.billing.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/** 支付页面状态，不持有 Activity；通知资源由页面按当前语言解析。 */
data class GoogleBillingUiState(
    val rows: List<GoogleBillingPlanRow> = emptyList(),
    val selectedId: String = "",
    val loading: Boolean = false,
    val purchasing: Boolean = false,
    val message: Int = 0,
    val status: GooglePaidStatus = GooglePaidStatus(),
)

/** 复用 Base 的支付编排，查询在生命周期协程内进行；页面销毁不终止引擎订单确认。 */
class GoogleBillingViewModel : BaseViewModel() {
    private val mutableState = MutableStateFlow(GoogleBillingUiState())
    val state = mutableState.asStateFlow()
    internal var config = GoogleBillingPageConfig(emptyList())
    internal var request: GoogleBillingRequest? = null
    internal var redemptionPage = false
    internal var redemptionOpened = false
    internal var purchaseAttempted = false
    private var job: Job? = null

    /** 只在首个实例配置页面，旋转后沿用 ViewModel 中的报价选择但重新核对购买。 */
    internal fun configure(input: GoogleBillingRequest, selection: String = "") {
        if (request != null) return
        request = input
        config = if (input.page.plans.isEmpty()) input.page.copy(plans = GoogleBillingRuntime.configuredPlans()) else input.page
        mutableState.value = mutableState.value.copy(selectedId = selection)
        refresh(true)
    }

    /** 更新选择，报价列表和商品身份保持关联，不存储 UI 或 Context。 */
    internal fun select(id: String) {
        if (!state.value.loading && !state.value.purchasing && state.value.rows.any { it.id == id && it.product != null })
            mutableState.value = state.value.copy(selectedId = id)
    }

    /** 查询或恢复实际购买记录；未知不会被伪装为免费，也不会撤销宿主已有权益。 */
    internal fun refresh(catalog: Boolean = false) {
        if (state.value.loading) return
        if (!GoogleBillingRuntime.isInitialized) {
            mutableState.value = state.value.copy(message = R.string.gpay_not_initialized)
            return
        }
        job = viewModelScope.launch {
            mutableState.value = state.value.copy(loading = true, message = R.string.gpay_checking)
            try {
                withTimeout(30_000) {
                    if (catalog) {
                        val result = GoogleBillingManager.queryConfiguredCatalog().getOrNull()
                        mutableState.value = if (result == null) state.value.copy(message = R.string.gpay_partial)
                        else state.value.copy(
                            rows = GoogleBillingPagePolicy.rows(config.plans, result),
                            message = if (result.subscriptionsUnavailable || result.oneTimeUnavailable) R.string.gpay_partial else 0,
                        )
                    }
                    val status = GoogleBillingStatus.refresh()
                    val message = when {
                        !status.isKnown -> R.string.gpay_error_body
                        status.hasPendingPurchase -> R.string.gpay_pending_body
                        status.isOffline -> R.string.gpay_offline_body
                        state.value.message == R.string.gpay_partial -> R.string.gpay_partial
                        else -> R.string.gpay_updated
                    }
                    mutableState.value = state.value.copy(status = status, message = message)
                }
            } catch (error: CancellationException) {
                if (error is kotlinx.coroutines.TimeoutCancellationException)
                    mutableState.value = state.value.copy(message = R.string.gpay_error_body)
                else throw error
            } catch (_: Exception) {
                mutableState.value = state.value.copy(message = R.string.gpay_error_body)
            } finally {
                mutableState.value = state.value.copy(loading = false)
            }
        }
    }

    /** 用户点击后重取报价；变化时刷新列表并阻止本次购买，禁止使用旧缓存静默下单。 */
    internal suspend fun preparePurchase(): GoogleProduct? {
        if (state.value.loading || state.value.purchasing) return null
        val row = state.value.rows.firstOrNull { it.id == state.value.selectedId } ?: return null
        val previous = row.product ?: return null
        mutableState.value = state.value.copy(purchasing = true, message = R.string.gpay_checking)
        return try {
            val products = withTimeout(20_000) {
                GoogleBillingManager.queryProducts(listOf(previous.productId.substringBefore(':')), previous.productType).getOrThrow()
            }
            val fresh = products.firstOrNull { it.productId == previous.productId && it.productType == previous.productType }
            if (fresh == null || !GoogleBillingPagePolicy.isPurchasable(fresh) || !GoogleBillingPagePolicy.sameQuote(previous, fresh)) {
                mutableState.value = state.value.copy(
                    rows = state.value.rows.map { if (it.id == row.id) it.copy(product = fresh?.takeIf(GoogleBillingPagePolicy::isPurchasable)) else it },
                    selectedId = "", purchasing = false, message = R.string.gpay_offer_changed,
                )
                null
            } else fresh
        } catch (cancelled: CancellationException) {
            mutableState.value = state.value.copy(purchasing = false)
            if (cancelled is kotlinx.coroutines.TimeoutCancellationException) {
                report(R.string.gpay_purchase_failed); null
            } else throw cancelled
        } catch (_: Exception) {
            mutableState.value = state.value.copy(purchasing = false, message = R.string.gpay_purchase_failed)
            null
        }
    }

    /** 记录 SDK 发起结果；成功只表示收银台可打开，不是付款已完成。 */
    internal fun launched(response: GoogleBillingResponse) {
        purchaseAttempted = true
        mutableState.value = state.value.copy(purchasing = response.isSuccess, message = when {
            response.isSuccess -> R.string.gpay_purchase_started
            response.responseCode == 1 -> R.string.gpay_cancelled_body
            else -> R.string.gpay_purchase_failed
        })
    }

    /** 接收进程级订单回调后重新核对，页面取消与 Pro 状态分别表达。 */
    internal fun purchaseUpdated(response: GoogleBillingResponse) {
        mutableState.value = state.value.copy(purchasing = false)
        if (response.responseCode == 1) report(R.string.gpay_cancelled_body)
        else if (!response.isSuccess) report(R.string.gpay_purchase_failed)
        else {
            refresh()
            if (response.requiresPurchaseRecovery) report(R.string.gpay_recovery)
        }
    }

    /** 从 Play 或外部链接回到前台重新核对，不依赖 Activity Result 判断付款或兑换成功。 */
    internal fun resumed() {
        mutableState.value = state.value.copy(purchasing = false)
        if (GoogleBillingRuntime.isInitialized) refresh()
    }

    /** 将可本地化的失败或状态通知交给页面展示，不向用户输出 SDK debugMessage。 */
    internal fun report(message: Int) {
        mutableState.value = state.value.copy(message = message)
    }
}
