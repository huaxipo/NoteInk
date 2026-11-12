package com.noteink.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val NoteColors = lightColorScheme(
    primary = Color(0xFF2F5FA8),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDCE6F5),
    onPrimaryContainer = Color(0xFF12283F),
    secondary = Color(0xFF6B7280),
    background = Color(0xFFF6F4EF),
    onBackground = Color(0xFF1C1C1E),
    surface = Color(0xFFFFFEFB),
    onSurface = Color(0xFF1C1C1E),
    surfaceVariant = Color(0xFFEDEAE3),
    onSurfaceVariant = Color(0xFF5B5B5E),
    outline = Color(0xFFC9C5BB),
    error = Color(0xFFB3261E)
)

@Composable
fun NoteInkTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = NoteColors,
        typography = Typography(),
        content = content
    )
}
