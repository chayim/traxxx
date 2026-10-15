package com.kirshen.traxxx.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val Dark = darkColorScheme(
    primary = Color(0xFF4F8CFF),
    background = Color(0xFF0F1115),
    surface = Color(0xFF171A21),
    error = Color(0xFFE05252),
)
private val Light = lightColorScheme(
    primary = Color(0xFF2F6BE5),
    background = Color(0xFFF6F7FB),
    surface = Color(0xFFFFFFFF),
    error = Color(0xFFB83030),
)

@Composable
fun TraxxxTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val colors = if (dark) Dark else Light
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colors.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !dark
        }
    }
    MaterialTheme(colorScheme = colors, content = content)
}
