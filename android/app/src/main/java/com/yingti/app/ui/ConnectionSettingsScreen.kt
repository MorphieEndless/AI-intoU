package com.yingti.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.yingti.app.auth.AuthMode
import com.yingti.app.auth.ConnectionConfig

@Composable
fun ConnectionSettingsScreen(
    initialConfig: ConnectionConfig,
    initialPassword: String = "",
    loading: Boolean,
    status: String?,
    error: String?,
    canCancel: Boolean,
    darkTheme: Boolean,
    paletteKey: String,
    devMode: Boolean,
    onDarkThemeChange: (Boolean) -> Unit,
    onPaletteChange: (String) -> Unit,
    onDevModeChange: (Boolean) -> Unit,
    onCancel: () -> Unit,
    onTest: (ConnectionConfig, String) -> Unit,
    onSave: (ConnectionConfig, String, Boolean) -> Unit,
    onCopy: (String, String) -> Unit,
) {
    var server by remember(initialConfig.serverBaseUrl) { mutableStateOf(initialConfig.serverBaseUrl) }
    var authMode by remember(initialConfig.authMode) { mutableStateOf(initialConfig.authMode) }
    var token by remember(initialConfig.token) { mutableStateOf(initialConfig.token) }
    var username by remember(initialConfig.username) { mutableStateOf(initialConfig.username) }
    var password by remember(initialPassword) { mutableStateOf(initialPassword) }
    var rememberPassword by remember { mutableStateOf(initialPassword.isNotEmpty()) }
    var mcpPath by remember(initialConfig.mcpPath) { mutableStateOf(initialConfig.mcpPath) }
    var relayPath by remember(initialConfig.relayPath) { mutableStateOf(initialConfig.relayPath) }
    var advanced by remember { mutableStateOf(false) }
    var showSecret by remember { mutableStateOf(false) }
    var pendingSensitiveCopy by remember { mutableStateOf<Pair<String, String>?>(null) }

    val draft = ConnectionConfig(
        serverBaseUrl = server,
        authMode = authMode,
        token = token,
        username = username,
        mcpPath = mcpPath,
        relayPath = relayPath,
    )
    val endpoints = remember(server, mcpPath, relayPath) {
        runCatching { draft.mcpUrl to draft.websocketUrl }.getOrNull()
    }
    val isHttp = runCatching { draft.usesCleartext }.getOrDefault(server.trim().startsWith("http://", true))
    val formReady = server.isNotBlank() && when (authMode) {
        AuthMode.TOKEN -> token.isNotBlank()
        AuthMode.ACCOUNT -> username.isNotBlank() && password.isNotBlank()
    }

    pendingSensitiveCopy?.let { (label, value) ->
        AlertDialog(
            onDismissRequest = { pendingSensitiveCopy = null },
            title = { Text("复制包含凭证的内容？") },
            text = { Text("$label 中含有完整 Bearer Token。请勿粘贴到公开聊天、Issue 或日志中。") },
            confirmButton = {
                Button(onClick = {
                    onCopy(label, value)
                    pendingSensitiveCopy = null
                }) { Text("仍然复制") }
            },
            dismissButton = { TextButton(onClick = { pendingSensitiveCopy = null }) { Text("取消") } },
        )
    }

    Scaffold(
        topBar = {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 14.dp)) {
                if (canCancel) IconButton(onClick = onCancel) { Icon(Icons.Outlined.ArrowBack, "返回") }
                Column(Modifier.padding(start = if (canCancel) 4.dp else 12.dp)) {
                    Text("连接设置", style = MaterialTheme.typography.headlineSmall)
                    Text("单服务器 · 自部署优先", color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SettingsSection("服务器") {
                OutlinedTextField(
                    value = server,
                    onValueChange = { server = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("服务器地址") },
                    placeholder = { Text("https://your-server.example.com") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                if (isHttp) {
                    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.medium) {
                        Text(
                            "HTTP 不加密账号、Bearer Token 和控制命令，只建议在可信局域网或 VPN 内使用。",
                            Modifier.fillMaxWidth().padding(14.dp),
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

            SettingsSection("认证方式") {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FilterChip(
                        selected = authMode == AuthMode.TOKEN,
                        onClick = { authMode = AuthMode.TOKEN },
                        label = { Text("Bearer Token") },
                    )
                    FilterChip(
                        selected = authMode == AuthMode.ACCOUNT,
                        onClick = { authMode = AuthMode.ACCOUNT },
                        label = { Text("账号登录") },
                    )
                }
                if (authMode == AuthMode.TOKEN) {
                    SecretField(
                        value = token,
                        onValueChange = { token = it },
                        label = "Bearer Token",
                        visible = showSecret,
                        onToggleVisibility = { showSecret = !showSecret },
                    )
                    Text("Token 将使用 Android 加密存储。App 与服务端必须配置同一个 Token。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                } else {
                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("用户名") },
                        leadingIcon = { Icon(Icons.Outlined.Person, null) },
                        singleLine = true,
                    )
                    SecretField(
                        value = password,
                        onValueChange = { password = it },
                        label = "密码",
                        visible = showSecret,
                        onToggleVisibility = { showSecret = !showSecret },
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = rememberPassword, onCheckedChange = { rememberPassword = it })
                        Text("记住密码（加密存储，下次自动填充）", style = MaterialTheme.typography.bodySmall)
                    }
                    Text("密码仅用于本次调用 /auth/login 换取 JWT；勾选后加密保存在本机。服务器签发的 JWT 也会加密保存。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                }
            }

            SettingsSection("外观") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("深色模式", style = MaterialTheme.typography.bodyLarge)
                        Text("主页面顶栏也可直接切换", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                    }
                    Switch(checked = darkTheme, onCheckedChange = onDarkThemeChange)
                }
                Text("UI 版式", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary)
                YingtiPalettes.forEach { palette ->
                    val selected = palette.key == paletteKey
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                if (selected) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceVariant
                            )
                            .clickable { onPaletteChange(palette.key) }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(20.dp).background(palette.light.primary, CircleShape))
                        Spacer(Modifier.width(10.dp))
                        Text(palette.name, style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.weight(1f))
                        if (selected) {
                            Text("当前", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }

            SettingsSection("开发者模式") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("协议调试入口", style = MaterialTheme.typography.bodyLarge)
                        Text("主页面显示自定义 HEX 帧", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                    }
                    Switch(checked = devMode, onCheckedChange = onDevModeChange)
                }
                Text(
                    "默认开启：便于适配其他型号玩具时自定义指令帧。关闭后主页面不再显示协议调试。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }

            SettingsSection("高级路径") {
                TextButton(onClick = { advanced = !advanced }, contentPadding = PaddingValues(0.dp)) {
                    Text(if (advanced) "收起高级设置" else "展开高级设置")
                }
                if (advanced) {
                    OutlinedTextField(
                        value = mcpPath, onValueChange = { mcpPath = it }, modifier = Modifier.fillMaxWidth(),
                        label = { Text("MCP Path") }, singleLine = true,
                    )
                    OutlinedTextField(
                        value = relayPath, onValueChange = { relayPath = it }, modifier = Modifier.fillMaxWidth(),
                        label = { Text("Phone Relay Path") }, singleLine = true,
                    )
                }
                endpoints?.let { (mcp, relay) ->
                    EndpointRow("MCP", mcp) { onCopy("MCP URL", mcp) }
                    EndpointRow("Relay", relay) { onCopy("Relay URL", relay) }
                }
            }

            status?.let {
                Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.medium) {
                    Text(it, Modifier.fillMaxWidth().padding(14.dp), color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
            error?.let {
                Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.medium) {
                    Text(it, Modifier.fillMaxWidth().padding(14.dp), color = MaterialTheme.colorScheme.onErrorContainer)
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = { onTest(draft, password) },
                    modifier = Modifier.weight(1f).height(52.dp),
                    enabled = !loading && formReady,
                ) { Text("测试连接") }
                Button(
                    onClick = { onSave(draft, password, rememberPassword) },
                    modifier = Modifier.weight(1f).height(52.dp),
                    enabled = !loading && formReady,
                ) {
                    if (loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    else Text("保存并启动")
                }
            }

            if (token.isNotBlank() && endpoints != null) {
                HorizontalDivider(Modifier.padding(top = 4.dp))
                Text("RikkaHub Remote MCP", style = MaterialTheme.typography.titleMedium)
                Text("第一版生成 Streamable HTTP 配置。复制完整配置前会再次提醒其中含有凭证。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                OutlinedButton(
                    onClick = { pendingSensitiveCopy = "RikkaHub 配置" to draft.rikkaHubJson() },
                    modifier = Modifier.fillMaxWidth(),
                ) { Icon(Icons.Outlined.ContentCopy, null); Spacer(Modifier.width(8.dp)); Text("复制完整 JSON") }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = { onCopy("MCP URL", draft.mcpUrl) }, modifier = Modifier.weight(1f)) { Text("复制 MCP URL") }
                    OutlinedButton(
                        onClick = { pendingSensitiveCopy = "Authorization" to "Bearer ${token.trim()}" },
                        modifier = Modifier.weight(1f),
                    ) { Text("复制 Authorization") }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = MaterialTheme.shapes.extraLarge, tonalElevation = 1.dp) {
        Column(
            Modifier.fillMaxWidth().padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun SecretField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    visible: Boolean,
    onToggleVisibility: () -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        leadingIcon = { Icon(Icons.Outlined.Lock, null) },
        trailingIcon = {
            IconButton(onClick = onToggleVisibility) {
                Icon(if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, if (visible) "隐藏" else "显示")
            }
        },
        singleLine = true,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
    )
}

@Composable
private fun EndpointRow(label: String, value: String, onCopy: () -> Unit) {
    Row(Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
            Text(value, style = MaterialTheme.typography.bodySmall)
        }
        IconButton(onClick = onCopy) { Icon(Icons.Outlined.ContentCopy, "复制 $label") }
    }
}
