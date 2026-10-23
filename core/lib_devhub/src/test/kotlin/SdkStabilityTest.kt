package com.devhub.sdk

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Looper
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.annotation.SQLiteMode
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 28, 35], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
class SdkStabilityTest {
  private lateinit var context: Context
  private lateinit var server: MockWebServer
  private lateinit var namespace: String
  private val instances = mutableListOf<DevHub>()
  private val requests = java.util.concurrent.CopyOnWriteArrayList<RecordedRequest>()
  private var response: (RecordedRequest) -> MockResponse = { MockResponse() }

  @Before fun setup() {
    context = RuntimeEnvironment.getApplication()
    namespace = UUID.randomUUID().toString()
    server = MockWebServer()
    response = { request -> when {
      request.path!!.contains("/install") -> MockResponse().setBody("{\"created\":true}")
      request.path!!.contains("/active") -> MockResponse().setBody("{\"recorded\":true}")
      request.path!!.contains("/events/batch") -> MockResponse().setBody("{\"accepted\":${JSONObject(request.body.clone().readUtf8()).getJSONArray("events").length()},\"duplicate\":0}")
      request.path!!.contains("/campaigns") -> MockResponse().setBody("{\"campaigns\":[],\"hasMore\":false}")
      request.path!!.contains("/purchases/verify") -> MockResponse().setBody("{\"verified\":true,\"status\":\"active\"}")
      else -> MockResponse().setBody("{\"entitlements\":[]}")
    } }
    server.dispatcher = object : Dispatcher() {
      override fun dispatch(request: RecordedRequest): MockResponse { requests.add(request); return response(request) }
    }
    server.start()
  }

