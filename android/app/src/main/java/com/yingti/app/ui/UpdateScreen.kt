package com.yingti.app.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yingti.app.BuildConfig
import com.yingti.app.update.ApkVerifier
import com.yingti.app.update.AppUpdater
import com.yingti.app.update.NoteLine
import com.yingti.app.update.UpdateInstaller
import com.yingti.app.update.UpdateSource
import com.yingti.app.update.UpdateSources
import com.yingti.app.update.UpdateState
import com.yingti.app.update.release
import com.yingti.app.update.releaseNoteLines
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun UpdateScreen(updater: AppUpdater, onBack: () -> Unit) {
    val state by updater.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var canInstall by remember { mutableStateOf(UpdateInstaller.canInstall(context)) }
    var installError by remember { mutableStateOf<String?>(null) }

    // 用户可能刚从系统「允许安装未知应用」页面回来——每次 resume 都重新读一次授权状态。
    LifecycleResumeEffect(Unit) {
        canInstall = UpdateInstaller.canInstall(context)
        onPauseOrDispose { }
    }
    LaunchedEffect(Unit) {
        val s = updater.state.value
        if (s is UpdateState.Idle || s is UpdateState.UpToDate || (s is UpdateState.Failed && s.release == null)) {
            updater.startCheck()
        }
    }

    UpdateContent(
        state = state,
        sources = updater.sources,
        currentVersion = BuildConfig.VERSION_NAME,
        canInstall = canInstall,
        installError = installError,
        onBack = onBack,
        onCheck = { installError = null; updater.startCheck() },
        onDownload = { installError = null; updater.startDownload() },
        onCancel = updater::cancelDownload,
        onInstall = { file ->
            if (!UpdateInstaller.canInstall(context)) {
                UpdateInstaller.openInstallPermissionSettings(context)
            } else {
                scope.launch {
                    val problem = withContext(Dispatchers.IO) {
                        val installed = ApkVerifier.installedFacts(context)
                        ApkVerifier.problem(
                            archive = ApkVerifier.archiveFacts(context, file),
                            expectedPackage = context.packageName,
                            installedVersionCode = installed?.versionCode ?: 0L,
                            installedCerts = installed?.certSha256,
                        )
                    }
                    if (problem != null) installError = problem else UpdateInstaller.install(context, file)
                }
            }
        },
        onOpenReleasePage = {
            val url = state.release?.pageUrl?.takeIf { it.isNotBlank() } ?: UpdateSources.RELEASES_PAGE
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(context, "没有可打开网页的浏览器，请安装浏览器后重试", Toast.LENGTH_LONG).show()
            }
        },
    )
}

