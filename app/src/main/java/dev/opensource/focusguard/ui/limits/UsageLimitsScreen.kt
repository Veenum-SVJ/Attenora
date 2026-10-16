package dev.opensource.focusguard.ui.limits

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.opensource.focusguard.data.FocusSchedule
import dev.opensource.focusguard.data.LimitType
import dev.opensource.focusguard.data.TargetKind
import dev.opensource.focusguard.data.UsageLimit
import dev.opensource.focusguard.domain.WeekEngine
import dev.opensource.focusguard.ui.common.AppIcon
import dev.opensource.focusguard.ui.common.FgCard
import dev.opensource.focusguard.ui.common.FgIcon
import dev.opensource.focusguard.ui.common.LocalAppContainer
import dev.opensource.focusguard.ui.common.PageHeader
import dev.opensource.focusguard.ui.common.appLabel
import dev.opensource.focusguard.ui.common.daysSummary
import dev.opensource.focusguard.ui.common.formatClock
import dev.opensource.focusguard.ui.common.formatDuration
import dev.opensource.focusguard.ui.icons.FgIcons
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

private data class LimitStatus(val usedMs: Long, val limitMs: Long, val blocked: Boolean) {
    val remainingMs: Long get() = (limitMs - usedMs).coerceAtLeast(0L)
}

