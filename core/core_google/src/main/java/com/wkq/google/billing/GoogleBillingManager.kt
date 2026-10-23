package com.wkq.google.billing

import android.app.Activity
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.wkq.google.model.SubscriptionStatus
import com.wkq.google.billing.ui.GoogleSubscriptionReplacementMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

/**
 * Google Play Billing 统一入口。
 */
object GoogleBillingManager : GoogleBillingGateway {

    private const val TAG = "SiteReportBilling"
    private const val PREF_NAME = "google_billing_state"
    private const val KEY_PENDING_ACKNOWLEDGEMENT_TOKENS = "pending_acknowledgement_tokens"
    private const val ACKNOWLEDGEMENT_MAX_ATTEMPTS = 3

    private val productCache = ConcurrentHashMap<String, ProductDetails>()
    private val clientLock = Any()
    private val entitlementMutex = Mutex()
    private val connectionMutex = Mutex()
    private val purchaseUpdateObservers = java.util.concurrent.CopyOnWriteArraySet<(GoogleBillingResponse, List<GooglePurchase>) -> Unit>()
    private val purchaseProcessingMutex = Mutex()
    private val billingScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var cachedEntitlement: CachedBillingEntitlement? = null
    @Volatile private var forceEntitlementRefresh = true

    @Volatile
    private var billingClient: BillingClient? = null

    @Volatile
    private var sandboxFallbackEnabled: Boolean = false

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var purchaseUpdatedListener: ((GoogleBillingResponse, List<GooglePurchase>) -> Unit)? = null

    @Volatile
    private var activeSubscriptionPurchase: GooglePurchase? = null

    private val purchasesUpdatedListener = PurchasesUpdatedListener { result, purchases ->
        forceEntitlementRefresh = true
        val response = result.toGoogleResponse()
        val googlePurchases = purchases.orEmpty().map { it.toGooglePurchase() }
        debugLog(
            "purchasesUpdated code=${result.responseCode} message=${result.debugMessage} " +
                "purchases=${googlePurchases.joinToString { purchase -> purchase.products.joinToString("|") }}"
        )
        // 回调不依赖支付页生命周期，页面销毁后仍必须完成订单确认。
        billingScope.launch {
            val requiresRecovery = reconcilePurchaseUpdate(response, googlePurchases)
            if (response.isSuccess && googlePurchases.isNotEmpty()) queryEntitlement()
            val reconciled = response.copy(requiresPurchaseRecovery = requiresRecovery)
            purchaseUpdatedListener?.invoke(reconciled, googlePurchases)
            purchaseUpdateObservers.forEach { it(reconciled, googlePurchases) }
        }
    }

    fun initialize(context: Context) {
        appContext = context.applicationContext
        sandboxFallbackEnabled = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        debugLog(
            "initialize debuggable=$sandboxFallbackEnabled package=${context.packageName} " +
                "version=${context.versionNameCode()} installer=${context.installerPackageNameCompat()} " +
                "signatureSha256=${context.signingCertSha256()}"
        )
        if (billingClient != null) return
        synchronized(clientLock) {
            if (billingClient != null) return
            billingClient = BillingClient.newBuilder(context.applicationContext)
                .setListener(purchasesUpdatedListener)
                .enablePendingPurchases(
                    PendingPurchasesParams.newBuilder()
                        .enableOneTimeProducts()
                        .build()
                )
                .enableAutoServiceReconnection()
                .build()
        }
    }

    fun setPurchaseUpdatedListener(
        listener: ((GoogleBillingResponse, List<GooglePurchase>) -> Unit)?
    ) {
        purchaseUpdatedListener = listener
    }

    fun addPurchaseUpdatedListener(listener: (GoogleBillingResponse, List<GooglePurchase>) -> Unit): AutoCloseable {
        purchaseUpdateObservers.add(listener)
        return AutoCloseable { purchaseUpdateObservers.remove(listener) }
    }

    suspend fun connect(context: Context? = null): GoogleBillingResponse = connectionMutex.withLock { connectInternal(context) }

