package com.yingti.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yingti.app.patterns.*

/** Pure drawing: never rewrites steps or emits commands. Long charts share a 10dp/s scale. */
@Composable
internal fun WaveformPreview(pattern: PatternDefinition, command: Boolean = false, progress: Float? = null) {
    val colors = MaterialTheme.colorScheme
    val primary = colors.primary
    val suction = colors.tertiary
    val grid = colors.onSurfaceVariant.copy(alpha = .10f)
    val muted = colors.onSurfaceVariant.copy(alpha = .65f)
    val channels = remember(pattern.steps) { listOf("vibrate", "constrict").filter { pattern.hasChannel(it) } }
    val curves = remember(pattern.steps, pattern.repeat, pattern.intensityScale, command) { channels.associateWith { WaveformGeometry.segments(WaveformGeometry.points(pattern, it, command)) } }
    val density = LocalDensity.current.density
    val traces = remember(curves, density) {
        curves.mapValues { (_, segments) ->
            fun x(ms: Double) = (8 + ms / 1000 * WaveformGeometry.PX_PER_SECOND).toFloat() * density
            fun y(value: Double) = (82 - value * 70).toFloat() * density
            Path().apply {
                if (segments.isNotEmpty()) moveTo(x(segments.first().start.ms), y(segments.first().start.value))
                segments.forEach { s -> cubicTo(x(s.c1.ms), y(s.c1.value), x(s.c2.ms), y(s.c2.value), x(s.end.ms), y(s.end.value)) }
            }
        }
    }
    val wash = remember(traces, density, pattern.totalMs) {
        traces["vibrate"]?.let { path -> Path().apply {
            addPath(path)
            lineTo((8 + pattern.totalMs / 1000f * WaveformGeometry.PX_PER_SECOND) * density, 82 * density)
            lineTo(8 * density, 82 * density); close()
        } }
    }
    val ticks = remember(pattern.totalMs) { WaveformGeometry.ticks(pattern.totalMs) }
    val scroll = rememberScrollState()
    Surface(color = colors.primary.copy(alpha = .065f), shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.padding(top = 4.dp, bottom = 8.dp)) {
            Row {
                if (command) Box(Modifier.width(27.dp).height(88.dp)) {
                    for (level in listOf(.2f, .4f, .6f, .8f, 1f)) {
                        Text(level.toString(), Modifier.align(Alignment.TopEnd).offset(y = (82 - level * 70 - 5).dp).padding(end = 4.dp),
                            color = muted, fontSize = 9.sp)
                    }
                }
                BoxWithConstraints(Modifier.weight(1f)) {
                    val chartWidth = WaveformGeometry.width(pattern.totalMs, maxWidth.value)
                    Column(Modifier.horizontalScroll(scroll).width(chartWidth.dp).semantics {
                        contentDescription = "${pattern.name}，${secondsLabel(pattern.totalMs)}秒，${if (command) "指令值示意" else "节奏示意"}，可左右滑动查看"
                    }) {
                        Canvas(Modifier.fillMaxWidth().height(90.dp)) {
                            fun x(ms: Double) = (8 + ms / 1000 * WaveformGeometry.PX_PER_SECOND).toFloat().dp.toPx()
                            fun y(value: Double) = (82 - value * 70).toFloat().dp.toPx()
                            for (level in if (command) listOf(0.0, .2, .4, .6, .8, 1.0) else listOf(0.0, .25, .5, .75, 1.0))
                                drawLine(grid, Offset(8.dp.toPx(), y(level)), Offset(size.width - 8.dp.toPx(), y(level)), 1.dp.toPx())
                            var time = 0L
                            while (time <= pattern.totalMs) {
                                drawLine(grid, Offset(x(time.toDouble()), 8.dp.toPx()), Offset(x(time.toDouble()), 82.dp.toPx()), 1.dp.toPx())
                                time += 5000
                            }
                            for (channel in channels) {
                                val path = traces.getValue(channel)
                                val color = if (channel == "vibrate") primary else suction
                                if (channel == "vibrate" && wash != null) drawPath(wash, color.copy(alpha = .14f))
                                drawPath(path, color, style = Stroke(width = (if (channel == "vibrate") 2.2f else 2f).dp.toPx(), cap = StrokeCap.Round))
                            }
                            progress?.let {
                                val cursor = x(pattern.totalMs * it.coerceIn(0f, 1f).toDouble())
                                drawLine(primary.copy(alpha = .7f), Offset(cursor, 8.dp.toPx()), Offset(cursor, 82.dp.toPx()), 1.dp.toPx())
                            }
                        }
                        Box(Modifier.fillMaxWidth().height(18.dp)) {
                            ticks.forEachIndexed { index, t ->
                                val text = "${secondsLabel((t * 1000).toLong())}s"
                                // Only the final label is right-aligned, giving short durations room.
                                if (index == ticks.lastIndex && pattern.totalMs < 4000)
                                    Text(text, Modifier.align(Alignment.TopEnd).padding(end = 8.dp), color = muted, fontSize = 9.sp, fontWeight = FontWeight.Medium)
                                else Text(text, Modifier.offset(x = (8 + t * WaveformGeometry.PX_PER_SECOND - if (index == ticks.lastIndex) 24 else 0).dp),
                                    color = muted, fontSize = 9.sp, fontWeight = if (index == ticks.lastIndex) FontWeight.SemiBold else FontWeight.Normal)
                            }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(end = 10.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                channels.forEach { channel ->
                    Canvas(Modifier.padding(start = 9.dp, end = 4.dp).width(12.dp).height(10.dp)) {
                        drawLine(if (channel == "vibrate") primary else suction, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 2.dp.toPx(), StrokeCap.Round)
                    }
                    Text(if (channel == "vibrate") "震动" else "吮吸", color = muted, fontSize = 9.sp)
                }
            }
        }
    }
}
