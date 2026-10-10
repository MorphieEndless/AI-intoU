package com.yingti.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yingti.app.auth.AccountApi
import com.yingti.app.auth.CreatedInvite
import com.yingti.app.auth.InviteInfo
import com.yingti.app.auth.UserInfo
import com.yingti.app.auth.shortDate

/** Same limits the server enforces (app/domain/invites.py, identity.py). */
internal object AdminLimits {
    const val MAX_USES = 50
    const val MAX_DAYS = 90
    const val MAX_NOTE = 80
    const val MIN_PASSWORD = 8

    /** Parsed and range-checked, or null if the field is invalid. */
    fun uses(raw: String): Int? = raw.trim().toIntOrNull()?.takeIf { it in 1..MAX_USES }
    fun days(raw: String): Int? = raw.trim().toIntOrNull()?.takeIf { it in 1..MAX_DAYS }

    /** What to send a friend: server address + code, ready to paste into a chat. */
    fun inviteMessage(server: String, code: String): String =
        "樱趣 App 邀请码：$code\n服务器地址：$server\n在 App 设置页选「账号登录」→「有邀请码？注册新账号」。邀请码只能使用一次。"
}

private fun inviteStateLabel(state: String) = when (state) {
    "active" -> "可用"
    "used_up" -> "已用完"
    "expired" -> "已过期"
    "revoked" -> "已撤销"
    else -> state
}

/**
 * Owner-only management (shown when 开发者模式 is on and the account is an
 * owner): invite codes and accounts. Server-side every call re-checks that the
 * session belongs to an active owner.
 */
@Composable
fun AdminScreen(
    api: AccountApi,
    serverUrl: String,
    onBack: () -> Unit,
    onCopy: (String, String) -> Unit,
) {
    val actions = rememberAccountActions()
    var invites by remember { mutableStateOf<List<InviteInfo>?>(null) }
    var users by remember { mutableStateOf<List<UserInfo>?>(null) }
    var created by remember { mutableStateOf<CreatedInvite?>(null) }
    var revokingInvite by remember { mutableStateOf<InviteInfo?>(null) }
    var togglingUser by remember { mutableStateOf<UserInfo?>(null) }
    var resettingUser by remember { mutableStateOf<UserInfo?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }

    fun reload() = actions.run {
        invites = api.invites()
        users = api.users()
    }
    LaunchedEffect(api) { reload() }

    SessionPasswordPrompt(actions, api)

    created?.let { invite ->
        val message = AdminLimits.inviteMessage(serverUrl, invite.code)
        AlertDialog(
            onDismissRequest = {},
            title = { Text("邀请码已生成") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("邀请码只显示这一次，关闭后只能看到前 4 位。", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error)
                    OneTimeValue(invite.code, "admin-invite-code")
                    Text(
                        "可用 ${invite.info.maxUses} 次" + (invite.info.expiresAt?.let { " · ${shortDate(it)} 过期" } ?: ""),
                        style = MaterialTheme.typography.labelSmall,
                    )
                    YingtiPrimaryButton(
                        onClick = { onCopy("邀请信息", message) },
                        modifier = Modifier.fillMaxWidth().testTag("admin-copy-invite-message"),
                    ) { Text("复制邀请信息（含服务器地址）") }
                    OutlinedButton(onClick = { onCopy("邀请码", invite.code) }, modifier = Modifier.fillMaxWidth()) {
                        Text("只复制邀请码")
                    }
                }
            },
            confirmButton = { TextButton(onClick = { created = null }) { Text("完成") } },
        )
    }
    revokingInvite?.let { invite ->
        ConfirmDialog(
            title = "撤销邀请码 ${invite.prefix}…？",
            message = "撤销后这个码不能再注册新账号；已经用它注册的账号不受影响。",
            confirmLabel = "撤销",
            onConfirm = { actions.run { api.revokeInvite(invite.id); invites = api.invites() } },
            onDismiss = { revokingInvite = null },
        )
    }
    togglingUser?.let { user ->
        val disabling = user.isActive
        ConfirmDialog(
            title = if (disabling) "停用「${user.username}」？" else "启用「${user.username}」？",
            message = if (disabling) "会立即急停并断开 TA 的手机，TA 的所有 token 和登录都会失效，直到重新启用。数据会保留。"
            else "TA 可以重新登录，之前未撤销的 token 也会恢复可用。",
            confirmLabel = if (disabling) "停用" else "启用",
            destructive = disabling,
            onConfirm = {
                actions.run {
                    api.setUserActive(user.id, !disabling)
                    users = api.users()
                    notice = if (disabling) "已停用 ${user.username}" else "已启用 ${user.username}"
                }
            },
            onDismiss = { togglingUser = null },
        )
    }
    resettingUser?.let { user ->
        ResetPasswordDialog(
            username = user.username,
            busy = actions.busy,
            onDismiss = { resettingUser = null },
            onReset = { password ->
                actions.run {
                    api.resetUserPassword(user.id, password)
                    resettingUser = null
                    notice = "已重置 ${user.username} 的密码，请私下把新密码告诉 TA"
                }
            },
        )
    }

    SubPage(title = "管理", onBack = onBack, onRefresh = ::reload, busy = actions.busy) {
        ErrorBanner(actions.error)
        notice?.let { NoticeBanner(it) }

        InviteForm(busy = actions.busy) { uses, days, note ->
            actions.run {
                created = api.createInvite(uses, days, note)
                invites = api.invites()
            }
        }

        SettingsSection("邀请码") {
            val list = invites
            when {
                list == null -> Text(if (actions.busy) "加载中…" else "还没加载，点右上角刷新。", style = MaterialTheme.typography.bodySmall)
                list.isEmpty() -> Text("还没有生成过邀请码。", style = MaterialTheme.typography.bodySmall)
                else -> list.forEach { invite -> InviteRow(invite) { revokingInvite = invite } }
            }
        }

        SettingsSection("用户") {
            val list = users
            when {
                list == null -> Text(if (actions.busy) "加载中…" else "还没加载，点右上角刷新。", style = MaterialTheme.typography.bodySmall)
                else -> list.forEach { user ->
                    UserRow(
                        user = user,
                        isSelf = user.username == api.username,
                        onToggle = { togglingUser = user },
                        onReset = { resettingUser = user },
                    )
                }
            }
        }
    }
}

