package com.mochiwall.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object MochiColors {
    val ObsidianBg = Color(0xFF111215)
    val PanelSurface = Color(0xFF1A1C22)
    val PanelBorder = Color(0xFF2A2D37)
    val MochiCream = Color(0xFFF4EFE6)
    val SoftCoral = Color(0xFFFF6B6B)
}

private val MochiDarkColorScheme = darkColorScheme(
    primary = MochiColors.SoftCoral,
    onPrimary = MochiColors.ObsidianBg,
    secondary = MochiColors.MochiCream,
    onSecondary = MochiColors.ObsidianBg,
    background = MochiColors.ObsidianBg,
    onBackground = MochiColors.MochiCream,
    surface = MochiColors.PanelSurface,
    onSurface = MochiColors.MochiCream,
    surfaceVariant = MochiColors.PanelSurface,
    onSurfaceVariant = MochiColors.MochiCream,
    outline = MochiColors.PanelBorder,
    outlineVariant = MochiColors.PanelBorder,
)

@Composable
fun StudioTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = MochiDarkColorScheme,
        content = content,
    )
}