@Composable
fun UsageLimitsScreen(query: String, onAddLimit: () -> Unit, onBlockKeywords: () -> Unit) {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val gate = rememberChallengeGate()

    val limits by container.enforcement.limits.collectAsState()
    val schedules by container.enforcement.schedules.collectAsState()
    val categories by container.categories.collectAsState()
    val blockAllUntil by container.enforcement.blockAllUntil.collectAsState()
    val grants by container.enforcement.sessionGrants.collectAsState()

    var statuses by remember { mutableStateOf<Map<Long, LimitStatus>>(emptyMap()) }
    var editingMinutes by remember { mutableStateOf<UsageLimit?>(null) }

    var sDaily by rememberSaveable { mutableStateOf(true) }
    var sSession by rememberSaveable { mutableStateOf(true) }
    var sSchedule by rememberSaveable { mutableStateOf(true) }
    var sPermanent by rememberSaveable { mutableStateOf(true) }
    var editMode by rememberSaveable { mutableStateOf(false) }
    var fabExpanded by rememberSaveable { mutableStateOf(false) }

    // Recompute countdown statuses periodically while the screen is visible.
    LaunchedEffect(limits, schedules) {
        while (true) {
            val map = HashMap<Long, LimitStatus>(limits.size)
            for (limit in limits) {
                if (limit.type != LimitType.DAILY) continue
                val used = when (limit.targetKind) {
                    TargetKind.WEBSITE -> container.usage.websiteMsToday(limit.targetId)
                    TargetKind.CATEGORY -> {
                        val id = limit.targetId.toLongOrNull() ?: 0L
                        val pkgs = container.enforcement.categoryMembers.value
                            .filter { it.categoryId == id && it.key.startsWith("app:") }
                            .map { it.key.removePrefix("app:") }
                        pkgs.sumOf { container.usage.usedMsToday(it) }
                    }
                    else -> container.usage.usedMsToday(limit.targetId)
                }
                map[limit.id] = LimitStatus(used, limit.dailyLimitMs, used >= limit.dailyLimitMs)
            }
            statuses = map
            delay(15_000L)
        }
    }

    fun matchesFilter(l: UsageLimit): Boolean {
        if (query.isBlank()) return true
        val label = targetLabel(l.targetKind, l.targetId, categories, context).lowercase()
        val name = l.name.lowercase()
        val q = query.trim().lowercase()
        return label.contains(q) || name.contains(q)
    }

    val daily = limits.filter { it.type == LimitType.DAILY && matchesFilter(it) }
    val session = limits.filter { it.type == LimitType.SESSION && matchesFilter(it) }
    val typedSchedules = limits.filter { it.type == LimitType.SCHEDULE && matchesFilter(it) }
    val permanent = limits.filter { it.type == LimitType.PERMANENT && matchesFilter(it) }

    val now = System.currentTimeMillis()
    val blockActive = blockAllUntil > now

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            PageHeader(
                title = "Usage Limits",
                trailing = {
                    Text(
                        if (editMode) "Done" else "Edit",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { editMode = !editMode }
                            .padding(horizontal = 8.dp, vertical = 6.dp)
                    )
                }
            )
        }

        // ---- Block Now card ----
        item {
            FgCard(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FgIcon(FgIcons.Stopwatch, null, size = 20.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "Block Now",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.weight(1f))
                        if (blockActive) {
                            Text(
                                "until ${formatClock(blockAllUntil.let { java.time.Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).hour * 60 + java.time.Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).minute })}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (blockActive) "Everything except your allowlist is blocked right now."
                        else "Start a blocking session without setting up a limit.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    if (blockActive) {
                        OutlinedButton(
                            onClick = {
                                gate.request {
                                    scope.launch { container.settings.setBlockAllUntil(0L) }
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("End session") }
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(
                                "15m" to 15 * 60_000L,
                                "30m" to 30 * 60_000L,
                                "1h" to 60 * 60_000L,
                                "Until midnight" to -1L
                            ).forEach { (label, ms) ->
                                OutlinedButton(
                                    onClick = {
                                        val until = if (ms < 0) {
                                            LocalDate.now().plusDays(1)
                                                .atStartOfDay(ZoneId.systemDefault())
                                                .toInstant().toEpochMilli()
                                        } else System.currentTimeMillis() + ms
                                        scope.launch { container.settings.setBlockAllUntil(until) }
                                    },
                                    modifier = Modifier.weight(1f)
                                ) { Text(label, maxLines = 1) }
                            }
                        }
                    }
                }
            }
        }

        // ---- Sections ----
        section("Limited Daily Usage", daily.size, sDaily, { sDaily = !sDaily }) {
            daily.forEach { limit ->
                item(key = "daily-${limit.id}") {
                    DailyRow(
                        limit = limit,
                        status = statuses[limit.id],
                        categories = categories,
                        editMode = editMode,
                        onToggle = { on ->
                            gate.request {
                                scope.launch { container.db.usageLimitDao().setEnabled(limit.id, on) }
                            }
                        },
                        onEdit = { editingMinutes = limit },
                        onDelete = {
                            gate.request {
                                scope.launch { container.db.usageLimitDao().delete(limit.id) }
                            }
                        }
                    )
                }
            }
        }

        section("Variable Session Limits Enabled", session.size, sSession, { sSession = !sSession }) {
            session.forEach { limit ->
                item(key = "sess-${limit.id}") {
                    SimpleLimitRow(
                        icon = FgIcons.Stopwatch,
                        title = targetLabel(limit.targetKind, limit.targetId, categories, context),
                        subtitle = grants[limit.targetId]?.let { until ->
                            if (until > System.currentTimeMillis()) "Session granted until ${
                                formatClock(java.time.Instant.ofEpochMilli(until).atZone(ZoneId.systemDefault()).hour * 60 +
                                    java.time.Instant.ofEpochMilli(until).atZone(ZoneId.systemDefault()).minute)
                            }" else null
                        } ?: "Asks how long each time you open it",
                        enabled = limit.enabled,
                        editMode = editMode,
                        onToggle = { on ->
                            gate.request {
                                scope.launch { container.db.usageLimitDao().setEnabled(limit.id, on) }
                            }
                        },
                        onDelete = {
                            gate.request {
                                scope.launch { container.db.usageLimitDao().delete(limit.id) }
                            }
                        }
                    )
                }
            }
        }

        section("Scheduled Blocks", typedSchedules.size + schedules.size, sSchedule, { sSchedule = !sSchedule }) {
            typedSchedules.forEach { limit ->
                item(key = "sched-${limit.id}") {
                    SimpleLimitRow(
                        icon = FgIcons.Clock,
                        title = limit.name.ifBlank {
                            targetLabel(limit.targetKind, limit.targetId, categories, context)
                        },
                        subtitle = "${formatClock(limit.startMinute)} – ${
                            if (limit.endMinute <= limit.startMinute) "+" else ""
                        }${formatClock(limit.endMinute)} · ${daysSummary(limit.daysMask)}",
                        enabled = limit.enabled,
                        editMode = editMode,
                        onToggle = { on ->
                            gate.request {
                                scope.launch { container.db.usageLimitDao().setEnabled(limit.id, on) }
                            }
                        },
                        onDelete = {
                            gate.request {
                                scope.launch { container.db.usageLimitDao().delete(limit.id) }
                            }
                        }
                    )
                }
            }
            schedules.forEach { schedule ->
                item(key = "focus-${schedule.id}") {
                    SimpleLimitRow(
                        icon = FgIcons.Shield,
                        title = schedule.name,
                        subtitle = "${formatClock(schedule.startMinute)} – ${
                            if (schedule.endMinute <= schedule.startMinute) "+" else ""
                        }${formatClock(schedule.endMinute)} · ${daysSummary(schedule.daysMask)}" +
                            if (schedule.blockAll) " · everything" else "",
                        enabled = schedule.enabled,
                        editMode = editMode,
                        onToggle = { on ->
                            gate.request {
                                scope.launch { container.db.focusScheduleDao().setEnabled(schedule.id, on) }
                            }
                        },
                        onDelete = {
                            gate.request {
                                scope.launch { container.db.focusScheduleDao().delete(schedule.id) }
                            }
                        }
                    )
                }
            }
        }

        section("Permanently Blocked", permanent.size, sPermanent, { sPermanent = !sPermanent }) {
            permanent.forEach { limit ->
                item(key = "perm-${limit.id}") {
                    SimpleLimitRow(
                        icon = FgIcons.Block,
                        title = targetLabel(limit.targetKind, limit.targetId, categories, context),
                        subtitle = "Blocked at all times",
                        enabled = limit.enabled,
                        editMode = editMode,
                        onToggle = { on ->
                            gate.request {
                                scope.launch { container.db.usageLimitDao().setEnabled(limit.id, on) }
                            }
                        },
                        onDelete = {
                            gate.request {
                                scope.launch { container.db.usageLimitDao().delete(limit.id) }
                            }
                        }
                    )
                }
            }
        }

        item { Spacer(Modifier.height(120.dp)) }
    }

    // ---- Expanding FAB ----
    Box(
        Modifier
            .fillMaxSize()
            .padding(16.dp),
        contentAlignment = Alignment.BottomEnd
    ) {
        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            AnimatedVisibility(
                visible = fabExpanded,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    FabAction("Apps, Websites, or Categories", FgIcons.Phone) {
                        fabExpanded = false
                        onAddLimit()
                    }
                    FabAction("Block Keywords", FgIcons.Filter) {
                        fabExpanded = false
                        onBlockKeywords()
                    }
                }
            }
            Box(
                Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.primary)
                    .clickable { fabExpanded = !fabExpanded },
                contentAlignment = Alignment.Center
            ) {
                FgIcon(
                    if (fabExpanded) FgIcons.Close else FgIcons.Plus,
                    null,
                    size = 26.dp
                )
            }
        }
    }

    // ---- Daily minutes edit dialog ----
    editingMinutes?.let { limit ->
        var text by remember(limit) {
            mutableStateOf((limit.dailyLimitMs / 60_000L).toString())
        }
        AlertDialog(
            onDismissRequest = { editingMinutes = null },
            title = { Text("Daily limit (minutes)") },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.filter(Char::isDigit).take(5) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    suffix = { Text("min") }
                )
            },
            confirmButton = {
                TextButton(enabled = text.toIntOrNull()?.let { it > 0 } == true, onClick = {
                    val minutes = text.toIntOrNull() ?: return@TextButton
                    editingMinutes = null
                    gate.request {
                        scope.launch {
                            container.db.usageLimitDao()
                                .upsert(limit.copy(dailyLimitMs = minutes * 60_000L))
                        }
                    }
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editingMinutes = null }) { Text("Cancel") } }
        )
    }
}

