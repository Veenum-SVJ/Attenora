package dev.opensource.focusguard.ui.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.opensource.focusguard.data.AppIconShape
import dev.opensource.focusguard.data.TabChipShape
import dev.opensource.focusguard.data.ThemeMode
import dev.opensource.focusguard.ui.common.FgIcon
import dev.opensource.focusguard.ui.common.LocalAppContainer
import dev.opensource.focusguard.ui.common.PageHeader
import dev.opensource.focusguard.ui.common.SectionCard
import dev.opensource.focusguard.ui.icons.FgIcons
import dev.opensource.focusguard.ui.theme.FgColors
import dev.opensource.focusguard.util.LauncherIcons
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AppearanceScreen(onBack: () -> Unit) {
    val container = LocalAppContainer.current
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current

    val launcherIndex by container.settings.launcherIcon.collectAsState(initial = 0)
    val accentIndex by container.settings.iconAccent.collectAsState(initial = 0)
    val iconShape by container.settings.appIconShape.collectAsState(initial = AppIconShape.ROUNDED)
    val chipShape by container.settings.tabChipShape.collectAsState(initial = TabChipShape.ROUNDED)
    val themeMode by container.settings.themeMode.collectAsState(initial = ThemeMode.SYSTEM)

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 32.dp)
    ) {
        PageHeader("Appearance", onBack = onBack)

        Spacer(Modifier.height(8.dp))

        // ── Theme ────────────────────────────────────────────────────────
        SectionCard("Theme") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(ThemeMode.SYSTEM to "System", ThemeMode.DARK to "Dark", ThemeMode.LIGHT to "Light")
                    .forEach { (mode, label) ->
                        val active = mode == themeMode
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(50))
                                .background(
                                    if (active) FgColors.Lavender else MaterialTheme.colorScheme.surfaceVariant
                                )
                                .clickable {
                                    scope.launch { container.settings.setThemeMode(mode) }
                                }
                                .padding(horizontal = 18.dp, vertical = 9.dp)
                        ) {
                            Text(
                                label,
                                style = MaterialTheme.typography.labelLarge,
                                color = if (active) Color(0xFF17131F) else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
            }
        }

        Spacer(Modifier.height(16.dp))

        // ── Launcher icon ────────────────────────────────────────────────
        SectionCard("Launcher icon") {
            Text(
                "Pick a shield colour. The change appears on your home screen in a moment.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                FgColors.AccentChoices.forEachIndexed { i, (main, deep) ->
                    val selected = i == launcherIndex
                    val border by animateColorAsState(
                        if (selected) FgColors.Lavender else Color.Transparent,
                        tween(200), label = "border-$i"
                    )
                    Box(
                        Modifier
                            .size(72.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .border(2.dp, border, RoundedCornerShape(18.dp))
                            .background(
                                Brush.linearGradient(listOf(main, deep)),
                                RoundedCornerShape(18.dp)
                            )
                            .clickable {
                                scope.launch {
                                    container.settings.setLauncherIcon(i)
                                    LauncherIcons.apply(context, i)
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        ShieldCheck(width = 44.dp, checkColor = Color(0xFF17131F))
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FgColors.AccentNames.forEachIndexed { i, name ->
                    val selected = i == launcherIndex
                    Text(
                        name,
                        modifier = Modifier.width(72.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        color = if (selected) FgColors.Lavender else MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // ── In-app accent ────────────────────────────────────────────────
        SectionCard("In-app icon accent") {
            Text(
                "The colour of icons and chips in the bottom bar, drawer, and search pill.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                FgColors.AccentChoices.forEachIndexed { i, (main, _) ->
                    val selected = i == accentIndex
                    Box(
                        Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(main)
                            .then(
                                if (selected) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                                else Modifier
                            )
                            .clickable {
                                scope.launch { container.settings.setIconAccent(i) }
                            }
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // ── Tab chip shape ───────────────────────────────────────────────
        SectionCard("Bottom nav chip shape") {
            val shapes = listOf(TabChipShape.ROUNDED, TabChipShape.CIRCLE, TabChipShape.PILL)
            val labels = listOf("Rounded □", "Circle ○", "Pill ▯")
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                shapes.forEachIndexed { i, shape ->
                    val active = shape == chipShape
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            Modifier
                                .size(48.dp)
                                .clip(
                                    when (shape) {
                                        TabChipShape.CIRCLE -> CircleShape
                                        TabChipShape.PILL -> RoundedCornerShape(50)
                                        else -> RoundedCornerShape(14.dp)
                                    }
                                )
                                .background(
                                    if (active) FgColors.Lavender.copy(alpha = 0.18f)
                                    else MaterialTheme.colorScheme.surfaceVariant
                                )
                                .clickable {
                                    scope.launch { container.settings.setTabChipShape(shape) }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            FgIcon(FgIcons.ChartBar, null, size = 24.dp,
                                tint = if (active) FgColors.Lavender else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(labels[i], style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // ── App icon shape ────────────────────────────────────────────────
        SectionCard("Per-app icon shape") {
            val shapes = listOf(AppIconShape.ROUNDED, AppIconShape.SQUIRCLE, AppIconShape.CIRCLE, AppIconShape.SQUARE)
            val labels = listOf("Rounded ▢", "Squircle ◇", "Circle ○", "Square □")
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                shapes.forEachIndexed { i, shape ->
                    val active = shape == iconShape
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            Modifier
                                .size(48.dp)
                                .clip(
                                    when (shape) {
                                        AppIconShape.CIRCLE -> CircleShape
                                        AppIconShape.SQUARE -> RoundedCornerShape(0.dp)
                                        AppIconShape.SQUIRCLE -> RoundedCornerShape(28.dp * 0.30f)
                                        else -> RoundedCornerShape(28.dp * 0.22f)
                                    }
                                )
                                .background(
                                    if (active) FgColors.Lavender else MaterialTheme.colorScheme.surfaceVariant
                                )
                                .clickable {
                                    scope.launch { container.settings.setAppIconShape(shape) }
                                }
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(labels[i], style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

/** Simplified shield + checkmark drawn on Canvas, used for launcher picker previews. */
@Composable
private fun ShieldCheck(width: Dp, checkColor: Color) {
    Canvas(Modifier.size(width)) {
        val w = size.width
        val h = size.height
        val cx = w / 2f
        val shieldPath = Path().apply {
            moveTo(cx, 0f)
            lineTo(w * 0.92f, w * 0.20f)
            lineTo(w * 0.92f, h * 0.65f)
            val control = (h - w * 0.20f) * 0.55f
            quadraticTo(w * 0.88f, h * 0.78f, cx, h * 0.92f)
            quadraticTo(w * 0.12f, h * 0.78f, w * 0.08f, h * 0.65f)
            lineTo(w * 0.08f, w * 0.20f)
            close()
        }
        drawPath(shieldPath, color = checkColor)
        // Checkmark
        val stroke = 3.5f
        drawLine(
            checkColor,
            Offset(w * 0.30f, h * 0.52f),
            Offset(w * 0.45f, h * 0.67f),
            strokeWidth = stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round
        )
        drawLine(
            checkColor,
            Offset(w * 0.45f, h * 0.67f),
            Offset(w * 0.70f, h * 0.40f),
            strokeWidth = stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round
        )
    }
}