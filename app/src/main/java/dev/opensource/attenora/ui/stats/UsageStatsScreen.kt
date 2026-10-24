package dev.opensource.attenora.ui.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.opensource.attenora.data.DailyUsage
import dev.opensource.attenora.data.HourTotals
import dev.opensource.attenora.data.HiddenKind
import dev.opensource.attenora.data.HiddenItem
import dev.opensource.attenora.data.ReelCount
import dev.opensource.attenora.domain.WeekEngine
import dev.opensource.attenora.ui.common.AppIcon
import dev.opensource.attenora.ui.common.FgCard
import dev.opensource.attenora.ui.common.FgDivider
import dev.opensource.attenora.ui.common.FirstRunHint
import dev.opensource.attenora.ui.common.FgIcon
import dev.opensource.attenora.ui.common.LocalAppContainer
import dev.opensource.attenora.ui.common.PageHeader
import dev.opensource.attenora.ui.common.appIconBitmap
import dev.opensource.attenora.ui.common.appLabel
import dev.opensource.attenora.enforcement.FocusSession
import dev.opensource.attenora.ui.common.formatDuration
import dev.opensource.attenora.ui.icons.FgIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private enum class Period(val chip: String) {
    TODAY("Today"), YESTERDAY("Yesterday"), D7("7d"), D14("14d"), D28("28d"), CUSTOM("Custom")
}

/** Which metric the app list is ranked by (Stayfree "Usage Metrics"). */
private enum class Metric(val label: String) {
    TIME("Usage time"), OPENS("Opens")
}

/** Resolved inclusive display range. */
private data class Range(val from: LocalDate, val to: LocalDate) {
    val single: Boolean get() = from == to
}

/** One app rolled up across every day in the selected range. */
private data class AppStat(val pkg: String, val ms: Long, val launches: Int)

