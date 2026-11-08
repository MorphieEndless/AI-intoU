package com.yingti.app.ui

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import com.yingti.app.update.ReleaseAsset
import com.yingti.app.update.ReleaseInfo
import com.yingti.app.update.UpdateSource
import com.yingti.app.update.UpdateState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class UpdateContentTest {
    @get:Rule
    val compose = createComposeRule()

    private val source = UpdateSource("测试源", "https://mirror.example.com/", proxiesApi = true)
    private val sources = listOf(source, UpdateSource("备用源", "https://backup.example.com/", proxiesApi = true))
    private val apk = ReleaseAsset(
        name = "app-release.apk",
        url = "https://example.com/app-release.apk",
        size = 400_000,
        sha256 = "a".repeat(64),
    )
    private val release = ReleaseInfo(
        tag = "v0.19.0",
        version = "0.19.0",
        title = "v0.19.0",
        notes = "## 变化\n- 修复了若干问题",
        publishedAt = "2026-11-20",
        pageUrl = "https://example.com/releases",
        apk = apk,
    )

    private fun render(state: UpdateState, sdkInt: Int = 34) {
        compose.setContent {
            MaterialTheme {
                UpdateContent(
                    state = state,
                    sources = sources,
                    currentVersion = "0.17.0",
                    onBack = {},
                    onCheck = {},
                    onDownload = {},
                    onCancel = {},
                    onOpenReleasePage = {},
                    sdkInt = sdkInt,
                )
            }
        }
    }

    @Test
    fun availableShowsDownloadButtonAndNotes() {
        render(UpdateState.Available(release))
        compose.onNodeWithText("下载更新").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("修复了若干问题").assertExists()
    }

    @Test
    fun downloadingShowsPercentAndSource() {
        render(UpdateState.Downloading(release, source, 100_000, 400_000, 5_000))
        compose.onAllNodes(hasText("测试源", substring = true)).assertCountEquals(2)
        compose.onAllNodes(hasText("25%", substring = true)).assertCountEquals(1)
    }

    @Test
    fun verifyingShowsProgressText() {
        render(UpdateState.Verifying(release, source))
        compose.onNodeWithText("正在校验安装包", substring = true).assertExists()
    }

    @Test
    fun downloadedGuidesToSystemNotificationWithoutInstallButton() {
        render(UpdateState.Downloaded(release, source, 7L), sdkInt = 34)
        compose.onNodeWithTag("update-install-hint").assertExists()
        compose.onNodeWithText("安装").assertDoesNotExist()
        compose.onNodeWithTag("update-legacy-hint").assertDoesNotExist()
    }

    @Test
    fun downloadedOnAndroid13ShowsBrowserHint() {
        render(UpdateState.Downloaded(release, source, 7L), sdkInt = 33)
        compose.onNodeWithTag("update-legacy-hint").assertExists()
    }

    @Test
    fun upToDateShowsNotice() {
        render(UpdateState.UpToDate(release))
        compose.onNodeWithText("已是最新版本", substring = true).assertExists()
    }
}
