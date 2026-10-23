package com.devhub.sdk

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

internal class CampaignCache(
  private val prefs: SharedPreferences,
  private val config: DevHubConfig,
  private val report: (String) -> Unit,
  private val now: () -> Long = System::currentTimeMillis
) {
  private var memory: JSONArray? = null
  private var memorySavedAt = 0L
  private var memoryRetryAt = 0L
  fun load(forceRefresh: Boolean, fetch: () -> DevHubResult<JSONArray>): DevHubResult<JSONArray> {
    if (config.campaignFetchMode == FetchMode.REALTIME) {
      val result = fetch()
      return if (result.successful) result.copy(value = filter(result.value!!, now())) else result
    }
    val time = now()
    var cached: JSONArray? = memory
    var savedAt = memorySavedAt
    var retryAt = memoryRetryAt
    try {
      if (cached == null) {
        val raw = prefs.getString("campaign_cache", null)
        if (raw != null && raw.toByteArray(Charsets.UTF_8).size <= config.maxResponseBytes) cached = JSONArray(raw)
        savedAt = prefs.getLong("campaign_saved_at", 0)
      }
      retryAt = maxOf(retryAt, prefs.getLong("campaign_retry_at", 0))
    } catch (_: Exception) { report("campaign_cache_unavailable") }
    val age = time - savedAt
    val usable = cached != null && age in 0..7L * 86_400_000
    val ttl = config.campaignCacheTtlMillis ?: config.campaignRefreshIntervalMillis
    if (!forceRefresh && usable && age < ttl) return cached(cached!!, false, time)
    if (forceRefresh && usable && age < minOf(60_000, ttl)) return cached(cached!!, false, time)
    if (retryAt > time && retryAt - time <= 86_400_000) {
      return if (usable && config.allowStaleCampaignsOnError) cached(cached!!, true, time) else DevHubResult(error = "campaign_refresh_cooldown", retryAfterMillis = retryAt - time)
    }
    memoryRetryAt = time + 3_600_000
    persist { putLong("campaign_retry_at", memoryRetryAt) }
    val result = fetch()
    if (!result.successful) {
      if (result.statusCode in setOf(401, 403)) {
        memory = null
        memorySavedAt = 0
        persist { remove("campaign_cache").remove("campaign_saved_at") }
        return result
      }
      val delay = maxOf(3_600_000L, result.retryAfterMillis ?: 0).coerceAtMost(86_400_000)
      memoryRetryAt = now() + delay
      persist { putLong("campaign_retry_at", memoryRetryAt) }
      return if (usable && config.allowStaleCampaignsOnError) cached(cached!!, true, time) else result
    }
    val serialized = result.value!!.toString()
    if (serialized.toByteArray(Charsets.UTF_8).size > config.maxResponseBytes) return DevHubResult(error = "response_too_large")
    val completed = now()
    memory = JSONArray(serialized)
    memorySavedAt = completed
    memoryRetryAt = 0
    persist { putString("campaign_cache", serialized).putLong("campaign_saved_at", completed).putLong("campaign_retry_at", memoryRetryAt) }
    return DevHubResult(filter(result.value, completed), statusCode = result.statusCode, requestId = result.requestId)
  }

  private fun cached(values: JSONArray, stale: Boolean, time: Long) = DevHubResult(filter(values, time), fromCache = true, stale = stale)
  private fun filter(values: JSONArray, time: Long): JSONArray = JSONArray().apply {
    for (index in 0 until values.length()) {
      val campaign = values.optJSONObject(index) ?: continue
      val start = timestamp(campaign.optString("startAt")) ?: continue
      val end = if (campaign.isNull("endAt")) Long.MAX_VALUE else timestamp(campaign.optString("endAt")) ?: continue
      if (campaign.optString("status", "active") == "active" && start <= time && time < end) put(JSONObject(campaign.toString()))
    }
  }
  private fun timestamp(value: String): Long? {
    val position = ParsePosition(0)
    val parser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC"); isLenient = false }
    return parser.parse(value, position)?.time?.takeIf { position.index == value.length }
  }
  private fun persist(edit: SharedPreferences.Editor.() -> SharedPreferences.Editor) {
    try { if (!prefs.edit().edit().commit()) report("campaign_cache_unavailable") }
    catch (_: Exception) { report("campaign_cache_unavailable") }
  }
}