  @After fun cleanup() { instances.forEach { it.close() }; server.shutdown(); shadowOf(Looper.getMainLooper()).idle() }
  private fun config(enabled: Boolean = true) = DevHubConfig(server.url("/").toString(), "test-app-key", allowLocalHttp = true, storageNamespace = namespace, collectionEnabled = enabled, connectTimeoutMillis = 500, readTimeoutMillis = 500, requestTimeoutMillis = 2000)
  private fun initialize(config: DevHubConfig = config(), target: Context = context) = DevHub.initialize(target, config).also { instances.add(it) }
  private fun pump(condition: () -> Boolean) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(6)
    while (!condition() && System.nanoTime() < deadline) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10) }
    assertTrue("Timed out", condition())
  }
  @Suppress("UNCHECKED_CAST") private fun <Value> field(instance: DevHub, name: String): Value {
    val field = DevHub::class.java.declaredFields.first { it.name == name || it.name == "$name\$delegate" || it.name == "${name}Delegate" }.apply { isAccessible = true }
    val value = field.get(instance)
    return (if (value is Lazy<*>) value.value else value) as Value
  }
  private fun ready(instance: DevHub) {
    var result: DevHubResult<JSONArray>? = null
    instance.loadEntitlementsResult { result = it }
    pump { result != null }
    assertTrue(result.toString(), result!!.successful)
    pump { !field<AtomicBoolean>(instance, "syncQueued").get() && !field<AtomicBoolean>(instance, "activeQueued").get() }
  }

  @Test fun closeReleasesDatabaseOpenedByDisabledPurge() {
    val instance = initialize(config(false))
    var completed = false
    instance.setCollectionEnabled(false) { assertTrue(it.successful); completed = true }
    pump { completed }
    assertFalse(field<Boolean>(instance, "ready"))
    val database = field<EventStore>(instance, "store").readableDatabase
    assertTrue(database.isOpen)
    instance.close()
    pump { !database.isOpen }
  }

  @Test fun defaultConfigurationDoesNotCollectOrSendUntilEnabled() {
    val instance = initialize(config(false))
    val databaseFiles = context.databaseList().toSet()
    assertFalse(instance.track("open"))
    assertEquals("", instance.purchaseAccountId)
    var denied: String? = null
    instance.loadCampaignsResult { denied = it.error }
    pump { denied != null }
    assertEquals("collection_disabled", denied)
    assertEquals(databaseFiles, context.databaseList().toSet())
    assertEquals(0, requests.size)
    instance.setCollectionEnabled(true)
    ready(instance)
    val install = JSONObject(requests.first { it.path!!.contains("/install") }.body.clone().readUtf8())
    assertEquals(setOf("installId", "firstOpenAt", "appVersion", "manufacturer", "model", "androidVersion"), install.keys().asSequence().toSet())
    assertFalse(install.has("country"))
    assertFalse(install.has("androidId"))
  }

  @Test fun businessOnlyModeDoesNotStartTelemetryAndKeepsInteractiveRequestsAvailable() {
    val instance = initialize(config().copy(analyticsEnabled = false, collectLocale = true))
    assertFalse(instance.analyticsEnabled)
    assertTrue(instance.collectionEnabled)
    assertEquals(0, requests.size)
    assertFalse(instance.track("open"))
    var trackError: String? = null
    instance.trackAsync("open") { trackError = it.error }
    pump { trackError != null }
    assertEquals("analytics_disabled", trackError)
    ready(instance)
    var purchase: DevHubResult<JSONObject>? = null
    instance.verifyPurchase("user-supplied-token", "product") { purchase = it }
    pump { purchase != null }
    assertTrue(purchase.toString(), purchase!!.successful)
    assertEquals(true, purchase!!.value!!.getBoolean("verified"))
    assertEquals(1, requests.count { it.path!!.contains("/purchases/verify") })
    instance.start()
    instance.flush()
    assertEquals(0L, instance.pendingEventCount())
    assertFalse(requests.any { it.path!!.contains("/active") || it.path!!.contains("/events/batch") })
    val registration = JSONObject(requests.first { it.path!!.contains("/install") }.body.clone().readUtf8())
    assertEquals(setOf("installId", "firstOpenAt", "appVersion"), registration.keys().asSequence().toSet())
    instance.setCollectionEnabled(false)
    var denied: String? = null
    instance.loadEntitlementsResult { denied = it.error }
    pump { denied != null }
    assertEquals("collection_disabled", denied)
  }

  @Test fun switchingToBusinessOnlyPurgesQueuedAnalyticsWithoutChangingPurchaseIdentity() {
    val original = initialize(config())
    ready(original)
    val identity = original.purchaseAccountId
    assertTrue(original.track("pending"))
    assertEquals(1L, original.pendingEventCount())
    original.close()
    val instance = initialize(config().copy(analyticsEnabled = false))
    ready(instance)
    assertEquals(identity, instance.purchaseAccountId)
    assertEquals(0L, instance.pendingEventCount())
    assertFalse(field<SharedPreferences>(instance, "prefs").contains("active_pending"))
  }

  @Test fun businessOnlyModeStillRequiresExplicitMasterEnablement() {
    val instance = initialize(config(false).copy(analyticsEnabled = false))
    var denied: String? = null
    instance.loadEntitlementsResult { denied = it.error }
    pump { denied != null }
    assertEquals("collection_disabled", denied)
    assertEquals(0, requests.size)
    instance.setCollectionEnabled(true)
    ready(instance)
    assertFalse(requests.any { it.path!!.contains("/active") })
  }

  @Test fun disabledDeviceInformationIsNotAddedToRegistration() {
    val instance = initialize(config().copy(collectDeviceInfo = false))
    ready(instance)
    val install = JSONObject(requests.first { it.path!!.contains("/install") }.body.clone().readUtf8())
    assertEquals(setOf("installId", "firstOpenAt", "appVersion"), install.keys().asSequence().toSet())
  }

  @Test fun realtimeCampaignConfigurationFetchesOnEachExplicitCall() {
    val instance = initialize(config().copy(analyticsEnabled = false, campaignFetchMode = FetchMode.REALTIME))
    repeat(3) {
      var result: DevHubResult<JSONArray>? = null
      instance.loadCampaignsResult { result = it }
      pump { result != null }
      assertTrue(result.toString(), result!!.successful)
      assertFalse(result!!.fromCache)
    }
    assertEquals(3, requests.count { it.path!!.contains("/campaigns") })
  }

  @Test fun repeatedCampaignReadsAndSdkRestartUseThePersistedEmptyList() {
    val settings = config().copy(analyticsEnabled = false)
    val instance = initialize(settings)
    repeat(5) { index ->
      var result: DevHubResult<JSONArray>? = null
      instance.loadCampaignsResult { result = it }
      pump { result != null }
      assertTrue(result.toString(), result!!.successful)
      assertEquals(index > 0, result!!.fromCache)
    }
    instance.close()
    val restarted = initialize(settings)
    var result: DevHubResult<JSONArray>? = null
    restarted.loadCampaignsResult { result = it }
    pump { result != null }
    assertTrue(result!!.fromCache)
    assertEquals(1, requests.count { it.path!!.contains("/campaigns") })
  }

  @Test fun propertyAllowlistRejectsUnexpectedAndNonPrimitiveValues() {
    val instance = initialize(config().copy(allowedEventProperties = setOf("screen")))
    assertFalse(instance.track("open", mapOf("email" to "private@example.com")))
    assertFalse(instance.track("open", mapOf("screen" to mapOf("nested" to "value"))))
    assertFalse(instance.track("open", mapOf("screen" to Double.NaN)))
    assertTrue(instance.track("open", mapOf("screen" to "home")))
    assertEquals(1, instance.pendingEventCount())
  }

  @Test fun invalidConfigurationReturnsAnInertInstanceWithoutThrowing() {
    val instance = initialize(config().copy(apiUrl = "not a URL"))
    assertEquals("invalid_configuration", instance.initializationError)
    assertFalse(instance.track("open"))
    var error: String? = null
    instance.loadCampaignsResult { error = it.error }
    pump { error != null }
    assertEquals("invalid_configuration", error)
    assertEquals(0, requests.size)
  }

  @Test fun storageFailureDoesNotEscapeIntoApplication() {
    val broken = object : ContextWrapper(context) {
      override fun getApplicationContext(): Context = this
      override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = throw IllegalStateException("storage failure")
    }
    val instance = initialize(target = broken)
    assertFalse(instance.track("open"))
    var error: String? = null
    instance.loadCampaignsResult { error = it.error }
    pump { error != null }
    assertEquals("storage_unavailable", error)
    assertEquals(0, requests.size)
  }

  @Test fun missingPackageMetadataUsesSafeFallback() {
    val broken = object : ContextWrapper(context) {
      override fun getApplicationContext(): Context = this
      override fun getPackageManager(): PackageManager = throw IllegalStateException("metadata unavailable")
    }
    val instance = initialize(target = broken)
    ready(instance)
    val install = JSONObject(requests.first { it.path!!.contains("/install") }.body.clone().readUtf8())
    assertEquals("unknown", install.getString("appVersion"))
  }

  @Test fun corruptQueueRecordDoesNotBlockValidEvent() {
    val instance = initialize()
    ready(instance)
    val store = field<EventStore>(instance, "store")
    store.writableDatabase.execSQL("INSERT INTO events(event_id,payload) VALUES('broken','not-json')")
    assertTrue(instance.track("valid"))
    instance.flush()
    pump { instance.pendingEventCount() == 0L }
    val events = requests.filter { it.path!!.contains("/events/batch") }.flatMap { request ->
      val values = JSONObject(request.body.clone().readUtf8()).getJSONArray("events")
      (0 until values.length()).map { values.getJSONObject(it).getString("name") }
    }
    assertEquals(listOf("valid"), events)
  }

  @Test fun previouslyQueuedUnapprovedPropertiesAreNotUploaded() {
    val instance = initialize()
    ready(instance)
    val store = field<EventStore>(instance, "store")
    store.append(JSONObject().put("id", UUID.randomUUID().toString()).put("name", "old_event").put("time", "2026-01-01T00:00:00.000Z").put("properties", JSONObject().put("email", "private@example.com")).toString())
    instance.flush()
    pump { instance.pendingEventCount() == 0L }
    assertTrue(requests.none { it.path!!.contains("/events/batch") })
  }

  @Test fun failedSingleEventIsRetriedWithoutWaitingForBatchThreshold() {
    val instance = initialize()
    ready(instance)
    val original = response
    val batches = AtomicInteger()
    response = { request -> if (request.path!!.contains("/events/batch") && batches.incrementAndGet() == 1) MockResponse().setResponseCode(503).setBody("{\"error\":\"unavailable\"}") else original(request) }
    assertTrue(instance.track("single"))
    instance.flush()
    pump { batches.get() == 1 && !field<AtomicBoolean>(instance, "syncQueued").get() }
    assertEquals(1, instance.pendingEventCount())
    DevHub::class.java.getDeclaredField("retryAt").apply { isAccessible = true }.setLong(instance, 0)
    val synchronize = DevHub::class.java.getDeclaredMethod("synchronize", Boolean::class.javaPrimitiveType).apply { isAccessible = true }
    assertEquals(true, synchronize.invoke(instance, false))
    assertEquals(0, instance.pendingEventCount())
    assertEquals(2, batches.get())
  }

  @Test fun retryIntentSurvivesReinitialization() {
    val first = initialize()
    ready(first)
    val original = response
    val batches = AtomicInteger()
    response = { request -> if (request.path!!.contains("/events/batch") && batches.incrementAndGet() == 1) MockResponse().setResponseCode(503).setBody("{}") else original(request) }
    assertTrue(first.track("single"))
    first.flush()
    pump { batches.get() == 1 && !field<AtomicBoolean>(first, "syncQueued").get() }
    first.close()
    val second = initialize()
    pump { batches.get() >= 2 && second.pendingEventCount() == 0L }
    assertNull(second.initializationError)
  }

  @Test fun revocationClearsQueuedEventsAndBlocksNewRequests() {
    val instance = initialize()
    ready(instance)
    assertTrue(instance.track("private_event"))
    var disabled: DevHubResult<Boolean>? = null
    instance.setCollectionEnabled(false) { disabled = it }
    pump { disabled != null }
    assertTrue(disabled!!.successful)
    assertEquals(0, field<EventStore>(instance, "store").count())
    val before = requests.size
    assertFalse(instance.track("after_opt_out"))
    var error: String? = null
    instance.loadEntitlementsResult { error = it.error }
    pump { error != null }
    assertEquals("collection_disabled", error)
    assertEquals(before, requests.size)
  }

  @Test fun interactiveOperationsDoNotWaitForBlockedEventUpload() {
    val instance = initialize()
    ready(instance)
    val original = response
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    response = { request ->
      if (request.path!!.contains("/events/batch")) { entered.countDown(); release.await(5, TimeUnit.SECONDS) }
      original(request)
    }
    assertTrue(instance.track("single"))
    instance.flush()
    assertTrue(entered.await(2, TimeUnit.SECONDS))
    try {
      var result: DevHubResult<JSONArray>? = null
      instance.loadEntitlementsResult { result = it }
      pump { result != null }
      assertTrue(result.toString(), result!!.successful)
    } finally { release.countDown() }
  }

  @Test fun saturatedStorageQueueRejectsWithExplicitError() {
    val instance = initialize()
    ready(instance)
    val executor = field<ThreadPoolExecutor>(instance, "storageExecutor")
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    executor.execute { entered.countDown(); release.await(5, TimeUnit.SECONDS) }
    assertTrue(entered.await(2, TimeUnit.SECONDS))
    try {
      repeat(128) { executor.execute { } }
      var error: String? = null
      instance.trackAsync("overflow") { error = it.error }
      pump { error != null }
      assertEquals("sdk_busy", error)
    } finally { release.countDown() }
  }

  @Test fun backendInstallationErrorsPreserveStatusAndRequestId() {
    response = { MockResponse().setResponseCode(403).setHeader("X-Request-Id", "trace-123").setBody("{\"error\":\"app_disabled\"}") }
    val instance = initialize()
    var result: DevHubResult<JSONArray>? = null
    instance.loadEntitlementsResult { result = it }
    pump { result != null }
    assertEquals("app_disabled", result!!.error)
    assertEquals(403, result!!.statusCode)
    assertEquals("trace-123", result!!.requestId)
  }

  @Test fun queuedRequestsAreNotResurrectedAfterConsentIsWithdrawnAndGrantedAgain() {
    val instance = initialize()
    ready(instance)
    val executor = field<ThreadPoolExecutor>(instance, "interactiveExecutor")
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    executor.execute { entered.countDown(); release.await(5, TimeUnit.SECONDS) }
    assertTrue(entered.await(2, TimeUnit.SECONDS))
    val before = requests.count { it.path!!.contains("/entitlements") }
    var error: String? = null
    val callbacks = AtomicInteger()
    try {
      instance.loadEntitlementsResult { error = it.error; callbacks.incrementAndGet() }
      var disabled = false
      instance.setCollectionEnabled(false) { disabled = it.successful }
      pump { disabled }
      var enabled = false
      instance.setCollectionEnabled(true) { enabled = it.successful }
      pump { enabled }
    } finally { release.countDown() }
    pump { callbacks.get() > 0 }
    assertEquals("collection_disabled", error)
    assertEquals(1, callbacks.get())
    assertEquals(before, requests.count { it.path!!.contains("/entitlements") })
  }

  @Test fun revocationDoesNotDeliverAnInFlightSuccess() {
    val instance = initialize()
    ready(instance)
    val original = response
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    response = { request ->
      if (request.path!!.contains("/entitlements")) { entered.countDown(); release.await(5, TimeUnit.SECONDS) }
      original(request)
    }
    var result: DevHubResult<JSONArray>? = null
    instance.loadEntitlementsResult { result = it }
    assertTrue(entered.await(2, TimeUnit.SECONDS))
    try { instance.setCollectionEnabled(false) } finally { release.countDown() }
    pump { result != null }
    assertFalse(result!!.successful)
    assertEquals("collection_disabled", result!!.error)
  }

  @Test fun screenshotUploadsRequireExplicitConfiguration() {
    val instance = initialize()
    ready(instance)
    var error: String? = null
    instance.submitFeedback("user feedback", byteArrayOf(1, 2, 3), "image/png") { error = it.error }
    pump { error != null }
    assertEquals("feedback_images_disabled", error)
    assertTrue(requests.none { it.path!!.contains("/feedback") })
  }
}
