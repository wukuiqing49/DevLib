package com.wkq.google.rate

import android.content.Context
import android.content.SharedPreferences
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 从 SiteReport/PressIPTC 控制器提取的本地策略存储，移除报告、品牌和已评分判断。 */
internal object RateAppController {
    private val lock = Any()

    /** 使用独立偏好文件，不继承旧版把流程结束误记为已评分的数据。 */
    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences("google_review_prompt_v1", Context.MODE_PRIVATE)

    /** 读取或初始化首次使用状态，磁盘提交位于 IO 且进程内串行。 */
    suspend fun snapshot(context: Context, now: Long): GoogleRateState = withContext(Dispatchers.IO) {
        synchronized(lock) { read(prefs(context), now) }
    }

    /** 最近 128 个关键操作按哈希去重，不保存业务原始 ID，不无限增加偏好键。 */
    suspend fun record(context: Context, actionId: String, now: Long): Boolean = withContext(Dispatchers.IO) {
        require(actionId.isNotBlank() && actionId.length <= 512)
        val hash = MessageDigest.getInstance("SHA-256").digest(actionId.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        synchronized(lock) {
            val preferences = prefs(context)
            val state = read(preferences, now)
            val recent = preferences.getString("recent_actions", "").orEmpty().lineSequence().filter(String::isNotBlank).toList().takeLast(128)
            if (hash in recent) false else {
                save(preferences.edit().putString("recent_actions", (recent + hash).takeLast(128).joinToString("\n"))
                    .putInt("completed", increment(state.completedActions)))
                true
            }
        }
    }

    /** 在调用 Play 前原子占用一次提示时机，失败也保留冷却以免反复打扰。 */
    suspend fun reserve(context: Context, policy: GoogleRatePolicy, now: Long): GoogleRateDecision = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val preferences = prefs(context)
            val state = read(preferences, now)
            val decision = policy.evaluate(state, now)
            if (decision == GoogleRateDecision.ELIGIBLE) save(preferences.edit().putLong("last_request", now)
                .putInt("requests", increment(state.requestCount)))
            decision
        }
    }

    /** 用户关闭自动提示只影响自动请求，不封锁宿主的手动商店或反馈入口。 */
    suspend fun suppress(context: Context, value: Boolean) = withContext(Dispatchers.IO) {
        synchronized(lock) { save(prefs(context).edit().putBoolean("suppressed", value)) }
    }

    /** 恢复本地状态并保留异常时间供策略拒绝，不把损坏计数提升为无限资格。 */
    private fun read(preferences: SharedPreferences, now: Long): GoogleRateState {
        if (!preferences.contains("first_use")) save(preferences.edit().putLong("first_use", now))
        return GoogleRateState(preferences.getLong("first_use", now),
            if (preferences.contains("last_request")) preferences.getLong("last_request", 0) else null,
            preferences.getInt("completed", 0).coerceAtLeast(0), preferences.getInt("requests", 0).coerceAtLeast(0),
            preferences.getBoolean("suppressed", false))
    }

    /** 计数到达上限后保持饱和，防止整型回绕。 */
    private fun increment(value: Int) = if (value == Int.MAX_VALUE) value else value + 1

    /** 明确报告持久化失败，避免请求后冷却状态未保存却被当作成功。 */
    private fun save(editor: SharedPreferences.Editor) {
        if (!editor.commit()) throw IOException("Unable to persist review prompt state")
    }
}
