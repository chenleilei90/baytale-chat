package com.baytale.baychat.core.storage

import android.content.Context
import com.baytale.baychat.BuildConfig
import com.baytale.baychat.core.model.AppThemeMode
import com.baytale.baychat.core.model.PartialFileMeta
import com.baytale.baychat.core.model.RecentRoom
import com.baytale.baychat.core.model.SavedFixedRoom
import com.baytale.baychat.core.model.TransferRecord
import com.baytale.baychat.core.model.UserProfile
import com.baytale.baychat.core.util.FormatUtils
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

data class AppUserPrefs(
    val autoAcceptSmallFiles: Boolean = true,
    val autoAcceptMaxMB: Int = 50,
    val imageOriginalMode: Boolean = true,
    val serverUrl: String = BuildConfig.DEFAULT_SERVER_URL,
    // 企业级 OTA 自动更新偏好设置
    val autoDownloadOnWifi: Boolean = true,
    val ignoredVersionCode: Int = 0,
    val lastUpdateCheckAt: Long = 0L,
    // 原生交互音效与触感反馈开关
    val soundEnabled: Boolean = true,
    val hapticEnabled: Boolean = true,
)

// 本地偏好、传输记录与身份持久化仓库（全部存于应用私有 SharedPreferences，卸载自动清理）
class AppPrefsStore(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("baychat_prefs_v1", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    fun hasAcceptedPrivacy(): Boolean = sp.getBoolean(KEY_PRIVACY_ACCEPTED, false)

    fun setPrivacyAccepted(accepted: Boolean) {
        sp.edit().putBoolean(KEY_PRIVACY_ACCEPTED, accepted).apply()
    }

    fun loadProfile(): UserProfile {
        val raw = sp.getString(KEY_PROFILE, null)
        if (!raw.isNullOrBlank()) {
            runCatching { return json.decodeFromString(UserProfile.serializer(), raw) }
        }
        val created = UserProfile(
            clientId = FormatUtils.generateClientId(),
            name = FormatUtils.generateRandomName(),
            avatar = FormatUtils.PRESET_AVATARS.random().first,
        )
        saveProfile(created)
        return created
    }

    fun saveProfile(profile: UserProfile) {
        val raw = json.encodeToString(UserProfile.serializer(), profile)
        sp.edit().putString(KEY_PROFILE, raw).apply()
    }

    // v1.1.0 切换默认主题为方案 B「清风极简 (AIR)」，独立存储键确保老用户升级后立即体验新视觉
    fun loadTheme(): AppThemeMode = AppThemeMode.fromId(sp.getString(KEY_THEME_V2, null))

    fun saveTheme(mode: AppThemeMode) {
        sp.edit().putString(KEY_THEME_V2, mode.id).apply()
    }

    fun loadPrefs(): AppUserPrefs = AppUserPrefs(
        autoAcceptSmallFiles = sp.getBoolean(KEY_AUTO_ACCEPT, true),
        autoAcceptMaxMB = sp.getInt(KEY_AUTO_ACCEPT_MB, 50).coerceIn(5, 500),
        imageOriginalMode = sp.getBoolean(KEY_IMAGE_ORIGINAL, true),
        serverUrl = sp.getString(KEY_SERVER_URL, BuildConfig.DEFAULT_SERVER_URL)
            ?.takeIf { it.isNotBlank() } ?: BuildConfig.DEFAULT_SERVER_URL,
        autoDownloadOnWifi = sp.getBoolean(KEY_AUTO_DOWNLOAD_WIFI, true),
        ignoredVersionCode = sp.getInt(KEY_IGNORED_VERSION_CODE, 0),
        lastUpdateCheckAt = sp.getLong(KEY_LAST_UPDATE_CHECK_AT, 0L),
        soundEnabled = sp.getBoolean(KEY_SOUND_ENABLED, true),
        hapticEnabled = sp.getBoolean(KEY_HAPTIC_ENABLED, true),
    )

    fun savePrefs(prefs: AppUserPrefs) {
        sp.edit()
            .putBoolean(KEY_AUTO_ACCEPT, prefs.autoAcceptSmallFiles)
            .putInt(KEY_AUTO_ACCEPT_MB, prefs.autoAcceptMaxMB)
            .putBoolean(KEY_IMAGE_ORIGINAL, prefs.imageOriginalMode)
            .putString(KEY_SERVER_URL, prefs.serverUrl.trim().trimEnd('/'))
            .putBoolean(KEY_AUTO_DOWNLOAD_WIFI, prefs.autoDownloadOnWifi)
            .putInt(KEY_IGNORED_VERSION_CODE, prefs.ignoredVersionCode)
            .putLong(KEY_LAST_UPDATE_CHECK_AT, prefs.lastUpdateCheckAt)
            .putBoolean(KEY_SOUND_ENABLED, prefs.soundEnabled)
            .putBoolean(KEY_HAPTIC_ENABLED, prefs.hapticEnabled)
            .apply()
    }

    // OTA 与交互反馈独立快捷持久化方法，避免局部更新误覆盖其他字段
    fun setAutoDownloadOnWifi(enabled: Boolean) {
        sp.edit().putBoolean(KEY_AUTO_DOWNLOAD_WIFI, enabled).apply()
    }

    fun setIgnoredVersionCode(versionCode: Int) {
        sp.edit().putInt(KEY_IGNORED_VERSION_CODE, versionCode).apply()
    }

    fun setLastUpdateCheckAt(timestampMs: Long) {
        sp.edit().putLong(KEY_LAST_UPDATE_CHECK_AT, timestampMs).apply()
    }

    fun setSoundEnabled(enabled: Boolean) {
        sp.edit().putBoolean(KEY_SOUND_ENABLED, enabled).apply()
    }

    fun setHapticEnabled(enabled: Boolean) {
        sp.edit().putBoolean(KEY_HAPTIC_ENABLED, enabled).apply()
    }

    fun loadSavedRooms(): List<SavedFixedRoom> {
        val raw = sp.getString(KEY_SAVED_ROOMS, null) ?: return emptyList()
        return runCatching {
            json.decodeFromString(ListSerializer(SavedFixedRoom.serializer()), raw)
        }.getOrDefault(emptyList())
    }

    fun upsertSavedRoom(room: SavedFixedRoom): List<SavedFixedRoom> {
        val list = loadSavedRooms().filterNot { it.code.equals(room.code, ignoreCase = true) }.toMutableList()
        list.add(0, room.copy(lastVisitedAt = System.currentTimeMillis()))
        val trimmed = list.take(20)
        sp.edit().putString(KEY_SAVED_ROOMS, json.encodeToString(ListSerializer(SavedFixedRoom.serializer()), trimmed)).apply()
        return trimmed
    }

    fun removeSavedRoom(code: String): List<SavedFixedRoom> {
        val next = loadSavedRooms().filterNot { it.code.equals(code, ignoreCase = true) }
        sp.edit().putString(KEY_SAVED_ROOMS, json.encodeToString(ListSerializer(SavedFixedRoom.serializer()), next)).apply()
        return next
    }

    fun loadRecentRooms(): List<RecentRoom> {
        val raw = sp.getString(KEY_RECENT_ROOMS, null) ?: return emptyList()
        return runCatching {
            json.decodeFromString(ListSerializer(RecentRoom.serializer()), raw)
                .sortedByDescending { it.lastVisitedAt }
        }.getOrDefault(emptyList())
    }

    fun upsertRecentRoom(room: RecentRoom): List<RecentRoom> {
        val list = loadRecentRooms().filterNot { it.code.equals(room.code, ignoreCase = true) }.toMutableList()
        list.add(0, room.copy(lastVisitedAt = System.currentTimeMillis()))
        val trimmed = list.take(10)
        sp.edit().putString(KEY_RECENT_ROOMS, json.encodeToString(ListSerializer(RecentRoom.serializer()), trimmed)).apply()
        return trimmed
    }

    fun removeRecentRoom(code: String): List<RecentRoom> {
        val next = loadRecentRooms().filterNot { it.code.equals(code, ignoreCase = true) }
        sp.edit().putString(KEY_RECENT_ROOMS, json.encodeToString(ListSerializer(RecentRoom.serializer()), next)).apply()
        return next
    }

    fun clearRecentRooms(): List<RecentRoom> {
        sp.edit().remove(KEY_RECENT_ROOMS).apply()
        return emptyList()
    }

    // 传输中心：持久化已完成文件记录
    fun loadTransferRecords(): List<TransferRecord> {
        val raw = sp.getString(KEY_TRANSFER_RECORDS, null) ?: return emptyList()
        return runCatching {
            json.decodeFromString(ListSerializer(TransferRecord.serializer()), raw)
                .sortedByDescending { it.timestamp }
        }.getOrDefault(emptyList())
    }

    fun addTransferRecord(record: TransferRecord): List<TransferRecord> {
        val list = loadTransferRecords().filterNot { it.id == record.id }.toMutableList()
        list.add(0, record)
        val trimmed = list.take(60)
        sp.edit().putString(KEY_TRANSFER_RECORDS, json.encodeToString(ListSerializer(TransferRecord.serializer()), trimmed)).apply()
        return trimmed
    }

    fun removeTransferRecord(id: String): List<TransferRecord> {
        val next = loadTransferRecords().filterNot { it.id == id }
        sp.edit().putString(KEY_TRANSFER_RECORDS, json.encodeToString(ListSerializer(TransferRecord.serializer()), next)).apply()
        return next
    }

    fun clearTransferRecords(): List<TransferRecord> {
        sp.edit().remove(KEY_TRANSFER_RECORDS).apply()
        return emptyList()
    }

    // 记录断点续传 .part 文件的原始文件名与房间信息，便于在「传输中心」清晰展示
    fun loadPartialMetaMap(): Map<String, PartialFileMeta> {
        val raw = sp.getString(KEY_PARTIAL_META, null) ?: return emptyMap()
        return runCatching {
            json.decodeFromString(MapSerializer(String.serializer(), PartialFileMeta.serializer()), raw)
        }.getOrDefault(emptyMap())
    }

    fun upsertPartialMeta(meta: PartialFileMeta) {
        val map = loadPartialMetaMap().toMutableMap()
        map[meta.partFileName] = meta
        sp.edit().putString(KEY_PARTIAL_META, json.encodeToString(MapSerializer(String.serializer(), PartialFileMeta.serializer()), map)).apply()
    }

    fun removePartialMeta(partFileName: String) {
        val map = loadPartialMetaMap().toMutableMap()
        if (map.remove(partFileName) != null) {
            sp.edit().putString(KEY_PARTIAL_META, json.encodeToString(MapSerializer(String.serializer(), PartialFileMeta.serializer()), map)).apply()
        }
    }

    companion object {
        private const val KEY_PRIVACY_ACCEPTED = "privacy_accepted_v1"
        private const val KEY_PROFILE = "user_profile"
        private const val KEY_THEME_V2 = "theme_mode_v2"
        private const val KEY_AUTO_ACCEPT = "auto_accept_small"
        private const val KEY_AUTO_ACCEPT_MB = "auto_accept_max_mb"
        private const val KEY_IMAGE_ORIGINAL = "image_original"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_SAVED_ROOMS = "saved_fixed_rooms"
        private const val KEY_RECENT_ROOMS = "recent_rooms_v1"
        private const val KEY_TRANSFER_RECORDS = "transfer_records_v1"
        private const val KEY_PARTIAL_META = "partial_meta_v1"
        private const val KEY_AUTO_DOWNLOAD_WIFI = "ota_auto_download_wifi_v1"
        private const val KEY_IGNORED_VERSION_CODE = "ota_ignored_version_code_v1"
        private const val KEY_LAST_UPDATE_CHECK_AT = "ota_last_update_check_at_v1"
        private const val KEY_SOUND_ENABLED = "ui_sound_enabled_v1"
        private const val KEY_HAPTIC_ENABLED = "ui_haptic_enabled_v1"
    }
}
