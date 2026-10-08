package com.yingti.app.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.Logout
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.res.stringResource
import com.yingti.app.R
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yingti.app.BridgeState
import kotlin.math.roundToInt

private enum class SuctionPanel { TOY, FREE }
private data class ToyPreset(val label: String, val mode: Int, val level: Int, val detail: String)

private val toyPresets = listOf(
    ToyPreset("1", 5, 1, "持续·弱"),
    ToyPreset("2", 5, 2, "持续·中"),
    ToyPreset("3", 5, 3, "持续·强"),
    // 4/5/6 → byte4 06/07/08，v0.10.0 真机初步确认可用，顺序校准待定。
    ToyPreset("4", 6, 3, "节奏 06"),
    ToyPreset("5", 7, 3, "节奏 07"),
    ToyPreset("6", 8, 3, "节奏 08"),
)

// 真机定论去重后的 6 个有效模式（02=03 抖动、08=01 脉冲均同一模式，已合并；4/5/6 经真机实测定名）。
private val suctionModes = listOf(
    5 to "持续直吸",
    1 to "脉冲",
    2 to "抖动",
    4 to "交替脉冲",
    6 to "波浪律动",
    7 to "节奏 B",
)

// 震动档位语义（0-10 档全部实测校准：7/8/9 经真机逐档体验确定）。
private val vibDescriptions = mapOf(
    0 to "停止",
    1 to "持续",
    2 to "波浪",
    3 to "尖锐波浪",
    4 to "长振循环",
    5 to "断续",
    6 to "中震×6 + 强震×2",
    7 to "微颤渐强",
    8 to "中震×4 + 强震×1",
    9 to "快速断续 + 强震×1",
    10 to "满功率",
)

