package app.strategyforge.android.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// Illustrations and motion (D-038): drawn in code from the theme colours, so they suit light and
// dark themes, add nothing to the APK size and are hidden from screen readers (decorative only).

enum class Art { PORTFOLIO, STRATEGIES, RESEARCH, INBOX, CHART }

/** A small decorative scene for empty screens; it draws itself in once when shown. */
@Composable
fun Illustration(
    art: Art,
    modifier: Modifier = Modifier,
    size: Dp = 132.dp,
) {
    val c = Sf.colors
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary
    val plate = MaterialTheme.colorScheme.surfaceContainerHigh
    val line = MaterialTheme.colorScheme.outline
    val progress = remember(art) { Animatable(0f) }
    LaunchedEffect(art) { progress.animateTo(1f, tween(900, easing = FastOutSlowInEasing)) }
    Canvas(modifier.size(size).clearAndSetSemantics {}) {
        val p = progress.value
        // Soft backdrop disc.
        drawCircle(Brush.radialGradient(listOf(primary.copy(alpha = 0.22f), primary.copy(alpha = 0f)), center, this.size.minDimension / 2f), this.size.minDimension / 2f)
        when (art) {
            Art.PORTFOLIO -> wallet(p, plate, primary, c.gain, line)
            Art.STRATEGIES -> candles(p, c.gain, c.loss, secondary)
            Art.RESEARCH -> magnifier(p, plate, secondary, primary, line)
            Art.INBOX -> bell(p, plate, primary, line)
            Art.CHART -> candles(p, c.gain, c.loss, primary)
        }
    }
}

private fun DrawScope.wallet(
    p: Float,
    plate: Color,
    primary: Color,
    gain: Color,
    line: Color,
) {
    val w = size.width
    val h = size.height
    drawRoundRect(plate, Offset(w * 0.18f, h * 0.36f), Size(w * 0.64f, h * 0.42f), CornerRadius(w * 0.08f))
    drawRoundRect(line, Offset(w * 0.18f, h * 0.36f), Size(w * 0.64f, h * 0.42f), CornerRadius(w * 0.08f), style = Stroke(2.dp.toPx()))
    drawRoundRect(primary.copy(alpha = 0.85f), Offset(w * 0.56f, h * 0.49f), Size(w * 0.3f, h * 0.16f), CornerRadius(w * 0.05f))
    drawCircle(plate, w * 0.03f, Offset(w * 0.64f, h * 0.57f))
    // Growth line rising out of the wallet.
    val path = Path()
    val pts = listOf(0.22f to 0.30f, 0.34f to 0.24f, 0.44f to 0.27f, 0.58f to 0.14f, 0.74f to 0.10f)
    pts.forEachIndexed { i, (x, y) -> if (i == 0) path.moveTo(w * x, h * y) else path.lineTo(w * x, h * y) }
    drawProgressPath(path, gain, p)
}

private fun DrawScope.candles(
    p: Float,
    gain: Color,
    loss: Color,
    accent: Color,
) {
    val w = size.width
    val h = size.height
    val bars = listOf(Triple(0.62f, 0.50f, true), Triple(0.52f, 0.58f, false), Triple(0.46f, 0.36f, true), Triple(0.40f, 0.44f, false), Triple(0.30f, 0.24f, true))
    bars.forEachIndexed { i, (top, bottom, up) ->
        val local = ((p * 1.4f) - i * 0.1f).coerceIn(0f, 1f)
        val x = w * (0.22f + i * 0.14f)
        val col = if (up) gain else loss
        val hi = minOf(top, bottom) - 0.07f
        val lo = maxOf(top, bottom) + 0.07f
        val mid = (top + bottom) / 2f
        drawLine(col, Offset(x, h * (mid - (mid - hi) * local)), Offset(x, h * (mid + (lo - mid) * local)), strokeWidth = 2.dp.toPx())
        val bt = mid - (mid - minOf(top, bottom)) * local
        val bb = mid + (maxOf(top, bottom) - mid) * local
        drawRoundRect(col, Offset(x - w * 0.04f, h * bt), Size(w * 0.08f, h * (bb - bt).coerceAtLeast(0.01f)), CornerRadius(3f))
    }
    val trend =
        Path().apply {
            moveTo(w * 0.16f, h * 0.72f)
            lineTo(w * 0.84f, h * 0.2f)
        }
    drawProgressPath(trend, accent.copy(alpha = 0.8f), p, dashed = true)
}

