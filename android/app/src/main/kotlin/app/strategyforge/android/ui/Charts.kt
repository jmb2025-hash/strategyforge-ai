package app.strategyforge.android.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

// Charts drawn with Compose Canvas (D-038): no chart library, so they stay small, fast and themed.
// Every chart has a spoken summary; values shown on touch are also available as text elsewhere.

data class LinePoint(
    val x: Long,
    val y: Double,
)

data class LineSeries(
    val name: String,
    val points: List<LinePoint>,
    val color: Color,
)

data class CandleBar(
    val t: Long,
    val o: Double,
    val h: Double,
    val l: Double,
    val c: Double,
    val v: Double,
)

data class ChartMarker(
    val t: Long,
    val price: Double,
    val buy: Boolean,
    val label: String,
)

private const val AXIS_WIDTH = 64f
private const val X_AXIS_HEIGHT = 22f

private data class Bounds(
    val min: Double,
    val max: Double,
) {
    val span get() = if (max - min == 0.0) 1.0 else max - min

    fun padded(fraction: Double = 0.08): Bounds {
        val pad = if (max == min) abs(max).coerceAtLeast(1.0) * 0.01 else (max - min) * fraction
        return Bounds(min - pad, max + pad)
    }
}

private fun DrawScope.gridAndYAxis(
    b: Bounds,
    plotW: Float,
    plotH: Float,
    grid: Color,
    textColor: Color,
    measurer: TextMeasurer,
    formatY: (Double) -> String,
    lines: Int = 4,
) {
    val style = TextStyle(color = textColor, fontSize = 10.sp, fontFeatureSettings = "tnum")
    for (k in 0..lines) {
        val v = b.min + b.span * k / lines
        val y = plotH - (plotH * k / lines)
        drawLine(grid, Offset(0f, y), Offset(plotW, y), strokeWidth = 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 6f)))
        val t = measurer.measure(formatY(v), style)
        drawText(t, topLeft = Offset(plotW + 6f, (y - t.size.height / 2f).coerceIn(0f, plotH - t.size.height)))
    }
}

private fun DrawScope.xLabels(
    xs: List<Long>,
    toX: (Long) -> Float,
    plotW: Float,
    plotH: Float,
    textColor: Color,
    measurer: TextMeasurer,
    formatX: (Long) -> String,
) {
    if (xs.isEmpty()) return
    val style = TextStyle(color = textColor, fontSize = 10.sp)
    val picks = if (xs.size < 3) xs else listOf(xs.first(), xs[xs.size / 2], xs.last())
    picks.forEachIndexed { i, x ->
        val t = measurer.measure(formatX(x), style)
        val cx = toX(x)
        val left =
            when (i) {
                0 -> cx
                picks.size - 1 -> cx - t.size.width
                else -> cx - t.size.width / 2f
            }.coerceIn(0f, max(0f, plotW - t.size.width))
        drawText(t, topLeft = Offset(left, plotH + 4f))
    }
}

private fun DrawScope.tooltip(
    lines: List<Pair<String, Color>>,
    anchorX: Float,
    plotW: Float,
    measurer: TextMeasurer,
    background: Color,
    textColor: Color,
) {
    val measured = lines.map { (s, c) -> measurer.measure(s, TextStyle(color = if (c == Color.Unspecified) textColor else c, fontSize = 11.sp, fontFeatureSettings = "tnum")) }
    val w = (measured.maxOfOrNull { it.size.width } ?: 0) + 20f
    val h = measured.sumOf { it.size.height } + 14f
    val left = (anchorX + 10f).let { if (it + w > plotW) anchorX - w - 10f else it }.coerceAtLeast(0f)
    drawRoundRect(background, Offset(left, 4f), Size(w, h), CornerRadius(10f, 10f))
    var y = 11f
    measured.forEach {
        drawText(it, topLeft = Offset(left + 10f, y))
        y += it.size.height
    }
}

