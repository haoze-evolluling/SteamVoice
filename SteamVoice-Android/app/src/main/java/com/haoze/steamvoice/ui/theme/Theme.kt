package com.haoze.steamvoice.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// 与桌面端一致的 SteamVoice 品牌配色；关闭动态取色以保证双端视觉统一。
private val DarkColorScheme = darkColorScheme(
    primary = SteamGreenLight,
    onPrimary = InkDark,
    primaryContainer = SteamGreen,
    onPrimaryContainer = SteamGreenLight,
    secondary = SteamAmber,
    onSecondary = InkDark,
    tertiary = SteamAmber,
    background = InkDark,
    onBackground = Color(0xFFE8EDF0),
    surface = InkDark,
    onSurface = Color(0xFFE8EDF0),
    surfaceVariant = InkSurface,
    onSurfaceVariant = Color(0xFFB6C1C7),
    outline = Color(0xFF3D4A51),
)

private val LightColorScheme = lightColorScheme(
    primary = SteamGreen,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB8E8D2),
    onPrimaryContainer = SteamGreen,
    secondary = Color(0xFF7A6220),
    onSecondary = Color.White,
    tertiary = Color(0xFF7A6220),
    background = MistSurface,
    onBackground = InkDark,
    surface = Color.White,
    onSurface = InkDark,
    surfaceVariant = Color(0xFFE8ECEE),
    onSurfaceVariant = SlateMuted,
    outline = Color(0xFFB8C0C6),
)

@Composable
fun SteamVoiceTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
        typography = Typography,
        content = content
    )
}
