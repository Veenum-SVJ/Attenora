package dev.opensource.focusguard.ui.stats

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.opensource.focusguard.data.DailyUsage
import dev.opensource.focusguard.data.HourlyUsage
import dev.opensource.focusguard.data.LimitType
import dev.opensource.focusguard.data.TargetKind
import dev.opensource.focusguard.data.UsageLimit
import dev.opensource.focusguard.domain.CategoryClassifier
import dev.opensource.focusguard.enforcement.Reels
import dev.opensource.focusguard.ui.common.AppIcon
import dev.opensource.focusguard.ui.common.FgCard
import dev.opensource.focusguard.ui.common.FgDivider
import dev.opensource.focusguard.ui.common.FgGroup
import dev.opensource.focusguard.ui.common.FgIcon
import dev.opensource.focusguard.ui.common.LocalAppContainer
import dev.opensource.focusguard.ui.common.PageHeader
import dev.opensource.focusguard.ui.common.appLabel
import dev.opensource.focusguard.ui.common.formatDuration
import dev.opensource.focusguard.ui.icons.FgIcons
import dev.opensource.focusguard.ui.theme.fg
import dev.opensource.focusguard.ui.theme.fgTabular
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** Ranges the per-app chart can show. "Today" is by clock hour. */
private enum class DetailPeriod(val label: String, val days: Int) {
    TODAY("Today", 1),
    D7("7 days", 7),
    D30("30 days", 30)
}

/**
 * One app's usage, the way StayFree shows it: how much today, an hourly (or
 * per-day) chart, the numbers behind it, and the two things you can do about
 * it — set a limit or block it from the inside.
 */
@Composable
fun AppDetailScreen(
    pkg: String,
    onBack: () -> Unit,
    onSetLimit: () -> Unit,
    onBlockInApp: () -> Unit
) {
    val container = LocalAppContainer.current
    val context = LocalContext.current

    val label = remember(pkg) { appLabel(context, pkg) }
    val reelsOn by container.reelCounterOn.collectAsState()

    var period by rememberSaveable { mutableStateOf(DetailPeriod.TODAY) }

    var dailyRows by remember { mutableStateOf<List<DailyUsage>>(emptyList()) }
    var hourly by remember { mutableStateOf(LongArray(24)) }
    var todayMs by remember { mutableStateOf(0L) }
    var reelsToday by remember { mutableStateOf(0) }
    var limits by remember { mutableStateOf<List<UsageLimit>>(emptyList()) }
    var categoryName by remember { mutableStateOf("") }

    // 30 days is the widest range the chart offers, so one read covers every
    // period and switching tabs never re-queries.
    LaunchedEffect(pkg) {
        runCatching { container.usage.reconcileNow() }
        val todayKey = container.usage.todayKey()
        val from = LocalDate.parse(todayKey).minusDays(29).toString()

        dailyRows = container.db.dailyUsageDao()
            .range(from, todayKey)
            .filter { it.packageName == pkg }

        todayMs = container.usage.usedMsToday(pkg)

        val hours = LongArray(24)
        container.db.hourlyUsageDao().range(todayKey, todayKey)
            .filter { it.packageName == pkg }
            .forEach { row: HourlyUsage ->
                if (row.hour in 0..23) hours[row.hour] = row.ms
            }
        hourly = hours

        // Lite and variant builds share one canonical counter (see Reels).
        reelsToday = container.db.reelCountDao()
            .forApp(Reels.creditKey(pkg), todayKey)?.count ?: 0

        val key = "app:$pkg"
        val catIds = container.db.categoryMemberDao().all().filter { it.key == key }.map { it.categoryId }
        categoryName = container.db.categoryDao().all()
            .filter { it.id in catIds }
            .map { it.name }
            .ifEmpty { listOf(CategoryClassifier.classify(pkg, label)) }
            .joinToString(", ")
    }

    // Live: adding or editing a limit should show up here without leaving the page.
    LaunchedEffect(pkg) {
        container.db.usageLimitDao().observeAll().collect { all ->
            limits = all.filter { it.targetKind == TargetKind.APP && it.targetId == pkg }
        }
    }

    val todayKey = container.usage.todayKey()
    val rangeStart = remember(period, todayKey) {
        LocalDate.parse(todayKey).minusDays((period.days - 1).toLong())
    }
    val inRange = remember(dailyRows, rangeStart) {
        dailyRows.filter { LocalDate.parse(it.day) >= rangeStart }
    }

    val periodTotal = if (period == DetailPeriod.TODAY) todayMs else inRange.sumOf { it.usedMs }
    val periodOpens = if (period == DetailPeriod.TODAY) {
        inRange.firstOrNull { it.day == todayKey }?.launches ?: 0
    } else {
        inRange.sumOf { it.launches }
    }

    val chartBars = remember(dailyRows, hourly, period, todayKey) {
        if (period == DetailPeriod.TODAY) {
            val active = (0..23).filter { hourly[it] > 0 }
            if (active.isEmpty()) emptyList()
            else (active.first()..active.last()).map { h ->
                ChartBar(h.toString(), hourly[h])
            }
        } else {
            val n = period.days
            val first = LocalDate.parse(todayKey).minusDays((n - 1).toLong())
            val byDay = dailyRows.associateBy { it.day }
            (0 until n).map { offset ->
                val day = first.plusDays(offset.toLong())
                ChartBar(day.dayOfMonth.toString(), byDay[day.toString()]?.usedMs ?: 0L)
            }
        }
    }

    val appLimit = limits.firstOrNull { it.enabled } ?: limits.firstOrNull()

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        PageHeader(title = label, onBack = onBack, subtitle = pkg)

        // Today, at a glance.
        FgCard(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
            Row(
                Modifier.padding(18.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AppIcon(pkg, size = 56.dp)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "Today",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        if (todayMs <= 0) "0m" else formatDuration(todayMs),
                        style = fgTabular(MaterialTheme.typography.headlineMedium),
                        fontWeight = FontWeight.Bold,
                        color = if (appLimit?.type == LimitType.DAILY &&
                            appLimit.dailyLimitMs > 0 &&
                            todayMs >= appLimit.dailyLimitMs
                        ) MaterialTheme.fg.danger else MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "${
                            inRange.firstOrNull { it.day == todayKey }?.launches ?: 0
                        } opens today" +
                            if (reelsOn && reelsToday > 0) {
                                " · $reelsToday ${if (reelsToday == 1) "reel" else "reels"}"
                            } else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Range.
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            DetailPeriod.values().forEach { p ->
                PeriodChip(p.label, p == period) { period = p }
            }
        }

        // Chart.
        FgCard(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
            Column(Modifier.padding(16.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        if (period == DetailPeriod.TODAY) "By hour" else "By day",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        if (periodTotal <= 0) "0m" else formatDuration(periodTotal),
                        style = fgTabular(MaterialTheme.typography.bodyMedium),
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Spacer(Modifier.height(12.dp))
                if (chartBars.none { it.valueMs > 0L }) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(200.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            if (period == DetailPeriod.TODAY) "Not used today"
                            else "No usage in this range",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    UsageChart(bars = chartBars, style = ChartStyle.BAR)
                }
            }
        }

        // The numbers behind the chart.
        FgGroup(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
            StatRow(
                label = if (period == DetailPeriod.TODAY) "Opens today" else "Opens",
                value = periodOpens.toString()
            )
            if (period != DetailPeriod.TODAY) {
                FgDivider(inset = 20.dp)
                StatRow(
                    label = "Daily average",
                    value = formatDuration(periodTotal / period.days)
                )
                FgDivider(inset = 20.dp)
                val busiest = inRange.maxByOrNull { it.usedMs }
                StatRow(
                    label = "Busiest day",
                    value = if (busiest == null || busiest.usedMs <= 0) "—"
                    else "${stamp(busiest.day)} · ${formatDuration(busiest.usedMs)}"
                )
            } else {
                FgDivider(inset = 20.dp)
                val peakHour = (0..23).maxByOrNull { hourly[it] } ?: 0
                StatRow(
                    label = "Busiest hour",
                    value = if (hourly[peakHour] <= 0) "—"
                    else "${peakHour.toString().padStart(2, '0')}:00 · ${
                        formatDuration(hourly[peakHour])
                    }"
                )
                if (reelsOn && reelsToday > 0) {
                    FgDivider(inset = 20.dp)
                    StatRow(label = "Reels today", value = reelsToday.toString())
                }
            }
            FgDivider(inset = 20.dp)
            StatRow(label = "Category", value = categoryName)
        }

        // What you can do about it.
        FgGroup(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
            if (appLimit != null && appLimit.type == LimitType.DAILY && appLimit.dailyLimitMs > 0) {
                LimitProgress(usedMs = todayMs, limitMs = appLimit.dailyLimitMs)
                FgDivider(inset = 20.dp)
            }
            FgIconRow(
                icon = FgIcons.Gauge,
                title = if (appLimit == null) "Set a daily limit"
                else "Edit the ${
                    when (appLimit.type) {
                        LimitType.DAILY -> "daily"
                        LimitType.SESSION -> "session"
                        LimitType.SCHEDULE -> "schedule"
                        else -> "permanent"
                    }
                } limit",
                subtitle = "Change the limit for this app",
                onClick = onSetLimit
            )
            FgDivider(inset = 56.dp)
            FgIconRow(
                icon = FgIcons.Grid,
                title = "In-app blocking",
                subtitle = "Block the feed and shorts inside the app",
                onClick = onBlockInApp
            )
        }

        Spacer(Modifier.height(24.dp))
    }
}

