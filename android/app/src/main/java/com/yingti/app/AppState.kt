package com.yingti.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

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
)

object AppState {
    private val _state = MutableStateFlow(BridgeState())
    val state = _state.asStateFlow()

    fun update(block: (BridgeState) -> BridgeState) { _state.update(block) }
    fun reset() { _state.value = BridgeState() }
}
