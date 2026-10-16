package dev.opensource.focusguard.ui.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.opensource.focusguard.data.DailyUsage
import dev.opensource.focusguard.data.HourTotals
import dev.opensource.focusguard.data.HiddenKind
import dev.opensource.focusguard.data.HiddenItem
import dev.opensource.focusguard.domain.WeekEngine
import dev.opensource.focusguard.ui.common.AppIcon
import dev.opensource.focusguard.ui.common.FgCard
import dev.opensource.focusguard.ui.common.FgIcon
import dev.opensource.focusguard.ui.common.LocalAppContainer
import dev.opensource.focusguard.ui.common.PageHeader
import dev.opensource.focusguard.ui.common.appLabel
import dev.opensource.focusguard.ui.common.formatDuration
import dev.opensource.focusguard.ui.icons.FgIcons
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private enum class Period(val chip: String) {
    TODAY("Today"), YESTERDAY("Yesterday"), D7("7d"), D14("14d"), D28("28d"), CUSTOM("Custom")
}

/** Resolved inclusive display range. */
private data class Range(val from: LocalDate, val to: LocalDate) {
    val single: Boolean get() = from == to
}

@Composable
fun UsageStatsScreen(query: String, onOpenApp: (String) -> Unit) {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var period by rememberSaveable { mutableStateOf(Period.TODAY) }
    var step by rememberSaveable { mutableIntStateOf(0) } // stepping offset from anchor
    var customFromKey by rememberSaveable { mutableStateOf("") }
    var customToKey by rememberSaveable { mutableStateOf("") }
    var showPicker by remember { mutableStateOf(false) }
    var chartStyle by rememberSaveable { mutableStateOf(ChartStyle.BAR) }
    var categoryFilter by rememberSaveable { mutableStateOf("all") } // "all" or categoryId
    var setupExpanded by rememberSaveable { mutableStateOf(false) }
    val dismissedTips = remember { androidx.compose.runtime.mutableStateListOf<String>() }

    val mondayFirst by container.settings.weekMondayFirst.collectAsState(initial = true)
    val hiddenKeys by container.hiddenKeys.collectAsState()
    val categories by container.categories.collectAsState()

    // Reconcile once when the screen appears (checkpointed delta — cheap).
    LaunchedEffect(Unit) { runCatching { container.usage.reconcileNow() } }

    val todayDate = remember(period, step) { LocalDate.parse(container.usage.todayKey()) }

    val range = remember(period, step, customFromKey, customToKey, todayDate, mondayFirst) {
        when (period) {
            Period.TODAY, Period.YESTERDAY -> {
                val base = if (period == Period.YESTERDAY) todayDate.minusDays(1) else todayDate
                val d = base.plusDays(step.toLong())
                Range(d, d)
            }
            Period.D7, Period.D14, Period.D28 -> {
                val days = when (period) {
                    Period.D7 -> 7; Period.D14 -> 14; else -> 28
                }
                val anchor = todayDate.plusDays(step.toLong())
                val (from, to) = WeekEngine.presetRange(days, anchor, mondayFirst)
                Range(from, to)
            }
            Period.CUSTOM -> {
                val f = customFromKey.toLocalDateOrNull() ?: todayDate
                val t = customToKey.toLocalDateOrNull() ?: todayDate
                if (f <= t) Range(f, t) else Range(t, f)
            }
        }
    }

    // Rows for the range.
    var rows by remember { mutableStateOf<List<DailyUsage>>(emptyList()) }
    var hourly by remember { mutableStateOf<List<HourTotals>>(emptyList()) }
    var webTotal by remember { mutableStateOf(0L) }

    LaunchedEffect(range) {
        val fromKey = range.from.toString()
        val toKey = range.to.toString()
        val dao = container.db.dailyUsageDao()
        rows = if (range.single) dao.forDay(fromKey) else dao.range(fromKey, toKey)
        hourly = if (range.single) container.db.hourlyUsageDao().dayTotalsByHour(fromKey) else emptyList()
        webTotal = container.db.elementUsageDao()
            .sumAll("__site__", fromKey, toKey) ?: 0L
    }

    // Category filter support: which packages are in the chosen category.
    val memberMap by container.enforcement.categoryMembers.collectAsState()
    val categoryPkgFilter = remember(categoryFilter, memberMap) {
        if (categoryFilter == "all") null
        else {
            val id = categoryFilter.toLongOrNull() ?: -1L
            memberMap.filter { it.categoryId == id && it.key.startsWith("app:") }
                .map { it.key.removePrefix("app:") }.toSet()
        }
    }

    val isToday = range.single && range.from.toString() == container.usage.todayKey()
    val grandTotal = remember(rows) { rows.sumOf { it.usedMs } }

    val visibleRows = remember(rows, query, hiddenKeys, categoryFilter, categoryPkgFilter) {
        rows.filter { row ->
            if (row.packageName.startsWith("web:")) return@filter false
            if ("app:${row.packageName}" in hiddenKeys) return@filter false
            if (categoryPkgFilter != null && row.packageName !in categoryPkgFilter) return@filter false
            if (query.isNotBlank()) {
                val label = row.packageName.lowercase()
                if (!label.contains(query.trim().lowercase())) return@filter false
            }
            true
        }
    }

    val displayTotal = remember(visibleRows) { visibleRows.sumOf { it.usedMs } }

    // Chart bars.
    val chartBars = remember(range, hourly, rows) {
        if (range.single) {
            val active = hourly.filter { it.ms > 0 }
            if (active.isEmpty()) emptyList()
            else {
                val minH = active.minOf { it.hour }
                val maxH = active.maxOf { it.hour }
                val byHour = hourly.associate { it.hour to it.ms }
                (minH..maxH).map { h -> ChartBar(h.toString(), byHour[h] ?: 0L) }
            }
        } else {
            val byDay = rows.groupBy { it.day }.mapValues { (_, r) -> r.sumOf { it.usedMs } }
            (0L..(range.to.toEpochDay() - range.from.toEpochDay())).map { offset ->
                val day = range.from.plusDays(offset)
                ChartBar(day.dayOfMonth.toString(), byDay[day.toString()] ?: 0L)
            }
        }
    }

    // Usage patterns (rule-based, dismissible).
    val tips = remember(visibleRows, hourly, grandTotal) {
        val out = ArrayList<Pair<String, String>>()
        val top = visibleRows.maxByOrNull { it.usedMs }
        if (top != null && top.usedMs > 0 && grandTotal > 0 && displayTotal > 0) {
            val share = (top.usedMs * 100) / displayTotal
            if (share >= 50) {
                out.add("top" to "App name takes up ${share}% of your screen time — consider a daily limit.")
            }
        }
        val night = hourly.filter { it.hour in 0..5 }.sumOf { it.ms }
        if (night >= 30 * 60_000L) {
            out.add("night" to "You used your phone ${formatDuration(night)} after midnight.")
        }
        val launches = visibleRows.maxByOrNull { it.launches }
        if (launches != null && launches.launches >= 20) {
            out.add("launch" to "You opened ${launches.packageName.substringAfterLast('.')} ${launches.launches} times — small checks add up.")
        }
        out
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp)
    ) {
        item { PageHeader("Usage Stats") }

        // Setup banner (only when something is missing).
        item {
            SetupBanner(
                expanded = setupExpanded,
                onToggle = { setupExpanded = !setupExpanded }
            )
        }

        // Date stepper + period chips.
        item {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StepButton(FgIcons.ChevronRight, rotated = true) {
                    step -= if (period == Period.TODAY || period == Period.YESTERDAY) 1 else 7
                }
                Row(
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .background(MaterialTheme.colorScheme.surface)
                        .clickable {
                            period = Period.TODAY
                            step = 0
                        }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FgIcon(FgIcons.Calendar, null, size = 18.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        rangeLabel(range, todayDate),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                }
                StepButton(FgIcons.ChevronRight) {
                    step += if (period == Period.TODAY || period == Period.YESTERDAY) 1 else 7
                }
            }
        }

        item {
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Period.values().forEach { p ->
                    Chip(
                        label = p.chip,
                        selected = period == p,
                        onClick = {
                            if (p == Period.CUSTOM) {
                                showPicker = true
                            } else {
                                period = p
                                step = 0
                            }
                        }
                    )
                }
                Chip(label = categoryLabel(categories, categoryFilter), selected = categoryFilter != "all", onClick = {
                    // Cycle: all → each category → all
                    val ids = categories.map { it.id.toString() }
                    val idx = ids.indexOf(categoryFilter)
                    categoryFilter = if (idx == -1) "all" else ids.getOrNull(idx + 1) ?: "all"
                })
            }
        }

        // Chart card.
        item {
            FgCard(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Usage Time",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f)
                        )
                        ChartStyleToggle(chartStyle) { chartStyle = it }
                    }
                    Spacer(Modifier.height(12.dp))
                    if (chartBars.isEmpty()) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(160.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "No usage recorded yet",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    } else {
                        UsageChart(bars = chartBars, style = chartStyle)
                    }
                    Spacer(Modifier.height(14.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LegendDot(MaterialTheme.colorScheme.primary, "Mobile App")
                        Spacer(Modifier.width(6.dp))
                        Text(
                            formatDuration(displayTotal),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.width(16.dp))
                        LegendDot(MaterialTheme.colorScheme.secondary, "Web")
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (webTotal <= 0) "Less than 1 min" else formatDuration(webTotal),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FgIcon(FgIcons.Eye, null, size = 20.dp)
                        Spacer(Modifier.weight(1f))
                        Text(
                            "Total Usage: ",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            if (displayTotal <= 0) "0m" else formatDuration(displayTotal),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(6.dp))
                        FgIcon(FgIcons.ArrowRight, null, size = 18.dp)
                    }
                }
            }
        }

        // App rows.
        val sorted = visibleRows.sortedByDescending { it.usedMs }
        items(sorted, key = { it.packageName }) { row ->
            AppUsageRow(
                pkg = row.packageName,
                ms = row.usedMs + (if (isToday) container.usage.liveMs(row.packageName) else 0L),
                share = if (displayTotal > 0) row.usedMs.toFloat() / displayTotal else 0f,
                onClick = { onOpenApp(row.packageName) }
            )
        }

        // Usage patterns.
        if (tips.isNotEmpty()) {
            item {
                Spacer(Modifier.height(16.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Usage Patterns",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.width(6.dp))
                    FgIcon(FgIcons.Info, "About usage patterns", size = 16.dp)
                }
            }
            items(tips.filter { it.first !in dismissedTips }) { (id, text) ->
                FgCard(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                    Row(
                        Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FgIcon(FgIcons.Lightbulb, null, size = 20.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text.replace("App name", appLabel(context, sorted.firstOrNull()?.packageName ?: "")),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { dismissedTips.add(id) }) {
                            FgIcon(FgIcons.Close, "Dismiss", size = 16.dp)
                        }
                    }
                }
            }
        }
    }

    if (showPicker) {
        CustomRangePicker(
            initialFrom = customFromKey.toLocalDateOrNull(),
            initialTo = customToKey.toLocalDateOrNull(),
            onDismiss = { showPicker = false },
            onConfirm = { from, to ->
                customFromKey = from.toString()
                customToKey = to.toString()
                period = Period.CUSTOM
                showPicker = false
            }
        )
    }
}

