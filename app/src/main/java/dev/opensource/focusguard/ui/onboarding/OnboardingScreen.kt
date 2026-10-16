package dev.opensource.focusguard.ui.onboarding

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import dev.opensource.focusguard.enforcement.AppMonitorService
import dev.opensource.focusguard.ui.common.hasNotificationPermission
import dev.opensource.focusguard.ui.common.hasUsageAccess
import dev.opensource.focusguard.ui.common.isAccessibilityServiceEnabled
import kotlinx.coroutines.launch

private const val TOTAL_STEPS = 4

@Composable
fun OnboardingScreen() {
    val container = dev.opensource.focusguard.ui.common.LocalAppContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var step by rememberSaveable { mutableIntStateOf(0) }
    var usageGranted by remember { mutableStateOf(hasUsageAccess(context)) }
    var monitorOn by remember {
        mutableStateOf(isAccessibilityServiceEnabled(context, AppMonitorService::class.java))
    }
    var notifGranted by remember { mutableStateOf(hasNotificationPermission(context)) }
    var showDisclosure by remember { mutableStateOf(false) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                usageGranted = hasUsageAccess(context)
                monitorOn = isAccessibilityServiceEnabled(context, AppMonitorService::class.java)
                notifGranted = hasNotificationPermission(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> notifGranted = granted }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        LinearProgressIndicator(
            progress = { (step + 1) / TOTAL_STEPS.toFloat() },
            modifier = Modifier.fillMaxWidth()
        )

        when (step) {
            0 -> WelcomeStep()
            1 -> UsageAccessStep(usageGranted) {
                context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            }
            2 -> NotificationStep(notifGranted) { notifLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS) }
            else -> MonitorStep(monitorOn) { showDisclosure = true }
        }

        Spacer(Modifier.weight(1f, fill = false))

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (step > 0) {
                OutlinedButton(onClick = { step-- }) { Text("Back") }
            } else {
                Spacer(Modifier.size(1.dp))
            }
            if (step < TOTAL_STEPS - 1) {
                Button(onClick = { step++ }) { Text("Next") }
            } else {
                Button(onClick = {
                    scope.launch { container.settings.setOnboardingDone(true) }
                }) { Text(if (monitorOn) "Start using FocusGuard" else "Skip for now") }
            }
        }
    }

    if (showDisclosure) {
        AlertDialog(
            onDismissRequest = { showDisclosure = false },
            title = { Text("Accessibility disclosure") },
            text = {
                Text(
                    "Android requires this explanation before enabling an accessibility service.\n\n" +
                        "FocusGuard's \"App monitor\" service only reports which app comes to the " +
                        "foreground. That lets it count screen time and show your blocking screen " +
                        "at the right moment. It does not read what you type, does not read other " +
                        "apps' contents, and makes no network requests — FocusGuard has no internet " +
                        "access at all.\n\n" +
                        "You can disable it any time in Settings → Accessibility."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showDisclosure = false
                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }) { Text("Continue to settings") }
            },
            dismissButton = {
                TextButton(onClick = { showDisclosure = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun WelcomeStep() {
    StepTitle("Take back your focus")
    Text(
        "FocusGuard tracks how long you use each app, lets you set daily limits, " +
            "blocks distracting apps on schedule, and can block websites in your browser.",
        style = MaterialTheme.typography.bodyLarge
    )
    Text(
        "• Fully offline — no account, no ads, no analytics, no internet permission\n" +
            "• Uninstalling removes every trace: no background processes survive\n" +
            "• Battery-friendly: nothing polls; the app wakes only when you switch apps",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun UsageAccessStep(granted: Boolean, onGrant: () -> Unit) {
    StepTitle("Usage access")
    Text(
        "Android only shares app usage with apps that have \"Usage access\". " +
            "FocusGuard uses it to count screen time and enforce your limits.",
        style = MaterialTheme.typography.bodyMedium
    )
    if (granted) {
        Text("✓ Usage access granted", color = MaterialTheme.colorScheme.primary)
    } else {
        Button(onClick = onGrant) { Text("Grant usage access") }
    }
}

@Composable
private fun NotificationStep(granted: Boolean, onRequest: () -> Unit) {
    StepTitle("Notifications")
    if (android.os.Build.VERSION.SDK_INT < 33) {
        Text(
            "Nothing to do here — your Android version doesn't require notification permission.",
            style = MaterialTheme.typography.bodyMedium
        )
        return
    }
    Text(
        "FocusGuard can remind you of your daily screen time and tell you when a " +
            "focus schedule starts. Notifications are optional.",
        style = MaterialTheme.typography.bodyMedium
    )
    if (granted) {
        Text("✓ Notifications allowed", color = MaterialTheme.colorScheme.primary)
    } else {
        Button(onClick = onRequest) { Text("Allow notifications") }
    }
}

@Composable
private fun MonitorStep(enabled: Boolean, onEnable: () -> Unit) {
    StepTitle("App monitoring service")
    Text(
        "To show the blocking screen the moment a limit is reached, FocusGuard needs " +
            "its lightweight accessibility service. It only sees which app is open — " +
            "never what's on screen.",
        style = MaterialTheme.typography.bodyMedium
    )
    if (enabled) {
        Text("✓ App monitoring enabled", color = MaterialTheme.colorScheme.primary)
    } else {
        Button(onClick = onEnable) { Text("Enable app monitoring") }
        Spacer(Modifier.height(4.dp))
        Text(
            "You can finish setup without it, but blocking won't work until it's on.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun StepTitle(text: String) {
    Text(text, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
}
