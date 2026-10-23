package com.wkq.iptc.upload

import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializer
import com.google.gson.JsonSerializer
import com.google.gson.reflect.TypeToken
import android.util.Log

object UploadConfigStore {

    private const val TAG = "UploadConfigStore"
    private const val KEY_PROFILE_JSON = "press_iptc_upload_profile_json"
    private const val KEY_PROFILES_JSON = "press_iptc_upload_profiles_json"
    private const val KEY_SELECTED_PROFILE_ID = "press_iptc_upload_selected_profile_id"

    private val gson = GsonBuilder()
        .registerTypeAdapter(
            UploadServerConfig::class.java,
            JsonSerializer<UploadServerConfig> { src, _, context ->
                val json = context.serialize(src).asJsonObject
                val type = when (src) {
                    is FtpConfig -> UploadProtocolType.FTP.name
                    is FtpsConfig -> UploadProtocolType.FTPS.name
                    is SftpConfig -> UploadProtocolType.SFTP.name
                    is HttpConfig -> UploadProtocolType.HTTP.name
                    is SmbConfig -> UploadProtocolType.SMB.name
                    is WebDavConfig -> UploadProtocolType.WEBDAV.name
                }
                json.addProperty("configType", type)
                json
            }
        )
        .registerTypeAdapter(
            UploadServerConfig::class.java,
            JsonDeserializer<UploadServerConfig> { json, _, context ->
                val jsonObject = json.asJsonObject
                when (UploadProtocolType.fromValue(jsonObject.get("configType")?.asString)) {
                    UploadProtocolType.FTP -> context.deserialize(json, FtpConfig::class.java)
                    UploadProtocolType.FTPS -> context.deserialize(json, FtpsConfig::class.java)
                    UploadProtocolType.SFTP -> context.deserialize(json, SftpConfig::class.java)
                    UploadProtocolType.HTTP -> context.deserialize(json, HttpConfig::class.java)
                    UploadProtocolType.SMB -> context.deserialize(json, SmbConfig::class.java)
                    UploadProtocolType.WEBDAV -> context.deserialize(json, WebDavConfig::class.java)
                }
            }
        )
        .create()

    fun load(): UploadServerProfile {
        val selected = getSelectedProfile()
        if (selected != null) {
            return selected
        }
        val json = UploadPreferenceStore.getString(KEY_PROFILE_JSON)
        if (json.isBlank()) {
            return UploadServerProfile()
        }
        return runCatching {
            gson.fromJson(json, UploadServerProfile::class.java) ?: UploadServerProfile()
        }.onFailure {
            Log.e(TAG, "Failed to load upload config: ${UploadLogSanitizer.throwable(it)}")
        }.getOrDefault(UploadServerProfile())
            .withStoredPassword()
    }

    fun save(profile: UploadServerProfile) {
        Log.i(TAG, "save legacy/default profile: ${UploadLogSanitizer.profile(profile)}")
        saveProfile(profile, selectAfterSave = true)
        UploadPreferenceStore.putString(KEY_PROFILE_JSON, gson.toJson(profile.withoutPassword()))
    }

    fun getProfiles(): List<UploadServerProfile> {
        val raw = UploadPreferenceStore.getString(KEY_PROFILES_JSON)
        val profiles = if (raw.isBlank()) {
            emptyList()
        } else {
            runCatching {
                gson.fromJson<List<UploadServerProfile>>(
                    raw,
                    object : TypeToken<List<UploadServerProfile>>() {}.type
                ) ?: emptyList()
            }.onFailure {
                Log.e(TAG, "Failed to load upload profiles: ${UploadLogSanitizer.throwable(it)}")
            }.getOrDefault(emptyList())
        }
        if (profiles.isNotEmpty()) {
            return profiles.map { it.withStoredPassword() }
        }
        val legacy = loadLegacyProfile()
        return if (legacy.config.host.isNotBlank() || legacy.name.isNotBlank()) {
            listOf(legacy.withStoredPassword())
        } else {
            emptyList()
        }
    }