    private suspend fun connectInternal(context: Context?): GoogleBillingResponse {
        context?.let { initialize(it) }
        val client = billingClient
            ?: return GoogleBillingResponse(
                isSuccess = false,
                responseCode = BillingClient.BillingResponseCode.ERROR,
                message = "BillingClient is not initialized."
            )
        if (client.isReady) {
            debugLog("connect already ready")
            return GoogleBillingResponse(true, BillingClient.BillingResponseCode.OK)
        }
        debugLog("connect start")
        return suspendCancellableCoroutine { continuation ->
            client.startConnection(object : BillingClientStateListener {
                override fun onBillingServiceDisconnected() {
                    debugLog("connect disconnected")
                }

                override fun onBillingSetupFinished(result: BillingResult) {
                    debugLog("connect finished code=${result.responseCode} message=${result.debugMessage}")
                    if (continuation.isActive) {
                        continuation.resume(result.toGoogleResponse())
                    }
                }
            })
        }
    }

    suspend fun queryProducts(
        productIds: List<String>,
        productType: String
    ): Result<List<GoogleProduct>> {
        val client = billingClient
            ?: return Result.failure(IllegalStateException("BillingClient is not initialized."))
        val connectResult = connect()
        if (!connectResult.isSuccess) {
            return Result.failure(IllegalStateException(connectResult.message))
        }
        if (productIds.isEmpty()) {
            debugLog("queryProducts skip empty productIds type=$productType")
            return Result.success(emptyList())
        }
        debugLog("queryProducts request type=$productType ids=${productIds.joinToString()}")

        return suspendCancellableCoroutine { continuation ->
            val products = productIds.map { productId ->
                QueryProductDetailsParams.Product.newBuilder()
                    .setProductId(productId)
                    .setProductType(productType)
                    .build()
            }
            val params = QueryProductDetailsParams.newBuilder()
                .setProductList(products)
                .build()
            client.queryProductDetailsAsync(params) { billingResult, queryResult ->
                val response = billingResult.toGoogleResponse()
                debugLog(
                    "queryProducts result type=$productType code=${billingResult.responseCode} " +
                        "message=${billingResult.debugMessage} count=${queryResult.productDetailsList.size} " +
                        "unfetched=${queryResult.unfetchedProductList.joinToString { product ->
                            "${product.productId}:${product.statusCode}"
                        }}"
                )
                if (!response.isSuccess) {
                    if (continuation.isActive) {
                        continuation.resume(Result.failure(IllegalStateException(response.message)))
                    }
                    return@queryProductDetailsAsync
                }
                val productDetails = queryResult.productDetailsList
                productDetails.forEach { details ->
                    val subscriptionOffers = details.subscriptionOfferDetails.orEmpty()
                        .joinToString { offer -> "${offer.basePlanId}:${offer.offerToken.take(8)}..." }
                    val oneTimeOffers = details.oneTimePurchaseOfferDetailsList.orEmpty()
                        .joinToString { offer ->
                            "${offer.purchaseOptionId.orEmpty()}:${offer.offerToken.orEmpty().take(8)}..."
                        }
                    debugLog(
                        "productDetails id=${details.productId} type=${details.productType} " +
                            "title=${details.title} subscriptionOffers=$subscriptionOffers " +
                            "oneTimeOffers=$oneTimeOffers"
                    )
                }
                productDetails.forEach { productCache[it.productId] = it }
                if (continuation.isActive) {
                    continuation.resume(Result.success(productDetails.flatMap { it.toGoogleProducts() }))
                }
            }
        }
    }

    suspend fun queryConfiguredCatalog(): Result<GoogleBillingCatalog> {
        val config = GoogleBillingRuntime.currentConfig()
        if (config.billingSubscriptionIds.isEmpty() && config.billingInAppProductIds.isEmpty()) {
            return Result.success(GoogleBillingCatalog())
        }
        // 串行连接 Play，分别保留两类结果；不并发重复 startConnection。
        val subscriptions = if (config.billingSubscriptionIds.isEmpty()) Result.success(emptyList()) else
            withTimeoutOrNull(10_000) { queryProducts(config.billingSubscriptionIds, GoogleProductType.SUBS) }
                ?: Result.failure(IllegalStateException("Subscription catalog timed out"))
        val oneTime = if (config.billingInAppProductIds.isEmpty()) Result.success(emptyList()) else
            withTimeoutOrNull(10_000) { queryProducts(config.billingInAppProductIds, GoogleProductType.IN_APP) }
                ?: Result.failure(IllegalStateException("One-time catalog timed out"))
        return combineBillingCatalog(subscriptions, oneTime)
    }

    fun launchPurchase(
        activity: Activity,
        productId: String,
        offerToken: String = ""
    ): GoogleBillingResponse {
        if (!GoogleBillingRuntime.requireConfig().billingPurchaseAllowed()) {
            return GoogleBillingResponse(
                isSuccess = false,
                responseCode = BillingClient.BillingResponseCode.DEVELOPER_ERROR,
                message = "Sign in before purchasing."
            )
        }
        val client = billingClient
            ?: return GoogleBillingResponse(
                isSuccess = false,
                responseCode = BillingClient.BillingResponseCode.ERROR,
                message = "BillingClient is not initialized."
            )
        if (!client.isReady) {
            return GoogleBillingResponse(
                isSuccess = false,
                responseCode = BillingClient.BillingResponseCode.SERVICE_DISCONNECTED,
                message = "Billing service is not connected."
            )
        }

        // 拆分 productId 以支持 "realProductId:basePlanId"
        val parts = productId.split(":")
        val realProductId = parts[0]

        val productDetails = productCache[realProductId]
            ?: return GoogleBillingResponse(
                isSuccess = false,
                responseCode = BillingClient.BillingResponseCode.ERROR,
                message = "Query product details before launching purchase."
            )
        if (productDetails.productType == GoogleProductType.SUBS) {
            val featureResult = client.isFeatureSupported(BillingClient.FeatureType.SUBSCRIPTIONS)
            if (featureResult.responseCode != BillingClient.BillingResponseCode.OK) {
                return featureResult.toGoogleResponse()
            }
        }
        val config = GoogleBillingRuntime.requireConfig()
        val allowedProductIds = config.billingSubscriptionIds + config.billingInAppProductIds
        if (realProductId !in allowedProductIds) {
            return GoogleBillingResponse(
                isSuccess = false,
                responseCode = BillingClient.BillingResponseCode.DEVELOPER_ERROR,
                message = "Product is not configured."
            )
        }
        debugLog(
            "launchPurchase request productId=$productId realProductId=$realProductId " +
                "inputOfferToken=${offerToken.take(8)}... cacheHit=true"
        )
        val productDetailsParamsBuilder = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(productDetails)

        val resolvedOfferToken = offerToken.ifBlank {
            if (parts.size > 1) {
                val basePlanId = parts[1]
                productDetails.subscriptionOfferDetails?.firstOrNull { it.basePlanId == basePlanId }?.offerToken.orEmpty()
            } else {
                productDetails.defaultOfferToken()
            }
        }

        val availableOfferTokens = when (productDetails.productType) {
            GoogleProductType.SUBS -> productDetails.subscriptionOfferDetails
                .orEmpty()
                .map { it.offerToken }
            GoogleProductType.IN_APP -> productDetails.oneTimePurchaseOfferDetailsList
                .orEmpty()
                .filter { offer -> config.billingOneTimePurchaseOptionIds[realProductId]
                    ?.let { it == offer.purchaseOptionId } ?: true }
                .mapNotNull { it.offerToken }
                .ifEmpty { if (realProductId in config.billingOneTimePurchaseOptionIds) emptyList()
                    else listOfNotNull(productDetails.oneTimePurchaseOfferDetails?.offerToken) }
            else -> emptyList()
        }
        if (productDetails.productType == GoogleProductType.IN_APP &&
            realProductId in config.billingOneTimePurchaseOptionIds &&
            resolvedOfferToken !in availableOfferTokens) {
            return GoogleBillingResponse(isSuccess = false,
                responseCode = BillingClient.BillingResponseCode.ITEM_UNAVAILABLE,
                message = "The configured purchase option is not available.")
        }
        if (availableOfferTokens.isNotEmpty() &&
            (resolvedOfferToken.isBlank() || resolvedOfferToken !in availableOfferTokens)
        ) {
            return GoogleBillingResponse(
                isSuccess = false,
                responseCode = BillingClient.BillingResponseCode.ITEM_UNAVAILABLE,
                message = "The selected Google Play offer is no longer available."
            )
        }

        if (resolvedOfferToken.isNotBlank()) {
            productDetailsParamsBuilder.setOfferToken(resolvedOfferToken)
        }
        // 同商品基础方案切换由 Play Console 的默认替换规则处理；不能套用跨商品 DEFERRED。
        val oldSubscription = activeSubscriptionPurchase
            ?.takeIf { productDetails.productType == GoogleProductType.SUBS }
            ?.takeIf { old -> old.products.none { it == realProductId } }
        if (oldSubscription != null) {
            val oldProductId = oldSubscription.products.firstOrNull()
            if (!oldProductId.isNullOrBlank()) {
                val replacementMode = resolveReplacementMode(oldProductId, realProductId)
                val replacementParams =
                    BillingFlowParams.ProductDetailsParams.SubscriptionProductReplacementParams
                        .newBuilder()
                        .setOldProductId(oldProductId)
                        .setReplacementMode(replacementMode)
                        .build()
                productDetailsParamsBuilder
                    .setSubscriptionProductReplacementParams(replacementParams)
            }
        }
        debugLog(
            "launchPurchase resolved productId=$productId resolvedOfferToken=${resolvedOfferToken.take(8)}... " +
                "availableOffers=${productDetails.subscriptionOfferDetails.orEmpty().joinToString { it.basePlanId }}"
        )
        val flowParamsBuilder = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(productDetailsParamsBuilder.build()))
        val obfuscatedAccountId = preferredObfuscatedAccountId()
        if (obfuscatedAccountId.isNotBlank()) {
            flowParamsBuilder.setObfuscatedAccountId(obfuscatedAccountId)
        }
        if (oldSubscription != null) {
            flowParamsBuilder.setSubscriptionUpdateParams(
                BillingFlowParams.SubscriptionUpdateParams.newBuilder()
                    .setOldPurchaseToken(oldSubscription.purchaseToken)
                    .build()
            )
        }
        val flowParams = flowParamsBuilder.build()
        val result = client.launchBillingFlow(activity, flowParams)
        debugLog("launchPurchase result code=${result.responseCode} message=${result.debugMessage}")
        return result.toGoogleResponse()
    }

    suspend fun queryActivePurchases(
        productType: String = GoogleProductType.SUBS
    ): Result<List<GooglePurchase>> {
        val client = billingClient
            ?: return Result.failure(IllegalStateException("BillingClient is not initialized."))
        val connectResult = connect()
        if (!connectResult.isSuccess) {
            return Result.failure(IllegalStateException(connectResult.message))
        }
        return suspendCancellableCoroutine { continuation ->
            val params = QueryPurchasesParams.newBuilder()
                .setProductType(productType)
                .build()
            client.queryPurchasesAsync(params) { billingResult, purchases ->
                val response = billingResult.toGoogleResponse()
                debugLog(
                    "queryActivePurchases type=$productType code=${billingResult.responseCode} " +
                        "message=${billingResult.debugMessage} count=${purchases.size}"
                )
                if (continuation.isActive) {
                    if (response.isSuccess) {
                        continuation.resume(Result.success(purchases.map { it.toGooglePurchase() }))
                    } else {
                        continuation.resume(Result.failure(IllegalStateException(response.message)))
                    }
                }
            }
        }
    }

    suspend fun acknowledgePurchase(purchaseToken: String): GoogleBillingResponse {
        val client = billingClient
            ?: return GoogleBillingResponse(
                isSuccess = false,
                responseCode = BillingClient.BillingResponseCode.ERROR,
                message = "BillingClient is not initialized."
            )
        val connectResult = connect()
        if (!connectResult.isSuccess) {
            return connectResult
        }
        val response = suspendCancellableCoroutine { continuation ->
            val params = AcknowledgePurchaseParams.newBuilder()
                .setPurchaseToken(purchaseToken)
                .build()
            client.acknowledgePurchase(params) { result ->
                if (continuation.isActive) {
                    continuation.resume(result.toGoogleResponse())
                }
            }
        }
        if (response.isSuccess) {
            removePendingAcknowledgement(purchaseToken)
        } else {
            addPendingAcknowledgement(purchaseToken)
        }
        return response
    }

    suspend fun refreshEntitlement(): GoogleBillingEntitlement {
        forceEntitlementRefresh = true
        return queryEntitlement()
    }

    /** 本地功能入口只读取权益，不启动 Play 连接或要求联网。 */
    suspend fun offlineEntitlement(): GoogleBillingEntitlement = entitlementMutex.withLock {
        val snapshot = cachedEntitlement ?: appContext?.let { GoogleBillingEntitlementCache.read(it) }
        cachedEntitlement = snapshot
        if (snapshot?.usableNow(entitlementCacheScope(), System.currentTimeMillis()) == true)
            snapshot.entitlement.copy(source = "google_play_offline_cache")
        else GoogleBillingEntitlement()
    }

    private fun entitlementCacheScope(): String = if (GoogleBillingRuntime.currentConfig().requireAppAccount)
        legacyObfuscatedAccountId() else "installation:play"

    private fun CachedBillingEntitlement.usableNow(scope: String, now: Long): Boolean {
        val config = GoogleBillingRuntime.currentConfig()
        return usableFor(scope, now, config.offlineSubscriptionGraceMillis, config.retainLifetimeOffline)
    }

    override suspend fun queryEntitlement(): GoogleBillingEntitlement = entitlementMutex.withLock {
        val context = appContext
        val account = entitlementCacheScope()
        val now = System.currentTimeMillis()
        val cached = cachedEntitlement ?: context?.let { GoogleBillingEntitlementCache.read(it) }
        cachedEntitlement = cached
        if (!forceEntitlementRefresh && cached?.usableNow(account, now) == true && now - cached.checkedAt < 60_000L) {
            return@withLock cached.entitlement
        }
        forceEntitlementRefresh = false
        val result = try {
            withTimeoutOrNull(10_000L) { queryFreshEntitlement() }
                ?: GoogleBillingEntitlement(errorMessage = "Billing query timed out")
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            GoogleBillingEntitlement(errorMessage = error.message.orEmpty())
        }
        // 登录账号在异步查询期间改变时，不使用或保存前一账号结果。
        if (account != entitlementCacheScope()) return@withLock GoogleBillingEntitlement()
        val completedAt = System.currentTimeMillis()
        if (result.isKnown && result.source == "google_play" && account.isNotBlank()) {
            val snapshot = CachedBillingEntitlement(account, completedAt, result)
            cachedEntitlement = snapshot
            if (context != null) {
                try {
                    GoogleBillingEntitlementCache.write(context, snapshot)
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    GoogleBillingEntitlementCache.clear(context)
                    Log.w(TAG, "Unable to persist offline entitlement")
                }
            }
        }
        // 查询期间可能跨过离线宽限边界，必须按返回时刻重新判断。
        if (!result.isKnown && cached?.usableNow(account, completedAt) == true) {
            cached.entitlement.copy(source = "google_play_offline_cache")
        } else result
    }

    private suspend fun queryFreshEntitlement(): GoogleBillingEntitlement {
        val subscriptionResult = queryActivePurchases(GoogleProductType.SUBS)
        val oneTimeResult = queryActivePurchases(GoogleProductType.IN_APP)
        val queryError = subscriptionResult.exceptionOrNull() ?: oneTimeResult.exceptionOrNull()
        val config = GoogleBillingRuntime.requireConfig()
        val acceptedAccountIds = currentObfuscatedAccountIds()
        val subscriptions = subscriptionResult.getOrDefault(emptyList())
            .filterPurchased()
            .filterAllowedProducts(config.billingSubscriptionIds)
            .filterCurrentAccountPurchases(acceptedAccountIds, config.requireAppAccount)
        val oneTimeProducts = oneTimeResult.getOrDefault(emptyList())
            .filterPurchased()
            .filterAllowedProducts(config.billingInAppProductIds)
            .filterCurrentAccountPurchases(acceptedAccountIds, config.requireAppAccount)
        activeSubscriptionPurchase = subscriptions.maxByOrNull { it.purchaseTimeMillis }

        // 确认购买可在下次查询继续重试，不让确认超时抹掉已查明的权益。
        withTimeoutOrNull(2_000L) { acknowledgeIfNeeded(subscriptions + oneTimeProducts) }

        val activeSubscriptionIds = subscriptions.flatMap { it.products }.distinct().toMutableList()
        val ownedOneTimeProductIds = oneTimeProducts.flatMap { it.products }.distinct().toMutableList()

        val pending = (subscriptionResult.getOrDefault(emptyList()).filterAllowedProducts(config.billingSubscriptionIds)
            + oneTimeResult.getOrDefault(emptyList()).filterAllowedProducts(config.billingInAppProductIds))
            .filterCurrentAccountPurchases(acceptedAccountIds, config.requireAppAccount)
            .any { it.purchaseState == Purchase.PurchaseState.PENDING }
        return combinePlayEntitlement(activeSubscriptionIds, ownedOneTimeProductIds, queryError?.let { it.message ?: "Google Play query failed" })
            .copy(hasPendingPurchase = pending)
    }

    override suspend fun querySubscriptionStatus(userId: String): SubscriptionStatus {
        val entitlement = queryEntitlement()
        return SubscriptionStatus(
            isActive = entitlement.isPro,
            expireTimeMillis = 0L,
            planId = entitlement.activePlanId,
            source = entitlement.source
        )
    }

    fun endConnection() {
        productCache.clear()
        synchronized(clientLock) {
            billingClient?.endConnection()
            billingClient = null
        }
    }

    /**
     * 应用进入前台时补查 Play 订单，并继续确认上次中断的订单。
     */
    fun refreshPurchasesInBackground() {
        forceEntitlementRefresh = true
        billingScope.launch {
            val entitlement = queryEntitlement()
            debugLog(
                "foreground reconcile known=${entitlement.isKnown} source=${entitlement.source} " +
                    "pendingAcknowledgements=${pendingAcknowledgementTokens().size}"
            )
        }
    }

    private suspend fun acknowledgeIfNeeded(purchases: List<GooglePurchase>) {
        purchases.filterNot { it.isAcknowledged }
            .forEach { addPendingAcknowledgement(it.purchaseToken) }
        pendingAcknowledgementTokens().forEach { purchaseToken ->
            acknowledgeWithRetry(purchaseToken)
        }
    }

    private suspend fun reconcilePurchaseUpdate(
        response: GoogleBillingResponse,
        purchases: List<GooglePurchase>
    ): Boolean = purchaseProcessingMutex.withLock {
        if (!response.isSuccess) return@withLock false
        val completedPurchases = purchases.filterPurchased()
        if (completedPurchases.isEmpty()) return@withLock false

        acknowledgeIfNeeded(completedPurchases)
        forceEntitlementRefresh = true
        completedPurchases.any { purchase ->
            purchase.purchaseToken in pendingAcknowledgementTokens()
        }
    }

    private suspend fun acknowledgeWithRetry(purchaseToken: String) {
        repeat(ACKNOWLEDGEMENT_MAX_ATTEMPTS) { attempt ->
            val response = acknowledgePurchase(purchaseToken)
            if (response.isSuccess ||
                response.responseCode == BillingClient.BillingResponseCode.ITEM_NOT_OWNED
            ) {
                removePendingAcknowledgement(purchaseToken)
                return
            }
            if (attempt < ACKNOWLEDGEMENT_MAX_ATTEMPTS - 1) {
                delay(250L * (attempt + 1))
            }
        }
    }

    private fun addPendingAcknowledgement(purchaseToken: String) {
        updatePendingAcknowledgements { it + purchaseToken }
    }

    private fun removePendingAcknowledgement(purchaseToken: String) {
        updatePendingAcknowledgements { it - purchaseToken }
    }

    private fun pendingAcknowledgementTokens(): Set<String> {
        return appContext?.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            ?.getStringSet(KEY_PENDING_ACKNOWLEDGEMENT_TOKENS, emptySet())
            ?.toSet()
            .orEmpty()
    }

    private fun updatePendingAcknowledgements(transform: (Set<String>) -> Set<String>) {
        val preferences = appContext?.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE) ?: return
        synchronized(clientLock) {
            val current = preferences
                .getStringSet(KEY_PENDING_ACKNOWLEDGEMENT_TOKENS, emptySet())
                ?.toSet()
                .orEmpty()
            preferences.edit()
                .putStringSet(KEY_PENDING_ACKNOWLEDGEMENT_TOKENS, transform(current))
                .apply()
        }
    }

    private fun resolveReplacementMode(oldProductId: String, newProductId: String): Int {
        val mode = GoogleBillingRuntime.requireConfig().billingPlans
            .firstOrNull { it.productId == newProductId }
            ?.replacementModes
            ?.get(oldProductId)
            ?: GoogleSubscriptionReplacementMode.DEFERRED
        return when (mode) {
            GoogleSubscriptionReplacementMode.CHARGE_FULL_PRICE ->
                BillingFlowParams.ProductDetailsParams.SubscriptionProductReplacementParams
                    .ReplacementMode.CHARGE_FULL_PRICE
            GoogleSubscriptionReplacementMode.DEFERRED ->
                BillingFlowParams.ProductDetailsParams.SubscriptionProductReplacementParams
                    .ReplacementMode.DEFERRED
        }
    }

    private fun preferredObfuscatedAccountId(): String {
        return legacyObfuscatedAccountId()
    }

    internal suspend fun clearEntitlementCache(context: Context) = entitlementMutex.withLock {
        cachedEntitlement = null
        forceEntitlementRefresh = true
        GoogleBillingEntitlementCache.clear(context)
    }

    private fun currentObfuscatedAccountIds(): Set<String> {
        return buildSet {
            legacyObfuscatedAccountId().takeIf(String::isNotBlank)?.let(::add)
        }
    }

    private fun legacyObfuscatedAccountId(): String {
        return obfuscateBillingAccountId(
            GoogleBillingRuntime.requireConfig().billingAccountIdProvider()
        )
    }

    private fun debugLog(message: String) {
        if (sandboxFallbackEnabled) {
            Log.d(TAG, message)
        }
    }

    private fun Context.versionNameCode(): String {
        return runCatching {
            val info = packageManager.getPackageInfo(packageName, 0)
            @Suppress("DEPRECATION")
            val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.longVersionCode
            } else {
                info.versionCode.toLong()
            }
            "${info.versionName}/$versionCode"
        }.getOrDefault("unknown")
    }

    private fun Context.installerPackageNameCompat(): String {
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val sourceInfo = packageManager.getInstallSourceInfo(packageName)
                sourceInfo.installingPackageName
                    ?: sourceInfo.initiatingPackageName
                    ?: sourceInfo.originatingPackageName
                    ?: "unknown"
            } else {
                @Suppress("DEPRECATION")
                packageManager.getInstallerPackageName(packageName) ?: "unknown"
            }
        }.getOrDefault("unknown")
    }

    private fun Context.signingCertSha256(): String {
        return runCatching {
            @Suppress("DEPRECATION")
            val info = packageManager.getPackageInfo(
                packageName,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    PackageManager.GET_SIGNING_CERTIFICATES
                } else {
                    PackageManager.GET_SIGNATURES
                }
            )
            @Suppress("DEPRECATION")
            val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.signingInfo?.apkContentsSigners.orEmpty()
            } else {
                info.signatures.orEmpty()
            }
            val bytes = signatures.firstOrNull()?.toByteArray() ?: return@runCatching "empty"
            MessageDigest.getInstance("SHA-256")
                .digest(bytes)
                .joinToString(":") { byte -> "%02X".format(byte) }
        }.getOrDefault("unknown")
    }
}

