package com.baytale.baychat.core.model

// 外观模式：支持跟随系统、强制浅色、强制深色
enum class AppearanceMode(val id: String) {
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark"),
    ;

    companion object {
        // 根据持久化 ID 解析外观模式，默认跟随系统
        fun fromId(id: String?): AppearanceMode =
            entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: SYSTEM
    }
}
