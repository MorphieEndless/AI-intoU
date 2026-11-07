package com.yingti.app.ble

/** Minimal output surface the dispatcher needs; tests substitute a fake. */
interface ToyOutput {
    val isConnected: Boolean
    fun scan()
    suspend fun setVibration(intensity: Double): Result<Int>
    suspend fun setSuction(intensity: Double, mode: Int = SvakomProtocol.SUCTION_DEFAULT_MODE): Result<Int>
    suspend fun stopAll(): Result<Unit>
    suspend fun writeRaw(hexText: String): Result<String>
}
