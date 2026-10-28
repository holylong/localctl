package com.minrray.voicectl.ui.theme

import android.app.Activity
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

private val DarkColorScheme = darkColorScheme(
    primary = GptButton,
    secondary = GptSurface,
    tertiary = GptTextSecondary,
    background = GptBackground,
    surface = GptSurface,
    error = GptError,
    onPrimary = GptTextPrimary,
    onSecondary = GptTextPrimary,
    onTertiary = GptTextPrimary,
    onBackground = GptTextPrimary,
    onSurface = GptTextPrimary,
    onError = GptTextPrimary
)

// 保持浅色主题，但使用ChatGPT配色的浅色版本
private val LightColorScheme = lightColorScheme(
    primary = GptButton,
    secondary = GptSurface,
    tertiary = GptTextSecondary,
    background = Color.White,
    surface = Color.White,
    error = GptError,
    onPrimary = Color.White,
    onSecondary = Color.Black,
    onTertiary = Color.Black,
    onBackground = Color.Black,
    onSurface = Color.Black,
    onError = Color.White
)

@Composable
fun VoicectlTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Dynamic color is available on Android 12+
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