package com.wkq.dev.demo.google

import android.content.Context
import android.view.View
import android.widget.ArrayAdapter
import androidx.lifecycle.lifecycleScope
import com.google.firebase.FirebaseApp
import com.wkq.dev.demo.common.*
import com.wkq.dev.demo.google.databinding.DemoGoogleBinding
import com.wkq.google.auth.DropGoogleAccount
import com.wkq.google.auth.GoogleSignInCoordinator
import com.wkq.google.billing.*
import com.wkq.router.annotation.Route
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

@Route(path = "/google/core_google")
class GoogleActivity : DemoActivity<DemoGoogleBinding, GoogleViewModel>() {
    private var observer: AutoCloseable? = null
    override fun initView() {
        val b = contentBinding
        bindDemo(b.result, getString(R.string.demo_google_title), b.signin, b.firebase,
            b.session, b.signout, b.query, b.purchase, b.restore)
        b.result.inputs.visibility = View.GONE
        b.result.share.visibility = View.GONE
        b.result.open.visibility = View.GONE
        b.signin.setOnClickListener { login(false) }
        b.firebase.setOnClickListener { login(true) }
        b.session.setOnClickListener {
            viewModel.report(if (!firebaseReady()) getString(R.string.google_firebase_missing)
                else DropGoogleAccount.currentUser?.let { getString(R.string.google_session_result, it.email ?: it.uid) }
                    ?: getString(R.string.google_no_session))
        }
        b.signout.setOnClickListener {
            viewModel.execute(applicationContext) {
                if (firebaseReady()) DropGoogleAccount.signOut(applicationContext)
                else GoogleSignInCoordinator.signOut(applicationContext)
                DemoResult(getString(R.string.google_signed_out))
            }
        }
        b.query.setOnClickListener {
            viewModel.query(applicationContext, b.product.text.toString().trim(), b.subscription.isChecked)
        }
        b.restore.setOnClickListener { viewModel.restore(applicationContext) }
        b.purchase.setOnClickListener {
            val quote = viewModel.quotes.value.getOrNull(b.offers.selectedItemPosition)
            if (quote == null || !viewModel.matches(b.product.text.toString().trim(), b.subscription.isChecked)) {
                viewModel.report(getString(R.string.google_query_first)); return@setOnClickListener
            }
            confirm(getString(R.string.google_confirm, quote.title, quote.formattedPrice)) {
                if (!viewModel.state.value.busy && viewModel.matches(b.product.text.toString().trim(), b.subscription.isChecked)) {
                    val response = GoogleBillingManager.launchPurchase(this, quote.productId, quote.offerToken)
                    viewModel.report(getString(R.string.google_launch, response.responseCode))
                }
            }
        }
        lifecycleScope.launch {
            viewModel.quotes.collect { quotes ->
                b.offers.adapter = ArrayAdapter(this@GoogleActivity, android.R.layout.simple_spinner_dropdown_item,
                    quotes.map { "${it.title} · ${it.formattedPrice} ${it.billingPeriod}" })
            }
        }
    }
    private fun firebaseReady() = FirebaseApp.initializeApp(applicationContext) != null
    private fun login(firebase: Boolean) {
        val client = contentBinding.client.text.toString().trim()
        if (client.isBlank()) { viewModel.report(getString(R.string.google_config_missing)); return }
        if (firebase && !firebaseReady()) { viewModel.report(getString(R.string.google_firebase_missing)); return }
        viewModel.execute(applicationContext) {
            withContext(Dispatchers.Main) {
                if (firebase) {
                    DropGoogleAccount.signIn(this@GoogleActivity, client)
                    DemoResult(getString(R.string.google_session_result, DropGoogleAccount.currentUser?.email.orEmpty()))
                } else {
                    val account = GoogleSignInCoordinator.signIn(this@GoogleActivity, client).getOrThrow()
                    DemoResult(getString(R.string.google_login_result, account.email))
                }
            }
        }
    }
    override fun onStart() {
        super.onStart()
        observer = GoogleBillingManager.addPurchaseUpdatedListener { response, _ ->
            runOnUiThread {
                if (!isFinishing && !isDestroyed) viewModel.report(getString(R.string.google_updated,
                    response.responseCode, response.requiresPurchaseRecovery.toString()))
            }
        }
    }
    override fun onStop() { observer?.close(); observer = null; super.onStop() }
    override fun onDestroy() {
        // 登录涉及 Activity；旋转/离开时取消，避免 ViewModel 持有旧页面。
        viewModel.cancel()
        super.onDestroy()
    }
}

class GoogleViewModel : DemoViewModel() {
    private val mutableQuotes = MutableStateFlow<List<GoogleProduct>>(emptyList())
    val quotes = mutableQuotes.asStateFlow()
    private var queriedId = ""
    private var queriedSubscription = false
    fun matches(id: String, subscription: Boolean) = id == queriedId && subscription == queriedSubscription
    fun query(context: Context, id: String, subscription: Boolean) = execute(context) {
        mutableQuotes.value = emptyList()
        queriedId = ""
        if (id.isBlank() || id.contains(',')) return@execute DemoResult(context.getString(R.string.google_product_missing))
        val config = GoogleBillingRuntimeConfig(
            billingSubscriptionIds = if (subscription) listOf(id) else emptyList(),
            billingInAppProductIds = if (subscription) emptyList() else listOf(id),
            requireAppAccount = false
        )
        GoogleBillingRuntime.initialize(context, config)
        val products = withTimeout(20_000) {
            GoogleBillingManager.queryProducts(listOf(id), if (subscription) GoogleProductType.SUBS else GoogleProductType.IN_APP).getOrThrow()
        }
        queriedId = id
        queriedSubscription = subscription
        mutableQuotes.value = products
        DemoResult(context.getString(if (products.isEmpty()) R.string.google_empty else R.string.google_quotes, products.size))
    }
    fun restore(context: Context) = execute(context) {
        if (queriedId.isBlank()) return@execute DemoResult(context.getString(R.string.google_restore_first))
        val entitlement = withTimeout(25_000) { GoogleBillingManager.refreshEntitlement() }
        DemoResult(context.getString(R.string.google_restored, entitlement.state.name,
            entitlement.activeSubscriptionIds.joinToString(), entitlement.ownedOneTimeProductIds.joinToString(),
            entitlement.hasPendingPurchase.toString()))
    }
}
