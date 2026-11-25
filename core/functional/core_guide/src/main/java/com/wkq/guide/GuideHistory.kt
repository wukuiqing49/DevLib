package com.wkq.guide

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64

/** 阅读和业务完成独立存储，宿主必须显式标记业务成功，不根据下一步推定。 */
enum class GuideRecord { SHOWN, SKIPPED, READ, BUSINESS_COMPLETED }

/** 可选本地记录组件；不同宿主、引导 ID、版本分别隔离，不自动控制弹出时机。 */
class GuideHistory(context: Context, namespace: String = "default") {
    private val preferences: SharedPreferences = context.applicationContext.getSharedPreferences("core-guide-$namespace", Context.MODE_PRIVATE)

    /** 读取该版本已发生的事件；返回独立集合，调用方不能修改底层状态。 */
    @Synchronized
    fun records(id: String, version: Int): Set<GuideRecord> = preferences.getStringSet(key(id, version), emptySet()).orEmpty()
        .mapNotNull { name -> GuideRecord.entries.find { it.name == name } }.toSet()

    /** 合并记录事件；业务成功必须由宿主在真实结果返回后调用。 */
    fun mark(id: String, version: Int, record: GuideRecord) {
        synchronized(preferences) {
            val values = preferences.getStringSet(key(id, version), emptySet()).orEmpty().toMutableSet()
            values += record.name
            preferences.edit().putStringSet(key(id, version), values).apply()
        }
    }

    /** 重置一个指定版本，不清空宿主其他引导记录。 */
    fun reset(id: String, version: Int) { synchronized(preferences) { preferences.edit().remove(key(id, version)).apply() } }

    /** 对 ID 编码避免分隔符冲突，并拒绝无效版本。 */
    private fun key(id: String, version: Int): String {
        require(id.isNotBlank() && version >= 1)
        return Base64.encodeToString(id.toByteArray(Charsets.UTF_8), Base64.NO_WRAP or Base64.URL_SAFE) + ":" + version
    }
}
