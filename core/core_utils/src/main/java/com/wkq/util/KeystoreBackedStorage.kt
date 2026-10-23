package com.wkq.util

/**
 * 使用 Android Keystore 加密后再写入 [SpUtils] 的字符串存储工具。
 *
 * 使用前需先初始化 [SpUtils] 或 [CoreUtils]。本类只负责字符串敏感数据；密钥由
 * [KeystoreUtil] 管理，调用方无需也不应自行保存加密密钥。
 */
object KeystoreBackedStorage {

    private const val DEFAULT_STORAGE_ID = "android_core_utils_secure_storage"
    private const val KEY_ALIAS_PREFIX = "android_core_utils.secure."

    /** 加密并保存字符串；传入 `null` 会删除该键。 */
    fun putString(key: String, value: String?, storageId: String = DEFAULT_STORAGE_ID) {
        require(key.isNotBlank()) { "key must not be blank." }
        val id = requireStorageId(storageId)
        if (value == null) {
            SpUtils.remove(key, id = id)
            return
        }
        SpUtils.put(key, KeystoreUtil.encrypt(keyAlias(id), value), id = id)
    }

    /** 获取并解密字符串；数据不存在、损坏或密钥失效时返回 [defaultValue]。 */
    fun getString(key: String, defaultValue: String = "", storageId: String = DEFAULT_STORAGE_ID): String {
        require(key.isNotBlank()) { "key must not be blank." }
        val id = requireStorageId(storageId)
        val encrypted = SpUtils.getString(key, id = id)
        if (encrypted.isBlank()) return defaultValue
        return KeystoreUtil.decrypt(keyAlias(id), encrypted) ?: defaultValue
    }

    /** 删除一条加密存储数据，不会删除对应 Keystore 密钥。 */
    fun remove(key: String, storageId: String = DEFAULT_STORAGE_ID) {
        require(key.isNotBlank()) { "key must not be blank." }
        SpUtils.remove(key, id = requireStorageId(storageId))
    }

    /** 判断加密存储中是否存在指定键。 */
    fun contains(key: String, storageId: String = DEFAULT_STORAGE_ID): Boolean {
        if (key.isBlank()) return false
        return SpUtils.contains(key, id = requireStorageId(storageId))
    }

    private fun requireStorageId(storageId: String): String {
        require(storageId.isNotBlank()) { "storageId must not be blank." }
        return storageId
    }

    private fun keyAlias(storageId: String): String {
        return KEY_ALIAS_PREFIX + DigestUtil.sha256(storageId).take(32)
    }
}
