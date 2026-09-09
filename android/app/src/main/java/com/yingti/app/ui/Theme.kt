package com.yingti.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance

/** 九套可切换版式，各带亮/暗模式；原有五套 key 和 primary 系推导保持不变。 */
data class YingtiPalette(
    val key: String,
    val name: String,
    val light: ColorScheme,
    val dark: ColorScheme,
)

/** 每套版式的基础色：只声明品牌相关的槽位，其余交给 [toScheme] 按统一规则补全。 */
data class YingtiPaletteBase(
    val primary: Color,
    val onPrimary: Color,
    val primaryContainer: Color,
    val onPrimaryContainer: Color,
    val secondary: Color,
    val background: Color,
    val surface: Color,
    val surfaceVariant: Color,
    val onSurfaceVariant: Color? = null,
    val error: Color = Color(0xFFBA1A1A),
    val onSurface: Color? = null,
    val secondaryContainer: Color? = null,
    val onSecondaryContainer: Color? = null,
    val onSecondary: Color? = null,
    val inverseSurface: Color? = null,
    val inverseOnSurface: Color? = null,
    val darkContent: Color = Color(0xFF1C1B1F),
)

/** 从基础色派生完整 ColorScheme（亮/暗共用一套推导规则，保持各版式一致）。 */
fun YingtiPaletteBase.toScheme(dark: Boolean): ColorScheme {
    val s = surface
    val sv = surfaceVariant

    // 文字/内容色：亮色深、暗色浅
    val onSurface = this.onSurface ?: if (dark) Color(0xFFE6E1E5) else Color(0xFF1C1B1F)
    val onSurfaceV = onSurfaceVariant ?: if (dark) Color(0xFFCAC4D0) else Color(0xFF49454F)

    // secondary 系：容器色由 surface 向 secondary 混色；前景按容器明度自适应
    val onSecondary = this.onSecondary ?: if (secondary.luminance() > 0.5f) darkContent else Color.White
    val secondaryContainer = this.secondaryContainer ?: lerp(s, secondary, if (dark) 0.30f else 0.14f)
    val onSecondaryContainer = this.onSecondaryContainer ?: if (secondaryContainer.luminance() > 0.5f) darkContent else Color.White

    // tertiary 系：secondary 向 primary 微偏，作为点缀（当前界面未使用，补全以防回落）
    val tertiary = lerp(secondary, primary, 0.15f)
    val onTertiary = if (tertiary.luminance() > 0.5f) darkContent else Color.White
    val tertiaryContainer = lerp(s, tertiary, if (dark) 0.30f else 0.14f)
    val onTertiaryContainer = if (tertiaryContainer.luminance() > 0.5f) darkContent else Color.White

    // error 系：M3 标准语义色（error 槽位仍可被各版式覆盖）
    val onError = if (dark) Color(0xFF690005) else Color.White
    val errorContainer = if (dark) Color(0xFF93000A) else Color(0xFFFFDAD6)
    val onErrorContainer = if (dark) Color(0xFFFFDAD6) else Color(0xFF410002)

    // 轮廓：边框随主题，不再是一成不变的紫灰
    val outline = lerp(sv, onSurface, 0.38f)
    val outlineVariant = sv

    // surfaceContainer 系列：决定 slider 未激活轨道 / FilterChip 未选中底 / 按钮底等
    val surfaceContainerLowest = if (dark) lerp(s, Color.Black, 0.08f) else s
    val surfaceContainerLow = lerp(s, sv, 0.30f)
    val surfaceContainer = lerp(s, sv, 0.50f)
    val surfaceContainerHigh = lerp(s, sv, 0.70f)
    val surfaceContainerHighest = lerp(s, sv, 0.88f)
    val surfaceDim = if (dark) lerp(s, Color.Black, 0.12f) else lerp(s, Color.Black, 0.04f)
    val surfaceBright = lerp(s, Color.White, 0.06f)

    // inverse 系：当前界面未直接使用，按 M3 惯例补全
    val inverseSurface = this.inverseSurface ?: if (dark) Color(0xFFE6E1E5) else Color(0xFF322F35)
    val inverseOnSurface = this.inverseOnSurface ?: if (dark) Color(0xFF322F35) else Color(0xFFF5EFF4)
    val inversePrimary = if (dark) lerp(primary, Color.Black, 0.35f) else lerp(primary, Color.White, 0.25f)

    return if (dark) darkColorScheme(
        primary = primary,
        onPrimary = onPrimary,
        primaryContainer = primaryContainer,
        onPrimaryContainer = onPrimaryContainer,
        inversePrimary = inversePrimary,
        secondary = secondary,
        onSecondary = onSecondary,
        secondaryContainer = secondaryContainer,
        onSecondaryContainer = onSecondaryContainer,
        tertiary = tertiary,
        onTertiary = onTertiary,
        tertiaryContainer = tertiaryContainer,
        onTertiaryContainer = onTertiaryContainer,
        error = error,
        onError = onError,
        errorContainer = errorContainer,
        onErrorContainer = onErrorContainer,
        background = background,
        onBackground = onSurface,
        surface = surface,
        onSurface = onSurface,
        surfaceVariant = surfaceVariant,
        onSurfaceVariant = onSurfaceV,
        surfaceTint = primary,
        inverseSurface = inverseSurface,
        inverseOnSurface = inverseOnSurface,
        outline = outline,
        outlineVariant = outlineVariant,
        scrim = Color.Black,
        surfaceBright = surfaceBright,
        surfaceDim = surfaceDim,
        surfaceContainer = surfaceContainer,
        surfaceContainerHigh = surfaceContainerHigh,
        surfaceContainerHighest = surfaceContainerHighest,
        surfaceContainerLow = surfaceContainerLow,
        surfaceContainerLowest = surfaceContainerLowest,
    ) else lightColorScheme(
        primary = primary,
        onPrimary = onPrimary,
        primaryContainer = primaryContainer,
        onPrimaryContainer = onPrimaryContainer,
        inversePrimary = inversePrimary,
        secondary = secondary,
        onSecondary = onSecondary,
        secondaryContainer = secondaryContainer,
        onSecondaryContainer = onSecondaryContainer,
        tertiary = tertiary,
        onTertiary = onTertiary,
        tertiaryContainer = tertiaryContainer,
        onTertiaryContainer = onTertiaryContainer,
        error = error,
        onError = onError,
        errorContainer = errorContainer,
        onErrorContainer = onErrorContainer,
        background = background,
        onBackground = onSurface,
        surface = surface,
        onSurface = onSurface,
        surfaceVariant = surfaceVariant,
        onSurfaceVariant = onSurfaceV,
        surfaceTint = primary,
        inverseSurface = inverseSurface,
        inverseOnSurface = inverseOnSurface,
        outline = outline,
        outlineVariant = outlineVariant,
        scrim = Color.Black,
        surfaceBright = surfaceBright,
        surfaceDim = surfaceDim,
        surfaceContainer = surfaceContainer,
        surfaceContainerHigh = surfaceContainerHigh,
        surfaceContainerHighest = surfaceContainerHighest,
        surfaceContainerLow = surfaceContainerLow,
        surfaceContainerLowest = surfaceContainerLowest,
    )
}