// ---- pieces ----------------------------------------------------------------

private fun targetLabel(
    kind: String,
    id: String,
    categories: List<dev.opensource.focusguard.data.Category>,
    context: android.content.Context
): String = when (kind) {
    TargetKind.APP -> appLabel(context, id)
    TargetKind.CATEGORY -> categories.firstOrNull { it.id.toString() == id }?.name ?: "Category"
    TargetKind.ALL -> "Everything"
    else -> id
}

private fun targetIcon(kind: String) = when (kind) {
    TargetKind.APP -> FgIcons.Phone
    TargetKind.WEBSITE -> FgIcons.Globe
    TargetKind.CATEGORY -> FgIcons.Shapes
    else -> FgIcons.Shield
}

private fun androidx.compose.foundation.lazy.LazyListScope.section(
    title: String,
    count: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit
) {
    item {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "$title ($count)",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = if (count > 0) MaterialTheme.colorScheme.onBackground
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            FgIcon(
                if (expanded) FgIcons.ChevronUp else FgIcons.ChevronDown,
                null,
                size = 20.dp
            )
        }
    }
    if (expanded) {
        if (count == 0) {
            item {
                Text(
                    "Nothing here yet — tap ＋ to add a limit.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
                )
            }
        } else {
            content()
        }
    }
}