@Composable
fun DashboardScreen(
    state: BridgeState,
    server: String,
    darkTheme: Boolean,
    devMode: Boolean,
    onToggleTheme: () -> Unit,
    onScan: () -> Unit,
    onVibrate: (Double) -> Unit,
    onStop: () -> Unit,
    onSettings: () -> Unit,
    onLogout: () -> Unit,
    onRawFrame: (String) -> Unit = {},
    onSuction: (Double, Int) -> Unit = { _, _ -> },
) {
    var slider by remember { mutableFloatStateOf(state.intensity / 10f) }
    var dragging by remember { mutableStateOf(false) }
    LaunchedEffect(state.intensity) { if (!dragging) slider = state.intensity / 10f }
    var suctionPanel by remember { mutableStateOf(SuctionPanel.TOY) }
    var suctionMode by remember(state.suctionMode) { mutableIntStateOf(state.suctionMode.coerceIn(1, 8)) }
    var suctionLevel by remember { mutableFloatStateOf(state.suctionIntensity.coerceIn(0, 5).toFloat()) }
    var suctionDragging by remember { mutableStateOf(false) }
    LaunchedEffect(state.suctionIntensity) {
        if (!suctionDragging) suctionLevel = state.suctionIntensity.coerceIn(0, 5).toFloat()
    }
    val selectedToy = toyPresets.indexOfFirst {
        state.suctionIntensity > 0 && it.mode == state.suctionMode && it.level == state.suctionIntensity
    }

    Scaffold(
        topBar = {
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SakuraLogo(Modifier.size(24.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall)
                    }
                    Text(
                        server.removePrefix("https://"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = onToggleTheme) {
                    Icon(
                        if (darkTheme) Icons.Outlined.LightMode else Icons.Outlined.DarkMode,
                        if (darkTheme) "切换亮色" else "切换深色",
                    )
                }
                IconButton(onClick = onSettings) { Icon(Icons.Outlined.Settings, "连接设置") }
                IconButton(onClick = onLogout) { Icon(Icons.Outlined.Logout, "清除凭证并退出") }
                // v0.11.1: 红色急停已从顶栏移入设备卡右上角（见下），顶栏只留 3 个图标。
            }
        }
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatusCard(Modifier.weight(1f), Icons.Outlined.Bluetooth, "蓝牙", state.bleStatus)
                StatusCard(Modifier.weight(1f), Icons.Outlined.Cloud, "Relay", state.relayStatus)
            }

            Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
                Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    // 设备卡头部：设备名 + 状态在左，红色急停按钮固定在右上角（v0.11.1 起）。
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(state.deviceName ?: "SX589B", style = MaterialTheme.typography.titleLarge)
                            Text(state.lastMessage, color = MaterialTheme.colorScheme.secondary)
                        }
                        Box(
                            Modifier
                                .size(44.dp)
                                .background(Color(0xFFB3261E), CircleShape)
                                .clickable(onClick = onStop),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Filled.Stop, "STOP ALL", tint = Color.White, modifier = Modifier.size(24.dp))
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("震动", style = MaterialTheme.typography.titleLarge)
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text("${(slider * 10).roundToInt()}", style = MaterialTheme.typography.displayMedium)
                        Text(" / 10 档", modifier = Modifier.padding(bottom = 8.dp), color = MaterialTheme.colorScheme.secondary)
                    }
                    Slider(
                        value = slider,
                        onValueChange = { dragging = true; slider = it },
                        onValueChangeFinished = { dragging = false; onVibrate(slider.toDouble()) },
                        steps = 9,
                    )
                    val level = (slider * 10).roundToInt()
                    vibDescriptions[level]?.let { desc ->
                        Text(
                            "档位 $level · $desc",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = onScan) { Text("重新扫描") }
                        TextButton(onClick = {
                            slider = 0f
                            onVibrate(0.0)
                        }) { Text("停止") }
                    }
                }
            }

            SuctionCard(
                panel = suctionPanel,
                selectedToy = selectedToy,
                mode = suctionMode,
                level = suctionLevel,
                onPanelChange = { suctionPanel = it },
                onToyPreset = { preset ->
                    suctionMode = preset.mode
                    suctionLevel = preset.level.toFloat()
                    onSuction(preset.level / 5.0, preset.mode)
                },
                onModeChange = { suctionMode = it },
                onLevelChange = { suctionDragging = true; suctionLevel = it },
                onLevelChangeFinished = {
                    // A tap updates and commits in the same frame; read the live state here.
                    suctionDragging = false
                    val level = suctionLevel.roundToInt().coerceIn(0, 5)
                    onSuction(level / 5.0, suctionMode)
                },
                onSuction = { value, mode -> suctionDragging = false; onSuction(value, mode) },
                onStopSuction = {
                    suctionDragging = false
                    suctionLevel = 0f
                    onSuction(0.0, suctionMode)
                },
            )

            if (devMode) {
                ProtocolDebugCard(lastMessage = state.lastMessage, onSendRaw = onRawFrame)
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun StatusCard(modifier: Modifier, icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, status: String) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(label, style = MaterialTheme.typography.labelLarge)
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(10.dp).background(
                        color = when {
                            status.contains("已连接") -> Color(0xFF2E7D32)
                            status.contains("扫描") || status.contains("连接") || status.contains("发现服务") -> Color(0xFF2E6FB5)
                            else -> Color(0xFF9E9E9E)
                        },
                        shape = CircleShape,
                    )
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    status,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun SuctionCard(
    panel: SuctionPanel,
    selectedToy: Int,
    mode: Int,
    level: Float,
    onPanelChange: (SuctionPanel) -> Unit,
    onToyPreset: (ToyPreset) -> Unit,
    onModeChange: (Int) -> Unit,
    onLevelChange: (Float) -> Unit,
    onLevelChangeFinished: () -> Unit,
    onSuction: (Double, Int) -> Unit,
    onStopSuction: () -> Unit,
) {
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
        Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("吮吸", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TabRow(
                    selectedTabIndex = panel.ordinal,
                    modifier = Modifier.width(180.dp),
                    containerColor = Color.Transparent,
                ) {
                    Tab(
                        selected = panel == SuctionPanel.TOY,
                        onClick = { onPanelChange(SuctionPanel.TOY) },
                        text = { Text("玩具预设") },
                    )
                    Tab(
                        selected = panel == SuctionPanel.FREE,
                        onClick = { onPanelChange(SuctionPanel.FREE) },
                        text = { Text("自由组合") },
                    )
                }
            }

            if (panel == SuctionPanel.TOY) {
                // Restore v0.11.1's three-column, two-row preset grid.
                toyPresets.chunked(3).forEachIndexed { rowIndex, row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEachIndexed { columnIndex, preset ->
                            val index = rowIndex * 3 + columnIndex
                            ElevatedButton(
                                onClick = { onToyPreset(preset) },
                                modifier = Modifier.weight(1f).heightIn(min = 72.dp),
                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
                                colors = if (selectedToy == index) ButtonDefaults.elevatedButtonColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                ) else ButtonDefaults.elevatedButtonColors(),
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        preset.label,
                                        style = MaterialTheme.typography.titleLarge,
                                        maxLines = 1,
                                    )
                                    Text(
                                        preset.detail,
                                        style = MaterialTheme.typography.labelSmall,
                                        textAlign = TextAlign.Center,
                                        maxLines = 2,
                                        softWrap = true,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onStopSuction) { Text("停止") }
                }
            } else {
                // 自由组合模式：流体双轨分段控件 (Fluid Segmented Dual-Track)
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("模式", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary)
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        suctionModes.chunked(3).forEach { row ->
                            val selectedIndex = row.indexOfFirst { it.first == mode }
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(42.dp),
                                shape = RoundedCornerShape(21.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.5f),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
                            ) {
                                BoxWithConstraints(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(3.dp),
                                ) {
                                    val itemWidth = maxWidth / 3

                                    if (selectedIndex != -1) {
                                        val pillOffset by animateDpAsState(
                                            targetValue = itemWidth * selectedIndex,
                                            animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
                                            label = "suction-mode-pill",
                                        )
                                        Box(
                                            modifier = Modifier
                                                .offset(x = pillOffset)
                                                .width(itemWidth)
                                                .fillMaxHeight()
                                                .shadow(1.dp, RoundedCornerShape(18.dp))
                                                .background(
                                                    color = MaterialTheme.colorScheme.surface,
                                                    shape = RoundedCornerShape(18.dp),
                                                ),
                                        )
                                    }

                                    Row(
                                        modifier = Modifier.fillMaxSize(),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        row.forEach { (value, label) ->
                                            val selected = mode == value
                                            Box(
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .fillMaxHeight()
                                                    .clip(RoundedCornerShape(18.dp))
                                                    .clickable(
                                                        interactionSource = remember { MutableInteractionSource() },
                                                        indication = null,
                                                        onClick = {
                                                            onModeChange(value)
                                                            val lvl = level.roundToInt()
                                                            if (lvl > 0) {
                                                                onSuction(lvl / 5.0, value)
                                                            }
                                                        },
                                                    ),
                                                contentAlignment = Alignment.Center,
                                            ) {
                                                Text(
                                                    text = label,
                                                    style = MaterialTheme.typography.labelLarge,
                                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                                    color = if (selected) MaterialTheme.colorScheme.primary
                                                           else MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text("${level.roundToInt()}", style = MaterialTheme.typography.displaySmall)
                        Text(" / 5 档", modifier = Modifier.padding(bottom = 6.dp), color = MaterialTheme.colorScheme.secondary)
                    }
                    Slider(
                        value = level,
                        onValueChange = onLevelChange,
                        valueRange = 0f..5f,
                        steps = 4,
                        onValueChangeFinished = onLevelChangeFinished,
                    )
                }
            }
        }
    }
}

@Composable
internal fun ProtocolDebugCard(lastMessage: String, onSendRaw: (String) -> Unit) {
    var rawInput by remember { mutableStateOf("") }
    var sentConfirm by remember { mutableStateOf(false) }

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 1.dp,
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("协议调试", style = MaterialTheme.typography.titleMedium)
            Text(
                "原始帧下发（大写 HEX，以 55AA 开头，必须为 8/9/10 字节）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
            )
            OutlinedTextField(
                value = rawInput,
                onValueChange = {
                    rawInput = it
                    sentConfirm = false
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("自定义 HEX 指令") },
                placeholder = { Text("55AA...") },
                singleLine = true,
                isError = rawInput.isNotBlank() && !isValidHex(rawInput),
                supportingText = {
                    if (rawInput.isNotBlank() && !isValidHex(rawInput)) {
                        Text("格式错误：需为 8/9/10 字节偶数位 HEX，如 55AA04010000005F")
                    } else if (sentConfirm) {
                        Text("已发送：$lastMessage", color = MaterialTheme.colorScheme.primary)
                    }
                }
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Button(
                    onClick = {
                        val hex = rawInput.trim().replace(" ", "").uppercase()
                        if (isValidHex(hex)) {
                            onSendRaw(hex)
                            sentConfirm = true
                        }
                    },
                    enabled = isValidHex(rawInput),
                ) {
                    Text("发送")
                }
            }
        }
    }
}

private fun isValidHex(input: String): Boolean {
    val clean = input.trim().replace(" ", "").uppercase()
    if (!clean.startsWith("55AA")) return false
    if (clean.length % 2 != 0) return false
    val byteCount = clean.length / 2
    if (byteCount !in 8..10) return false
    return clean.all { it in '0'..'9' || it in 'A'..'F' }
}
