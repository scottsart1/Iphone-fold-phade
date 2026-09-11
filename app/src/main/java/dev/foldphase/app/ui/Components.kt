package dev.foldphase.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import dev.foldphase.diagnostics.HingeTrace
import java.util.Locale

fun Float.fmt(decimals: Int = 2): String =
    if (isNaN()) "—" else String.format(Locale.US, "%.${decimals}f", this)

/** A titled card wrapper used by every screen, so spacing stays uniform. */
@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    content: ColumnContent,
) {
    Card(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Column(Modifier.padding(top = 8.dp)) { content() }
        }
    }
}

typealias ColumnContent = @Composable () -> Unit

/** Label on the left, monospaced value on the right. */
@Composable
fun Readout(label: String, value: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall)
        Text(value, style = MonoNumber, color = MaterialTheme.colorScheme.onSurface)
    }
}

/**
 * A labelled slider that reports its value.
 *
 * Every tuning parameter in the dev panel goes through this, which is what keeps the
 * panel's ~20 controls from becoming 20 slightly different layouts.
 */
@Composable
fun TuningSlider(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    decimals: Int = 3,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, style = MaterialTheme.typography.bodySmall)
            Text(value.fmt(decimals), style = MonoNumber)
        }
        Slider(
            value = value.coerceIn(valueRange.start, valueRange.endInclusive),
            onValueChange = onValueChange,
            valueRange = valueRange,
        )
    }
}

/**
 * Live time/angle graph (brief §3).
 *
 * ## Why it draws from a reusable buffer
 *
 * The graph redraws every frame while the hinge moves. Snapshotting the trace into a new
 * list per frame would allocate tens of kilobytes a second on the exact code path whose
 * frame timing the rest of this screen is trying to measure — the instrument would be
 * changing the reading. Instead it fills two caller-owned `FloatArray`s that live as long
 * as the composable does.
 *
 * Both raw and filtered series are drawn, because seeing them together is the only way to
 * judge the jitter-versus-lag tradeoff the brief describes in §5.
 */
@Composable
fun HingeGraph(
    trace: HingeTrace,
    repaintKey: State<*>,
    minAngle: Float,
    maxAngle: Float,
    modifier: Modifier = Modifier,
    pointCount: Int = 360,
) {
    val rawBuf = remember(pointCount) { FloatArray(pointCount) }
    val filteredBuf = remember(pointCount) { FloatArray(pointCount) }
    val rawColor = Color(0xFF6B7A99)
    val filteredColor = Color(0xFF8AB4F8)
    val gridColor = Color(0x22FFFFFF)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(160.dp)
            .drawBehind {
                // Reading the key here keeps the redraw in the draw phase only.
                repaintKey.value

                val span = (maxAngle - minAngle).takeIf { it > 1e-3f } ?: 1f

                // Horizontal gridlines at 25% intervals.
                for (i in 0..4) {
                    val y = size.height * i / 4f
                    drawLine(gridColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
                }

                val nRaw = trace.recentRaw(rawBuf, pointCount)
                val nFiltered = trace.recentFiltered(filteredBuf, pointCount)
                if (nRaw < 2) return@drawBehind

                fun buildPath(buf: FloatArray, n: Int): Path {
                    val path = Path()
                    for (i in 0 until n) {
                        val x = size.width * i / (n - 1).coerceAtLeast(1)
                        val norm = ((buf[i] - minAngle) / span).coerceIn(0f, 1f)
                        val y = size.height * (1f - norm)
                        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    return path
                }

                drawPath(buildPath(rawBuf, nRaw), rawColor, style = Stroke(width = 1.5f))
                if (nFiltered >= 2) {
                    drawPath(
                        buildPath(filteredBuf, nFiltered),
                        filteredColor,
                        style = Stroke(width = 2.5f),
                    )
                }
            },
    )
}
