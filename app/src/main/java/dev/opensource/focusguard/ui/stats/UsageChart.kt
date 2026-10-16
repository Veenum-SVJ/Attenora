package dev.opensource.focusguard.ui.stats

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.opensource.focusguard.ui.common.formatDuration

/** One chart column. */
data class ChartBar(val label: String, val valueMs: Long)

enum class ChartStyle { BAR, LINE }

/**
 * Hand-drawn usage chart (no chart library): bars or line, entrance
 * animation, value labels, tap-to-highlight. Pure Compose Canvas.
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
    val progress by animateFloatAsState(
        targetValue = 1f,
        animationSpec = tween(500),
        label = "chart-entrance"
    )

    val labelColor = MaterialThemeColors.label
    val trackColor = MaterialThemeColors.track
    val valueColor = MaterialThemeColors.value

    Box(
        modifier
            .fillMaxWidth()
            .height(230.dp)
            .pointerInput(bars) {
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
            val anim = progress.coerceIn(0f, 1f)

            // Baseline
            drawLine(
                color = trackColor,
                start = Offset(0f, chartTop + chartHeight),
                end = Offset(chartWidth, chartTop + chartHeight),
                strokeWidth = 2f
            )

            if (style == ChartStyle.BAR) {
                val barWidth = slot * 0.58f
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
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(6f, 6f)
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
                drawPath(path, accent, style = Stroke(width = 4f, cap = StrokeCap.Round))
                // Area under the line for depth.
                val area = Path().apply {
                    addPath(path)
                    lineTo(points.last().x, chartTop + chartHeight)
                    lineTo(points.first().x, chartTop + chartHeight)
                    close()
                }
                clipRect(top = chartTop) {
                    drawPath(area, accent.copy(alpha = 0.14f))
                }
                points.forEachIndexed { i, p ->
                    val selected = i == selectedIndex
                    drawCircle(
                        color = if (selected) accent else accent,
                        radius = if (selected) 8f else 5f,
                        center = p
                    )
                    if (selected) {
                        val layout = measurer.measure(
                            formatDuration(bars[i].valueMs),
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

/** Theme-aware chart colors without importing MaterialTheme inside Canvas. */
private object MaterialThemeColors {
    val accent @Composable get() = androidx.compose.material3.MaterialTheme.colorScheme.primary
    val label @Composable get() = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
    val track @Composable get() = androidx.compose.material3.MaterialTheme.colorScheme.outline
    val value @Composable get() = androidx.compose.material3.MaterialTheme.colorScheme.onSurface
}
