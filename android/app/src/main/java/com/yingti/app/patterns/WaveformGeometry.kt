package com.yingti.app.patterns

import kotlin.math.*

/** Illustrative cadence templates from the approved demo, never hardware commands. */
object WaveformGeometry {
    const val PX_PER_SECOND = 10f
    val vibrationNames = listOf("停止", "持续", "波浪", "尖锐波浪", "长振循环", "断续", "中震×6 + 强震×2", "微颤渐强", "中震×4 + 强震×1", "快速断续 + 强震×1", "满功率")
    val suctionNames = mapOf(1 to "脉冲", 2 to "抖动", 3 to "抖动", 4 to "交替脉冲", 5 to "持续直吸", 6 to "波浪律动", 7 to "尖锐波浪", 8 to "脉冲")
    data class Point(val ms: Double, val value: Double)
    data class Segment(val start: Point, val c1: Point, val c2: Point, val end: Point)
    fun width(totalMs: Long, minWidth: Float) = max(minWidth, 16f + totalMs / 1000f * PX_PER_SECOND)
    private fun wave(t: Double, period: Double) = (1 - cos(2 * PI * t / period)) / 2
    private fun clamp(v: Double) = v.coerceIn(0.0, 1.0)
    fun rhythm(channel: String, level: Double, mode: Int, time: Double): Double {
        if (level <= 0) return 0.0
        val t = max(0.0, time)
        if (channel == "constrict") {
            val base = clamp(level)
            return when (mode) {
                5 -> base * (.88 + .12 * wave(t, 3.2))
                6 -> base * (.20 + .80 * wave(t, 6.0))
                2, 3 -> base * (.35 + .65 * wave(t, .9))
                4 -> base * (.2 + .8 * (.6 * wave(t, 2.4) + .4 * wave(t, 4.8)))
                7 -> base * (.18 + .82 * wave(t, 2.8).pow(1.4))
                else -> base * (.15 + .85 * max(0.0, sin(PI * (t % 2.4) / 1.5)).pow(1.3) * if (t % 2.4 < 1.5) 1 else 0)
            }
        }
        return when ((level * 10).roundToInt()) {
            0 -> 0.0
            1 -> .28 + .04 * wave(t, 3.0)
            2 -> .15 + .58 * wave(t, 5.0)
            3 -> .12 + .68 * (.65 * wave(t, 3.2) + .35 * wave(t, 1.6))
            4 -> .15 + .62 * wave(t, 6.0)
            5 -> { val ph = t % 2.5; if (ph < 1.5) .1 + .58 * sin(PI * ph / 1.5).pow(1.3) else .1 }
            6 -> { val a = if (floor(t).toInt() % 8 < 6) .42 else .88; .1 + a * wave(t, 1.0) }
            7 -> (.22 + .60 * wave(t, 6.0)) * (.82 + .18 * wave(t, 1.5))
            8 -> { val a = if (floor(t).toInt() % 5 < 4) .42 else .88; .1 + a * wave(t, 1.0) }
            9 -> { val ph = t % 4.5; if (ph < 3.0) .1 + .42 * wave(ph, .6) else .1 + .85 * wave(ph - 3.0, 1.5) }
            else -> .78 + .18 * wave(t, 1.8)
        }
    }
    private data class Located(val step: WaveStep, val index: Int, val loop: Int, val local: Double)
    private fun locate(p: PatternDefinition, ms: Double): Located {
        val pos = if (ms >= p.totalMs) p.oneMs - 1e-7 else max(0.0, ms) % p.oneMs
        var elapsed = 0L
        p.steps.forEachIndexed { i, s ->
            if (pos < elapsed + s.durationMs) return Located(s, i, min(p.repeat - 1, floor(max(0.0, ms) / p.oneMs).toInt()), (pos - elapsed) / 1000)
            elapsed += s.durationMs
        }
        return Located(p.steps.last(), p.steps.lastIndex, p.repeat - 1, 0.0)
    }
    fun value(p: PatternDefinition, channel: String, ms: Double, command: Boolean = false): Double {
        val (s, index, loop, local) = locate(p, ms)
        if (command) return s.output(channel) * p.intensityScale
        if (s.output(channel) == 0.0) return 0.0
        fun at(step: WaveStep, time: Double) = clamp(rhythm(channel, step.output(channel), step.mode, time) * p.intensityScale)
        val raw = at(s, local)
        val duration = s.durationMs / 1000.0
        val window = min(.7, duration * .2)
        val previous = if (index > 0) p.steps[index - 1] else if (loop > 0) p.steps.last() else null
        val next = if (index < p.steps.lastIndex) p.steps[index + 1] else if (loop < p.repeat - 1) p.steps.first() else null
        fun ease(t: Double) = (1 - cos(PI * clamp(t))) / 2
        if (previous != null && local < window) {
            val start = if (previous.output(channel) == 0.0) 0.0 else (at(previous, previous.durationMs / 1000.0) + at(s, 0.0)) / 2
            val weight = ease(local / window)
            return clamp(start * (1 - weight) + raw * weight)
        }
        if (next != null && duration - local < window) {
            val end = if (next.output(channel) == 0.0) 0.0 else (at(s, duration) + at(next, 0.0)) / 2
            val weight = ease((local - duration + window) / window)
            return clamp(raw * (1 - weight) + end * weight)
        }
        return raw
    }
    fun points(p: PatternDefinition, channel: String, command: Boolean = false): List<Point> {
        // Keep fast templates legible even over ten minutes; avoid the demo's 600-point aliasing.
        val count = max(600, ceil(p.totalMs / 50.0).toInt())
        val times = sortedSetOf<Double>()
        for (i in 0..count) times += i * p.totalMs.toDouble() / count
        var cursor = 0L
        repeat(p.repeat) {
            p.steps.forEach { step ->
                if (cursor > 0) { times += max(0.0, cursor - min(60.0, step.durationMs * .08)); times += cursor.toDouble() }
                cursor += step.durationMs
            }
        }
        return times.map { Point(it, value(p, channel, it, command)) }
    }
    fun segments(points: List<Point>): List<Segment> {
        if (points.size < 2) return emptyList()
        val slopes = points.zipWithNext { a, b -> (b.value - a.value) / (b.ms - a.ms) }
        val tangents = points.indices.map { i ->
            when {
                i == 0 -> slopes.first()
                i == points.lastIndex -> slopes.last()
                slopes[i - 1] * slopes[i] <= 0 -> 0.0
                else -> 2 / (1 / slopes[i - 1] + 1 / slopes[i])
            }
        }
        return (0 until points.lastIndex).map { i ->
            val a = points[i]; val b = points[i + 1]; val dx = (b.ms - a.ms) / 3
            Segment(a, Point(a.ms + dx, a.value + tangents[i] * dx), Point(b.ms - dx, b.value - tangents[i + 1] * dx), b)
        }
    }
    fun ticks(totalMs: Long): List<Double> {
        val total = totalMs / 1000.0
        val ticks = mutableListOf<Double>()
        var t = 0.0
        while (t < total) { ticks += t; t += 5 }
        while (ticks.size > 1 && total - ticks.last() < 4) ticks.removeAt(ticks.lastIndex)
        ticks += total
        return ticks
    }
}
