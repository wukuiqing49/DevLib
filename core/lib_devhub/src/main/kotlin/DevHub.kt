package com.devhub.sdk

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import android.util.LruCache
import com.google.android.gms.tasks.Tasks
import com.google.android.play.core.integrity.IntegrityManagerFactory
import com.google.android.play.core.integrity.IntegrityTokenRequest
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

enum class FetchMode { REALTIME, CACHE }
enum class AnalyticsMode { STANDARD, CORE_ONLY }
enum class EntitlementState { PRO, FREE, UNKNOWN }
enum class CampaignType { ANNOUNCEMENT, PROMOTION, UPGRADE }
enum class CampaignVisibility { VISIBLE, EMPTY, UNKNOWN }

data class CampaignAction(val campaignId: String, val type: CampaignType)
data class CampaignEntryState(val visibility: CampaignVisibility, val fromCache: Boolean = false, val stale: Boolean = false)

data class EntitlementSnapshot(
  val state: EntitlementState,
  val entitlements: JSONArray,
  val verifiedAtMillis: Long = 0L,
  val fromCache: Boolean = false
)

data class DevHubConfig(
  val apiUrl: String,
  val appKey: String,
  val allowLocalHttp: Boolean = false,
  val onError: (String) -> Unit = {},
  val storageNamespace: String = "default",
  val collectionEnabled: Boolean = false,
  val collectDeviceInfo: Boolean = true,
  val collectLocale: Boolean = false,
  val allowedEventProperties: Set<String> = emptySet(),
  val allowFeedbackImages: Boolean = false,
  val connectTimeoutMillis: Int = 15_000,
  val readTimeoutMillis: Int = 20_000,
  val requestTimeoutMillis: Int = 30_000,
  val maxResponseBytes: Int = 1_000_000,
  val analyticsEnabled: Boolean = true,
  /** CORE_ONLY keeps install and daily-active metrics but never persists custom track() events. */
  val analyticsMode: AnalyticsMode = AnalyticsMode.STANDARD,
  val campaignRefreshIntervalMillis: Long = 3L * 86_400_000,
  val campaignFetchMode: FetchMode = FetchMode.CACHE,
  val campaignCacheTtlMillis: Long? = null,
  val allowStaleCampaignsOnError: Boolean = true,
  /** Retain a previously verified subscription entitlement during a temporary server outage. */
  val entitlementGracePeriodMillis: Long = 7 * 24 * 60 * 60 * 1000L,
  /** Google Cloud project number for Play Integrity. Required when the API enables integrity enforcement. */
  val playIntegrityCloudProjectNumber: Long? = null
)

data class DevHubResult<T>(
  val value: T? = null,
  val error: String? = null,
  val statusCode: Int? = null,
  val requestId: String? = null,
  val retryAfterMillis: Long? = null,
  val fromCache: Boolean = false,
  val stale: Boolean = false
) {
  val successful: Boolean get() = error == null
  internal fun <Value> failure(code: String? = error): DevHubResult<Value> = DevHubResult(error = code, statusCode = statusCode, requestId = requestId, retryAfterMillis = retryAfterMillis)
}

class DevHub private constructor(private val context: Context, private val config: DevHubConfig, val initializationError: String? = null) {
  private val scope by lazy { digest(config.apiUrl.trimEnd('/') + ":" + context.packageName + ":" + config.storageNamespace).take(24) }
  private val previousScope by lazy { digest(config.apiUrl.trimEnd('/') + ":" + config.appKey).take(24) }
  private val prefs by lazy { context.getSharedPreferences("devhub_$scope", Context.MODE_PRIVATE) }
  private val campaignCache by lazy { CampaignCache(prefs, config, ::report) }
  private val campaignImages = object : LruCache<String, ByteArray>(8_000_000) {
    override fun sizeOf(key: String, value: ByteArray): Int = value.size
  }
  private val storeDelegate = lazy { EventStore(context, "devhub_$scope.db", onDiscard = { report("corrupt_events_discarded") }) }
  private val store by storeDelegate
  private val executor = ScheduledThreadPoolExecutor(1) { task -> Thread(task, "devhub-sync").apply { isDaemon = true } }.apply {
    removeOnCancelPolicy = true
    setExecuteExistingDelayedTasksAfterShutdownPolicy(false)
    setContinueExistingPeriodicTasksAfterShutdownPolicy(false)
  }
  private val storageExecutor = boundedExecutor("devhub-storage", 128)
  private val interactiveExecutor = boundedExecutor("devhub-requests", 8)
  private val transport = SdkTransport(config)
  private val persistenceLock = Any()
  private val installLock = Any()
  private val syncQueued = AtomicBoolean(false)
  private val activeQueued = AtomicBoolean(false)
  private val continuationQueued = AtomicBoolean(false)
  private val imageQueued = AtomicBoolean(false)
  private val forceRequested = AtomicBoolean(false)
  private val generation = AtomicLong()
  private val serverRetryAt = AtomicLong()
  private val collection = AtomicBoolean(config.collectionEnabled)
  private val purgePending = AtomicBoolean(!config.analyticsEnabled)
  private val closing = AtomicBoolean(false)
  private val operationGeneration = ThreadLocal<Long>()
  private val operationDeadline = ThreadLocal<Long>()
  private val main by lazy { Handler(Looper.getMainLooper()) }
  @Volatile private var retryAt = 0L
  private var retryDelay = 60_000L
  @Volatile private var closed = false
  @Volatile private var ready = false
  private var installId = ""
  private var installToken = ""
  @Volatile private var accountId = ""
  val collectionEnabled: Boolean get() = collection.get() && !closed && initializationError == null
  val analyticsEnabled: Boolean get() = collectionEnabled && config.analyticsEnabled
  val purchaseAccountId: String get() {
    val error = prepare()
    if (error != null) { report(error); return "" }
    return try { accountId.ifBlank { digest(installId) } } catch (_: Exception) { report("sdk_internal_error"); "" }
  }
  private val lifecycle = object : Application.ActivityLifecycleCallbacks {
    override fun onActivityResumed(activity: Activity) { start() }
    override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
  }

