package com.baytale.baychat.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.baytale.baychat.core.model.AppThemeMode
import com.baytale.baychat.core.model.AppearanceMode

// ============================================================================
// Bay Design 2.0 视觉主题系统（颜色 Token、字体层级、圆角形状、间距网格）
// ============================================================================

/**
 * 8 种文件分类的语义化图标配色，随浅色/深色外观自适应明度。
 */
@Immutable
data class BayFileTypeColors(
    val pdf: Color,
    val doc: Color,
    val archive: Color,
    val apk: Color,
    val image: Color,
    val video: Color,
    val audio: Color,
    val other: Color,
)

/**
 * Bay Design 2.0 核心颜色 Token。
 * 全应用通过 [LocalBayColors] 或 [BayTheme.colors] 读取，彻底消除散落硬编码色值与全局描边。
 */
@Immutable
data class BayColors(
    val bg: Color,
    val surface: Color,
    val surfaceElevated: Color,
    val fill: Color,
    val fillStrong: Color,
    val separator: Color,
    val accent: Color,
    val accentSoft: Color,
    val onAccent: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val success: Color,
    val warning: Color,
    val danger: Color,
    val toastBg: Color,
    val isDark: Boolean,
    val fileTypeColors: BayFileTypeColors,
) {
    // ------------------------------------------------------------------------
    // Phase 1 兼容映射属性（确保旧页面在迁移完成前 100% 编译通过，Phase 2 结束后统一清理）
    // ------------------------------------------------------------------------
    @Deprecated("请迁移至 bg", ReplaceWith("bg"))
    val background: Color get() = bg

    @Deprecated("请迁移至 textPrimary", ReplaceWith("textPrimary"))
    val textMain: Color get() = textPrimary

    @Deprecated("请迁移至 textSecondary", ReplaceWith("textSecondary"))
    val textSub: Color get() = textSecondary

    @Deprecated("请迁移至 textTertiary", ReplaceWith("textTertiary"))
    val textMuted: Color get() = textTertiary

    @Deprecated("Bay Design 2.0 已废弃全局描边，请改用层级与填充色", ReplaceWith("Color.Transparent"))
    val border: Color get() = Color.Transparent

    @Deprecated("请迁移至 fill", ReplaceWith("fill"))
    val surfaceSubtle: Color get() = fill

    @Deprecated("请迁移至 success", ReplaceWith("success"))
    val emerald: Color get() = success

    @Deprecated("请迁移至 warning", ReplaceWith("warning"))
    val amber: Color get() = warning

    @Deprecated("请迁移至 accent", ReplaceWith("accent"))
    val accentSecondary: Color get() = accent

    @Deprecated(
        "请迁移至 surface 或 surfaceElevated",
        ReplaceWith("if (isDark) surfaceElevated else surface"),
    )
    val peerBubbleBg: Color get() = if (isDark) surfaceElevated else surface

    @Deprecated("请迁移至 onAccent", ReplaceWith("onAccent"))
    val selfBubbleText: Color get() = onAccent
}

/** Phase 1 兼容类型别名，供旧代码无缝过渡 */
typealias BayChatColors = BayColors

private val LightFileTypeColors = BayFileTypeColors(
    pdf = Color(0xFFFF3B30),
    doc = Color(0xFF007AFF),
    archive = Color(0xFFFF9500),
    apk = Color(0xFF34C759),
    image = Color(0xFFAF52DE),
    video = Color(0xFF5856D6),
    audio = Color(0xFFFF2D55),
    other = Color(0xFF8E8E93),
)

private val DarkFileTypeColors = BayFileTypeColors(
    pdf = Color(0xFFFF453A),
    doc = Color(0xFF0A84FF),
    archive = Color(0xFFFF9F0A),
    apk = Color(0xFF30D158),
    image = Color(0xFFBF5AF2),
    video = Color(0xFF5E5CE6),
    audio = Color(0xFFFF375F),
    other = Color(0xFF98989D),
)

/**
 * 解析主题主色（NEON 已废弃，传入时自动回退至 AIR 海湾蓝）。
 */
internal fun accentColorFor(mode: AppThemeMode, isDark: Boolean): Color = when (mode) {
    AppThemeMode.AIR, AppThemeMode.NEON -> if (isDark) Color(0xFF0A84FF) else Color(0xFF007AFF)
    AppThemeMode.MINT -> if (isDark) Color(0xFF3CC4D8) else Color(0xFF14A3B8)
    AppThemeMode.BERRY -> if (isDark) Color(0xFF7D7AFF) else Color(0xFF5E5CE6)
    AppThemeMode.PEACH -> if (isDark) Color(0xFFFF6B70) else Color(0xFFFF5A5F)
    AppThemeMode.LIME -> if (isDark) Color(0xFF30D158) else Color(0xFF34C759)
}

