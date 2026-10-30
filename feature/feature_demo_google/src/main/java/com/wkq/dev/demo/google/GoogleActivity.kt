package com.wkq.dev.demo.google

import android.content.Context
import android.view.View
import android.widget.ArrayAdapter
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import com.google.firebase.FirebaseApp
import com.wkq.dev.demo.common.*
import com.wkq.dev.demo.google.databinding.DemoGoogleBinding
import com.wkq.google.auth.DropGoogleAccount
import com.wkq.google.auth.GoogleSignInCoordinator
import com.wkq.google.billing.*
import com.wkq.google.billing.ui.*
import com.wkq.google.rate.*
import com.wkq.util.IntentUtil
import com.wkq.router.annotation.Route
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Google 能力宿主页面，编排登录、Firebase 会话及商品查询购买，商业配置由页面输入提供。 */
@Route(path = "/google/core_google")
class GoogleActivity : DemoActivity<DemoGoogleBinding, GoogleViewModel>() {
    private var observer: AutoCloseable? = null
    private val billingPage = registerForActivityResult(GoogleBillingContract()) { result ->
        viewModel.report(getString(when {
            !result.status.isKnown -> R.string.google_page_unknown
            result.status.isPro -> R.string.google_page_pro
            else -> R.string.google_page_free
        }))
    }