  init {
    if (initializationError != null) report(initializationError)
    else safely {
      (context as? Application)?.registerActivityLifecycleCallbacks(lifecycle)
      executor.scheduleWithFixedDelay({ synchronizeLater() }, 60, 60, TimeUnit.SECONDS)
      if (collectionEnabled) start()
    }
  }

  private fun stateError(): String? = when {
    closed -> "sdk_closed"
    initializationError != null -> initializationError
    !collection.get() -> "collection_disabled"
    else -> null
  }

  private fun prepare(): String? = synchronized(persistenceLock) {
    stateError()?.let { return@synchronized it }
    try {
      purgePendingData()
      if (ready) return@synchronized null
      val previous = context.getSharedPreferences("devhub_$previousScope", Context.MODE_PRIVATE)
      if (!prefs.contains("install_id") && previous.contains("install_id")) {
        val editor = prefs.edit()
        val cleared = prefs.getBoolean("collection_reset", false)
        previous.all.forEach { (key, value) ->
          if (!cleared || key !in setOf("active_pending", "active_day", "events_retry_pending")) {
            when (value) { is String -> editor.putString(key, value); is Boolean -> editor.putBoolean(key, value); is Long -> editor.putLong(key, value) }
          }
        }
        check(editor.commit())
      }
      val legacy = context.getSharedPreferences("devhub", Context.MODE_PRIVATE)
      installId = prefs.getString("install_id", null) ?: legacy.getString("install_id", null) ?: UUID.randomUUID().toString()
      installToken = prefs.getString("install_token", null) ?: UUID.randomUUID().toString() + UUID.randomUUID().toString()
      accountId = prefs.getString("purchase_account_id", "").orEmpty()
      check(prefs.edit().putString("install_id", installId).putString("install_token", installToken).commit())
      if (!prefs.contains("first_open_at")) check(prefs.edit().putString("first_open_at", utcNow()).commit())
      if (!prefs.contains("events_last_flush")) check(prefs.edit().putLong("events_last_flush", System.currentTimeMillis()).commit())
      store.count()
      if (config.analyticsMode == AnalyticsMode.CORE_ONLY) {
        store.clear()
        check(prefs.edit().putBoolean("events_retry_pending", false).commit())
      } else if (config.analyticsEnabled) safely {
        val oldEvents = legacy.getString("events", "[]") ?: "[]"
        if (oldEvents.length <= 5_000_000) store.importOnce("shared-preferences", JSONArray(oldEvents)) else report("legacy_import_too_large")
        val databaseFile = context.getDatabasePath("devhub_$previousScope.db")
        if (databaseFile.exists()) {
          val events = JSONArray()
          var totalSize = 0
          SQLiteDatabase.openDatabase(databaseFile.path, null, SQLiteDatabase.OPEN_READONLY).use { database ->
            database.rawQuery("SELECT CASE WHEN length(payload)<=16384 THEN payload END FROM events ORDER BY sequence LIMIT 10001", null).use { cursor ->
              while (cursor.moveToNext()) {
                if (cursor.isNull(0)) { report("corrupt_events_discarded"); continue }
                val payload = cursor.getString(0)
                totalSize += payload.length
                if (totalSize > 5_000_000) { report("legacy_import_too_large"); break }
                val event = try { JSONObject(payload) } catch (_: Exception) { report("corrupt_events_discarded"); null }
                if (event != null) events.put(event)
              }
            }
          }
          if (events.length() <= 10_000 && totalSize <= 5_000_000) store.importOnce(previousScope, events) else report("legacy_import_too_large")
        }
      }
      ready = true
      null
    } catch (_: Exception) { "storage_unavailable" }
  }

  fun setCollectionEnabled(enabled: Boolean, onComplete: ((DevHubResult<Boolean>) -> Unit)? = null) {
    if (closed || initializationError != null) { fail(onComplete, stateError() ?: "sdk_closed"); return }
    collection.set(enabled)
    val expected = generation.incrementAndGet()
    if (!enabled) { purgePending.set(true); forceRequested.set(false); transport.cancel(); campaignImages.evictAll() }
    try {
      storageExecutor.execute {
        val result = try {
          if (!enabled) synchronized(persistenceLock) { purgePendingData() }
          if (enabled && generation.get() == expected) start()
          DevHubResult(enabled)
        } catch (_: Exception) { DevHubResult<Boolean>(error = "storage_unavailable") }
        complete(onComplete, result)
      }
    } catch (_: RejectedExecutionException) { fail(onComplete, if (closed) "sdk_closed" else "sdk_busy") }
  }

  fun start() {
    stateError()?.let { if (it != "collection_disabled") report(it); return }
    if (!analyticsEnabled) return
    if (!activeQueued.compareAndSet(false, true)) return
    val expected = generation.get()
    val time = utcNow()
    try {
      storageExecutor.execute {
        try {
          if (expected != generation.get()) return@execute
          val error = prepare()
          if (error != null) { report(error); return@execute }
          synchronized(persistenceLock) {
            if (!collectionEnabled || expected != generation.get()) return@synchronized
            if (prefs.getString("active_day", null) != time.take(10)) {
              val pending = pendingActivity().put(time.take(10), time)
              check(prefs.edit().putString("active_pending", pending.toString()).commit())
            }
          }
          synchronizeLater()
        } catch (_: Exception) { report("storage_unavailable") }
        finally { activeQueued.set(false) }
      }
    } catch (_: RejectedExecutionException) { activeQueued.set(false); report(if (closed) "sdk_closed" else "sdk_busy") }
  }

