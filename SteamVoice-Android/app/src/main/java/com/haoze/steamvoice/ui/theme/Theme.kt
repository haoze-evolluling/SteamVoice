package com.haoze.steamvoice.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// Android 12+ 跟随系统“莫奈取色”（Material You 壁纸动态配色）；
// 低版本回退到与桌面端一致的 SteamVoice 品牌配色。
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
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
