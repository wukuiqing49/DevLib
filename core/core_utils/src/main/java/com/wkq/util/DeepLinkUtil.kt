package com.wkq.util

import android.content.Intent
import android.net.Uri

/**
 * DeepLink 解析与轻量存储工具。
 *
 * 解析类方法会尽量兜底，空链接或异常链接不会向外抛出。存储类方法复用 [SpUtils]，
 * 使用前需要先调用 [CoreUtils.init] 或 [SpUtils.init] 完成 MMKV 初始化。
 */
object DeepLinkUtil {

    private const val STORAGE_ID = "android_core_utils_deep_link"
    private const val KEY_LAST_URL = "last_url"
    private const val KEY_LAST_TIME = "last_time"
    private const val DATA_PREFIX = "data_"

    /**
     * DeepLink 解析结果。
     *
     * [params] 保存每个 query key 的第一个值；[allParams] 保存重复 key 的全部值。
     */
    data class DeepLinkData(
        val raw: String,
        val scheme: String?,
        val host: String?,
        val path: String?,
        val lastPathSegment: String?,
        val pathSegments: List<String>,
        val params: Map<String, String>,
        val allParams: Map<String, List<String>>
    ) {
        /** 原始链接是否非空。 */
        val isValid: Boolean
            get() = raw.isNotBlank()

        /** 获取指定参数的第一个值，找不到时返回 [defaultValue]。 */
        fun getParam(key: String, defaultValue: String = ""): String {
            return params[key] ?: defaultValue
        }

        /** 获取指定参数的全部值，适合处理重复 query key。 */
        fun getParamList(key: String): List<String> {
            return allParams[key].orEmpty()
        }
    }

    /** 从 [Intent.data] 解析 DeepLink。 */
    fun parse(intent: Intent?): DeepLinkData {
        return parse(intent?.data)
    }

    /** 从原始链接字符串解析 DeepLink。 */
    fun parse(url: String?): DeepLinkData {
        val raw = url.orEmpty()
        if (raw.isBlank()) return emptyData(raw)
        return parse(runCatching { Uri.parse(raw) }.getOrNull(), raw)
    }

    /** 从 [Uri] 解析 DeepLink。 */
    fun parse(uri: Uri?): DeepLinkData {
        return parse(uri, uri?.toString().orEmpty())
    }

    /** 保存 [Intent.data] 中的最近一次 DeepLink，并返回解析结果。 */
    fun save(intent: Intent?): DeepLinkData {
        return save(intent?.data)
    }

    /** 保存原始链接字符串为最近一次 DeepLink，并返回解析结果。 */
    fun save(url: String?): DeepLinkData {
        val data = parse(url)
        saveLast(data.raw)
        return data
    }

    /** 保存 [Uri] 为最近一次 DeepLink，并返回解析结果。 */
    fun save(uri: Uri?): DeepLinkData {
        val data = parse(uri)
        saveLast(data.raw)
        return data
    }

    /** 获取最近一次保存的原始 DeepLink 链接。 */
    fun getLastUrl(defaultValue: String = ""): String {
        return SpUtils.getString(KEY_LAST_URL, defaultValue, id = STORAGE_ID)
    }

    /** 获取最近一次保存的 DeepLink Uri；为空或解析失败时返回 null。 */
    fun getLastUri(): Uri? {
        val url = getLastUrl()
        if (url.isBlank()) return null
        return runCatching { Uri.parse(url) }.getOrNull()
    }

    /** 获取最近一次保存的 DeepLink 解析结果。 */
    fun getLastData(): DeepLinkData {
        return parse(getLastUrl())
    }

    /** 获取最近一次保存 DeepLink 的时间戳。 */
    fun getLastSaveTime(defaultValue: Long = 0L): Long {
        return SpUtils.getLong(KEY_LAST_TIME, defaultValue, id = STORAGE_ID)
    }

    /** 清除最近一次保存的 DeepLink 和时间戳。 */
    fun clearLast() {
        SpUtils.remove(KEY_LAST_URL, id = STORAGE_ID)
        SpUtils.remove(KEY_LAST_TIME, id = STORAGE_ID)
    }

    /** 保存 DeepLink 处理过程中的自定义字符串数据。 */
    fun saveData(key: String, value: String?) {
        putData(key, value)
    }

    /** 批量保存 DeepLink 处理过程中的自定义字符串数据。 */
    fun saveData(data: Map<String, String?>) {
        putData(data)
    }

    /** 保存 DeepLink 处理过程中的自定义字符串数据。 */
    fun putData(key: String, value: String?) {
        if (key.isBlank()) return
        SpUtils.put(DATA_PREFIX + key, value, id = STORAGE_ID)
    }

    /** 批量保存 DeepLink 处理过程中的自定义字符串数据。 */
    fun putData(data: Map<String, String?>) {
        data.forEach { (key, value) -> putData(key, value) }
    }

    /** 获取通过 [putData] 或 [saveData] 保存的自定义数据。 */
    fun getData(key: String, defaultValue: String = ""): String {
        if (key.isBlank()) return defaultValue
        return SpUtils.getString(DATA_PREFIX + key, defaultValue, id = STORAGE_ID)
    }