@Composable
internal fun UpdateContent(
    state: UpdateState,
    sources: List<UpdateSource>,
    currentVersion: String,
    canInstall: Boolean,
    installError: String?,
    onBack: () -> Unit,
    onCheck: () -> Unit,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onInstall: (File) -> Unit,
    onOpenReleasePage: () -> Unit,
) {
    val release = state.release
    val showRelease = release != null && when (state) {
        is UpdateState.Available, is UpdateState.Probing,
        is UpdateState.Downloading, is UpdateState.Downloaded -> true
        is UpdateState.Failed -> true
        else -> false
    }
    SubPage(title = "检查更新", onBack = onBack, onRefresh = onCheck, busy = state is UpdateState.Checking) {
        SettingsSection("当前版本") {
            Text("AI-intoU · 樱趣 $currentVersion", style = MaterialTheme.typography.bodyMedium)
        }

        when (state) {
            is UpdateState.Checking -> Text(
                "正在检查 GitHub 上的最新版本…",
                style = MaterialTheme.typography.bodySmall,
            )
            is UpdateState.UpToDate -> if (state.latest == null) {
                NoticeBanner("GitHub 上还没有发布版本")
            } else {
                NoticeBanner("已是最新版本（${state.latest.tag}）")
            }
            is UpdateState.Failed -> ErrorBanner(state.message)
            else -> {}
        }

        if (showRelease && release != null) {
            SettingsSection("发现新版本 ${release.tag}") {
                Text(
                    "发布于 ${release.publishedAt} · ${formatSize(release.apk?.size ?: 0L)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
                when (state) {
                    is UpdateState.Available, is UpdateState.Failed -> {
                        YingtiPrimaryButton(
                            onClick = onDownload,
                            modifier = Modifier.fillMaxWidth().testTag("update-download"),
                        ) { Text("下载更新") }
                        Text(
                            "自动测速，从最快的下载源拉取",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary,
                        )
                    }
                    is UpdateState.Probing -> {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text("正在测速，选择最快的下载源…", style = MaterialTheme.typography.bodySmall)
                    }
                    is UpdateState.Downloading -> {
                        val fraction = if (state.total > 0) {
                            (state.downloaded.toFloat() / state.total).coerceIn(0f, 1f)
                        } else 0f
                        LinearProgressIndicator(
                            progress = { fraction },
                            modifier = Modifier.fillMaxWidth().testTag("update-progress"),
                        )
                        val percent = if (state.total > 0) state.downloaded * 100 / state.total else 0L
                        Text(
                            "${state.source.name} · ${formatSize(state.downloaded)} / ${formatSize(state.total)}" +
                                " · ${formatSize(state.bytesPerSecond)}/s · $percent%",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        TextButton(onClick = onCancel) { Text("取消") }
                    }
                    is UpdateState.Downloaded -> {
                        Text(
                            if (state.release.apk?.sha256 != null) "下载完成，已通过 SHA-256 校验（来源：${state.source.name}）"
                            else "下载完成（来源：${state.source.name}）；这个版本未提供 SHA-256，安装前仍会校验包名、版本与签名",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (canInstall) {
                            YingtiPrimaryButton(
                                onClick = { onInstall(state.file) },
                                modifier = Modifier.fillMaxWidth().testTag("update-install"),
                            ) { Text("安装") }
                        } else {
                            Text(
                                "安装需要允许樱趣「安装未知应用」。这项授权只在你点安装时使用，可随时在系统设置里关闭。",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            YingtiPrimaryButton(
                                onClick = { onInstall(state.file) },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("去授权并安装") }
                        }
                        ErrorBanner(installError)
                    }
                    else -> {}
                }
                OutlinedButton(onClick = onOpenReleasePage, modifier = Modifier.fillMaxWidth()) {
                    Text("在浏览器中打开 Release 页面")
                }
            }
        }

        if (release != null && release.notes.isNotBlank()) {
            SettingsSection("更新说明") {
                releaseNoteLines(release.notes).forEach { line ->
                    when (line) {
                        is NoteLine.Heading -> Text(line.text, style = MaterialTheme.typography.titleSmall)
                        is NoteLine.Bullet -> Row(verticalAlignment = Alignment.Top) {
                            Text("•", style = MaterialTheme.typography.bodySmall)
                            Spacer(Modifier.width(6.dp))
                            Text(line.text, style = MaterialTheme.typography.bodySmall)
                        }
                        is NoteLine.Paragraph -> Text(line.text, style = MaterialTheme.typography.bodySmall)
                        NoteLine.Divider -> HorizontalDivider()
                    }
                }
            }
        }

        SettingsSection("下载源") {
            val inUse = when (state) {
                is UpdateState.Downloading -> state.source
                is UpdateState.Downloaded -> state.source
                else -> null
            }
            sources.forEach { source ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        source.name,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    if (source == inUse) StatusPill("使用中", emphasized = true)
                }
            }
            Text(
                "下载时自动测速，选最快的源；无论来自哪个源，都要通过 SHA-256 与签名校验才会安装。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

internal fun formatSize(bytes: Long): String =
    if (bytes >= 1024 * 1024) String.format(Locale.US, "%.1f MB", bytes.toDouble() / (1024 * 1024))
    else String.format(Locale.US, "%d KB", bytes / 1024)