  private fun purgePendingData() {
    if (!purgePending.get()) return
    store.clear()
    store.importOnce("shared-preferences", JSONArray())
    store.importOnce(previousScope, JSONArray())
    check(prefs.edit().remove("active_pending").remove("active_day").putBoolean("events_retry_pending", false).putBoolean("collection_reset", true).commit())
    purgePending.set(false)
  }

  private fun eventSnapshot(name: String, properties: Map<String, Any?>): DevHubResult<String> {
    stateError()?.let { return DevHubResult(error = it) }
    if (!analyticsEnabled) return DevHubResult(error = "analytics_disabled")
    if (!name.matches(Regex("[A-Za-z][A-Za-z0-9_.-]{0,127}"))) return DevHubResult(error = "invalid_event_name")
    return try {
      if (properties.size > 32) return DevHubResult(error = "event_properties_too_large")
      for ((key, value) in properties) {
        if (key.length > 128) return DevHubResult(error = "invalid_event_properties")
        if (key !in config.allowedEventProperties || sensitiveProperty(key)) return DevHubResult(error = "event_property_not_allowed")
        if (value !is String && value !is Boolean && value !is Byte && value !is Short && value !is Int && value !is Long && value !is Float && value !is Double && value != null) return DevHubResult(error = "invalid_event_properties")
        if ((value is Double && !value.isFinite()) || (value is Float && !value.isFinite())) return DevHubResult(error = "invalid_event_properties")
        if (value is String && value.length > 4096) return DevHubResult(error = "event_properties_too_large")
      }
      val serialized = JSONObject(properties).toString() ?: return DevHubResult(error = "invalid_event_properties")
      if (serialized.toByteArray(Charsets.UTF_8).size > 4096) DevHubResult(error = "event_properties_too_large") else DevHubResult(serialized)
    } catch (_: Exception) { DevHubResult(error = "invalid_event_properties") }
  }

  fun track(name: String, properties: Map<String, Any?> = emptyMap()): Boolean {
    if (config.analyticsMode == AnalyticsMode.CORE_ONLY && analyticsEnabled) return true
    val expected = generation.get()
    val snapshot = eventSnapshot(name, properties)
    val result = if (snapshot.successful) persistEvent(name, snapshot.value!!, expected, utcNow()) else snapshot.failure<Boolean>()
    result.error?.let { report(it) }
    return result.value == true
  }

  private fun persistEvent(name: String, serialized: String, expected: Long, time: String, eventId: String? = null): DevHubResult<Boolean> {
    prepare()?.let { return DevHubResult(error = it) }
    try {
      synchronized(persistenceLock) {
        stateError()?.let { return DevHubResult(error = it) }
        if (expected != generation.get()) return DevHubResult(error = "collection_disabled")
        val event = JSONObject().put("id", eventId ?: UUID.randomUUID().toString()).put("name", name).put("time", time).put("properties", JSONObject(serialized))
        if (!store.append(event.toString())) return DevHubResult(error = "event_queue_full")
      }
    } catch (_: Exception) { return DevHubResult(error = "event_persistence_failed") }
    synchronizeLater()
    return DevHubResult(true)
  }

  fun trackAsync(name: String, properties: Map<String, Any?> = emptyMap(), onComplete: (DevHubResult<Boolean>) -> Unit) {
    if (config.analyticsMode == AnalyticsMode.CORE_ONLY && analyticsEnabled) { complete(onComplete, DevHubResult(true)); return }
    val expected = generation.get()
    val snapshot = eventSnapshot(name, properties)
    if (!snapshot.successful) { fail(onComplete, snapshot.error!!); return }
    val time = utcNow()
    try {
      storageExecutor.execute {
        val result = persistEvent(name, snapshot.value!!, expected, time)
        result.error?.let { report(it) }
        complete(onComplete, result)
      }
    } catch (_: RejectedExecutionException) { fail(onComplete, if (closed) "sdk_closed" else "sdk_busy") }
  }

  /**
   * Records the first point at which the user actually starts a report. Call this from the
   * report-start action, never from app launch. It requires collection consent and is retained
   * in CORE_ONLY mode without report content or user identifiers.
   */
  fun reportFirstReportStarted(onComplete: ((DevHubResult<Boolean>) -> Unit)? = null) {
    enqueueResult(onComplete) {
      if (prefs.getBoolean("first_report_started_recorded", false)) return@enqueueResult DevHubResult(true)
      val snapshot = eventSnapshot("first_report_started", emptyMap())
      if (!snapshot.successful) return@enqueueResult snapshot.failure()
      val eventId = UUID.nameUUIDFromBytes("devhub:first_report_started:$installId".toByteArray(Charsets.UTF_8)).toString()
      val recorded = persistEvent("first_report_started", snapshot.value!!, generation.get(), utcNow(), eventId)
      if (!recorded.successful) recorded
      else if (!prefs.edit().putBoolean("first_report_started_recorded", true).commit()) DevHubResult(error = "storage_unavailable")
      else DevHubResult(true)
    }
  }

  internal fun pendingEventCount(): Long = synchronized(persistenceLock) { if (prepare() == null) store.count() else 0L }
  fun flush() { stateError()?.let { report(it); return }; if (!analyticsEnabled) { report("analytics_disabled"); return }; synchronizeLater(force = true) }

  fun loadPurchaseAccountId(onComplete: (DevHubResult<String>) -> Unit) {
    enqueueResult(onComplete) { DevHubResult(accountId.ifBlank { digest(installId) }) }
  }

  fun identifyGoogle(idToken: String, onComplete: (DevHubResult<Boolean>) -> Unit) {
    if (idToken.isBlank() || idToken.length > 16_000) { fail(onComplete, "invalid_google_login"); return }
    enqueueResult(onComplete) {
      val result = withInstall { request("/v1/sdk/users/google-identify", JSONObject().put("installId", installId).put("idToken", idToken)) }
      val value = result.value?.optString("purchaseAccountId")
      if (!result.successful) result.failure()
      else if (value.isNullOrBlank() || value.length > 128) DevHubResult(error = "invalid_google_login_response")
      else {
        if (!prefs.edit().putString("purchase_account_id", value).remove("identity_clear_pending").commit()) DevHubResult(error = "storage_unavailable")
        else { accountId = value; DevHubResult(true) }
      }
    }
  }

