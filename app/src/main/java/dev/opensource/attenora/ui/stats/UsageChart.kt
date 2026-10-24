package dev.opensource.attenora.ui.stats

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.opensource.attenora.ui.common.formatDuration
import kotlin.math.min
import kotlin.math.sqrt

/** One chart column. */
data class ChartBar(val label: String, val valueMs: Long)

/** Heatmap row: day label -> 24 hourly millisecond totals. */
data class HeatRow(val label: String, val hours: LongArray)

enum class ChartStyle { BAR, LINE, DONUT, HEATMAP }

/** Shared slice palette for the donut (assigned by package-name hash). */
val ChartSliceColors = listOf(
    Color(0xFF7C6BF2), // lavender accent
    Color(0xFF34C77B),
    Color(0xFFF2A65A),
    Color(0xFF4FA3E3),
    Color(0xFFE36A8A),
    Color(0xFF67C7C2)
)

fun sliceColorFor(key: String): Color =
    ChartSliceColors[Math.floorMod(key.hashCode(), ChartSliceColors.size)]

/**
 * Hand-drawn usage chart (no chart library). BAR and LINE share this column
 * canvas; DONUT and HEATMAP have their own composables below. Every style
 * replays its entrance animation on switch, so toggling always reads as a real
 * change. Pure Compose Canvas.
 */
@Composable
fun UsageChart(
    bars: List<ChartBar>,
    style: ChartStyle,
    modifier: Modifier = Modifier,
    accent: Color = MaterialThemeColors.accent
) {
    val measurer = rememberTextMeasurer()
    var selectedIndex by remember(bars, style) { mutableIntStateOf(-1) }
    val entrance = remember(style) { Animatable(0f) }
    LaunchedEffect(style) { entrance.animateTo(1f, tween(500)) }
    val anim = entrance.value

    val labelColor = MaterialThemeColors.label
    val trackColor = MaterialThemeColors.track
    val valueColor = MaterialThemeColors.value
    val surfaceColor = MaterialThemeColors.surface

    Box(
        modifier
            .fillMaxWidth()
            .height(230.dp)
            .pointerInput(bars, style) {
                detectTapGestures { offset ->
                    if (bars.isEmpty()) return@detectTapGestures
                    val slot = size.width.toFloat() / bars.size
                    selectedIndex = (offset.x / slot).toInt().coerceIn(0, bars.size - 1)
                }
            }
    ) {
        Canvas(Modifier.fillMaxWidth().height(230.dp)) {
            if (bars.isEmpty()) return@Canvas
            val labelArea = 44f
            val topValueArea = 34f
            val chartTop = topValueArea
            val chartHeight = size.height - chartTop - labelArea
            val chartWidth = size.width
            val maxValue = bars.maxOf { it.valueMs }.coerceAtLeast(1L)
            val slot = chartWidth / bars.size

            // Baseline
            drawLine(
                color = trackColor,
                start = Offset(0f, chartTop + chartHeight),
                end = Offset(chartWidth, chartTop + chartHeight),
                strokeWidth = 2f
            )

            if (style == ChartStyle.BAR) {
                // Cap the bar width so a single bar (e.g. one active hour on
                // the app-detail chart) reads as a bar, not a full-width block.
                val barWidth = (slot * 0.58f).coerceAtMost(72f)
                bars.forEachIndexed { index, bar ->
                    if (bar.valueMs <= 0L) return@forEachIndexed
                    val h = (bar.valueMs.toFloat() / maxValue) * chartHeight * anim
                    val left = index * slot + (slot - barWidth) / 2f
                    val selected = index == selectedIndex
                    val color = if (selected) accent else accent.copy(alpha = 0.85f)
                    drawRoundRect(
                        color = color,
                        topLeft = Offset(left, chartTop + chartHeight - h),
                        size = Size(barWidth, h),
                        cornerRadius = CornerRadius(6f, 6f)
                    )
                    if (bar.valueMs > 0 && (selected || bars.size <= 14 || index % ((bars.size + 13) / 14) == 0)) {
                        val text = formatDuration(bar.valueMs)
                        val layout = measurer.measure(
                            text,
                            TextStyle(color = valueColor, fontSize = 11.sp)
                        )
                        drawText(
                            layout,
                            topLeft = Offset(
                                (index * slot + slot / 2f) - layout.size.width / 2f,
                                (chartTop + chartHeight - h - layout.size.height - 4f).coerceAtLeast(0f)
                            )
                        )
                    }
                }
            } else {
                // Line: thicker stroke, shaded area and visible nodes so the
                // styles are unmistakably different at a glance.
                val points = bars.mapIndexed { index, bar ->
                    Offset(
                        index * slot + slot / 2f,
                        chartTop + chartHeight - (bar.valueMs.toFloat() / maxValue) * chartHeight * anim
                    )
                }
                val path = Path()
                points.forEachIndexed { i, p ->
                    if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
                }
                val area = Path().apply {
                    addPath(path)
                    lineTo(points.last().x, chartTop + chartHeight)
                    lineTo(points.first().x, chartTop + chartHeight)
                    close()
                }
                clipRect(top = chartTop) {
                    drawPath(area, accent.copy(alpha = 0.22f))
                }
                drawPath(path, accent, style = Stroke(width = 5f, cap = StrokeCap.Round))
                points.forEachIndexed { i, p ->
                    val selected = i == selectedIndex
                    drawCircle(color = surfaceColor, radius = 5f, center = p)
                    drawCircle(
                        color = accent,
                        radius = if (selected) 8f else 3.5f,
                        center = p
                    )
                }
                if (selectedIndex in bars.indices) {
                    val p = points[selectedIndex]
                    val layout = measurer.measure(
                        formatDuration(bars[selectedIndex].valueMs),
                        TextStyle(color = valueColor, fontSize = 12.sp)
                    )
                    drawText(
                        layout,
                        topLeft = Offset(
                            (p.x - layout.size.width / 2f).coerceAtLeast(0f),
                            (p.y - layout.size.height - 12f).coerceAtLeast(0f)
                        )
                    )
                }
            }

            // X labels (thinned out for dense charts).
            val every = (bars.size + 9) / 10
            bars.forEachIndexed { index, bar ->
                if (index % every != 0) return@forEachIndexed
                val text = measurer.measure(
                    bar.label,
                    TextStyle(color = labelColor, fontSize = 11.sp)
                )
                drawText(
                    text,
                    topLeft = Offset(
                        (index * slot + slot / 2f) - text.size.width / 2f,
                        chartTop + chartHeight + 12f
                    )
                )
            }

            // Highlighted column guide.
            if (selectedIndex in bars.indices) {
                drawLine(
                    color = accent.copy(alpha = 0.35f),
                    start = Offset(selectedIndex * slot + slot / 2f, chartTop),
                    end = Offset(selectedIndex * slot + slot / 2f, chartTop + chartHeight),
                    strokeWidth = 2f
                )
            }
        }
    }
}

