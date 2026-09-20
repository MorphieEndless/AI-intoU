package com.yingti.app.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yingti.app.BuildConfig
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
    darkTheme: Boolean = false,
    paletteKey: String = "wine",
    devMode: Boolean = false,
    onDarkThemeChange: (Boolean) -> Unit = {},
    onPaletteChange: (String) -> Unit = {},
    onDevModeChange: (Boolean) -> Unit = {},
    onCancel: () -> Unit,
    onSave: (ConnectionConfig, String, Boolean) -> Unit,
    onCopy: (String, String) -> Unit,
    onClearHistory: () -> Unit = {},
) {
    var server by remember(initialConfig.serverBaseUrl) { mutableStateOf(initialConfig.serverBaseUrl) }
    var authMode by remember(initialConfig.authMode) { mutableStateOf(initialConfig.authMode) }
    var token by remember(initialConfig.token) { mutableStateOf(initialConfig.token) }
    var username by remember(initialConfig.username) { mutableStateOf(initialConfig.username) }
    var password by remember(initialPassword) { mutableStateOf(initialPassword) }
    var rememberPassword by remember { mutableStateOf(initialPassword.isNotEmpty()) }
    var tokenVisible by remember { mutableStateOf(false) }
    var passwordVisible by remember { mutableStateOf(false) }
    var showJsonPreview by remember { mutableStateOf(false) }
    var showClearDialog by remember { mutableStateOf(false) }
    var clearConfirmInput by remember { mutableStateOf("") }
    val draft = ConnectionConfig(server, authMode, token, username)
    val endpoints = draft.resolvedEndpoints()
    val context = LocalContext.current

    fun openProjectPage(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, "未找到可用浏览器", Toast.LENGTH_SHORT).show()
        }
    }

    val requiredConfirmText = if (token.trim().isNotBlank()) token.trim() else "CONFIRM-DELETE-LOCAL-HISTORY"
    val isConfirmMatched = clearConfirmInput.trim() == requiredConfirmText

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("确认清除全部本机记录？", color = MaterialTheme.colorScheme.error) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "将永久抹除本机保存的全部操作日志及使用频率统计，不可恢复。云端波形与硬件不受影响。",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        if (token.trim().isNotBlank())
                            "为防止误触破坏，请输入当前配置的 Token 以确认："
                        else
                            "为防止误触破坏，请输入 CONFIRM-DELETE-LOCAL-HISTORY 以确认：",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    OutlinedTextField(
                        value = clearConfirmInput,
                        onValueChange = { clearConfirmInput = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        placeholder = {
                            Text(
                                if (token.trim().isNotBlank()) "粘贴或输入当前完整 Token"
                                else "CONFIRM-DELETE-LOCAL-HISTORY",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        },
                        isError = clearConfirmInput.isNotBlank() && !isConfirmMatched,
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onClearHistory()
                        showClearDialog = false
                    },
                    enabled = isConfirmMatched,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) {
                    Text("确认删除")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text("取消")
                }
            },
        )
    }

    Scaffold(
        topBar = {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (canCancel) {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.Outlined.ArrowBack, contentDescription = "返回")
                    }
                }
                Text("设置", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            }
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SettingsSection("服务器地址") {
                OutlinedTextField(
                    value = server,
                    onValueChange = { server = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("服务地址") },
                    placeholder = { Text("https://example.com:8443") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    singleLine = true,
                )
                if (endpoints != null && !endpoints.isSecure) {
                    Text("当前使用 HTTP 明文地址，凭证与控制指令可能在局域网内被监听。", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }

            SettingsSection("认证方式") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = authMode == AuthMode.TOKEN,
                        onClick = { authMode = AuthMode.TOKEN },
                        label = { Text("Token 模式") },
                    )
                    FilterChip(
                        selected = authMode == AuthMode.ACCOUNT,
                        onClick = { authMode = AuthMode.ACCOUNT },
                        label = { Text("账号密码") },
                    )
                }

                if (authMode == AuthMode.TOKEN) {
                    SecretField(
                        value = token,
                        onValueChange = { token = it },
                        label = "Bearer Token",
                        visible = tokenVisible,
                        onToggleVisibility = { tokenVisible = !tokenVisible },
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
                        visible = passwordVisible,
                        onToggleVisibility = { passwordVisible = !passwordVisible },
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = rememberPassword, onCheckedChange = { rememberPassword = it })
                        Text("记住密码", style = MaterialTheme.typography.bodyMedium)
                    }
                }

                YingtiPrimaryButton(
                    onClick = { onSave(draft, password, rememberPassword) },
                    loading = loading,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("登入") }

                if (status != null) {
                    Text(status, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                }
                if (error != null) {
                    Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }

            SettingsSection("外观") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("深色模式", style = MaterialTheme.typography.bodyLarge)
                    }
                    Switch(checked = darkTheme, onCheckedChange = onDarkThemeChange)
                }
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                Text("主题版式", style = MaterialTheme.typography.titleMedium)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    YingtiPalettes.forEach { p ->
                        val selected = p.key == paletteKey
                        val dotColor = if (darkTheme) p.dark.primary else p.light.primary
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onPaletteChange(p.key) }
                                .padding(horizontal = 4.dp, vertical = 6.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .background(dotColor, CircleShape)
                                    .then(
                                        if (selected) Modifier.background(Color.Transparent)
                                        else Modifier
                                    ),
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                p.name,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
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

                    // 下方分两行优雅呈现参数与复制按钮，彻底解决断行与局促感
                    McpCopyFieldRow(
                        label = "MCP URL",
                        value = draft.mcpUrl,
                        onCopy = { onCopy("MCP URL", draft.mcpUrl) },
                    )
                    val authHeader = "Bearer ${token.trim()}"
                    val maskedAuth = if (token.trim().length > 8) "Bearer ${token.trim().take(4)}••••${token.trim().takeLast(4)}" else "Bearer ••••••••"
                    McpCopyFieldRow(
                        label = "Authorization",
                        value = maskedAuth,
                        onCopy = { onCopy("Authorization", authHeader) },
                    )
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

            // 新增：破坏性操作卡片（微微标红以示警告）
            Surface(
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.22f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.40f)),
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        "破坏性操作",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Text(
                        "清除本机记录的操作日志与使用频率统计。此操作无法撤销，不会影响云端波形，也不会停止设备。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(
                        onClick = {
                            clearConfirmInput = ""
                            showClearDialog = true
                        },
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.65f)),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Outlined.DeleteForever, null)
                        Spacer(Modifier.width(8.dp))
                        Text("清除本机记录")
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun McpCopyFieldRow(
    label: String,
    value: String,
    onCopy: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onCopy),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    value,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            IconButton(
                onClick = onCopy,
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    Icons.Outlined.ContentCopy,
                    contentDescription = "复制 $label",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
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
                Icon(
                    if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                    contentDescription = if (visible) "隐藏" else "显示",
                )
            }
        },
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        singleLine = true,
    )
}