  /** Clears the active Google identity for this installation without deleting server-side purchase history. */
  fun clearIdentity(onComplete: (DevHubResult<Boolean>) -> Unit) {
    enqueueResult(onComplete) {
      if (!prefs.edit().remove("purchase_account_id").putBoolean("identity_clear_pending", true).commit()) return@enqueueResult DevHubResult(error = "storage_unavailable")
      accountId = ""
      val result = withInstall { request("/v1/sdk/users/clear-identity", JSONObject().put("installId", installId)) }
      if (!result.successful) result.failure()
      else if (!prefs.edit().remove("identity_clear_pending").commit()) DevHubResult(error = "storage_unavailable")
      else DevHubResult(true)
    }
  }

  fun submitFeedback(content: String, type: String = "general", onComplete: ((DevHubResult<JSONObject>) -> Unit)? = null) {
    if (content.isBlank() || content.trim().length > 4096 || type !in setOf("general", "bug", "feature")) { fail(onComplete, "invalid_feedback"); return }
    enqueueResult(onComplete) { withInstall { request("/v1/sdk/feedback", JSONObject().put("installId", installId).put("content", content.trim()).put("type", type)) } }
  }

  fun submitFeedback(content: String, image: ByteArray, imageMime: String, type: String = "general", onComplete: ((DevHubResult<JSONObject>) -> Unit)? = null) {
    if (content.isBlank() || content.trim().length > 4096 || type !in setOf("general", "bug", "feature")) { fail(onComplete, "invalid_feedback"); return }
    if (image.size !in 1..1_000_000 || imageMime !in setOf("image/jpeg", "image/png", "image/webp")) { fail(onComplete, "invalid_feedback_image"); return }
    stateError()?.let { fail(onComplete, it); return }
    if (!config.allowFeedbackImages) { fail(onComplete, "feedback_images_disabled"); return }
    if (!imageQueued.compareAndSet(false, true)) { fail(onComplete, "sdk_busy"); return }
    val snapshot = image.copyOf()
    enqueueResult(onComplete, onFinished = { imageQueued.set(false) }) {
      withInstall {
        val body = JSONObject().put("installId", installId).put("content", content.trim()).put("type", type).put("imageMime", imageMime).put("imageBase64", Base64.encodeToString(snapshot, Base64.NO_WRAP))
        request("/v1/sdk/feedback", body)
      }
    }
  }

  fun verifyPurchase(purchaseToken: String, onComplete: (JSONObject) -> Unit) {
    verifyPurchase(purchaseToken, "subscription") { result -> onComplete(result.value ?: JSONObject().put("error", result.error)) }
  }

  fun verifyPurchase(purchaseToken: String, kind: String, onComplete: (DevHubResult<JSONObject>) -> Unit) {
    if (purchaseToken.isBlank() || purchaseToken.length > 4096 || kind !in setOf("subscription", "product")) { fail(onComplete, "invalid_purchase"); return }
    enqueueResult(onComplete) { withInstall { request("/v1/sdk/purchases/verify", JSONObject().put("installId", installId).put("purchaseToken", purchaseToken).put("kind", kind)) } }
  }

  fun redeemCoupon(code: String, onComplete: (Boolean) -> Unit) {
    redeemCouponDetails(code) { result -> onComplete(result.successful && result.value?.optBoolean("redeemed") == true) }
  }

  fun redeemCouponDetails(code: String, onComplete: (DevHubResult<JSONObject>) -> Unit) {
    if (code.isBlank() || code.trim().length > 64) { fail(onComplete, "invalid_coupon_code"); return }
    enqueueResult(onComplete) { withInstall { request("/v1/sdk/coupons/redeem", JSONObject().put("installId", installId).put("code", code.trim())) } }
  }

  fun redeemCampaignCoupon(campaignId: String, code: String, onComplete: (DevHubResult<JSONObject>) -> Unit) {
    if (!campaignId.matches(Regex("[0-9a-f-]{36}")) || code.isBlank() || code.trim().length > 64) { fail(onComplete, "invalid_coupon_code"); return }
    enqueueResult(onComplete) { withInstall { request("/v1/sdk/coupons/redeem", JSONObject().put("installId", installId).put("campaignId", campaignId).put("code", code.trim())) } }
  }

  fun loadEntitlements(onComplete: (JSONArray) -> Unit) { loadEntitlementsResult { result -> onComplete(result.value ?: JSONArray()) } }

  fun loadEntitlementsResult(onComplete: (DevHubResult<JSONArray>) -> Unit) {
    if (prefs.getBoolean("identity_clear_pending", false)) { fail(onComplete, "identity_clear_pending"); return }
    enqueueResult(onComplete) {
      val result = withInstall { request("/v1/sdk/entitlements?installId=${URLEncoder.encode(installId, "UTF-8")}") }
      if (!result.successful) result.failure()
      else {
        val values = result.value?.optJSONArray("entitlements")
        if (values == null) result.failure("invalid_entitlements_response") else DevHubResult(values, statusCode = result.statusCode, requestId = result.requestId)
      }
    }
  }