/** Donut: each time bucket as a slice of the range total, total in the hole. */
@Composable
fun DonutChart(
    bars: List<ChartBar>,
    modifier: Modifier = Modifier
) {
    val entrance = remember { Animatable(0f) }
    LaunchedEffect(Unit) { entrance.animateTo(1f, tween(600)) }
    val total = bars.sumOf { it.valueMs }
    val subLabel = MaterialThemeColors.label

    Box(
        modifier
            .fillMaxWidth()
            .height(230.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.fillMaxWidth().height(230.dp)) {
            if (total <= 0L) return@Canvas
            val diameter = min(size.width, size.height) * 0.66f
            val stroke = diameter * 0.16f
            val radius = diameter / 2f
            val center = Offset(size.width / 2f, size.height / 2f)
            val topLeft = Offset(center.x - radius, center.y - radius)
            val arcSize = Size(diameter, diameter)
            val colorable = bars.filter { it.valueMs > 0L }
            var start = -90f
            colorable.forEachIndexed { i, bar ->
                val full = (bar.valueMs.toFloat() / total) * 360f
                val sweep = full * entrance.value
                val color = ChartSliceColors[i % ChartSliceColors.size]
                drawArc(
                    color = color,
                    startAngle = start + 1.5f,
                    sweepAngle = (sweep - 3f).coerceAtLeast(1.2f),
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Butt)
                )
                start += full
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            androidx.compose.material3.Text(
                if (total <= 0) "0m" else formatDuration(total),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            androidx.compose.material3.Text(
                "total",
                style = MaterialTheme.typography.bodySmall,
                color = subLabel
            )
        }
        if (total > 0) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                bars.filter { it.valueMs > 0L }.take(4).forEachIndexed { i, bar ->
                    androidx.compose.foundation.Canvas(Modifier.size(8.dp)) {
                        drawCircle(ChartSliceColors[i % ChartSliceColors.size])
                    }
                    Spacer(Modifier.width(4.dp))
                    androidx.compose.material3.Text(
                        bar.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = subLabel
                    )
                    if (i < 3) Spacer(Modifier.width(10.dp))
                }
            }
        }
    }
}

