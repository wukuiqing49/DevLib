package com.devhub.sdk

import android.os.SystemClock
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal class SdkTransport(private val config: DevHubConfig, private val openConnection: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection }) {
  private val connections = mutableSetOf<HttpURLConnection>()
  private val deadlines = ScheduledThreadPoolExecutor(2) { task -> Thread(task, "devhub-deadline").apply { isDaemon = true } }.apply { removeOnCancelPolicy = true }

  fun cancel() {
    val pending = synchronized(connections) { connections.toList().also { connections.clear() } }
    pending.forEach { active ->
      try { deadlines.execute { try { active.disconnect() } catch (_: Exception) { } } } catch (_: Exception) { }
    }
  }
  fun close() { cancel(); deadlines.shutdown() }

  fun json(path: String, body: JSONObject?, headers: Map<String, String>, deadlineMillis: Long? = null, allowed: () -> Boolean): DevHubResult<JSONObject> {
    val response = execute(path, body, headers, config.maxResponseBytes, deadlineMillis, allowed)
    if (!response.successful) return response.failure()
    val parsed = try { JSONObject(String(response.value!!, Charsets.UTF_8)) } catch (_: Exception) { return response.failure("invalid_response") }
    return DevHubResult(parsed, statusCode = response.statusCode, requestId = response.requestId, retryAfterMillis = response.retryAfterMillis)
  }

  fun image(path: String, headers: Map<String, String>, deadlineMillis: Long? = null, allowed: () -> Boolean): DevHubResult<ByteArray> = execute(path, null, headers, 5_000_000, deadlineMillis, allowed)

  private fun execute(path: String, body: JSONObject?, headers: Map<String, String>, maximum: Int, deadlineMillis: Long?, allowed: () -> Boolean): DevHubResult<ByteArray> {
    var connection: HttpURLConnection? = null
    var status: Int? = null
    var requestId: String? = null
    var retryAfter: Long? = null
    var timer: ScheduledFuture<*>? = null
    val timedOut = AtomicBoolean(false)
    try {
      if (!allowed()) return DevHubResult(error = "collection_disabled")
      val deadline = minOf(SystemClock.elapsedRealtime() + config.requestTimeoutMillis, deadlineMillis ?: Long.MAX_VALUE)
      val remaining = deadline - SystemClock.elapsedRealtime()
      if (remaining <= 0) return DevHubResult(error = "network_timeout")
      val active = openConnection(URL(config.apiUrl.trimEnd('/') + path))
      connection = active
      active.connectTimeout = minOf(config.connectTimeoutMillis, remaining.toInt().coerceAtLeast(1))
      active.readTimeout = minOf(config.readTimeoutMillis, remaining.toInt().coerceAtLeast(1))
      active.instanceFollowRedirects = false
      active.setRequestProperty("User-Agent", "DevHub-Android")
      headers.forEach { (name, value) -> active.setRequestProperty(name, value) }
      synchronized(connections) { connections.add(active) }
      if (!allowed()) return DevHubResult(error = "collection_disabled")
      timer = deadlines.schedule({ timedOut.set(true); try { active.disconnect() } catch (_: Exception) { } }, remaining, TimeUnit.MILLISECONDS)
      if (body != null) {
        active.requestMethod = "POST"
        active.doOutput = true
        active.setRequestProperty("Content-Type", "application/json")
        val bytes = body.toString().toByteArray(Charsets.UTF_8)
        active.setFixedLengthStreamingMode(bytes.size)
        active.outputStream.use { output -> output.write(bytes) }
      }
      status = active.responseCode
      requestId = active.getHeaderField("X-Request-Id")?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,128}")) }
      retryAfter = retryAfter(active.getHeaderField("Retry-After"))
      val output = ByteArrayOutputStream()
      val stream = if (status in 200..299) active.inputStream else active.errorStream
      stream?.use { input ->
        val buffer = ByteArray(8192)
        while (true) {
          if (!allowed()) return DevHubResult(error = "collection_disabled")
          if (SystemClock.elapsedRealtime() >= deadline) throw SocketTimeoutException()
          val count = input.read(buffer)
          if (count < 0) break
          if (output.size() + count > maximum) return DevHubResult(error = "response_too_large", statusCode = status, requestId = requestId, retryAfterMillis = retryAfter)
          output.write(buffer, 0, count)
        }
      }
      if (!allowed()) return DevHubResult(error = "collection_disabled")
      if (timedOut.get() || SystemClock.elapsedRealtime() >= deadline) throw SocketTimeoutException()
      val bytes = output.toByteArray()
      if (status !in 200..299) {
        val code = try { JSONObject(String(bytes, Charsets.UTF_8)).optString("error") } catch (_: Exception) { "" }
        val safeCode = code.takeIf { it.matches(Regex("[a-z][a-z0-9_]{0,127}")) } ?: "http_$status"
        return DevHubResult(error = safeCode, statusCode = status, requestId = requestId, retryAfterMillis = retryAfter)
      }
      return DevHubResult(bytes, statusCode = status, requestId = requestId, retryAfterMillis = retryAfter)
    } catch (_: SocketTimeoutException) { return DevHubResult(error = if (allowed()) "network_timeout" else "collection_disabled", statusCode = status, requestId = requestId, retryAfterMillis = retryAfter) }
    catch (_: IOException) { return DevHubResult(error = if (!allowed()) "collection_disabled" else if (timedOut.get()) "network_timeout" else "network_error", statusCode = status, requestId = requestId, retryAfterMillis = retryAfter) }
    catch (_: SecurityException) { return DevHubResult(error = "network_permission_denied") }
    catch (_: Exception) { return DevHubResult(error = "sdk_internal_error") }
    finally {
      timer?.cancel(false)
      connection?.let { active ->
        synchronized(connections) { connections.remove(active) }
        try { active.disconnect() } catch (_: Exception) { }
      }
    }
  }

  private fun retryAfter(value: String?): Long? {
    if (value == null) return null
    val seconds = value.trim().toLongOrNull()
    if (seconds != null) return seconds.coerceIn(0, 86_400) * 1000
    return try {
      val date = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).apply { timeZone = TimeZone.getTimeZone("GMT"); isLenient = false }.parse(value)
      date?.let { (it.time - System.currentTimeMillis()).coerceIn(0, 86_400_000) }
    } catch (_: Exception) { null }
  }
}
