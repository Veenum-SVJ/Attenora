package dev.opensource.focusguard.ui.settings

import android.content.Intent
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.opensource.focusguard.data.BlockedSite
import dev.opensource.focusguard.data.ChallengeMode
import dev.opensource.focusguard.data.ThemeMode
import dev.opensource.focusguard.enforcement.Alarms
import dev.opensource.focusguard.enforcement.FocusGuardAccessibilityService
import dev.opensource.focusguard.security.PinManager
import dev.opensource.focusguard.ui.common.FgDivider
import dev.opensource.focusguard.ui.common.FgGroup
import dev.opensource.focusguard.ui.common.FgIcon
import dev.opensource.focusguard.ui.common.LocalAppContainer
import dev.opensource.focusguard.ui.common.NavRow
import dev.opensource.focusguard.ui.common.PageHeader
import dev.opensource.focusguard.ui.common.PermissionRow
import dev.opensource.focusguard.ui.common.ToggleRow
import dev.opensource.focusguard.ui.common.hasNotificationPermission
import dev.opensource.focusguard.ui.common.requestNotificationPermission
import dev.opensource.focusguard.ui.icons.FgIcons
import dev.opensource.focusguard.ui.notifications.UsageNotificationsScreen
import dev.opensource.focusguard.ui.theme.FgColors
import dev.opensource.focusguard.ui.theme.fg
import dev.opensource.focusguard.util.LauncherIcons
import dev.opensource.focusguard.util.exportCsv
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

// ─────────────────────────────────────────────────────────────────────────────
// Shared bits
// ─────────────────────────────────────────────────────────────────────────────

/** Small section heading above a grouped card. Sentence case, never shouted. */
@Composable
private fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier.padding(start = 20.dp, top = 20.dp, bottom = 8.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/** Standard page shell for the pushed settings screens. */
@Composable
private fun SettingsPage(
    title: String,
    onBack: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        PageHeader(title, onBack = onBack)
        Spacer(Modifier.height(4.dp))
        content()
        Spacer(Modifier.height(40.dp))
    }
}

