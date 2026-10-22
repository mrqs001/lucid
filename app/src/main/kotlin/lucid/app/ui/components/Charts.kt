package lucid.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import lucid.app.ui.LucidColors
import lucid.core.model.SleepStage
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.max
import kotlin.math.min

/*
 * Night timeline charts drawn on Canvas. Shared conventions: a fixed left gutter so stacked
 * charts line up, a time axis in hours, thin marks, recessive grid, and tap/drag to select an
 * epoch (the selection is hoisted so every chart and the detail panel follow the same cursor).
 */

private val Gutter = 44.dp
private val AxisHeight = 16.dp
private val labelStyle = TextStyle(color = LucidColors.InkMuted, fontSize = 10.sp)
private val hourFormat = DateTimeFormatter.ofPattern("HH:mm")

/** Lane order top to bottom, as in clinical hypnograms (REM drawn just below wake). */
private val laneOrder = listOf(SleepStage.WAKE, SleepStage.REM, SleepStage.LIGHT, SleepStage.DEEP)

private fun Modifier.selectable(count: Int, onSelect: ((Int) -> Unit)?): Modifier {
    if (onSelect == null || count == 0) return this
    return this
        .pointerInput(count) {
            detectTapGestures { o -> onSelect(indexAt(o.x, size.width.toFloat(), Gutter.toPx(), count)) }
        }
        .pointerInput(count) {
            detectHorizontalDragGestures { change, _ -> onSelect(indexAt(change.position.x, size.width.toFloat(), Gutter.toPx(), count)) }
        }
}

private fun indexAt(x: Float, width: Float, gutter: Float, count: Int): Int =
    (((x - gutter) / (width - gutter)) * count).toInt().coerceIn(0, count - 1)

private fun DrawScope.plotRect(bottomAxis: Boolean): Rect {
    val left = Gutter.toPx()
    val bottom = size.height - if (bottomAxis) AxisHeight.toPx() else 0f
    return Rect(left, 2.dp.toPx(), size.width, bottom)
}

private fun DrawScope.timeAxis(tm: TextMeasurer, plot: Rect, startMs: Long, spanMs: Long) {
    if (spanMs <= 0) return
    val zone = ZoneId.systemDefault()
    val hour = 3_600_000L
    val stepHours = if (spanMs > 6 * hour) 2 else 1
    var t = (startMs / hour + 1) * hour
    while (t < startMs + spanMs) {
        val x = plot.left + (t - startMs).toFloat() / spanMs * plot.width
        drawLine(LucidColors.Grid, Offset(x, plot.top), Offset(x, plot.bottom), strokeWidth = 1f)
        val hourOfDay = Instant.ofEpochMilli(t).atZone(zone).hour
        if (hourOfDay % stepHours == 0) {
            val label = tm.measure(Instant.ofEpochMilli(t).atZone(zone).format(hourFormat), labelStyle)
            val lx = (x - label.size.width / 2f).coerceIn(plot.left, size.width - label.size.width)
            drawText(label, topLeft = Offset(lx, plot.bottom + 2.dp.toPx()))
        }
        t += hour
    }
}

private fun DrawScope.cursor(plot: Rect, selected: Int?, count: Int) {
    if (selected == null || count == 0) return
    val x = plot.left + (selected + 0.5f) / count * plot.width
    drawLine(LucidColors.Ink, Offset(x, plot.top), Offset(x, plot.bottom), strokeWidth = 1.5f)
}

/**
 * Estimated hypnogram. [stages] holds a [SleepStage] ordinal per epoch (-1 = no estimate).
 * [truth], when given, is drawn as a thin labelled strip underneath: simulator ground truth.
 */