  /**
   * Returns PRO, FREE, or UNKNOWN. UNKNOWN never replaces a recently verified PRO result.
   * The cached permanent products (no expiry) remain available until a later authoritative response revokes them.
   */
  fun loadEntitlementSnapshot(onComplete: (DevHubResult<EntitlementSnapshot>) -> Unit) {
    loadEntitlementsResult { result ->
      if (result.successful) {
        val values = result.value ?: JSONArray()
        val now = System.currentTimeMillis()
        val state = if (values.length() > 0) EntitlementState.PRO else EntitlementState.FREE
        if (!prefs.edit().putString("entitlements_cache", values.toString()).putLong("entitlements_verified_at", now).commit()) {
          onComplete(DevHubResult(error = "storage_unavailable"))
        } else onComplete(DevHubResult(EntitlementSnapshot(state, values, now)))
        return@loadEntitlementsResult
      }
      val cached = try { JSONArray(prefs.getString("entitlements_cache", "[]")) } catch (_: Exception) { JSONArray() }
      val verifiedAt = prefs.getLong("entitlements_verified_at", 0L)
      val permanent = (0 until cached.length()).any { index ->
        val item = cached.optJSONObject(index)
        item?.optString("source") in setOf("product", "coupon") && item?.isNull("expireAt") != false
      }
      val inGrace = verifiedAt > 0 && System.currentTimeMillis() - verifiedAt <= config.entitlementGracePeriodMillis
      if (cached.length() > 0 && (permanent || inGrace)) {
        onComplete(DevHubResult(EntitlementSnapshot(EntitlementState.PRO, cached, verifiedAt, fromCache = true), statusCode = result.statusCode, requestId = result.requestId))
      } else onComplete(DevHubResult(EntitlementSnapshot(EntitlementState.UNKNOWN, JSONArray(), verifiedAt, fromCache = true), error = result.error, statusCode = result.statusCode, requestId = result.requestId))
    }
  }

  fun loadCampaigns(onComplete: (JSONArray) -> Unit) { loadCampaignsResult { result -> onComplete(result.value ?: JSONArray()) } }

  fun loadCampaignsResult(onComplete: (DevHubResult<JSONArray>) -> Unit) {
    loadCampaignsResult(false, onComplete)
  }

  fun loadCampaignsResult(forceRefresh: Boolean, onComplete: (DevHubResult<JSONArray>) -> Unit) {
    enqueueResult(onComplete) { campaignCache.load(forceRefresh) { fetchCampaigns() } }
  }

  fun loadCampaignEntryState(onComplete: (CampaignEntryState) -> Unit) {
    loadCampaignsResult { result ->
      val visibility = when {
        !result.successful || result.value == null -> CampaignVisibility.UNKNOWN
        result.value.length() == 0 && result.stale -> CampaignVisibility.UNKNOWN
        result.value.length() == 0 -> CampaignVisibility.EMPTY
        else -> CampaignVisibility.VISIBLE
      }
      onComplete(CampaignEntryState(visibility, result.fromCache, result.stale))
    }
  }

  /** Launch with ActivityResultContracts.StartActivityForResult; the host handles the returned action. */
  fun campaignsIntent(activity: Activity): Intent = Intent(activity, CampaignsActivity::class.java)
    .putExtra("devhub_instance", config.apiUrl.trimEnd('/') + ":" + context.packageName + ":" + config.storageNamespace)

  fun loadCampaignDetail(campaignId: String, onComplete: (DevHubResult<JSONObject>) -> Unit) {
    if (!campaignId.matches(Regex("[0-9a-f-]{36}"))) { fail(onComplete, "invalid_campaign_id"); return }
    enqueueResult(onComplete) {
      val cacheKey = "campaign_detail_$campaignId"
      val savedAt = prefs.getLong("${cacheKey}_at", 0L)
      val age = System.currentTimeMillis() - savedAt
      if (age in 0 until 3L * 86_400_000) {
        val cached = try { prefs.getString(cacheKey, null)?.let(::JSONObject) } catch (_: Exception) { null }
        if (cached != null && cached.optString("id") == campaignId) return@enqueueResult DevHubResult(cached, fromCache = true)
      }
      val result = request("/v1/sdk/campaigns/$campaignId")
      if (result.successful && result.value?.optString("id") == campaignId) {
        prefs.edit().putString(cacheKey, result.value.toString()).putLong("${cacheKey}_at", System.currentTimeMillis()).apply()
      }
      result
    }
  }

  private fun fetchCampaigns(): DevHubResult<JSONArray> {
      val all = JSONArray()
      var totalBytes = 0
      for (offset in 0..900 step 100) {
        val result = request("/v1/sdk/campaigns?limit=100&offset=$offset")
        if (!result.successful) return result.failure()
        val batch = result.value?.optJSONArray("campaigns") ?: return result.failure("invalid_campaign_response")
        if (batch.length() > 100 || result.value.opt("hasMore") !is Boolean) return result.failure("invalid_campaign_response")
        totalBytes += batch.toString().toByteArray(Charsets.UTF_8).size
        if (totalBytes > config.maxResponseBytes) return result.failure("response_too_large")
        repeat(batch.length()) { index ->
          val campaign = batch.optJSONObject(index) ?: return result.failure("invalid_campaign_response")
          all.put(campaign)
        }
        if (!result.value.optBoolean("hasMore")) return DevHubResult(all)
      }
      return DevHubResult(error = "campaign_pagination_limit")
  }

  fun loadCampaignsPage(offset: Int = 0, onComplete: (DevHubResult<JSONObject>) -> Unit) {
    if (offset !in 0..1_000_000) { fail(onComplete, "invalid_campaign_offset"); return }
    enqueueResult(onComplete) { request("/v1/sdk/campaigns?limit=100&offset=$offset") }
  }

  fun loadCampaignImage(imagePath: String, onComplete: (DevHubResult<ByteArray>) -> Unit) {
    if (!imagePath.startsWith("/v1/sdk/images?key=") || imagePath.length > 2048 || imagePath.contains('#') || imagePath.contains('\r') || imagePath.contains('\n')) { fail(onComplete, "invalid_image_path"); return }
    enqueueResult(onComplete) {
      campaignImages.get(imagePath)?.let { return@enqueueResult DevHubResult(it.clone(), fromCache = true) }
      val expected = operationGeneration.get() ?: generation.get()
      val headers = requestHeaders(false)
      if (!headers.successful) headers.failure() else {
        val result = transport.image(imagePath, headers.value!!, operationDeadline.get()) { collectionEnabled && expected == generation.get() }
        if (result.successful && result.value != null) campaignImages.put(imagePath, result.value.clone())
        result
      }
    }
  }