/** Pill selector used by the theme, gate and reset-hour choices. */
@Composable
internal fun ChoicePill(
    label: String,
    active: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(
                if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                else MaterialTheme.colorScheme.surfaceVariant
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = when {
                !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                active -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Usage Limits and Tracking
// ─────────────────────────────────────────────────────────────────────────────

/**
 * How usage is measured, and who is allowed to change a limit.
 *
 * The two halves are deliberately one page: both answer "what counts, and who
 * decides", and they are the only two things that need the accessibility
 * service to agree with the UI.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TrackingSettingsScreen(onBack: () -> Unit) {
    val container = LocalAppContainer.current
    val scope = rememberCoroutineScope()

    val resetHour by container.settings.resetHour.collectAsState(initial = 0)
    val mondayFirst by container.settings.weekMondayFirst.collectAsState(initial = true)
    val countdownOn by container.settings.countdownOn.collectAsState(initial = true)
    val reelCounterOn by container.settings.reelCounterOn.collectAsState(initial = true)
    val challengeMode by container.settings.challengeMode.collectAsState(initial = ChallengeMode.NONE)
    val challengeType by container.settings.challengeType.collectAsState(initial = "quote")
    val challengeText by container.settings.challengeText.collectAsState(initial = "")
    val pinHash by container.settings.pinHash.collectAsState(initial = "")
    val biometricOn by container.settings.biometricEnabled.collectAsState(initial = true)
    val hasPin = pinHash != null && pinHash != ""

    var showHourPicker by remember { mutableStateOf(false) }
    var showPinDialog by remember { mutableStateOf(false) }
    var showRemovePinConfirm by remember { mutableStateOf(false) }
    var customSentence by rememberSaveable { mutableStateOf<String?>(null) }

    SettingsPage("Usage Limits and Tracking", onBack) {
        SectionLabel("Tracking")
        FgGroup(Modifier.padding(horizontal = 16.dp)) {
            NavRow(
                title = "New day starts at",
                value = "%02d:00".format(resetHour),
                onClick = { showHourPicker = true }
            )
            FgDivider(inset = 20.dp)
            ToggleRow(
                title = "Week starts on Monday",
                subtitle = "Off to start the week on Sunday",
                checked = mondayFirst,
                onCheckedChange = { on -> scope.launch { container.settings.setWeekMondayFirst(on) } }
            )
            FgDivider(inset = 20.dp)
            ToggleRow(
                title = "Countdown before a limit hits",
                subtitle = "Shows time remaining while a limited app is open",
                checked = countdownOn,
                onCheckedChange = { on -> scope.launch { container.settings.setCountdownOn(on) } }
            )
            FgDivider(inset = 20.dp)
            ToggleRow(
                title = "Count reels and shorts",
                subtitle = "One per video in Reels, Shorts, TikTok and Spotlight, " +
                    "with a live count over the feed",
                checked = reelCounterOn,
                onCheckedChange = { on -> scope.launch { container.settings.setReelCounter(on) } }
            )
        }

        SectionLabel("Limit protection")
        FgGroup(Modifier.padding(horizontal = 16.dp)) {
            Column(Modifier.padding(20.dp)) {
                Text(
                    "Ask before anyone can add, edit or delete a usage limit.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                val modeActive = challengeMode == ChallengeMode.PIN ||
                    challengeMode == ChallengeMode.CHALLENGE
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ChoicePill("Off", !modeActive) {
                        scope.launch { container.settings.setChallengeMode(ChallengeMode.NONE) }
                    }
                    ChoicePill("PIN", challengeMode == ChallengeMode.PIN, enabled = hasPin) {
                        scope.launch { container.settings.setChallengeMode(ChallengeMode.PIN) }
                    }
                    ChoicePill(
                        "Math challenge",
                        challengeMode == ChallengeMode.CHALLENGE && challengeType == "math"
                    ) {
                        scope.launch {
                            container.settings.setChallengeMode(ChallengeMode.CHALLENGE)
                            container.settings.setChallengeType("math")
                        }
                    }
                    ChoicePill(
                        "Long word",
                        challengeMode == ChallengeMode.CHALLENGE && challengeType != "math"
                    ) {
                        scope.launch {
                            container.settings.setChallengeMode(ChallengeMode.CHALLENGE)
                            container.settings.setChallengeType("quote")
                        }
                    }
                }
                if (!hasPin) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Set the PIN below first to be able to pick PIN protection.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (challengeMode == ChallengeMode.CHALLENGE && challengeType != "math") {
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = customSentence ?: challengeText,
                            onValueChange = { customSentence = it.take(120) },
                            label = { Text("Your long word or sentence (optional)") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = {
                            scope.launch {
                                container.settings.setChallengeText(customSentence ?: challengeText)
                            }
                        }) { Text("Save") }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "It must be retyped exactly before any limit change goes through. " +
                            "Leave it alone to keep the built-in sentence.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            FgDivider(inset = 20.dp)
            if (!hasPin) {
                Row(
                    Modifier.fillMaxWidth().padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("App lock PIN", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Protects limits and settings.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    OutlinedButton(onClick = { showPinDialog = true }) { Text("Set PIN") }
                }
            } else {
                NavRow("Change PIN", { showPinDialog = true }, value = "Set")
                FgDivider(inset = 20.dp)
                ToggleRow(
                    title = "Biometric / screen-lock unlock",
                    subtitle = "Faster entry with fingerprint or device credentials",
                    checked = biometricOn,
                    onCheckedChange = { on -> scope.launch { container.settings.setBiometric(on) } }
                )
                FgDivider(inset = 20.dp)
                NavRow(
                    title = "Remove PIN",
                    subtitle = "Limits will no longer be protected",
                    onClick = { showRemovePinConfirm = true }
                )
            }
        }
    }

    if (showHourPicker) {
        HourChoiceDialog(
            current = resetHour,
            onDismiss = { showHourPicker = false },
            onPick = { hour ->
                scope.launch { container.settings.setResetHour(hour) }
                showHourPicker = false
            }
        )
    }
    if (showPinDialog) {
        PinSetupDialog(
            onDismiss = { showPinDialog = false },
            onSaved = { hash, salt ->
                scope.launch { container.settings.setPin(hash, salt) }
                showPinDialog = false
            }
        )
    }
    if (showRemovePinConfirm) {
        AlertDialog(
            onDismissRequest = { showRemovePinConfirm = false },
            title = { Text("Remove PIN?") },
            text = { Text("Your limits and settings will no longer be protected.") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { container.settings.setPin(null, null) }
                    showRemovePinConfirm = false
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { showRemovePinConfirm = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun HourChoiceDialog(current: Int, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New day starts at") },
        text = {
            Column(
                Modifier
                    .height(320.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                (0..23).forEach { hour ->
                    val label = "%02d:00".format(hour)
                    Text(
                        if (hour == 0) "$label  ·  midnight" else label,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(hour) }
                            .padding(vertical = 12.dp, horizontal = 4.dp),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (hour == current) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (hour == current) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// Permissions
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Only reachable while something is missing — the Settings list hides the row
 * once everything is granted, so landing here always means "fix this".
 */
@Composable
fun PermissionsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var usageGranted by remember { mutableStateOf(dev.opensource.focusguard.ui.common.hasUsageAccess(context)) }
    var serviceOn by remember {
        mutableStateOf(
            dev.opensource.focusguard.ui.common.isAccessibilityServiceEnabled(
                context, FocusGuardAccessibilityService::class.java
            )
        )
    }
    var notifGranted by remember { mutableStateOf(hasNotificationPermission(context)) }

    SettingsPage("Permissions", onBack) {
        FgGroup(Modifier.padding(horizontal = 16.dp)) {
            PermissionRow("Usage access", usageGranted, "Grant") {
                context.startActivity(Intent(AndroidSettings.ACTION_USAGE_ACCESS_SETTINGS))
            }
            FgDivider(inset = 20.dp)
            PermissionRow("FocusGuard accessibility (blocking, websites, in-app)", serviceOn, "Open") {
                context.startActivity(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                FgDivider(inset = 20.dp)
                PermissionRow("Notifications", notifGranted, "Allow") {
                    requestNotificationPermission(context)
                }
            }
        }
        SectionLabel("Note")
        FgGroup(Modifier.padding(horizontal = 16.dp)) {
            Text(
                "Grayscale needs one extra adb grant. It is set up on its own page " +
                    "under Focus Tools, which walks you through the command.",
                Modifier.padding(20.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Theme
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ThemeSettingsScreen(onBack: () -> Unit) {
    val container = LocalAppContainer.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val themeMode by container.settings.themeMode.collectAsState(initial = ThemeMode.SYSTEM)
    val launcherIndex by container.settings.launcherIcon.collectAsState(initial = 0)
    val accentIndex by container.settings.iconAccent.collectAsState(initial = 0)

    SettingsPage("Theme", onBack) {
        SectionLabel("Appearance")
        FgGroup(Modifier.padding(horizontal = 16.dp)) {
            Column(Modifier.padding(20.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        ThemeMode.SYSTEM to "System",
                        ThemeMode.DARK to "Dark",
                        ThemeMode.LIGHT to "Light"
                    ).forEach { (mode, label) ->
                        ChoicePill(label, mode == themeMode) {
                            scope.launch { container.settings.setThemeMode(mode) }
                        }
                    }
                }
            }
        }

        SectionLabel("Accent")
        FgGroup(Modifier.padding(horizontal = 16.dp)) {
            Column(Modifier.padding(20.dp)) {
                Text(
                    "Re-tints buttons, charts and the selected tab. Blocked and allowed " +
                        "states keep their own colours so they always mean the same thing.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    FgColors.AccentChoices.forEachIndexed { i, (main, _) ->
                        val selected = i == accentIndex
                        Box(
                            Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(main)
                                .then(
                                    if (selected) Modifier.border(
                                        3.dp, MaterialTheme.colorScheme.onSurface, CircleShape
                                    ) else Modifier
                                )
                                .clickable { scope.launch { container.settings.setIconAccent(i) } }
                        )
                    }
                }
            }
        }

        SectionLabel("Home screen icon")
        FgGroup(Modifier.padding(horizontal = 16.dp)) {
            Column(Modifier.padding(20.dp)) {
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
                            if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                            tween(200), label = "launcher-border-$i"
                        )
                        // Label lives inside the same column as its swatch, so
                        // the names can never wrap out of line with the tiles.
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
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
                                ShieldCheck(width = 44.dp, fill = Color(0xFF17131F), mark = main)
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(
                                FgColors.AccentNames[i],
                                modifier = Modifier.width(72.dp),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                color = if (selected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Simplified shield + check for the launcher-colour previews. The check is
 * drawn in [mark] rather than the shield's own colour — otherwise it is
 * invisible, which is exactly what happened while it was one colour.
 */
@Composable
private fun ShieldCheck(width: Dp, fill: Color, mark: Color) {
    Canvas(Modifier.size(width)) {
        val w = size.width
        val h = size.height
        val cx = w / 2f
        val shieldPath = Path().apply {
            moveTo(cx, 0f)
            lineTo(w * 0.92f, w * 0.20f)
            lineTo(w * 0.92f, h * 0.65f)
            quadraticTo(w * 0.88f, h * 0.78f, cx, h * 0.92f)
            quadraticTo(w * 0.12f, h * 0.78f, w * 0.08f, h * 0.65f)
            lineTo(w * 0.08f, w * 0.20f)
            close()
        }
        drawPath(shieldPath, color = fill)
        val stroke = 4f
        drawLine(
            mark, Offset(w * 0.30f, h * 0.50f), Offset(w * 0.44f, h * 0.65f),
            strokeWidth = stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round
        )
        drawLine(
            mark, Offset(w * 0.44f, h * 0.65f), Offset(w * 0.71f, h * 0.38f),
            strokeWidth = stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Focus Tools
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun FocusToolsScreen(onBack: () -> Unit) {
    var page by rememberSaveable { mutableStateOf<String?>(null) }

    BackHandler(enabled = page != null) { page = null }

    when (page) {
        "unlock" -> { UnlockSettingsScreen(onBack = { page = null }); return }
        "grayscale" -> { GrayscaleScreen(onBack = { page = null }); return }
    }

    SettingsPage("Focus Tools", onBack) {
        FgGroup(Modifier.padding(horizontal = 16.dp)) {
            NavRow(
                title = "Unlock challenge & quota",
                subtitle = "Delay, math/typing/intent challenges and a daily unlock cap " +
                    "at the moment of weakness",
                icon = FgIcons.Hourglass,
                onClick = { page = "unlock" }
            )
            FgDivider(inset = RowInset)
            NavRow(
                title = "Grayscale apps",
                subtitle = "Open selected apps in black & white. Needs one adb grant — " +
                    "the command is inside",
                icon = FgIcons.Eye,
                onClick = { page = "grayscale" }
            )
        }
        SectionLabel("Focus sessions")
        FgGroup(Modifier.padding(horizontal = 16.dp)) {
            Text(
                "Focus sessions are started from the Usage Limits screen: pick a length " +
                    "and every limit on the device is held off until it ends. Time spent " +
                    "focused is accrued per day and shown alongside your usage.",
                Modifier.padding(20.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Website Blocking
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun WebsiteBlockingScreen(onBack: () -> Unit) {
    val container = LocalAppContainer.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val webOn by container.webBlockingActive.collectAsState()
    val sites by container.db.blockedSiteDao().observeAll().collectAsState(initial = emptyList())

    var newSite by rememberSaveable { mutableStateOf("") }
    var siteError by rememberSaveable { mutableStateOf<String?>(null) }

    SettingsPage("Website Blocking", onBack) {
        FgGroup(Modifier.padding(horizontal = 16.dp)) {
            ToggleRow(
                title = "Block websites",
                subtitle = "Needs the FocusGuard accessibility service (see Permissions)",
                checked = webOn,
                onCheckedChange = { on -> scope.launch { container.settings.setWebBlocking(on) } }
            )
            FgDivider(inset = 20.dp)
            Column(Modifier.padding(20.dp)) {
                Text("Blocked domains", style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newSite,
                        onValueChange = { newSite = it; siteError = null },
                        label = { Text("Domain, e.g. youtube.com") },
                        singleLine = true,
                        isError = siteError != null,
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
                    )
                    Spacer(Modifier.width(8.dp))
                    FilledTonalButton(onClick = {
                        val domain = dev.opensource.focusguard.domain.UrlMatcher.normalizeDomain(newSite)
                        if (domain == null) {
                            siteError = "Enter a valid domain"
                        } else {
                            scope.launch {
                                container.db.blockedSiteDao()
                                    .insert(BlockedSite(domain, System.currentTimeMillis()))
                            }
                            newSite = ""
                        }
                    }) { FgIcon(FgIcons.Plus, "Add domain", size = 18.dp) }
                }
                siteError?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                if (sites.isEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "No domains yet. Add one above and it will be blocked in every browser " +
                            "while the switch is on.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Spacer(Modifier.height(4.dp))
                    sites.forEach { site ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(site.domain, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                            androidx.compose.material3.IconButton(onClick = {
                                scope.launch { container.db.blockedSiteDao().delete(site.domain) }
                            }) {
                                FgIcon(
                                    FgIcons.Trash, "Remove ${site.domain}", size = 20.dp,
                                    tint = MaterialTheme.fg.danger
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Daily Reminder
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DailyReminderScreen(onBack: () -> Unit) {
    val container = LocalAppContainer.current
    val scope = rememberCoroutineScope()

    val reminderMinute by container.settings.reminderMinute.collectAsState(initial = -1)
    var showReminderPicker by remember { mutableStateOf(false) }
    var showNotifications by remember { mutableStateOf(false) }

    BackHandler(enabled = showNotifications) { showNotifications = false }

    if (showNotifications) {
        UsageNotificationsScreen(onBack = { showNotifications = false })
        return
    }

    SettingsPage("Daily Reminder", onBack) {
        FgGroup(Modifier.padding(horizontal = 16.dp)) {
            if (reminderMinute in 0..1439) {
                NavRow(
                    title = "Daily reminder",
                    value = "%02d:%02d".format(reminderMinute / 60, reminderMinute % 60),
                    onClick = { showReminderPicker = true }
                )
                FgDivider(inset = 20.dp)
                NavRow(
                    title = "Turn off",
                    subtitle = "Stop the daily total notification",
                    onClick = { scope.launch { container.settings.setReminderMinute(-1) } }
                )
            } else {
                Column(Modifier.padding(20.dp)) {
                    Text(
                        "Get a notification with your total screen time.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(onClick = { showReminderPicker = true }) {
                        Text("Set reminder time")
                    }
                }
            }
        }
        SectionLabel("Other notifications")
        FgGroup(Modifier.padding(horizontal = 16.dp)) {
            NavRow(
                title = "Usage notifications",
                subtitle = "Weekly summary, morning & night nudges, block alerts",
                icon = FgIcons.Bell,
                onClick = { showNotifications = true }
            )
        }
    }

    if (showReminderPicker) {
        ReminderTimeDialog(
            initialMinute = if (reminderMinute in 0..1439) reminderMinute else 20 * 60,
            onDismiss = { showReminderPicker = false },
            onSave = { minute ->
                scope.launch { container.settings.setReminderMinute(minute) }
                showReminderPicker = false
            }
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Data
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun DataScreen(onBack: () -> Unit) {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showWipeConfirm by remember { mutableStateOf(false) }

    val csvLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                runCatching { exportCsv(context, container.db, uri) }
            }
        }
    }

    SettingsPage("Data", onBack) {
        SectionLabel("Export")
        FgGroup(Modifier.padding(horizontal = 16.dp)) {
            NavRow(
                title = "Export last 90 days as CSV",
                subtitle = "One row per app per day — opens your Files app to save it",
                icon = FgIcons.Download,
                onClick = {
                    val name = "focusguard-usage-${java.time.LocalDate.now()}.csv"
                    csvLauncher.launch(name)
                }
            )
        }
        SectionLabel("Danger zone")
        FgGroup(Modifier.padding(horizontal = 16.dp)) {
            Column(Modifier.padding(20.dp)) {
                Text(
                    "Erase all FocusGuard data",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.fg.danger
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Every limit, schedule, blocked site, PIN and day of usage history is " +
                        "deleted from this device. There is no cloud copy — this cannot be undone.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = { showWipeConfirm = true }) {
                    Text("Erase everything", color = MaterialTheme.fg.danger)
                }
            }
        }
    }

    if (showWipeConfirm) {
        AlertDialog(
            onDismissRequest = { showWipeConfirm = false },
            title = { Text("Erase all data?") },
            text = {
                Text(
                    "Every limit, schedule, blocked site, PIN and day of usage history " +
                        "will be deleted from this device. There is no cloud copy — this " +
                        "cannot be undone."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        val dao = container.db.focusScheduleDao()
                        Alarms.rearmAll(context, emptyList())
                        container.db.dailyUsageDao().deleteAll()
                        container.db.usageLimitDao().deleteAll()
                        dao.all().forEach { Alarms.setScheduleAlarm(context, it.copy(enabled = false)) }
                        dao.deleteAll()
                        container.db.blockedSiteDao().deleteAll()
                        container.settings.wipe()
                    }
                    showWipeConfirm = false
                }) { Text("Erase everything", color = MaterialTheme.fg.danger) }
            },
            dismissButton = { TextButton(onClick = { showWipeConfirm = false }) { Text("Cancel") } }
        )
    }
}

/** Danger colour, resolved from the fixed semantic palette. */

// ─────────────────────────────────────────────────────────────────────────────
// Dialogs
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun PinSetupDialog(onDismiss: () -> Unit, onSaved: (hash: String, salt: String) -> Unit) {
    var pin by rememberSaveable { mutableStateOf("") }
    var confirm by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Set PIN") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = pin,
                    onValueChange = { if (it.length <= 6 && it.all(Char::isDigit)) pin = it },
                    label = { Text("4–6 digits") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
                )
                OutlinedTextField(
                    value = confirm,
                    onValueChange = { if (it.length <= 6 && it.all(Char::isDigit)) confirm = it },
                    label = { Text("Repeat PIN") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
                )
                error?.let {
                    Text(it, color = MaterialTheme.fg.danger, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                when {
                    pin.length < 4 -> error = "PIN must be at least 4 digits"
                    pin != confirm -> error = "PINs don't match"
                    else -> {
                        val salt = PinManager.newSalt()
                        onSaved(PinManager.hash(pin, salt), salt)
                    }
                }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReminderTimeDialog(
    initialMinute: Int,
    onDismiss: () -> Unit,
    onSave: (minutesAfterMidnight: Int) -> Unit
) {
    val state = rememberTimePickerState(
        initialHour = initialMinute / 60,
        initialMinute = initialMinute % 60,
        is24Hour = true
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Reminder time") },
        text = { TimePicker(state = state) },
        confirmButton = {
            TextButton(onClick = { onSave(state.hour * 60 + state.minute) }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
