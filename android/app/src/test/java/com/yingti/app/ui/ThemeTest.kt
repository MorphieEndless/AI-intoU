package com.yingti.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.*
import org.junit.Test

class ThemeTest {
    @Test fun themeDisplayNamesAreUpdated() {
        assertEquals(
            listOf("浆果红", "雾霾蓝", "青松绿", "蜂蜜黄", "烟熏紫", "Gemini", "DeepSeek", "ChatGPT", "Claude"),
            YingtiPalettes.map { it.name }
        )
    }

    @Test fun paletteKeysStayCompatibleAndUnique() {
        assertEquals(listOf("wine", "blue", "green", "yellow", "purple", "gemini", "deepseek", "chatgpt", "claude"), YingtiPalettes.map { it.key })
    }

    @Test fun originalPalettesAreUnchanged() {
        // 前五套的基础色与主源逐套比对：Yellow 已按需求改为蜂蜜黄，其余保持原值。
        listOf(Wine, Blue, Green, Yellow, Purple).zip(YingtiPalettes).forEach { (before, after) ->
            assertEquals(before.light.primary, after.light.primary)
            assertEquals(before.dark.primary, after.dark.primary)
            assertEquals(before.light.primaryContainer, after.light.primaryContainer)
            assertEquals(before.dark.primaryContainer, after.dark.primaryContainer)
            assertEquals(before.light.surface, after.light.surface)
            assertEquals(before.dark.surface, after.dark.surface)
        }
    }

    @Test fun newPaletteTextHasReadableContrast() {
        // 覆盖 Gemini 及其后各套，外加本轮重配色的蜂蜜黄。
        YingtiPalettes.drop(3).forEach { palette ->
            listOf(palette.light, palette.dark).forEach { s ->
                listOf(s.onSurface to s.surface, s.onSurfaceVariant to s.surfaceVariant,
                    s.onPrimary to s.primary, s.onPrimaryContainer to s.primaryContainer,
                    s.onSecondary to s.secondary, s.onSecondaryContainer to s.secondaryContainer).forEach { (fg, bg) ->
                    assertTrue("${palette.key} text contrast below 4.5", contrast(fg, bg) >= 4.5f)
                }
            }
        }
    }

    @Test fun chatGptContainersStayNeutral() {
        val palette = YingtiPalettes.first { it.key == "chatgpt" }
        listOf(palette.light, palette.dark).forEach { s ->
            listOf(s.primary, s.secondary, s.tertiary, s.background, s.surface,
                s.primaryContainer, s.secondaryContainer, s.tertiaryContainer,
                s.surfaceContainer, s.surfaceContainerHigh, s.surfaceContainerHighest,
                s.onSurface, s.onSurfaceVariant, s.onSecondary, s.onSecondaryContainer,
                s.onTertiary, s.onTertiaryContainer, s.inverseSurface, s.inverseOnSurface).forEach { color ->
                assertEquals(color.red, color.green, 0.0001f)
                assertEquals(color.green, color.blue, 0.0001f)
            }
        }
    }

    @Test fun geminiUsesIconPinkAndBlue() {
        val p = YingtiPalettes.first { it.key == "gemini" }
        assertEquals(Color(0xFFD9659B), p.light.primary)
        assertEquals(Color(0xFF6F9FD3), p.light.secondary)
        assertEquals(Color(0xFFFAF5F6), p.light.background)
        assertEquals(Color(0xFFEE9FC4), p.dark.primary)
        assertEquals(Color(0xFF9CC3EF), p.dark.secondary)
    }

    @Test fun sakuraPetalsStayDistinctAcrossEveryPalette() {
        // 每一套的亮/暗主色都应有五个互不相同的花瓣色，纯黑白版式也不例外。
        YingtiPalettes.forEach { p ->
            listOf(p.light.primary, p.dark.primary).forEach { primary ->
                val petals = yingtiSakuraPetals(primary)
                assertEquals(5, petals.size)
                assertEquals("${p.key} 五瓣应各有深浅", 5, petals.toSet().size)
            }
        }
    }

    @Test fun sakuraPetalsKeepPrimaryAsMidTone() {
        // 常规彩色主色下，中间那瓣即主题主色本身。
        listOf(Color(0xFF8E354A), Color(0xFF3D6FA8), Color(0xFFEAB308), Color(0xFFD9659B)).forEach { primary ->
            assertEquals(primary, yingtiSakuraPetals(primary)[2])
        }
    }

    @Test fun sakuraCoreStandsOutFromPrimary() {
        // 花心若与主色明度接近，在图形里会看不见；纯黑与纯白主色都要成立。
        listOf(
            Color(0xFF8E354A), Color(0xFFD9659B), Color(0xFFEAB308),
            Color(0xFF000000), Color(0xFFFFFFFF),
        ).forEach { primary ->
            listOf(false, true).forEach { dark ->
                val core = yingtiSakuraCore(primary, dark)
                assertTrue(
                    "$primary dark=$dark 花心与主色明度差过小",
                    kotlin.math.abs(core.luminance() - primary.luminance()) > 0.05f,
                )
            }
        }
    }

    private fun contrast(a: Color, b: Color): Float {
        val x = a.luminance()
        val y = b.luminance()
        return (maxOf(x, y) + 0.05f) / (minOf(x, y) + 0.05f)
    }

    private fun palette(key: String, name: String, light: YingtiPaletteBase, dark: YingtiPaletteBase) =
        YingtiPalette(key, name, light.toScheme(false), dark.toScheme(true))

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
        name = "蜂蜜黄",
        light = YingtiPaletteBase(
            primary = Color(0xFFEAB308),
            onPrimary = Color(0xFF4A3806),
            primaryContainer = Color(0xFFFEF0B3),
            onPrimaryContainer = Color(0xFF4A3A0A),
            secondary = Color(0xFF72694E),
            background = Color(0xFFF8F5F2),
            surface = Color(0xFFFFFBF8),
            surfaceVariant = Color(0xFFEEE8D6),
        ),
        dark = YingtiPaletteBase(
            primary = Color(0xFFF0CE60),
            onPrimary = Color(0xFF3E3109),
            primaryContainer = Color(0xFF7A6425),
            onPrimaryContainer = Color(0xFFFEF0B3),
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
}
