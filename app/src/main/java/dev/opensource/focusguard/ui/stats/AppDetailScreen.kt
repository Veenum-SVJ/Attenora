package dev.opensource.focusguard.ui.stats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.opensource.focusguard.data.HiddenItem
import dev.opensource.focusguard.data.HiddenKind
import dev.opensource.focusguard.domain.CategoryClassifier
import dev.opensource.focusguard.ui.common.AppIcon
import dev.opensource.focusguard.ui.common.FgCard
import dev.opensource.focusguard.ui.common.FgIcon
import dev.opensource.focusguard.ui.common.LocalAppContainer
import dev.opensource.focusguard.ui.common.PageHeader
import dev.opensource.focusguard.ui.common.appLabel
import dev.opensource.focusguard.ui.common.formatDuration
import dev.opensource.focusguard.ui.icons.FgIcons
import kotlinx.coroutines.launch
import java.time.LocalDate

/** Per-app history + quick actions ("mini history + set limit"). */
@Composable
fun AppDetailScreen(
    pkg: String,
    onBack: () -> Unit,
    onSetLimit: () -> Unit,
    onBlockInApp: () -> Unit
) {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var weekRows by remember { mutableStateOf<List<Pair<String, Long>>>(emptyList()) }
    var todayMs by remember { mutableStateOf(0L) }
    var weekTotal by remember { mutableStateOf(0L) }
    var categoryNames by remember { mutableStateOf<List<String>>(emptyList()) }

    LaunchedEffect(pkg) {
        runCatching { container.usage.reconcileNow() }
        val end = LocalDate.now()
        val from = end.minusDays(6).toString()
        val to = end.toString()
        val rows = container.db.dailyUsageDao().range(from, to).filter { it.packageName == pkg }
        weekRows = rows.map { it.day to it.usedMs }.sortedBy { it.first }
        weekTotal = rows.sumOf { it.usedMs }
        todayMs = container.usage.usedMsToday(pkg)
        val key = "app:$pkg"
        val catIds = container.db.categoryMemberDao().all().filter { it.key == key }.map { it.categoryId }
        categoryNames = container.db.categoryDao().all().filter { it.id in catIds }.map { it.name }
    }

    val label = remember(pkg) { appLabel(context, pkg) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        PageHeader(title = label, onBack = onBack, subtitle = pkg)

        FgCard(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AppIcon(pkg, size = 52.dp)
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text(
                            "Today: ${if (todayMs <= 0) "0m" else formatDuration(todayMs)}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            "Last 7 days: ${formatDuration(weekTotal)}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(Modifier.height(18.dp))
                if (weekRows.isEmpty()) {
                    Text(
                        "No usage in the last 7 days",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    val max = weekRows.maxOf { it.second }.coerceAtLeast(1L)
                    UsageChart(
                        bars = weekRows.map { (day, ms) ->
                            ChartBar(LocalDate.parse(day).dayOfMonth.toString(), ms)
                        },
                        style = ChartStyle.BAR
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Peak day: ${formatDuration(max)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        FgCard(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
            Row(
                Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FgIcon(FgIcons.Shapes, null, size = 20.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Category", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        categoryNames.ifEmpty {
                            listOf(CategoryClassifier.classify(pkg, label))
                        }.joinToString(", "),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(onClick = onSetLimit, modifier = Modifier.weight(1f)) {
                FgIcon(FgIcons.Gauge, null, size = 18.dp)
                Spacer(Modifier.width(8.dp))
                Text("Set a limit")
            }
            OutlinedButton(onClick = onBlockInApp, modifier = Modifier.weight(1f)) {
                FgIcon(FgIcons.Grid, null, size = 18.dp)
                Spacer(Modifier.width(8.dp))
                Text("In-app blocking")
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}
