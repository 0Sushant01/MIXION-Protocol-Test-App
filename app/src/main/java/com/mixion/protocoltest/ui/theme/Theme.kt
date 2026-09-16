package com.mixion.protocoltest.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColorScheme = lightColorScheme(
    primary = PrimaryBlue,
    onPrimary = Color.White,
    primaryContainer = PrimaryBlueLight.copy(alpha = 0.15f),
    onPrimaryContainer = PrimaryBlue,
    secondary = LightSlate,
    onSecondary = Color.White,
    background = NeutralBg,
    onBackground = TextPrimary,
    surface = CardBg,
    onSurface = TextPrimary,
    surfaceVariant = CodeBg,
    onSurfaceVariant = TextSecondary,
    outline = SlateBorder.copy(alpha = 0.3f),
    error = FailRed,
    onError = Color.White
)

@Composable
fun MixionProtocolTestTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LightColorScheme,
        content = content
    )
}
