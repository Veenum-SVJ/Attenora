package dev.opensource.focusguard.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * "Quiet Instrument" — the v1.2 visual language.
 *
 * Structure follows iOS Settings: true-black page background, grouped raised
 * surfaces, hairline separators, large left-aligned titles, generous spacing,
 * and exactly one accent. No shadows, no gradients, no glass — depth comes
 * from the surface steps alone.
 *
 * The palette is grounded in the OLED dark-mode pattern (true #000000 base,
 * which also costs the least to draw on an OLED panel) and a single restrained
 * lavender tint. Status colour is *semantic* rather than decorative: see
 * [FgSemanticColors].
 */
object FgColors {
    val Lavender = Color(0xFFC3A9F7)
    val LavenderDeep = Color(0xFF9B8AFB)

    // Surfaces: black page, grouped card, raised control.
    val BackgroundDark = Color(0xFF000000)
    val CardDark = Color(0xFF1C1C1E)
    val CardDarkElevated = Color(0xFF2C2C2E)
    val TrackDark = Color(0xFF3A3A3D)

    // Labels.
    val LabelDark = Color(0xFFF5F5F3)
    val LabelSecondaryDark = Color(0xFF9A9AA0)

    // Chart series — desaturated so six hues can coexist on black without
    // reading as a rainbow, and each still distinguishable from its neighbour.
    val Chart = listOf(
        Color(0xFF8E7CE8), Color(0xFF4FA98A), Color(0xFFC98B5E),
        Color(0xFF5E93C4), Color(0xFFB56A85), Color(0xFF6FA8A4),
        Color(0xFFA8A05E), Color(0xFF8A8FA6)
    )
    fun chartColor(index: Int): Color = Chart[index % Chart.size]

    // In-app accent choices for the brand tint. Six tonally-matched options so
    // whichever one is picked still sits correctly on true black. This only
    // re-tints the brand accent — success/warning/danger stay fixed (see
    // [FgSemanticColors]) because a user-pickable "blocked" colour would be an
    // accessibility hole.
    val AccentChoices = listOf(
        Lavender to Color(0xFF9B8AFB),
        Color(0xFF7FB2E8) to Color(0xFF5A8FC7),
        Color(0xFF6FBF9B) to Color(0xFF4E9C7B),
        Color(0xFFD9A76A) to Color(0xFFB8874C),
        Color(0xFFD98A93) to Color(0xFFB66C76),
        Color(0xFFD8D6DE) to Color(0xFFA9A6B4)
    )
    val AccentNames = listOf("Lavender", "Sky", "Mint", "Amber", "Rose", "Mono")

    fun accentColor(index: Int): Color = AccentChoices[index.coerceIn(0, AccentChoices.size - 1)].first
    fun accentDeep(index: Int): Color = AccentChoices[index.coerceIn(0, AccentChoices.size - 1)].second
}

/**
 * Status colour, kept out of the user-selectable accent so that "blocked"
 * always looks like "blocked".
 */
@Immutable
data class FgSemanticColors(
    val success: Color,
    val warning: Color,
    val danger: Color
)

private val DarkSemantic = FgSemanticColors(
    success = Color(0xFF6FBF8F),
    warning = Color(0xFFE0A75E),
    danger = Color(0xFFE5726B)
)

private val LightSemantic = FgSemanticColors(
    success = Color(0xFF2E8B57),
    warning = Color(0xFFB4762A),
    danger = Color(0xFFC0392B)
)

val LocalFgSemantic = staticCompositionLocalOf { DarkSemantic }

/** `MaterialTheme.fg.success` — status colour that works in either theme. */
val MaterialTheme.fg: FgSemanticColors
    @Composable
    @ReadOnlyComposable
    get() = LocalFgSemantic.current

