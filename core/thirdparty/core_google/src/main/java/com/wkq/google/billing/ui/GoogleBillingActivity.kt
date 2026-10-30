package com.wkq.google.billing.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.addCallback
import androidx.core.content.IntentCompat
import androidx.core.view.isVisible
import androidx.core.view.doOnLayout
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.lxj.xpopup.XPopup
import com.lxj.xpopup.core.BottomPopupView
import com.wkq.base.activity.BaseVMActivity
import com.wkq.base.adapter.BaseRecyclerViewAdapter
import com.wkq.google.R
import com.wkq.google.billing.GoogleBillingManager
import com.wkq.google.billing.GoogleProduct
import com.wkq.google.billing.GoogleProductType
import com.wkq.google.databinding.GpayHostBinding
import com.wkq.google.databinding.GpayPageBinding
import com.wkq.google.databinding.GpayPlanBinding
import kotlinx.coroutines.launch

/** 单库支付宿主；完整页面复用 Base Insets，底部弹层交给 XPopup 管理边界。 */
class GoogleBillingActivity : BaseVMActivity<GpayHostBinding, GoogleBillingViewModel>() {
    private var page: GpayPageBinding? = null
    private var planAdapter: BaseRecyclerViewAdapter<GpayPlanBinding, GoogleBillingPlanRow>? = null
    private var popup: BottomPopupView? = null
    private var listener: AutoCloseable? = null
    private var ready = false
    private var paused = false
    private var destroying = false
    private val request: GoogleBillingRequest
        get() = IntentCompat.getSerializableExtra(intent, GoogleBillingUi.REQUEST, GoogleBillingRequest::class.java)
            ?: GoogleBillingRequest()
    private val sheet: Boolean get() = request.presentation == GoogleBillingPresentation.BOTTOM_SHEET

