package dev.opensource.focusguard.ui.limits

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.opensource.focusguard.data.FocusSchedule
import dev.opensource.focusguard.data.LimitType
import dev.opensource.focusguard.data.TargetKind
import dev.opensource.focusguard.data.UsageLimit
import dev.opensource.focusguard.domain.UrlMatcher
import dev.opensource.focusguard.ui.common.AppIcon
import dev.opensource.focusguard.ui.common.FgIcon
import dev.opensource.focusguard.ui.common.LocalAppContainer
import dev.opensource.focusguard.ui.common.PageHeader
import dev.opensource.focusguard.ui.common.appLabel
import dev.opensource.focusguard.ui.icons.FgIcons
import kotlinx.coroutines.launch
import java.time.format.TextStyle
import java.util.Locale

/** A chosen target entry: "APP:pkg" / "WEB:domain" / "CAT:<id>" / "ALL:" */
private data class Target(val kind: String, val id: String)

@Composable
fun AddLimitScreen(onBack: () -> Unit) {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val targets = remember { mutableStateListOf<Target>() }
    var pickerOpen by remember { mutableStateOf(false) }
    var type by remember { mutableStateOf<String?>(null) }

    var dailyMinutes by remember { mutableStateOf("30") }
    var startMinute by remember { mutableStateOf(9 * 60) }
    var endMinute by remember { mutableStateOf(17 * 60) }
    var daysMask by remember { mutableStateOf(0x7F) }
    var sessionChoices by remember { mutableStateOf(setOf(15, 30, 60, 1440)) }
    var name by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var saved by remember { mutableStateOf(false) }

    val categories by container.categories.collectAsState()

    LaunchedEffect(saved) { if (saved) onBack() }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        PageHeader(title = "Add Limit", onBack = onBack)

        // Target selector row (screenshot: "Select apps… to limit →").
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 6.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surface)
                .clickable { pickerOpen = true }
                .padding(horizontal = 16.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                if (targets.isEmpty()) "Select apps, websites, or categories to limit"
                else targets.joinToString(", ") { targetLabelOf(it, categories, context) },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f)
            )
            FgIcon(FgIcons.ArrowRight, null, size = 20.dp)
        }

        Spacer(Modifier.height(20.dp))
        Text(
            "How do you want to limit usage?",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 20.dp)
        )
        Spacer(Modifier.height(10.dp))

        TypeRow("Set a Daily Usage Limit", type == LimitType.DAILY) { type = LimitType.DAILY }
        TypeRow("Block on a schedule", type == LimitType.SCHEDULE) { type = LimitType.SCHEDULE }
        TypeRow("Block permanently", type == LimitType.PERMANENT) { type = LimitType.PERMANENT }
        TypeRow("Enable variable session limits", type == LimitType.SESSION) { type = LimitType.SESSION }

        // ---- Type configuration ----
        when (type) {
            LimitType.DAILY -> {
                OutlinedTextField(
                    value = dailyMinutes,
                    onValueChange = { dailyMinutes = it.filter(Char::isDigit).take(5) },
                    label = { Text("Daily limit (minutes per target)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 8.dp)
                )
            }
            LimitType.SCHEDULE -> {
                ScheduleEditor(
                    startMinute = startMinute,
                    endMinute = endMinute,
                    daysMask = daysMask,
                    onStart = { startMinute = it },
                    onEnd = { endMinute = it },
                    onDays = { daysMask = it }
                )
            }
            LimitType.SESSION -> {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
                    Text(
                        "Session choices offered when you open the app:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(15 to "15m", 30 to "30m", 60 to "1h", 1440 to "Tomorrow").forEach { (v, label) ->
                            val checked = v in sessionChoices
                            ChoiceChip(label = label, selected = checked) {
                                sessionChoices = if (checked) sessionChoices - v else sessionChoices + v
                            }
                        }
                    }
                }
            }
            LimitType.PERMANENT -> {
                Text(
                    "This target will be blocked at all times until you remove the limit.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                )
            }
        }

        Spacer(Modifier.height(20.dp))
        Text(
            "Want to name your limit? (Optional)",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 20.dp)
        )
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = name,
            onValueChange = { name = it.take(40) },
            placeholder = { Text("Limit Name") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
        )

        error?.let {
            Spacer(Modifier.height(10.dp))
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 20.dp)
            )
        }

        Spacer(Modifier.height(28.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack, modifier = Modifier.weight(1f)) { Text("Cancel") }
            OutlinedButton(
                onClick = {
                    val t = type
                    error = when {
                        targets.isEmpty() -> "Pick at least one app, website, or category."
                        t == null -> "Choose how you want to limit usage."
                        t == LimitType.DAILY && dailyMinutes.toIntOrNull()?.let { it <= 0 } != false ->
                            "Enter a daily limit in minutes."
                        t == LimitType.SESSION && sessionChoices.isEmpty() ->
                            "Pick at least one session choice."
                        t != LimitType.SCHEDULE && targets.any { it.kind == TargetKind.ALL } ->
                            "\"Everything\" only works with Block on a schedule."
                        else -> null
                    }
                    if (error == null && t != null) {
                        scope.launch {
                            for (target in targets) {
                                when {
                                    target.kind == TargetKind.ALL && t == LimitType.SCHEDULE -> {
                                        container.db.focusScheduleDao().upsert(
                                            FocusSchedule(
                                                name = name.ifBlank { "Focus schedule" },
                                                startMinute = startMinute,
                                                endMinute = endMinute,
                                                daysMask = daysMask,
                                                blockAll = true
                                            )
                                        )
                                    }
                                    target.kind == TargetKind.ALL -> Unit
                                    else -> {
                                        container.db.usageLimitDao().upsert(
                                            UsageLimit(
                                                type = t,
                                                targetKind = target.kind,
                                                targetId = target.id,
                                                dailyLimitMs = (dailyMinutes.toLongOrNull() ?: 30L) * 60_000L,
                                                sessionChoices = sessionChoices.sorted().joinToString(","),
                                                daysMask = daysMask,
                                                startMinute = startMinute,
                                                endMinute = endMinute,
                                                name = name
                                            )
                                        )
                                    }
                                }
                            }
                            // Re-arm schedule alarms for any new focus schedule.
                            dev.opensource.focusguard.enforcement.Alarms.rearmAll(
                                context, container.db.focusScheduleDao().all()
                            )
                            saved = true
                        }
                    }
                },
                modifier = Modifier.weight(1f)
            ) { Text("Save") }
        }
        Spacer(Modifier.height(32.dp))
    }

    if (pickerOpen) {
        TargetPicker(
            selected = targets,
            onDone = { pickerOpen = false }
        )
    }
}

