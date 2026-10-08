package com.yingti.app.patterns

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WaveformGeometryTest {
    private fun waveform() = PatternDefinition.from(JSONObject("""{"id":"abc123456789","name":"节奏","repeat":2,"intensity_scale":0.4,"steps":[{"duration_ms":1000,"vibrate":0.2},{"duration_ms":500,"vibrate":0,"constrict":0},{"duration_ms":1000,"vibrate":0.7,"constrict":0.4,"constrict_mode":6}]}"""))
    @Test fun durationIncludesEveryRepeat() {
        assertEquals(5000L, waveform().totalMs)
        assertEquals("2.5×2·5秒", waveform().durationLabel)
    }
    @Test fun zeroRestsRemainZeroAndCommandsUnchanged() {
        val p = waveform()
        val before = p.toJson().toString()
        for (t in listOf(1000.0, 1100.0, 1250.0, 1499.0, 3500.0, 3900.0)) {
            assertEquals(0.0, WaveformGeometry.value(p, "vibrate", t), 0.0)
            assertEquals(0.0, WaveformGeometry.value(p, "constrict", t), 0.0)
        }
        WaveformGeometry.points(p, "vibrate")
        assertEquals(before, p.toJson().toString())
        assertEquals(.08, WaveformGeometry.value(p, "vibrate", 500.0, command = true), 1e-9)
    }
    @Test fun allTemplatesAreBoundedAndDistinct() {
        val templates = (0..10).map { gear -> (0..100).map { WaveformGeometry.rhythm("vibrate", gear / 10.0, 5, it / 10.0) } }
        templates.forEach { values -> assertTrue(values.all { it.isFinite() && it in 0.0..1.0 }) }
        assertEquals(11, templates.distinct().size)
        assertTrue(templates[0].all { it == 0.0 })
        for (mode in 1..8) for (i in 0..100) {
            val value = WaveformGeometry.rhythm("constrict", .8, mode, i / 10.0)
            assertTrue(value in 0.0..1.0)
        }
    }
    @Test fun curveControlsStayInsideEachSegmentAndRestIsFlat() {
        val points = WaveformGeometry.points(waveform(), "vibrate")
        assertEquals(0.0, points.first().ms, 0.0)
        assertEquals(5000.0, points.last().ms, 0.0)
        WaveformGeometry.segments(points).forEach { s ->
            val low = minOf(s.start.value, s.end.value) - 1e-9
            val high = maxOf(s.start.value, s.end.value) + 1e-9
            assertTrue(s.c1.value in low..high && s.c2.value in low..high)
        }
    }
    @Test fun ticksDoNotOverlapFinalLabel() {
        assertEquals(listOf(0.0, 5.0, 10.0, 15.0, 20.0, 24.0), WaveformGeometry.ticks(24000))
        assertEquals(listOf(0.0, .1), WaveformGeometry.ticks(100))
    }
    @Test fun geometryUsesConstantTimeScale() {
        assertEquals(10f, WaveformGeometry.PX_PER_SECOND, 0f)
        assertEquals(6016f, WaveformGeometry.width(600000, 320f), 0f)
    }
}
