package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = MintPrimary,
    onPrimary = Color(0xFF070B12),
    secondary = MintLight,
    onSecondary = Color(0xFF070B12),
    tertiary = AmberAccent,
    background = SlateDark,
    surface = SlateCard,
    onBackground = SlateTextDark,
    onSurface = SlateTextDark,
    error = CoralAccent,
    outline = SlateBorder
)

// We enforce a unified slate-dark aesthetic for consistent contrast and visual focus
private val EnforcedThemeColorScheme = DarkColorScheme

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = true, // Force dark mode for bold corporate/utility productivity look
    dynamicColor: Boolean = false, // Use our handcrafted palette instead of generic device colors
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = EnforcedThemeColorScheme,
        typography = Typography,
        content = content
    )
}
