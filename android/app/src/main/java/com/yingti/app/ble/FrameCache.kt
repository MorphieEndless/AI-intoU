package com.yingti.app.ble

/** Confined to the BLE write mutex. Never cache a failed/unsent frame. */
internal class FrameCache {
    private var generation = Long.MIN_VALUE
    private val frames = mutableMapOf<Int, ByteArray>()

    private fun select(connection: Long) {
        if (connection != generation) {
            frames.clear()
            generation = connection
        }
    }

    fun contains(connection: Long, frame: ByteArray): Boolean {
        select(connection)
        return frames[frame[1].toInt()]?.contentEquals(frame) == true
    }

    fun record(connection: Long, frame: ByteArray) {
        select(connection)
        frames[frame[1].toInt()] = frame.copyOf()
    }

    fun clear() { frames.clear() }
}