@Composable
private fun DailyRow(
    limit: UsageLimit,
    status: LimitStatus?,
    categories: List<dev.opensource.focusguard.data.Category>,
    editMode: Boolean,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val context = LocalContext.current
    val progress = if (status != null && status.limitMs > 0) {
        (status.usedMs.toFloat() / status.limitMs).coerceIn(0f, 1f)
    } else 0f

    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = !editMode, onClick = onEdit)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (limit.targetKind == TargetKind.APP) {
            AppIcon(limit.targetId, size = 42.dp)
        } else {
            Box(
                Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) { FgIcon(targetIcon(limit.targetKind), null, size = 22.dp) }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                targetLabel(limit.targetKind, limit.targetId, categories, context),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                when {
                    status == null -> limit.name.ifBlank { "…" }
                    status.blocked -> "Limit reached"
                    status.usedMs <= 0 && status.limitMs > 0 -> "${formatDuration(status.limitMs)} left"
                    else -> "${formatDuration(status.remainingMs)} left"
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (status?.blocked == true) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .weight(1f)
                        .height(5.dp)
                        .clip(RoundedCornerShape(50))
                        .background(MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(progress)
                            .height(5.dp)
                            .clip(RoundedCornerShape(50))
                            .background(
                                if (status?.blocked == true) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.primary
                            )
                    )
                }
                Spacer(Modifier.width(8.dp))
                FgIcon(
                    FgIcons.Block,
                    null,
                    size = 16.dp,
                    modifier = Modifier.alpha(
                        if (status?.blocked == true) 1f else 0.35f
                    )
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        if (editMode) {
            androidx.compose.material3.IconButton(onClick = onDelete) {
                FgIcon(FgIcons.Trash, "Delete limit", size = 20.dp)
            }
        } else {
            Switch(checked = limit.enabled, onCheckedChange = onToggle)
        }
    }
}

@Composable
private fun SimpleLimitRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    enabled: Boolean,
    editMode: Boolean,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) { FgIcon(icon, null, size = 22.dp) }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(10.dp))
        if (editMode) {
            androidx.compose.material3.IconButton(onClick = onDelete) {
                FgIcon(FgIcons.Trash, "Delete", size = 20.dp)
            }
        } else {
            Switch(checked = enabled, onCheckedChange = onToggle)
        }
    }
}

@Composable
private fun FabAction(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        Spacer(Modifier.width(10.dp))
        FgIcon(icon, null, size = 20.dp)
    }
}
