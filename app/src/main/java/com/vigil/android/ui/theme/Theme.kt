package com.vigil.android.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// The field screens share a light, high-contrast palette with the XML patrol activities.
private val VigilColors = lightColorScheme(
    primary = Color(0xFF006B5E), onPrimary = Color.White,
    primaryContainer = Color(0xFFD3F3E8), onPrimaryContainer = Color(0xFF083C32),
    secondary = Color(0xFF35675D), secondaryContainer = Color(0xFFDCF1E8),
    onSecondaryContainer = Color(0xFF143E35), background = AppBackground,
    surface = CardBackground, surfaceContainer = Color(0xFFF0F4F0),
    outlineVariant = Color(0xFFCBD9D2)
)

@Composable
fun VigilTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = VigilColors, typography = Typography, content = content)
}
