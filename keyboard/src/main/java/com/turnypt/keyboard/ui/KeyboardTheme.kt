package com.turnypt.keyboard.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Compact Material palette local to the IME (the keyboard module keeps its
// own theme so it never depends on the app module).
private val ImeDark = darkColorScheme(
    primary = Color(0xFF5B8DEF),
    onPrimary = Color(0xFFFFFFFF),
    background = Color(0xFF16161C),
    onBackground = Color(0xFFE4E4EC),
    surface = Color(0xFF1C1C24),
    onSurface = Color(0xFFE4E4EC),
    surfaceVariant = Color(0xFF2A2A34),
    onSurfaceVariant = Color(0xFFE4E4EC),
    outline = Color(0xFF3A3A46),
    error = Color(0xFFE25B5B),
)

private val ImeLight = lightColorScheme(
    primary = Color(0xFF2E4A80),
    onPrimary = Color(0xFFFFFFFF),
    background = Color(0xFFF2F3F8),
    onBackground = Color(0xFF1A1A22),
    surface = Color(0xFFEDEFF5),
    onSurface = Color(0xFF1A1A22),
    surfaceVariant = Color(0xFFDDE1EA),
    onSurfaceVariant = Color(0xFF1A1A22),
    outline = Color(0xFFC6CAD6),
    error = Color(0xFFB23B3B),
)

@Composable
fun KeyboardTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) ImeDark else ImeLight,
        content = content,
    )
}
