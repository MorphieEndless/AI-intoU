package com.yingti.app.ble

import kotlin.math.roundToInt

object SvakomProtocol {
    const val SERVICE_UUID = "0000ffe0-0000-1000-8000-00805f9b34fb"
    const val WRITE_UUID = "0000ffe1-0000-1000-8000-00805f9b34fb"
    const val NOTIFY_UUID = "0000ffe2-0000-1000-8000-00805f9b34fb"

    /**
     * SX589B 实测协议：7 字节族（Klitty/文档式）。
     * 震动帧 55 03 00 00 <level 0-10> <sub 1> 00；停止帧全零。
     * 真机验证：byte4=强度(0A=满档)，byte5=01 基础/05 增强，byte6=00。
     *
     * 吮吸帧 55 09 00 00 <mode> <level> 00（真机验证）：
     *   byte4=模式：01=断续/脉冲，05=持续，00=停止
     *   byte5=强度：01 弱 ~ 06 强（真机仅 1-6 档有效）
     *   byte4=00 时无论 byte5 多少都停止
     */
    fun vibrate(level: Int): ByteArray {
        val safe = level.coerceIn(0, 10)
        return if (safe == 0) byteArrayOf(0x55, 0x03, 0x00, 0x00, 0x00, 0x00, 0x00)
        else byteArrayOf(0x55, 0x03, 0x00, 0x00, safe.toByte(), 0x01, 0x00)
    }

    /** 吮吸：默认持续模式(0x05)，强度走 byte5。真机实测强度仅 1-6 档有效。 */
    fun suction(level: Int, mode: Int = 5): ByteArray {
        val safe = level.coerceIn(0, 6)
        val safeMode = mode.coerceIn(1, 5)
        return if (safe == 0) byteArrayOf(0x55, 0x09, 0x00, 0x00, 0x00, 0x00, 0x00)
        else byteArrayOf(0x55, 0x09, 0x00, 0x00, safeMode.toByte(), safe.toByte(), 0x00)
    }

    /** 吮吸强度映射：0.0~1.0 → 0~6（真机仅 6 档有效）。 */
    fun suctionLevelFor(intensity: Double, floor: Double = 0.0): Int {
        val raw = intensity.coerceIn(0.0, 1.0)
        if (raw <= 0.0) return 0
        val adjusted = if (floor > 0) floor + raw * (1.0 - floor) else raw
        return (adjusted * 6.0).roundToInt().coerceIn(1, 6)
    }

    fun levelFor(intensity: Double, floor: Double = 0.0): Int {
        val raw = intensity.coerceIn(0.0, 1.0)
        if (raw <= 0.0) return 0
        val adjusted = if (floor > 0) floor + raw * (1.0 - floor) else raw
        return (adjusted * 10.0).roundToInt().coerceIn(1, 10)
    }

    val stopFrames: List<ByteArray> = listOf(vibrate(0), suction(0))
}