  fun close() {
    if (!closing.compareAndSet(false, true)) return
    closed = true
    generation.incrementAndGet()
    campaignImages.evictAll()
    transport.close()
    safely { (context as? Application)?.unregisterActivityLifecycleCallbacks(lifecycle) }
    storageExecutor.shutdown()
    interactiveExecutor.shutdown()
    executor.shutdown()
    synchronized(instances) { instances.entries.removeAll { it.value === this } }
    Thread({
      try {
        val storageDone = storageExecutor.awaitTermination(60, TimeUnit.SECONDS)
        val interactiveDone = interactiveExecutor.awaitTermination(60, TimeUnit.SECONDS)
        val syncDone = executor.awaitTermination(60, TimeUnit.SECONDS)
        if (storageDone && interactiveDone && syncDone) synchronized(persistenceLock) { if (storeDelegate.isInitialized()) store.close() }
      } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
      catch (_: Exception) { report("storage_unavailable") }
    }, "devhub-close").apply { isDaemon = true; start() }
  }

  private fun pendingActivity(): JSONObject {
    val raw = prefs.getString("active_pending", "{}") ?: "{}"
    val parsed = try { if (raw.length > 32_768) throw IllegalArgumentException(); JSONObject(raw) } catch (_: Exception) { report("corrupt_activity_discarded"); JSONObject() }
    val valid = JSONObject()
    val earliest = utcNow(System.currentTimeMillis() - 89L * 86_400_000).take(10)
    val today = utcNow().take(10)
    parsed.keys().asSequence().toList().sorted().takeLast(90).forEach { day ->
      val time = parsed.optString(day)
      if (day.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) && day >= earliest && day <= today && time.matches(Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z")) && time.startsWith(day)) valid.put(day, time)
      else report("corrupt_activity_discarded")
    }
    return valid
  }

  private fun synchronize(forceFlush: Boolean): Boolean {
    if (!analyticsEnabled || SystemClock.elapsedRealtime() < maxOf(retryAt, serverRetryAt.get())) return false
    val expected = operationGeneration.get() ?: generation.get()
    if (expected != generation.get()) return false
    prepare()?.let { report(it); backoff(); return false }
    if (forceFlush) synchronized(persistenceLock) { check(prefs.edit().putBoolean("events_retry_pending", true).commit()) }
    val installation = ensureInstall()
    if (!installation.successful) { backoff(); return false }
    val pending = synchronized(persistenceLock) {
      val cleaned = pendingActivity()
      check(prefs.edit().putString("active_pending", cleaned.toString()).commit())
      cleaned
    }
    for (day in pending.keys().asSequence().toList().sorted().take(3)) {
      if (!collectionEnabled || expected != generation.get()) return false
      val result = request("/v1/sdk/active", JSONObject().put("installId", installId).put("appVersion", appVersion()).put("activityTime", pending.getString(day)))
      if (result.successful && result.value?.opt("recorded") !is Boolean) { report("invalid_activity_response"); backoff(); return false }
      if (!result.successful && result.error !in setOf("invalid_activity_time", "invalid_date")) { backoff(); return false }
      synchronized(persistenceLock) {
        if (expected != generation.get()) return false
        val current = pendingActivity()
        current.remove(day)
        check(prefs.edit().putString("active_pending", current.toString()).putString("active_day", day).commit())
      }
    }
    if (forceFlush || prefs.getBoolean("events_retry_pending", false) || store.count() >= 20 || System.currentTimeMillis() - prefs.getLong("events_last_flush", 0) >= 1_800_000) {
      synchronized(persistenceLock) { check(prefs.edit().putBoolean("events_retry_pending", true).commit()) }
      repeat(3) {
        if (!collectionEnabled || expected != generation.get()) return false
        val stored = store.batch()
        val batch = stored.filter { (_, event) ->
          val properties = event.optJSONObject("properties")
          val snapshot = if (properties == null) DevHubResult<String>(error = "invalid_event_properties") else eventSnapshot(event.optString("name"), properties.keys().asSequence().associateWith { properties.get(it).takeUnless { value -> value == JSONObject.NULL } })
          snapshot.successful
        }
        val retainedIds = batch.map { it.first }.toSet()
        val rejected = stored.map { it.first }.filter { it !in retainedIds }
        if (rejected.isNotEmpty()) { store.remove(rejected); report("event_policy_rejected") }
        if (batch.isEmpty()) return@repeat
        val result = request("/v1/sdk/events/batch", JSONObject().put("installId", installId).put("events", JSONArray().apply { batch.forEach { put(it.second) } }))
        if (!result.successful) {
          if (result.error in invalidEvents) {
            val event = batch.first()
            val single = request("/v1/sdk/events/batch", JSONObject().put("installId", installId).put("events", JSONArray().put(event.second)))
            if ((single.successful && accepted(single) == 1) || single.error in invalidEvents) {
              if (expected != generation.get()) return false
              store.remove(listOf(event.first))
              if (!single.successful) report("invalid_event_discarded")
              scheduleContinuation()
            } else backoff()
          } else backoff()
          return false
        }
        if (accepted(result) != batch.size) { report("invalid_batch_response"); backoff(); return false }
        if (expected != generation.get()) return false
        store.remove(batch.map { it.first })
      }
      if (store.count() == 0L) synchronized(persistenceLock) { check(prefs.edit().putLong("events_last_flush", System.currentTimeMillis()).putBoolean("events_retry_pending", false).commit()) }
      else scheduleContinuation()
    }
    retryAt = 0
    retryDelay = 60_000
    return true
  }

  private fun accepted(result: DevHubResult<JSONObject>): Int {
    val accepted = result.value?.opt("accepted") as? Number ?: return -1
    val duplicate = result.value.opt("duplicate") as? Number ?: return -1
    if (accepted.toInt() !in 0..20 || duplicate.toInt() !in 0..20 || accepted.toDouble() != accepted.toInt().toDouble() || duplicate.toDouble() != duplicate.toInt().toDouble()) return -1
    return accepted.toInt() + duplicate.toInt()
  }

  private fun ensureInstall(): DevHubResult<JSONObject> {
    prepare()?.let { return DevHubResult(error = it) }
    return synchronized(installLock) {
      stateError()?.let { return@synchronized DevHubResult(error = it) }
      val earliest = utcNow(System.currentTimeMillis() - 89L * 86_400_000)
      val firstOpenAt = (prefs.getString("first_open_at", null) ?: utcNow()).coerceIn(earliest, utcNow())
      val body = JSONObject().put("installId", installId).put("firstOpenAt", firstOpenAt).put("appVersion", appVersion())
      if (config.analyticsEnabled && config.collectDeviceInfo) {
        body.put("manufacturer", safeText { Build.MANUFACTURER }).put("model", safeText { Build.MODEL }).put("androidVersion", safeText { Build.VERSION.RELEASE })
      }
      if (config.analyticsEnabled && config.collectLocale) {
        val locale = Locale.getDefault()
        val country = locale.country.uppercase(Locale.US).takeIf { it.matches(Regex("[A-Z]{2}")) }
        if (country != null) body.put("country", country)
        body.put("language", safeText { locale.toLanguageTag() })
      }
      val profile = body.toString()
      if (prefs.getBoolean("install_sent_v2", false) && prefs.getString("install_profile_v2", null) == profile) {
        return@synchronized DevHubResult(JSONObject())
      }
      val result = request("/v1/sdk/install", body)
      if (result.successful) {
        if (result.value?.opt("created") !is Boolean) { report("invalid_install_response"); return@synchronized result.failure("invalid_install_response") }
        synchronized(persistenceLock) {
          check(prefs.edit().putBoolean("install_sent_v2", true).putString("install_profile_v2", profile).commit())
        }
      }
      result
    }
  }

  private fun withInstall(block: () -> DevHubResult<JSONObject>): DevHubResult<JSONObject> {
    val result = ensureInstall()
    return if (result.successful) block() else result
  }

  private fun requestHeaders(requiresIntegrity: Boolean): DevHubResult<Map<String, String>> {
    val headers = mutableMapOf("X-DevHub-App-Key" to config.appKey, "X-DevHub-Install-Token" to installToken)
    if (!requiresIntegrity) return DevHubResult(headers)
    val project = config.playIntegrityCloudProjectNumber ?: return DevHubResult(headers)
    return try {
      val nonce = ByteArray(32).also { SecureRandom().nextBytes(it) }
      val request = IntegrityTokenRequest.builder()
        .setNonce(Base64.encodeToString(nonce, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING))
        .setCloudProjectNumber(project)
        .build()
      val token = Tasks.await(IntegrityManagerFactory.create(context).requestIntegrityToken(request), 10, TimeUnit.SECONDS).token()
      if (token.isNullOrBlank() || token.length > 20_000) DevHubResult(error = "integrity_unavailable")
      else DevHubResult(headers.apply { put("X-Play-Integrity-Token", token) })
    } catch (_: Exception) { DevHubResult(error = "integrity_unavailable") }
  }

  private fun request(path: String, body: JSONObject? = null): DevHubResult<JSONObject> {
    val expected = operationGeneration.get() ?: generation.get()
    val sensitive = path in setOf("/v1/sdk/install", "/v1/sdk/feedback", "/v1/sdk/coupons/redeem", "/v1/sdk/purchases/verify", "/v1/sdk/users/google-identify")
    val headers = requestHeaders(sensitive)
    if (!headers.successful) return headers.failure()
    val result = transport.json(path, body, headers.value!!, operationDeadline.get()) { collectionEnabled && expected == generation.get() }
    if (result.statusCode in setOf(429, 503) && result.retryAfterMillis != null) serverRetryAt.set(maxOf(serverRetryAt.get(), SystemClock.elapsedRealtime() + result.retryAfterMillis))
    if (result.error == "install_not_found") synchronized(persistenceLock) { check(prefs.edit().putBoolean("install_sent_v2", false).commit()) }
    result.error?.let { report(it) }
    return result
  }

  private fun backoff() { retryAt = SystemClock.elapsedRealtime() + retryDelay; retryDelay = minOf(retryDelay * 2, 3_600_000) }

  private fun scheduleContinuation() {
    if (!analyticsEnabled || !continuationQueued.compareAndSet(false, true)) return
    try {
      executor.schedule({ continuationQueued.set(false); synchronizeLater(force = true) }, maxOf(1000, maxOf(retryAt, serverRetryAt.get()) - SystemClock.elapsedRealtime()), TimeUnit.MILLISECONDS)
    } catch (_: RejectedExecutionException) { continuationQueued.set(false) }
  }

  private fun synchronizeLater(force: Boolean = false) {
    if (!analyticsEnabled) return
    if (force) forceRequested.set(true)
    if (!syncQueued.compareAndSet(false, true)) return
    val expected = generation.get()
    try {
      executor.execute {
        operationGeneration.set(expected)
        val forced = forceRequested.getAndSet(false)
        try {
          if (!synchronize(forced) && forced) forceRequested.set(true)
        } catch (_: Exception) { if (forced) forceRequested.set(true); report("sdk_internal_error"); backoff() }
        finally {
          operationGeneration.remove()
          syncQueued.set(false)
          if (forceRequested.get()) scheduleContinuation()
        }
      }
    } catch (_: RejectedExecutionException) { syncQueued.set(false) }
  }

  private fun <Value> enqueueResult(callback: ((DevHubResult<Value>) -> Unit)?, onFinished: () -> Unit = {}, block: () -> DevHubResult<Value>) {
    stateError()?.let { onFinished(); fail(callback, it); return }
    val expected = generation.get()
    val queuedAt = SystemClock.elapsedRealtime()
    try {
      interactiveExecutor.execute {
        operationGeneration.set(expected)
        operationDeadline.set(queuedAt + config.requestTimeoutMillis)
        val result = try {
          val error = stateError() ?: if (expected != generation.get()) "collection_disabled" else if (SystemClock.elapsedRealtime() - queuedAt > config.requestTimeoutMillis) "request_queue_timeout" else prepare()
          if (error != null) DevHubResult<Value>(error = error)
          else {
            val response = block()
            if (!collectionEnabled || expected != generation.get()) DevHubResult(error = stateError() ?: "collection_disabled") else response
          }
        } catch (_: Exception) { DevHubResult<Value>(error = "sdk_internal_error") }
        finally { operationGeneration.remove(); operationDeadline.remove(); onFinished() }
        complete(callback, result)
      }
    } catch (_: RejectedExecutionException) { onFinished(); fail(callback, if (closed) "sdk_closed" else "sdk_busy") }
  }

  private fun safely(block: () -> Unit) { try { block() } catch (_: Exception) { report("sdk_internal_error") } }
  private fun report(error: String) {
    try { main.post { try { config.onError(error) } catch (_: Exception) { android.util.Log.w("DevHub", "error_handler_failed") } } }
    catch (_: Exception) { }
  }
  private fun <Value> fail(callback: ((DevHubResult<Value>) -> Unit)?, error: String) { report(error); complete(callback, DevHubResult(error = error)) }
  private fun <Value> complete(callback: ((DevHubResult<Value>) -> Unit)?, result: DevHubResult<Value>) {
    if (callback != null) try { main.post { try { callback(result) } catch (_: Exception) { report("sdk_callback_failed") } } }
    catch (_: Exception) { report("callback_unavailable") }
  }
  private fun safeText(read: () -> String?): String = try { read()?.trim()?.take(128)?.takeIf { it.isNotEmpty() } ?: "unknown" } catch (_: Exception) { "unknown" }
  private fun appVersion(): String = safeText { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
  private fun utcNow(time: Long = System.currentTimeMillis()): String = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date(time))

  companion object {
    internal fun campaignInstance(key: String): DevHub? = synchronized(instances) { instances[key] }
    fun campaignAction(resultCode: Int, data: Intent?): CampaignAction? {
      if (resultCode != Activity.RESULT_OK || data == null) return null
      val id = data.getStringExtra("campaignId") ?: return null
      val type = try { CampaignType.valueOf(data.getStringExtra("campaignType") ?: return null) } catch (_: IllegalArgumentException) { return null }
      return if (id.matches(Regex("[0-9a-f-]{36}"))) CampaignAction(id, type) else null
    }
    private val instances = mutableMapOf<String, DevHub>()
    private val invalidEvents = setOf("validation_error", "invalid_event_time", "invalid_date", "properties_too_large")
    private fun boundedExecutor(name: String, capacity: Int): ThreadPoolExecutor = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(capacity), { task -> Thread(task, name).apply { isDaemon = true } }, ThreadPoolExecutor.AbortPolicy())
    private fun sensitiveProperty(name: String): Boolean = name.lowercase(Locale.US).replace(Regex("[^a-z0-9]"), "") in setOf("email", "phone", "phonenumber", "password", "token", "purchasetoken", "androidid", "advertisingid", "adid", "gaid", "imei", "imsi", "serial", "serialnumber", "mac", "macaddress", "latitude", "longitude", "location", "contacts", "installedapps", "clipboard")
    private fun digest(value: String): String = Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    fun initialize(context: Context, config: DevHubConfig): DevHub {
      return try {
        val url = URL(config.apiUrl)
        require(config.appKey.isNotBlank() && config.appKey.length <= 256 && !config.appKey.contains('\r') && !config.appKey.contains('\n'))
        require(url.host.isNotBlank() && url.userInfo == null && url.query == null && url.ref == null && url.path in setOf("", "/"))
        require(url.protocol == "https" || (config.allowLocalHttp && url.protocol == "http" && url.host in setOf("localhost", "127.0.0.1", "10.0.2.2")))
        require(config.storageNamespace.matches(Regex("[A-Za-z0-9_-]{1,64}")))
        require(config.connectTimeoutMillis in 100..30_000 && config.readTimeoutMillis in 100..30_000 && config.requestTimeoutMillis in 100..60_000 && config.maxResponseBytes in 1024..2_000_000)
        require(config.allowedEventProperties.size <= 32 && config.allowedEventProperties.all { it.matches(Regex("[A-Za-z][A-Za-z0-9_]{0,127}")) && !sensitiveProperty(it) })
        require(config.campaignRefreshIntervalMillis in 3_600_000L..7L * 86_400_000)
        require(config.campaignCacheTtlMillis == null || config.campaignCacheTtlMillis in 1L..7L * 86_400_000)
        require(config.playIntegrityCloudProjectNumber == null || config.playIntegrityCloudProjectNumber > 0)
        val application = try { context.applicationContext ?: context } catch (_: Exception) { context }
        val key = config.apiUrl.trimEnd('/') + ":" + application.packageName + ":" + config.storageNamespace
        synchronized(instances) {
          val existing = instances[key]
          if (existing != null && !existing.closed && existing.config != config) DevHub(application, config, "configuration_conflict")
          else if (existing != null && !existing.closed) existing
          else DevHub(application, config.copy(allowedEventProperties = config.allowedEventProperties.toSet())).also { instances[key] = it }
        }
      } catch (_: Exception) { DevHub(context, config, "invalid_configuration") }
    }
  }
}
