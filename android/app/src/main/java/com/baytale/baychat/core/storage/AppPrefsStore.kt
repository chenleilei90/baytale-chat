package com.baytale.baychat.core.storage

import android.content.Context
import com.baytale.baychat.BuildConfig
import com.baytale.baychat.core.model.AppThemeMode
import com.baytale.baychat.core.model.AppearanceMode
import com.baytale.baychat.core.model.BlockedPeer
import com.baytale.baychat.core.model.FavoriteItem
import com.baytale.baychat.core.model.LastActiveRoom
import com.baytale.baychat.core.model.PartialFileMeta
import com.baytale.baychat.core.model.RecentRoom
import com.baytale.baychat.core.model.SavedFixedRoom
import com.baytale.baychat.core.model.SavedPickupRecord
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
    // v1.2.0 外观与隐私/协同偏好设置
    val appearance: AppearanceMode = AppearanceMode.SYSTEM,
    val nearbyDiscoverable: Boolean = true,
    val cloudBackupOnWifi: Boolean = true,
    val acceptClipShare: Boolean = true,
    val onboardingDone: Boolean = false,
    val notificationAsked: Boolean = false,
    val minimizeHintShown: Boolean = false,
    val cameraPermAsked: Boolean = false,
    val audioPermAsked: Boolean = false,
)

