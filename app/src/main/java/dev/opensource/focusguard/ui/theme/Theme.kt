package dev.opensource.focusguard.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Stayfree-inspired visual language: near-black navy background, dark slate
 * cards with 20dp radii, lavender accent. Light mode mirrors the same accent
 * on a soft off-white.
 */
object FgColors {
    val Lavender = Color(0xFFC3A9F7)
    val LavenderDeep = Color(0xFF9B8AFB)
    val CardDark = Color(0xFF26262F)
    val CardDarkElevated = Color(0xFF2E2E39)
    val BackgroundDark = Color(0xFF0E0E16)
    val TrackDark = Color(0xFF3A3A47)

    // Chart palette (element donuts)
    val Chart = listOf(
        Color(0xFF6FB3F2), Color(0xFFF2A65A), Color(0xFF7ED957),
        Color(0xFFF25F5C), Color(0xFFC3A9F7), Color(0xFF7FD1B9),
        Color(0xFFE8D05F), Color(0xFF9B8AFB), Color(0xFF8A93A6)
    )
    fun chartColor(index: Int): Color = Chart[index % Chart.size]

    // In-app icon accent choices: [main, deep] pairs, index-matched to the
    // launcher icon themes so both pickers feel like one system.
    val AccentChoices = listOf(
        Lavender to LavenderDeep,
        Color(0xFF6FB3F2) to Color(0xFF4A8FE0),
        Color(0xFF7FD1B9) to Color(0xFF5BB89E),
        Color(0xFFF2A65A) to Color(0xFFE08D3E),
        Color(0xFFF25F5C) to Color(0xFFD94A4A),
        Color(0xFFF2F1F7) to Color(0xFFB8B5C4)
    )
    val AccentNames = listOf("Lavender", "Sky", "Mint", "Amber", "Rose", "Mono")

    fun accentColor(index: Int): Color = AccentChoices[index.coerceIn(0, AccentChoices.size - 1)].first
    fun accentDeep(index: Int): Color = AccentChoices[index.coerceIn(0, AccentChoices.size - 1)].second
}

private val DarkColors = darkColorScheme(
    primary = FgColors.Lavender,
    onPrimary = Color(0xFF17131F),
    primaryContainer = FgColors.CardDarkElevated,
    onPrimaryContainer = FgColors.Lavender,
    secondary = FgColors.LavenderDeep,
    onSecondary = Color(0xFF17131F),
    background = FgColors.BackgroundDark,
    onBackground = Color(0xFFF2F1F7),
    surface = FgColors.CardDark,
    onSurface = Color(0xFFF2F1F7),
    surfaceVariant = FgColors.CardDarkElevated,
    onSurfaceVariant = Color(0xFF9D9DAA),
    outline = FgColors.TrackDark,
    outlineVariant = Color(0xFF2A2A34),
    error = Color(0xFFFF6B7A),
    onError = Color(0xFF1A0B0E)
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF5B3FC4),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFEDE6FF),
    onPrimaryContainer = Color(0xFF2B1A66),
    secondary = Color(0xFF7361D8),
    onSecondary = Color.White,
    background = Color(0xFFF7F6FB),
    onBackground = Color(0xFF16151C),
    surface = Color.White,
    onSurface = Color(0xFF16151C),
    surfaceVariant = Color(0xFFECEAF3),
    onSurfaceVariant = Color(0xFF5C5B66),
    outline = Color(0xFFD9D7E3),
    outlineVariant = Color(0xFFECEAF3),
    error = Color(0xFFB3261E),
    onError = Color.White
)

@Composable
fun FocusGuardTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = Typography(),
        shapes = Shapes(
            extraSmall = RoundedCornerShape(10.dp),
            small = RoundedCornerShape(14.dp),
            medium = RoundedCornerShape(20.dp),
            large = RoundedCornerShape(24.dp),
            extraLarge = RoundedCornerShape(28.dp)
        ),
        content = content
    )
}
