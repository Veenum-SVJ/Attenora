package dev.opensource.attenora.ui.settings

import android.content.Intent
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import dev.opensource.attenora.data.BlockedSite
import dev.opensource.attenora.data.ChallengeMode
import dev.opensource.attenora.data.SettingsRepository
import dev.opensource.attenora.data.ThemeMode
import dev.opensource.attenora.enforcement.Alarms
import dev.opensource.attenora.enforcement.AttenoraAccessibilityService
import dev.opensource.attenora.enforcement.HabitStatusSync
import dev.opensource.attenora.enforcement.MonitorWatchdog
import dev.opensource.attenora.ui.limits.rememberChallengeGate
import dev.opensource.attenora.util.Grayscale
import dev.opensource.attenora.ui.common.FgDivider
import dev.opensource.attenora.ui.common.FgGroup
import dev.opensource.attenora.ui.common.FgIcon
import dev.opensource.attenora.ui.common.LocalAppContainer
import dev.opensource.attenora.ui.common.NavRow
import dev.opensource.attenora.ui.common.PageHeader
import dev.opensource.attenora.ui.common.PermissionRow
import dev.opensource.attenora.ui.common.formatClock
import dev.opensource.attenora.ui.common.isAccessibilityServiceEnabled
import dev.opensource.attenora.ui.common.openAccessibilitySettings
import dev.opensource.attenora.ui.common.ToggleRow
import dev.opensource.attenora.ui.common.hasNotificationPermission
import dev.opensource.attenora.ui.common.requestNotificationPermission
import dev.opensource.attenora.ui.icons.FgIcons
import dev.opensource.attenora.ui.notifications.UsageNotificationsScreen
import dev.opensource.attenora.ui.theme.FgColors
import dev.opensource.attenora.ui.theme.fg
import dev.opensource.attenora.util.LauncherIcons
import dev.opensource.attenora.util.exportCsv
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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

/**
 * Pill selector used by the theme, gate and reset-hour choices.
 *
 * Sized to the 48dp minimum rather than to its label: these pills are the whole
 * choice on their row, so the tap target is the pill itself.
 */