/**
 * 根据主题主色与深浅模式生成完整的 [BayColors] 色板。
 */
fun bayColorsFor(accentMode: AppThemeMode, isDark: Boolean): BayColors {
    val accent = accentColorFor(accentMode, isDark)
    return if (isDark) {
        BayColors(
            bg = Color(0xFF000000),
            surface = Color(0xFF1C1C1E),
            surfaceElevated = Color(0xFF2C2C2E),
            fill = Color(0x3D767680),
            fillStrong = Color(0x5C787880),
            separator = Color(0x99545458),
            accent = accent,
            accentSoft = accent.copy(alpha = 0.22f),
            onAccent = Color.White,
            textPrimary = Color(0xFFFFFFFF),
            textSecondary = Color(0x99EBEBF5),
            textTertiary = Color(0x4DEBEBF5),
            success = Color(0xFF30D158),
            warning = Color(0xFFFF9F0A),
            danger = Color(0xFFFF453A),
            toastBg = Color(0xFF3A3A3C),
            isDark = true,
            fileTypeColors = DarkFileTypeColors,
        )
    } else {
        BayColors(
            bg = Color(0xFFF2F2F7),
            surface = Color(0xFFFFFFFF),
            surfaceElevated = Color(0xFFFFFFFF),
            fill = Color(0x1F767680),
            fillStrong = Color(0x33787880),
            separator = Color(0x4A3C3C43),
            accent = accent,
            accentSoft = accent.copy(alpha = 0.12f),
            onAccent = Color.White,
            textPrimary = Color(0xFF000000),
            textSecondary = Color(0x993C3C43),
            textTertiary = Color(0x4D3C3C43),
            success = Color(0xFF34C759),
            warning = Color(0xFFFF9500),
            danger = Color(0xFFFF3B30),
            toastBg = Color(0xEB1C1C1E),
            isDark = false,
            fileTypeColors = LightFileTypeColors,
        )
    }
}

val LocalBayColors = staticCompositionLocalOf { bayColorsFor(AppThemeMode.AIR, isDark = false) }

/**
 * 快捷主题访问入口。
 */
object BayTheme {
    val colors: BayColors
        @Composable
        @ReadOnlyComposable
        get() = LocalBayColors.current
}

/**
 * 主题色球指示颜色（支持浅深色模式，保留单参默认调用兼容性）。
 */
fun themeOrbColor(mode: AppThemeMode, isDark: Boolean = false): Color = accentColorFor(mode, isDark)

// ============================================================================
// 字体阶梯（BayType）、圆角系统（BayShapes）与 4/8dp 间距网格（BaySpacing）
// ============================================================================

object BayType {
    val largeTitle = TextStyle(
        fontSize = 28.sp,
        lineHeight = 34.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.4).sp,
    )
    val title1 = TextStyle(
        fontSize = 22.sp,
        lineHeight = 28.sp,
        fontWeight = FontWeight.SemiBold,
    )
    val title2 = TextStyle(
        fontSize = 20.sp,
        lineHeight = 25.sp,
        fontWeight = FontWeight.SemiBold,
    )
    val headline = TextStyle(
        fontSize = 17.sp,
        lineHeight = 22.sp,
        fontWeight = FontWeight.SemiBold,
    )
    val body = TextStyle(
        fontSize = 16.sp,
        lineHeight = 22.sp,
        fontWeight = FontWeight.Normal,
    )
    val callout = TextStyle(
        fontSize = 15.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.Normal,
    )
    val subhead = TextStyle(
        fontSize = 14.sp,
        lineHeight = 19.sp,
        fontWeight = FontWeight.Normal,
    )
    val footnote = TextStyle(
        fontSize = 13.sp,
        lineHeight = 18.sp,
        fontWeight = FontWeight.Normal,
    )
    val caption = TextStyle(
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.Medium,
    )
    val codeLarge = TextStyle(
        fontSize = 32.sp,
        lineHeight = 38.sp,
        fontWeight = FontWeight.Bold,
        fontFamily = FontFamily.Monospace,
    )
}

object BayShapes {
    val xs = 8.dp
    val sm = 12.dp
    val md = 16.dp
    val lg = 20.dp
    val xl = 28.dp
    val full = 999.dp

    val xsShape = RoundedCornerShape(xs)
    val smShape = RoundedCornerShape(sm)
    val mdShape = RoundedCornerShape(md)
    val lgShape = RoundedCornerShape(lg)
    val xlShape = RoundedCornerShape(xl)
    val fullShape = RoundedCornerShape(full)
    val sheetTopShape = RoundedCornerShape(topStart = xl, topEnd = xl)
}

