package dev.opensource.attenora.ui.theme

import android.app.Activity
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
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

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
    // The Attenora brand colours (palette: Ivory, Mist, Teal, Slate).
    val AttenoraIvory = Color(0xFFF9FAF8)
    val AttenoraMist = Color(0xFFD6E2DD)
    val AttenoraTeal = Color(0xFF5FA7A1)
    val AttenoraSlate = Color(0xFF2F3E44)

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
    // The brand teal leads.
    val Chart = listOf(
        Color(0xFF5FA7A1), Color(0xFF4FA98A), Color(0xFFC98B5E),
        Color(0xFF5E93C4), Color(0xFFB56A85), Color(0xFF6FA8A4),
        Color(0xFFA8A05E), Color(0xFF8A8FA6)
    )
    fun chartColor(index: Int): Color = Chart[index % Chart.size]

    // In-app accent choices for the brand tint. Six options drawn from the
    // Attenora palette so whichever one is picked still sits correctly on
    // true black. This only re-tints the brand accent — success/warning/danger
    // stay fixed (see [FgSemanticColors]) because a user-pickable "blocked"
    // colour would be an accessibility hole. Each pair is (on-black tint,
    // on-white deep tint for the light theme).
    val AccentChoices = listOf(
        AttenoraTeal to Color(0xFF3E827D),
        AttenoraMist to Color(0xFF8FAFA6),
        Color(0xFF7A939D) to AttenoraSlate,
        AttenoraIvory to Color(0xFF6B6E68),
        Color(0xFF6FBF9B) to Color(0xFF4E9C7B),
        Color(0xFFD8D6DE) to Color(0xFFA9A6B4)
    )
    val AccentNames = listOf("Teal", "Mist", "Slate", "Ivory", "Mint", "Mono")

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
    primary = FgColors.AttenoraTeal,
    onPrimary = Color(0xFF0F2926),
    primaryContainer = FgColors.CardDarkElevated,
    onPrimaryContainer = FgColors.AttenoraTeal,
    secondary = Color(0xFF3E827D),
    onSecondary = Color(0xFF0F2926),
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
    primary = Color(0xFF3E827D),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE3EEEC),
    onPrimaryContainer = Color(0xFF1F4B47),
    secondary = Color(0xFF35706B),
    onSecondary = Color.White,
    background = FgColors.AttenoraIvory,
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
fun AttenoraTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    accentIndex: Int = 0,
    content: @Composable () -> Unit
) {
    val semantic = if (darkTheme) DarkSemantic else LightSemantic

    // The accent picked in Settings > Theme is the app's ONE brand colour, so it
    // has to reach MaterialTheme.primary. It previously stopped at
    // LocalAccentColor, which only the nav bar and onboarding read — so choosing
    // Mint turned the bottom bar green while every chip, button and value stayed
    // lavender. Dark surfaces take the bright variant, light surfaces the deep
    // one, which is the same pairing the launcher-icon previews use.
    val accent = if (darkTheme) {
        FgColors.accentColor(accentIndex)
    } else {
        FgColors.accentDeep(accentIndex)
    }
    val scheme = (if (darkTheme) DarkColors else LightColors).copy(
        primary = accent,
        secondary = accent,
        onPrimary = if (darkTheme) Color(0xFF0F2926) else Color.White,
        onPrimaryContainer = accent
    )

    // enableEdgeToEdge() styles the system bars from the *system* dark mode, so
    // picking Light while the phone is in dark mode left white status-bar icons
    // on a #F2F2F7 page — unreadable. Tie the bar icon tint to the app's theme.
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    CompositionLocalProvider(LocalFgSemantic provides semantic) {
        MaterialTheme(
            colorScheme = scheme,
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