@Composable
internal fun ChoicePill(
    label: String,
    active: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Box(
        Modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(50))
            .background(
                if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                else MaterialTheme.colorScheme.surfaceVariant
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center
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
 * How solid the on-video count is. Lives under the reel switch because it only
 * means anything while the counter is on. The value is committed when the
 * finger lifts rather than on every frame, so a drag is one write, not sixty.
 */
@Composable
private fun OverlayOpacityRow(value: Float, onChange: (Float) -> Unit) {
    var local by remember(value) { mutableStateOf(value) }
    Column(
        Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 2.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Count opacity",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f)
            )
            Text(
                "${(local * 100).toInt()}%",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Slider(
            value = local,
            onValueChange = { local = it },
            onValueChangeFinished = { onChange(local) },
            valueRange = SettingsRepository.MIN_REEL_OPACITY..1f
        )
    }
}

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
    val reelOpacity by container.settings.reelOverlayOpacity.collectAsState(initial = 1f)
    val challengeMode by container.settings.challengeMode.collectAsState(initial = ChallengeMode.NONE)
    val challengeType by container.settings.challengeType.collectAsState(initial = "quote")

    // Turning protection off, or swapping it for something easier, is itself a
    // protection change: whoever is holding the phone must pass whatever is set
    // right now, or the gate below is a two-tap formality.
    val gate = rememberChallengeGate()

    var showHourPicker by remember { mutableStateOf(false) }

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
            if (reelCounterOn) {
                FgDivider(inset = RowInset)
                OverlayOpacityRow(reelOpacity) { v ->
                    scope.launch { container.settings.setReelOverlayOpacity(v) }
                }
            }
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
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ChoicePill("Off", challengeMode != ChallengeMode.CHALLENGE) {
                        gate.request {
                            scope.launch { container.settings.setChallengeMode(ChallengeMode.NONE) }
                        }
                    }
                    ChoicePill(
                        "Math challenge",
                        challengeMode == ChallengeMode.CHALLENGE && challengeType == "math"
                    ) {
                        gate.request {
                            scope.launch {
                                container.settings.setChallengeMode(ChallengeMode.CHALLENGE)
                                container.settings.setChallengeType("math")
                            }
                        }
                    }
                    ChoicePill(
                        "Awareness Challenge",
                        challengeMode == ChallengeMode.CHALLENGE && challengeType != "math"
                    ) {
                        gate.request {
                            scope.launch {
                                container.settings.setChallengeMode(ChallengeMode.CHALLENGE)
                                container.settings.setChallengeType("quote")
                            }
                        }
                    }
                }
                if (challengeMode == ChallengeMode.CHALLENGE && challengeType != "math") {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "One of several long awareness passages, picked at random each " +
                            "time. It must be retyped exactly, capital letters and " +
                            "punctuation included, before an existing limit is changed. " +
                            "Adding a new limit never asks.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
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
                            .padding(vertical = 14.dp, horizontal = 4.dp),
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
    val container = LocalAppContainer.current
    val scope = rememberCoroutineScope()
    var usageGranted by remember { mutableStateOf(dev.opensource.attenora.ui.common.hasUsageAccess(context)) }
    var serviceOn by remember {
        mutableStateOf(
            dev.opensource.attenora.ui.common.isAccessibilityServiceEnabled(
                context, AttenoraAccessibilityService::class.java
            )
        )
    }
    // "Enabled" only means the setting lists us. This is the half that can be
    // true while nothing is actually running (see MonitorWatchdog).
    var monitorStalled by remember { mutableStateOf(MonitorWatchdog.isStalled(context)) }
    var notifGranted by remember { mutableStateOf(hasNotificationPermission(context)) }

    fun refresh() {
        usageGranted = dev.opensource.attenora.ui.common.hasUsageAccess(context)
        serviceOn = dev.opensource.attenora.ui.common.isAccessibilityServiceEnabled(
            context, AttenoraAccessibilityService::class.java
        )
        monitorStalled = MonitorWatchdog.isStalled(context)
        notifGranted = hasNotificationPermission(context)
    }

    // The user may leave for system settings and come back; and a vendor kill
    // can happen while this page is open. Either way the row must be re-read.
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    SettingsPage("Permissions", onBack) {
        FgGroup(Modifier.padding(horizontal = 16.dp)) {
            PermissionRow("Usage access", usageGranted, "Grant") {
                context.startActivity(Intent(AndroidSettings.ACTION_USAGE_ACCESS_SETTINGS))
            }
            FgDivider(inset = 20.dp)
            PermissionRow(
                title = "Attenora accessibility (blocking, websites, in-app)",
                granted = serviceOn,
                actionLabel = if (monitorStalled) "Restart" else "Open",
                note = if (monitorStalled) {
                    "On, but not answering. Limits and the reel counter are paused, " +
                        "usually after the phone was locked for a while."
                } else null,
                warn = monitorStalled
            ) {
                if (!monitorStalled) {
                    // A normal grant: open the system page and let the user flip it.
                    openAccessibilitySettings(context, AttenoraAccessibilityService::class.java)
                } else {
                    // Try to force the rebind ourselves first (needs the adb
                    // grant); fall back to the toggle, which always works.
                    MonitorWatchdog.check(context, "settings")
                    scope.launch {
                        delay(1_500)
                        refresh()
                        if (!container.a11yConnected && serviceOn) {
                            openAccessibilitySettings(
                                context, AttenoraAccessibilityService::class.java
                            )
                        }
                    }
                }
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

@Composable
fun ThemeSettingsScreen(onBack: () -> Unit) {
    val container = LocalAppContainer.current
    val scope = rememberCoroutineScope()

    val themeMode by container.settings.themeMode.collectAsState(initial = ThemeMode.SYSTEM)

    SettingsPage("Theme", onBack) {
        SectionLabel("Appearance")
        FgGroup(Modifier.padding(horizontal = 16.dp)) {
            Column(Modifier.padding(20.dp)) {
                Text(
                    "Attenora follows your phone unless you tell it otherwise.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(14.dp))
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

        // One accent, six options, all from the brand palette. The picked index
        // also selects the matching home-screen icon (see LauncherIcons), which
        // is why both are written together.
        SectionLabel("Accent")
        FgGroup(Modifier.padding(horizontal = 16.dp)) {
            val context = LocalContext.current
            val accentIndex by container.settings.iconAccent.collectAsState(initial = 0)
            Column(Modifier.padding(20.dp)) {
                Text(
                    "One accent, drawn from the Attenora palette. It sets the app " +
                        "tint and the home-screen icon together.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(16.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    FgColors.AccentNames.forEachIndexed { index, name ->
                        val swatch = FgColors.AccentChoices[index].first
                        val selected = index == accentIndex
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(
                                Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(swatch)
                                    .then(
                                        if (selected) {
                                            Modifier.border(
                                                2.dp,
                                                MaterialTheme.colorScheme.onBackground,
                                                CircleShape
                                            )
                                        } else {
                                            Modifier
                                        }
                                    )
                                    .clickable {
                                        scope.launch {
                                            container.settings.setIconAccent(index)
                                            LauncherIcons.apply(context, index)
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                if (selected) {
                                    FgIcon(
                                        FgIcons.Check,
                                        null,
                                        size = 18.dp,
                                        tint = if (swatch.luminance() > 0.5f) Color.Black else Color.White
                                    )
                                }
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(
                                name,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (selected) {
                                    MaterialTheme.colorScheme.onBackground
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Focus Tools
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun FocusToolsScreen(onBack: () -> Unit) {
    var page by rememberSaveable { mutableStateOf<String?>(null) }
    val context = LocalContext.current

    // The grayscale row used to keep saying "Needs one adb grant" after the
    // grant existed, which reads like the feature is still broken. Ask the
    // permission instead, and re-ask on resume (the grant arrives from a
    // computer while the app is in the background).
    var grayscaleReady by remember { mutableStateOf(Grayscale.hasPermission(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                grayscaleReady = Grayscale.hasPermission(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    BackHandler(enabled = page != null) { page = null }

    when (page) {
        "unlock" -> { UnlockSettingsScreen(onBack = { page = null }); return }
        "grayscale" -> { GrayscaleScreen(onBack = { page = null }); return }
    }

    SettingsPage("Focus Tools", onBack) {
        FgGroup(Modifier.padding(horizontal = 16.dp)) {
            NavRow(
                title = "Unlock delay & quota",
                subtitle = "A pause before a focus session can end early, and a daily " +
                    "allowance on how often. No codes.",
                icon = FgIcons.Hourglass,
                onClick = { page = "unlock" }
            )
            FgDivider(inset = RowInset)
            NavRow(
                title = "Grayscale apps",
                subtitle = if (grayscaleReady) {
                    "Open selected apps in black & white"
                } else {
                    "Open selected apps in black & white. Needs one adb grant — " +
                        "the command is inside"
                },
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

    // The switch keeps its value while the service is off, so the subtitle has
    // to reflect the real state. It used to be a static "needs accessibility"
    // line, which kept nagging people who had already granted it.
    var serviceOn by remember {
        mutableStateOf(
            isAccessibilityServiceEnabled(context, AttenoraAccessibilityService::class.java)
        )
    }
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                serviceOn = isAccessibilityServiceEnabled(
                    context, AttenoraAccessibilityService::class.java
                )
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var newSite by rememberSaveable { mutableStateOf("") }
    var siteError by rememberSaveable { mutableStateOf<String?>(null) }

    SettingsPage("Website Blocking", onBack) {
        FgGroup(Modifier.padding(horizontal = 16.dp)) {
            ToggleRow(
                title = "Block websites",
                subtitle = if (serviceOn) {
                    "Blocks these domains in every browser"
                } else {
                    "Needs the Attenora accessibility service (see Permissions)"
                },
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
                        val domain = dev.opensource.attenora.domain.UrlMatcher.normalizeDomain(newSite)
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
                subtitle = "Weekly summary and block alerts",
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
// Habit reminders
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Being told about habits, in the two shapes that need no polling: one inexact
 * alarm a day, and one silent ongoing notification driven by the database.
 */
@Composable
fun HabitRemindersScreen(onBack: () -> Unit) {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val reminderMinute by container.settings.habitReminderMinute.collectAsState(initial = -1)
    val notifyOn by container.settings.habitNotifyOn.collectAsState(initial = false)

    var showPicker by remember { mutableStateOf(false) }
    var notifPermission by remember { mutableStateOf(hasNotificationPermission(context)) }

    // The system dialog changes the permission; re-read it when we come back.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                notifPermission = hasNotificationPermission(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    SettingsPage("Habit reminders", onBack) {
        if (!notifPermission) {
            FgGroup(Modifier.padding(horizontal = 16.dp)) {
                Column(Modifier.padding(20.dp)) {
                    Text("Notifications are off", style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Android blocks both of these until you allow notifications for Attenora.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(onClick = { requestNotificationPermission(context) }) {
                        Text("Allow notifications")
                    }
                }
            }
        }

        SectionLabel("Daily nudge")
        FgGroup(Modifier.padding(horizontal = 16.dp)) {
            ToggleRow(
                title = "Remind me about habits",
                subtitle = if (reminderMinute in 0..1439) {
                    "Every day at ${formatClock(reminderMinute)} — habits without a time of their own"
                } else {
                    "Off"
                },
                icon = FgIcons.Bell,
                checked = reminderMinute in 0..1439,
                onCheckedChange = { on ->
                    val minute = if (on) 20 * 60 else -1
                    // The alarm follows from the collector in AppContainer:
                    // this default and the habit table both feed it.
                    scope.launch { container.settings.setHabitReminderMinute(minute) }
                }
            )
            if (reminderMinute in 0..1439) {
                FgDivider(inset = RowInset)
                NavRow(
                    title = "Time",
                    value = formatClock(reminderMinute),
                    onClick = { showPicker = true }
                )
            }
        }
        Text(
            "One notification a day, and it stays quiet when everything is " +
                "already done. A habit can ask for a time of its own on its own " +
                "page; this is the default for the ones that don't. Android may " +
                "deliver it a few minutes late — that is the price of not asking " +
                "for exact alarms.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
        )

        SectionLabel("In the notification shade")
        FgGroup(Modifier.padding(horizontal = 16.dp)) {
            ToggleRow(
                title = "Show what's left today",
                subtitle = "A silent, ongoing line that updates as you tick habits",
                icon = FgIcons.CircleCheck,
                checked = notifyOn,
                onCheckedChange = { on ->
                    scope.launch {
                        container.settings.setHabitNotify(on)
                        Alarms.setHabitRefreshAlarm(context, on, container.resetHourCache)
                        HabitStatusSync.refresh(context, container)
                    }
                }
            )
        }
        Text(
            "It disappears on its own once the last habit is marked, and comes " +
                "back the next morning with the new day.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
        )
    }

    if (showPicker) {
        ReminderTimeDialog(
            initialMinute = if (reminderMinute in 0..1439) reminderMinute else 20 * 60,
            onDismiss = { showPicker = false },
            onSave = { minute ->
                scope.launch {
                    container.settings.setHabitReminderMinute(minute)
                }
                showPicker = false
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
                    val name = "attenora-usage-${java.time.LocalDate.now()}.csv"
                    csvLauncher.launch(name)
                }
            )
        }
        SectionLabel("Danger zone")
        FgGroup(Modifier.padding(horizontal = 16.dp)) {
            Column(Modifier.padding(20.dp)) {
                Text(
                    "Erase all Attenora data",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.fg.danger
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Every limit, schedule, blocked site, habit and day of usage history " +
                        "is deleted from this device. There is no cloud copy — this cannot " +
                        "be undone.",
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
                    "Every limit, schedule, blocked site, habit and day of usage history " +
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
                        container.db.habitStepCheckDao().deleteAll()
                        container.db.habitStepDao().deleteAll()
                        container.db.habitEntryDao().deleteAll()
                        container.db.habitDao().deleteAll()
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReminderTimeDialog(
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
