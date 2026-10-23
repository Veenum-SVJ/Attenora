package dev.opensource.focusguard.ui.notifications

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.opensource.focusguard.data.SettingsRepository
import dev.opensource.focusguard.enforcement.Alarms
import dev.opensource.focusguard.enforcement.MuteListenerService
import dev.opensource.focusguard.ui.common.FgCard
import dev.opensource.focusguard.ui.common.FgIcon
import dev.opensource.focusguard.ui.common.LocalAppContainer
import dev.opensource.focusguard.ui.common.NavRow
import dev.opensource.focusguard.ui.common.PageHeader
import dev.opensource.focusguard.ui.common.ToggleRow
import dev.opensource.focusguard.ui.common.formatClock
import dev.opensource.focusguard.ui.common.hasNotificationPermission
import dev.opensource.focusguard.ui.icons.FgIcons
import kotlinx.coroutines.launch

/**
 * Usage Notifications: every notification FocusGuard can show, in one list —
 * each with its own toggle/time. Nothing here runs extra background work:
 * totals update on app switches, the rest are inexact alarms.
 */
@Composable
fun UsageNotificationsScreen(onBack: () -> Unit) {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = container.settings

    val assistantOn by settings.assistantOn.collectAsState(initial = false)
    val total10On by settings.total10On.collectAsState(initial = false)
    val fiveMinOn by settings.fiveMinWarn.collectAsState(initial = true)
    val blockHitOn by settings.blockHitOn.collectAsState(initial = true)
    val scheduleAlertsOn by settings.scheduleAlerts.collectAsState(initial = true)
    val reminderMinute by settings.reminderMinute.collectAsState(initial = -1)
    val weeklyOn by settings.weeklyOn.collectAsState(initial = true)
    val weeklyDay by settings.weeklyDay.collectAsState(initial = 0)
    val weeklyMinute by settings.weeklyMinute.collectAsState(initial = 20 * 60)
    val muteBlocked by settings.muteBlocked.collectAsState(initial = false)

    var notifPermission by remember { mutableStateOf(hasNotificationPermission(context)) }

    // The system dialog changes the permission; re-read it when we come back.
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                notifPermission = hasNotificationPermission(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var timeTarget by remember { mutableStateOf<String?>(null) } // which time row is picking
    var dayTarget by remember { mutableStateOf(false) } // weekly summary day

    LaunchedEffect(Unit) {
        if (!notifPermission) {
            dev.opensource.focusguard.ui.common.requestNotificationPermission(context)
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        PageHeader(
            title = "Usage Notifications",
            onBack = onBack,
            subtitle = "Reminders and alerts — all optional"
        )

        if (!notifPermission) {
            FgCard(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                Row(Modifier.padding(16.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    FgIcon(FgIcons.Warning, null, size = 20.dp)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Notifications are off", fontWeight = FontWeight.SemiBold)
                        Text(
                            "Android blocks all of these until you allow notifications for FocusGuard.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = {
                        dev.opensource.focusguard.ui.common.requestNotificationPermission(context)
                    }) { Text("Allow") }
                }
            }
        }

        SectionLabel("Ongoing")
        FgCard(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
            Column {
                ToggleRow(
                    title = "Usage Assistant",
                    subtitle = "Ongoing notification with your current app and today's total",
                    icon = FgIcons.User,
                    checked = assistantOn,
                    onCheckedChange = { scope.launch { settings.setAssistantOn(it) } }
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                ToggleRow(
                    title = "Total Time Message",
                    subtitle = "\"Total time today\" at most every 10 minutes",
                    icon = FgIcons.ChartBar,
                    checked = total10On,
                    onCheckedChange = { scope.launch { settings.setTotal10On(it) } }
                )
            }
        }

        SectionLabel("Reminders")
        FgCard(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
            Column {
                TimeToggleRow(
                    title = "Daily screen-time reminder",
                    subtitle = if (reminderMinute >= 0) "Every day at ${formatClock(reminderMinute)}" else "Off",
                    icon = FgIcons.Bell,
                    on = reminderMinute >= 0,
                    onChangeTime = { timeTarget = "reminder" },
                    onToggle = { on -> scope.launch { settings.setReminderMinute(if (on) 8 * 60 else -1) } }
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                TimeToggleRow(
                    title = "Weekly summary",
                    subtitle = "${dayLabel(weeklyDay)} at ${formatClock(weeklyMinute)}",
                    icon = FgIcons.Calendar,
                    on = weeklyOn,
                    onChangeTime = { timeTarget = "weekly" },
                    onChangeDay = { dayTarget = true },
                    onToggle = { on ->
                        scope.launch {
                            settings.setWeekly(on, weeklyDay, weeklyMinute)
                            Alarms.rearmWeekly(context, on, weeklyDay, weeklyMinute)
                        }
                    }
                )
            }
        }

        SectionLabel("While blocking")
        FgCard(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
            Column {
                ToggleRow(
                    title = "\"5 minutes left\" warning",
                    subtitle = "Once per app per day, computed on app switch — no timers",
                    icon = FgIcons.Stopwatch,
                    checked = fiveMinOn,
                    onCheckedChange = { scope.launch { settings.setFiveMinWarn(it) } }
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                ToggleRow(
                    title = "Block-hit alert",
                    subtitle = "Notify when a limit or schedule blocks an app",
                    icon = FgIcons.Block,
                    checked = blockHitOn,
                    onCheckedChange = { scope.launch { settings.setBlockHitOn(it) } }
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                ToggleRow(
                    title = "Schedule start/end alerts",
                    icon = FgIcons.Clock,
                    checked = scheduleAlertsOn,
                    onCheckedChange = { scope.launch { settings.setScheduleAlerts(it) } }
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                NavRow(
                    title = "Block Notifications",
                    subtitle = if (muteBlocked && muteListenerConnected(context)) {
                        "Active — blocked apps are muted"
                    } else if (muteBlocked) {
                        "Allowed but not enabled in Android yet — tap to open access settings"
                    } else {
                        "Mute notifications from blocked apps"
                    },
                    icon = FgIcons.EyeOff,
                    onClick = {
                        scope.launch { settings.setMuteBlocked(!muteBlocked) }
                        if (!muteBlocked) {
                            // First enable: make sure Android has granted access.
                            runCatching {
                                context.startActivity(
                                    android.content.Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
                                )
                            }
                        }
                    },
                    value = null
                )
            }
        }

        Spacer(Modifier.height(24.dp))
    }

    // Time pickers.
    timeTarget?.let { target ->
        val initial = when (target) {
            "reminder" -> reminderMinute.coerceAtLeast(8 * 60)
            else -> weeklyMinute
        }
        TimePickerDialogShared(
            title = "Pick a time",
            initial = initial,
            onPick = { minute ->
                scope.launch {
                    when (target) {
                        "reminder" -> {
                            settings.setReminderMinute(minute)
                        }
                        else -> {
                            settings.setWeekly(weeklyOn, weeklyDay, minute)
                            Alarms.rearmWeekly(context, weeklyOn, weeklyDay, minute)
                        }
                    }
                }
                timeTarget = null
            },
            onDismiss = { timeTarget = null }
        )
    }

    // Weekly summary day picker.
    if (dayTarget) {
        DayPickerDialog(
            onPick = { day ->
                scope.launch {
                    settings.setWeekly(weeklyOn, day, weeklyMinute)
                    Alarms.rearmWeekly(context, weeklyOn, day, weeklyMinute)
                }
                dayTarget = false
            },
            onDismiss = { dayTarget = false }
        )
    }
}

private fun muteListenerConnected(context: android.content.Context): Boolean =
    runCatching {
        (context.applicationContext as dev.opensource.focusguard.FocusApp).container.muteListenerConnected
    }.getOrDefault(false)

private fun dayLabel(dayMon0: Int): String =
    listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")
        .getOrElse(dayMon0) { "Monday" }

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)
    )
}

/** Toggle + tappable time subtitle (opens the picker on subtitle tap). */
@Composable
private fun TimeToggleRow(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    on: Boolean,
    onChangeTime: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onChangeDay: (() -> Unit)? = null
) {
    Column {
        ToggleRow(
            title = title,
            subtitle = subtitle,
            icon = icon,
            checked = on,
            onCheckedChange = onToggle
        )
        if (on) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 60.dp, end = 20.dp, bottom = 6.dp)
            ) {
                TextButton(onClick = onChangeTime) {
                    Text("Change time", color = MaterialTheme.colorScheme.primary)
                }
                if (onChangeDay != null) {
                    TextButton(onClick = onChangeDay) {
                        Text("Change day", color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

/** Which weekday the summary lands on; Monday 0 .. Sunday 6, matching Alarms. */
@Composable
private fun DayPickerDialog(onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Day of week") },
        text = {
            Column {
                listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")
                    .forEachIndexed { index, name ->
                        TextButton(
                            onClick = { onPick(index) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                name,
                                color = MaterialTheme.colorScheme.onSurface,
                                textAlign = TextAlign.Start
                            )
                        }
                    }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimePickerDialogShared(title: String, initial: Int, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    val state = rememberTimePickerState(
        initialHour = initial.coerceIn(0, 1439) / 60,
        initialMinute = initial.coerceIn(0, 1439) % 60,
        is24Hour = true
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { TimePicker(state = state) },
        confirmButton = {
            TextButton(onClick = { onPick(state.hour * 60 + state.minute) }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