    /** 绑定登录与计费操作，购买前核对查询配置并确认报价，订阅报价与购买恢复状态。 */
    override fun initView() {
        val b = contentBinding
        bindDemo(
            b.result,
            getString(R.string.demo_google_title),
            b.signin,
            b.firebase,
            b.session,
            b.signout,
            b.query,
            b.purchase,
            b.restore,
            b.rateCheck, b.rateRecord, b.rateSuppress, b.rateResume, b.rateStore, b.feedback,
        )
        b.result.inputs.visibility = View.GONE
        b.result.share.visibility = View.GONE
        b.result.open.visibility = View.GONE
        b.rateCheck.setOnClickListener { viewModel.checkRate(applicationContext) }
        b.rateRecord.setOnClickListener { recordRateAction() }
        b.rateSuppress.setOnClickListener { viewModel.suppressRate(applicationContext, true) }
        b.rateResume.setOnClickListener { viewModel.suppressRate(applicationContext, false) }
        b.rateStore.setOnClickListener {
            viewModel.report(getString(if (IntentUtil.openMarket(this)) R.string.google_rate_store_opened else R.string.google_rate_store_missing))
        }
        b.feedback.setOnClickListener {
            val email = b.feedbackEmail.text.toString().trim()
            if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                viewModel.report(getString(R.string.google_rate_email_invalid))
            } else {
                val opened = IntentUtil.sendEmail(this, email, getString(R.string.google_rate_feedback_subject))
                viewModel.report(getString(if (opened) R.string.google_rate_email_opened else R.string.google_rate_email_missing))
            }
        }
        b.signin.setOnClickListener { login(false) }
        b.firebase.setOnClickListener { login(true) }
        b.session.setOnClickListener {
            viewModel.report(
                if (!firebaseReady()) getString(R.string.google_firebase_missing)
                else
                    DropGoogleAccount.currentUser?.let {
                        getString(R.string.google_session_result, it.email ?: it.uid)
                    } ?: getString(R.string.google_no_session)
            )
        }
        b.signout.setOnClickListener {
            viewModel.execute(applicationContext) {
                if (firebaseReady()) DropGoogleAccount.signOut(applicationContext)
                else GoogleSignInCoordinator.signOut(applicationContext)
                DemoResult(getString(R.string.google_signed_out))
            }
        }
        b.query.setOnClickListener {
            viewModel.query(
                applicationContext,
                b.product.text.toString().trim(),
                b.subscription.isChecked,
            )
        }
        b.restore.setOnClickListener { viewModel.restore(applicationContext) }
        b.billingPage.setOnClickListener { openBillingPage(false) }
        b.billingSheet.setOnClickListener { openBillingPage(true) }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.billingStatus.collect { state ->
                    b.billingStatus.text =
                        when (state) {
                            PurchaseRecoveryState.Idle -> ""
                            PurchaseRecoveryState.Loading -> getString(R.string.google_recovering)
                            PurchaseRecoveryState.Failed ->
                                getString(R.string.google_recovery_failed)
                            is PurchaseRecoveryState.Ready ->
                                state.entitlement.let {
                                    getString(
                                        R.string.google_restored,
                                        it.state.name,
                                        it.activeSubscriptionIds.joinToString(),
                                        it.ownedOneTimeProductIds.joinToString(),
                                        it.hasPendingPurchase.toString(),
                                    )
                                }
                        }
                }
            }
        }
        b.purchase.setOnClickListener {
            val quote = viewModel.quotes.value.getOrNull(b.offers.selectedItemPosition)
            if (
                quote == null ||
                    !viewModel.matches(b.product.text.toString().trim(), b.subscription.isChecked)
            ) {
                viewModel.report(getString(R.string.google_query_first))
                return@setOnClickListener
            }
            confirm(getString(R.string.google_confirm, quote.title, quote.formattedPrice)) {
                if (
                    !viewModel.state.value.busy &&
                        viewModel.matches(
                            b.product.text.toString().trim(),
                            b.subscription.isChecked,
                        )
                ) {
                    val response =
                        GoogleBillingManager.launchPurchase(this, quote.productId, quote.offerToken)
                    viewModel.report(getString(R.string.google_launch, response.responseCode))
                }
            }
        }
        lifecycleScope.launch {
            viewModel.quotes.collect { quotes ->
                b.offers.adapter =
                    ArrayAdapter(
                        this@GoogleActivity,
                        android.R.layout.simple_spinner_dropdown_item,
                        quotes.map { "${it.title} · ${it.formattedPrice} ${it.billingPeriod}" },
                    )
            }
        }
    }

    /** 使用页面输入显式配置测试商品，再调用单库原生支付入口，不自动发起购买。 */
    private fun openBillingPage(sheet: Boolean) {
        val id = contentBinding.product.text.toString().trim()
        if (id.isBlank() || id.contains(',')) {
            viewModel.report(getString(R.string.google_product_missing))
            return
        }
        val subscription = contentBinding.subscription.isChecked
        GoogleBillingRuntime.initialize(applicationContext, GoogleBillingRuntimeConfig(
            billingSubscriptionIds = if (subscription) listOf(id) else emptyList(),
            billingInAppProductIds = if (subscription) emptyList() else listOf(id),
            requireAppAccount = false,
        ))
        if (sheet) GoogleBillingUi.showBottomSheet(billingPage) else GoogleBillingUi.openActivity(billingPage)
    }

    /** 显式模拟宿主关键操作完成，再按正式节流策略请求；不强制显示、不先收集星级。 */
    private fun recordRateAction() {
        viewModel.execute(applicationContext) {
            GoogleRateManager.recordCompletedAction(applicationContext, "workbench-${java.util.UUID.randomUUID()}")
            val result = withContext(Dispatchers.Main) { GoogleRateManager.requestIfEligible(this@GoogleActivity) }
            DemoResult(viewModel.reviewResultText(applicationContext, result))
        }
    }

    /** 尝试从宿主资源初始化 Firebase，返回是否具备有效应用实例。 */
    private fun firebaseReady() = FirebaseApp.initializeApp(applicationContext) != null

    /** 校验客户端配置后在主线程发起 Google 或 Firebase 登录，错误通过公共执行流程展示。 */
    private fun login(firebase: Boolean) {
        val client = contentBinding.client.text.toString().trim()
        if (client.isBlank()) {
            viewModel.report(getString(R.string.google_config_missing))
            return
        }
        if (firebase && !firebaseReady()) {
            viewModel.report(getString(R.string.google_firebase_missing))
            return
        }
        viewModel.execute(applicationContext) {
            withContext(Dispatchers.Main) {
                if (firebase) {
                    DropGoogleAccount.signIn(this@GoogleActivity, client)
                    DemoResult(
                        getString(
                            R.string.google_session_result,
                            DropGoogleAccount.currentUser?.email.orEmpty(),
                        )
                    )
                } else {
                    val account =
                        GoogleSignInCoordinator.signIn(this@GoogleActivity, client).getOrThrow()
                    DemoResult(getString(R.string.google_login_result, account.email))
                }
            }
        }
    }

    /** 注册购买更新监听并触发状态核对，回调仅在页面仍有效时更新提示。 */
    override fun onStart() {
        super.onStart()
        observer =
            GoogleBillingManager.addPurchaseUpdatedListener { response, _ ->
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        viewModel.report(
                            getString(
                                R.string.google_updated,
                                response.responseCode,
                                response.requiresPurchaseRecovery.toString(),
                            )
                        )
                        viewModel.reconcile()
                    }
                }
            }
        viewModel.reconcile()
    }

    /** 关闭购买更新监听并清空引用，避免不可见页面持续接收回调。 */
    override fun onStop() {
        observer?.close()
        observer = null
        super.onStop()
    }

    /** 取消可能持有 Activity 的登录任务，再交由基类清理页面。 */
    override fun onDestroy() {
        // 登录涉及 Activity；旋转/离开时取消，避免 ViewModel 持有旧页面。
        viewModel.cancel()
        super.onDestroy()
    }
}

