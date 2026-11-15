package com.ayan.agent.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

// PRD v0.3: Material 3, white base + purple. Dark mode is intentionally NOT used.
private val LightColors = lightColorScheme(
    primary = PurplePrimary,
    onPrimary = WhiteBase,
    primaryContainer = PurpleContainer,
    onPrimaryContainer = PurpleDeep,
    secondary = PurpleLight,
    onSecondary = WhiteBase,
    background = WhiteBase,
    onBackground = OnSurfaceDark,
    surface = SurfaceWhite,
    onSurface = OnSurfaceDark,
    error = ErrorRed
)

@Composable
fun AyanAgentTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LightColors,
        typography = Typography,
        content = content
    )
}