object BaySpacing {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 20.dp
    val xxl = 24.dp
    val xxxl = 32.dp
}

private val BayMaterialTypography = Typography(
    displayLarge = BayType.codeLarge,
    displayMedium = BayType.largeTitle,
    displaySmall = BayType.title1,
    headlineLarge = BayType.largeTitle,
    headlineMedium = BayType.title1,
    headlineSmall = BayType.title2,
    titleLarge = BayType.title2,
    titleMedium = BayType.headline,
    titleSmall = BayType.subhead,
    bodyLarge = BayType.body,
    bodyMedium = BayType.callout,
    bodySmall = BayType.footnote,
    labelLarge = BayType.callout.copy(fontWeight = FontWeight.Medium),
    labelMedium = BayType.caption,
    labelSmall = BayType.caption,
)

private val BayMaterialShapes = Shapes(
    extraSmall = BayShapes.xsShape,
    small = BayShapes.smShape,
    medium = BayShapes.mdShape,
    large = BayShapes.lgShape,
    extraLarge = BayShapes.xlShape,
)

/**
 * BayChat 根主题入口：提供 [BayColors]、完整覆盖 M3 [MaterialTheme] 角色并实时同步系统栏图标明暗。
 */
@Composable
fun BayChatTheme(
    accent: AppThemeMode = AppThemeMode.AIR,
    appearance: AppearanceMode = AppearanceMode.SYSTEM,
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val isDark = when (appearance) {
        AppearanceMode.SYSTEM -> systemDark
        AppearanceMode.LIGHT -> false
        AppearanceMode.DARK -> true
    }

    val palette = bayColorsFor(accentMode = accent, isDark = isDark)
    val view = LocalView.current

    // 随外观明暗实时同步系统状态栏与导航栏图标颜色，避免浅色底上白字隐身
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
            onPrimary = palette.onAccent,
            primaryContainer = palette.accentSoft,
            onPrimaryContainer = palette.accent,
            secondary = palette.accent,
            onSecondary = palette.onAccent,
            secondaryContainer = palette.accentSoft,
            onSecondaryContainer = palette.accent,
            background = palette.bg,
            surface = palette.surface,
            surfaceVariant = palette.surfaceElevated,
            surfaceContainer = palette.surfaceElevated,
            surfaceContainerLow = palette.surface,
            surfaceContainerHigh = palette.surfaceElevated,
            surfaceContainerHighest = palette.surfaceElevated,
            surfaceContainerLowest = palette.surface,
            onBackground = palette.textPrimary,
            onSurface = palette.textPrimary,
            onSurfaceVariant = palette.textSecondary,
            outline = palette.fillStrong,
            outlineVariant = Color.Transparent,
            error = palette.danger,
            onError = palette.onAccent,
            surfaceTint = Color.Transparent,
            scrim = Color.Black.copy(alpha = 0.40f),
        )
    } else {
        lightColorScheme(
            primary = palette.accent,
            onPrimary = palette.onAccent,
            primaryContainer = palette.accentSoft,
            onPrimaryContainer = palette.accent,
            secondary = palette.accent,
            onSecondary = palette.onAccent,
            secondaryContainer = palette.accentSoft,
            onSecondaryContainer = palette.accent,
            background = palette.bg,
            surface = palette.surface,
            surfaceVariant = palette.surfaceElevated,
            surfaceContainer = palette.surfaceElevated,
            surfaceContainerLow = palette.surface,
            surfaceContainerHigh = palette.surfaceElevated,
            surfaceContainerHighest = palette.surfaceElevated,
            surfaceContainerLowest = palette.surface,
            onBackground = palette.textPrimary,
            onSurface = palette.textPrimary,
            onSurfaceVariant = palette.textSecondary,
            outline = palette.fillStrong,
            outlineVariant = Color.Transparent,
            error = palette.danger,
            onError = palette.onAccent,
            surfaceTint = Color.Transparent,
            scrim = Color.Black.copy(alpha = 0.40f),
        )
    }

    CompositionLocalProvider(LocalBayColors provides palette) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = BayMaterialTypography,
            shapes = BayMaterialShapes,
            content = content,
        )
    }
}

/**
 * Phase 1 兼容重载，供尚未升级 AppearanceMode 的旧入口无缝编译。
 */
@Deprecated(
    "请迁移至 BayChatTheme(accent = ..., appearance = ...)",
    ReplaceWith("BayChatTheme(accent = themeMode, appearance = AppearanceMode.SYSTEM, content = content)"),
)
@Composable
fun BayChatTheme(
    themeMode: AppThemeMode,
    content: @Composable () -> Unit,
) {
    BayChatTheme(
        accent = themeMode,
        appearance = AppearanceMode.SYSTEM,
        content = content,
    )
}
