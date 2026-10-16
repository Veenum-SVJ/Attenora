package dev.opensource.focusguard.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * FocusGuard's custom outline icon set — hand-drawn, no Material icons.
 * Every icon is a 24×24 stroked path; [dev.opensource.focusguard.ui.common.FgIcon]
 * tints them with LocalContentColor.
 */
object FgIcons {

    private fun strokes(name: String, block: PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            , pathBuilder = block)
        }.build()

    private fun solids(name: String, block: PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(fill = SolidColor(Color.Black), pathBuilder = block)
        }.build()

    private fun PathBuilder.circle(cx: Float, cy: Float, r: Float) {
        moveTo(cx + r, cy)
        arcTo(r, r, 0f, true, true, cx - r, cy)
        arcTo(r, r, 0f, true, true, cx + r, cy)
    }

    // ---- Navigation / chrome -------------------------------------------------

    val Menu = strokes("menu") {
        moveTo(3f, 6f); lineTo(21f, 6f)
        moveTo(3f, 12f); lineTo(21f, 12f)
        moveTo(3f, 18f); lineTo(21f, 18f)
    }

    val Search = strokes("search") {
        circle(10.5f, 10.5f, 6.5f)
        moveTo(15.5f, 15.5f); lineTo(21f, 21f)
    }

    val Close = strokes("close") {
        moveTo(6f, 6f); lineTo(18f, 18f)
        moveTo(18f, 6f); lineTo(6f, 18f)
    }

    val Plus = strokes("plus") {
        moveTo(12f, 5f); lineTo(12f, 19f)
        moveTo(5f, 12f); lineTo(19f, 12f)
    }

    val Check = strokes("check") {
        moveTo(4.5f, 12.5f); lineTo(9.5f, 17.5f); lineTo(19.5f, 6.5f)
    }

    val ChevronDown = strokes("chevron_down") {
        moveTo(5f, 9f); lineTo(12f, 16f); lineTo(19f, 9f)
    }

    val ChevronUp = strokes("chevron_up") {
        moveTo(5f, 15f); lineTo(12f, 8f); lineTo(19f, 15f)
    }

    val ChevronRight = strokes("chevron_right") {
        moveTo(9f, 5f); lineTo(16f, 12f); lineTo(9f, 19f)
    }

    val ArrowBack = strokes("arrow_back") {
        moveTo(20f, 12f); lineTo(4f, 12f)
        moveTo(10f, 6f); lineTo(4f, 12f); lineTo(10f, 18f)
    }

    val ArrowRight = strokes("arrow_right") {
        moveTo(4f, 12f); lineTo(20f, 12f)
        moveTo(14f, 6f); lineTo(20f, 12f); lineTo(14f, 18f)
    }

    // ---- Tabs / sections -----------------------------------------------------

    /** Usage Stats tab: bar chart. */
    val ChartBar = strokes("chart_bar") {
        moveTo(4f, 20f); lineTo(4f, 12f)
        moveTo(9.5f, 20f); lineTo(9.5f, 5f)
        moveTo(15f, 20f); lineTo(15f, 9f)
        moveTo(20.5f, 20f); lineTo(20.5f, 14f)
        moveTo(3f, 20f); lineTo(21f, 20f)
    }

    /** Usage Stats chart toggle: line chart. */
    val ChartLine = strokes("chart_line") {
        moveTo(3f, 17f); lineTo(8.5f, 11f); lineTo(13f, 14f); lineTo(21f, 6f)
        moveTo(3f, 20f); lineTo(21f, 20f)
    }

    /** Usage Limits tab: speedometer/gauge. */
    val Gauge = strokes("gauge") {
        arcTo(7f, 7f, 0f, false, true, 21f, 14f)
        moveTo(14f, 14f); lineTo(10.5f, 9.5f)
        circle(7f, 14f, 1.2f)
    }

    /** In-App Blocking tab: 3×3 dot grid. */
    val Grid = solids("grid") {
        val r = 1.6f
        for (x in listOf(5f, 12f, 19f)) for (y in listOf(5f, 12f, 19f)) {
            moveTo(x + r, y)
            arcTo(r, r, 0f, true, true, x - r, y)
            arcTo(r, r, 0f, true, true, x + r, y)
            close()
        }
    }

    val Home = strokes("home") {
        moveTo(4f, 11f); lineTo(12f, 4f); lineTo(20f, 11f)
        moveTo(6.5f, 9.5f); lineTo(6.5f, 19.5f); lineTo(17.5f, 19.5f); lineTo(17.5f, 9.5f)
        moveTo(10f, 19.5f); lineTo(10f, 14f); lineTo(14f, 14f); lineTo(14f, 19.5f)
    }

    val Bell = strokes("bell") {
        moveTo(6f, 16f); lineTo(6f, 10.5f)
        arcTo(6f, 6f, 0f, false, true, 18f, 10.5f)
        lineTo(18f, 16f)
        moveTo(4.5f, 16f); lineTo(19.5f, 16f)
        moveTo(10f, 19f); arcTo(2f, 2f, 0f, false, false, 14f, 19f)
    }

    /** Settings: sliders. */
    val Sliders = strokes("sliders") {
        moveTo(3f, 7f); lineTo(21f, 7f)
        moveTo(3f, 12f); lineTo(21f, 12f)
        moveTo(3f, 17f); lineTo(21f, 17f)
        circle(9f, 7f, 2.2f)
        circle(15f, 12f, 2.2f)
        circle(8f, 17f, 2.2f)
    }

    /** Categories: shapes (triangle, circle, square). */
    val Shapes = strokes("shapes") {
        moveTo(7f, 4.5f); lineTo(10.5f, 10f); lineTo(3.5f, 10f); close()
        moveTo(17f, 4.5f); circle(17f, 7.5f, 3f)
        moveTo(5f, 13f); lineTo(11f, 13f); lineTo(11f, 19.5f); lineTo(5f, 19.5f); close()
        circle(17f, 16.5f, 3f)
    }

    // ---- Time / limits -------------------------------------------------------

    val Calendar = strokes("calendar") {
        moveTo(4f, 6f); lineTo(20f, 6f); lineTo(20f, 19.5f); lineTo(4f, 19.5f); close()
        moveTo(4f, 10f); lineTo(20f, 10f)
        moveTo(8.5f, 4f); lineTo(8.5f, 7.5f)
        moveTo(15.5f, 4f); lineTo(15.5f, 7.5f)
    }

    val Clock = strokes("clock") {
        circle(12f, 12f, 8f)
        moveTo(12f, 7.5f); lineTo(12f, 12f); lineTo(15.5f, 14f)
    }

    val Stopwatch = strokes("stopwatch") {
        circle(12f, 13.5f, 7f)
        moveTo(9.5f, 3.5f); lineTo(14.5f, 3.5f)
        moveTo(12f, 3.5f); lineTo(12f, 6.5f)
        moveTo(12f, 13.5f); lineTo(15f, 11f)
        moveTo(18.5f, 7f); lineTo(20f, 5.5f)
    }

    val Infinity = strokes("infinity") {
        moveTo(7f, 14.5f)
        arcTo(3f, 3f, 0f, false, true, 7f, 9.5f)
        lineTo(17f, 14.5f)
        arcTo(3f, 3f, 0f, false, true, 17f, 9.5f)
        lineTo(7f, 14.5f)
        close()
    }

    val Block = strokes("block") {
        circle(12f, 12f, 8f)
        moveTo(6.5f, 17.5f); lineTo(17.5f, 6.5f)
    }

    val Hourglass = strokes("hourglass") {
        moveTo(6f, 4f); lineTo(18f, 4f); lineTo(18f, 6.5f)
        arcTo(5f, 5f, 0f, false, true, 12f, 13f)
        arcTo(5f, 5f, 0f, false, true, 6f, 19.5f)
        lineTo(6f, 20f); lineTo(18f, 20f); lineTo(18f, 17.5f)
        arcTo(5f, 5f, 0f, false, false, 12f, 11f)
        arcTo(5f, 5f, 0f, false, false, 18f, 6.5f)
        close()
    }

    // ---- Targets -------------------------------------------------------------

    val Phone = strokes("phone") {
        moveTo(7f, 3.5f); lineTo(17f, 3.5f); lineTo(17f, 20.5f); lineTo(7f, 20.5f); close()
        moveTo(10.5f, 17.5f); lineTo(13.5f, 17.5f)
    }

    val Globe = strokes("globe") {
        circle(12f, 12f, 8f)
        moveTo(4f, 12f); lineTo(20f, 12f)
        moveTo(12f, 4f)
        arcTo(4f, 8f, 0f, false, true, 12f, 20f)
        moveTo(12f, 4f)
        arcTo(4f, 8f, 0f, false, false, 12f, 20f)
    }

    val Tag = strokes("tag") {
        moveTo(4f, 4.5f); lineTo(11f, 4.5f); lineTo(19.5f, 13f)
        lineTo(13f, 19.5f); lineTo(4.5f, 11f); close()
        moveTo(8f, 8f); circle(8f, 8f, 1.2f)
    }

    val Filter = strokes("filter") {
        moveTo(4f, 6f); lineTo(20f, 6f); lineTo(14f, 13f); lineTo(14f, 19f); lineTo(10f, 17f); lineTo(10f, 13f); close()
    }

    // ---- Status / meta -------------------------------------------------------

    val Eye = strokes("eye") {
        moveTo(3f, 12f)
        arcTo(4.5f, 4.5f, 0f, false, true, 21f, 12f)
        arcTo(4.5f, 4.5f, 0f, false, true, 3f, 12f)
        circle(12f, 12f, 2.4f)
    }

    val EyeOff = strokes("eye_off") {
        moveTo(4f, 9f)
        arcTo(5f, 5f, 0f, false, true, 20f, 9f)
        arcTo(5f, 5f, 0f, false, true, 4f, 9f)
        moveTo(4.5f, 19.5f); lineTo(19.5f, 4.5f)
        circle(12f, 11f, 2.4f)
    }

    val Info = strokes("info") {
        circle(12f, 12f, 8f)
        moveTo(12f, 11f); lineTo(12f, 16.5f)
        moveTo(12f, 7.6f); circle(12f, 7.6f, 0.2f)
    }

    val Warning = strokes("warning") {
        moveTo(12f, 4f); lineTo(21f, 19.5f); lineTo(3f, 19.5f); close()
        moveTo(12f, 10f); lineTo(12f, 14.5f)
        moveTo(12f, 17.2f); circle(12f, 17.2f, 0.2f)
    }

    val Lock = strokes("lock") {
        moveTo(6f, 11f); lineTo(18f, 11f); lineTo(18f, 19.5f); lineTo(6f, 19.5f); close()
        moveTo(8.5f, 11f); lineTo(8.5f, 8f)
        arcTo(3.5f, 3.5f, 0f, false, true, 15.5f, 8f)
        lineTo(15.5f, 11f)
        moveTo(12f, 14.5f); lineTo(12f, 16.5f)
    }

    val Key = strokes("key") {
        circle(8f, 8f, 4f)
        moveTo(11f, 11f); lineTo(19f, 19f)
        moveTo(16f, 16f); lineTo(18.5f, 13.5f)
        moveTo(14f, 14f); lineTo(16.5f, 11.5f)
    }

    val Shield = strokes("shield") {
        moveTo(12f, 3.5f); lineTo(19.5f, 6.5f); lineTo(19.5f, 12f)
        arcTo(7.5f, 7.5f, 0f, false, true, 12f, 20.5f)
        arcTo(7.5f, 7.5f, 0f, false, true, 4.5f, 12f)
        lineTo(4.5f, 6.5f); close()
        moveTo(9f, 12f); lineTo(11.2f, 14.2f); lineTo(15.3f, 10f)
    }

    val Edit = strokes("edit") {
        moveTo(4f, 20f); lineTo(4.8f, 16.2f); lineTo(15.8f, 5.2f)
        lineTo(18.8f, 8.2f); lineTo(7.8f, 19.2f); close()
        moveTo(14f, 7f); lineTo(17f, 10f)
    }

    val Trash = strokes("trash") {
        moveTo(4.5f, 6.5f); lineTo(19.5f, 6.5f)
        moveTo(9f, 6.5f); lineTo(9f, 4.5f); lineTo(15f, 4.5f); lineTo(15f, 6.5f)
        moveTo(6.5f, 6.5f); lineTo(7.3f, 19.5f); lineTo(16.7f, 19.5f); lineTo(17.5f, 6.5f)
        moveTo(10.5f, 10f); lineTo(10.5f, 16f)
        moveTo(13.5f, 10f); lineTo(13.5f, 16f)
    }

    val Download = strokes("download") {
        moveTo(12f, 4f); lineTo(12f, 14.5f)
        moveTo(7.5f, 10.5f); lineTo(12f, 15f); lineTo(16.5f, 10.5f)
        moveTo(4.5f, 19.5f); lineTo(19.5f, 19.5f)
    }

    val Upload = strokes("upload") {
        moveTo(12f, 15f); lineTo(12f, 4.5f)
        moveTo(7.5f, 9f); lineTo(12f, 4.5f); lineTo(16.5f, 9f)
        moveTo(4.5f, 19.5f); lineTo(19.5f, 19.5f)
    }

    val Moon = strokes("moon") {
        moveTo(16.5f, 4.5f)
        arcTo(7.5f, 7.5f, 0f, false, true, 8f, 19f)
        arcTo(6.5f, 6.5f, 0f, false, false, 16.5f, 4.5f)
        close()
    }

    val Sun = strokes("sun") {
        circle(12f, 12f, 4f)
        moveTo(12f, 3f); lineTo(12f, 5f)
        moveTo(12f, 19f); lineTo(12f, 21f)
        moveTo(3f, 12f); lineTo(5f, 12f)
        moveTo(19f, 12f); lineTo(21f, 12f)
        moveTo(5.6f, 5.6f); lineTo(7f, 7f)
        moveTo(17f, 17f); lineTo(18.4f, 18.4f)
        moveTo(18.4f, 5.6f); lineTo(17f, 7f)
        moveTo(7f, 17f); lineTo(5.6f, 18.4f)
    }

    val Palette = strokes("palette") {
        moveTo(12f, 3.5f)
        arcTo(8.5f, 8.5f, 0f, true, true, 4f, 15f)
        arcTo(8.5f, 8.5f, 0f, true, true, 9.5f, 20.5f)
        arcTo(8.5f, 8.5f, 0f, false, true, 12f, 3.5f)
        close()
        circle(8f, 10f, 1.1f)
        circle(12f, 7.5f, 1.1f)
        circle(15.5f, 10.5f, 1.1f)
        circle(8.5f, 14.5f, 1.1f)
    }

    val Bug = strokes("bug") {
        circle(12f, 12f, 4.5f)
        moveTo(9f, 5f); lineTo(7.5f, 3.5f)
        moveTo(15f, 5f); lineTo(16.5f, 3.5f)
        moveTo(8f, 10f); lineTo(4.5f, 9f)
        moveTo(8f, 13f); lineTo(4f, 14f)
        moveTo(8f, 16f); lineTo(5.5f, 18f)
        moveTo(16f, 10f); lineTo(19.5f, 9f)
        moveTo(16f, 13f); lineTo(20f, 14f)
        moveTo(16f, 16f); lineTo(18.5f, 18f)
        moveTo(12f, 10f); lineTo(12f, 16.5f)
    }

    val Doc = strokes("doc") {
        moveTo(6f, 3.5f); lineTo(14f, 3.5f); lineTo(18f, 7.5f); lineTo(18f, 20.5f); lineTo(6f, 20.5f); close()
        moveTo(14f, 3.5f); lineTo(14f, 7.5f); lineTo(18f, 7.5f)
        moveTo(9f, 12f); lineTo(15f, 12f)
        moveTo(9f, 15.5f); lineTo(15f, 15.5f)
    }

    val Refresh = strokes("refresh") {
        moveTo(19.5f, 12f)
        arcTo(7.5f, 7.5f, 0f, false, true, 6.5f, 17.5f)
        moveTo(4.5f, 12f)
        arcTo(7.5f, 7.5f, 0f, false, true, 17.5f, 6.5f)
        moveTo(17.5f, 3.5f); lineTo(17.5f, 6.5f); lineTo(14.5f, 6.5f)
        moveTo(6.5f, 20.5f); lineTo(6.5f, 17.5f); lineTo(9.5f, 17.5f)
    }

    val Lightbulb = strokes("lightbulb") {
        moveTo(8.5f, 15f)
        arcTo(5f, 5f, 0f, false, true, 15.5f, 15f)
        lineTo(15.5f, 17f); lineTo(8.5f, 17f); close()
        moveTo(9.5f, 19f); lineTo(14.5f, 19f)
        moveTo(10.5f, 21f); lineTo(13.5f, 21f)
    }

    val User = strokes("user") {
        circle(12f, 8.5f, 4f)
        moveTo(4.5f, 20f)
        arcTo(7.5f, 7.5f, 0f, false, true, 19.5f, 20f)
    }

    val WifiOff = strokes("wifi_off") {
        moveTo(3f, 8.5f)
        arcTo(13f, 13f, 0f, false, true, 21f, 8.5f)
        moveTo(6.5f, 12.5f)
        arcTo(8f, 8f, 0f, false, true, 17.5f, 12.5f)
        moveTo(12f, 17f); lineTo(12f, 20f)
        moveTo(4.5f, 19.5f); lineTo(19.5f, 4.5f)
    }

    val Battery = strokes("battery") {
        moveTo(3.5f, 8f); lineTo(17.5f, 8f); lineTo(17.5f, 16f); lineTo(3.5f, 16f); close()
        moveTo(19.5f, 10.5f); lineTo(19.5f, 13.5f)
        moveTo(6f, 10.5f); lineTo(9f, 10.5f); lineTo(9f, 13.5f); lineTo(6f, 13.5f); close()
    }

    /** Expanding FAB close state. */
    val X = Close
}