    /** 获取全部自定义 DeepLink 数据，不包含最近一次链接。 */
    fun getAllData(): Map<String, String> {
        return SpUtils.allKeys(id = STORAGE_ID)
            .filter { it.startsWith(DATA_PREFIX) }
            .associate { key ->
                key.removePrefix(DATA_PREFIX) to SpUtils.getString(key, id = STORAGE_ID)
            }
    }

    /** 删除一条自定义 DeepLink 数据。 */
    fun removeData(key: String) {
        if (key.isBlank()) return
        SpUtils.remove(DATA_PREFIX + key, id = STORAGE_ID)
    }

    /** 判断指定自定义 DeepLink 数据是否存在。 */
    fun containsData(key: String): Boolean {
        if (key.isBlank()) return false
        return SpUtils.contains(DATA_PREFIX + key, id = STORAGE_ID)
    }

    /** 清除全部自定义 DeepLink 数据，不会清除最近一次链接。 */
    fun clearData() {
        val keys = SpUtils.allKeys(id = STORAGE_ID)
            .filter { it.startsWith(DATA_PREFIX) }
            .toTypedArray()
        if (keys.isNotEmpty()) {
            SpUtils.removeKeys(keys, id = STORAGE_ID)
        }
    }

    /** 从 [Intent.data] 获取指定 query 参数的第一个值。 */
    fun getParam(intent: Intent?, key: String, defaultValue: String = ""): String {
        return parse(intent).getParam(key, defaultValue)
    }

    /** 从原始链接字符串获取指定 query 参数的第一个值。 */
    fun getParam(url: String?, key: String, defaultValue: String = ""): String {
        return parse(url).getParam(key, defaultValue)
    }

    /** 从 [Uri] 获取指定 query 参数的第一个值。 */
    fun getParam(uri: Uri?, key: String, defaultValue: String = ""): String {
        return parse(uri).getParam(key, defaultValue)
    }

    /** 从原始链接字符串获取指定 query 参数的全部值。 */
    fun getParamList(url: String?, key: String): List<String> {
        return parse(url).getParamList(key)
    }

    /** 从 [Intent.data] 获取全部 query 参数。 */
    fun getParams(intent: Intent?): Map<String, String> {
        return parse(intent).params
    }

    /** 从原始链接字符串获取全部 query 参数。 */
    fun getParams(url: String?): Map<String, String> {
        return parse(url).params
    }

    /** 从 [Uri] 获取全部 query 参数。 */
    fun getParams(uri: Uri?): Map<String, String> {
        return parse(uri).params
    }

    /** 获取 Int 类型 query 参数，缺失或格式错误时返回 [defaultValue]。 */
    fun getIntParam(url: String?, key: String, defaultValue: Int = 0): Int {
        return getParam(url, key).toIntOrNull() ?: defaultValue
    }

    /** 获取 Long 类型 query 参数，缺失或格式错误时返回 [defaultValue]。 */
    fun getLongParam(url: String?, key: String, defaultValue: Long = 0L): Long {
        return getParam(url, key).toLongOrNull() ?: defaultValue
    }

    /** 获取 Boolean 类型 query 参数，支持 true/false、1/0、yes/no、y/n。 */
    fun getBooleanParam(url: String?, key: String, defaultValue: Boolean = false): Boolean {
        return when (getParam(url, key).trim().lowercase()) {
            "true", "1", "yes", "y" -> true
            "false", "0", "no", "n" -> false
            else -> defaultValue
        }
    }

    /** 获取链接的 scheme，例如 https 或 app。 */
    fun getScheme(url: String?): String? {
        return parse(url).scheme
    }

    /** 获取链接的 host。 */
    fun getHost(url: String?): String? {
        return parse(url).host
    }

    /** 获取链接的 path。 */
    fun getPath(url: String?): String? {
        return parse(url).path
    }

    /** 获取链接的 path segments。 */
    fun getPathSegments(url: String?): List<String> {
        return parse(url).pathSegments
    }

    private fun saveLast(url: String) {
        if (url.isBlank()) return
        SpUtils.put(KEY_LAST_URL, url, id = STORAGE_ID)
        SpUtils.put(KEY_LAST_TIME, System.currentTimeMillis(), id = STORAGE_ID)
    }

    private fun parse(uri: Uri?, raw: String): DeepLinkData {
        if (uri == null) return emptyData(raw)

        val allParams = queryParameters(uri)
        return DeepLinkData(
            raw = raw,
            scheme = runCatching { uri.scheme }.getOrNull(),
            host = runCatching { uri.host }.getOrNull(),
            path = runCatching { uri.path }.getOrNull(),
            lastPathSegment = runCatching { uri.lastPathSegment }.getOrNull(),
            pathSegments = runCatching { uri.pathSegments }.getOrDefault(emptyList()),
            params = allParams.mapValues { it.value.firstOrNull().orEmpty() },
            allParams = allParams
        )
    }

    private fun queryParameters(uri: Uri): Map<String, List<String>> {
        return runCatching {
            uri.queryParameterNames.associateWith { name ->
                uri.getQueryParameters(name)
            }
        }.getOrDefault(emptyMap())
    }

    private fun emptyData(raw: String = ""): DeepLinkData {
        return DeepLinkData(
            raw = raw,
            scheme = null,
            host = null,
            path = null,
            lastPathSegment = null,
            pathSegments = emptyList(),
            params = emptyMap(),
            allParams = emptyMap()
        )
    }
}
