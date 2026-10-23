package com.devhub.sdk

import android.content.Context
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
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
class DevHubTest {
  private lateinit var context: Context
  private lateinit var server: MockWebServer
  private var sdk: DevHub? = null
  private lateinit var namespace: String

  @Before fun setup() {
    context = RuntimeEnvironment.getApplication()
    namespace = UUID.randomUUID().toString()
    server = MockWebServer()
    server.dispatcher = object : Dispatcher() {
      override fun dispatch(request: RecordedRequest): MockResponse = when {
        request.path!!.contains("/install") -> MockResponse().setResponseCode(201).setBody("{\"created\":true}")
        request.path!!.contains("/active") -> MockResponse().setBody("{\"recorded\":true}")
        request.path!!.contains("/events/batch") -> MockResponse().setBody("{\"accepted\":${JSONObject(request.body.readUtf8()).getJSONArray("events").length()},\"duplicate\":0}")
        request.path!!.contains("/campaigns") -> MockResponse().setBody("{\"campaigns\":[],\"hasMore\":false}")
        else -> MockResponse().setBody("{\"entitlements\":[]}")
      }
    }
    server.start()
  }
  @After fun cleanup() { sdk?.close(); server.shutdown(); shadowOf(Looper.getMainLooper()).idle() }
  private fun initialize(key: String = "app-key-test") = DevHub.initialize(context, DevHubConfig(server.url("/").toString(), key, allowLocalHttp = true, storageNamespace = namespace, collectionEnabled = true, allowedEventProperties = setOf("value"))).also { sdk = it }
  private fun pumpUntil(condition: () -> Boolean) {
    val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
    while (!condition() && System.nanoTime() < until) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10) }
    assertTrue("Timed out waiting for callback", condition())
  }

  @Test fun campaignImageIsReusedWithoutAnotherRequest() {
    val imageRequests = AtomicInteger()
    server.dispatcher = object : Dispatcher() {
      override fun dispatch(request: RecordedRequest): MockResponse = when {
        request.path!!.startsWith("/v1/sdk/images?") -> { imageRequests.incrementAndGet(); MockResponse().setBody("image-bytes") }
        request.path!!.contains("/install") -> MockResponse().setResponseCode(201).setBody("{\"created\":true}")
        request.path!!.contains("/active") -> MockResponse().setBody("{\"recorded\":true}")
        else -> MockResponse().setBody("{}")
      }
    }
    val instance = initialize()
    val path = "/v1/sdk/images?key=campaigns/test/image.png"
    var first: DevHubResult<ByteArray>? = null
    instance.loadCampaignImage(path) { first = it }
    pumpUntil { first != null }
    assertTrue(first!!.successful)
    var second: DevHubResult<ByteArray>? = null
    instance.loadCampaignImage(path) { second = it }
    pumpUntil { second != null }
    assertTrue(second!!.fromCache)
    assertEquals(1, imageRequests.get())
  }

  @Test fun eventStoreSurvivesReopenAndRejectsOverflow() {
    val name = "test-$namespace.db"
    var store = EventStore(context, name, 2)
    val first = JSONObject().put("id", "first").put("name", "open").toString()
    assertTrue(store.append(first))
    assertTrue(store.append(first))
    assertTrue(store.append(JSONObject().put("id", "second").toString()))
    assertFalse(store.append(JSONObject().put("id", "third").toString()))
    store.close()
    store = EventStore(context, name, 2)
    assertEquals(2L, store.count())
    store.remove(listOf(store.batch().first().first))
    assertEquals(1L, store.count())
    store.close()
  }

  @Test fun concurrentAppendsRespectCapacity() {
    val store = EventStore(context, "concurrent-$namespace.db", 10)
    val executor = Executors.newFixedThreadPool(4)
    repeat(50) { index -> executor.execute { store.append(JSONObject().put("id", "event-$index").toString()) } }
    executor.shutdown()
    assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
    assertEquals(10L, store.count())
    store.close()
  }

  @Test fun legacyImportIsTransactionalAndIdempotent() {
    val store = EventStore(context, "import-$namespace.db", 2)
    val batch = JSONArray().put(JSONObject().put("id", "first")).put(JSONObject().put("id", "second"))
    store.importOnce("old", batch)
    store.importOnce("old", batch)
    assertEquals(2L, store.count())
    assertThrows(IllegalStateException::class.java) { store.importOnce("overflow", JSONArray().put(JSONObject().put("id", "third"))) }
    assertEquals(2L, store.count())
    store.close()
  }

  @Test fun trackPersistsWhileNetworkIsBlocked() {
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    server.dispatcher = object : Dispatcher() {
      override fun dispatch(request: RecordedRequest): MockResponse {
        if (request.path!!.contains("install")) { entered.countDown(); release.await(5, TimeUnit.SECONDS) }
        return MockResponse().setBody("{\"created\":true,\"recorded\":true}")
      }
    }
    val instance = initialize()
    assertTrue(entered.await(3, TimeUnit.SECONDS))
    try { assertTrue(instance.track("offline_event")); assertEquals(1L, instance.pendingEventCount()) }
    finally { release.countDown() }
  }

  @Test fun asyncReceiptMeansPersistedAndRunsOnMain() {
    val instance = initialize()
    var result: DevHubResult<Boolean>? = null
    var mainThread = false
    instance.trackAsync("async_event") { value -> result = value; mainThread = Looper.myLooper() == Looper.getMainLooper() }
    pumpUntil { result != null }
    assertTrue(result!!.successful)
    assertTrue(mainThread)
    assertEquals(1L, instance.pendingEventCount())
  }

  @Test fun coreModeKeepsFirstReportStartedButDropsCustomEvents() {
    server.dispatcher = object : Dispatcher() {
      override fun dispatch(request: RecordedRequest): MockResponse = when {
        request.path!!.contains("/events/batch") -> MockResponse().setResponseCode(503)
        request.path!!.contains("/install") -> MockResponse().setResponseCode(201).setBody("{\"created\":true}")
        else -> MockResponse().setBody("{\"recorded\":true,\"entitlements\":[]}")
      }
    }
    val instance = DevHub.initialize(context, DevHubConfig(server.url("/").toString(), "app-key-test", allowLocalHttp = true, storageNamespace = namespace, collectionEnabled = true, analyticsMode = AnalyticsMode.CORE_ONLY)).also { sdk = it }
    assertTrue(instance.track("ignored_custom_event"))
    assertEquals(0L, instance.pendingEventCount())
    var first: DevHubResult<Boolean>? = null
    instance.reportFirstReportStarted { first = it }
    pumpUntil { first != null }
    assertTrue(first!!.successful)
    assertEquals(1L, instance.pendingEventCount())
    var duplicate: DevHubResult<Boolean>? = null
    instance.reportFirstReportStarted { duplicate = it }
    pumpUntil { duplicate != null }
    assertTrue(duplicate!!.successful)
    assertEquals(1L, instance.pendingEventCount())
  }

  @Test fun malformedResponseCompletesExactlyOnce() {
    val instance = initialize()
    server.dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest) = MockResponse().setBody("not-json") }
    val count = AtomicInteger()
    var error: String? = null
    instance.loadCampaignsResult { result -> error = result.error; count.incrementAndGet() }
    pumpUntil { count.get() > 0 }
    assertEquals("invalid_response", error)
    shadowOf(Looper.getMainLooper()).idle()
    assertEquals(1, count.get())
  }

  @Test fun closedSdkCompletesCallbacksWithError() {
    val instance = initialize()
    instance.close()
    var error: String? = null
    instance.loadEntitlementsResult { error = it.error }
    pumpUntil { error != null }
    assertEquals("sdk_closed", error)
  }

  @Test fun rotatingKeyKeepsInstallationIdentity() {
    val first = initialize("first-key")
    val identity = first.purchaseAccountId
    first.close()
    val second = initialize("second-key")
    assertEquals(identity, second.purchaseAccountId)
  }

  @Test fun invalidAsyncArgumentsCompleteOnceOnMainThread() {
    val instance = initialize()
    val errors = mutableListOf<String?>()
    var allOnMain = true
    fun record(result: DevHubResult<*>) {
      allOnMain = allOnMain && Looper.myLooper() == Looper.getMainLooper()
      errors.add(result.error)
    }
    instance.trackAsync(" ") { record(it) }
    instance.trackAsync("large", mapOf("value" to "x".repeat(4097))) { record(it) }
    instance.submitFeedback(" ") { record(it) }
    instance.submitFeedback("feedback", byteArrayOf(), "image/png") { record(it) }
    instance.verifyPurchase("", "product") { record(it) }
    instance.redeemCouponDetails(" ") { record(it) }
    instance.loadCampaignsPage(-1) { record(it) }
    instance.loadCampaignImage("https://example.com/image") { record(it) }
    pumpUntil { errors.size == 8 }
    shadowOf(Looper.getMainLooper()).idle()
    assertEquals(listOf("invalid_event_name", "event_properties_too_large", "invalid_feedback", "invalid_feedback_image", "invalid_purchase", "invalid_coupon_code", "invalid_campaign_offset", "invalid_image_path"), errors)
    assertTrue(allOnMain)
    assertEquals(0L, instance.pendingEventCount())
  }

  @Test fun synchronousTrackReturnsFalseForInvalidInputAndClosedSdk() {
    val instance = initialize()
    assertFalse(instance.track(" "))
    assertFalse(instance.track("large", mapOf("value" to "x".repeat(4097))))
    assertTrue(instance.track("valid"))
    instance.close()
    assertFalse(instance.track("after_close"))
    instance.start()
  }

  @Test fun persistenceFailureIsNotReportedAsQueueFull() {
    val instance = initialize()
    instance.pendingEventCount()
    val field = DevHub::class.java.getDeclaredField("storeDelegate").apply { isAccessible = true }
    val store = (field.get(instance) as Lazy<*>).value as EventStore
    store.writableDatabase.execSQL("DROP TABLE events")
    assertFalse(instance.track("failed"))
    var error: String? = null
    instance.trackAsync("failed_async") { error = it.error }
    pumpUntil { error != null }
    assertEquals("event_persistence_failed", error)
  }

  @Test fun callbackExceptionsAreReportedWithoutBreakingLaterCallbacks() {
    val errors = mutableListOf<String>()
    val instance = DevHub.initialize(context, DevHubConfig(server.url("/").toString(), "app-key-test", allowLocalHttp = true, storageNamespace = namespace, collectionEnabled = true, onError = { errors.add(it) })).also { sdk = it }
    instance.loadCampaignsPage { throw IllegalStateException("host_callback_failure") }
    pumpUntil { errors.contains("sdk_callback_failed") }
    var completed = false
    instance.loadCampaignsResult { completed = it.successful }
    pumpUntil { completed }
    assertEquals(1, errors.count { it == "sdk_callback_failed" })
  }

  @Test fun throwingErrorHandlerDoesNotPreventCompletion() {
    val count = AtomicInteger()
    val instance = DevHub.initialize(context, DevHubConfig(server.url("/").toString(), "app-key-test", allowLocalHttp = true, storageNamespace = namespace, collectionEnabled = true, onError = { count.incrementAndGet(); throw IllegalStateException("host_error_handler_failure") })).also { sdk = it }
    var error: String? = null
    instance.redeemCouponDetails("") { error = it.error }
    pumpUntil { error != null }
    assertEquals("invalid_coupon_code", error)
    assertEquals(1, count.get())
  }

  @Test fun malformedCampaignItemCompletesWithErrorExactlyOnce() {
    val instance = initialize()
    server.dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest) = MockResponse().setBody("{\"campaigns\":[42],\"hasMore\":false}") }
    val errors = mutableListOf<String?>()
    instance.loadCampaignsResult { errors.add(it.error) }
    pumpUntil { errors.isNotEmpty() }
    shadowOf(Looper.getMainLooper()).idle()
    assertEquals(listOf("invalid_campaign_response"), errors)
  }

  @Test fun missingEntitlementsIsNotSuccessfulEmptyData() {
    val instance = initialize()
    server.dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest) = MockResponse().setBody(if (request.path!!.contains("/install")) "{\"created\":true}" else "{}") }
    var result: DevHubResult<JSONArray>? = null
    instance.loadEntitlementsResult { result = it }
    pumpUntil { result != null }
    assertFalse(result!!.successful)
    assertEquals("invalid_entitlements_response", result!!.error)
  }

  @Test fun htmlErrorResponsePreservesHttpStatus() {
    val instance = initialize()
    server.dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(503).setBody("<html>Unavailable</html>") }
    var error: String? = null
    instance.loadCampaignsPage { error = it.error }
    pumpUntil { error != null }
    assertEquals("http_503", error)
  }

  @Test fun serverBusinessErrorIsPreserved() {
    val instance = initialize()
    server.dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(403).setBody("{\"error\":\"app_disabled\"}") }
    var error: String? = null
    instance.loadCampaignsPage { error = it.error }
    pumpUntil { error != null }
    assertEquals("app_disabled", error)
  }
}
