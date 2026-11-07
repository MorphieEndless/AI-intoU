package com.yingti.app

import com.yingti.app.patterns.PatternPlayback
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class PausedOutput(val vibrateLevel: Int, val suctionLevel: Int, val suctionMode: Int)

enum class ToyControl { IDLE, RUNNING, PAUSED }

data class BridgeState(
    val serviceRunning: Boolean = false,
    val bleStatus: String = "未连接",
    val relayStatus: String = "未连接",
    val deviceName: String? = null,
    val intensity: Int = 0,
    val suctionIntensity: Int = 0,
    val suctionMode: Int = 5,
    val lastMessage: String = "等待启动",
    val error: String? = null,
    val patternPlayback: PatternPlayback? = null,
    val pausedOutput: PausedOutput? = null,
    val systemOverlayShowing: Boolean = false,
) {
    val toyControl: ToyControl get() = when {
        pausedOutput != null || patternPlayback?.paused == true -> ToyControl.PAUSED
        patternPlayback != null || intensity > 0 || suctionIntensity > 0 -> ToyControl.RUNNING
        else -> ToyControl.IDLE
    }
}

object AppState {
    private val _state = MutableStateFlow(BridgeState())
    val state = _state.asStateFlow()

    fun update(block: (BridgeState) -> BridgeState) { _state.update(block) }
    fun reset() { _state.value = BridgeState() }
}
