package dev.opensource.attenora.ui.blocking

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.opensource.attenora.data.ElementRule
import dev.opensource.attenora.enforcement.Detectors
import dev.opensource.attenora.enforcement.AttenoraAccessibilityService
import dev.opensource.attenora.ui.common.AppIcon
import dev.opensource.attenora.ui.common.FgCard
import dev.opensource.attenora.ui.common.FgIcon
import dev.opensource.attenora.ui.common.FirstRunHint
import dev.opensource.attenora.ui.common.LocalAppContainer
import dev.opensource.attenora.ui.common.PageHeader
import dev.opensource.attenora.ui.common.appLabel
import dev.opensource.attenora.ui.common.formatDuration
import dev.opensource.attenora.ui.common.isAccessibilityServiceEnabled
import dev.opensource.attenora.ui.common.openAccessibilitySettings
import dev.opensource.attenora.ui.icons.FgIcons
import dev.opensource.attenora.ui.limits.rememberChallengeGate
import dev.opensource.attenora.ui.theme.FgColors
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * In-App Blocking: per-app cards with element toggles, a 7-day element
 * donut, honest reliability caveats, and a detector test.
 */
@Composable
fun InAppBlockingScreen() {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val gate = rememberChallengeGate()

    val rules by container.db.elementRuleDao().observeAll()
        .collectAsState(initial = emptyList())
    var serviceOn by remember {
        mutableStateOf(isAccessibilityServiceEnabled(context, AttenoraAccessibilityService::class.java))
    }

    // Re-check whenever we return from system settings — the service can be
    // enabled while this screen is stopped, and `remember` alone would keep
    // showing "off" forever.
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                serviceOn = isAccessibilityServiceEnabled(
                    context,
                    AttenoraAccessibilityService::class.java
                )
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    var expandedApp by remember { mutableStateOf<String?>(null) }

    // "pkg|element" -> enabled
    val enabledMap = remember(rules) {
        rules.filter { it.enabled }.associate { "${it.packageName}|${it.element}" to true }
    }

    // 7-day element usage per package.
    var donutData by remember { mutableStateOf<Map<String, List<Pair<String, Long>>>>(emptyMap()) }
    LaunchedEffect(Unit) {
        val end = LocalDate.now()
        val from = end.minusDays(6).toString()
        val to = end.toString()
        val out = HashMap<String, List<Pair<String, Long>>>()
        for ((pkg, _) in Detectors.uiCatalog) {
            val rows = container.db.elementUsageDao().range(pkg, from, to)
            if (rows.isNotEmpty()) {
                out[pkg] = rows.groupBy { it.element }
                    .mapValues { (_, r) -> r.sumOf { it.ms } }
                    .toList()
                    .sortedByDescending { it.second }
            }
        }
        donutData = out
    }

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            PageHeader(title = "In-App Blocking")
        }
        item {
            FirstRunHint(
                id = "inapp",
                text = "Turn off the parts of an app that pull you in — feeds, Reels, Shorts, messages — app by app, without blocking the app itself."
            )
        }
        item {
            Text(
                "Customize your social media visits — hide the parts of apps that pull you in. " +
                    "These are heuristics against other apps' screens, so they may stop working " +
                    "when the target app updates.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
            )
        }
        item {
            // Service status row.
            FgCard(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                Row(
                    Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier
                            .size(10.dp)
                            .clip(RoundedCornerShape(50))
                            .background(if (serviceOn) Color(0xFF34C77B) else Color(0xFFFF6B6B))
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        if (serviceOn) "Attenora service enabled — element blocking is active"
                        else "Attenora accessibility service is off — element blocking is inactive",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f)
                    )
                    if (!serviceOn) {
                        OutlinedButton(onClick = {
                            openAccessibilitySettings(context, AttenoraAccessibilityService::class.java)
                        }) { Text("Enable") }
                    }
                }
            }
        }

        items(Detectors.uiCatalog, key = { it.first }) { (pkg, elements) ->
            val expanded = expandedApp == pkg
            FgCard(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                Column(Modifier.animateContentSize()) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { expandedApp = if (expanded) null else pkg }
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AppIcon(pkg, size = 40.dp)
                        Spacer(Modifier.width(14.dp))
                        Text(
                            appLabel(context, pkg),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f)
                        )
                        FgIcon(
                            if (expanded) FgIcons.ChevronUp else FgIcons.ChevronDown,
                            null,
                            size = 22.dp
                        )
                    }

                    if (expanded) {
                        val segments = donutData[pkg].orEmpty()
                        if (segments.isNotEmpty()) {
                            Spacer(Modifier.height(4.dp))
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "Last 7 Days of Usage",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.weight(1f)
                                )
                                FgIcon(FgIcons.Info, "Attributed while elements are visible", size = 16.dp)
                            }
                            Spacer(Modifier.height(8.dp))
                            ElementDonut(
                                segments = segments,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp)
                            )
                            Spacer(Modifier.height(12.dp))
                        }

                        elements.forEach { element ->
                            val key = "${pkg}|${element.key}"
                            val enabled = enabledMap[key] == true
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        gate.request {
                                            scope.launch {
                                                container.db.elementRuleDao().upsert(
                                                    ElementRule(pkg, element.key, !enabled)
                                                )
                                            }
                                        }
                                    }
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                ElementIcon(element.icon)
                                Spacer(Modifier.width(14.dp))
                                Text(
                                    "Block ${element.label.lowercase()}",
                                    style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier.weight(1f)
                                )
                                Switch(
                                    checked = enabled,
                                    onCheckedChange = { on ->
                                        gate.request {
                                            scope.launch {
                                                container.db.elementRuleDao()
                                                    .upsert(ElementRule(pkg, element.key, on))
                                            }
                                        }
                                    }
                                )
                            }
                        }

                        Spacer(Modifier.height(6.dp))
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                if (serviceOn) "Test: open ${appLabel(context, pkg)} and check that hidden elements stay hidden."
                                else "Enable the service to test.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(Modifier.width(8.dp))
                            OutlinedButton(enabled = serviceOn, onClick = {
                                runCatching {
                                    context.startActivity(
                                        context.packageManager.getLaunchIntentForPackage(pkg)
                                            ?: return@OutlinedButton
                                    )
                                }
                            }) { Text("Test") }
                        }
                        Spacer(Modifier.height(6.dp))
                    }
                }
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

