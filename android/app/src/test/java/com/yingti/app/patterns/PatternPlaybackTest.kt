package com.yingti.app.patterns

import org.junit.Assert.*
import org.junit.Test

class PatternPlaybackTest {
    @Test fun phoneTimelineUsesMonotonicClockAndCapsProgress() {
        val playback = PatternPlayback("builtin-wave", "event", 1_000_000_000, 30000)
        assertEquals(0f, playback.progress(0), 0f)
        assertEquals(.5f, playback.progress(16_000_000_000), 0f)
        assertEquals(1f, playback.progress(100_000_000_000), 0f)
    }

    @Test fun pauseFreezesProgressAndResumeContinuesFromBreakpoint() {
        val playback = PatternPlayback("id", "event", 0, 30000)
        val paused = playback.pausedAt(15_000_000_000)
        assertTrue(paused.paused)
        assertEquals(15000L, paused.pausedAtMs)
        // Progress stays frozen no matter how much wall time passes.
        assertEquals(.5f, paused.progress(75_000_000_000), 0f)
        val resumed = paused.resumedAt(75_000_000_000)
        assertFalse(resumed.paused)
        // Resume shifts the start so elapsed continues at the breakpoint.
        assertEquals(.5f, resumed.progress(75_000_000_000), 0f)
        assertEquals(2f / 3f, resumed.progress(80_000_000_000), 0.001f)
        // Double pause/resume is idempotent.
        assertSame(paused, paused.pausedAt(90_000_000_000))
        assertSame(resumed, resumed.resumedAt(99_000_000_000))
        assertSame(playback, playback.resumedAt(50_000_000_000))
    }
}
