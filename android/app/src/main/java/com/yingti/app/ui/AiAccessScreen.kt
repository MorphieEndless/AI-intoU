package com.yingti.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yingti.app.auth.AccountApi
import com.yingti.app.auth.AiPlatform
import com.yingti.app.auth.ConnectionConfig
import com.yingti.app.auth.CreatedToken
import com.yingti.app.auth.TokenInfo
import com.yingti.app.auth.shortDate

/**
 * AI 接入: one agent token per AI client. A new token is shown exactly once,
 * together with everything the client needs (MCP URL, header, full JSON).
 *
 * [api] is null in TOKEN mode — managing tokens needs an account session.
 * [welcome] is set right after invite registration (onboarding step 3).
 */
@Composable
fun AiAccessScreen(
    api: AccountApi?,
    config: ConnectionConfig,
    phoneTokenId: String?,
    welcome: Boolean,
    onBack: () -> Unit,
    onCopy: (String, String) -> Unit,
    onHasAiAccess: () -> Unit,
) {
    val actions = rememberAccountActions()
    var tokens by remember { mutableStateOf<List<TokenInfo>?>(null) }
    var created by remember { mutableStateOf<Pair<AiPlatform, CreatedToken>?>(null) }
    var showCreate by remember { mutableStateOf(false) }
    var revoking by remember { mutableStateOf<TokenInfo?>(null) }
    val mcpUrl = remember(config) { runCatching { config.mcpUrl }.getOrNull() }

    fun reload() {
        val account = api ?: return
        actions.run {
            val list = account.tokens()
            tokens = list
            if (list.any { it.kind == "agent" && it.isActive }) onHasAiAccess()
        }
    }
    LaunchedEffect(api) { reload() }

    if (api != null) SessionPasswordPrompt(actions, api)

    created?.let { (platform, token) ->
        CreatedTokenDialog(platform, token, config, onCopy) { created = null }
    }
    if (showCreate && api != null) {
        NewAiAccessDialog(
            busy = actions.busy,
            onDismiss = { showCreate = false },
            onCreate = { platform, name ->
                actions.run {
                    val result = api.createToken(name, "agent")
                    showCreate = false
                    created = platform to result
                    onHasAiAccess()
                    tokens = api.tokens()
                }
            },
        )
    }
    revoking?.let { token ->
        ConfirmDialog(
            title = "撤销「${token.name.ifBlank { token.prefix }}」？",
            message = if (token.kind == "agent") "使用这个 token 的 AI 客户端会立刻失去连接，之后需要重新生成。"
            else "使用这个 token 的手机会立刻断开连接，需要重新登录。",
            confirmLabel = "撤销",
            onConfirm = { actions.run { api?.revokeToken(token.id); tokens = api?.tokens() } },
            onDismiss = { revoking = null },
        )
    }

    SubPage(
        title = "AI 接入",
        onBack = onBack,
        onRefresh = if (api != null) ::reload else null,
        busy = actions.busy,
    ) {
        if (welcome) {
            NoticeBanner("注册完成，手机已连上服务器！最后一步：为你的 AI 客户端生成一个 token，把配置复制过去。")
        }
        ErrorBanner(actions.error)

        if (api == null) {
            NoticeBanner("当前用的是手机 token 登录，无法在 App 里管理 AI 接入。请在连接设置里改用账号登录，或让服务器管理员用命令行为你签发 AI token。")
            mcpUrl?.let { url ->
                SettingsSection("MCP 地址") {
                    McpCopyFieldRow(label = "MCP URL", value = url, onCopy = { onCopy("MCP URL", url) })
                }
            }
            return@SubPage
        }

        SettingsSection("怎么用") {
            Text(
                "每个 AI 客户端（RikkaHub、Cherry Studio、Claude Desktop…）各生成一个 token。" +
                    "token 只在创建时显示一次；丢了就撤销，再生成一个新的。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            YingtiPrimaryButton(
                onClick = { showCreate = true },
                modifier = Modifier.fillMaxWidth().testTag("ai-access-new"),
                enabled = !actions.busy,
            ) { Text("新建 AI 接入") }
            mcpUrl?.let { url ->
                McpCopyFieldRow(label = "MCP URL", value = url, onCopy = { onCopy("MCP URL", url) })
            }
        }

        val list = tokens
        val agents = list.orEmpty().filter { it.kind == "agent" && it.isActive }
        val phones = list.orEmpty().filter { it.kind == "phone" && it.isActive }
        val inactive = list.orEmpty().count { !it.isActive }

        SettingsSection("AI 客户端") {
            when {
                list == null && actions.busy -> Text("加载中…", style = MaterialTheme.typography.bodySmall)
                list == null -> Text("还没加载，点右上角刷新。", style = MaterialTheme.typography.bodySmall)
                agents.isEmpty() -> Text(
                    "还没有 AI 接入。点上面的「新建 AI 接入」，选你用的 AI 客户端，把生成的配置复制进去即可。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> agents.forEach { token -> TokenRow(token, local = false, onRevoke = { revoking = token }) }
            }
        }

        if (phones.isNotEmpty()) {
            SettingsSection("手机") {
                // This phone's own token is not revocable here: 退出连接 does that cleanly.
                phones.forEach { token ->
                    val local = token.id == phoneTokenId
                    TokenRow(token, local = local, onRevoke = if (local) null else ({ revoking = token }))
                }
            }
        }
        if (inactive > 0) {
            Text(
                "另有 $inactive 个已撤销或过期的 token 未显示。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TokenRow(token: TokenInfo, local: Boolean, onRevoke: (() -> Unit)?) {
    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        token.name.ifBlank { "未命名" },
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (local) StatusPill("本机", emphasized = true)
                }
                Text(
                    "${token.prefix}…",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.secondary,
                )
                Text(
                    buildString {
                        append("创建 ").append(shortDate(token.createdAt))
                        append(" · ")
                        append(token.lastUsedAt?.let { "最近使用 ${shortDate(it)}" } ?: "尚未使用")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (onRevoke != null) TextButton(onClick = onRevoke) { Text("撤销", color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun NewAiAccessDialog(
    busy: Boolean,
    onDismiss: () -> Unit,
    onCreate: (AiPlatform, String) -> Unit,
) {
    var platform by remember { mutableStateOf(AiPlatform.RIKKAHUB) }
    var name by remember { mutableStateOf(AiPlatform.RIKKAHUB.defaultName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建 AI 接入") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("用在哪个 AI 客户端？", style = MaterialTheme.typography.labelLarge)
                // Two rows of two: chips stay readable on narrow phones.
                AiPlatform.entries.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { option ->
                            FilterChip(
                                selected = platform == option,
                                onClick = {
                                    // Keep a name the person typed; follow the preset otherwise.
                                    if (name.isBlank() || AiPlatform.entries.any { it.defaultName == name }) name = option.defaultName
                                    platform = option
                                },
                                label = { Text(option.label) },
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(64) },
                    modifier = Modifier.fillMaxWidth().testTag("ai-access-name"),
                    label = { Text("名字") },
                    placeholder = { Text("例如：我的平板 RikkaHub") },
                    singleLine = true,
                    supportingText = { Text("只用来区分，撤销时认得出就行") },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onCreate(platform, name.trim()) },
                enabled = !busy && name.isNotBlank(),
                modifier = Modifier.testTag("ai-access-create"),
            ) { Text("生成") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun CreatedTokenDialog(
    platform: AiPlatform,
    created: CreatedToken,
    config: ConnectionConfig,
    onCopy: (String, String) -> Unit,
    onDone: () -> Unit,
) {
    val json = remember(created.token) { runCatching { platform.configJson(config, created.token) }.getOrNull() }
    val url = remember(config) { runCatching { config.mcpUrl }.getOrNull() }
    val header = "Bearer ${created.token}"
    AlertDialog(
        // Only the explicit button closes it: the token can't be shown again.
        onDismissRequest = {},
        title = { Text("「${created.info.name}」已生成") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.medium) {
                    Text(
                        "token 只显示这一次，关闭后无法再查看。现在就复制到 AI 客户端里。",
                        Modifier.fillMaxWidth().padding(12.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text(platform.hint, style = MaterialTheme.typography.bodySmall)
                if (json != null) {
                    YingtiPrimaryButton(
                        onClick = { onCopy("${platform.label} 配置", json) },
                        modifier = Modifier.fillMaxWidth().testTag("ai-access-copy-json"),
                    ) { Text("复制完整 JSON") }
                }
                url?.let { McpCopyFieldRow(label = "MCP URL", value = it, onCopy = { onCopy("MCP URL", it) }) }
                McpCopyFieldRow(label = "Authorization", value = header, onCopy = { onCopy("Authorization", header) })
                if (json != null) {
                    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(12.dp)) {
                        Text(
                            json,
                            Modifier.fillMaxWidth().heightIn(max = 160.dp)
                                .verticalScroll(rememberScrollState()).padding(12.dp),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDone, modifier = Modifier.testTag("ai-access-done")) { Text("我已复制，完成") }
        },
    )
}
