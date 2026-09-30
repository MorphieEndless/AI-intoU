package com.yingti.app.ble

import org.junit.Assert.*
import org.junit.Test

class FrameCacheTest {
    @Test fun cacheOnlySuccessfulFramesAndInvalidateOnReconnect() {
        val cache = FrameCache()
        val frame = SvakomProtocol.vibrate(3)
        assertFalse(cache.contains(1, frame))
        cache.record(1, frame)
        assertTrue(cache.contains(1, SvakomProtocol.vibrate(3)))
        assertFalse(cache.contains(1, SvakomProtocol.vibrate(4)))
        assertFalse(cache.contains(2, frame))
    }
    @Test fun channelsAreIndependentAndModesMatter() {
        val cache = FrameCache()
        cache.record(1, SvakomProtocol.vibrate(4))
        cache.record(1, SvakomProtocol.suction(2, 5))
        assertTrue(cache.contains(1, SvakomProtocol.vibrate(4)))
        assertTrue(cache.contains(1, SvakomProtocol.suction(2, 5)))
        assertFalse(cache.contains(1, SvakomProtocol.suction(2, 6)))
        cache.clear()
        assertFalse(cache.contains(1, SvakomProtocol.vibrate(4)))
    }
}
