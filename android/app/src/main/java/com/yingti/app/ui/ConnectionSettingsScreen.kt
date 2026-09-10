package com.yingti.app.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import com.yingti.app.BuildConfig
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
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
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
    onSave: (ConnectionConfig, String, Boolean) -> Unit,
    onCopy: (String, String) -> Unit,
) {
    val context = LocalContext.current
    fun openProjectPage(url: String) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, "没有可打开网页的浏览器，请安装浏览器后重试", Toast.LENGTH_LONG).show()
        } catch (_: SecurityException) {
            Toast.makeText(context, "系统阻止打开网页，请检查浏览器设置", Toast.LENGTH_LONG).show()
        }
    }
    var server by remember(initialConfig.serverBaseUrl) { mutableStateOf(initialConfig.serverBaseUrl) }
    var authMode by remember(initialConfig.authMode) { mutableStateOf(initialConfig.authMode) }
    var token by remember(initialConfig.token) { mutableStateOf(initialConfig.token) }
    var username by remember(initialConfig.username) { mutableStateOf(initialConfig.username) }
    var password by remember(initialPassword) { mutableStateOf(initialPassword) }
    var rememberPassword by remember { mutableStateOf(initialPassword.isNotEmpty()) }
    var showSecret by remember { mutableStateOf(false) }
    var showJsonPreview by remember { mutableStateOf(false) }

    val draft = ConnectionConfig(
        serverBaseUrl = server,
        authMode = authMode,
        token = token,
        username = username,
        mcpPath = initialConfig.mcpPath,
        relayPath = initialConfig.relayPath,
    )
    val endpoints = remember(server, initialConfig.mcpPath, initialConfig.relayPath) {
        runCatching { draft.mcpUrl to draft.websocketUrl }.getOrNull()
    }
    val isHttp = runCatching { draft.usesCleartext }.getOrDefault(server.trim().startsWith("http://", true))
    val formReady = server.isNotBlank() && when (authMode) {
        AuthMode.TOKEN -> token.isNotBlank()
        AuthMode.ACCOUNT -> username.isNotBlank() && password.isNotBlank()
    }

    Scaffold(
        topBar = {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 14.dp)) {
                if (canCancel) IconButton(onClick = onCancel) { Icon(Icons.Outlined.ArrowBack, "返回") }
                Column(Modifier.padding(start = if (canCancel) 4.dp else 12.dp)) {
                    Text("连接设置", style = MaterialTheme.typography.headlineSmall)
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
                }
                // 登入：原「保存并启动」，位于记住密码下方，两种认证方式都从这里提交。
                YingtiPrimaryButton(
                    onClick = { onSave(draft, password, rememberPassword) },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    enabled = !loading && formReady,
                ) {
                    if (loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    else Text("登入")
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

            SettingsSection("外观") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("深色模式", style = MaterialTheme.typography.bodyLarge)
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
                        Box(Modifier.size(20.dp).background(if (darkTheme) palette.dark.primary else palette.light.primary, CircleShape))
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
                    }
                    Switch(checked = devMode, onCheckedChange = onDevModeChange)
                }
            }

            if (token.isNotBlank() && endpoints != null) {
                SettingsSection("RikkaHub Remote MCP") {
                    TextButton(
                        onClick = { showJsonPreview = !showJsonPreview },
                        contentPadding = PaddingValues(0.dp),
                    ) {
                        Text(if (showJsonPreview) "▾ 收起预览" else "▸ 预览完整 JSON")
                    }
                    if (showJsonPreview) {
                        Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(12.dp)) {
                            Text(
                                draft.rikkaHubJson(),
                                Modifier.fillMaxWidth().heightIn(max = 150.dp)
                                    .verticalScroll(rememberScrollState()).padding(12.dp),
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                    YingtiPrimaryButton(
                        onClick = { onCopy("RikkaHub 配置", draft.rikkaHubJson()) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("复制完整 JSON") }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(
                            onClick = { onCopy("MCP URL", draft.mcpUrl) },
                            modifier = Modifier.weight(1f),
                        ) { Text("复制 MCP URL") }
                        OutlinedButton(
                            onClick = { onCopy("Authorization", "Bearer ${token.trim()}") },
                            modifier = Modifier.weight(1f),
                        ) { Text("复制 Authorization") }
                    }
                }
            }

            SettingsSection("关于") {
                Text(
                    "AI-intoU · 樱趣  ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedButton(
                    onClick = { openProjectPage("https://github.com/MorphieEndless/AI-intoU/releases") },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("检查更新") }
                OutlinedButton(
                    onClick = { openProjectPage("https://github.com/MorphieEndless/AI-intoU") },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("作者信息") }
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