/** 管理商品报价和配置匹配，复用购买恢复协调器核对 Play 状态，不实现宿主 Pro 策略。 */
class GoogleViewModel : DemoViewModel() {
    private val mutableQuotes = MutableStateFlow<List<GoogleProduct>>(emptyList())
    val quotes = mutableQuotes.asStateFlow()
    private val recovery =
        PurchaseStateRecovery(viewModelScope) { GoogleBillingManager.refreshEntitlement() }
    internal val billingStatus = recovery.state
    private var queriedId = ""
    private var queriedSubscription = false

    /** 查询真实本地节流状态，纯诊断不调用 Play、不增加请求次数。 */
    fun checkRate(context: Context) = execute(context, record = false) {
        val state = GoogleRateManager.snapshot(context)
        val decision = GoogleRateManager.evaluate(context)
        DemoResult(context.getString(R.string.google_rate_state, state.completedActions, state.requestCount,
            rateDecisionText(context, decision)))
    }

    /** 设置或恢复自动提示偏好，不影响独立手动商店及反馈入口。 */
    fun suppressRate(context: Context, suppressed: Boolean) = execute(context, record = false) {
        GoogleRateManager.setSuppressed(context, suppressed)
        DemoResult(context.getString(if (suppressed) R.string.google_rate_suppressed else R.string.google_rate_resumed))
    }

    /** 结果只表达流程和节流，不把 Task 完成误报为已评分。 */
    fun reviewResultText(context: Context, result: GoogleReviewResult): String = when (result.status) {
        GoogleReviewStatus.FLOW_FINISHED -> context.getString(R.string.google_rate_finished)
        GoogleReviewStatus.NOT_ELIGIBLE -> rateDecisionText(context, requireNotNull(result.decision))
        GoogleReviewStatus.UNAVAILABLE -> context.getString(R.string.google_rate_unavailable)
        GoogleReviewStatus.IN_PROGRESS -> context.getString(R.string.google_rate_in_progress)
        GoogleReviewStatus.HOST_INACTIVE -> context.getString(R.string.google_rate_host_inactive)
    }

    /** 将库的领域枚举映射为宿主语言资源，避免展示英文内部标识。 */
    private fun rateDecisionText(context: Context, decision: GoogleRateDecision) = context.getString(when (decision) {
        GoogleRateDecision.ELIGIBLE -> R.string.google_rate_eligible
        GoogleRateDecision.SUPPRESSED -> R.string.google_rate_suppressed
        GoogleRateDecision.CLOCK_INVALID -> R.string.google_rate_clock_invalid
        GoogleRateDecision.NO_COMPLETED_ACTION -> R.string.google_rate_no_action
        GoogleRateDecision.TOO_EARLY -> R.string.google_rate_too_early
        GoogleRateDecision.COOLDOWN -> R.string.google_rate_cooldown
    })

    /** 判断当前商品 ID 和订阅类型是否与最近成功查询的配置一致。 */
    fun matches(id: String, subscription: Boolean) =
        id == queriedId && subscription == queriedSubscription

    /** 清空旧报价并按单个商品配置初始化计费，在 20 秒超时内查询报价，同时启动购买状态恢复。 */
    fun query(context: Context, id: String, subscription: Boolean) =
        execute(context) {
            mutableQuotes.value = emptyList()
            queriedId = ""
            if (id.isBlank() || id.contains(','))
                return@execute DemoResult(context.getString(R.string.google_product_missing))
            val config =
                GoogleBillingRuntimeConfig(
                    billingSubscriptionIds = if (subscription) listOf(id) else emptyList(),
                    billingInAppProductIds = if (subscription) emptyList() else listOf(id),
                    requireAppAccount = false,
                )
            GoogleBillingRuntime.initialize(context, config)
            recovery.configure()
            reconcile()
            val products =
                withTimeout(20_000) {
                    GoogleBillingManager.queryProducts(
                            listOf(id),
                            if (subscription) GoogleProductType.SUBS else GoogleProductType.IN_APP,
                        )
                        .getOrThrow()
                }
            queriedId = id
            queriedSubscription = subscription
            mutableQuotes.value = products
            DemoResult(
                context.getString(
                    if (products.isEmpty()) R.string.google_empty else R.string.google_quotes,
                    products.size,
                )
            )
        }

    /** 已配置计费时重新核对 Play 状态，否则提示用户先配置商品。 */
    fun restore(context: Context) {
        if (!recovery.configured) report(context.getString(R.string.google_restore_first))
        else reconcile()
    }

    /** 每次回到前台或收到购买回调都重新核对 Play 状态。 */
    fun reconcile() = recovery.refresh()
}