/** A time-series line chart; one series gets a gradient fill. Drag or tap to read values. */
@Composable
fun LineChart(
    series: List<LineSeries>,
    formatY: (Double) -> String,
    formatX: (Long) -> String,
    description: String,
    modifier: Modifier = Modifier,
    height: Dp = 200.dp,
    baseline: Double? = null,
    showAxes: Boolean = true,
) {
    val points = series.flatMap { it.points }
    if (points.size < 2) {
        EmptyChart(if (points.isEmpty()) "No data yet" else "Not enough data yet", modifier, height)
        return
    }
    val measurer = rememberTextMeasurer()
    val grid = Sf.colors.grid
    val axis = Sf.colors.axisText
    val tipBg = MaterialTheme.colorScheme.surfaceContainerHighest
    val tipText = MaterialTheme.colorScheme.onSurface
    val base = Sf.colors.neutral
    var touchX by remember(series) { mutableStateOf<Float?>(null) }
    val reveal = remember(series.size, series.firstOrNull()?.name) { Animatable(0f) }
    LaunchedEffect(series) { reveal.animateTo(1f, tween(750, easing = FastOutSlowInEasing)) }
    Canvas(
        modifier
            .fillMaxWidth()
            .height(height)
            .semantics { contentDescription = description }
            .pointerInput(series) {
                detectTapGestures { touchX = if (touchX == null) it.x else null }
            }.pointerInput(series) {
                detectHorizontalDragGestures(onDragEnd = { touchX = null }, onDragCancel = { touchX = null }) { change, _ ->
                    touchX = change.position.x
                    change.consume()
                }
            },
    ) {
        val plotW = if (showAxes) size.width - AXIS_WIDTH else size.width
        val plotH = if (showAxes) size.height - X_AXIS_HEIGHT else size.height
        val xs = points.map { it.x }
        val minX = xs.min()
        val maxX = xs.max()
        val spanX = (maxX - minX).coerceAtLeast(1L).toDouble()
        val b = Bounds(min(points.minOf { it.y }, baseline ?: Double.MAX_VALUE), max(points.maxOf { it.y }, baseline ?: -Double.MAX_VALUE)).padded()

        fun toX(x: Long) = ((x - minX) / spanX * plotW).toFloat()

        fun toY(y: Double) = (plotH - (y - b.min) / b.span * plotH).toFloat()
        if (showAxes) gridAndYAxis(b, plotW, plotH, grid, axis, measurer, formatY)
        baseline?.let { drawLine(base, Offset(0f, toY(it)), Offset(plotW, toY(it)), strokeWidth = 1.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f))) }
        clipRect(right = plotW * reveal.value + 2f) { drawSeries(series, ::toX, ::toY, plotH) }
        if (showAxes) xLabels(xs.distinct().sorted(), ::toX, plotW, plotH, axis, measurer, formatX)
        touchX?.let { tx -> crosshair(tx, plotW, plotH, minX, spanX, series, ::toX, ::toY, formatX, formatY, axis, tipBg, tipText, measurer) }
    }
}

