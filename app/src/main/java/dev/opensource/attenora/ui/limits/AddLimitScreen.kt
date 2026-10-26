package dev.opensource.attenora.ui.limits

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
import androidx.compose.foundation.layout.heightIn
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
import dev.opensource.attenora.data.FocusSchedule
import dev.opensource.attenora.data.LimitType
import dev.opensource.attenora.data.TargetKind
import dev.opensource.attenora.data.UsageLimit
import dev.opensource.attenora.domain.Allowlist
import dev.opensource.attenora.domain.UrlMatcher
import dev.opensource.attenora.ui.common.AppIcon
import dev.opensource.attenora.ui.common.FgIcon
import dev.opensource.attenora.ui.common.LocalAppContainer
import dev.opensource.attenora.ui.common.PageHeader
import dev.opensource.attenora.ui.common.appLabel
import dev.opensource.attenora.ui.icons.FgIcons
import kotlinx.coroutines.launch
import java.time.format.TextStyle
import java.util.Locale

/** A chosen target entry: "APP:pkg" / "WEB:domain" / "CAT:<id>" / "ALL:" */
private data class Target(val kind: String, val id: String)

@Composable
fun AddLimitScreen(onBack: () -> Unit, initialPkg: String? = null) {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Arriving from an app's own page means the target is already decided and
    // the only sensible question left is how long — so open on a daily limit.
    val targets = remember {
        mutableStateListOf<Target>().apply {
            if (initialPkg != null) add(Target(TargetKind.APP, initialPkg))
        }
    }
    var pickerOpen by remember { mutableStateOf(false) }
    // Multi-select: daily, session and schedule can all be set in one save;
    // "Block permanently" is exclusive. Arriving from an app's own page still
    // preselects a daily limit, as before.
    var types by remember {
        mutableStateOf(
            if (initialPkg != null) setOf(LimitType.DAILY) else emptySet<String>()
        )
    }
    val toggleType: (String) -> Unit = { t ->
        types = if (t == LimitType.PERMANENT) {
            if (LimitType.PERMANENT in types) emptySet() else setOf(LimitType.PERMANENT)
        } else {
            val others = types - LimitType.PERMANENT
            if (t in others) others - t else others + t
        }
    }

    var dailyMinutes by remember { mutableStateOf("30") }
    var startMinute by remember { mutableStateOf(9 * 60) }
    var endMinute by remember { mutableStateOf(17 * 60) }
    var daysMask by remember { mutableStateOf(0x7F) }
    /** SESSION only: fixed length granted each time the app is opened. */
    var sessionMinutes by remember { mutableStateOf("30") }
    /** SESSION only: block length after a session runs out before reopening. */
    var cooldownMinutes by remember { mutableStateOf("30") }
    var name by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var saved by remember { mutableStateOf(false) }

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
                if (targets.isEmpty()) "Select apps or websites to limit"
                else    targets.joinToString(", ") { targetLabelOf(it, context) },
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
        Text(
            "Pick as many as you like — daily, session and schedule are all saved together. Block permanently stands alone.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
        )
        Spacer(Modifier.height(10.dp))

        // Each type's options render directly under the row that opened them,
        // above "Want to name your limit?" — nothing jumps to the bottom.
        TypeRow("Set a Daily Usage Limit", LimitType.DAILY in types) { toggleType(LimitType.DAILY) }
        if (LimitType.DAILY in types) {
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

        TypeRow("Block on a schedule", LimitType.SCHEDULE in types) { toggleType(LimitType.SCHEDULE) }
        if (LimitType.SCHEDULE in types) {
            ScheduleEditor(
                startMinute = startMinute,
                endMinute = endMinute,
                daysMask = daysMask,
                onStart = { startMinute = it },
                onEnd = { endMinute = it },
                onDays = { daysMask = it }
            )
        }

        TypeRow("Block permanently", LimitType.PERMANENT in types) { toggleType(LimitType.PERMANENT) }
        if (LimitType.PERMANENT in types) {
            Text(
                "This target will be blocked at all times until you remove the limit.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
            )
        }

        TypeRow("Set a fixed session limit", LimitType.SESSION in types) { toggleType(LimitType.SESSION) }
        if (LimitType.SESSION in types) {
            OutlinedTextField(
                value = sessionMinutes,
                onValueChange = { sessionMinutes = it.filter(Char::isDigit).take(4) },
                label = { Text("Session length (minutes per open)") },
                supportingText = {
                    Text("Fixed — no prompt appears when you open the app.")
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp)
            )
            OutlinedTextField(
                value = cooldownMinutes,
                onValueChange = { cooldownMinutes = it.filter(Char::isDigit).take(4) },
                label = { Text("Cooldown (minutes)") },
                supportingText = {
                    Text("Once the session is used up, the app stays blocked this long before it can be opened again.")
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp)
            )
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
                    val ts = types.toList()
                    error = when {
                        targets.isEmpty() -> "Pick at least one app, website, or category."
                        ts.isEmpty() -> "Choose how you want to limit usage."
                        LimitType.DAILY in ts && dailyMinutes.toIntOrNull()?.let { it <= 0 } != false ->
                            "Enter a daily limit in minutes."
                        LimitType.SESSION in ts && sessionMinutes.toIntOrNull()?.let { it <= 0 } != false ->
                            "Enter the session length in minutes."
                        LimitType.SESSION in ts && cooldownMinutes.toIntOrNull()?.let { it <= 0 } != false ->
                            "Enter a cooldown in minutes (at least 1)."
                        ts.any { it != LimitType.SCHEDULE } && targets.any { it.kind == TargetKind.ALL } ->
                            "\"Everything\" only works with Block on a schedule."
                        else -> null
                    }
                    if (error == null && ts.isNotEmpty()) {
                        // Adding a limit is not an edit: the challenge guards
                        // changes to limits that already exist, not new ones.
                        scope.launch {
                            for (t in ts) for (target in targets) {
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
                                                dailyLimitMs = (
                                                    (if (t == LimitType.SESSION) sessionMinutes.toLongOrNull()
                                                        else dailyMinutes.toLongOrNull()) ?: 30L
                                                    ) * 60_000L,
                                                cooldownMs = if (t == LimitType.SESSION)
                                                    (cooldownMinutes.toLongOrNull() ?: 30L) * 60_000L
                                                else 0L,
                                                sessionChoices = (sessionMinutes.toIntOrNull() ?: 30).toString(),
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
                            dev.opensource.attenora.enforcement.Alarms.rearmAll(
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
    context: android.content.Context
): String = when (target.kind) {
    TargetKind.APP -> appLabel(context, target.id)
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

/** Pill selector sized to the 48dp minimum so the target is the pill itself. */
@Composable
private fun ChoiceChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(50))
            .background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                else MaterialTheme.colorScheme.surfaceVariant
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center
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
    var tab by remember { mutableStateOf(0) } // 0 apps, 1 websites, 2 everything
    var search by remember { mutableStateOf("") }

    val installedApps = remember {
        val pm = context.packageManager
        val intent = android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_LAUNCHER)
        runCatching {
            pm.queryIntentActivities(intent, 0).map { it.activityInfo.packageName }.distinct()
                // Skip ourselves and the allowlist: enforcement bails out for
                // those packages, so a target on them would be a dead limit
                // that silently never fires (found on emulator, 2026-10-18).
                .filterNot { it == context.packageName || Allowlist.isAllowlisted(it) }
        }.getOrDefault(emptyList())
    }

    val sites by container.blocklist.collectAsState()
    // Seed today's usage synchronously before the first frame: with only the
    // async flow the picker opened with an empty usage map, so the list was
    // alphabetical until a later recomposition re-sorted it (observed on both
    // Pixel and Redmi, 2026-10-25 — it looked like visiting the Websites tab
    // and coming back "switched" it to most-used-first). The flow below keeps
    // the order live afterwards.
    val todaySeed = remember {
        kotlinx.coroutines.runBlocking {
            container.db.dailyUsageDao().forDay(container.usage.todayKey())
        }
    }
    val today by container.usage.todayFlow().collectAsState(initial = todaySeed)
    // Most-used first, so the apps that actually need a limit are at the top
    // instead of buried under an alphabetical list.
    val usedByPkg = remember(today) {
        today.groupBy { it.packageName }.mapValues { (_, rows) -> rows.sumOf { it.usedMs } }
    }

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
                listOf("Apps", "Websites", "Everything").forEachIndexed { i, label ->
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
            } else if (tab == 0) {
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
                            .sortedWith(
                                compareByDescending<String> { usedByPkg[it] ?: 0L }
                                    .thenBy { appLabel(context, it).lowercase() }
                            )
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
                        item {
                            TargetRow(
                                leading = { FgIcon(FgIcons.Shield, null, size = 24.dp) },
                                title = "Everything (block on a schedule)",
                                subtitle = "Block the whole phone in a window you choose.",
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
    subtitle: String? = null,
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
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
    }
}