private fun ProductDetails.toGoogleProducts(): List<GoogleProduct> {
    if (productType == GoogleProductType.IN_APP) {
        val configuredOption = GoogleBillingRuntime.currentConfig().billingOneTimePurchaseOptionIds[productId]
        val offer = if (configuredOption != null) {
            oneTimePurchaseOfferDetailsList.orEmpty().firstOrNull { it.purchaseOptionId == configuredOption }
                ?: return emptyList()
        } else oneTimePurchaseOfferDetailsList.orEmpty().firstOrNull() ?: oneTimePurchaseOfferDetails
        return listOf(
            GoogleProduct(
                productId = productId,
                productType = productType,
                title = title,
                description = description,
                formattedPrice = offer?.formattedPrice.orEmpty(),
                priceCurrencyCode = offer?.priceCurrencyCode.orEmpty(),
                priceAmountMicros = offer?.priceAmountMicros ?: 0L,
                offerToken = offer?.offerToken.orEmpty()
            )
        )
    }

    val offers = subscriptionOfferDetails.orEmpty().filter { offer ->
        offer.offerId == null && offer.pricingPhases.pricingPhaseList.singleOrNull()?.recurrenceMode ==
            ProductDetails.RecurrenceMode.INFINITE_RECURRING
    }
    if (offers.isEmpty()) {
        return listOf(
            GoogleProduct(
                productId = productId,
                productType = productType,
                title = title,
                description = description,
                formattedPrice = formattedPrice(),
                offerToken = ""
            )
        )
    }

    return offers.map { offer ->
        val basePlanId = offer.basePlanId
        val pricingPhase = offer.pricingPhases.pricingPhaseList.firstOrNull()
        GoogleProduct(
            productId = if (basePlanId.isNotBlank()) "$productId:$basePlanId" else productId,
            productType = productType,
            title = title,
            description = description,
            formattedPrice = pricingPhase?.formattedPrice.orEmpty(),
            priceCurrencyCode = pricingPhase?.priceCurrencyCode.orEmpty(),
            priceAmountMicros = pricingPhase?.priceAmountMicros ?: 0L,
            offerToken = offer.offerToken,
            billingPeriod = pricingPhase?.billingPeriod.orEmpty()
        )
    }
}