private fun palette(
    key: String,
    name: String,
    light: YingtiPaletteBase,
    dark: YingtiPaletteBase,
) = YingtiPalette(key, name, light.toScheme(dark = false), dark.toScheme(dark = true))

private val Wine = palette(
    key = "wine",
    name = "浆果红",
    light = YingtiPaletteBase(
        primary = Color(0xFF8E354A),
        onPrimary = Color.White,
        primaryContainer = Color(0xFFFFD9DF),
        onPrimaryContainer = Color(0xFF3A0715),
        secondary = Color(0xFF6E5860),
        background = Color(0xFFF8F5F2),
        surface = Color(0xFFFFFBF8),
        surfaceVariant = Color(0xFFF0E4E6),
    ),
    dark = YingtiPaletteBase(
        primary = Color(0xFFFFB1C2),
        onPrimary = Color(0xFF5F1228),
        primaryContainer = Color(0xFF76233A),
        onPrimaryContainer = Color(0xFFFFD9DF),
        secondary = Color(0xFFE2BDC5),
        background = Color(0xFF1C1416),
        surface = Color(0xFF241B1D),
        surfaceVariant = Color(0xFF514346),
        onSurfaceVariant = Color(0xFFD5C2C6),
    ),
)

private val Blue = palette(
    key = "blue",
    name = "雾霾蓝",
    light = YingtiPaletteBase(
        primary = Color(0xFF3D6FA8),
        onPrimary = Color.White,
        primaryContainer = Color(0xFFD3E4FA),
        onPrimaryContainer = Color(0xFF12355C),
        secondary = Color(0xFF5D6A7E),
        background = Color(0xFFF8F5F2),
        surface = Color(0xFFFFFBF8),
        surfaceVariant = Color(0xFFE3E8F2),
    ),
    dark = YingtiPaletteBase(
        primary = Color(0xFFA8C8F0),
        onPrimary = Color(0xFF17395F),
        primaryContainer = Color(0xFF33598A),
        onPrimaryContainer = Color(0xFFD3E4FA),
        secondary = Color(0xFFB8C5D9),
        background = Color(0xFF141A22),
        surface = Color(0xFF1B232D),
        surfaceVariant = Color(0xFF3A4553),
        onSurfaceVariant = Color(0xFFC6D2E0),
    ),
)

