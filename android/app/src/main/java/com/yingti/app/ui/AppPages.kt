package com.yingti.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Waves
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.yingti.app.AppScreen

@Composable
fun AppNavigation(screen: AppScreen, onScreen: (AppScreen) -> Unit, content: @Composable () -> Unit) {
    BackHandler(screen != AppScreen.DASHBOARD) { onScreen(AppScreen.DASHBOARD) }
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            NavigationBar {
                val tabs = listOf(
                    Triple(AppScreen.DASHBOARD, "玩具", Icons.Outlined.Bluetooth),
                    Triple(AppScreen.PATTERNS, "波形库", Icons.Outlined.Waves),
                    Triple(AppScreen.LOGS, "日志", Icons.Outlined.History),
                    Triple(AppScreen.SETTINGS, "设置", Icons.Outlined.Settings),
                )
                tabs.forEach { (target, label, icon) ->
                    NavigationBarItem(
                        selected = screen == target,
                        onClick = { onScreen(target) },
                        icon = { Icon(icon, null) },
                        label = { Text(label) },
                    )
                }
            }
        },
    ) { padding -> Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) { content() } }
}

@Composable
fun PatternLibraryPlaceholder(onSettings: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(48.dp))
        Text("先连接你的服务器", style = MaterialTheme.typography.titleLarge)
        Text(
            "波形库需要与你的云端服务器同步。请先到设置填写服务器地址与凭证，保存后即可查看云端波形库并重放。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.secondary,
            textAlign = TextAlign.Center,
        )
        Button(onClick = onSettings) { Text("打开设置") }
    }
}

@Composable
fun SettingsPages(devMode: Boolean, configured: Boolean, lastMessage: String, onRaw: (String) -> Unit, onLogout: () -> Unit, settings: @Composable () -> Unit) {
    var debug by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(devMode) { if (!devMode) debug = false }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            if (devMode) TextButton(onClick = { debug = !debug }) { Text(if (debug) "返回设置" else "协议调试") }
            if (configured) TextButton(onClick = onLogout) { Text("退出连接") }
        }
        Box(Modifier.weight(1f)) {
            if (debug && devMode) Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
                ProtocolDebugCard(lastMessage, onRaw)
            } else settings()
        }
    }
}