private fun ProductDetails.formattedPrice(): String {
    oneTimePurchaseOfferDetailsList?.firstOrNull()?.formattedPrice?.let { return it }
    oneTimePurchaseOfferDetails?.formattedPrice?.let { return it }
    return subscriptionOfferDetails
        ?.firstOrNull()
        ?.pricingPhases
        ?.pricingPhaseList
        ?.firstOrNull()
        ?.formattedPrice
        .orEmpty()
}

private fun ProductDetails.defaultOfferToken(): String {
    return oneTimePurchaseOfferDetailsList?.firstOrNull()?.offerToken
        ?: subscriptionOfferDetails?.firstOrNull()?.offerToken
        .orEmpty()
}

private fun Purchase.toGooglePurchase(): GooglePurchase {
    return GooglePurchase(
        products = products,
        purchaseToken = purchaseToken,
        purchaseTimeMillis = purchaseTime,
        isAcknowledged = isAcknowledged,
        purchaseState = purchaseState,
        obfuscatedAccountId = accountIdentifiers?.obfuscatedAccountId.orEmpty()
    )
}

private fun BillingResult.toGoogleResponse(): GoogleBillingResponse {
    return GoogleBillingResponse(
        isSuccess = responseCode == BillingClient.BillingResponseCode.OK,
        responseCode = responseCode,
        message = debugMessage
    )
}