    /** 根据展示模式选主题，恢复流程选择而不序列化宿主凭据。 */
    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(if (sheet) R.style.Gpay_Theme_Sheet else R.style.Gpay_Theme)
        super.onCreate(savedInstanceState)
        ready = true
        viewModel.configure(request, savedInstanceState?.getString("selection").orEmpty())
        if (savedInstanceState != null) {
            viewModel.redemptionPage = savedInstanceState.getBoolean("redemption")
            viewModel.redemptionOpened = savedInstanceState.getBoolean("opened")
            viewModel.purchaseAttempted = savedInstanceState.getBoolean("attempted")
        }
        onBackPressedDispatcher.addCallback(this) { navigateBack() }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { viewModel.state.collect { render(it) } }
        }
        if (!sheet && request.options.immersive) {
            WindowCompat.getInsetsController(window, binding.root).apply {
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    /** 弹层避免与 XPopup 重复消费顶部安全区。 */
    override fun shouldApplyStatusBarInset(): Boolean = !sheet
    /** 弹层避免重复消费导航栏安全区。 */
    override fun shouldApplyNavigationBarInset(): Boolean = !sheet
    /** 横向刘海由当前布局唯一的 Insets 所有者消费。 */
    override fun shouldApplyHorizontalInset(): Boolean = !sheet
    /** 普通页面兼容宿主键盘返回后的可用空间。 */
    override fun shouldApplyImeInset(): Boolean = !sheet

    /** 两种容器复用同一套 XML、状态和业务流程。 */
    override fun initView() {
        if (sheet) {
            val content = object : BottomPopupView(this) {
                /** 指定库内可滚动支付布局。 */
                override val implLayoutId: Int get() = R.layout.gpay_page
                /** 弹层初始化后绑定页面，生命周期仍归 Activity。 */
                override fun onCreate() { super.onCreate(); attach(GpayPageBinding.bind(popupImplView)) }
                /** 下滑及遮罩关闭均返回同一快照；旋转销毁不结束新实例。 */
                override fun onDismiss() {
                    super.onDismiss()
                    if (!destroying && !isChangingConfigurations) finish()
                }
            }
            popup = content
            binding.root.doOnLayout { root ->
                if (!destroying && !isFinishing) {
                    XPopup.Builder(this).isViewMode(true).isDestroyOnDismiss(true)
                        .maxWidth(minOf(root.width, (560 * resources.displayMetrics.density).toInt()))
                        .popupHeight((root.height * .9f).toInt())
                        .asCustom(content).show()
                }
            }
        } else attach(GpayPageBinding.inflate(layoutInflater, binding.container, true))
    }

    /** 数据由 ViewModel 配置并加载，避免 Base 初始化阶段重复查询。 */
    override fun initData() = Unit

    /** 绑定操作入口；兑换页明确跳转官方 Play，不接收自定义兑换码。 */
    private fun attach(binding: GpayPageBinding) {
        page = binding
        binding.plans.layoutManager = LinearLayoutManager(this)
        planAdapter = object : BaseRecyclerViewAdapter<GpayPlanBinding, GoogleBillingPlanRow>(this, GpayPlanBinding::inflate) {
            /** 复用 Base 列表，真实报价为空时仅展示不可用，不使用 fallbackPrice 伪造价格。 */
            override fun convert(binding: GpayPlanBinding, item: GoogleBillingPlanRow, position: Int) {
                val product = item.product
                binding.name.text = item.config?.title?.ifBlank { product?.title.orEmpty() } ?: product?.title.orEmpty()
                binding.description.text = item.config?.description?.ifBlank { product?.description.orEmpty() } ?: product?.description.orEmpty()
                binding.description.isVisible = binding.description.text.isNotBlank()
                binding.price.text = product?.formattedPrice ?: getString(R.string.gpay_unavailable)
                binding.period.text = product?.let { disclosure(it) }.orEmpty()
                binding.root.isSelected = item.id == viewModel.state.value.selectedId
                binding.root.strokeWidth = ((if (binding.root.isSelected) 3 else 1) * resources.displayMetrics.density).toInt()
                binding.root.isEnabled = product != null && !viewModel.state.value.loading && !viewModel.state.value.purchasing
                binding.root.setOnClickListener { viewModel.select(item.id) }
            }
        }
        binding.plans.adapter = planAdapter
        binding.back.setOnClickListener { navigateBack() }
        binding.restore.setOnClickListener { viewModel.refresh() }
        binding.redeem.setOnClickListener {
            if (viewModel.config.openRedeemPageOnClick) redeem()
            else { viewModel.redemptionPage = true; render(viewModel.state.value) }
        }
        binding.manage.setOnClickListener { openUrl("https://play.google.com/store/account/subscriptions?package=$packageName", true) }
        binding.terms.setOnClickListener { openUrl(request.options.termsUrl) }
        binding.privacy.setOnClickListener { openUrl(request.options.privacyUrl) }
        binding.primary.setOnClickListener {
            when {
                viewModel.redemptionPage -> redeem()
                viewModel.state.value.rows.none { it.product != null } -> viewModel.refresh(true)
                else -> lifecycleScope.launch {
                    val product = viewModel.preparePurchase() ?: return@launch
                    if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                        viewModel.purchaseUpdated(com.wkq.google.billing.GoogleBillingResponse(false, 1))
                        return@launch
                    }
                    viewModel.launched(GoogleBillingManager.launchPurchase(this@GoogleBillingActivity, product.productId, product.offerToken))
                }
            }
        }
        render(viewModel.state.value)
    }

    /** 根据当前语言和真实报价渲染，不将未知状态当作免费用户。 */
    private fun render(state: GoogleBillingUiState) {
        val binding = page ?: return
        val redeem = viewModel.redemptionPage
        binding.toolbarTitle.setText(if (redeem) R.string.gpay_redeem_title else R.string.gpay_choose)
        binding.title.text = if (redeem) getString(R.string.gpay_redeem_title) else viewModel.config.productsTitle.ifBlank { getString(R.string.gpay_choose) }
        binding.description.text = if (redeem) getString(R.string.gpay_redeem_intro) else viewModel.config.productsDescription
        binding.description.isVisible = binding.description.text.isNotBlank()
        binding.progress.isVisible = state.loading || state.purchasing
        binding.status.text = if (state.message != 0) getString(state.message) else ""
        binding.ownership.text = when {
            state.status.isPro -> getString(R.string.gpay_pro)
            state.status.hasPendingPurchase -> getString(R.string.gpay_pending)
            !state.status.isKnown -> getString(R.string.gpay_checking)
            else -> ""
        }
        binding.ownership.isVisible = binding.ownership.text.isNotBlank()
        if (state.status.isKnown && viewModel.config.statusTitle.isNotBlank()) {
            binding.ownership.text = listOf(binding.ownership.text, viewModel.config.statusTitle, viewModel.config.statusDescription)
                .filter { it.isNotBlank() }.joinToString("\n")
            binding.ownership.isVisible = true
        }
        binding.manage.isVisible = !redeem && state.status.subscriptionIds.isNotEmpty()
        binding.plans.isVisible = !redeem
        planAdapter?.setData(state.rows)
        val selected = state.rows.firstOrNull { it.id == state.selectedId }?.product
        binding.disclosure.text = if (redeem) listOf(getString(R.string.gpay_redeem_account), getString(R.string.gpay_redeem_gift)).joinToString("\n\n")
            else selected?.let { disclosure(it) } ?: viewModel.config.emptyText.ifBlank { getString(R.string.gpay_empty_body) }
        if (!redeem && viewModel.config.priceDisclosure.isNotBlank())
            binding.disclosure.append("\n" + viewModel.config.priceDisclosure)
        binding.primary.text = when {
            redeem -> getString(R.string.gpay_redeem_go)
            state.rows.none { it.product != null } -> getString(R.string.gpay_retry)
            selected != null -> getString(R.string.gpay_buy, selected.formattedPrice)
            else -> getString(R.string.gpay_select)
        }
        binding.primary.isEnabled = !state.loading && !state.purchasing && (redeem || selected != null || state.rows.none { it.product != null })
        binding.restore.isEnabled = !state.loading && !state.purchasing
        binding.restore.text = viewModel.config.restoreButtonText.ifBlank { getString(R.string.gpay_restore) }
        binding.redeem.text = viewModel.config.redeemButtonText.ifBlank { getString(R.string.gpay_redeem) }
        binding.redeem.isVisible = !redeem
        binding.redeem.isEnabled = !state.loading && !state.purchasing
        binding.terms.isVisible = validUrl(request.options.termsUrl)
        binding.privacy.isVisible = validUrl(request.options.privacyUrl)
    }

    /** 周期使用本地复数资源，价格保留 Google Play 已本地化的币种格式。 */
    private fun disclosure(product: GoogleProduct): String {
        if (product.productType == GoogleProductType.IN_APP) return getString(R.string.gpay_one_time, product.formattedPrice)
        val count = product.billingPeriod.drop(1).dropLast(1).toIntOrNull() ?: return getString(R.string.gpay_unavailable)
        val resource = when (product.billingPeriod.lastOrNull()) {
            'D' -> R.plurals.gpay_period_days
            'W' -> R.plurals.gpay_period_weeks
            'M' -> R.plurals.gpay_period_months
            else -> R.plurals.gpay_period_years
        }
        return getString(R.string.gpay_subscription_disclosure, product.formattedPrice, resources.getQuantityString(resource, count, count))
    }

    /** 仅允许 HTTPS 条款和官方链接。 */
    private fun validUrl(url: String): Boolean = runCatching { Uri.parse(url).let { it.scheme == "https" && !it.host.isNullOrBlank() } }.getOrDefault(false)

    /** 优先官方 Play，未安装时退回浏览器；打开成功不代表兑换完成。 */
    private fun openUrl(url: String, play: Boolean = false): Boolean {
        if (!validUrl(url)) return false
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        if (play && runCatching { startActivity(Intent(intent).setPackage("com.android.vending")) }.isSuccess) return true
        return runCatching { startActivity(intent); true }.getOrElse { viewModel.report(R.string.gpay_external_failed); false }
    }

    /** 只记录实际打开的官方兑换入口，回到前台后查询订单确认权益。 */
    private fun redeem() { if (openUrl("https://play.google.com/redeem", true)) viewModel.redemptionOpened = true }

    /** 兑换说明与购买列表构成同一级流程，系统返回先回到购买列表。 */
    private fun navigateBack() {
        if (viewModel.redemptionPage) { viewModel.redemptionPage = false; render(viewModel.state.value) }
        else finish()
    }

    /** 在可见期间监听订单结果，页面销毁不结束共享 BillingClient。 */
    override fun onStart() {
        super.onStart()
        listener = GoogleBillingManager.addPurchaseUpdatedListener { response, _ -> runOnUiThread { viewModel.purchaseUpdated(response) } }
    }
    /** Play 或浏览器返回时刷新，首次展示由 configure 加载。 */
    override fun onResume() { super.onResume(); if (paused && ready) viewModel.resumed(); paused = false }
    /** 记录离开前台，供返回时恢复核对。 */
    override fun onPause() { paused = true; super.onPause() }
    /** 注销本页面监听，避免重复投递和 Activity 泄漏。 */
    override fun onStop() { listener?.close(); listener = null; super.onStop() }
    /** 进程恢复只保存非敏感流程状态，不存订单令牌。 */
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("selection", viewModel.state.value.selectedId)
        outState.putBoolean("redemption", viewModel.redemptionPage)
        outState.putBoolean("opened", viewModel.redemptionOpened)
        outState.putBoolean("attempted", viewModel.purchaseAttempted)
        super.onSaveInstanceState(outState)
    }
    /** 所有正常关闭路径返回快照；宿主仍需判断未知和待付款状态。 */
    override fun finish() {
        if (ready) setResult(RESULT_OK, Intent().putExtra(GoogleBillingUi.RESULT,
            GoogleBillingPageResult(viewModel.state.value.status, viewModel.purchaseAttempted, viewModel.redemptionOpened)))
        super.finish()
    }
    /** 清理弹层引用；透明承载窗口结束后自动恢复原宿主系统栏。 */
    override fun onDestroy() { destroying = true; popup?.dismiss(); popup = null; page = null; planAdapter = null; super.onDestroy() }
}
