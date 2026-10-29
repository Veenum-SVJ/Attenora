package dev.opensource.attenora.ui.common

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import dev.opensource.attenora.enforcement.AttenoraAccessibilityService
import dev.opensource.attenora.enforcement.MonitorWatchdog
import dev.opensource.attenora.ui.icons.FgIcons
import dev.opensource.attenora.ui.theme.LocalFgSemantic
import dev.opensource.attenora.ui.theme.fg
import kotlinx.coroutines.delay

// ---- What is actually broken ------------------------------------------------

/**
 * The three ways Attenora stops doing its job. They are deliberately separate,
 * because the fix for each is different and telling someone "something is
 * wrong" is useless.
 */
enum class HealthIssue {
    /** Listed in Accessibility but not connected: the silent failure. */
    MONITOR_STALLED,

    /** Usage access missing: screen time is not counted at all. */
    USAGE_ACCESS,

    /** Switched off on purpose, so nothing is enforced. */
    MONITOR_OFF
}

/**
 * Priority order is the order of how silent the failure is. A stalled monitor
 * looks perfectly healthy everywhere else in the OS, so it wins; a deliberate
 * off switch is something the user just did; missing usage access is visible
 * as empty stats.
 */
fun healthIssue(
    usageGranted: Boolean,
    monitorEnabled: Boolean,
    monitorConnected: Boolean
): HealthIssue? = when {
    monitorEnabled && !monitorConnected -> HealthIssue.MONITOR_STALLED
    !usageGranted -> HealthIssue.USAGE_ACCESS
    !monitorEnabled -> HealthIssue.MONITOR_OFF
    else -> null
}

data class HealthCopy(val title: String, val body: String)

fun healthCopy(issue: HealthIssue): HealthCopy = when (issue) {
    HealthIssue.MONITOR_STALLED -> HealthCopy(
        title = "The monitor isn't running",
        body = "Limits and the reel counter are paused. Tap to fix it."
    )
    HealthIssue.USAGE_ACCESS -> HealthCopy(
        title = "Screen time isn't being counted",
        body = "Usage access is off. Tap to fix it."
    )
    HealthIssue.MONITOR_OFF -> HealthCopy(
        title = "Blocking is off",
        body = "The app monitor is turned off, so limits aren't enforced. Tap to fix it."
    )
}

// ---- Vendor guides ----------------------------------------------------------

/**
 * The phone-level steps that decide whether the App monitor survives being
 * locked. Text only: there is no public API for "autostart", and every vendor
 * hides it somewhere else.
 */
data class VendorGuide(
    val battery: String,
    val autostart: String,
    val recents: String
)

/**
 * Picks the guide from the manufacturer/brand strings rather than from
 * [android.os.Build] directly, so it can be unit-tested.
 */
fun vendorGuide(manufacturer: String?, brand: String?): VendorGuide {
    val key = "${manufacturer.orEmpty()} ${brand.orEmpty()}".lowercase()
    return when {
        // HyperOS/MIUI is the aggressive one: it kills the process from the
        // recents cleaner and from lock-screen cleanup, and records both as a
        // kill rather than a crash (see MonitorWatchdog).
        listOf("xiaomi", "redmi", "poco", "hyperos", "miui").any { key.contains(it) } ->
            VendorGuide(
                battery = "Battery saver → No restrictions",
                autostart = "Security → Manage apps → Attenora → Autostart",
                recents = "Open recents, long-press the Attenora card, then tap the " +
                    "padlock. This is what stops the phone's cleaner from killing it."
            )
        listOf("samsung").any { key.contains(it) } -> VendorGuide(
            battery = "Battery → Background usage limits → Never sleeping apps",
            autostart = "Usually not needed — the battery setting above is the one",
            recents = "Open recents, tap the Attenora icon, then Keep open."
        )
        listOf("oneplus", "oppo", "realme", "vivo", "iqoo", "nothing").any { key.contains(it) } ->
            VendorGuide(
                battery = "Battery → App battery management → Allow background activity",
                autostart = "App management → Attenora → Allow auto launch",
                recents = "Open recents and lock the Attenora card."
            )
        listOf("huawei", "honor").any { key.contains(it) } -> VendorGuide(
            battery = "Battery → App launch → Attenora → Manage manually",
            autostart = "Enable all three: auto-launch, secondary launch, run in background",
            recents = "Open recents and lock the Attenora card."
        )
        else -> VendorGuide(
            battery = "Battery → Battery optimization → Attenora → Don't optimize",
            autostart = "No autostart switch on most phones — the battery setting is enough",
            recents = "If your phone has a cleaner, lock Attenora in recents."
        )
    }
}

