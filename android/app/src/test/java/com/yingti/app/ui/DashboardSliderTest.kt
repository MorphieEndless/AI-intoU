package com.yingti.app.ui

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.assertRangeInfoEquals
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import com.yingti.app.BridgeState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class DashboardSliderTest {
    @get:Rule
    val compose = createComposeRule()

    private val bridge = mutableStateOf(BridgeState(bleStatus = "已连接", serviceRunning = true))

    private val commands = mutableListOf<Pair<Double, Int>>()
    private val suctionSlider = SemanticsMatcher("suction slider with range 0..5") {
        it.config.getOrNull(SemanticsProperties.ProgressBarRangeInfo)?.range == 0f..5f
    }

    private fun showDashboard(initialLevel: Int = 0, mode: Int = 5) {
        bridge.value = bridge.value.copy(suctionIntensity = initialLevel, suctionMode = mode)
        compose.setContent {
            MaterialTheme {
                DashboardScreen(
                    state = bridge.value,
                    server = "",
                    darkTheme = false,
                    devMode = false,
                    onToggleTheme = {},
                    onScan = {},
                    onVibrate = {},
                    onStop = {},
                    onSettings = {},
                    onLogout = {},
                    onSuction = { intensity, selectedMode -> commands += intensity to selectedMode },
                )
            }
        }
        compose.onNodeWithText("自由组合").performScrollTo().performClick()
        compose.onNode(suctionSlider).performScrollTo()
    }

    private fun assertCommands(expected: List<Pair<Double, Int>>) {
        check(commands == expected) { "Expected suction commands $expected, received $commands" }
    }

    private fun tapLevel(level: Int) {
        compose.onNode(suctionSlider).performTouchInput {
            // Keep pointer injection inside the track, including endpoint hit areas.
            click(Offset(width * (0.05f + 0.9f * level / 5f), centerY))
        }
    }

    @Test
    fun tapCommitsEachNewLevelExactlyOnce() {
        showDashboard()
        for (level in 1..5) {
            tapLevel(level)
            compose.runOnIdle {
                assertCommands((1..level).map { it / 5.0 to 5 })
            }
        }
    }

    @Test
    fun tapCanLowerLevelWithoutDragging() {
        showDashboard(initialLevel = 5)
        tapLevel(2)
        compose.runOnIdle { assertCommands(listOf(0.4 to 5)) }
    }

    @Test
    fun tapZeroStopsSuction() {
        showDashboard(initialLevel = 4)
        tapLevel(0)
        compose.runOnIdle { assertCommands(listOf(0.0 to 5)) }
    }

    @Test
    fun tapPreservesSelectedMode() {
        showDashboard(mode = 1)
        tapLevel(3)
        compose.runOnIdle { assertCommands(listOf(0.6 to 1)) }
    }

    @Test
    fun dragStillCommitsOnlyAtRelease() {
        showDashboard()
        compose.onNode(suctionSlider).performTouchInput {
            swipe(Offset(width * 0.05f, centerY), Offset(width * 0.8f, centerY), durationMillis = 300)
        }
        compose.runOnIdle { assertCommands(listOf(0.8 to 5)) }
    }

    private fun echo(level: Int, mode: Int = 5) {
        compose.runOnIdle {
            bridge.value = bridge.value.copy(suctionIntensity = level, suctionMode = mode)
        }
    }

    private fun assertDisplayedLevel(level: Int) {
        compose.onNode(suctionSlider).assertRangeInfoEquals(ProgressBarRangeInfo(level.toFloat(), 0f..5f, 4))
    }

    @Test
    fun firstTapSurvivesDelayedOlderEcho() {
        showDashboard()
        tapLevel(3)
        assertDisplayedLevel(3)
        echo(1) // A previous command finishes after the new selection.
        assertDisplayedLevel(3)
        compose.runOnIdle { assertCommands(listOf(0.6 to 5)) }
        echo(3)
        assertDisplayedLevel(3)
    }

    @Test
    fun latestTapSurvivesPreviousTapEcho() {
        showDashboard()
        tapLevel(1)
        tapLevel(4)
        echo(1)
        assertDisplayedLevel(4)
        echo(4)
        assertDisplayedLevel(4)
        compose.runOnIdle { assertCommands(listOf(0.2 to 5, 0.8 to 5)) }
    }

    @Test
    fun remoteUpdatesResumeAfterMatchingEcho() {
        showDashboard()
        tapLevel(3)
        echo(3)
        echo(2)
        assertDisplayedLevel(2)
    }

    @Test
    fun emergencyStopOverridesPendingTap() {
        showDashboard()
        tapLevel(4)
        compose.runOnIdle {
            bridge.value = bridge.value.copy(suctionIntensity = 0, lastMessage = "全部停止")
        }
        assertDisplayedLevel(0)
    }

    @Test
    fun disconnectClearsPendingTap() {
        showDashboard()
        tapLevel(4)
        compose.runOnIdle {
            bridge.value = bridge.value.copy(suctionIntensity = 0, bleStatus = "已断开")
        }
        assertDisplayedLevel(0)
    }

}