// ---- pieces ----------------------------------------------------------------

private fun targetLabelOf(
    target: Target,
    categories: List<dev.opensource.focusguard.data.Category>,
    context: android.content.Context
): String = when (target.kind) {
    TargetKind.APP -> appLabel(context, target.id)
    TargetKind.CATEGORY -> categories.firstOrNull { it.id.toString() == target.id }?.name ?: "Category"
    TargetKind.ALL -> "Everything"
    else -> target.id
}

@Composable
private fun TypeRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 5.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                else MaterialTheme.colorScheme.surface
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.weight(1f)
        )
        FgIcon(FgIcons.ChevronRight, null, size = 18.dp)
    }
}

@Composable
private fun ChoiceChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                else MaterialTheme.colorScheme.surfaceVariant
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun ScheduleEditor(
    startMinute: Int,
    endMinute: Int,
    daysMask: Int,
    onStart: (Int) -> Unit,
    onEnd: (Int) -> Unit,
    onDays: (Int) -> Unit
) {
    var showStart by remember { mutableStateOf(false) }
    var showEnd by remember { mutableStateOf(false) }

    Column(Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = { showStart = true }, modifier = Modifier.weight(1f)) {
                Text("Start ${clockLabel(startMinute)}")
            }
            OutlinedButton(onClick = { showEnd = true }, modifier = Modifier.weight(1f)) {
                Text("End ${clockLabel(endMinute)}")
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val names = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
            names.forEachIndexed { i, d ->
                val on = (daysMask shr i) and 1 == 1
                Box(
                    Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            if (on) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                            else MaterialTheme.colorScheme.surfaceVariant
                        )
                        .clickable { onDays(if (on) daysMask and (1 shl i).inv() else daysMask or (1 shl i)) }
                        .padding(horizontal = 8.dp, vertical = 8.dp)
                ) {
                    Text(
                        d.take(1),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (on) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            if (endMinute <= startMinute) "Runs overnight (ends the next day)." else "Same-day window.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    if (showStart) {
        TimePickerDialog(title = "Schedule start", initial = startMinute, onPick = {
            onStart(it); showStart = false
        }, onDismiss = { showStart = false })
    }
    if (showEnd) {
        TimePickerDialog(title = "Schedule end", initial = endMinute, onPick = {
            onEnd(it); showEnd = false
        }, onDismiss = { showEnd = false })
    }
}

private fun clockLabel(minute: Int): String {
    val m = ((minute % 1440) + 1440) % 1440
    return "%02d:%02d".format(m / 60, m % 60)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimePickerDialog(title: String, initial: Int, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    val state = rememberTimePickerState(
        initialHour = ((initial % 1440) + 1440) % 1440 / 60,
        initialMinute = ((initial % 60) + 60) % 60,
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

// ---- target picker ---------------------------------------------------------

@Composable
private fun TargetPicker(selected: MutableList<Target>, onDone: () -> Unit) {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    var tab by remember { mutableStateOf(0) } // 0 apps, 1 websites, 2 categories
    var search by remember { mutableStateOf("") }

    val installedApps = remember {
        val pm = context.packageManager
        val intent = android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_LAUNCHER)
        runCatching {
            pm.queryIntentActivities(intent, 0).map { it.activityInfo.packageName }.distinct()
                .filterNot { it == context.packageName }
        }.getOrDefault(emptyList())
    }

    val categories by container.categories.collectAsState()
    val sites by container.blocklist.collectAsState()

    androidx.compose.material3.Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(Modifier.fillMaxSize()) {
            PageHeader(title = "Choose targets", onBack = onDone)

            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf("Apps", "Websites", "Categories").forEachIndexed { i, label ->
                    ChoiceChip(label = label, selected = tab == i) { tab = i }
                }
            }

            if (tab == 1) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = search,
                        onValueChange = { search = it },
                        placeholder = { Text("Add a website…") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(enabled = UrlMatcher.looksLikeWebAddress(search), onClick = {
                        val domain = UrlMatcher.normalizeDomain(search) ?: return@OutlinedButton
                        if (Target(TargetKind.WEBSITE, domain) !in selected) {
                            selected.add(Target(TargetKind.WEBSITE, domain))
                        }
                        search = ""
                    }) { Text("Add") }
                }
            } else {
                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    placeholder = { Text("Search…") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 6.dp)
                )
            }

            LazyColumn(Modifier.weight(1f)) {
                when (tab) {
                    0 -> {
                        val filtered = installedApps
                            .filter {
                                search.isBlank() ||
                                    appLabel(context, it).contains(search, ignoreCase = true) ||
                                    it.contains(search, ignoreCase = true)
                            }
                            .sortedBy { appLabel(context, it).lowercase() }
                        items(filtered, key = { it }) { pkg ->
                            TargetRow(
                                leading = { AppIcon(pkg, size = 36.dp) },
                                title = appLabel(context, pkg),
                                checked = Target(TargetKind.APP, pkg) in selected,
                                onToggle = {
                                    val t = Target(TargetKind.APP, pkg)
                                    if (t in selected) selected.remove(t) else selected.add(t)
                                }
                            )
                        }
                    }
                    1 -> {
                        items(sites.sorted(), key = { it }) { domain ->
                            TargetRow(
                                leading = { FgIcon(FgIcons.Globe, null, size = 24.dp) },
                                title = domain,
                                checked = Target(TargetKind.WEBSITE, domain) in selected,
                                onToggle = {
                                    val t = Target(TargetKind.WEBSITE, domain)
                                    if (t in selected) selected.remove(t) else selected.add(t)
                                }
                            )
                        }
                    }
                    else -> {
                        items(categories, key = { it.id }) { cat ->
                            TargetRow(
                                leading = {
                                    Box(
                                        Modifier
                                            .size(28.dp)
                                            .clip(RoundedCornerShape(50))
                                            .background(androidx.compose.ui.graphics.Color(cat.color))
                                    )
                                },
                                title = cat.name,
                                checked = Target(TargetKind.CATEGORY, cat.id.toString()) in selected,
                                onToggle = {
                                    val t = Target(TargetKind.CATEGORY, cat.id.toString())
                                    if (t in selected) selected.remove(t) else selected.add(t)
                                }
                            )
                        }
                        item {
                            TargetRow(
                                leading = { FgIcon(FgIcons.Shield, null, size = 24.dp) },
                                title = "Everything (focus schedule)",
                                checked = Target(TargetKind.ALL, "") in selected,
                                onToggle = {
                                    val t = Target(TargetKind.ALL, "")
                                    if (t in selected) selected.remove(t) else selected.add(t)
                                }
                            )
                        }
                    }
                }
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "${selected.size} selected",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                OutlinedButton(onClick = { selected.clear() }) { Text("Clear") }
                OutlinedButton(onClick = onDone) { Text("Done") }
            }
        }
    }
}

@Composable
private fun TargetRow(
    leading: @Composable () -> Unit,
    title: String,
    checked: Boolean,
    onToggle: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        leading()
        Spacer(Modifier.width(14.dp))
        Text(
            title,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f)
        )
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
    }
}
