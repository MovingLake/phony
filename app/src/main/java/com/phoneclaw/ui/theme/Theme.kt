package com.phoneclaw.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val PhoneclawDark = darkColorScheme(
    primary = Color(0xFF9B81E8),
    onPrimary = Color(0xFF1C0060),
    primaryContainer = Color(0xFF4527A0),
    onPrimaryContainer = Color(0xFFEDDEFF),
    secondary = Color(0xFFCBBEF0),
    onSecondary = Color(0xFF322B4B),
    background = Color(0xFF0D0D0F),
    onBackground = Color(0xFFE6E1E5),
    surface = Color(0xFF1C1B1F),
    onSurface = Color(0xFFE6E1E5),
    surfaceVariant = Color(0xFF2B2833),
    onSurfaceVariant = Color(0xFFCAC4D0),
    error = Color(0xFFCF6679),
    outline = Color(0xFF4A4458),
)

@Composable
fun PhoneclawTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = PhoneclawDark,
        content = content
    )
}