@Composable
private fun InviteForm(busy: Boolean, onCreate: (Int, Int, String) -> Unit) {
    var uses by remember { mutableStateOf("1") }
    var days by remember { mutableStateOf("7") }
    var note by remember { mutableStateOf("") }
    val usesValue = AdminLimits.uses(uses)
    val daysValue = AdminLimits.days(days)
    SettingsSection("生成邀请码") {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                value = uses,
                onValueChange = { uses = it.filter(Char::isDigit).take(2) },
                modifier = Modifier.weight(1f).testTag("admin-invite-uses"),
                label = { Text("可用次数") },
                singleLine = true,
                isError = usesValue == null,
                supportingText = { Text("1–${AdminLimits.MAX_USES}") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            OutlinedTextField(
                value = days,
                onValueChange = { days = it.filter(Char::isDigit).take(2) },
                modifier = Modifier.weight(1f).testTag("admin-invite-days"),
                label = { Text("有效天数") },
                singleLine = true,
                isError = daysValue == null,
                supportingText = { Text("1–${AdminLimits.MAX_DAYS}") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        }
        OutlinedTextField(
            value = note,
            onValueChange = { note = it.take(AdminLimits.MAX_NOTE) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("备注（可选）") },
            placeholder = { Text("例如：给群里的小明") },
            singleLine = true,
        )
        YingtiPrimaryButton(
            onClick = { if (usesValue != null && daysValue != null) onCreate(usesValue, daysValue, note) },
            modifier = Modifier.fillMaxWidth().testTag("admin-invite-create"),
            enabled = !busy && usesValue != null && daysValue != null,
        ) { Text("生成邀请码") }
    }
}

@Composable
private fun InviteRow(invite: InviteInfo, onRevoke: () -> Unit) {
    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("${invite.prefix}-…", style = MaterialTheme.typography.bodyLarge, fontFamily = FontFamily.Monospace)
                    StatusPill(inviteStateLabel(invite.state), emphasized = invite.state == "active")
                }
                if (invite.note.isNotBlank()) {
                    Text(invite.note, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(
                    buildString {
                        append("已用 ${invite.usedCount}/${invite.maxUses}")
                        append(" · ")
                        append(invite.expiresAt?.let { "${shortDate(it)} 过期" } ?: "永不过期")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (invite.usedBy.isNotEmpty()) {
                    Text(
                        "使用者：" + invite.usedBy.joinToString("、"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (invite.state == "active") {
                TextButton(onClick = onRevoke) { Text("撤销", color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

@Composable
private fun UserRow(user: UserInfo, isSelf: Boolean, onToggle: () -> Unit, onReset: () -> Unit) {
    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    user.username,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (isSelf) StatusPill("我", emphasized = true)
                if (user.isAdmin) StatusPill("owner")
                if (!user.isActive) StatusPill("已停用")
                StatusPill(if (user.phoneOnline) "手机在线" else "手机离线", emphasized = user.phoneOnline)
            }
            Text(
                buildString {
                    append("注册 ").append(shortDate(user.createdAt))
                    append(" · AI token ${user.activeTokens["agent"] ?: 0}")
                    append(" · 手机 token ${user.activeTokens["phone"] ?: 0}")
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!isSelf) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onReset) { Text("重置密码") }
                    OutlinedButton(onClick = onToggle) {
                        Text(
                            if (user.isActive) "停用" else "启用",
                            color = if (user.isActive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ResetPasswordDialog(username: String, busy: Boolean, onDismiss: () -> Unit, onReset: (String) -> Unit) {
    var password by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    val valid = password.length >= AdminLimits.MIN_PASSWORD
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("重置「$username」的密码") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "旧密码立即失效。已签发的 token 和已登录的会话（最长 24 小时）不受影响；想让 TA 立刻下线，请用「停用」。",
                    style = MaterialTheme.typography.bodySmall,
                )
                SecretField(
                    value = password,
                    onValueChange = { password = it },
                    label = "新密码（至少 ${AdminLimits.MIN_PASSWORD} 位）",
                    visible = visible,
                    onToggleVisibility = { visible = !visible },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onReset(password) }, enabled = valid && !busy) { Text("重置") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
