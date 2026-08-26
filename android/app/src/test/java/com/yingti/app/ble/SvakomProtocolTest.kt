package com.yingti.app.ble

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class SvakomProtocolTest {
    @Test fun vibrationFrames() {
        assertArrayEquals(byteArrayOf(0x55, 0x03, 0, 0, 5, 1, 0), SvakomProtocol.vibrate(5))
        assertArrayEquals(byteArrayOf(0x55, 0x03, 0, 0, 10, 1, 0), SvakomProtocol.vibrate(10))
        assertArrayEquals(byteArrayOf(0x55, 0x03, 0, 0, 0, 0, 0), SvakomProtocol.vibrate(0))
    }
    @Test fun suctionFrames() {
        // 持续模式默认：byte4=05 模式, byte5=强度（真机 1-6 档）
        assertArrayEquals(byteArrayOf(0x55, 0x09, 0, 0, 5, 5, 0), SvakomProtocol.suction(5))
        assertArrayEquals(byteArrayOf(0x55, 0x09, 0, 0, 5, 6, 0), SvakomProtocol.suction(6))
        assertArrayEquals(byteArrayOf(0x55, 0x09, 0, 0, 5, 6, 0), SvakomProtocol.suction(10)) // 7+ 无效，钳到 6
        // 全零停止
        assertArrayEquals(byteArrayOf(0x55, 0x09, 0, 0, 0, 0, 0), SvakomProtocol.suction(0))
    }
    @Test fun suctionLevelMapping() {
        assertEquals(0, SvakomProtocol.suctionLevelFor(0.0))
        assertEquals(3, SvakomProtocol.suctionLevelFor(0.5))
        assertEquals(5, SvakomProtocol.suctionLevelFor(0.85)) // nearest of 6 real levels
        assertEquals(6, SvakomProtocol.suctionLevelFor(1.0))
    }
    @Test fun intensityMapping() {
        assertEquals(0, SvakomProtocol.levelFor(0.0))
        assertEquals(5, SvakomProtocol.levelFor(0.5))
        assertEquals(9, SvakomProtocol.levelFor(0.85)) // no 0.05 level: snap to 0.9
        assertEquals(10, SvakomProtocol.levelFor(1.0))
    }
    @Test fun stopFrameIsVibrateZero() {
        assertArrayEquals(SvakomProtocol.stopFrames.first(), SvakomProtocol.vibrate(0))
    }
}
