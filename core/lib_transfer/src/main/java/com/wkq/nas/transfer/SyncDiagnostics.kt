package com.wkq.nas.transfer

import android.content.Context
import android.os.SystemClock
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

enum class DiagnosticEvent { ACTION, ENQUEUED, WORKER_START, SCAN_START, SCAN_END, FILE_START, COMMIT, FILE_END, WAITING, CONTROL, RECOVERY, RESULT, PROBE, DEVICE_SIGNAL, GAP }
enum class DiagnosticOutcome { QUEUED, RUNNING, SUCCEEDED, SKIPPED, FAILED, CANCELED, PAUSED, UNCHANGED, RETRY_WAIT }
enum class DiagnosticTrigger { MANUAL, PERIODIC, DEVICE, RECOVERY }

/** 只接受枚举、数字和匿名引用，异常文本与业务路径没有进入日志的接口。 */
class SyncDiagnostics(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("sync-diagnostics", Context.MODE_PRIVATE)
    private val directory = File(app.filesDir, "sync-diagnostics")
    fun detailedUntil() = prefs.getLong("detailedUntil", 0)
    fun detailed(enabled: Boolean) { prefs.edit().putLong("detailedUntil", if (enabled) System.currentTimeMillis() + 2 * 3600000 else 0).commit() }
    fun event(event: DiagnosticEvent, run: String = "", plan: String = "", endpoint: String = "", file: String = "",
        reason: TransferError? = null, count: Long = -1, bytes: Long = -1, detail: Boolean = false,
        outcome: DiagnosticOutcome? = null, target: String = "", attempt: Int = 0, verifyRequested: Boolean? = null,
        kind: TransferKind? = null, trigger: DiagnosticTrigger? = null) {
        synchronized(lock) {
            try {
                if (detail && detailedUntil() < System.currentTimeMillis()) return
                directory.mkdirs()
                trim()
                val sequence = prefs.getLong("sequence", 0) + 1
                if (!prefs.edit().putLong("sequence", sequence).commit()) return
                val session = prefs.getString("session", null) ?: UUID.randomUUID().toString().also { prefs.edit().putString("session", it).commit() }
                val row = JSONObject().put("schemaVersion", 1).put("sessionId", session).put("sequence", sequence)
                    .put("utcMillis", System.currentTimeMillis()).put("offsetMinutes", java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60000)
                    .put("elapsedMillis", SystemClock.elapsedRealtime()).put("process", process).put("event", event.name)
                    .put("run", alias(run)).put("plan", alias(plan)).put("endpoint", alias(endpoint)).put("file", alias(file))
                    .put("reason", reason?.name.orEmpty()).put("count", count).put("bytes", bytes)
                    .put("outcome", outcome?.name.orEmpty()).put("target", alias(target)).put("attempt", attempt)
                    .put("contentVerificationRequested", verifyRequested ?: JSONObject.NULL)
                    .put("kind", kind?.name.orEmpty()).put("trigger", trigger?.name.orEmpty())
                if (prefs.getBoolean("gap", false)) row.put("diagnosticGap", true)
                val logs = directory.listFiles().orEmpty().filter { it.extension == "jsonl" }.sortedBy { it.name }
                val current = logs.lastOrNull()?.takeIf { it.length() < 1024 * 1024 } ?: File(directory, String.format(java.util.Locale.ROOT, "%020d.jsonl", sequence))
                current.appendText(row.toString() + "\n", Charsets.UTF_8)
                prefs.edit().putBoolean("gap", false).commit()
            } catch (_: Exception) { runCatching { prefs.edit().putBoolean("gap", true).commit() } }
        }
    }
    private fun alias(value: String): String {
        if (value.isEmpty()) return ""
        // 私有随机密钥使低熵名称不可被字典猜测；不保存原值映射，也不导出密钥。
        val secret = prefs.getString("aliasSecret", null) ?: UUID.randomUUID().toString().also { prefs.edit().putString("aliasSecret", it).commit() }
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(javax.crypto.spec.SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return UUID.nameUUIDFromBytes(mac.doFinal(value.toByteArray(Charsets.UTF_8))).toString()
    }
    private fun trim() {
        val logs = directory.listFiles().orEmpty().filter { it.extension == "jsonl" }.sortedBy { it.name }
        var total = logs.sumOf { it.length() }
        logs.forEach { if (it.lastModified() < System.currentTimeMillis() - 7 * 86400000L || total >= 19 * 1024 * 1024) {
            val size = it.length(); if (it.delete()) { total -= size; prefs.edit().putBoolean("truncated", true).commit() }
        } }
    }
    fun summary(): String = synchronized(lock) {
        "schemaVersion=1\nfiles=${directory.listFiles().orEmpty().count { it.extension == "jsonl" }}\nbytes=${directory.listFiles().orEmpty().sumOf { it.length() }}\ntruncated=${prefs.getBoolean("truncated", false)}\ngap=${prefs.getBoolean("gap", false)}\n"
    }
    fun export(output: java.io.OutputStream, run: String = "") = synchronized(lock) {
        trim()
        val runAlias = alias(run)
        val info = app.packageManager.getPackageInfo(app.packageName, 0)
        ZipOutputStream(output).use { zip ->
            fun entry(name: String, text: String) { zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray(Charsets.UTF_8)); zip.closeEntry() }
            entry("manifest.json", JSONObject().put("schemaVersion", 1).put("appVersion", info.versionName)
                .put("api", android.os.Build.VERSION.SDK_INT).put("truncated", prefs.getBoolean("truncated", false))
                .put("gap", prefs.getBoolean("gap", false)).put("scope", if (run.isEmpty()) "session" else "run").toString())
            entry("summary.txt", "同步诊断包：按 sessionId、sequence 阅读时间线。run/plan/endpoint/file 为匿名代号。\n入队不等于执行；最后事件不是异常退出根因；缺失或截断不得推断成功。\n" + summary())
            zip.putNextEntry(ZipEntry("events.jsonl"))
            directory.listFiles().orEmpty().filter { it.extension == "jsonl" }.sortedBy { it.name }.forEach { log ->
                log.forEachLine(Charsets.UTF_8) { line ->
                    try {
                        val row = JSONObject(line)
                        if (run.isEmpty() || runAlias.isNotEmpty() && row.optString("run") == runAlias) zip.write((clean(row).toString() + "\n").toByteArray(Charsets.UTF_8))
                    } catch (_: Exception) { zip.write("{\"event\":\"GAP\"}\n".toByteArray()) }
                }
            }
            zip.closeEntry()
        }
    }
    private fun clean(row: JSONObject): JSONObject {
        val result = JSONObject()
        val uuid = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        listOf("sessionId", "process", "run", "plan", "endpoint", "target", "file").forEach { key ->
            val value = row.optString(key); result.put(key, value.takeIf { it.isEmpty() || uuid.matches(it) }.orEmpty())
        }
        listOf("schemaVersion", "sequence", "utcMillis", "offsetMinutes", "elapsedMillis", "count", "bytes", "attempt").forEach { key ->
            if (row.opt(key) is Number) result.put(key, row.getLong(key))
        }
        val sets = mapOf("event" to DiagnosticEvent.entries.map { it.name }, "reason" to TransferError.entries.map { it.name },
            "outcome" to DiagnosticOutcome.entries.map { it.name }, "kind" to TransferKind.entries.map { it.name }, "trigger" to DiagnosticTrigger.entries.map { it.name })
        sets.forEach { (key, values) -> result.put(key, row.optString(key).takeIf { it in values }.orEmpty()) }
        listOf("contentVerificationRequested", "diagnosticGap").forEach { if (row.opt(it) is Boolean) result.put(it, row.getBoolean(it)) }
        return result
    }
    fun clear() = synchronized(lock) {
        directory.listFiles().orEmpty().forEach { if (!it.delete()) throw java.io.IOException("diagnostic cleanup") }
        prefs.edit().clear().commit()
    }
    companion object { private val lock = Any(); private val process = UUID.randomUUID().toString() }
}