private val Green = palette(
    key = "green",
    name = "青松绿",
    light = YingtiPaletteBase(
        primary = Color(0xFF3E7A5C),
        onPrimary = Color.White,
        primaryContainer = Color(0xFFD0EADB),
        onPrimaryContainer = Color(0xFF0E3A26),
        secondary = Color(0xFF5C6F64),
        background = Color(0xFFF8F5F2),
        surface = Color(0xFFFFFBF8),
        surfaceVariant = Color(0xFFE1EBE4),
    ),
    dark = YingtiPaletteBase(
        primary = Color(0xFFA0D4B8),
        onPrimary = Color(0xFF0F3D28),
        primaryContainer = Color(0xFF2C5C43),
        onPrimaryContainer = Color(0xFFD0EADB),
        secondary = Color(0xFFB5C9BB),
        background = Color(0xFF121914),
        surface = Color(0xFF1A211C),
        surfaceVariant = Color(0xFF39463E),
        onSurfaceVariant = Color(0xFFC2D4C8),
    ),
)

private val Yellow = palette(
    key = "yellow",
    name = "琥珀黄",
    light = YingtiPaletteBase(
        primary = Color(0xFF9A7B2D),
        onPrimary = Color.White,
        primaryContainer = Color(0xFFF6E8C6),
        onPrimaryContainer = Color(0xFF4A3A0A),
        secondary = Color(0xFF72694E),
        background = Color(0xFFF8F5F2),
        surface = Color(0xFFFFFBF8),
        surfaceVariant = Color(0xFFEEE8D6),
    ),
    dark = YingtiPaletteBase(
        primary = Color(0xFFE2C980),
        onPrimary = Color(0xFF3E3109),
        primaryContainer = Color(0xFF6B5720),
        onPrimaryContainer = Color(0xFFF6E8C6),
        secondary = Color(0xFFC9BC9C),
        background = Color(0xFF1B1710),
        surface = Color(0xFF231F17),
        surfaceVariant = Color(0xFF45402F),
        onSurfaceVariant = Color(0xFFD8CDB4),
    ),
)

private val Purple = palette(
    key = "purple",
    name = "烟熏紫",
    light = YingtiPaletteBase(
        primary = Color(0xFF7A50A3),
        onPrimary = Color.White,
        primaryContainer = Color(0xFFEBDDF6),
        onPrimaryContainer = Color(0xFF3A1E5C),
        secondary = Color(0xFF6E6179),
        background = Color(0xFFF8F5F2),
        surface = Color(0xFFFFFBF8),
        surfaceVariant = Color(0xFFEAE2F0),
    ),
    dark = YingtiPaletteBase(
        primary = Color(0xFFD2B8EC),
        onPrimary = Color(0xFF3A1E5C),
        primaryContainer = Color(0xFF5F3B85),
        onPrimaryContainer = Color(0xFFEBDDF6),
        secondary = Color(0xFFCBBED8),
        background = Color(0xFF17131C),
        surface = Color(0xFF1E1924),
        surfaceVariant = Color(0xFF453E4E),
        onSurfaceVariant = Color(0xFFD4C7DE),
    ),
)

private val Gemini = palette(
    key = "gemini",
    name = "Gemini",
    light = YingtiPaletteBase(
        primary = Color(0xFFDE6B93),
        onPrimary = Color(0xFF33252B),
        primaryContainer = Color(0xFFFCE3EC),
        onPrimaryContainer = Color(0xFF5A1630),
        secondary = Color(0xFF6F9FD3),
        background = Color(0xFFFAF5F6),
        surface = Color(0xFFFFFFFF),
        surfaceVariant = Color(0xFFF7F0F3),
        onSurface = Color(0xFF33252B),
        onSurfaceVariant = Color(0xFF73616A),
        secondaryContainer = Color(0xFFE4EFFB),
        onSecondaryContainer = Color(0xFF23405F),
        onSecondary = Color(0xFF33252B),
    ),
    dark = YingtiPaletteBase(
        primary = Color(0xFFF59CB8),
        onPrimary = Color(0xFF45222F),
        primaryContainer = Color(0xFF45222F),
        onPrimaryContainer = Color(0xFFF6EDF0),
        secondary = Color(0xFF9CC3EF),
        background = Color(0xFF1B1618),
        surface = Color(0xFF262022),
        surfaceVariant = Color(0xFF382C32),
        onSurface = Color(0xFFF6EDF0),
        onSurfaceVariant = Color(0xFFD8BBC7),
        secondaryContainer = Color(0xFF223247),
        onSecondaryContainer = Color(0xFFCFE3FA),
    ),
)

