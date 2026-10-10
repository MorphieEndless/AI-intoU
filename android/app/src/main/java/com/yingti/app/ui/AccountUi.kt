package com.yingti.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.yingti.app.auth.AccountApi
import com.yingti.app.auth.NeedPasswordException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Runs account calls for a page: one busy flag, one error line, and — when the
 * 24 h session has lapsed and no saved password could renew it — a password
 * prompt that retries the interrupted action after logging in again.
 */
@Stable
class AccountActions(private val scope: CoroutineScope) {
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
    var pendingRetry by mutableStateOf<(suspend () -> Unit)?>(null)
        private set

    fun run(action: suspend () -> Unit) {
        scope.launch {
            busy = true
            error = null
            try {
                action()
            } catch (e: CancellationException) {
                throw e
            } catch (e: NeedPasswordException) {
                pendingRetry = action
            } catch (e: Exception) {
                error = e.message ?: "操作失败，请稍后重试"
            } finally {
                busy = false
            }
        }
    }

    fun dismissPrompt() {
        pendingRetry = null
    }

    fun loginAndRetry(api: AccountApi, password: String) {
        val retry = pendingRetry ?: return
        pendingRetry = null
        run {
            api.relogin(password)
            retry()
        }
    }
}

@Composable
fun rememberAccountActions(): AccountActions {
    val scope = rememberCoroutineScope()
    return remember(scope) { AccountActions(scope) }
}

/** Shown while [AccountActions.pendingRetry] is set. */
@Composable
fun SessionPasswordPrompt(actions: AccountActions, api: AccountApi) {
    if (actions.pendingRetry == null) return
    var password by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = actions::dismissPrompt,
        title = { Text("请重新输入密码") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "登录已过期（24 小时有效）。输入「${api.username}」的密码继续；" +
                        "在连接设置里勾选「记住密码」后会自动续期。",
                    style = MaterialTheme.typography.bodySmall,
                )
                SecretField(
                    value = password,
                    onValueChange = { password = it },
                    label = "密码",
                    visible = visible,
                    onToggleVisibility = { visible = !visible },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { actions.loginAndRetry(api, password) }, enabled = password.isNotBlank()) { Text("继续") }
        },
        dismissButton = { TextButton(onClick = actions::dismissPrompt) { Text("取消") } },
    )
}

/** Header with back arrow (and optional refresh) for the 设置 sub-pages. */
@Composable
fun SubPage(
    title: String,
    onBack: () -> Unit,
    onRefresh: (() -> Unit)? = null,
    busy: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack, modifier = Modifier.testTag("subpage-back")) { Icon(Icons.Outlined.ArrowBack, "返回") }
            Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(start = 4.dp).weight(1f))
            if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            if (onRefresh != null) IconButton(onClick = onRefresh, enabled = !busy) { Icon(Icons.Outlined.Refresh, "刷新") }
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            content = {
                content()
                Spacer(Modifier.height(24.dp))
            },
        )
    }
}

@Composable
fun ErrorBanner(message: String?) {
    message ?: return
    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.medium) {
        Text(
            message,
            Modifier.fillMaxWidth().padding(14.dp).testTag("account-error"),
            color = MaterialTheme.colorScheme.onErrorContainer,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
fun NoticeBanner(message: String) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium) {
        Text(
            message,
            Modifier.fillMaxWidth().padding(14.dp),
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/** Small rounded label, e.g. 状态 / owner / 本机. */
@Composable
fun StatusPill(text: String, emphasized: Boolean = false) {
    Surface(
        shape = RoundedCornerShape(50),
        color = if (emphasized) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Text(
            text,
            Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = if (emphasized) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** A value that must be copied now: large monospace text, never masked. */
@Composable
fun OneTimeValue(value: String, tag: String) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(12.dp)) {
        Text(
            value,
            Modifier.fillMaxWidth().padding(12.dp).testTag(tag),
            style = MaterialTheme.typography.titleMedium,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    destructive: Boolean = true,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message, style = MaterialTheme.typography.bodyMedium) },
        confirmButton = {
            TextButton(onClick = { onConfirm(); onDismiss() }, modifier = Modifier.testTag("confirm-action")) {
                Text(confirmLabel, color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
