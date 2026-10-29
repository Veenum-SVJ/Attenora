package dev.opensource.attenora.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import dev.opensource.attenora.enforcement.AttenoraAccessibilityService
import dev.opensource.attenora.enforcement.MonitorWatchdog
import dev.opensource.attenora.ui.common.AboutFooter
import dev.opensource.attenora.ui.common.FgDivider
import dev.opensource.attenora.ui.common.FgGroup
import dev.opensource.attenora.ui.common.NavRow
import dev.opensource.attenora.ui.common.PageHeader
import dev.opensource.attenora.ui.common.hasNotificationPermission
import dev.opensource.attenora.ui.common.hasUsageAccess
import dev.opensource.attenora.ui.common.healthCopy
import dev.opensource.attenora.ui.common.healthIssue
import dev.opensource.attenora.ui.common.isAccessibilityServiceEnabled
import dev.opensource.attenora.ui.icons.FgIcons
import dev.opensource.attenora.ui.theme.fg

/** Row leading padding (20) + icon (22) + gap (14) — where a divider should start. */
internal val RowInset = 56.dp

/**
 * Settings is a menu, not a form.
 *
 * Every row opens its own full page: there are no dropdowns and nothing expands
 * in place, so the list stays short enough to scan and a setting is always one
 * tap from here. The old screen put eight collapsible sections on one page,
 * which is what made it read as a settings *dump* rather than a settings menu.
 */
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var page by rememberSaveable { mutableStateOf<String?>(null) }

    var usageGranted by remember { mutableStateOf(hasUsageAccess(context)) }
    var serviceOn by remember {
        mutableStateOf(isAccessibilityServiceEnabled(context, AttenoraAccessibilityService::class.java))
    }
    var notifGranted by remember { mutableStateOf(hasNotificationPermission(context)) }
    // Enabled in Accessibility but not bound in this process: the setting is a
    // claim, not proof. See MonitorWatchdog for why this happens.
    var monitorStalled by remember { mutableStateOf(MonitorWatchdog.isStalled(context)) }

    // Re-read on resume: the user leaves Settings to grant a permission in
    // system settings, and we want the list to be correct when they come back.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                usageGranted = hasUsageAccess(context)
                serviceOn = isAccessibilityServiceEnabled(
                    context, AttenoraAccessibilityService::class.java
                )
                notifGranted = hasNotificationPermission(context)
                monitorStalled = MonitorWatchdog.isStalled(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Back closes the open page, so the system gesture behaves like the arrow.
    BackHandler(enabled = page != null) { page = null }

    when (page) {
        "tracking" -> { TrackingSettingsScreen(onBack = { page = null }); return }
        "permissions" -> { PermissionsScreen(onBack = { page = null }); return }
        "health" -> { AppHealthScreen(onBack = { page = null }); return }
        "theme" -> { ThemeSettingsScreen(onBack = { page = null }); return }
        "tools" -> { FocusToolsScreen(onBack = { page = null }); return }
        "web" -> { WebsiteBlockingScreen(onBack = { page = null }); return }
        "reminder" -> { DailyReminderScreen(onBack = { page = null }); return }
        "data" -> { DataScreen(onBack = { page = null }); return }
    }

    // Permissions is auto-hiding: once everything is granted the row is simply
    // gone, and it comes back by itself the moment something is revoked - or
    // stops answering while still being listed as granted.
    val allGranted = usageGranted && serviceOn && !monitorStalled && notifGranted

    // A stalled monitor is "on" in the OS but not connected here, so it has to
    // be reported as disconnected rather than as enabled.
    val issue = healthIssue(
        usageGranted = usageGranted,
        monitorEnabled = serviceOn,
        monitorConnected = serviceOn && !monitorStalled
    )

    // The footer sits at the bottom of the screen, not tucked under the last
    // row: the list scrolls inside the space above it, so the brand line is
    // always in the same place no matter how many rows are showing.
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
        ) {
            PageHeader("Settings", onBack = onBack)
            Spacer(Modifier.height(4.dp))
            FgGroup(Modifier.padding(horizontal = 16.dp)) {
                NavRow("Usage Limits and Tracking", { page = "tracking" }, icon = FgIcons.Gauge)
                FgDivider(inset = RowInset)
                NavRow(
                    title = "App health",
                    onClick = { page = "health" },
                    subtitle = issue?.let { healthCopy(it).title },
                    value = if (issue == null) "OK" else "Fix",
                    valueColor = if (issue == null) {
                        MaterialTheme.fg.success
                    } else {
                        MaterialTheme.fg.warning
                    },
                    icon = FgIcons.Shield
                )
                if (!allGranted) {
                    FgDivider(inset = RowInset)
                    NavRow("Permissions", { page = "permissions" }, icon = FgIcons.Lock)
                }
                FgDivider(inset = RowInset)
                NavRow("Theme", { page = "theme" }, icon = FgIcons.Palette)
                FgDivider(inset = RowInset)
                NavRow("Focus Tools", { page = "tools" }, icon = FgIcons.Stopwatch)
                FgDivider(inset = RowInset)
                NavRow("Website Blocking", { page = "web" }, icon = FgIcons.Globe)
                FgDivider(inset = RowInset)
                NavRow("Daily Reminder", { page = "reminder" }, icon = FgIcons.Bell)
                FgDivider(inset = RowInset)
                NavRow("Data", { page = "data" }, icon = FgIcons.Doc)
            }
        }
        AboutFooter()
    }
}