@Composable
fun Hypnogram(
    stages: List<Int>,
    startMs: Long,
    modifier: Modifier = Modifier,
    epochMs: Long = 30_000,
    truth: List<Int>? = null,
    selected: Int? = null,
    onSelect: ((Int) -> Unit)? = null,
) {
    val tm = rememberTextMeasurer()
    val laneDp = 20.dp
    val truthDp = if (truth != null) 18.dp else 0.dp
    Canvas(
        modifier
            .fillMaxWidth()
            .height(laneDp * 4 + truthDp + AxisHeight + 4.dp)
            .selectable(stages.size, onSelect),
    ) {
        val plot = plotRect(bottomAxis = true)
        val n = stages.size
        val lane = laneDp.toPx()
        val truthH = truthDp.toPx()
        laneOrder.forEachIndexed { i, st ->
            val y = plot.top + i * lane
            val label = tm.measure(if (st == SleepStage.REM) "REM" else st.name.lowercase().replaceFirstChar { it.uppercase() }, labelStyle)
            drawText(label, topLeft = Offset(0f, y + (lane - label.size.height) / 2))
            drawLine(LucidColors.Grid, Offset(plot.left, y + lane / 2), Offset(plot.right, y + lane / 2), strokeWidth = 1f)
        }
        timeAxis(tm, Rect(plot.left, plot.top, plot.right, plot.bottom), startMs, n * epochMs)
        if (n == 0) return@Canvas
        val w = plot.width / n
        var i = 0
        var prevLaneY: Float? = null
        while (i < n) {
            val s = stages[i]
            var j = i
            while (j + 1 < n && stages[j + 1] == s) j++
            if (s >= 0) {
                val laneIdx = laneOrder.indexOf(SleepStage.entries[s])
                val cy = plot.top + laneIdx * lane + lane / 2
                val h = lane * 0.56f
                if (prevLaneY != null && prevLaneY != cy) {
                    val x = plot.left + i * w
                    drawLine(LucidColors.InkFaint, Offset(x, prevLaneY), Offset(x, cy), strokeWidth = 1f)
                }
                drawRoundRect(
                    color = LucidColors.stage(s),
                    topLeft = Offset(plot.left + i * w, cy - h / 2),
                    size = Size(max(1.5f, (j - i + 1) * w), h),
                    cornerRadius = CornerRadius(2.dp.toPx()),
                )
                prevLaneY = cy
            } else {
                prevLaneY = null
            }
            i = j + 1
        }
        if (truth != null) {
            val ty = plot.top + 4 * lane + 4.dp.toPx()
            val label = tm.measure("Sim truth", labelStyle.copy(color = LucidColors.Dawn))
            drawText(label, topLeft = Offset(0f, ty + (truthH - 4.dp.toPx() - label.size.height) / 2))
            val tw = plot.width / max(n, truth.size)
            truth.forEachIndexed { k, st ->
                if (st >= 0) drawRect(LucidColors.stage(st), Offset(plot.left + k * tw, ty + 3.dp.toPx()), Size(tw + 0.5f, 6.dp.toPx()))
            }
        }
        cursor(plot, selected, n)
    }
}

/**
 * REM probability over the night with the cue threshold and cue marks:
 * filled triangles = delivered, hollow = would-deliver (baseline), small ticks = suppressed.
 */
@Composable
fun RemProbabilityChart(
    values: List<Float>,
    startMs: Long,
    threshold: Float,
    modifier: Modifier = Modifier,
    epochMs: Long = 30_000,
    delivered: Set<Int> = emptySet(),
    wouldDeliver: Set<Int> = emptySet(),
    suppressed: Set<Int> = emptySet(),
    selected: Int? = null,
    onSelect: ((Int) -> Unit)? = null,
    height: Int = 120,
) {
    val tm = rememberTextMeasurer()
    Canvas(modifier.fillMaxWidth().height(height.dp).selectable(values.size, onSelect)) {
        val full = plotRect(bottomAxis = true)
        val markBand = 12.dp.toPx()
        val plot = Rect(full.left, full.top + markBand, full.right, full.bottom)
        val n = values.size
        fun y(p: Float) = plot.bottom - p.coerceIn(0f, 1f) * plot.height
        listOf(0f, 0.5f, 1f).forEach { g ->
            drawLine(LucidColors.Grid, Offset(plot.left, y(g)), Offset(plot.right, y(g)), strokeWidth = 1f)
            val l = tm.measure("%.1f".format(g), labelStyle)
            drawText(l, topLeft = Offset(Gutter.toPx() - l.size.width - 6.dp.toPx(), y(g) - l.size.height / 2))
        }
        timeAxis(tm, full, startMs, n * epochMs)
        drawLine(
            LucidColors.InkMuted, Offset(plot.left, y(threshold)), Offset(plot.right, y(threshold)),
            strokeWidth = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)),
        )
        if (n == 0) return@Canvas
        val w = plot.width / n
        val line = Path()
        val area = Path()
        values.forEachIndexed { i, p ->
            val x = plot.left + (i + 0.5f) * w
            if (i == 0) {
                line.moveTo(x, y(p)); area.moveTo(x, plot.bottom); area.lineTo(x, y(p))
            } else {
                line.lineTo(x, y(p)); area.lineTo(x, y(p))
            }
        }
        area.lineTo(plot.left + (n - 0.5f) * w, plot.bottom)
        area.close()
        drawPath(area, LucidColors.Rem.copy(alpha = 0.22f))
        drawPath(line, LucidColors.Rem, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))

        val tri = 5.dp.toPx()
        fun triangle(i: Int, filled: Boolean) {
            val x = plot.left + (i + 0.5f) * w
            val p = Path().apply {
                moveTo(x - tri, full.top); lineTo(x + tri, full.top); lineTo(x, full.top + tri * 1.6f); close()
            }
            if (filled) drawPath(p, LucidColors.Ink) else drawPath(p, LucidColors.Ink, style = Stroke(1.5f))
        }
        suppressed.forEach { i ->
            if (i in 0 until n) {
                val x = plot.left + (i + 0.5f) * w
                drawLine(LucidColors.InkFaint, Offset(x, full.top + 2f), Offset(x, full.top + markBand * 0.6f), strokeWidth = 1f)
            }
        }
        wouldDeliver.forEach { if (it in 0 until n) triangle(it, filled = false) }
        delivered.forEach { if (it in 0 until n) triangle(it, filled = true) }
        cursor(plot, selected, n)
    }
}

