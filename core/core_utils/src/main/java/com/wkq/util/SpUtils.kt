package com.wkq.util

import android.content.Context
import android.os.Parcelable
import com.tencent.mmkv.MMKV

/**
 * 基于 MMKV 的本地键值存储工具。
 *
 * 使用前必须在 Application 中调用 [init]。未指定 [id] 时使用默认 MMKV；指定
 * [id] 与 [cryptKey] 可访问命名或加密实例。同一 [id] 必须始终使用相同的密钥，
 * 否则无法正确读取已保存的数据。
 */
object SpUtils {

    private var mmkv: MMKV? = null
    private val instances = mutableMapOf<String, MMKV>()

    /** 初始化 MMKV，并创建默认存储实例。建议仅在 Application 中调用一次。 */
    @Synchronized
    fun init(context: Context) {
        MMKV.initialize(context)
        mmkv = MMKV.defaultMMKV()
        instances.clear()
    }

    /** 是否已经完成初始化。 */
    fun isInitialized(): Boolean = mmkv != null

    /** 获取默认 MMKV 实例；未调用 [init] 时会抛出异常。 */
    fun getMMKV(): MMKV {
        return mmkv ?: throw IllegalStateException(
            "SpUtils has not been initialized. Please call SpUtils.init(context) in your Application."
        )
    }

    /**
     * 获取命名 MMKV 实例。
     *
     * 空 [id] 会返回默认实例；非空 [cryptKey] 会创建加密实例。
     */
    @Synchronized
    fun getMMKV(id: String, cryptKey: String? = null): MMKV {
        if (id.isBlank()) return getMMKV()

        val cacheKey = buildInstanceKey(id, cryptKey)
        return instances.getOrPut(cacheKey) {
            if (cryptKey.isNullOrEmpty()) {
                MMKV.mmkvWithID(id)
            } else {
                MMKV.mmkvWithID(id, MMKV.SINGLE_PROCESS_MODE, cryptKey)
            }
        }
    }

    /** 写入基础类型、[Parcelable] 或 `Set<String>`；传入 `null` 会删除该键。 */
    fun put(key: String, value: Any?, id: String? = null, cryptKey: String? = null) {
        putTo(getTargetMMKV(id, cryptKey), key, value)
    }

    fun getString(
        key: String,
        defaultValue: String = "",
        id: String? = null,
        cryptKey: String? = null
    ): String {
        return getTargetMMKV(id, cryptKey).decodeString(key, defaultValue) ?: defaultValue
    }

    fun getInt(key: String, defaultValue: Int = 0, id: String? = null, cryptKey: String? = null): Int {
        return getTargetMMKV(id, cryptKey).decodeInt(key, defaultValue)
    }

    fun getBoolean(
        key: String,
        defaultValue: Boolean = false,
        id: String? = null,
        cryptKey: String? = null
    ): Boolean {
        return getTargetMMKV(id, cryptKey).decodeBool(key, defaultValue)
    }

    fun getFloat(key: String, defaultValue: Float = 0f, id: String? = null, cryptKey: String? = null): Float {
        return getTargetMMKV(id, cryptKey).decodeFloat(key, defaultValue)
    }

    fun getLong(key: String, defaultValue: Long = 0L, id: String? = null, cryptKey: String? = null): Long {
        return getTargetMMKV(id, cryptKey).decodeLong(key, defaultValue)
    }

    fun getDouble(
        key: String,
        defaultValue: Double = 0.0,
        id: String? = null,
        cryptKey: String? = null
    ): Double {
        return getTargetMMKV(id, cryptKey).decodeDouble(key, defaultValue)
    }

    fun getByteArray(
        key: String,
        defaultValue: ByteArray = ByteArray(0),
        id: String? = null,
        cryptKey: String? = null
    ): ByteArray {
        return getTargetMMKV(id, cryptKey).decodeBytes(key, defaultValue) ?: defaultValue
    }

    fun getStringSet(
        key: String,
        defaultValue: Set<String> = emptySet(),
        id: String? = null,
        cryptKey: String? = null
    ): Set<String> {
        return getTargetMMKV(id, cryptKey).decodeStringSet(key, defaultValue) ?: defaultValue
    }

    inline fun <reified T : Parcelable> getParcelable(
        key: String,
        defaultValue: T? = null,
        id: String? = null,
        cryptKey: String? = null
    ): T? {
        return getTargetMMKV(id, cryptKey).decodeParcelable(key, T::class.java, defaultValue)
    }

    fun remove(key: String, id: String? = null, cryptKey: String? = null) {
        getTargetMMKV(id, cryptKey).removeValueForKey(key)
    }

    /** 批量删除 key。 */
    fun removeKeys(keys: Array<String>, id: String? = null, cryptKey: String? = null) {
        if (keys.isEmpty()) return
        getTargetMMKV(id, cryptKey).removeValuesForKeys(keys)
    }

    fun clearAll(id: String? = null, cryptKey: String? = null) {
        getTargetMMKV(id, cryptKey).clearAll()
    }

    fun contains(key: String, id: String? = null, cryptKey: String? = null): Boolean {
        return getTargetMMKV(id, cryptKey).containsKey(key)
    }

    /** 获取当前 MMKV 实例中的所有 key。 */
    fun allKeys(id: String? = null, cryptKey: String? = null): Array<String> {
        return getTargetMMKV(id, cryptKey).allKeys() ?: emptyArray()
    }

    /** 当前 MMKV 实例是否没有任何 key。 */
    fun isEmpty(id: String? = null, cryptKey: String? = null): Boolean {
        return allKeys(id, cryptKey).isEmpty()
    }

    @PublishedApi
    internal fun getTargetMMKV(id: String?, cryptKey: String?): MMKV {
        return if (id.isNullOrBlank()) getMMKV() else getMMKV(id, cryptKey)
    }

    private fun putTo(kv: MMKV, key: String, value: Any?) {
        if (value == null) {
            kv.removeValueForKey(key)
            return
        }

        when (value) {
            is String -> kv.encode(key, value)
            is Int -> kv.encode(key, value)
            is Boolean -> kv.encode(key, value)
            is Float -> kv.encode(key, value)
            is Long -> kv.encode(key, value)
            is Double -> kv.encode(key, value)
            is ByteArray -> kv.encode(key, value)
            is Parcelable -> kv.encode(key, value)
            is Set<*> -> {
                require(value.all { it is String }) {
                    "Only Set<String> is supported for MMKV."
                }
                @Suppress("UNCHECKED_CAST")
                kv.encode(key, value as Set<String>)
            }
            else -> throw IllegalArgumentException("Unsupported type for MMKV: ${value.javaClass.simpleName}")
        }
    }

    private fun buildInstanceKey(id: String, cryptKey: String?): String {
        return "$id:${cryptKey.orEmpty()}"
    }
}