// ---- pieces ----------------------------------------------------------------

private fun String.toLocalDateOrNull(): LocalDate? = runCatching { LocalDate.parse(this) }.getOrNull()

private fun rangeLabel(range: Range, today: LocalDate): String = when {
    range.single && range.from == today -> "Today"
    range.single && range.from == today.minusDays(1) -> "Yesterday"
    range.single -> DateTimeFormatter.ofPattern("MMM d").format(range.from)
    else -> "${DateTimeFormatter.ofPattern("MMM d").format(range.from)} – ${DateTimeFormatter.ofPattern("MMM d").format(range.to)}"
}

private fun categoryLabel(categories: List<dev.opensource.focusguard.data.Category>, filter: String): String =
    if (filter == "all") "All Categories"
    else categories.firstOrNull { it.id.toString() == filter }?.name ?: "All Categories"

@Composable
private fun StepButton(icon: androidx.compose.ui.graphics.vector.ImageVector, rotated: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier
            .size(38.dp)
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        FgIcon(
            icon,
            null,
            size = 18.dp,
            modifier = if (rotated) Modifier.rotate(180f) else Modifier
        )
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                else MaterialTheme.colorScheme.surface
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun LegendDot(color: androidx.compose.ui.graphics.Color, label: String) {
    Box(
        Modifier
            .size(10.dp)
            .clip(RoundedCornerShape(50))
            .background(color)
    )
    Spacer(Modifier.width(6.dp))
    Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun ChartStyleToggle(style: ChartStyle, onChange: (ChartStyle) -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        listOf(ChartStyle.BAR to FgIcons.ChartBar, ChartStyle.LINE to FgIcons.ChartLine).forEach { (s, icon) ->
            Box(
                Modifier
                    .size(40.dp)
                    .background(
                        if (style == s) MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
                        else androidx.compose.ui.graphics.Color.Transparent
                    )
                    .clickable { onChange(s) },
                contentAlignment = Alignment.Center
            ) {
                FgIcon(icon, null, size = 20.dp)
            }
        }
    }
}

@Composable
private fun AppUsageRow(pkg: String, ms: Long, share: Float, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AppIcon(pkg, size = 42.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                appLabel(LocalContext.current, pkg),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(6.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(5.dp)
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.outlineVariant)
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(share.coerceIn(0.02f, 1f))
                        .height(5.dp)
                        .clip(RoundedCornerShape(50))
                        .background(MaterialTheme.colorScheme.primary)
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                if (ms <= 0) "Less than 1 min" else formatDuration(ms),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                "${"%.1f".format(share * 100)}%",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SetupBanner(expanded: Boolean, onToggle: () -> Unit) {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val usageOk = remember { dev.opensource.focusguard.ui.common.hasUsageAccess(context) }
    val monitorOk = remember {
        dev.opensource.focusguard.ui.common.isAccessibilityServiceEnabled(
            context, dev.opensource.focusguard.enforcement.AppMonitorService::class.java
        )
    }
    val notifOk = remember { dev.opensource.focusguard.ui.common.hasNotificationPermission(context) }
    val missing = listOf(
        Triple("Usage access", usageOk, true),
        Triple("App monitor service", monitorOk, false),
        Triple("Notifications", notifOk, false)
    ).filter { !it.second }

    if (missing.isEmpty()) return

    FgCard(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Complete FocusGuard Setup (${3 - missing.size}/3)",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onToggle) {
                    Text(if (expanded) "Hide" else "Show", color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(4.dp))
                    FgIcon(if (expanded) FgIcons.ChevronUp else FgIcons.ChevronDown, null, size = 16.dp)
                }
            }
            if (expanded) {
                missing.forEach { (label, _, isUsage) ->
                    dev.opensource.focusguard.ui.common.PermissionRow(
                        title = label,
                        granted = false,
                        actionLabel = "Fix",
                        onClick = {
                            val intent = if (isUsage) {
                                android.content.Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS)
                            } else {
                                android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                            }
                            runCatching { context.startActivity(intent) }
                        }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CustomRangePicker(
    initialFrom: LocalDate?,
    initialTo: LocalDate?,
    onDismiss: () -> Unit,
    onConfirm: (LocalDate, LocalDate) -> Unit
) {
    fun LocalDate?.toMillis(): Long? =
        this?.atStartOfDay(ZoneId.systemDefault())?.toInstant()?.toEpochMilli()

    val state = rememberDateRangePickerState(
        initialSelectedStartDateMillis = initialFrom.toMillis(),
        initialSelectedEndDateMillis = initialTo.toMillis()
    )
    val from = state.selectedStartDateMillis?.let {
        Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate()
    }
    val to = state.selectedEndDateMillis?.let {
        Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                enabled = from != null && to != null,
                onClick = {
                    val f = from ?: return@TextButton
                    val t = to ?: return@TextButton
                    onConfirm(f, t)
                }
            ) { Text("Apply") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Custom range") },
        text = {
            DateRangePicker(
                state = state,
                modifier = Modifier.height(420.dp),
                showModeToggle = false,
                title = { Text("Pick a date range", Modifier.padding(16.dp)) },
                headline = {
                    Text(
                        buildString {
                            if (from != null) append(DateTimeFormatter.ofPattern("MMM d, yyyy").format(from))
                            append(" – ")
                            if (to != null) append(DateTimeFormatter.ofPattern("MMM d, yyyy").format(to))
                        },
                        Modifier.padding(start = 16.dp)
                    )
                }
            )
        }
    )
}

