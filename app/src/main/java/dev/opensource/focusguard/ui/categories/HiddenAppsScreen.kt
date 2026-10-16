package dev.opensource.focusguard.ui.categories

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.opensource.focusguard.data.HiddenItem
import dev.opensource.focusguard.data.HiddenKind
import dev.opensource.focusguard.domain.UrlMatcher
import dev.opensource.focusguard.ui.common.AppIcon
import dev.opensource.focusguard.ui.common.FgCard
import dev.opensource.focusguard.ui.common.FgIcon
import dev.opensource.focusguard.ui.common.LocalAppContainer
import dev.opensource.focusguard.ui.common.PageHeader
import dev.opensource.focusguard.ui.common.appLabel
import dev.opensource.focusguard.ui.icons.FgIcons
import kotlinx.coroutines.launch

/**
 * Hidden Apps: items excluded from totals and the Usage Stats screen.
 * Searchable, remove via ✕, add via the bottom button.
 */
@Composable
fun HiddenAppsScreen(onBack: () -> Unit) {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val hidden by container.db.hiddenItemDao().observeAll().collectAsState(initial = emptyList())
    var search by remember { mutableStateOf("") }
    var adding by remember { mutableStateOf(false) }

    val visible = hidden.filter {
        search.isBlank() ||
            (it.kind == HiddenKind.APP && appLabel(context, it.id).contains(search, true)) ||
            it.id.contains(search, true)
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            PageHeader(title = "Hidden Apps", onBack = onBack)

            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                placeholder = { Text("Search hidden items…") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 6.dp)
            )

            if (visible.isEmpty()) {
                Text(
                    if (hidden.isEmpty()) "Nothing hidden yet."
                    else "No matches.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
                )
            }

            LazyColumn {
                items(visible, key = { "${it.kind}:${it.id}" }) { item ->
                    FgCard(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (item.kind == HiddenKind.APP) {
                                AppIcon(item.id, size = 36.dp)
                            } else {
                                FgIcon(FgIcons.Globe, null, size = 24.dp)
                            }
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    if (item.kind == HiddenKind.APP) appLabel(context, item.id) else item.id,
                                    style = MaterialTheme.typography.bodyLarge
                                )
                                Text(
                                    if (item.kind == HiddenKind.APP) "App hidden from stats" else "Website hidden from stats",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            androidx.compose.material3.IconButton(onClick = {
                                scope.launch { container.db.hiddenItemDao().delete(item.kind, item.id) }
                            }) {
                                FgIcon(FgIcons.Close, "Stop hiding", size = 18.dp)
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(110.dp)) }
            }
        }

        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
        ) {
            Row(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.primary)
                    .clickable { adding = true }
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FgIcon(FgIcons.Plus, null, size = 20.dp)
                Spacer(Modifier.width(8.dp))
                Text("Add App or Website", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.SemiBold)
            }
        }
    }

    if (adding) {
        AddHiddenDialog(onDismiss = { adding = false })
    }
}

@Composable
private fun AddHiddenDialog(onDismiss: () -> Unit) {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var search by remember { mutableStateOf("") }
    var siteInput by remember { mutableStateOf("") }

    val installedApps = remember {
        val pm = context.packageManager
        val intent = android.content.Intent(android.content.Intent.ACTION_MAIN)
            .addCategory(android.content.Intent.CATEGORY_LAUNCHER)
        runCatching {
            pm.queryIntentActivities(intent, 0).map { it.activityInfo.packageName }.distinct()
                .filterNot { it == context.packageName }
        }.getOrDefault(emptyList())
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add to hidden") },
        text = {
            Column {
                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    placeholder = { Text("Search apps…") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Column(Modifier.heightIn(max = 300.dp)) {
                    installedApps
                        .filter { search.isBlank() || appLabel(context, it).contains(search, true) }
                        .sortedBy { appLabel(context, it).lowercase() }
                        .take(50)
                        .forEach { pkg ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        scope.launch {
                                            container.db.hiddenItemDao().insert(
                                                HiddenItem(HiddenKind.APP, pkg, System.currentTimeMillis())
                                            )
                                        }
                                    }
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                AppIcon(pkg, size = 30.dp)
                                Spacer(Modifier.width(10.dp))
                                Text(appLabel(context, pkg), style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                }
                Spacer(Modifier.height(10.dp))
                Text("Or hide a website", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = siteInput,
                        onValueChange = { siteInput = it },
                        placeholder = { Text("example.com") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(),
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(
                        enabled = UrlMatcher.looksLikeWebAddress(siteInput),
                        onClick = {
                            val domain = UrlMatcher.normalizeDomain(siteInput) ?: return@OutlinedButton
                            scope.launch {
                                container.db.hiddenItemDao().insert(
                                    HiddenItem(HiddenKind.WEBSITE, domain, System.currentTimeMillis())
                                )
                            }
                            siteInput = ""
                        }
                    ) { Text("Add") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } }
    )
}
