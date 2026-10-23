package com.devhub.sdk

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.ServerSocket
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 28, 35], manifest = Config.NONE)
class SdkTransportTest {
  private lateinit var server: MockWebServer
  private val transports = mutableListOf<SdkTransport>()
  @Before fun setup() { server = MockWebServer(); server.start() }
  @After fun cleanup() { transports.forEach { it.close() }; server.shutdown() }
  private fun config() = DevHubConfig(server.url("/").toString(), "test-key", allowLocalHttp = true, connectTimeoutMillis = 200, readTimeoutMillis = 200, requestTimeoutMillis = 500, maxResponseBytes = 1024)
  private fun transport(config: DevHubConfig = config()) = SdkTransport(config).also { transports.add(it) }

  @Test fun validJsonIsReadOnSupportedAndroidVersions() {
    server.enqueue(MockResponse().setBody("{\"ok\":true}").setHeader("X-Request-Id", "request-123"))
    val result = transport().json("/test", null, mapOf("X-DevHub-App-Key" to "test-key")) { true }
    assertTrue(result.toString(), result.successful)
    assertEquals(true, result.value!!.getBoolean("ok"))
    assertEquals("request-123", result.requestId)
    val request = server.takeRequest()
    assertEquals("test-key", request.getHeader("X-DevHub-App-Key"))
    assertEquals("DevHub-Android", request.getHeader("User-Agent"))
  }

  @Test fun disconnectedNetworkReturnsError() {
    val unusedPort = ServerSocket(0).use { it.localPort }
    val result = transport(config().copy(apiUrl = "http://127.0.0.1:$unusedPort")).json("/test", null, emptyMap()) { true }
    assertFalse(result.successful)
    assertTrue(result.error in setOf("network_error", "network_timeout"))
  }

  @Test fun stalledServerReturnsTimeout() {
    server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
    val started = System.nanoTime()
    val result = transport().json("/test", null, emptyMap()) { true }
    assertEquals("network_timeout", result.error)
    assertTrue(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started) < 3)
  }

  @Test fun oversizedResponseIsRejectedWithoutParsingIt() {
    server.enqueue(MockResponse().setBody("x".repeat(2048)))
    assertEquals("response_too_large", transport().json("/test", null, emptyMap()) { true }.error)
  }

  @Test fun malformedJsonIsNotConfusedWithNetworkFailure() {
    server.enqueue(MockResponse().setBody("not-json"))
    assertEquals("invalid_response", transport().json("/test", null, emptyMap()) { true }.error)
  }

  @Test fun rateLimitPreservesRetryAdviceAndDoesNotRetryPostAutomatically() {
    server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "120").setHeader("X-Request-Id", "trace-429").setBody("{\"error\":\"rate_limited\"}"))
    val result = transport().json("/test", org.json.JSONObject().put("action", "purchase"), emptyMap()) { true }
    assertEquals("rate_limited", result.error)
    assertEquals(429, result.statusCode)
    assertEquals(120_000L, result.retryAfterMillis)
    assertEquals("trace-429", result.requestId)
    assertEquals(1, server.requestCount)
  }

  @Test fun permissionFailureDoesNotEscape() {
    val client = SdkTransport(config()) { throw SecurityException("permission denied") }.also { transports.add(it) }
    assertEquals("network_permission_denied", client.json("/test", null, emptyMap()) { true }.error)
  }

  @Test fun disabledCollectionNeverOpensAConnection() {
    val result = transport().json("/test", null, emptyMap()) { false }
    assertEquals("collection_disabled", result.error)
    assertEquals(0, server.requestCount)
  }

  @Test fun redirectDoesNotForwardCredentialsToAnotherHost() {
    server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://example.com/private"))
    val result = transport().json("/test", null, mapOf("X-DevHub-App-Key" to "private-key")) { true }
    assertEquals("http_302", result.error)
    assertEquals(1, server.requestCount)
  }
}