private val DarkColors = darkColorScheme(
    primary = FgColors.Lavender,
    onPrimary = Color(0xFF1A1420),
    primaryContainer = FgColors.CardDarkElevated,
    onPrimaryContainer = FgColors.Lavender,
    secondary = FgColors.LavenderDeep,
    onSecondary = Color(0xFF1A1420),
    background = FgColors.BackgroundDark,
    onBackground = FgColors.LabelDark,
    surface = FgColors.CardDark,
    onSurface = FgColors.LabelDark,
    surfaceVariant = FgColors.CardDarkElevated,
    onSurfaceVariant = FgColors.LabelSecondaryDark,
    outline = Color(0xA6545458),
    outlineVariant = Color(0x3D545458),
    error = Color(0xFFE5726B),
    onError = Color(0xFF2A0F0D)
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF5B3FC4),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFEDE6FF),
    onPrimaryContainer = Color(0xFF2B1A66),
    secondary = Color(0xFF7361D8),
    onSecondary = Color.White,
    background = Color(0xFFF2F2F7),
    onBackground = Color(0xFF1C1C1E),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1C1C1E),
    surfaceVariant = Color(0xFFE5E5EA),
    onSurfaceVariant = Color(0xFF6C6C70),
    outline = Color(0x4A3C3C43),
    outlineVariant = Color(0x293C3C43),
    error = Color(0xFFC0392B),
    onError = Color.White
)

/**
 * Type scale. Slots are retuned rather than renamed so every existing call
 * site inherits the new hierarchy without churn: 32/700 page titles, 22/600
 * section titles, 17/400 rows, 15/400 secondary, 13/400 captions.
 *
 * Large text carries slightly negative tracking, the way display sizes do on
 * iOS — it is the cheapest way to stop big headings looking loose.
 */
private val FgTypography = Typography().let { base ->
    base.copy(
        headlineLarge = base.headlineLarge.copy(
            fontSize = 34.sp, lineHeight = 41.sp, fontWeight = FontWeight.Bold,
            letterSpacing = (-0.6).sp, fontFamily = FontFamily.Default
        ),
        headlineMedium = base.headlineMedium.copy(
            fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold,
            letterSpacing = (-0.4).sp, fontFamily = FontFamily.Default
        ),
        headlineSmall = base.headlineSmall.copy(
            fontSize = 32.sp, lineHeight = 39.sp, fontWeight = FontWeight.Bold,
            letterSpacing = (-0.5).sp, fontFamily = FontFamily.Default
        ),
        titleLarge = base.titleLarge.copy(
            fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold,
            letterSpacing = (-0.2).sp, fontFamily = FontFamily.Default
        ),
        titleMedium = base.titleMedium.copy(
            fontSize = 17.sp, lineHeight = 23.sp, fontWeight = FontWeight.Medium,
            letterSpacing = (-0.1).sp, fontFamily = FontFamily.Default
        ),
        titleSmall = base.titleSmall.copy(
            fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium,
            fontFamily = FontFamily.Default
        ),
        bodyLarge = base.bodyLarge.copy(
            fontSize = 17.sp, lineHeight = 23.sp, fontWeight = FontWeight.Normal,
            fontFamily = FontFamily.Default
        ),
        bodyMedium = base.bodyMedium.copy(
            fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Normal,
            fontFamily = FontFamily.Default
        ),
        bodySmall = base.bodySmall.copy(
            fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Normal,
            fontFamily = FontFamily.Default
        ),
        labelLarge = base.labelLarge.copy(
            fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium,
            fontFamily = FontFamily.Default
        ),
        labelMedium = base.labelMedium.copy(
            fontSize = 13.sp, lineHeight = 17.sp, fontWeight = FontWeight.Medium,
            fontFamily = FontFamily.Default
        ),
        labelSmall = base.labelSmall.copy(
            fontSize = 11.sp, lineHeight = 15.sp, fontWeight = FontWeight.Medium,
            fontFamily = FontFamily.Default
        )
    )
}

/** Tighter radii than the old 20/24 card kit — grouped lists, not tiles. */
private val FgShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(22.dp)
)

@Composable
fun FocusGuardTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val semantic = if (darkTheme) DarkSemantic else LightSemantic
    CompositionLocalProvider(LocalFgSemantic provides semantic) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            typography = FgTypography,
            shapes = FgShapes,
            content = content
        )
    }
}

/**
 * Numbers that line up in columns. Duration, percentage and count text should
 * all go through this so digits don't jitter as values change.
 */
fun fgTabular(style: TextStyle): TextStyle =
    style.copy(fontFeatureSettings = "tnum")