@OptIn(ExperimentalLayoutApi::class)
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
    var metric by rememberSaveable { mutableStateOf(Metric.TIME) }
    var sortDesc by rememberSaveable { mutableStateOf(true) }
    var metricMenu by remember { mutableStateOf(false) }
    val dismissedTips = remember { androidx.compose.runtime.mutableStateListOf<String>() }

    val mondayFirst by container.settings.weekMondayFirst.collectAsState(initial = true)

    // Reconcile once when the screen appears (checkpointed delta — cheap).
    // The rows below are read by a separate one-shot query, so bump a tick
    // once reconcile has written its rollups — otherwise the first frame can
    // win the race and keep showing the empty state even though the day's
    // rows are already in the database (reproduced on emulator, 2026-10-18).
    var refreshTick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        // Yield the opening frame before the UsageStats drain starts: reconcile
        // is a multi-hundred-ms Binder IPC + DB write burst, and on a cold start
        // it otherwise competes with the first (most expensive) frame while the
        // app is still class-loading. One frame is enough — `withFrameNanos`
        // resumes on the *next* frame's dispatch, after this frame's draw.
        androidx.compose.runtime.withFrameNanos { }
        val t0 = android.os.SystemClock.elapsedRealtime()
        runCatching { container.usage.reconcileNow() }
        android.util.Log.d(
            "FGSTART",
            "reconcile: ${android.os.SystemClock.elapsedRealtime() - t0}ms"
        )
        refreshTick++
    }

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
    var hourRows by remember { mutableStateOf<List<dev.opensource.attenora.data.HourlyUsage>>(emptyList()) }
    var webTotal by remember { mutableStateOf(0L) }

    LaunchedEffect(range, refreshTick) {
        val t0 = android.os.SystemClock.elapsedRealtime()
        val fromKey = range.from.toString()
        val toKey = range.to.toString()
        val dao = container.db.dailyUsageDao()
        rows = if (range.single) dao.forDay(fromKey) else dao.range(fromKey, toKey)
        hourly = if (range.single) container.db.hourlyUsageDao().dayTotalsByHour(fromKey) else emptyList()
        hourRows = if (range.single) emptyList() else container.db.hourlyUsageDao().range(fromKey, toKey)
        webTotal = container.db.elementUsageDao()
            .sumAll("__site__", fromKey, toKey) ?: 0L
        android.util.Log.d(
            "FGSTART",
            "stats query: ${android.os.SystemClock.elapsedRealtime() - t0}ms"
        )
    }

    val isToday = range.single && range.from.toString() == container.usage.todayKey()
    val grandTotal = remember(rows) { rows.sumOf { it.usedMs } }

    val visibleRows = remember(rows, query) {
        rows.filter { row ->
            if (row.packageName.startsWith("web:")) return@filter false
            if (query.isNotBlank()) {
                val label = row.packageName.lowercase()
                if (!label.contains(query.trim().lowercase())) return@filter false
            }
            true
        }
    }

    val displayTotal = remember(visibleRows) { visibleRows.sumOf { it.usedMs } }

    // Roll every per-day row up into one row per app. Multi-day ranges return
    // one row per (day, app) — without this rollup the list handed LazyColumn
    // duplicate keys and only a single stale day's row rendered.
    val appStats = remember(visibleRows) {
        visibleRows.groupBy { it.packageName }.map { (pkg, rs) ->
            AppStat(pkg, rs.sumOf { it.usedMs }, rs.sumOf { it.launches })
        }
    }
    val metricTotal = remember(appStats, metric) {
        appStats.sumOf { if (metric == Metric.TIME) it.ms else it.launches.toLong() }
    }
    val orderedStats = remember(appStats, metric, sortDesc) {
        val byValue = compareBy<AppStat> { if (metric == Metric.TIME) it.ms else it.launches.toLong() }
        if (sortDesc) appStats.sortedWith(byValue.reversed()) else appStats.sortedWith(byValue)
    }

    // First scroll janks because every row decodes its launcher icon (a
    // PackageManager IPC + bitmap decode) and resolves its label on the main
    // thread. Warm the process-wide caches in the background as soon as the
    // list is known — the rows then only hit a map. Deferred one frame so the
    // opening frame never waits on it. Best-effort: the caches already swallow
    // failures.
    LaunchedEffect(appStats) {
        val pkgs = appStats.map { it.pkg }
        if (pkgs.isEmpty()) return@LaunchedEffect
        androidx.compose.runtime.withFrameNanos { }
        withContext(Dispatchers.IO) {
            pkgs.forEach { pkg ->
                appLabel(container.app, pkg)
                appIconBitmap(container.app, pkg)
            }
        }
    }

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

    // Heat grid (day × hour) for the heatmap style.
    val heatRows = remember(range, hourly, hourRows) {
        if (range.single) {
            val arr = LongArray(24)
            hourly.forEach { arr[it.hour] = it.ms }
            listOf(HeatRow("this day", arr))
        } else {
            (0..(range.to.toEpochDay() - range.from.toEpochDay())).map { off ->
                val day = range.from.plusDays(off)
                val arr = LongArray(24)
                hourRows.filter { it.day == day.toString() }.forEach { arr[it.hour] = it.ms }
                HeatRow(day.toString().substring(5), arr) // MM-DD
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

        item {
            FirstRunHint(
                id = "stats",
                text = "Tap any app to open its own page — usage by hour, opens, and a place to set a limit."
            )
        }

        // Date stepper + period chips.
        item {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
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

        // Period chips — scroll only if they ever overflow, but edge-padded so
        // the first/last chip never sit flush against the screen edge.
        item {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Spacer(Modifier.width(10.dp))
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
                Spacer(Modifier.width(10.dp))
            }
        }

        // Chart card.
        item {
            FgCard(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Usage Time",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.weight(1f))
                        // Style switcher: four tiny icons tucked into the right
                        // edge of the title row — no full-width segmented bar.
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            ChartStyleChip(FgIcons.ChartBar, "Bars", chartStyle == ChartStyle.BAR) {
                                chartStyle = ChartStyle.BAR
                            }
                            ChartStyleChip(FgIcons.ChartLine, "Line", chartStyle == ChartStyle.LINE) {
                                chartStyle = ChartStyle.LINE
                            }
                            ChartStyleChip(FgIcons.ChartDonut, "Donut", chartStyle == ChartStyle.DONUT) {
                                chartStyle = ChartStyle.DONUT
                            }
                            ChartStyleChip(FgIcons.ChartHeat, "Heat", chartStyle == ChartStyle.HEATMAP) {
                                chartStyle = ChartStyle.HEATMAP
                            }
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    val noData = when (chartStyle) {
                        ChartStyle.BAR, ChartStyle.LINE, ChartStyle.DONUT ->
                            chartBars.none { it.valueMs > 0L }
                        ChartStyle.HEATMAP -> heatRows.all { r -> r.hours.all { it == 0L } }
                    }
                    if (noData) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(230.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "No usage recorded yet",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    } else {
                        when (chartStyle) {
                            ChartStyle.BAR, ChartStyle.LINE -> UsageChart(bars = chartBars, style = chartStyle)
                            ChartStyle.DONUT -> DonutChart(bars = chartBars.filter { it.valueMs > 0L })
                            ChartStyle.HEATMAP -> HeatmapChart(rows = heatRows)
                        }
                    }

                    Spacer(Modifier.height(14.dp))
                    // FlowRow, not Row: at 1.3x font the Web value ("Less than
                    // 1 min") is wider than the leftover space and used to clip
                    // against the card edge. Each pair now wraps as one unit.
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            LegendDot(MaterialTheme.colorScheme.primary, "Mobile App")
                            Spacer(Modifier.width(6.dp))
                            Text(
                                formatDuration(displayTotal),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            LegendDot(MaterialTheme.colorScheme.secondary, "Web")
                            Spacer(Modifier.width(6.dp))
                            Text(
                                if (webTotal <= 0) "Less than 1 min" else formatDuration(webTotal),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1
                            )
                        }
                    }

                    // Total and focus share one row — stacked, they read as two
                    // unrelated facts and leave a ragged gap under the legend.
                    // FlowRow keeps that side-by-side reading but lets the second
                    // stat drop to its own line when the font is scaled up;
                    // weight(1f) previously squeezed "24s" into a one-character
                    // column and pushed "Focused today" off the row entirely.
                    Spacer(Modifier.height(8.dp))
                    FlowRow(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(20.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        StatLine(
                            icon = FgIcons.Eye,
                            label = "Total usage",
                            value = if (displayTotal <= 0) "0m" else formatDuration(displayTotal)
                        )
                        if (isToday) {
                            var focusedToday by remember { mutableIntStateOf(-1) }
                            LaunchedEffect(Unit) {
                                runCatching {
                                    // Fold a session that just ended, then read today's total.
                                    FocusSession.finalize(container)
                                    focusedToday = FocusSession.accruedTodayMinutes(container)
                                }
                            }
                            StatLine(
                                icon = FgIcons.Stopwatch,
                                label = "Focused today",
                                value = if (focusedToday <= 0) "0m"
                                else formatDuration(focusedToday * 60_000L)
                            )
                        }
                    }
                }
            }
        }

        // Reel counter: one slim line under the chart, expanding to the per-app
        // split. Today only — the counter answers "how deep am I in right now",
        // and its rows are stored per usage day.
        if (isToday) {
            item { ReelCounterRow(day = range.from.toString()) }
        }

        // App list — ranked by the selected metric (usage time or opens).
        item {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 14.dp, top = 16.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Most used apps",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "${orderedStats.size} apps · by ${metric.label.lowercase()}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Box {
                    Row(
                        Modifier
                            .clip(RoundedCornerShape(50))
                            .background(MaterialTheme.colorScheme.surface)
                            .clickable { metricMenu = true }
                            .padding(start = 12.dp, end = 8.dp, top = 7.dp, bottom = 7.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            metric.label,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(4.dp))
                        FgIcon(FgIcons.ChevronDown, "Change metric", size = 16.dp)
                    }
                    DropdownMenu(
                        expanded = metricMenu,
                        onDismissRequest = { metricMenu = false }
                    ) {
                        Metric.values().forEach { m ->
                            DropdownMenuItem(
                                text = { Text(m.label) },
                                trailingIcon = if (m == metric) {
                                    { FgIcon(FgIcons.Check, null, size = 16.dp) }
                                } else null,
                                onClick = {
                                    metric = m
                                    metricMenu = false
                                }
                            )
                        }
                    }
                }
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(50))
                        .background(MaterialTheme.colorScheme.surface)
                        .clickable { sortDesc = !sortDesc },
                    contentAlignment = Alignment.Center
                ) {
                    FgIcon(
                        if (sortDesc) FgIcons.ChevronDown else FgIcons.ChevronUp,
                        if (sortDesc) "Sorted high to low" else "Sorted low to high",
                        size = 18.dp
                    )
                }
            }
        }

        items(orderedStats, key = { it.pkg }) { stat ->
            AppUsageRow(
                pkg = stat.pkg,
                primary = when (metric) {
                    Metric.TIME -> {
                        val ms = stat.ms + (if (isToday) container.usage.liveMs(stat.pkg) else 0L)
                        if (ms <= 0) "Less than 1 min" else formatDuration(ms)
                    }
                    Metric.OPENS -> if (stat.launches <= 0) "No opens" else "${stat.launches} opens"
                },
                share = if (metricTotal > 0) {
                    (if (metric == Metric.TIME) stat.ms else stat.launches.toLong()).toFloat() / metricTotal
                } else 0f,
                onClick = { onOpenApp(stat.pkg) }
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
            items(tips.filter { it.first !in dismissedTips }, key = { it.first }) { (id, text) ->
                FgCard(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                    Row(
                        Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FgIcon(FgIcons.Lightbulb, null, size = 20.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text.replace("App name", appLabel(context, orderedStats.firstOrNull()?.pkg ?: "")),
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

@Composable
private fun StatLine(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        FgIcon(icon, null, size = 18.dp)
        Spacer(Modifier.width(8.dp))
        Text(
            "$label:",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
        Spacer(Modifier.width(6.dp))
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1
        )
    }
}
@Composable
private fun ChartStyleChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    name: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    // The visual chip is 32dp, but the touch target is a full 48dp: Android's
    // minimum, and the icons sit close enough that a 32dp hit area would be a
    // miss-tap farm.
    Box(
        Modifier
            .size(48.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(
                    if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                    else androidx.compose.ui.graphics.Color.Transparent
                ),
            contentAlignment = Alignment.Center
        ) {
            FgIcon(
                icon,
                if (selected) "$name chart, selected" else "$name chart",
                size = 17.dp,
                tint = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun StepButton(icon: androidx.compose.ui.graphics.vector.ImageVector, rotated: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier
            .size(48.dp)
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
            .padding(horizontal = 11.dp, vertical = 8.dp)
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


/**
 * "47 reels today" under the chart. Collapsed it is one line; tapped it opens
 * the per-source split, so the single number never needs a screen of its own.
 * Hidden entirely when the counter is off or nothing has been counted.
 */
@Composable
private fun ReelCounterRow(day: String) {
    val container = LocalAppContainer.current
    val enabled by container.reelCounterOn.collectAsState()
    var rows by remember(day) { mutableStateOf<List<ReelCount>>(emptyList()) }
    var expanded by rememberSaveable(day) { mutableStateOf(false) }

    LaunchedEffect(day, enabled) {
        if (!enabled) return@LaunchedEffect
        container.db.reelCountDao().forDayFlow(day).collect { rows = it }
    }

    if (!enabled || rows.isEmpty()) return
    val total = rows.sumOf { it.count }

    FgCard(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .semantics(mergeDescendants = true) {
                    // One node for TalkBack: "65 reels today, Collapsed".
                    stateDescription = if (expanded) "Expanded" else "Collapsed"
                }
                .padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "$total ${if (total == 1) "reel" else "reels"} today",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f)
            )
            FgIcon(
                if (expanded) FgIcons.ChevronUp else FgIcons.ChevronDown,
                if (expanded) "Hide per-app counts" else "Show per-app counts",
                size = 18.dp
            )
        }
        if (expanded) {
            rows.forEach { row ->
                FgDivider(inset = 16.dp)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AppIcon(row.packageName, size = 30.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        appLabel(LocalContext.current, row.packageName),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        row.count.toString(),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}


@Composable
private fun AppUsageRow(pkg: String, primary: String, share: Float, onClick: () -> Unit) {
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
                primary,
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

