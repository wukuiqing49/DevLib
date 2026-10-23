package com.devhub.sdk

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 28, 35], manifest = Config.NONE)
class CampaignCacheTest {
  private val config = DevHubConfig("https://example.com", "test-key")
  private lateinit var prefs: android.content.SharedPreferences
  private var time = 1_800_000_000_000L
  private var requests = 0
  private fun cache(settings: DevHubConfig = config) = CampaignCache(prefs, settings, {}, { time })
  private fun empty(): DevHubResult<JSONArray> { requests++; return DevHubResult(JSONArray()) }
  @Before fun setup() { prefs = RuntimeEnvironment.getApplication().getSharedPreferences(UUID.randomUUID().toString(), Context.MODE_PRIVATE) }

  @Test fun emptyListsAreCachedAcrossInstancesAndExpireAfterThreeDays() {
    assertFalse(cache().load(false, ::empty).fromCache)
    repeat(20) { assertTrue(cache().load(false, ::empty).fromCache) }
    assertEquals(1, requests)
    time += 3L * 86_400_000 + 1
    assertFalse(cache().load(false, ::empty).fromCache)
    assertEquals(2, requests)
  }
  @Test fun forcedRefreshHasAMinimumInterval() {
    val cache = cache()
    cache.load(false, ::empty)
    cache.load(true, ::empty)
    assertEquals(1, requests)
    time += 60_001
    cache.load(true, ::empty)
    assertEquals(2, requests)
  }
  @Test fun failedRefreshUsesStaleCacheAndPersistsCooldown() {
    cache().load(false, ::empty)
    time += 3L * 86_400_000 + 1
    val failed = cache().load(false) { requests++; DevHubResult(error = "network_error") }
    assertTrue(failed.fromCache)
    assertTrue(failed.stale)
    assertTrue(cache().load(true, ::empty).stale)
    assertEquals(2, requests)
    time += 3_600_001
    cache().load(false, ::empty)
    assertEquals(3, requests)
  }
  @Test fun absentCacheAndOfflineRequestsDoNotHammerTheServer() {
    assertEquals("network_error", cache().load(false) { requests++; DevHubResult(error = "network_error") }.error)
    assertEquals("campaign_refresh_cooldown", cache().load(false, ::empty).error)
    assertEquals(1, requests)
  }
  @Test fun cachedExpiredCampaignsAreFilteredAndReturnedValuesAreIsolated() {
    time = 1_788_912_000_000L
    val values = JSONArray().put(JSONObject().put("id", "campaign").put("startAt", "2020-01-01T00:00:00.000Z").put("endAt", "2026-09-10T00:00:00.000Z"))
    val cache = cache()
    val result = cache.load(false) { DevHubResult(values) }
    assertEquals(1, result.value!!.length())
    result.value.getJSONObject(0).put("id", "mutated")
    assertEquals("campaign", cache.load(false, ::empty).value!!.getJSONObject(0).getString("id"))
    time = 1_789_084_800_000L
    val expired = cache.load(false) { DevHubResult(error = "network_error") }
    assertEquals(0, expired.value!!.length())
  }
  @Test fun weekOldCacheIsNotShownAfterRefreshFailure() {
    cache().load(false, ::empty)
    time += 7L * 86_400_000 + 1
    assertEquals("network_error", cache().load(false) { DevHubResult(error = "network_error") }.error)
  }
  @Test fun authorizationFailureDoesNotReturnPreviouslyCachedCampaigns() {
    cache().load(false, ::empty)
    time += 3L * 86_400_000 + 1
    val result = cache().load(false) { DevHubResult(error = "unauthorized", statusCode = 401) }
    assertEquals("unauthorized", result.error)
    assertFalse(result.fromCache)
    assertFalse(prefs.contains("campaign_cache"))
  }
  @Test fun realtimeFetchesEveryCallWithoutReadingOrReplacingStoredCache() {
    cache().load(false, ::empty)
    val stored = prefs.all.toMap()
    val realtime = cache(config.copy(campaignFetchMode = FetchMode.REALTIME))
    repeat(3) { assertFalse(realtime.load(false, ::empty).fromCache) }
    assertEquals(4, requests)
    assertEquals("network_error", realtime.load(false) { DevHubResult(error = "network_error") }.error)
    assertEquals(stored, prefs.all)
  }
  @Test fun customTtlRefreshesAtExpiryAndStartsAgainAfterSuccess() {
    val cache = cache(config.copy(campaignCacheTtlMillis = 5000))
    cache.load(false, ::empty)
    time += 4999
    assertTrue(cache.load(false, ::empty).fromCache)
    time++
    assertFalse(cache.load(false, ::empty).fromCache)
    time += 4999
    assertTrue(cache.load(false, ::empty).fromCache)
    assertEquals(2, requests)
  }
  @Test fun disablingStaleFallbackReturnsErrorsAndDoesNotExtendFreshness() {
    val settings = config.copy(campaignCacheTtlMillis = 5000, allowStaleCampaignsOnError = false)
    cache(settings).load(false, ::empty)
    val savedAt = prefs.getLong("campaign_saved_at", 0)
    time += 5000
    val failure = cache(settings).load(false) { DevHubResult(error = "network_error") }
    assertEquals("network_error", failure.error)
    assertNull(failure.value)
    assertEquals(savedAt, prefs.getLong("campaign_saved_at", 0))
    assertEquals("campaign_refresh_cooldown", cache(settings).load(false, ::empty).error)
  }
}
