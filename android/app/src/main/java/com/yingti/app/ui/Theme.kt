package com.yingti.app.ui

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Colors = lightColorScheme(
    primary = Color(0xFF8E354A),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFD9DF),
    onPrimaryContainer = Color(0xFF3A0715),
    secondary = Color(0xFF6E5860),
    background = Color(0xFFF8F5F2),
    surface = Color(0xFFFFFBF8),
    surfaceVariant = Color(0xFFF0E4E6),
    error = Color(0xFFBA1A1A),
)

@Composable fun YingtiTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Colors, typography = Typography(), content = content)
}
