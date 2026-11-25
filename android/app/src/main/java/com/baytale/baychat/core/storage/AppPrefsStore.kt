package com.baytale.baychat.core.storage

import android.content.Context
import com.baytale.baychat.BuildConfig
import com.baytale.baychat.core.model.AppThemeMode
import com.baytale.baychat.core.model.RecentRoom
import com.baytale.baychat.core.model.SavedFixedRoom
import com.baytale.baychat.core.model.UserProfile
import com.baytale.baychat.core.util.FormatUtils
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

data class AppUserPrefs(
    val autoAcceptSmallFiles: Boolean = true,
    val autoAcceptMaxMB: Int = 50,
    val imageOriginalMode: Boolean = true,
    val serverUrl: String = BuildConfig.DEFAULT_SERVER_URL,
)

// 本地偏好与身份持久化仓库（全部存于应用私有 SharedPreferences，卸载自动清理）
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

    fun loadTheme(): AppThemeMode = AppThemeMode.fromId(sp.getString(KEY_THEME, null))

    fun saveTheme(mode: AppThemeMode) {
        sp.edit().putString(KEY_THEME, mode.id).apply()
    }

    fun loadPrefs(): AppUserPrefs = AppUserPrefs(
        autoAcceptSmallFiles = sp.getBoolean(KEY_AUTO_ACCEPT, true),
        autoAcceptMaxMB = sp.getInt(KEY_AUTO_ACCEPT_MB, 50).coerceIn(5, 500),
        imageOriginalMode = sp.getBoolean(KEY_IMAGE_ORIGINAL, true),
        serverUrl = sp.getString(KEY_SERVER_URL, BuildConfig.DEFAULT_SERVER_URL)
            ?.takeIf { it.isNotBlank() } ?: BuildConfig.DEFAULT_SERVER_URL,
    )

    fun savePrefs(prefs: AppUserPrefs) {
        sp.edit()
            .putBoolean(KEY_AUTO_ACCEPT, prefs.autoAcceptSmallFiles)
            .putInt(KEY_AUTO_ACCEPT_MB, prefs.autoAcceptMaxMB)
            .putBoolean(KEY_IMAGE_ORIGINAL, prefs.imageOriginalMode)
            .putString(KEY_SERVER_URL, prefs.serverUrl.trim().trimEnd('/'))
            .apply()
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

    companion object {
        private const val KEY_PRIVACY_ACCEPTED = "privacy_accepted_v1"
        private const val KEY_PROFILE = "user_profile"
        private const val KEY_THEME = "theme_mode"
        private const val KEY_AUTO_ACCEPT = "auto_accept_small"
        private const val KEY_AUTO_ACCEPT_MB = "auto_accept_max_mb"
        private const val KEY_IMAGE_ORIGINAL = "image_original"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_SAVED_ROOMS = "saved_fixed_rooms"
        private const val KEY_RECENT_ROOMS = "recent_rooms_v1"
    }
}