private fun DrawScope.magnifier(
    p: Float,
    plate: Color,
    secondary: Color,
    primary: Color,
    line: Color,
) {
    val w = size.width
    val h = size.height
    drawRoundRect(plate, Offset(w * 0.16f, h * 0.2f), Size(w * 0.5f, h * 0.58f), CornerRadius(10f))
    for (k in 0..3) drawLine(line, Offset(w * 0.22f, h * (0.32f + k * 0.1f)), Offset(w * (0.5f + (k % 2) * 0.08f), h * (0.32f + k * 0.1f)), strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
    val cx = w * 0.62f
    val cy = h * 0.56f
    val r = w * 0.16f
    drawCircle(secondary.copy(alpha = 0.18f), r, Offset(cx, cy))
    drawArc(secondary, -90f, 360f * p, false, Offset(cx - r, cy - r), Size(r * 2, r * 2), style = Stroke(4.dp.toPx(), cap = StrokeCap.Round))
    drawLine(secondary, Offset(cx + r * 0.72f, cy + r * 0.72f), Offset(cx + r * 0.72f + w * 0.12f * p, cy + r * 0.72f + h * 0.12f * p), strokeWidth = 6.dp.toPx(), cap = StrokeCap.Round)
    val spark =
        Path().apply {
            moveTo(cx - r * 0.6f, cy + r * 0.2f)
            lineTo(cx - r * 0.2f, cy - r * 0.1f)
            lineTo(cx + r * 0.1f, cy + r * 0.1f)
            lineTo(cx + r * 0.55f, cy - r * 0.4f)
        }
    drawProgressPath(spark, primary, p)
}

private fun DrawScope.bell(
    p: Float,
    plate: Color,
    primary: Color,
    line: Color,
) {
    val w = size.width
    val h = size.height
    val body =
        Path().apply {
            moveTo(w * 0.3f, h * 0.66f)
            cubicTo(w * 0.34f, h * 0.6f, w * 0.32f, h * 0.3f, w * 0.5f, h * 0.26f)
            cubicTo(w * 0.68f, h * 0.3f, w * 0.66f, h * 0.6f, w * 0.7f, h * 0.66f)
            close()
        }
    drawPath(body, plate)
    drawPath(body, line, style = Stroke(2.dp.toPx(), join = StrokeJoin.Round))
    drawCircle(primary, w * 0.05f, Offset(w * 0.5f, h * 0.72f))
    // Signal arcs.
    for (k in 1..2) {
        val r = w * (0.22f + k * 0.07f)
        drawArc(primary.copy(alpha = 0.6f * p), -60f, 40f, false, Offset(w * 0.5f - r, h * 0.42f - r), Size(r * 2, r * 2), style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
        drawArc(primary.copy(alpha = 0.6f * p), -160f, 40f, false, Offset(w * 0.5f - r, h * 0.42f - r), Size(r * 2, r * 2), style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
    }
}

private fun DrawScope.drawProgressPath(
    path: Path,
    color: Color,
    p: Float,
    dashed: Boolean = false,
) {
    val measure =
        androidx.compose.ui.graphics
            .PathMeasure()
    measure.setPath(path, false)
    val seg = Path()
    measure.getSegment(0f, measure.length * p, seg, true)
    drawPath(
        seg,
        color,
        style =
            Stroke(
                width = 3.dp.toPx(),
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
                pathEffect =
                    if (dashed) {
                        androidx.compose.ui.graphics.PathEffect
                            .dashPathEffect(floatArrayOf(10f, 10f))
                    } else {
                        null
                    },
            ),
    )
}

/** An illustrated empty state; the message keeps the "empty" test tag. */
@Composable
fun EmptyState(
    text: String,
    art: Art,
    modifier: Modifier = Modifier,
    action: @Composable (() -> Unit)? = null,
) {
    Column(modifier.fillMaxWidth().padding(vertical = 20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Illustration(art)
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 24.dp).testTag("empty"),
        )
        action?.invoke()
    }
}

/** Faint candlesticks and grid behind a hero card, like a terminal backdrop. */
fun Modifier.chartBackdrop(
    color: Color,
    seed: Int = 7,
): Modifier =
    drawBehind {
        val n = 18
        val slot = size.width / n
        var v = 0.55f
        var r = seed
        for (i in 0 until n) {
            r = (r * 1103515245 + 12345) and 0x7fffffff
            val step = ((r % 1000) / 1000f - 0.45f) * 0.12f
            val open = v
            v = (v - step).coerceIn(0.2f, 0.85f)
            val top = minOf(open, v) * size.height
            val bottom = maxOf(open, v) * size.height
            val x = slot * i + slot / 2f
            drawLine(color, Offset(x, top - 8f), Offset(x, bottom + 8f), strokeWidth = 1.5f)
            drawRect(color, Offset(x - slot * 0.25f, top), Size(slot * 0.5f, (bottom - top).coerceAtLeast(2f)))
        }
    }

/** Shimmering placeholder bars shown while content loads. */
@Composable
fun Skeleton(
    modifier: Modifier = Modifier,
    rows: Int = 3,
) {
    val t = rememberInfiniteTransition(label = "skeleton")
    val shift by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Restart), label = "shimmer")
    val base = MaterialTheme.colorScheme.surfaceContainerHigh
    val shine = MaterialTheme.colorScheme.surfaceContainerHighest
    Column(modifier.fillMaxWidth().padding(vertical = 12.dp).semantics { contentDescription = "Loading" }, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        repeat(rows) { i ->
            Box(
                Modifier
                    .fillMaxWidth(if (i == rows - 1) 0.6f else 1f)
                    .height(if (i == 0) 22.dp else 14.dp)
                    .drawBehind {
                        val x = size.width * (shift * 2f - 0.5f)
                        drawRoundRect(
                            Brush.linearGradient(listOf(base, shine, base), start = Offset(x - size.width * 0.3f, 0f), end = Offset(x + size.width * 0.3f, 0f)),
                            cornerRadius = CornerRadius(8.dp.toPx()),
                        )
                    }.background(Color.Transparent),
            )
        }
    }
}
