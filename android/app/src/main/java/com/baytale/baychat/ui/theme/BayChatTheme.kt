package com.baytale.baychat.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
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
    AppThemeMode.PEACH -> BayChatColors(
        background = Color(0xFFFFF8F6),
        surface = Color(0xFFFFFFFF),
        surfaceSubtle = Color(0xFFFFF0F4),
        accent = Color(0xFFFF5487),
        accentSecondary = Color(0xFFFF8B53),
        accentSoft = Color(0x1FFF5487),
        textMain = Color(0xFF1F1629),
        textSub = Color(0xFF5C4D6D),
        textMuted = Color(0xFF9382A6),
        border = Color(0x2BF472B6),
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
        accentSoft = Color(0x2206B6D4),
        textMain = Color(0xFF0F2430),
        textSub = Color(0xFF456173),
        textMuted = Color(0xFF7B98AA),
        border = Color(0x2B06B6D4),
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
        accent = Color(0xFF8B5CF6),
        accentSecondary = Color(0xFFEC4899),
        accentSoft = Color(0x228B5CF6),
        textMain = Color(0xFF1D1533),
        textSub = Color(0xFF574978),
        textMuted = Color(0xFF8B7DAB),
        border = Color(0x2B8B5CF6),
        selfBubbleText = Color.White,
        peerBubbleBg = Color(0xFFFFFFFF),
        emerald = Color(0xFF10B981),
        amber = Color(0xFFF59E0B),
        danger = Color(0xFFE11D48),
        isDark = false,
    )
    AppThemeMode.LIME -> BayChatColors(
        background = Color(0xFFF7FEE7),
        surface = Color(0xFFFFFFFF),
        surfaceSubtle = Color(0xFFECFDF5),
        accent = Color(0xFF059669),
        accentSecondary = Color(0xFFF59E0B),
        accentSoft = Color(0x2210B981),
        textMain = Color(0xFF14291E),
        textSub = Color(0xFF426352),
        textMuted = Color(0xFF789987),
        border = Color(0x2B10B981),
        selfBubbleText = Color.White,
        peerBubbleBg = Color(0xFFFFFFFF),
        emerald = Color(0xFF10B981),
        amber = Color(0xFFF59E0B),
        danger = Color(0xFFE11D48),
        isDark = false,
    )
    AppThemeMode.NEON -> BayChatColors(
        background = Color(0xFF0B091A),
        surface = Color(0xFF1B1738),
        surfaceSubtle = Color(0xFF25204B),
        accent = Color(0xFFFB7185),
        accentSecondary = Color(0xFF22D3EE),
        accentSoft = Color(0x33F43F5E),
        textMain = Color(0xFFF8FAFC),
        textSub = Color(0xFFCBD5E1),
        textMuted = Color(0xFF8B95B5),
        border = Color(0x22FFFFFF),
        selfBubbleText = Color.White,
        peerBubbleBg = Color(0xFF211D40),
        emerald = Color(0xFF34D399),
        amber = Color(0xFFFBBF24),
        danger = Color(0xFFFB7185),
        isDark = true,
    )
}

val LocalBayColors = staticCompositionLocalOf { paletteFor(AppThemeMode.PEACH) }

fun themeOrbColor(mode: AppThemeMode): Color = when (mode) {
    AppThemeMode.PEACH -> Color(0xFFFF5487)
    AppThemeMode.MINT -> Color(0xFF06B6D4)
    AppThemeMode.BERRY -> Color(0xFF8B5CF6)
    AppThemeMode.LIME -> Color(0xFF10B981)
    AppThemeMode.NEON -> Color(0xFF1E1B4B)
}

@Composable
fun BayChatTheme(
    themeMode: AppThemeMode,
    content: @Composable () -> Unit,
) {
    val palette = paletteFor(themeMode)
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
