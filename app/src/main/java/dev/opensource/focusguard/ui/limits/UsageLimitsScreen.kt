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
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableLongStateOf
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
import dev.opensource.focusguard.enforcement.FocusSession
import dev.opensource.focusguard.domain.WeekEngine
import dev.opensource.focusguard.ui.common.AppIcon
import dev.opensource.focusguard.ui.common.FgCard
import dev.opensource.focusguard.ui.common.FgIcon
import dev.opensource.focusguard.ui.common.FirstRunHint
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
    val blockAllUntil by container.enforcement.blockAllUntil.collectAsState()
    val focusDay by container.settings.focusDay.collectAsState(initial = "")
    val focusMinutes by container.settings.focusMinutes.collectAsState(initial = 0)
    val grants by container.enforcement.sessionGrants.collectAsState()

    var statuses by remember { mutableStateOf<Map<Long, LimitStatus>>(emptyMap()) }
    var editingMinutes by remember { mutableStateOf<UsageLimit?>(null) }

    // Each section lives in its own bordered box. Expanded/collapsed state is
    // saved so switching tabs and coming back keeps the section open instead
    // of silently collapsing it (previously plain `remember` — reset on nav).
    var sDaily by rememberSaveable { mutableStateOf(false) }
    var sSession by rememberSaveable { mutableStateOf(false) }
    var sSchedule by rememberSaveable { mutableStateOf(false) }
    var sPermanent by rememberSaveable { mutableStateOf(false) }
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
        val label = targetLabel(l.targetKind, l.targetId, context).lowercase()
        val name = l.name.lowercase()
        val q = query.trim().lowercase()
        return label.contains(q) || name.contains(q)
    }

    val daily = limits.filter { it.type == LimitType.DAILY && matchesFilter(it) }
    val session = limits.filter { it.type == LimitType.SESSION && matchesFilter(it) }
    val typedSchedules = limits.filter { it.type == LimitType.SCHEDULE && matchesFilter(it) }
    val permanent = limits.filter { it.type == LimitType.PERMANENT && matchesFilter(it) }

    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val blockActive = blockAllUntil > now
    val focusedToday = if (focusDay == container.usage.todayKey()) focusMinutes else 0
    LaunchedEffect(blockActive) {
        if (blockActive) {
            while (true) {
                delay(1_000L)
                now = System.currentTimeMillis()
                if (blockAllUntil <= now) break
            }
        }
    }

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

        item {
            FirstRunHint(
                id = "limits",
                text = "A limit can be a daily allowance, a fixed session, a schedule, or permanent. Focus Tools holds the unlock delay and the daily quota."
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
                                "until ${formatClock(blockAllUntil.let { java.time.Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).hour * 60 + java.time.Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).minute })} · ${formatDuration(blockAllUntil - now)} left",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1
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
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Focused today: " + if (focusedToday <= 0) "0m"
                        else formatDuration(focusedToday * 60_000L),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(12.dp))
                    if (blockActive) {
                        OutlinedButton(
                            onClick = {
                                gate.request {
                                    scope.launch { FocusSession.end(container) }
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("End session") }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                BlockNowPreset("15 min", 15 * 60_000L, Modifier.weight(1f)) {
                                    scope.launch { FocusSession.start(container, it) }
                                }
                                BlockNowPreset("30 min", 30 * 60_000L, Modifier.weight(1f)) {
                                    scope.launch { FocusSession.start(container, it) }
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                BlockNowPreset("1 hour", 60 * 60_000L, Modifier.weight(1f)) {
                                    scope.launch { FocusSession.start(container, it) }
                                }
                                BlockNowPreset("Until midnight", -1L, Modifier.weight(1f)) {
                                    scope.launch { FocusSession.start(container, it) }
                                }
                            }
                        }
                    }
                }
            }
        }

        // ---- Sections ----
        item {
            SectionBox(
                title = "Limited Daily Usage",
                count = daily.size,
                expanded = sDaily,
                onToggle = { sDaily = !sDaily }
            ) {
                if (daily.isEmpty()) {
                    EmptySectionHint()
                } else {
                    daily.forEach { limit ->
                        DailyRow(
                            limit = limit,
                            status = statuses[limit.id],
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
        }

        item {
            SectionBox(
                title = "Session Limits",
                count = session.size,
                expanded = sSession,
                onToggle = { sSession = !sSession }
            ) {
                if (session.isEmpty()) {
                    EmptySectionHint()
                } else {
                    session.forEach { limit ->
                        SimpleLimitRow(
                            icon = FgIcons.Stopwatch,
                            targetKind = limit.targetKind,
                            targetId = limit.targetId,
                            title = targetLabel(limit.targetKind, limit.targetId, context),
                            subtitle = grants[limit.targetId]?.let { until ->
                                if (until > System.currentTimeMillis()) "Session until ${
                                    formatClock(java.time.Instant.ofEpochMilli(until).atZone(ZoneId.systemDefault()).hour * 60 +
                                        java.time.Instant.ofEpochMilli(until).atZone(ZoneId.systemDefault()).minute)
                                }" else null
                            } ?: sessionSummary(limit),
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
        }

        item {
            SectionBox(
                title = "Scheduled Blocks",
                count = typedSchedules.size + schedules.size,
                expanded = sSchedule,
                onToggle = { sSchedule = !sSchedule }
            ) {
                if (typedSchedules.isEmpty() && schedules.isEmpty()) {
                    EmptySectionHint()
                } else {
                    typedSchedules.forEach { limit ->
                        SimpleLimitRow(
                            icon = FgIcons.Clock,
                            targetKind = limit.targetKind,
                            targetId = limit.targetId,
                            title = limit.name.ifBlank {
                                targetLabel(limit.targetKind, limit.targetId, context)
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
                    schedules.forEach { schedule ->
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
        }

        item {
            SectionBox(
                title = "Permanently Blocked",
                count = permanent.size,
                expanded = sPermanent,
                onToggle = { sPermanent = !sPermanent }
            ) {
                if (permanent.isEmpty()) {
                    EmptySectionHint()
                } else {
                    permanent.forEach { limit ->
                        SimpleLimitRow(
                            icon = FgIcons.Block,
                            targetKind = limit.targetKind,
                            targetId = limit.targetId,
                            title = targetLabel(limit.targetKind, limit.targetId, context),
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
                    FabAction("Apps or Websites", FgIcons.Phone) {
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
    context: android.content.Context
): String = when (kind) {
    TargetKind.APP -> appLabel(context, id)
    TargetKind.ALL -> "Everything"
    else -> id
}

private fun targetIcon(kind: String) = when (kind) {
    TargetKind.APP -> FgIcons.Phone
    TargetKind.WEBSITE -> FgIcons.Globe
    TargetKind.CATEGORY -> FgIcons.Shapes
    else -> FgIcons.Shield
}

/** "20 min per open · 30 min cooldown" for a session limit row. */
private fun sessionSummary(limit: UsageLimit): String {
    val minutes = (limit.dailyLimitMs / 60_000L).toInt()
    val cooldown = (limit.cooldownMs / 60_000L).toInt()
    return if (cooldown > 0) "$minutes min per open · $cooldown min cooldown"
    else "$minutes min per open · blocked once spent"
}

/** One bordered rectangle per section: header toggles the whole box. */
@Composable
private fun SectionBox(
    title: String,
    count: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    OutlinedCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        colors = CardDefaults.outlinedCardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        border = CardDefaults.outlinedCardBorder()
    ) {
        Column {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle)
                    .padding(horizontal = 20.dp, vertical = 14.dp),
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
                    if (expanded) "Collapse" else "Expand",
                    size = 20.dp
                )
            }
            if (expanded) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Column(Modifier.padding(vertical = 4.dp), content = content)
            }
        }
    }
}

@Composable
private fun EmptySectionHint() {
    Text(
        "Nothing here yet — tap ＋ to add a limit.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
    )
}

@Composable
private fun DailyRow(
    limit: UsageLimit,
    status: LimitStatus?,
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
                targetLabel(limit.targetKind, limit.targetId, context),
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
    targetKind: String? = null,
    targetId: String? = null,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (targetKind == TargetKind.APP && targetId != null) {
            AppIcon(targetId, size = 42.dp)
        } else {
            Box(
                Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) { FgIcon(icon, null, size = 22.dp) }
        }
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
private fun BlockNowPreset(
    label: String,
    durationMs: Long,
    modifier: Modifier = Modifier,
    onStart: (Long) -> Unit
) {
    OutlinedButton(
        onClick = {
            val until = if (durationMs < 0) {
                LocalDate.now().plusDays(1)
                    .atStartOfDay(ZoneId.systemDefault())
                    .toInstant().toEpochMilli()
            } else System.currentTimeMillis() + durationMs
            onStart(until)
        },
        modifier = modifier,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 10.dp)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
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
