package com.wkq.iptc.upload

import com.google.gson.JsonElement
import com.google.gson.JsonParser

/** 在旧配置的宽松读取兜底之前阻止协议被悄悄改写；原始配置与密码保持不动。 */
internal fun validateStoredUploadProtocols(json: String) {
    if (json.isBlank()) return
    // 损坏 JSON 仍沿用配置存储原有的解析失败策略。
    val root = runCatching { JsonParser.parseString(json) }.getOrNull() ?: return
    fun validate(element: JsonElement) {
        when {
            element.isJsonArray -> element.asJsonArray.forEach(::validate)
            element.isJsonObject -> {
                val obj = element.asJsonObject
                listOf("protocol", "configType").forEach { key ->
                    obj.get(key)?.let { UploadProtocolType.fromValue(it.asString) }
                }
                obj.get("config")?.let(::validate)
            }
        }
    }
    validate(root)
}