    /** 导出可迁移的服务器配置；密码由独立安全存储管理，因此不写入备份。 */
    fun exportProfilesForBackup(): String {
        val profiles = getProfiles().map { it.withoutPassword() }
        return gson.toJson(profiles)
    }

    /** 在修改任何本地配置前验证备份中的服务器配置和选中项。 */
    fun validateProfilesBackup(json: String, selectedProfileId: String?) {
        val imported = parseProfilesBackup(json)
        val ids = imported.map(UploadServerProfile::id)
        require(ids.all(String::isNotBlank) && ids.distinct().size == ids.size) {
            "服务器配置备份包含无效或重复的 ID"
        }
        val availableIds = getProfiles().mapTo(mutableSetOf(), UploadServerProfile::id) + ids
        require(selectedProfileId.isNullOrBlank() || selectedProfileId in availableIds) {
            "服务器配置备份引用了不存在的配置"
        }
    }

    /** 导入服务器配置时合并，不删除本机其他配置；同 ID 的本机密码予以保留。 */
    fun importProfilesFromBackup(json: String, selectedProfileId: String?) {
        validateProfilesBackup(json, selectedProfileId)
        val imported = parseProfilesBackup(json)
        imported.forEach { profile ->
            val local = getProfile(profile.id)
            val restored = if (local != null && profile.config.passwordValue().isBlank()) {
                profile.copy(config = profile.config.copyPassword(local.config.passwordValue()))
            } else profile
            saveProfile(restored, selectAfterSave = false)
        }
        selectedProfileId?.takeIf { id -> getProfile(id) != null }?.let(::selectProfile)
    }

    private fun parseProfilesBackup(json: String): List<UploadServerProfile> = runCatching {
        gson.fromJson<List<UploadServerProfile>>(
            json,
            object : TypeToken<List<UploadServerProfile>>() {}.type
        ).orEmpty()
    }.getOrElse { throw IllegalArgumentException("服务器配置备份格式无效", it) }

    fun getProfile(profileId: String): UploadServerProfile? {
        return getProfiles().firstOrNull { it.id == profileId }.also {
            Log.i(TAG, "getProfile id=${UploadLogSanitizer.redactedId(profileId)}, found=${it?.let(UploadLogSanitizer::profile) ?: "null"}")
        }
    }

    fun getSelectedProfile(): UploadServerProfile? {
        val profiles = getProfilesWithoutLegacyFallback()
        if (profiles.isEmpty()) {
            return null
        }
        val selectedId = UploadPreferenceStore.getString(KEY_SELECTED_PROFILE_ID)
        return profiles.firstOrNull { it.id == selectedId } ?: profiles.firstOrNull()
    }

    /** 模板绑定的服务器优先；未绑定时回退到当前默认服务器。 */
    fun resolveTargetProfile(preferredProfileId: String?): UploadServerProfile {
        return preferredProfileId
            ?.takeIf(String::isNotBlank)
            ?.let(::getProfile)
            ?: load()
    }

    fun saveProfile(profile: UploadServerProfile, selectAfterSave: Boolean = true) {
        val profiles = getProfiles().toMutableList()
        val index = profiles.indexOfFirst { it.id == profile.id }
        if (index >= 0) {
            profiles[index] = profile
        } else {
            profiles += profile
        }
        UploadSecretStore.savePassword(profile.id, profile.config.passwordValue())
        saveProfiles(profiles)
        Log.i(
            TAG,
            "saveProfile selectAfterSave=$selectAfterSave, action=${if (index >= 0) "update" else "add"}, " +
                "profile=${UploadLogSanitizer.profile(profile)}, total=${profiles.size}"
        )
        if (selectAfterSave) {
            selectProfile(profile.id)
        }
    }