fun isBatteryUnrestricted(context: Context): Boolean = runCatching {
    context.getSystemService(PowerManager::class.java)
        ?.isIgnoringBatteryOptimizations(context.packageName) == true
}.getOrDefault(false)

/**
 * The system dialog for "don't optimize this app". Falls back to the list when
 * a vendor replaced the dialog with nothing.
 */
fun requestBatteryUnrestricted(context: Context) {
    val ask = Intent(
        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
        Uri.parse("package:${context.packageName}")
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (runCatching { context.startActivity(ask) }.isSuccess) return
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

/**
 * Best-effort autostart screen. Vendors expose no contract for it, so every
 * entry is a guess; when none of them resolve we land on app info, where the
 * battery and background rows live on most ROMs.
 */
fun openAutoStartSettings(context: Context) {
    val candidates = listOf(
        "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
        "com.samsung.android.lool" to "com.samsung.android.sm.ui.battery.BatteryActivity",
        "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
        "com.oplus.safecenter" to "com.oplus.safecenter.startupapp.StartupAppListActivity",
        "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
        "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
    )
    for ((pkg, cls) in candidates) {
        val intent = Intent()
            .setComponent(ComponentName(pkg, cls))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { context.startActivity(intent) }.isSuccess) return
    }
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

// ---- The banner on Home -----------------------------------------------------

/**
 * One line at the top of the home screen whenever the app is not doing its job.
 * It is deliberately not dismissible: a dismissed warning would leave the
 * screen looking healthy while nothing is enforced, which is the exact failure
 * this exists to expose.
 *
 * The monitor half is re-read on resume and once after the post-start settle,
 * because `a11yConnected` is not observable state - it is only true after the
 * system has bound the service, which can be a few seconds after launch.
 */
@Composable
fun HealthBanner(onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val container = LocalAppContainer.current
    var issue by remember { mutableStateOf<HealthIssue?>(null) }

    fun refresh() {
        // Before the bind has had a chance to complete, "not connected" means
        // "too early", not "broken". Never flash a false alarm.
        if (MonitorWatchdog.settleRemainingMs() > 0L) {
            issue = null
            return
        }
        issue = healthIssue(
            usageGranted = hasUsageAccess(context),
            monitorEnabled = isAccessibilityServiceEnabled(
                context, AttenoraAccessibilityService::class.java
            ),
            monitorConnected = container.a11yConnected
        )
    }

    LaunchedEffect(Unit) {
        refresh()
        val wait = MonitorWatchdog.settleRemainingMs()
        if (wait > 0L) {
            delay(wait + 400L)
            refresh()
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val showing = issue ?: return
    val copy = healthCopy(showing)
    val warning = MaterialTheme.fg.warning

    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onOpen)
            .padding(16.dp),
        verticalAlignment = Alignment.Top
    ) {
        FgIcon(FgIcons.Warning, null, size = 20.dp, tint = warning)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                copy.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(2.dp))
            Text(
                copy.body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(8.dp))
        FgIcon(
            FgIcons.ChevronRight,
            null,
            size = 18.dp,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ---- Onboarding step --------------------------------------------------------

/**
 * Fifth onboarding step. The service cannot be kept alive by anything inside
 * the app: the phone's own cleaner decides that, and it does not ask. Saying so
 * here is the difference between an app that works and one that silently stops
 * overnight.
 */
@Composable
fun KeepRunningStep() {
    val context = LocalContext.current
    val guide = remember {
        vendorGuide(android.os.Build.MANUFACTURER, android.os.Build.BRAND)
    }
    var unrestricted by remember { mutableStateOf(isBatteryUnrestricted(context)) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                unrestricted = isBatteryUnrestricted(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Text(
        "Keep it running",
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.SemiBold
    )
    Text(
        "Your phone stops apps in the background to save battery — Attenora " +
            "included. When that happens the monitor stops and limits and the reel " +
            "counter quietly stop working, even though Accessibility still shows " +
            "the service as on. These three settings prevent it.",
        style = MaterialTheme.typography.bodyMedium
    )

    KeepRunningBullet("1", "Battery", guide.battery)
    KeepRunningBullet("2", "Autostart", guide.autostart)
    KeepRunningBullet("3", "Recents", guide.recents)

    if (unrestricted) {
        Text(
            "✓ Battery optimization is off for Attenora",
            color = LocalFgSemantic.current.success,
            style = MaterialTheme.typography.bodySmall
        )
    } else {
        Button(onClick = { requestBatteryUnrestricted(context) }) {
            Text("Turn off battery optimization")
        }
    }
    Text(
        "You can come back to this any time in Settings → App health. " +
            "If the monitor ever stops, opening Accessibility and switching Attenora " +
            "off and on again restarts it.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun KeepRunningBullet(number: String, title: String, detail: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(
            number,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = LocalAccentColor.current
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
