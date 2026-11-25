package com.baytale.baychat.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.baytale.baychat.core.model.AppThemeMode

data class BayChatColors(
    val background: Color,
    val surface: Color,
    val surfaceSubtle: Color,
    val accent: Color,
    val accentSecondary: Color,
    val accentSoft: Color,
    val textMain: Color,
    val textSub: Color,
    val textMuted: Color,
    val border: Color,
    val selfBubbleText: Color,
    val peerBubbleBg: Color,
    val emerald: Color,
    val amber: Color,
    val danger: Color,
    val isDark: Boolean,
)

private fun paletteFor(mode: AppThemeMode): BayChatColors = when (mode) {
    // 方案 B · 清风极简（默认旗舰配色：澎湃 OS / 隔空投送清爽工具质感）
    AppThemeMode.AIR -> BayChatColors(
        background = Color(0xFFF3F6FB),
        surface = Color(0xFFFFFFFF),
        surfaceSubtle = Color(0xFFEEF3FA),
        accent = Color(0xFF2563EB),
        accentSecondary = Color(0xFF06B6D4),
        accentSoft = Color(0x1A2563EB),
        textMain = Color(0xFF0F172A),
        textSub = Color(0xFF475569),
        textMuted = Color(0xFF8596AB),
        border = Color(0x170F172A),
        selfBubbleText = Color.White,
        peerBubbleBg = Color(0xFFFFFFFF),
        emerald = Color(0xFF059669),
        amber = Color(0xFFD97706),
        danger = Color(0xFFE11D48),
        isDark = false,
    )
    AppThemeMode.PEACH -> BayChatColors(
        background = Color(0xFFFFF8F6),
        surface = Color(0xFFFFFFFF),
        surfaceSubtle = Color(0xFFFFF1F3),
        accent = Color(0xFFFF4F81),
        accentSecondary = Color(0xFFFF8A50),
        accentSoft = Color(0x1DFF4F81),
        textMain = Color(0xFF1E1528),
        textSub = Color(0xFF5D4E6E),
        textMuted = Color(0xFF9483A6),
        border = Color(0x28F472B6),
        selfBubbleText = Color.White,
        peerBubbleBg = Color(0xFFFFFFFF),
        emerald = Color(0xFF10B981),
        amber = Color(0xFFF59E0B),
        danger = Color(0xFFE11D48),
        isDark = false,
    )
    AppThemeMode.MINT -> BayChatColors(
        background = Color(0xFFF2FBFD),
        surface = Color(0xFFFFFFFF),
        surfaceSubtle = Color(0xFFECFEFF),
        accent = Color(0xFF0891B2),
        accentSecondary = Color(0xFF3B82F6),
        accentSoft = Color(0x2006B6D4),
        textMain = Color(0xFF0F2430),
        textSub = Color(0xFF456173),
        textMuted = Color(0xFF7B98AA),
        border = Color(0x2606B6D4),
        selfBubbleText = Color.White,
        peerBubbleBg = Color(0xFFFFFFFF),
        emerald = Color(0xFF10B981),
        amber = Color(0xFFF59E0B),
        danger = Color(0xFFE11D48),
        isDark = false,
    )
    AppThemeMode.BERRY -> BayChatColors(
        background = Color(0xFFF9F5FF),
        surface = Color(0xFFFFFFFF),
        surfaceSubtle = Color(0xFFF5F0FF),
        accent = Color(0xFF7C3AED),
        accentSecondary = Color(0xFFEC4899),
        accentSoft = Color(0x207C3AED),
        textMain = Color(0xFF1D1533),
        textSub = Color(0xFF574978),
        textMuted = Color(0xFF8B7DAB),
        border = Color(0x268B5CF6),
        selfBubbleText = Color.White,
        peerBubbleBg = Color(0xFFFFFFFF),
        emerald = Color(0xFF10B981),
        amber = Color(0xFFF59E0B),
        danger = Color(0xFFE11D48),
        isDark = false,
    )
    AppThemeMode.LIME -> BayChatColors(
        background = Color(0xFFF5FCF7),
        surface = Color(0xFFFFFFFF),
        surfaceSubtle = Color(0xFFECFDF5),
        accent = Color(0xFF059669),
        accentSecondary = Color(0xFF10B981),
        accentSoft = Color(0x2010B981),
        textMain = Color(0xFF14291E),
        textSub = Color(0xFF426352),
        textMuted = Color(0xFF789987),
        border = Color(0x2410B981),
        selfBubbleText = Color.White,
        peerBubbleBg = Color(0xFFFFFFFF),
        emerald = Color(0xFF10B981),
        amber = Color(0xFFF59E0B),
        danger = Color(0xFFE11D48),
        isDark = false,
    )
    // 方案 C · 暗夜光束（深夜护眼深色模式）
    AppThemeMode.NEON -> BayChatColors(
        background = Color(0xFF0C0F1A),
        surface = Color(0xFF161B2C),
        surfaceSubtle = Color(0xFF1F263D),
        accent = Color(0xFF38BDF8),
        accentSecondary = Color(0xFF818CF8),
        accentSoft = Color(0x2938BDF8),
        textMain = Color(0xFFF8FAFC),
        textSub = Color(0xFFCBD5E1),
        textMuted = Color(0xFF7E8BA8),
        border = Color(0x1EFFFFFF),
        selfBubbleText = Color.White,
        peerBubbleBg = Color(0xFF1D243B),
        emerald = Color(0xFF34D399),
        amber = Color(0xFFFBBF24),
        danger = Color(0xFFFB7185),
        isDark = true,
    )
}

val LocalBayColors = staticCompositionLocalOf { paletteFor(AppThemeMode.AIR) }

fun themeOrbColor(mode: AppThemeMode): Color = when (mode) {
    AppThemeMode.AIR -> Color(0xFF2563EB)
    AppThemeMode.PEACH -> Color(0xFFFF4F81)
    AppThemeMode.MINT -> Color(0xFF06B6D4)
    AppThemeMode.BERRY -> Color(0xFF7C3AED)
    AppThemeMode.LIME -> Color(0xFF059669)
    AppThemeMode.NEON -> Color(0xFF161B2C)
}

@Composable
fun BayChatTheme(
    themeMode: AppThemeMode,
    content: @Composable () -> Unit,
) {
    val palette = paletteFor(themeMode)
    val view = LocalView.current

    // 随主题实时切换系统状态栏与底部导航栏深浅图标，彻底避免浅色背景顶部时间/电量白字隐身
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !palette.isDark
            controller.isAppearanceLightNavigationBars = !palette.isDark
        }
    }

    val colorScheme = if (palette.isDark) {
        darkColorScheme(
            primary = palette.accent,
            secondary = palette.accentSecondary,
            background = palette.background,
            surface = palette.surface,
            onPrimary = Color.White,
            onBackground = palette.textMain,
            onSurface = palette.textMain,
            error = palette.danger,
        )
    } else {
        lightColorScheme(
            primary = palette.accent,
            secondary = palette.accentSecondary,
            background = palette.background,
            surface = palette.surface,
            onPrimary = Color.White,
            onBackground = palette.textMain,
            onSurface = palette.textMain,
            error = palette.danger,
        )
    }

    CompositionLocalProvider(LocalBayColors provides palette) {
        MaterialTheme(
            colorScheme = colorScheme,
            content = content,
        )
    }
}
