package com.andrewtheguy.duocb.ui

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

/** The app icon's blue, used as the seed where dynamic color is unavailable. */
private val Blue = Color(0xFF2369DC)
private val BlueDark = Color(0xFF1746C8)

private val LightColors = lightColorScheme(
    primary = BlueDark,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD9E6FD),
    onPrimaryContainer = Color(0xFF001A44),
    secondary = Blue,
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA9C7FF),
    onPrimary = Color(0xFF00306F),
    primaryContainer = BlueDark,
    onPrimaryContainer = Color(0xFFD9E6FD),
    secondary = Blue,
)

@Composable
fun DuocbTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        dark -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colors, content = content)
}