    fun selectProfile(profileId: String) {
        UploadPreferenceStore.putString(KEY_SELECTED_PROFILE_ID, profileId)
        getProfile(profileId)?.let {
            UploadPreferenceStore.putString(KEY_PROFILE_JSON, gson.toJson(it.withoutPassword()))
            Log.i(TAG, "selectProfile id=${UploadLogSanitizer.redactedId(profileId)}, selected=${UploadLogSanitizer.profile(it)}")
        } ?: run {
            Log.w(TAG, "selectProfile id=${UploadLogSanitizer.redactedId(profileId)}, but profile not found")
        }
    }

    fun deleteProfiles(profileIds: Collection<String>) {
        if (profileIds.isEmpty()) return
        val profiles = getProfiles().filterNot { it.id in profileIds }
        profileIds.forEach { UploadSecretStore.clearPassword(it) }
        saveProfiles(profiles)
        Log.i(TAG, "deleteProfiles count=${profileIds.size}, remaining=${profiles.size}")
        val selectedId = UploadPreferenceStore.getString(KEY_SELECTED_PROFILE_ID)
        if (selectedId in profileIds) {
            profiles.firstOrNull()?.let { selectProfile(it.id) }
                ?: UploadPreferenceStore.remove(KEY_SELECTED_PROFILE_ID)
        }
        if (profiles.isEmpty()) {
            UploadPreferenceStore.remove(KEY_PROFILE_JSON)
        }
    }

    fun clear() {
        UploadPreferenceStore.remove(KEY_PROFILE_JSON)
        UploadPreferenceStore.remove(KEY_PROFILES_JSON)
        UploadPreferenceStore.remove(KEY_SELECTED_PROFILE_ID)
    }

    private fun saveProfiles(profiles: List<UploadServerProfile>) {
        UploadPreferenceStore.putString(KEY_PROFILES_JSON, gson.toJson(profiles.map { it.withoutPassword() }))
        Log.i(TAG, "saveProfiles total=${profiles.size}, items=${profiles.joinToString { UploadLogSanitizer.profile(it) }}")
    }

    private fun getProfilesWithoutLegacyFallback(): List<UploadServerProfile> {
        val raw = UploadPreferenceStore.getString(KEY_PROFILES_JSON)
        if (raw.isBlank()) return emptyList()
        return runCatching {
            gson.fromJson<List<UploadServerProfile>>(
                raw,
                object : TypeToken<List<UploadServerProfile>>() {}.type
            ) ?: emptyList()
        }.getOrDefault(emptyList()).map { it.withStoredPassword() }
    }

    private fun loadLegacyProfile(): UploadServerProfile {
        val json = UploadPreferenceStore.getString(KEY_PROFILE_JSON)
        if (json.isBlank()) {
            return UploadServerProfile()
        }
        return runCatching {
            gson.fromJson(json, UploadServerProfile::class.java) ?: UploadServerProfile()
        }.getOrDefault(UploadServerProfile()).withStoredPassword()
    }

    private fun UploadServerProfile.withoutPassword(): UploadServerProfile {
        return copy(config = config.copyPassword(""))
    }

    private fun UploadServerProfile.withStoredPassword(): UploadServerProfile {
        val stored = UploadSecretStore.loadPassword(id)
        if (stored.isBlank()) {
            val legacyPassword = config.passwordValue()
            if (legacyPassword.isNotBlank()) {
                UploadSecretStore.savePassword(id, legacyPassword)
                return withoutPassword().copy(config = config.copyPassword(legacyPassword))
            }
            return this
        }
        return copy(config = config.copyPassword(stored))
    }

    private fun UploadServerConfig.passwordValue(): String {
        return when (this) {
            is FtpConfig -> password
            is FtpsConfig -> password
            is SftpConfig -> password
            is HttpConfig -> password
            is SmbConfig -> password
            is WebDavConfig -> password
        }
    }

    private fun UploadServerConfig.copyPassword(password: String): UploadServerConfig {
        return when (this) {
            is FtpConfig -> copy(password = password)
            is FtpsConfig -> copy(password = password)
            is SftpConfig -> copy(password = password)
            is HttpConfig -> copy(password = password)
            is SmbConfig -> copy(password = password)
            is WebDavConfig -> copy(password = password)
        }
    }
}