/** Small square-ish icon per element type, from the custom set. */
@Composable
private fun ElementIcon(kind: String) {
    val icon = when (kind) {
        "shorts", "reels", "spotlight" -> FgIcons.ChartLine
        "search", "video_search" -> FgIcons.Search
        "comments" -> FgIcons.Info
        "stories" -> FgIcons.EyeOff
        "explore" -> FgIcons.Globe
        "pip" -> FgIcons.Calendar
        "channels" -> FgIcons.Shapes
        "status" -> FgIcons.Bell
        else -> FgIcons.Block
    }
    FgIcon(icon, null, size = 22.dp)
}

/**
 * Hand-drawn donut: arcs per element with the two largest percentages
 * labeled inside (matching the reference design).
 */
@Composable
private fun ElementDonut(segments: List<Pair<String, Long>>, modifier: Modifier = Modifier) {
    val total = segments.sumOf { it.second }.coerceAtLeast(1L)
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        // Legend
        Column(
            Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            segments.forEachIndexed { index, (name, ms) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(12.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(FgColors.chartColor(index))
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        elementLabel(name),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        "${"%.1f".format(ms * 100.0 / total)}%",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
        Spacer(Modifier.width(16.dp))
        Box(Modifier.size(150.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val stroke = 18.dp.toPx()
                val inset = stroke / 2 + 2.dp.toPx()
                val arcSize = Size(size.width - inset * 2, size.height - inset * 2)
                var startAngle = -90f
                segments.forEachIndexed { index, (_, ms) ->
                    val sweep = (ms.toFloat() / total) * 360f
                    drawArc(
                        color = FgColors.chartColor(index),
                        startAngle = startAngle,
                        sweepAngle = (sweep - 1.5f).coerceAtLeast(0.5f),
                        useCenter = false,
                        topLeft = Offset(inset, inset),
                        size = arcSize,
                        style = Stroke(width = stroke)
                    )
                    startAngle += sweep
                }
            }
            if (segments.size == 1) {
                Text(
                    "${"%.1f".format(segments[0].second * 100.0 / total)}%",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

private fun elementLabel(key: String): String = when (key) {
    "shorts" -> "Shorts"
    "video_search" -> "Search"
    "pip" -> "PiP"
    "comments" -> "Comments"
    "stories" -> "Story"
    "reels" -> "Reels"
    "explore" -> "Explore tab"
    "spotlight" -> "Spotlight"
    "search" -> "Search"
    "status" -> "Status"
    "channels" -> "Channels"
    "meta_ai" -> "Meta AI"
    else -> key
}
