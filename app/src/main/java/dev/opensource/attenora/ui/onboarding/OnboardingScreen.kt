package dev.opensource.attenora.ui.onboarding

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import dev.opensource.attenora.enforcement.AttenoraAccessibilityService
import dev.opensource.attenora.ui.common.FgCard
import dev.opensource.attenora.ui.common.FgIcon
import dev.opensource.attenora.ui.common.LocalAccentColor
import dev.opensource.attenora.ui.common.hasNotificationPermission
import dev.opensource.attenora.ui.common.hasUsageAccess
import dev.opensource.attenora.ui.common.isAccessibilityServiceEnabled
import dev.opensource.attenora.ui.common.openAccessibilitySettings
import dev.opensource.attenora.ui.common.requestNotificationPermission
import dev.opensource.attenora.ui.icons.FgIcons
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private const val TOTAL_STEPS = 4

@Composable
fun OnboardingScreen() {
    val container = dev.opensource.attenora.ui.common.LocalAppContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var step by rememberSaveable { mutableIntStateOf(0) }
    var usageGranted by remember { mutableStateOf(hasUsageAccess(context)) }
    var monitorOn by remember {
        mutableStateOf(isAccessibilityServiceEnabled(context, AttenoraAccessibilityService::class.java))
    }
    var notifGranted by remember { mutableStateOf(hasNotificationPermission(context)) }
    var showDisclosure by remember { mutableStateOf(false) }

    // Restore the last step after process death, then keep it mirrored in
    // DataStore so a mid-setup kill doesn't send the user back to step 1.
    LaunchedEffect(Unit) {
        val saved = runCatching { container.settings.onboardingStep.first() }.getOrDefault(0)
        if (saved in 1 until TOTAL_STEPS) step = saved
    }
    LaunchedEffect(step) {
        runCatching { container.settings.setOnboardingStep(step) }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                usageGranted = hasUsageAccess(context)
                monitorOn = isAccessibilityServiceEnabled(context, AttenoraAccessibilityService::class.java)
                notifGranted = hasNotificationPermission(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding() // keeps the progress bar out of the status bar / cutout
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
            2 -> NotificationStep(notifGranted) {
                requestNotificationPermission(context)
            }
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
                }) { Text(if (monitorOn) "Start using Attenora" else "Skip for now") }
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
                        "Attenora uses one service to count screen time and show your blocking " +
                        "screen, check browser address bars for website blocking, and hide the " +
                        "in-app elements you chose (like Shorts or Reels). It reads nothing from " +
                        "keyboards or passwords and makes no network requests — Attenora has no " +
                        "internet access at all.\n\n" +
                        "You can disable it any time in Settings → Accessibility."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showDisclosure = false
                    openAccessibilitySettings(context, AttenoraAccessibilityService::class.java)
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
    val context = LocalContext.current
    val accent = LocalAccentColor.current
    val version = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull()?.let { "v$it" } ?: "v1.1.0"
    }
    StepTitle("Welcome to Attenora")
    Text(
        "Attenora $version",
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(4.dp))

    FgCard {
        Column(Modifier.padding(20.dp)) {
            Box(
                Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(accent.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center
            ) {
                FgIcon(FgIcons.ChartBar, null, size = 28.dp, tint = accent)
            }
            Spacer(Modifier.height(14.dp))
            Text(
                "Take back your time",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "See where your screen time goes, cap the apps that eat your day, " +
                    "and hide the parts of apps that pull you in — all on your phone.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    Spacer(Modifier.height(4.dp))
    WelcomeBullet(FgIcons.WifiOff, "100% offline — no internet, no account, no ads")
    WelcomeBullet(FgIcons.Shield, "Blocks apps, websites and in-app feeds")
    WelcomeBullet(FgIcons.ChartBar, "Simple daily stats and trends, kept on-device")
}

@Composable
private fun WelcomeBullet(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FgIcon(icon, null, size = 20.dp, tint = LocalAccentColor.current)
        Spacer(Modifier.size(12.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun UsageAccessStep(granted: Boolean, onGrant: () -> Unit) {
    StepTitle("Usage access")
    Text(
        "Android only shares app usage with apps that have \"Usage access\". " +
            "Attenora uses it to count screen time and enforce your limits.",
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
        "Attenora can remind you of your daily screen time and tell you when a " +
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
    StepTitle("Accessibility service")
    Text(
        "To show the blocking screen the moment a limit is reached, Attenora needs " +
            "its accessibility service. The same service powers website blocking and " +
            "in-app blocking, so you only enable it once.",
        style = MaterialTheme.typography.bodyMedium
    )
    if (enabled) {
        Text("✓ Accessibility enabled", color = MaterialTheme.colorScheme.primary)
    } else {
        Button(onClick = onEnable) { Text("Enable accessibility") }
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
