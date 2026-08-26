package com.yingti.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Logout
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.yingti.app.BridgeState
import kotlin.math.roundToInt

@Composable
fun DashboardScreen(
    state: BridgeState,
    server: String,
    onScan: () -> Unit,
    onVibrate: (Double) -> Unit,
    onStop: () -> Unit,
    onLogout: () -> Unit,
    onRawFrame: (String) -> Unit = {},
    onSuction: (Double) -> Unit = {},
) {
    var slider by remember(state.intensity) { mutableFloatStateOf(state.intensity / 10f) }
    var suctionSlider by remember(state.suctionIntensity) { mutableFloatStateOf(state.suctionIntensity / 6f) }
    Scaffold(
        topBar = {
            Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("樱媞 Bridge", style = MaterialTheme.typography.headlineSmall)
                    Text(server.removePrefix("https://"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                }
                IconButton(onClick = onLogout) { Icon(Icons.Outlined.Logout, "退出") }
            }
        }
    ) { padding ->
        Column(
            Modifier.padding(padding).padding(horizontal = 20.dp).fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatusCard(Modifier.weight(1f), Icons.Outlined.Bluetooth, "蓝牙", state.bleStatus, state.bleStatus == "已连接")
                StatusCard(Modifier.weight(1f), Icons.Outlined.Cloud, "Relay", state.relayStatus, state.relayStatus == "已连接")
            }

            Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
                Column(Modifier.padding(22.dp)) {
                    Text(state.deviceName ?: "SX589B", style = MaterialTheme.typography.titleLarge)
                    Text(state.lastMessage, color = MaterialTheme.colorScheme.secondary)
                    Spacer(Modifier.height(28.dp))
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text("${(slider * 10).roundToInt()}", style = MaterialTheme.typography.displayMedium)
                        Text(" / 10 档", modifier = Modifier.padding(bottom = 8.dp), color = MaterialTheme.colorScheme.secondary)
                    }
                    Slider(
                        value = slider,
                        onValueChange = { slider = it },
                        onValueChangeFinished = { onVibrate(slider.toDouble()) },
                        steps = 9,
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(onClick = onScan) { Text("重新扫描") }
                        Text(if (state.serviceRunning) "前台服务运行中" else "服务未运行", color = MaterialTheme.colorScheme.secondary)
                    }
                    HorizontalDivider(Modifier.padding(vertical = 10.dp))
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text("${(suctionSlider * 6).roundToInt()}", style = MaterialTheme.typography.displaySmall)
                        Text(" / 6 档吮吸", modifier = Modifier.padding(bottom = 8.dp), color = MaterialTheme.colorScheme.secondary)
                    }
                    Slider(
                        value = suctionSlider,
                        onValueChange = { suctionSlider = it },
                        onValueChangeFinished = { onSuction(suctionSlider.toDouble()) },
                        steps = 5,
                    )
                }
            }

            state.error?.let {
                Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(14.dp)) {
                    Text(it, Modifier.fillMaxWidth().padding(14.dp), color = MaterialTheme.colorScheme.onErrorContainer)
                }
            }

            ProtocolDebugCard(
                lastMessage = state.lastMessage,
                onSendRaw = onRawFrame,
            )

            Spacer(Modifier.weight(1f))
            Button(
                onClick = { slider = 0f; suctionSlider = 0f; onStop() },
                modifier = Modifier.fillMaxWidth().height(68.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB3261E)),
                shape = RoundedCornerShape(20.dp),
            ) { Text("STOP ALL", style = MaterialTheme.typography.titleLarge) }
            Text(
                "断网、Relay 断开或服务退出时会自动发送停止帧。",
                modifier = Modifier.fillMaxWidth().padding(bottom = 18.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
            )
        }
    }
}

@Composable
private fun StatusCard(modifier: Modifier, icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, status: String, online: Boolean) {
    Surface(modifier, shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null)
                Spacer(Modifier.weight(1f))
                Box(Modifier.size(9.dp).background(if (online) Color(0xFF2E7D32) else Color(0xFF9E9E9E), CircleShape))
            }
            Text(title, style = MaterialTheme.typography.labelLarge)
            Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
        }
    }
}

private val quickFrames = listOf(
    "已验证·7字节" to listOf(
        "震动5档 55 03 00 00 05 01 00",
        "震动满 55 03 00 00 0A 01 00",
        "增强帧 55 03 00 00 05 05 00",
        "停止 55 03 00 00 00 00 00",
    ),
    "吮吸·已验证" to listOf(
        "吮持续5 55 09 00 00 05 05 00",
        "吮脉冲5 55 09 00 00 01 05 00",
        "吮持续1 55 09 00 00 05 01 00",
        "吮停止 55 09 00 00 00 00 00",
    ),
)

@Composable
private fun ProtocolDebugCard(lastMessage: String, onSendRaw: (String) -> Unit) {
    var hex by remember { mutableStateOf("55 03 00 00 01 01 00") }
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceVariant, tonalElevation = 1.dp) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("协议调试", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = hex,
                    onValueChange = { hex = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall,
                    label = { Text("HEX 帧") },
                )
                Button(onClick = { if (hex.isNotBlank()) onSendRaw(hex) }) { Text("发送") }
            }
            quickFrames.forEach { (group, frames) ->
                Text(group, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                frames.forEach { frame ->
                    val label = frame.substringBefore(' ')
                    val bytes = frame.substringAfter(' ')
                    AssistChip(
                        onClick = { onSendRaw(bytes) },
                        label = { Text("$label · $bytes", style = MaterialTheme.typography.bodySmall) },
                    )
                }
            }
            Text("最近: $lastMessage", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
        }
    }
}