/** Single-series line (heart rate, movement). Gaps (null) break the line. */
@Composable
fun SeriesChart(
    values: List<Float?>,
    color: Color,
    modifier: Modifier = Modifier,
    startMs: Long? = null,
    stepMs: Long = 30_000,
    selected: Int? = null,
    onSelect: ((Int) -> Unit)? = null,
    height: Int = 90,
    floor: Float? = null,
) {
    val tm = rememberTextMeasurer()
    Canvas(modifier.fillMaxWidth().height(height.dp).selectable(values.size, onSelect)) {
        val plot = plotRect(bottomAxis = startMs != null)
        val present = values.filterNotNull()
        val n = values.size
        if (startMs != null) timeAxis(tm, plot, startMs, n * stepMs)
        if (present.isEmpty()) return@Canvas
        var lo = present.min()
        var hi = present.max()
        if (floor != null) lo = min(lo, floor)
        if (hi - lo < 1f) { hi += 0.5f; lo -= 0.5f }
        val pad = (hi - lo) * 0.1f
        lo -= pad; hi += pad
        fun y(v: Float) = plot.bottom - (v - lo) / (hi - lo) * plot.height
        listOf(lo + pad, hi - pad).forEach { g ->
            drawLine(LucidColors.Grid, Offset(plot.left, y(g)), Offset(plot.right, y(g)), strokeWidth = 1f)
            val l = tm.measure("%.0f".format(g), labelStyle)
            drawText(l, topLeft = Offset(Gutter.toPx() - l.size.width - 6.dp.toPx(), y(g) - l.size.height / 2))
        }
        val w = plot.width / max(1, n)
        val path = Path()
        var pen = false
        values.forEachIndexed { i, v ->
            if (v == null) { pen = false; return@forEachIndexed }
            val x = plot.left + (i + 0.5f) * w
            if (pen) path.lineTo(x, y(v)) else path.moveTo(x, y(v))
            pen = true
        }
        drawPath(path, color, style = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round))
        cursor(plot, selected, n)
    }
}

/** Simple labelled bars for statistics (one series, values >= 0). */
@Composable
fun BarChart(
    labels: List<String>,
    values: List<Float>,
    color: Color,
    modifier: Modifier = Modifier,
    valueFormat: (Float) -> String = { "%.0f".format(it) },
    height: Int = 140,
) {
    val tm = rememberTextMeasurer()
    Canvas(modifier.fillMaxWidth().height(height.dp)) {
        val n = values.size
        if (n == 0) return@Canvas
        val top = 14.dp.toPx()
        val bottom = size.height - 16.dp.toPx()
        val maxV = max(values.max(), 1e-6f)
        val slot = size.width / n
        val barW = min(slot * 0.6f, 28.dp.toPx())
        drawLine(LucidColors.Grid, Offset(0f, bottom), Offset(size.width, bottom), strokeWidth = 1f)
        values.forEachIndexed { i, v ->
            val h = (v / maxV) * (bottom - top)
            val x = i * slot + (slot - barW) / 2
            if (v > 0f) {
                drawRoundRect(
                    color, Offset(x, bottom - h), Size(barW, h),
                    cornerRadius = CornerRadius(4.dp.toPx()),
                )
            }
            val vl = tm.measure(valueFormat(v), labelStyle.copy(color = LucidColors.Ink))
            drawText(vl, topLeft = Offset(x + barW / 2 - vl.size.width / 2, bottom - h - vl.size.height - 1.dp.toPx()))
            val l = tm.measure(labels[i], labelStyle)
            drawText(l, topLeft = Offset((x + barW / 2 - l.size.width / 2).coerceIn(0f, size.width - l.size.width), bottom + 2.dp.toPx()))
        }
    }
}
