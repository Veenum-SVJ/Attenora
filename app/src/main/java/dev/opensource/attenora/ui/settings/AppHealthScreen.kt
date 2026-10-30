package dev.opensource.attenora.ui.settings

import android.content.Intent
import android.provider.Settings
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import dev.opensource.attenora.enforcement.AttenoraAccessibilityService
import dev.opensource.attenora.enforcement.MonitorWatchdog
import dev.opensource.attenora.ui.common.FgDivider
import dev.opensource.attenora.ui.common.FgGroup
import dev.opensource.attenora.ui.common.FgIcon
import dev.opensource.attenora.ui.common.NavRow
import dev.opensource.attenora.ui.common.PageHeader
import dev.opensource.attenora.ui.common.PermissionRow
import dev.opensource.attenora.ui.common.hasUsageAccess
import dev.opensource.attenora.ui.common.isAccessibilityServiceEnabled
import dev.opensource.attenora.ui.common.isBatteryUnrestricted
import dev.opensource.attenora.ui.common.openAccessibilitySettings
import dev.opensource.attenora.ui.common.openAutoStartSettings
import dev.opensource.attenora.ui.common.requestBatteryUnrestricted
import dev.opensource.attenora.ui.common.vendorGuide
import dev.opensource.attenora.ui.icons.FgIcons

/**
 * Everything that decides whether the app is actually doing its job, in one
 * place: the two permissions that are ours to ask for, plus the three
 * phone-level settings that decide whether the monitor survives being locked.
 *
 * This page exists because the Home banner needs somewhere honest to send
 * people, and because "turn it off and on in Accessibility" is not discoverable
 * anywhere inside Attenora. It stays reachable from Settings even when nothing
 * is wrong, so it can be read *before* the monitor dies rather than after.
 *
 * Note that a crashed accessibility service cannot be repaired from here (or
 * from anywhere in the app): Android skips a crashed component at bind time
 * until the package is updated, force-stopped, or re-enabled. So the copy below
 * asks for the toggle instead of promising a fix the app cannot deliver.
 */
@Composable
fun AppHealthScreen(onBack: () -> Unit) {
    val context = LocalContext.current

    var usageGranted by remember { mutableStateOf(hasUsageAccess(context)) }
    var serviceOn by remember {
        mutableStateOf(
            isAccessibilityServiceEnabled(context, AttenoraAccessibilityService::class.java)
        )
    }
    var stalled by remember { mutableStateOf(MonitorWatchdog.isStalled(context)) }
    var unrestricted by remember { mutableStateOf(isBatteryUnrestricted(context)) }

    fun refresh() {
        usageGranted = hasUsageAccess(context)
        serviceOn = isAccessibilityServiceEnabled(
            context, AttenoraAccessibilityService::class.java
        )
        stalled = MonitorWatchdog.isStalled(context)
        unrestricted = isBatteryUnrestricted(context)
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val guide = remember {
        vendorGuide(android.os.Build.MANUFACTURER, android.os.Build.BRAND)
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        PageHeader("App health", onBack = onBack)
        Spacer(Modifier.height(4.dp))

        FgGroup(Modifier.padding(horizontal = 16.dp)) {
            PermissionRow("Usage access", usageGranted, "Grant") {
                context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            }
            FgDivider(inset = RowInset)
            PermissionRow(
                title = "App monitor",
                granted = serviceOn && !stalled,
                actionLabel = if (stalled) "Restart" else "Open",
                note = when {
                    !serviceOn ->
                        "Off. Limits are not enforced and screen time is not counted."
                    stalled ->
                        "On, but not answering. Switch Attenora off and on in Accessibility."
                    else -> null
                },
                warn = stalled
            ) {
                openAccessibilitySettings(context, AttenoraAccessibilityService::class.java)
            }
            FgDivider(inset = 20.dp)
            NavRow(
                title = "Battery",
                onClick = { requestBatteryUnrestricted(context) },
                subtitle = if (unrestricted) {
                    "No restrictions — the system may still freeze it, but it is not " +
                        "optimised away."
                } else {
                    guide.battery
                },
                value = if (unrestricted) "Unrestricted" else "Restricted",
                icon = FgIcons.Battery
            )
            FgDivider(inset = RowInset)
            NavRow(
                title = "Autostart",
                onClick = { openAutoStartSettings(context) },
                subtitle = guide.autostart,
                icon = FgIcons.Refresh
            )
            FgDivider(inset = RowInset)
            GuideRow(
                icon = FgIcons.Lock,
                title = "Keep it in recents",
                detail = guide.recents
            )
        }

        HealthLabel("If it stops anyway")
        FgGroup(Modifier.padding(horizontal = 16.dp)) {
            Text(
                "Open Settings → Accessibility → Downloaded apps → Attenora and switch it " +
                    "off, then on. That forces Android to connect the service again; nothing " +
                    "inside Attenora can, because a service the system has marked as crashed " +
                    "is skipped until it is re-enabled. If the switch will not stay on, " +
                    "restart the phone.",
                Modifier.padding(20.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(40.dp))
    }
}

/** Read-only guide row: same leading rhythm as [NavRow], without a chevron. */
@Composable
private fun GuideRow(icon: ImageVector, title: String, detail: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.Top
    ) {
        FgIcon(icon, null, size = 22.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun HealthLabel(text: String) {
    Text(
        text,
        modifier = Modifier.padding(start = 20.dp, top = 20.dp, bottom = 8.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}