// 本地偏好、传输记录与身份持久化仓库（全部存于应用私有 SharedPreferences，已禁用系统云备份）
class AppPrefsStore(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("baychat_prefs_v1", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    init {
        migrateLegacyThemeIfNeeded()
    }

    // 若旧版存有 "neon" 暗黑主题，自动迁移为清风极简 (AIR) + 强制深色外观并立即持久化
    private fun migrateLegacyThemeIfNeeded() {
        val rawTheme = sp.getString(KEY_THEME_V2, null)
        if (rawTheme.equals(AppThemeMode.NEON.id, ignoreCase = true)) {
            sp.edit()
                .putString(KEY_THEME_V2, AppThemeMode.AIR.id)
                .putString(KEY_APPEARANCE, AppearanceMode.DARK.id)
                .apply()
        }
    }

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

    fun loadTheme(): AppThemeMode {
        migrateLegacyThemeIfNeeded()
        return AppThemeMode.fromId(sp.getString(KEY_THEME_V2, null))
    }

    fun saveTheme(mode: AppThemeMode) {
        if (mode == AppThemeMode.NEON) {
            sp.edit()
                .putString(KEY_THEME_V2, AppThemeMode.AIR.id)
                .putString(KEY_APPEARANCE, AppearanceMode.DARK.id)
                .apply()
            return
        }
        sp.edit().putString(KEY_THEME_V2, mode.id).apply()
    }

    fun loadPrefs(): AppUserPrefs {
        migrateLegacyThemeIfNeeded()
        return AppUserPrefs(
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
            appearance = AppearanceMode.fromId(sp.getString(KEY_APPEARANCE, null)),
            nearbyDiscoverable = sp.getBoolean(KEY_NEARBY_DISCOVERABLE, true),
            cloudBackupOnWifi = sp.getBoolean(KEY_CLOUD_BACKUP_WIFI, true),
            acceptClipShare = sp.getBoolean(KEY_ACCEPT_CLIP_SHARE, true),
            onboardingDone = sp.getBoolean(KEY_ONBOARDING_DONE, false),
            notificationAsked = sp.getBoolean(KEY_NOTIFICATION_ASKED, false),
            minimizeHintShown = sp.getBoolean(KEY_MINIMIZE_HINT_SHOWN, false),
            cameraPermAsked = sp.getBoolean(KEY_CAMERA_PERM_ASKED, false),
            audioPermAsked = sp.getBoolean(KEY_AUDIO_PERM_ASKED, false),
        )
    }

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
            .putString(KEY_APPEARANCE, prefs.appearance.id)
            .putBoolean(KEY_NEARBY_DISCOVERABLE, prefs.nearbyDiscoverable)
            .putBoolean(KEY_CLOUD_BACKUP_WIFI, prefs.cloudBackupOnWifi)
            .putBoolean(KEY_ACCEPT_CLIP_SHARE, prefs.acceptClipShare)
            .putBoolean(KEY_ONBOARDING_DONE, prefs.onboardingDone)
            .putBoolean(KEY_NOTIFICATION_ASKED, prefs.notificationAsked)
            .putBoolean(KEY_MINIMIZE_HINT_SHOWN, prefs.minimizeHintShown)
            .putBoolean(KEY_CAMERA_PERM_ASKED, prefs.cameraPermAsked)
            .putBoolean(KEY_AUDIO_PERM_ASKED, prefs.audioPermAsked)
            .apply()
    }

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

    fun setAppearance(mode: AppearanceMode) {
        sp.edit().putString(KEY_APPEARANCE, mode.id).apply()
    }

    fun setNearbyDiscoverable(enabled: Boolean) {
        sp.edit().putBoolean(KEY_NEARBY_DISCOVERABLE, enabled).apply()
    }

    fun setCloudBackupOnWifi(enabled: Boolean) {
        sp.edit().putBoolean(KEY_CLOUD_BACKUP_WIFI, enabled).apply()
    }

    fun setAcceptClipShare(enabled: Boolean) {
        sp.edit().putBoolean(KEY_ACCEPT_CLIP_SHARE, enabled).apply()
    }

    fun setOnboardingDone(done: Boolean) {
        sp.edit().putBoolean(KEY_ONBOARDING_DONE, done).apply()
    }

    fun setNotificationAsked(asked: Boolean) {
        sp.edit().putBoolean(KEY_NOTIFICATION_ASKED, asked).apply()
    }

    // 记录「收起房间」一次性引导提示已展示
    fun setMinimizeHintShown(shown: Boolean) {
        sp.edit().putBoolean(KEY_MINIMIZE_HINT_SHOWN, shown).apply()
    }

    fun setCameraPermAsked(asked: Boolean) {
        sp.edit().putBoolean(KEY_CAMERA_PERM_ASKED, asked).apply()
    }

    fun setAudioPermAsked(asked: Boolean) {
        sp.edit().putBoolean(KEY_AUDIO_PERM_ASKED, asked).apply()
    }

    fun loadSavedRooms(): List<SavedFixedRoom> {
        val raw = sp.getString(KEY_SAVED_ROOMS, null) ?: return emptyList()
        return runCatching {
            json.decodeFromString(ListSerializer(SavedFixedRoom.serializer()), raw)
        }.getOrDefault(emptyList())
    }

    fun upsertSavedRoom(room: SavedFixedRoom): List<SavedFixedRoom> {
        val existing = loadSavedRooms().firstOrNull { it.code.equals(room.code, ignoreCase = true) }
        val merged = room.copy(
            ownerToken = room.ownerToken.ifBlank { existing?.ownerToken.orEmpty() },
            alias = room.alias.ifBlank { existing?.alias.orEmpty() },
            lastVisitedAt = System.currentTimeMillis(),
        )
        val list = loadSavedRooms().filterNot { it.code.equals(room.code, ignoreCase = true) }.toMutableList()
        list.add(0, merged)
        val trimmed = list.take(20)
        sp.edit().putString(KEY_SAVED_ROOMS, json.encodeToString(ListSerializer(SavedFixedRoom.serializer()), trimmed)).apply()
        return trimmed
    }

    fun renameSavedRoom(code: String, alias: String): List<SavedFixedRoom> {
        val next = loadSavedRooms().map {
            if (it.code.equals(code, ignoreCase = true)) it.copy(alias = alias.trim().take(24)) else it
        }
        sp.edit().putString(KEY_SAVED_ROOMS, json.encodeToString(ListSerializer(SavedFixedRoom.serializer()), next)).apply()
        return next
    }

    fun updateSavedPassword(code: String, newPassword: String): List<SavedFixedRoom> {
        val clean = newPassword.trim()
        val next = loadSavedRooms().map {
            if (it.code.equals(code, ignoreCase = true)) {
                it.copy(hasPassword = clean.isNotBlank(), password = clean)
            } else {
                it
            }
        }
        sp.edit().putString(KEY_SAVED_ROOMS, json.encodeToString(ListSerializer(SavedFixedRoom.serializer()), next)).apply()
        return next
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
        val existing = loadRecentRooms().firstOrNull { it.code.equals(room.code, ignoreCase = true) }
        val merged = room.copy(
            ownerToken = room.ownerToken.ifBlank { existing?.ownerToken.orEmpty() },
            lastVisitedAt = System.currentTimeMillis(),
        )
        val list = loadRecentRooms().filterNot { it.code.equals(room.code, ignoreCase = true) }.toMutableList()
        list.add(0, merged)
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

    // 持久化屏蔽列表（按 clientId 唯一去重）
    fun loadBlockedPeers(): List<BlockedPeer> {
        val raw = sp.getString(KEY_BLOCKED_CLIENTS, null) ?: return emptyList()
        return runCatching {
            json.decodeFromString(ListSerializer(BlockedPeer.serializer()), raw)
                .sortedByDescending { it.blockedAt }
        }.getOrDefault(emptyList())
    }

    fun addBlockedPeer(peer: BlockedPeer): List<BlockedPeer> {
        val list = loadBlockedPeers()
            .filterNot { it.clientId.equals(peer.clientId, ignoreCase = true) }
            .toMutableList()
        list.add(0, peer)
        val trimmed = list.take(200)
        sp.edit().putString(KEY_BLOCKED_CLIENTS, json.encodeToString(ListSerializer(BlockedPeer.serializer()), trimmed)).apply()
        return trimmed
    }

    fun removeBlockedPeer(clientId: String): List<BlockedPeer> {
        val next = loadBlockedPeers().filterNot { it.clientId.equals(clientId, ignoreCase = true) }
        sp.edit().putString(KEY_BLOCKED_CLIENTS, json.encodeToString(ListSerializer(BlockedPeer.serializer()), next)).apply()
        return next
    }

    fun clearBlockedPeers(): List<BlockedPeer> {
        sp.edit().remove(KEY_BLOCKED_CLIENTS).apply()
        return emptyList()
    }

    // 我发出的离线取件码持久化记录
    fun loadMyPickups(): List<SavedPickupRecord> {
        val raw = sp.getString(KEY_MY_PICKUPS, null) ?: return emptyList()
        return runCatching {
            json.decodeFromString(ListSerializer(SavedPickupRecord.serializer()), raw)
                .sortedByDescending { it.createdAt }
        }.getOrDefault(emptyList())
    }

    fun upsertMyPickup(record: SavedPickupRecord): List<SavedPickupRecord> {
        val list = loadMyPickups()
            .filterNot { it.code.equals(record.code, ignoreCase = true) }
            .toMutableList()
        list.add(0, record)
        val trimmed = list.take(30)
        sp.edit().putString(KEY_MY_PICKUPS, json.encodeToString(ListSerializer(SavedPickupRecord.serializer()), trimmed)).apply()
        return trimmed
    }

    fun removeMyPickup(code: String): List<SavedPickupRecord> {
        val next = loadMyPickups().filterNot { it.code.equals(code, ignoreCase = true) }
        sp.edit().putString(KEY_MY_PICKUPS, json.encodeToString(ListSerializer(SavedPickupRecord.serializer()), next)).apply()
        return next
    }

    // 进程被杀后最近活跃房间持久化（用于 10 分钟内冷启动恢复提示）
    fun loadLastActiveRoom(): LastActiveRoom? {
        val raw = sp.getString(KEY_LAST_ACTIVE_ROOM, null) ?: return null
        return runCatching {
            json.decodeFromString(LastActiveRoom.serializer(), raw)
        }.getOrNull()
    }

    fun saveLastActiveRoom(room: LastActiveRoom) {
        sp.edit().putString(KEY_LAST_ACTIVE_ROOM, json.encodeToString(LastActiveRoom.serializer(), room)).apply()
    }

    fun clearLastActiveRoom() {
        sp.edit().remove(KEY_LAST_ACTIVE_ROOM).apply()
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

    // 按房间保存输入草稿（最近最多保留 20 个房间，roomCode 统一大写规范化）
    fun loadRoomDrafts(): Map<String, String> {
        val raw = sp.getString(KEY_ROOM_DRAFTS, null) ?: return emptyMap()
        return runCatching {
            val decoded = json.decodeFromString(MapSerializer(String.serializer(), String.serializer()), raw)
            val normalized = linkedMapOf<String, String>()
            decoded.forEach { (k, v) ->
                val cleanKey = k.trim().uppercase()
                if (cleanKey.isNotEmpty() && v.isNotEmpty()) {
                    normalized[cleanKey] = v
                }
            }
            normalized.toMap()
        }.getOrDefault(emptyMap())
    }

    fun saveRoomDraft(roomCode: String, text: String): Map<String, String> {
        val key = roomCode.trim().uppercase()
        if (key.isEmpty()) return loadRoomDrafts()
        val map = LinkedHashMap(loadRoomDrafts())
        map.remove(key)
        if (text.isNotEmpty()) {
            map[key] = text
        }
        while (map.size > MAX_ROOM_DRAFTS) {
            val oldestKey = map.keys.firstOrNull() ?: break
            map.remove(oldestKey)
        }
        if (map.isEmpty()) {
            sp.edit().remove(KEY_ROOM_DRAFTS).apply()
        } else {
            sp.edit()
                .putString(KEY_ROOM_DRAFTS, json.encodeToString(MapSerializer(String.serializer(), String.serializer()), map))
                .apply()
        }
        return map.toMap()
    }

    // 按房间保存消息免打扰开关（roomCode 统一大写规范化）
    fun loadMutedRooms(): Set<String> {
        val raw = sp.getString(KEY_MUTED_ROOMS, null) ?: return emptySet()
        return runCatching {
            json.decodeFromString(ListSerializer(String.serializer()), raw)
                .map { it.trim().uppercase() }
                .filter { it.isNotEmpty() }
                .toSet()
        }.getOrDefault(emptySet())
    }

    fun setRoomMuted(code: String, muted: Boolean): Set<String> {
        val key = code.trim().uppercase()
        if (key.isEmpty()) return loadMutedRooms()
        val next = loadMutedRooms().toMutableSet()
        if (muted) next.add(key) else next.remove(key)
        if (next.isEmpty()) {
            sp.edit().remove(KEY_MUTED_ROOMS).apply()
        } else {
            sp.edit()
                .putString(KEY_MUTED_ROOMS, json.encodeToString(ListSerializer(String.serializer()), next.toList()))
                .apply()
        }
        return next.toSet()
    }

    // UX-10：低内存相机拍照临时 URI 持久化，防止系统杀进程后冷启动丢失拍摄结果
    fun savePendingCameraCapture(roomCode: String, uriString: String) {
        val key = roomCode.trim().uppercase()
        if (key.isEmpty() || uriString.isBlank()) return
        val payload = listOf(key, System.currentTimeMillis().toString(), uriString.trim())
        sp.edit()
            .putString(KEY_PENDING_CAMERA_CAPTURE, json.encodeToString(ListSerializer(String.serializer()), payload))
            .apply()
    }

    fun peekPendingCameraCapture(roomCode: String): String? {
        val raw = sp.getString(KEY_PENDING_CAMERA_CAPTURE, null) ?: return null
        val list = runCatching {
            json.decodeFromString(ListSerializer(String.serializer()), raw)
        }.getOrNull() ?: run {
            clearPendingCameraCapture()
            return null
        }
        if (list.size < 3) {
            clearPendingCameraCapture()
            return null
        }
        val savedCode = list[0]
        val savedAt = list[1].toLongOrNull() ?: 0L
        val savedUri = list[2]
        val ageMs = System.currentTimeMillis() - savedAt
        if (ageMs !in 0L..PENDING_CAMERA_TTL_MS || savedUri.isBlank()) {
            clearPendingCameraCapture()
            return null
        }
        return if (savedCode.equals(roomCode.trim(), ignoreCase = true)) savedUri else null
    }

    fun consumePendingCameraCapture(roomCode: String): String? {
        val matched = peekPendingCameraCapture(roomCode) ?: return null
        clearPendingCameraCapture()
        return matched
    }

    fun clearPendingCameraCapture() {
        sp.edit().remove(KEY_PENDING_CAMERA_CAPTURE).apply()
    }

    // 加载本地收藏列表（最多保留 300 条，最新在前）
    fun loadFavorites(): List<FavoriteItem> {
        val raw = sp.getString(KEY_FAVORITES, null) ?: return emptyList()
        return runCatching {
            json.decodeFromString(ListSerializer(FavoriteItem.serializer()), raw).take(MAX_FAVORITES)
        }.getOrDefault(emptyList())
    }

    // 保存本地收藏列表（最多保留 300 条，最新在前）
    fun saveFavorites(list: List<FavoriteItem>) {
        val capped = list.take(MAX_FAVORITES)
        if (capped.isEmpty()) {
            sp.edit().remove(KEY_FAVORITES).apply()
        } else {
            sp.edit()
                .putString(KEY_FAVORITES, json.encodeToString(ListSerializer(FavoriteItem.serializer()), capped))
                .apply()
        }
    }

    // 加载最近使用的表情列表（最多 24 个）
    fun loadRecentEmojis(): List<String> {
        val raw = sp.getString(KEY_RECENT_EMOJIS, null) ?: return emptyList()
        return runCatching {
            json.decodeFromString(ListSerializer(String.serializer()), raw)
                .filter { it.isNotBlank() }
                .distinct()
                .take(MAX_RECENT_EMOJIS)
        }.getOrDefault(emptyList())
    }

    // 保存最近使用的表情列表（最多 24 个）
    fun saveRecentEmojis(list: List<String>) {
        val capped = list.filter { it.isNotBlank() }.distinct().take(MAX_RECENT_EMOJIS)
        if (capped.isEmpty()) {
            sp.edit().remove(KEY_RECENT_EMOJIS).apply()
        } else {
            sp.edit()
                .putString(KEY_RECENT_EMOJIS, json.encodeToString(ListSerializer(String.serializer()), capped))
                .apply()
        }
    }

    companion object {
        private const val MAX_ROOM_DRAFTS = 20
        private const val MAX_FAVORITES = 300
        private const val MAX_RECENT_EMOJIS = 24
        private const val PENDING_CAMERA_TTL_MS = 30L * 60L * 1000L
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
        private const val KEY_APPEARANCE = "ui_appearance_mode_v1"
        private const val KEY_NEARBY_DISCOVERABLE = "nearby_discoverable_v1"
        private const val KEY_CLOUD_BACKUP_WIFI = "cloud_backup_wifi_v1"
        private const val KEY_ACCEPT_CLIP_SHARE = "accept_clip_share_v1"
        private const val KEY_ONBOARDING_DONE = "onboarding_done_v1"
        private const val KEY_NOTIFICATION_ASKED = "notification_asked_v1"
        private const val KEY_MINIMIZE_HINT_SHOWN = "minimize_hint_shown_v1"
        private const val KEY_CAMERA_PERM_ASKED = "camera_perm_asked_v1"
        private const val KEY_AUDIO_PERM_ASKED = "audio_perm_asked_v1"
        private const val KEY_BLOCKED_CLIENTS = "blocked_clients_v1"
        private const val KEY_MY_PICKUPS = "my_pickups_v1"
        private const val KEY_LAST_ACTIVE_ROOM = "last_active_room_v1"
        private const val KEY_ROOM_DRAFTS = "room_drafts_v1"
        private const val KEY_MUTED_ROOMS = "muted_rooms_v1"
        private const val KEY_FAVORITES = "favorites_v1"
        private const val KEY_RECENT_EMOJIS = "recent_emojis_v1"
        private const val KEY_PENDING_CAMERA_CAPTURE = "pending_camera_capture_v1"
    }
}
