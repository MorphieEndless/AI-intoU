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
}
