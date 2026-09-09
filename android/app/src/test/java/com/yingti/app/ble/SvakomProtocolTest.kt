package com.yingti.app.ble

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SvakomProtocolTest {
    @Test fun vibrationFrames() {
        assertArrayEquals(byteArrayOf(0x55, 0x03, 0, 0, 5, 1, 0), SvakomProtocol.vibrate(5))
        assertArrayEquals(byteArrayOf(0x55, 0x03, 0, 0, 10, 1, 0), SvakomProtocol.vibrate(10))
        assertArrayEquals(byteArrayOf(0x55, 0x03, 0, 0, 0, 0, 0), SvakomProtocol.vibrate(0))
    }

    @Test fun suctionFramesUseFiveStrengthLevelsAndEightModes() {
        assertArrayEquals(byteArrayOf(0x55, 0x09, 0, 0, 5, 5, 0), SvakomProtocol.suction(5))
        assertArrayEquals(byteArrayOf(0x55, 0x09, 0, 0, 1, 5, 0), SvakomProtocol.suction(5, 1))
        assertArrayEquals(byteArrayOf(0x55, 0x09, 0, 0, 8, 3, 0), SvakomProtocol.suction(3, 8))
        assertArrayEquals(byteArrayOf(0x55, 0x09, 0, 0, 5, 5, 0), SvakomProtocol.suction(10))
        assertArrayEquals(byteArrayOf(0x55, 0x09, 0, 0, 0, 0, 0), SvakomProtocol.suction(0))
        assertArrayEquals(byteArrayOf(0x55, 0x09, 0, 0, 0, 0, 0), SvakomProtocol.suction(5, 0))
        assertThrows(IllegalArgumentException::class.java) { SvakomProtocol.suction(3, 9) }
    }

    @Test fun suctionLevelMapping() {
        assertEquals(0, SvakomProtocol.suctionLevelFor(0.0))
        assertEquals(3, SvakomProtocol.suctionLevelFor(0.5))
        assertEquals(4, SvakomProtocol.suctionLevelFor(0.85))
        assertEquals(5, SvakomProtocol.suctionLevelFor(1.0))
    }

    @Test fun intensityMapping() {
        assertEquals(0, SvakomProtocol.levelFor(0.0))
        assertEquals(5, SvakomProtocol.levelFor(0.5))
        assertEquals(9, SvakomProtocol.levelFor(0.85))
        assertEquals(10, SvakomProtocol.levelFor(1.0))
    }

    @Test fun stopFrameIsVibrateZero() {
        assertArrayEquals(SvakomProtocol.stopFrames.first(), SvakomProtocol.vibrate(0))
    }
}
