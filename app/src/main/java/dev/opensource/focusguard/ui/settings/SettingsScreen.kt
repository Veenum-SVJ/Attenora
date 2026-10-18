package dev.opensource.focusguard.ui.settings

import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import dev.opensource.focusguard.data.BlockedSite
import dev.opensource.focusguard.data.ChallengeMode
import dev.opensource.focusguard.enforcement.Alarms
import dev.opensource.focusguard.enforcement.FocusGuardAccessibilityService
import dev.opensource.focusguard.security.PinManager
import dev.opensource.focusguard.ui.categories.HiddenAppsScreen
import dev.opensource.focusguard.ui.common.CollapsibleSectionCard
import dev.opensource.focusguard.ui.common.LocalAppContainer
import dev.opensource.focusguard.ui.common.NavRow
import dev.opensource.focusguard.ui.common.PageHeader
import dev.opensource.focusguard.ui.common.PermissionRow
import dev.opensource.focusguard.ui.common.SectionCard
import dev.opensource.focusguard.ui.icons.FgIcons
import dev.opensource.focusguard.ui.notifications.UsageNotificationsScreen
import dev.opensource.focusguard.ui.common.hasNotificationPermission
import dev.opensource.focusguard.ui.common.hasUsageAccess
import dev.opensource.focusguard.ui.common.isAccessibilityServiceEnabled
import dev.opensource.focusguard.util.exportCsv
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var usageGranted by remember { mutableStateOf(hasUsageAccess(context)) }
    var serviceOn by remember {
        mutableStateOf(isAccessibilityServiceEnabled(context, FocusGuardAccessibilityService::class.java))
    }
    var notifGranted by remember { mutableStateOf(hasNotificationPermission(context)) }

    // Collapsible sections. Permissions starts collapsed only when everything
    // is already granted — otherwise it stays open so the gaps are obvious.
    var permExpanded by rememberSaveable {
        mutableStateOf(!(usageGranted && serviceOn && notifGranted))
    }
    var appearanceExpanded by rememberSaveable { mutableStateOf(true) }
    var focusExpanded by rememberSaveable { mutableStateOf(true) }
    var webExpanded by rememberSaveable { mutableStateOf(false) }
    var lockExpanded by rememberSaveable { mutableStateOf(true) }
    var reminderExpanded by rememberSaveable { mutableStateOf(true) }
    var dataExpanded by rememberSaveable { mutableStateOf(true) }
    var batteryExpanded by rememberSaveable { mutableStateOf(true) }
    var aboutExpanded by rememberSaveable { mutableStateOf(true) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                usageGranted = hasUsageAccess(context)
                serviceOn = isAccessibilityServiceEnabled(context, FocusGuardAccessibilityService::class.java)
                notifGranted = hasNotificationPermission(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val webOn by container.webBlockingActive.collectAsState()
    val sites by container.db.blockedSiteDao().observeAll().collectAsState(initial = emptyList())
    val pinHash by container.settings.pinHash.collectAsState(initial = "")
    val biometricOn by container.settings.biometricEnabled.collectAsState(initial = true)
    val reminderMinute by container.settings.reminderMinute.collectAsState(initial = -1)
    val challengeMode by container.settings.challengeMode.collectAsState(initial = ChallengeMode.NONE)
    val challengeType by container.settings.challengeType.collectAsState(initial = "quote")
    val challengeText by container.settings.challengeText.collectAsState(initial = "")
    val hasPin = pinHash != null && pinHash != ""

    var newSite by rememberSaveable { mutableStateOf("") }
    var siteError by rememberSaveable { mutableStateOf<String?>(null) }
    var showPinDialog by remember { mutableStateOf(false) }
    var showRemovePinConfirm by remember { mutableStateOf(false) }
    var showWipeConfirm by remember { mutableStateOf(false) }
    var showReminderPicker by remember { mutableStateOf(false) }
    var showAppearance by remember { mutableStateOf(false) }
    var showUnlock by remember { mutableStateOf(false) }
    var showGrayscale by remember { mutableStateOf(false) }
    var showNotifications by remember { mutableStateOf(false) }
    var showHiddenApps by remember { mutableStateOf(false) }
    // null = not edited yet; shows the stored sentence until the user types.
    var customSentence by rememberSaveable { mutableStateOf<String?>(null) }

    if (showAppearance) {
        AppearanceScreen(onBack = { showAppearance = false })
        return
    }
    if (showUnlock) {
        UnlockSettingsScreen(onBack = { showUnlock = false })
        return
    }
    if (showGrayscale) {
        GrayscaleScreen(onBack = { showGrayscale = false })
        return
    }
    if (showNotifications) {
        UsageNotificationsScreen(onBack = { showNotifications = false })
        return
    }
    if (showHiddenApps) {
        HiddenAppsScreen(onBack = { showHiddenApps = false })
        return
    }

    val csvLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                runCatching { exportCsv(context, container.db, uri) }
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        // Back arrow lives here: pushed screens never show the tab bar.
        PageHeader(
            "Settings",
            onBack = onBack,
            subtitle = "Permissions, blocking, protection, data"
        )
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
        CollapsibleSectionCard(
            "Permissions",
            expanded = permExpanded,
            onToggle = { permExpanded = !permExpanded },
            trailing = {
                if (usageGranted && serviceOn && notifGranted) {
                    Text(
                        "All granted",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        ) {
            PermissionRow("Usage access", usageGranted, "Grant") {
                context.startActivity(Intent(AndroidSettings.ACTION_USAGE_ACCESS_SETTINGS))
            }
            PermissionRow("FocusGuard accessibility (blocking, websites, in-app)", serviceOn, "Open") {
                context.startActivity(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                PermissionRow("Notifications", notifGranted, "Allow") {
                    dev.opensource.focusguard.ui.common.requestNotificationPermission(context)
                }
            }
            Text(
                "Grayscale needs one extra adb grant — it lives under Focus tools.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        CollapsibleSectionCard(
            "Appearance",
            expanded = appearanceExpanded,
            onToggle = { appearanceExpanded = !appearanceExpanded }
        ) {
            Row(
                Modifier.fillMaxWidth().clickable { showAppearance = true },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Theme, launcher icon & shapes")
                    Text(
                        "System/dark/light, home-screen icon color, in-app icon accent, " +
                            "nav chips, and per-app icon shape.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text("Open ▸", color = MaterialTheme.colorScheme.primary)
            }
        }

        CollapsibleSectionCard(
            "Focus tools",
            expanded = focusExpanded,
            onToggle = { focusExpanded = !focusExpanded }
        ) {
            Row(
                Modifier.fillMaxWidth().clickable { showUnlock = true },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Unlock challenge & quota")
                    Text(
                        "Delay, math/typing/intent challenges and a daily unlock cap " +
                            "at the moment of weakness.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text("Open ▸", color = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.size(12.dp))
            Row(
                Modifier.fillMaxWidth().clickable { showGrayscale = true },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Grayscale apps")
                    Text(
                        "Open selected apps in black & white. Needs one adb grant — " +
                            "the command is inside.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text("Open ▸", color = MaterialTheme.colorScheme.primary)
            }
        }

        CollapsibleSectionCard(
            "Website blocking",
            expanded = webExpanded,
            onToggle = { webExpanded = !webExpanded }
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Enable website blocking")
                    Text(
                        "Needs the FocusGuard accessibility service in Permissions.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = webOn,
                    onCheckedChange = { on -> scope.launch { container.settings.setWebBlocking(on) } }
                )
            }
            Spacer(Modifier.size(12.dp))
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
                }) {
                    Icon(Icons.Default.Add, contentDescription = "Add site", modifier = Modifier.size(18.dp))
                }
            }
            siteError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Spacer(Modifier.size(8.dp))
            sites.forEach { site ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(site.domain, Modifier.weight(1f))
                    IconButton(onClick = {
                        scope.launch { container.db.blockedSiteDao().delete(site.domain) }
                    }) {
                        Icon(Icons.Default.Delete, contentDescription = "Remove ${site.domain}")
                    }
                }
            }
        }

        CollapsibleSectionCard(
            "App lock",
            expanded = lockExpanded,
            onToggle = { lockExpanded = !lockExpanded }
        ) {
            if (pinHash == null || pinHash == "") {
                Text(
                    "Protect your limits and settings with a PIN.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.size(8.dp))
                if (pinHash == "") {
                    Text("Loading…", style = MaterialTheme.typography.bodySmall)
                } else {
                    Button(onClick = { showPinDialog = true }) { Text("Set PIN") }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("PIN set", Modifier.weight(1f))
                    TextButton(onClick = { showPinDialog = true }) { Text("Change") }
                    TextButton(onClick = { showRemovePinConfirm = true }) { Text("Remove") }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Biometric / screen-lock unlock")
                        Text(
                            "Faster entry when unlocking with fingerprint or device credentials.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = biometricOn,
                        onCheckedChange = { on -> scope.launch { container.settings.setBiometric(on) } }
                    )
                }
            }

            // ---- Protect limit changes (gates every add/edit/delete) ----
            Spacer(Modifier.size(14.dp))
            Text(
                "Protect limit changes",
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                "Ask before anyone can add, edit or delete a usage limit.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.size(8.dp))
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
                Spacer(Modifier.size(6.dp))
                Text(
                    "Set the app lock PIN first to be able to pick PIN protection.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (challengeMode == ChallengeMode.CHALLENGE && challengeType != "math") {
                Spacer(Modifier.size(10.dp))
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
                Text(
                    "It must be retyped exactly before any limit change goes through. " +
                        "Leave it alone to keep the built-in sentence.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        CollapsibleSectionCard(
            "Daily reminder",
            expanded = reminderExpanded,
            onToggle = { reminderExpanded = !reminderExpanded }
        ) {
            if (reminderMinute in 0..1439) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Daily at ${"%02d:%02d".format(reminderMinute / 60, reminderMinute % 60)}",
                        Modifier.weight(1f)
                    )
                    TextButton(onClick = { showReminderPicker = true }) { Text("Change") }
                    TextButton(onClick = {
                        scope.launch { container.settings.setReminderMinute(-1) }
                    }) { Text("Off") }
                }
            } else {
                Text(
                    "Get a notification with your total screen time.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.size(8.dp))
                OutlinedButton(onClick = { showReminderPicker = true }) { Text("Set reminder time") }
            }
            Spacer(Modifier.size(12.dp))
            NavRow(
                title = "Usage notifications",
                subtitle = "Weekly summary, morning & night nudges, block alerts",
                icon = FgIcons.Bell,
                onClick = { showNotifications = true }
            )
        }

        CollapsibleSectionCard(
            "Data",
            expanded = dataExpanded,
            onToggle = { dataExpanded = !dataExpanded }
        ) {
            NavRow(
                title = "Hidden apps",
                subtitle = "Apps or websites excluded from your totals",
                icon = FgIcons.EyeOff,
                onClick = { showHiddenApps = true }
            )
            Spacer(Modifier.size(8.dp))
            OutlinedButton(
                onClick = {
                    val name = "focusguard-usage-${java.time.LocalDate.now()}.csv"
                    csvLauncher.launch(name)
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Export last 90 days as CSV") }
            Spacer(Modifier.size(8.dp))
            TextButton(onClick = { showWipeConfirm = true }) {
                Text("Erase all FocusGuard data", color = MaterialTheme.colorScheme.error)
            }
        }

        CollapsibleSectionCard(
            "Battery & background",
            expanded = batteryExpanded,
            onToggle = { batteryExpanded = !batteryExpanded }
        ) {
            Text(
                "FocusGuard is designed to be invisible to your battery: no polling, no " +
                    "background service of its own, no exact alarms, no network activity.\n\n" +
                    "If blocking ever stops firing on your device (some Xiaomi/HyperOS " +
                    "settings kill background components), allow FocusGuard in:\n" +
                    "• Settings → Apps → FocusGuard → Battery saver → No restrictions\n" +
                    "• Settings → Apps → Autorun → enable FocusGuard\n\n" +
                    "Those switches only let Android keep the accessibility service alive; " +
                    "FocusGuard itself still does nothing in the background.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        CollapsibleSectionCard(
            "About",
            expanded = aboutExpanded,
            onToggle = { aboutExpanded = !aboutExpanded }
        ) {
            val version = remember {
                runCatching {
                    context.packageManager.getPackageInfo(context.packageName, 0).versionName
                }.getOrNull() ?: "1.0.0"
            }
            Text("FocusGuard v$version", style = MaterialTheme.typography.bodyMedium)
            Text(
                "Open source · works fully offline · no ads, no tracking, no accounts. " +
                    "Uninstalling removes every trace of your data.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        }
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
                }) { Text("Erase everything", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { showWipeConfirm = false }) { Text("Cancel") } }
        )
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

/** Small pill selector used by "Protect limit changes". */
@Composable
private fun ChoicePill(label: String, active: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
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

@Composable
private fun PinSetupDialog(
    onDismiss: () -> Unit,
    onSaved: (hash: String, salt: String) -> Unit
) {
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
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
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
