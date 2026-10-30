package com.wkq.util

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 基于 Android Keystore 的 AES-GCM 加解密工具。
 *
 * 密钥由系统 Keystore 保管，不会以明文出现在应用存储中。密文仅可由相同应用签名、
 * 相同用户空间内的应用读取；卸载应用、清除系统凭据或密钥失效后，旧密文可能无法恢复。
 */
object KeystoreUtil {

    private const val ANDROID_KEY_STORE = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val FORMAT_VERSION = "v1"
    private const val GCM_TAG_LENGTH_BITS = 128

    /** 使用指定别名加密 UTF-8 文本，返回可直接保存的密文字符串。 */
    fun encrypt(alias: String, plainText: String): String {
        require(alias.isNotBlank()) { "alias must not be blank." }

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey(alias))
        val encrypted = cipher.doFinal(plainText.toByteArray(StandardCharsets.UTF_8))
        return listOf(FORMAT_VERSION, encode(cipher.iv), encode(encrypted)).joinToString(".")
    }

    /**
     * 使用指定别名解密文本。密文格式错误、密钥不存在或密钥已失效时返回 `null`。
     */
    fun decrypt(alias: String, encryptedText: String): String? {
        if (alias.isBlank() || encryptedText.isBlank()) return null
        val parts = encryptedText.split('.')
        if (parts.size != 3 || parts[0] != FORMAT_VERSION) return null

        return runCatching {
            val key = getKey(alias) ?: return null
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                key,
                GCMParameterSpec(GCM_TAG_LENGTH_BITS, decode(parts[1]))
            )
            String(cipher.doFinal(decode(parts[2])), StandardCharsets.UTF_8)
        }.getOrNull()
    }

    /** 判断指定别名的 Keystore 密钥是否存在。 */
    fun containsKey(alias: String): Boolean {
        if (alias.isBlank()) return false
        return runCatching { keyStore().containsAlias(alias) }.getOrDefault(false)
    }

    /** 删除指定 Keystore 密钥。删除后，使用该密钥加密的历史数据将无法解密。 */
    fun deleteKey(alias: String): Boolean {
        if (alias.isBlank()) return false
        return runCatching {
            val keyStore = keyStore()
            if (!keyStore.containsAlias(alias)) return true
            keyStore.deleteEntry(alias)
            true
        }.getOrDefault(false)
    }

    @Synchronized
    private fun getOrCreateKey(alias: String): SecretKey {
        getKey(alias)?.let { return it }

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
        val spec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    private fun getKey(alias: String): SecretKey? {
        return runCatching { keyStore().getKey(alias, null) as? SecretKey }.getOrNull()
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }

    private fun encode(value: ByteArray): String = Base64.encodeToString(value, Base64.NO_WRAP)

    private fun decode(value: String): ByteArray = Base64.decode(value, Base64.NO_WRAP)
}
