package com.yingti.app.ble

import kotlin.math.roundToInt

object SvakomProtocol {
    const val SERVICE_UUID = "0000ffe0-0000-1000-8000-00805f9b34fb"
    const val WRITE_UUID = "0000ffe1-0000-1000-8000-00805f9b34fb"
    const val NOTIFY_UUID = "0000ffe2-0000-1000-8000-00805f9b34fb"

    const val SUCTION_MIN_MODE = 1
    const val SUCTION_MAX_MODE = 8
    const val SUCTION_DEFAULT_MODE = 5
    const val SUCTION_STEPS = 5

    /**
     * SX589B 实测协议：7 字节族。
     * 震动帧：55 03 00 00 <level 0-10> 01 00；停止帧全零。
     *
     * 吮吸帧：55 09 00 00 <mode> <level> 00。
     * byte4 模式：01=脉冲，02/03=抖动，04=另类脉冲，05=持续，
     *              06/07=节奏，08≈01，00=停止。
     * byte5 强度：仅 01-05 有效；06 是所有模式下的死档。
     */
    fun vibrate(level: Int): ByteArray {
        val safe = level.coerceIn(0, 10)
        return if (safe == 0) byteArrayOf(0x55, 0x03, 0x00, 0x00, 0x00, 0x00, 0x00)
        else byteArrayOf(0x55, 0x03, 0x00, 0x00, safe.toByte(), 0x01, 0x00)
    }

    /** 吮吸强度为 1-5；mode 透传 1-8。level=0 或 mode=0 均发送标准停止帧。 */
    fun suction(level: Int, mode: Int = SUCTION_DEFAULT_MODE): ByteArray {
        val safeLevel = level.coerceIn(0, SUCTION_STEPS)
        if (safeLevel == 0 || mode == 0) {
            return byteArrayOf(0x55, 0x09, 0x00, 0x00, 0x00, 0x00, 0x00)
        }
        require(mode in SUCTION_MIN_MODE..SUCTION_MAX_MODE) { "吮吸模式必须在 1-$SUCTION_MAX_MODE" }
        return byteArrayOf(0x55, 0x09, 0x00, 0x00, mode.toByte(), safeLevel.toByte(), 0x00)
    }

    /** 吮吸强度映射：0.0~1.0 → 0~5。 */
    fun suctionLevelFor(intensity: Double, floor: Double = 0.0): Int {
        val raw = intensity.coerceIn(0.0, 1.0)
        if (raw <= 0.0) return 0
        val adjusted = if (floor > 0) floor + raw * (1.0 - floor) else raw
        return (adjusted * SUCTION_STEPS).roundToInt().coerceIn(1, SUCTION_STEPS)
    }

    fun levelFor(intensity: Double, floor: Double = 0.0): Int {
        val raw = intensity.coerceIn(0.0, 1.0)
        if (raw <= 0.0) return 0
        val adjusted = if (floor > 0) floor + raw * (1.0 - floor) else raw
        return (adjusted * 10.0).roundToInt().coerceIn(1, 10)
    }

    val stopFrames: List<ByteArray> = listOf(vibrate(0), suction(0))
}