// ---- pieces ----------------------------------------------------------------

/** "Oct 19" from a usage-day key. */
private fun stamp(day: String): String = runCatching {
    DateTimeFormatter.ofPattern("MMM d").format(LocalDate.parse(day))
}.getOrDefault(day)

@Composable
private fun StatRow(label: String, value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f)
        )
        Text(
            value,
            style = fgTabular(MaterialTheme.typography.bodyMedium),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun PeriodChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(50))
            .background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                else MaterialTheme.colorScheme.surface
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center
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

/** Today's progress against a daily limit, with the bar turning red when spent. */
@Composable
private fun LimitProgress(usedMs: Long, limitMs: Long) {
    val spent = usedMs >= limitMs
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Daily limit",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f)
            )
            Text(
                formatDuration(limitMs),
                style = fgTabular(MaterialTheme.typography.bodyMedium),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(10.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.outlineVariant)
        ) {
            Box(
                Modifier
                    .fillMaxWidth((usedMs.toFloat() / limitMs.coerceAtLeast(1L)).coerceIn(0.02f, 1f))
                    .height(6.dp)
                    .clip(RoundedCornerShape(50))
                    .background(if (spent) MaterialTheme.fg.danger else MaterialTheme.colorScheme.primary)
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            if (spent) "Limit reached · ${formatDuration(usedMs)} used today"
            else "${formatDuration(usedMs)} of ${formatDuration(limitMs)} used today",
            style = MaterialTheme.typography.bodySmall,
            color = if (spent) MaterialTheme.fg.danger else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** [NavRow] with a leading icon, kept local so the action list stays readable. */
@Composable
private fun FgIconRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FgIcon(icon, null, size = 22.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        FgIcon(
            FgIcons.ChevronRight,
            null,
            size = 18.dp,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        )
    }
}
