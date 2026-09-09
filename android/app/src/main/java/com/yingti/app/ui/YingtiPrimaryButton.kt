package com.yingti.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/** Gemini 的主操作使用粉蓝渐变；禁用态与其余版式沿用 Material 行为。急停不使用此组件。 */
@Composable
fun YingtiPrimaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    content: @Composable RowScope.() -> Unit,
) {
    val gradient = LocalYingtiPaletteKey.current == "gemini" && enabled
    val scheme = MaterialTheme.colorScheme
    val shape = ButtonDefaults.shape
    Button(
        onClick = onClick,
        modifier = if (gradient) modifier.background(
            Brush.linearGradient(listOf(scheme.primary, scheme.secondary)), shape,
        ) else modifier,
        enabled = enabled,
        shape = shape,
        colors = if (gradient) ButtonDefaults.buttonColors(
            containerColor = Color.Transparent,
            contentColor = scheme.onPrimary,
        ) else ButtonDefaults.buttonColors(),
        contentPadding = contentPadding,
        content = content,
    )
}
