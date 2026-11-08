package com.yingti.app.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yingti.app.BuildConfig
import com.yingti.app.update.AppUpdater
import com.yingti.app.update.NoteLine
import com.yingti.app.update.UpdateSource
import com.yingti.app.update.UpdateSources
import com.yingti.app.update.UpdateState
import com.yingti.app.update.release
import com.yingti.app.update.releaseNoteLines
import java.util.Locale

@Composable
fun UpdateScreen(updater: AppUpdater, onBack: () -> Unit) {
    val state by updater.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
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
        onBack = onBack,
        onCheck = updater::startCheck,
        onDownload = updater::startDownload,
        onCancel = updater::cancelDownload,
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
    onBack: () -> Unit,
    onCheck: () -> Unit,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onOpenReleasePage: () -> Unit,
    sdkInt: Int = Build.VERSION.SDK_INT,
) {
    val release = state.release
    val showRelease = release != null && when (state) {
        is UpdateState.Available, is UpdateState.Probing, is UpdateState.Downloading,
        is UpdateState.Verifying, is UpdateState.Downloaded -> true
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
                            "自动测速，选最快的下载源交给系统下载",
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
                    is UpdateState.Verifying -> {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text("正在校验安装包…", style = MaterialTheme.typography.bodySmall)
                    }
                    is UpdateState.Downloaded -> {
                        Text(
                            "下载完成，已通过${if (state.release.apk?.sha256 != null) "SHA-256、" else ""}" +
                                "包名、版本与签名校验（来源：${state.source.name}）",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            "请点通知栏里系统的「下载完成」通知安装。安装由系统发起，樱趣不需要任何安装权限。",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.testTag("update-install-hint"),
                        )
                        if (sdkInt < 34) {
                            Text(
                                "Android 13 及以下的部分系统点通知后不会弹出安装界面，这时请用下方「在浏览器中打开 Release 页面」下载安装。",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.testTag("update-legacy-hint"),
                            )
                        }
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
                is UpdateState.Verifying -> state.source
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
                "下载时自动测速，选最快的源交给系统下载管理器；无论来自哪个源，都要通过 SHA-256 与签名校验，不通过会自动删除。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

internal fun formatSize(bytes: Long): String =
    if (bytes >= 1024 * 1024) String.format(Locale.US, "%.1f MB", bytes.toDouble() / (1024 * 1024))
    else String.format(Locale.US, "%d KB", bytes / 1024)
