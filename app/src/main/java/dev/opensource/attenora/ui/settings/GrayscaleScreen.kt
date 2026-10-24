package dev.opensource.attenora.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import dev.opensource.attenora.ui.common.AppIcon
import dev.opensource.attenora.ui.common.FgCard
import dev.opensource.attenora.ui.common.LocalAppContainer
import dev.opensource.attenora.ui.common.PageHeader
import dev.opensource.attenora.ui.common.appLabel
import dev.opensource.attenora.util.Grayscale
import kotlinx.coroutines.launch

/**
 * Settings → Grayscale (Curbox port): pick apps that open in black & white.
 * Needs one WRITE_SECURE_SETTINGS grant — shown as a card with the exact adb
 * command until the permission is in place (no Shizuku dependency).
 */
@Composable
fun GrayscaleScreen(onBack: () -> Unit) {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    val selected by container.settings.grayscaleApps.collectAsState(initial = emptySet())
    var granted by remember { mutableStateOf(Grayscale.hasPermission(context)) }
    var search by remember { mutableStateOf("") }

    // The grant happens outside the app — re-check on return.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                granted = Grayscale.hasPermission(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val installed = remember {
        val intent = android.content.Intent(android.content.Intent.ACTION_MAIN)
            .addCategory(android.content.Intent.CATEGORY_LAUNCHER)
        runCatching {
            context.packageManager.queryIntentActivities(intent, 0)
                .map { it.activityInfo.packageName }
                .distinct()
                .filter { it != context.packageName }
                .sortedBy { appLabel(context, it).lowercase() }
        }.getOrDefault(emptyList())
    }

    val visible = installed.filter {
        search.isBlank() || appLabel(context, it).contains(search, ignoreCase = true) ||
            it.contains(search, ignoreCase = true)
    }

    Column(Modifier.fillMaxSize()) {
        PageHeader(
            "Grayscale",
            onBack = onBack,
            subtitle = "Selected apps open in black & white"
        )

        if (!granted) {
            FgCard(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                Column(Modifier.padding(14.dp)) {
                    Text(
                        "One-time grant required",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Run this once from a computer with adb — then the switches below work:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    val command =
                        "adb shell pm grant dev.opensource.attenora android.permission.WRITE_SECURE_SETTINGS"
                    Text(
                        command,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                    )
                    Row {
                        TextButton(onClick = {
                            clipboard.setText(AnnotatedString(command))
                        }) { Text("Copy command") }
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(onClick = {
                            granted = Grayscale.hasPermission(context)
                        }) { Text("I ran it") }
                    }
                }
            }
        }

        OutlinedTextField(
            value = search,
            onValueChange = { search = it },
            placeholder = { Text("Search apps…") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 6.dp)
        )

        if (visible.isEmpty()) {
            Text(
                if (installed.isEmpty()) "No launcher apps found."
                else "No matches.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
            )
        }

        LazyColumn(Modifier.fillMaxSize()) {
            items(visible, key = { it }) { pkg ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AppIcon(pkg, size = 36.dp)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(appLabel(context, pkg), style = MaterialTheme.typography.bodyLarge)
                        if (pkg in selected && !granted) {
                            Text(
                                "Waiting for the adb grant above",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Switch(
                        checked = pkg in selected,
                        enabled = granted,
                        onCheckedChange = { on ->
                            scope.launch { container.settings.setGrayscaleApp(pkg, on) }
                        }
                    )
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}