/** GitHub-style hour × day grid; upright habit view for 7/14/28-day ranges. */
@Composable
fun HeatmapChart(
    rows: List<HeatRow>,
    modifier: Modifier = Modifier,
    accent: Color = MaterialThemeColors.accent
) {
    val measurer = rememberTextMeasurer()
    val entrance = remember { Animatable(0f) }
    LaunchedEffect(Unit) { entrance.animateTo(1f, tween(600)) }
    val labelColor = MaterialThemeColors.label
    // Captured here — Canvas lambdas are not composable context.
    val trackColor = MaterialThemeColors.track

    Box(
        modifier
            .fillMaxWidth()
            .height(230.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.fillMaxWidth().height(230.dp)) {
            if (rows.isEmpty()) return@Canvas
            val labelW = 46f
            val bottomAxis = 22f
            val topPad = 4f
            val gridW = size.width - labelW - 4f
            val gridH = size.height - topPad - bottomAxis
            val cellW = gridW / 24f
            val cellH = gridH / rows.size
            val gap = 2f
            val maxV = rows.maxOf { r -> r.hours.max() }.coerceAtLeast(1L)

            rows.forEachIndexed { r, row ->
                val y = topPad + r * cellH
                val dayLayout = measurer.measure(
                    row.label,
                    TextStyle(color = labelColor, fontSize = 10.sp)
                )
                drawText(
                    dayLayout,
                    topLeft = Offset(0f, y + (cellH - dayLayout.size.height) / 2f)
                )
                for (h in 0 until 24) {
                    val v = row.hours[h]
                    val intensity = sqrt(v.toFloat() / maxV) * entrance.value
                    drawRoundRect(
                        color = if (v <= 0L) {
                            trackColor.copy(alpha = 0.35f)
                        } else accent.copy(alpha = 0.10f + 0.90f * intensity),
                        topLeft = Offset(labelW + h * cellW, y),
                        size = Size(
                            (cellW - gap).coerceAtLeast(1f),
                            (cellH - gap).coerceAtLeast(1f)
                        ),
                        cornerRadius = CornerRadius(3f, 3f)
                    )
                }
            }

            // Hour axis: 0, 6, 12, 18, 24.
            listOf(0, 6, 12, 18, 23).forEach { h ->
                val text = measurer.measure(
                    h.toString().padStart(2, '0'),
                    TextStyle(color = labelColor, fontSize = 10.sp)
                )
                drawText(
                    text,
                    topLeft = Offset(
                        (labelW + h * cellW + (cellW - text.size.width) / 2f)
                            .coerceAtLeast(0f),
                        size.height - bottomAxis + 6f
                    )
                )
            }
        }
        androidx.compose.material3.Text(
            "hour of day →",
            style = MaterialTheme.typography.labelSmall,
            color = labelColor,
            modifier = Modifier.align(Alignment.BottomEnd)
        )
    }
}

/** Compact color key for the donut. */
@Composable
fun SliceKey(labels: List<String>) {
    if (labels.isEmpty()) return
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        labels.take(4).forEachIndexed { i, label ->
            androidx.compose.foundation.Canvas(Modifier.size(8.dp)) {
                drawCircle(ChartSliceColors[i % ChartSliceColors.size])
            }
            Spacer(Modifier.width(4.dp))
            androidx.compose.material3.Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialThemeColors.label,
                maxLines = 1
            )
            if (i < 3) Spacer(Modifier.width(10.dp))
        }
    }
}

/** Theme-aware chart colors without importing MaterialTheme inside Canvas. */
private object MaterialThemeColors {
    val accent @Composable get() = androidx.compose.material3.MaterialTheme.colorScheme.primary
    val label @Composable get() = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
    val track @Composable get() = androidx.compose.material3.MaterialTheme.colorScheme.outline
    val value @Composable get() = androidx.compose.material3.MaterialTheme.colorScheme.onSurface
    val surface @Composable get() = androidx.compose.material3.MaterialTheme.colorScheme.surface
}