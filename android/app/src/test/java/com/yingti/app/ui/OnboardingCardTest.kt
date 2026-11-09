package com.yingti.app.ui

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.yingti.app.auth.ConnectionConfig
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class OnboardingCardTest {
    @get:Rule
    val compose = createComposeRule()

    private fun showCard(replayIndex: Int?, onReplayNext: () -> Unit = {}) {
        compose.setContent {
            MaterialTheme {
                OnboardingCard(
                    step = OnboardingStep.DONE,
                    toyConnected = true,
                    onOpenToy = {},
                    onOpenAiAccess = {},
                    onDismiss = {},
                    replayIndex = replayIndex,
                    onReplayNext = onReplayNext,
                )
            }
        }
    }

    @Test
    fun replayFirstStepShowsNextButton() {
        var next = 0
        showCard(replayIndex = 0, onReplayNext = { next++ })
        compose.onNodeWithTag("onboarding-replay-next").assertExists()
        compose.onNodeWithText("下一步").performClick()
        assertEquals(1, next)
    }

    @Test
    fun replayLastStepShowsDoneButton() {
        showCard(replayIndex = 2)
        compose.onNodeWithTag("onboarding-replay-next").assertExists()
        compose.onNodeWithText("完成").assertExists()
    }

    @Test
    fun normalCardHasNoReplayButton() {
        showCard(replayIndex = null)
        compose.onNodeWithTag("onboarding-replay-next").assertDoesNotExist()
    }

    @Test
    fun settingsReplayEntryInvokesCallbackOnce() {
        var replays = 0
        compose.setContent {
            MaterialTheme {
                ConnectionSettingsScreen(
                    initialConfig = ConnectionConfig("https://example.com", token = "t"),
                    loading = false,
                    status = null,
                    error = null,
                    canCancel = false,
                    darkTheme = false,
                    paletteKey = "wine",
                    devMode = true,
                    onDarkThemeChange = {},
                    onPaletteChange = {},
                    onDevModeChange = {},
                    onCancel = {},
                    onSave = { _, _, _ -> },
                    onCopy = { _, _ -> },
                    onReplayOnboarding = { replays++ },
                )
            }
        }
        compose.onNodeWithTag("replay-onboarding").performScrollTo().performClick()
        assertEquals(1, replays)
    }
}