private fun DrawScope.drawSeries(
    series: List<LineSeries>,
    toX: (Long) -> Float,
    toY: (Double) -> Float,
    plotH: Float,
) {
    series.forEach { s ->
        if (s.points.isEmpty()) return@forEach
        val path = Path()
        s.points.forEachIndexed { i, p -> if (i == 0) path.moveTo(toX(p.x), toY(p.y)) else path.lineTo(toX(p.x), toY(p.y)) }
        if (series.size == 1 && s.points.size > 1) {
            val area =
                Path().apply {
                    addPath(path)
                    lineTo(toX(s.points.last().x), plotH)
                    lineTo(toX(s.points.first().x), plotH)
                    close()
                }
            drawPath(area, Brush.verticalGradient(listOf(s.color.copy(alpha = 0.32f), s.color.copy(alpha = 0f)), startY = 0f, endY = plotH))
        }
        drawPath(path, s.color, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        if (s.points.size == 1) drawCircle(s.color, 3.dp.toPx(), Offset(toX(s.points[0].x), toY(s.points[0].y)))
    }
}

private fun DrawScope.crosshair(
    tx: Float,
    plotW: Float,
    plotH: Float,
    minX: Long,
    spanX: Double,
    series: List<LineSeries>,
    toX: (Long) -> Float,
    toY: (Double) -> Float,
    formatX: (Long) -> String,
    formatY: (Double) -> String,
    axis: Color,
    tipBg: Color,
    tipText: Color,
    measurer: TextMeasurer,
) {
    val x = tx.coerceIn(0f, plotW)
    val target = minX + (x / plotW * spanX).toLong()
    drawLine(axis, Offset(x, 0f), Offset(x, plotH), strokeWidth = 1f)
    val lines = mutableListOf(formatX(target) to Color.Unspecified)
    series.forEach { s ->
        val p = s.points.minByOrNull { abs(it.x - target) } ?: return@forEach
        drawCircle(s.color, 5.dp.toPx(), Offset(toX(p.x), toY(p.y)))
        drawCircle(tipBg, 2.5.dp.toPx(), Offset(toX(p.x), toY(p.y)))
        lines += (if (series.size > 1) "${s.name}: " else "") + formatY(p.y) to s.color
    }
    tooltip(lines, x, plotW, measurer, tipBg, tipText)
}

/** A small line without axes for cards and list rows. */
@Composable
fun Sparkline(
    values: List<Double>,
    color: Color,
    modifier: Modifier = Modifier,
    height: Dp = 40.dp,
) {
    if (values.size < 2) return
    Canvas(modifier.fillMaxWidth().height(height)) {
        val b = Bounds(values.min(), values.max()).padded(0.1)
        val step = size.width / (values.size - 1)
        val path = Path()
        values.forEachIndexed { i, v ->
            val y = (size.height - (v - b.min) / b.span * size.height).toFloat()
            if (i == 0) path.moveTo(0f, y) else path.lineTo(i * step, y)
        }
        val area =
            Path().apply {
                addPath(path)
                lineTo(size.width, size.height)
                lineTo(0f, size.height)
                close()
            }
        drawPath(area, Brush.verticalGradient(listOf(color.copy(alpha = 0.25f), color.copy(alpha = 0f))))
        drawPath(path, color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** Candlesticks with volume, the latest price, and simulated trades marked on the bars they happened in. */
@Composable
fun CandlestickChart(
    bars: List<CandleBar>,
    markers: List<ChartMarker>,
    formatY: (Double) -> String,
    formatX: (Long) -> String,
    description: String,
    modifier: Modifier = Modifier,
    height: Dp = 320.dp,
) {
    if (bars.isEmpty()) {
        EmptyChart("No price data for this range", modifier, height)
        return
    }
    val measurer = rememberTextMeasurer()
    val colors = Sf.colors
    val tipBg = MaterialTheme.colorScheme.surfaceContainerHighest
    val tipText = MaterialTheme.colorScheme.onSurface
    val onGain = MaterialTheme.colorScheme.onPrimary
    var touchX by remember(bars) { mutableStateOf<Float?>(null) }
    val grow = remember(bars.size, bars.firstOrNull()?.t) { Animatable(0f) }
    LaunchedEffect(bars.size, bars.firstOrNull()?.t) { grow.animateTo(1f, tween(650, easing = FastOutSlowInEasing)) }
    // Each trade sits on the last bar that opened at or before it.
    val placed =
        remember(bars, markers) {
            markers.mapNotNull { m ->
                val idx = bars.indexOfLast { it.t <= m.t }
                if (idx < 0) null else idx to m
            }
        }
    Canvas(
        modifier
            .fillMaxWidth()
            .height(height)
            .testTag("candles")
            .semantics { contentDescription = description }
            .pointerInput(bars) { detectTapGestures { touchX = if (touchX == null) it.x else null } }
            .pointerInput(bars) {
                detectHorizontalDragGestures(onDragEnd = { touchX = null }, onDragCancel = { touchX = null }) { change, _ ->
                    touchX = change.position.x
                    change.consume()
                }
            },
    ) {
        val plotW = size.width - AXIS_WIDTH
        val fullH = size.height - X_AXIS_HEIGHT
        val volH = fullH * 0.18f
        val priceH = fullH - volH - 8f
        val n = bars.size
        val slot = plotW / n
        val body = (slot * 0.66f).coerceAtLeast(1f)
        val lows = bars.minOf { it.l }
        val highs = bars.maxOf { it.h }
        val b = Bounds(min(lows, placed.minOfOrNull { it.second.price } ?: lows), max(highs, placed.maxOfOrNull { it.second.price } ?: highs)).padded(0.1)

        fun toY(v: Double) = (priceH - (v - b.min) / b.span * priceH).toFloat()

        fun cx(i: Int) = slot * i + slot / 2f
        gridAndYAxis(b, plotW, priceH, colors.grid, colors.axisText, measurer, formatY)
        val maxVol = bars.maxOf { it.v }.takeIf { it > 0 } ?: 1.0
        bars.forEachIndexed { i, bar ->
            // Candles grow out of their midpoint, left to right.
            val k = ((grow.value * 1.5f) - i.toFloat() / n * 0.5f).coerceIn(0f, 1f)
            val up = bar.c >= bar.o
            val col = if (up) colors.gain else colors.loss
            val x = cx(i)
            val mid = toY((bar.o + bar.c) / 2)
            drawLine(col, Offset(x, mid + (toY(bar.h) - mid) * k), Offset(x, mid + (toY(bar.l) - mid) * k), strokeWidth = max(1f, body / 6f))
            val top = mid + (toY(max(bar.o, bar.c)) - mid) * k
            val bot = mid + (toY(min(bar.o, bar.c)) - mid) * k
            drawRect(col, Offset(x - body / 2f, top), Size(body, max(1.5f, bot - top)))
            val vh = (bar.v / maxVol * volH).toFloat() * k
            drawRect(col.copy(alpha = 0.35f), Offset(x - body / 2f, fullH - vh), Size(body, vh))
        }
        // Latest price line and tag.
        val last = bars.last()
        val ly = toY(last.c)
        val lastCol = if (last.c >= last.o) colors.gain else colors.loss
        drawLine(lastCol, Offset(0f, ly), Offset(plotW, ly), strokeWidth = 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
        val tag = measurer.measure(formatY(last.c), TextStyle(color = onGain, fontSize = 10.sp, fontFeatureSettings = "tnum"))
        val tagTop = (ly - tag.size.height / 2f - 2f).coerceIn(0f, priceH - tag.size.height - 4f)
        drawRoundRect(lastCol, Offset(plotW + 2f, tagTop), Size(tag.size.width + 8f, tag.size.height + 4f), CornerRadius(6f, 6f))
        drawText(tag, topLeft = Offset(plotW + 6f, tagTop + 2f))
        // Trade markers: buys below the bar, sells above.
        val tri = max(6f, min(12f, slot * 0.8f))
        val markerAlpha = ((grow.value - 0.6f) / 0.4f).coerceIn(0f, 1f)
        if (markerAlpha > 0f) {
            placed.forEach { (i, m) ->
                val x = cx(i)
                val path = Path()
                if (m.buy) {
                    val y = toY(bars[i].l) + 6f
                    path.moveTo(x, y)
                    path.lineTo(x - tri / 2f, y + tri)
                    path.lineTo(x + tri / 2f, y + tri)
                } else {
                    val y = toY(bars[i].h) - 6f
                    path.moveTo(x, y)
                    path.lineTo(x - tri / 2f, y - tri)
                    path.lineTo(x + tri / 2f, y - tri)
                }
                path.close()
                drawPath(path, if (m.buy) colors.gain else colors.loss, alpha = markerAlpha)
                drawPath(path, tipText.copy(alpha = 0.6f * markerAlpha), style = Stroke(1f))
                drawLine((if (m.buy) colors.gain else colors.loss).copy(alpha = 0.5f), Offset(x - slot, toY(m.price)), Offset(x + slot, toY(m.price)), strokeWidth = 1.5f)
            }
        }
        xLabels(bars.map { it.t }, { t -> cx(bars.indexOfFirst { it.t == t }) }, plotW, fullH, colors.axisText, measurer, formatX)
        touchX?.let { tx ->
            val i = (tx / slot).toInt().coerceIn(0, n - 1)
            val bar = bars[i]
            val x = cx(i)
            drawLine(colors.axisText, Offset(x, 0f), Offset(x, fullH), strokeWidth = 1f)
            val lines =
                mutableListOf(
                    formatX(bar.t) to Color.Unspecified,
                    "O ${formatY(bar.o)}  H ${formatY(bar.h)}" to Color.Unspecified,
                    "L ${formatY(bar.l)}  C ${formatY(bar.c)}" to (if (bar.c >= bar.o) colors.gain else colors.loss),
                )
            placed.filter { it.first == i }.forEach { (_, m) -> lines += m.label to (if (m.buy) colors.gain else colors.loss) }
            tooltip(lines, x, plotW, measurer, tipBg, tipText)
        }
    }
}

data class DonutSlice(
    val label: String,
    val value: Double,
    val color: Color,
)

/** Allocation ring with the total in the middle and a legend with percentages. */
@Composable
fun DonutChart(
    slices: List<DonutSlice>,
    centerLabel: String,
    centerValue: String,
    modifier: Modifier = Modifier,
) {
    val total = slices.sumOf { it.value.coerceAtLeast(0.0) }
    if (total <= 0.0) return
    val ring = MaterialTheme.colorScheme.surfaceContainerHigh
    val summary = slices.joinToString { "${it.label} ${pct(it.value / total)}" }
    val sweepIn = remember(slices) { Animatable(0f) }
    LaunchedEffect(slices) { sweepIn.animateTo(1f, tween(900, easing = FastOutSlowInEasing)) }
    Row(modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = "Allocation: $summary" }, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(132.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(132.dp).testTag("donut")) {
                val stroke = 16.dp.toPx()
                val inset = stroke / 2f
                val arcSize = Size(size.width - stroke, size.height - stroke)
                drawArc(ring, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
                var start = -90f
                val gap = if (slices.count { it.value > 0 } > 1) 2f else 0f
                slices.filter { it.value > 0 }.forEach { s ->
                    val sweep = (s.value / total * 360.0).toFloat() * sweepIn.value
                    drawArc(s.color, start + gap / 2f, max(0.5f, sweep - gap), false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Butt))
                    start += sweep
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(centerLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(centerValue, style = MaterialTheme.typography.titleSmall)
            }
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            slices.take(8).forEach { s ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).background(s.color, CircleShape))
                    Spacer(Modifier.width(8.dp))
                    Text(s.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Text(pct(s.value / total), style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

private fun pct(f: Double) = String.format(java.util.Locale.US, "%.1f%%", f * 100)

data class BarItem(
    val label: String,
    val value: Double?,
    val display: String,
)

/**
 * Horizontal bars for comparing strategies on one measure. With mixed signs the bars grow left
 * (below zero) or right (above zero) from a centre line; [signed] colours them gain/loss.
 */
@Composable
fun BarComparison(
    items: List<BarItem>,
    signed: Boolean,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.secondary,
) {
    val known = items.mapNotNull { it.value }
    val maxAbs = known.maxOfOrNull { abs(it) }?.takeIf { it > 0 } ?: 1.0
    val diverging = known.any { it < 0 }
    val track = MaterialTheme.colorScheme.surfaceContainerHigh
    Column(modifier.fillMaxWidth().testTag("bars"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items.forEach { item ->
            val v = item.value
            val barColor =
                when {
                    v == null -> Sf.colors.neutral
                    !signed -> color
                    v >= 0 -> Sf.colors.gain
                    else -> Sf.colors.loss
                }
            val target = if (v == null) 0f else (abs(v) / maxAbs).toFloat().coerceIn(0f, 1f)
            val animated by animateFloatAsState(target, tween(700, easing = FastOutSlowInEasing), label = "bar")
            Column(Modifier.semantics(mergeDescendants = true) {}) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(item.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1)
                    Text(item.display, style = MaterialTheme.typography.labelLarge, color = if (v == null) Sf.colors.neutral else barColor)
                }
                Canvas(Modifier.fillMaxWidth().height(10.dp).padding(top = 2.dp)) {
                    val r = CornerRadius(size.height / 2f, size.height / 2f)
                    drawRoundRect(track, Offset.Zero, size, r)
                    if (v == null) return@Canvas
                    val frac = animated
                    if (diverging) {
                        val mid = size.width / 2f
                        val w = frac * mid
                        drawRoundRect(barColor, Offset(if (v >= 0) mid else mid - w, 0f), Size(max(w, 2f), size.height), r)
                        drawLine(centreLine(track), Offset(mid, -2f), Offset(mid, size.height + 2f), strokeWidth = 2f)
                    } else {
                        drawRoundRect(barColor, Offset.Zero, Size(max(frac * size.width, 2f), size.height), r)
                    }
                }
            }
        }
    }
}

private fun centreLine(track: Color) = track.copy(alpha = 1f).let { Color(red = 1f - it.red, green = 1f - it.green, blue = 1f - it.blue, alpha = 0.35f) }

/** Legend chips for multi-series charts. */
@Composable
fun ChartLegend(series: List<LineSeries>) {
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        series.take(6).forEach { s ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(width = 12.dp, height = 4.dp).background(s.color, RoundedCornerShape(2.dp)))
                Spacer(Modifier.width(4.dp))
                Text(s.name, style = MaterialTheme.typography.labelMedium, maxLines = 1)
            }
        }
    }
}

@Composable
private fun EmptyChart(
    text: String,
    modifier: Modifier,
    height: Dp,
) {
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .background(MaterialTheme.colorScheme.surfaceContainerLow, MaterialTheme.shapes.medium)
            .testTag("chart-empty"),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
}