private val DeepSeek = palette(
    key = "deepseek",
    name = "DeepSeek",
    light = YingtiPaletteBase(
        primary = Color(0xFF4561EA),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFE4E9FF),
        onPrimaryContainer = Color(0xFF223B9A),
        secondary = Color(0xFF4760B3),
        background = Color(0xFFF4F6FA),
        surface = Color(0xFFFFFFFF),
        surfaceVariant = Color(0xFFE7EBF3),
        onSurface = Color(0xFF1F2733),
        onSurfaceVariant = Color(0xFF4E5D73),
    ),
    dark = YingtiPaletteBase(
        primary = Color(0xFF9BABFF),
        onPrimary = Color(0xFF162467),
        primaryContainer = Color(0xFF293963),
        onPrimaryContainer = Color(0xFFDDE3FF),
        secondary = Color(0xFF9EAFE8),
        background = Color(0xFF1F2733),
        surface = Color(0xFF283241),
        surfaceVariant = Color(0xFF354255),
        onSurface = Color(0xFFEDF1F7),
        onSurfaceVariant = Color(0xFFBECADE),
        onSecondary = Color(0xFF162467),
    ),
)

private val ChatGPT = palette(
    key = "chatgpt",
    name = "ChatGPT",
    light = YingtiPaletteBase(
        darkContent = Color(0xFF111111),
        primary = Color(0xFF000000),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFE8E8E8),
        onPrimaryContainer = Color(0xFF111111),
        secondary = Color(0xFF595959),
        background = Color(0xFFFFFFFF),
        surface = Color(0xFFFFFFFF),
        surfaceVariant = Color(0xFFF0F0F0),
        onSurface = Color(0xFF111111),
        onSurfaceVariant = Color(0xFF595959),
        onSecondary = Color(0xFFFFFFFF),
        inverseSurface = Color(0xFF242424),
        inverseOnSurface = Color(0xFFF5F5F5),
    ),
    dark = YingtiPaletteBase(
        darkContent = Color(0xFF111111),
        primary = Color(0xFFFFFFFF),
        onPrimary = Color(0xFF000000),
        primaryContainer = Color(0xFF303030),
        onPrimaryContainer = Color(0xFFFFFFFF),
        secondary = Color(0xFFBDBDBD),
        background = Color(0xFF000000),
        surface = Color(0xFF171717),
        surfaceVariant = Color(0xFF303030),
        onSurface = Color(0xFFFFFFFF),
        onSurfaceVariant = Color(0xFFBDBDBD),
        inverseSurface = Color(0xFFEEEEEE),
        inverseOnSurface = Color(0xFF242424),
    ),
)

private val Claude = palette(
    key = "claude",
    name = "Claude",
    light = YingtiPaletteBase(
        primary = Color(0xFFD97757),
        onPrimary = Color(0xFF30180F),
        primaryContainer = Color(0xFFF7E0D5),
        onPrimaryContainer = Color(0xFF5A2D1D),
        secondary = Color(0xFF88634D),
        background = Color(0xFFFAF8F3),
        surface = Color(0xFFFFFDFA),
        surfaceVariant = Color(0xFFF0E8DF),
        onSurface = Color(0xFF302B27),
        onSurfaceVariant = Color(0xFF6C5F55),
    ),
    dark = YingtiPaletteBase(
        primary = Color(0xFFEDAD91),
        onPrimary = Color(0xFF482518),
        primaryContainer = Color(0xFF5B382B),
        onPrimaryContainer = Color(0xFFFFE5D7),
        secondary = Color(0xFFD9B9A3),
        background = Color(0xFF211C19),
        surface = Color(0xFF2B2521),
        surfaceVariant = Color(0xFF41362E),
        onSurface = Color(0xFFF3EBE5),
        onSurfaceVariant = Color(0xFFD2BFB0),
    ),
)

val YingtiPalettes = listOf(Wine, Blue, Green, Yellow, Purple, Gemini, DeepSeek, ChatGPT, Claude)

internal val LocalYingtiPaletteKey = androidx.compose.runtime.staticCompositionLocalOf { "wine" }

@Composable
fun YingtiTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    paletteKey: String = "wine",
    content: @Composable () -> Unit,
) {
    val palette = YingtiPalettes.firstOrNull { it.key == paletteKey } ?: Wine
    androidx.compose.runtime.CompositionLocalProvider(LocalYingtiPaletteKey provides palette.key) {
        MaterialTheme(
            colorScheme = if (darkTheme) palette.dark else palette.light,
            typography = Typography(),
            content = content,
        )
    }
}