internal fun List<GooglePurchase>.filterPurchased(): List<GooglePurchase> {
    return filter { it.purchaseState == Purchase.PurchaseState.PURCHASED }
}

internal fun List<GooglePurchase>.filterCurrentAccountPurchases(
    expectedAccountId: String
): List<GooglePurchase> {
    return filterCurrentAccountPurchases(setOf(expectedAccountId))
}

internal fun List<GooglePurchase>.filterCurrentAccountPurchases(
    expectedAccountIds: Set<String>,
    requireAppAccount: Boolean = true
): List<GooglePurchase> {
    if (!requireAppAccount) return this
    val validAccountIds = expectedAccountIds.filterTo(mutableSetOf(), String::isNotBlank)
    if (validAccountIds.isEmpty()) return emptyList()
    return filter { purchase ->
        purchase.obfuscatedAccountId.isBlank() ||
            purchase.obfuscatedAccountId in validAccountIds
    }
}

internal fun obfuscateBillingAccountId(accountId: String): String {
    val normalizedAccountId = accountId.trim()
    if (normalizedAccountId.isBlank()) return ""
    return MessageDigest.getInstance("SHA-256")
        .digest(normalizedAccountId.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
}

private fun List<GooglePurchase>.filterAllowedProducts(
    allowedProductIds: List<String>
): List<GooglePurchase> {
    val whitelist = allowedProductIds.toSet()
    return mapNotNull { purchase ->
        val allowedProducts = purchase.products.filter { it in whitelist }
        purchase.copy(products = allowedProducts).takeIf { allowedProducts.isNotEmpty() }
    }
}
