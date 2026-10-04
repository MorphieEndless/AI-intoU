package com.yingti.app.ui

import android.app.Application
import androidx.compose.material3.MaterialTheme
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
import org.junit.Assert.assertEquals
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

    private val commands = mutableListOf<Pair<Double, Int>>()
    private val suctionSlider = SemanticsMatcher("suction slider with range 0..5") {
        it.config.getOrNull(SemanticsProperties.ProgressBarRangeInfo)?.range == 0f..5f
    }

    private fun showDashboard(initialLevel: Int = 0, mode: Int = 5) {
        compose.setContent {
            MaterialTheme {
                DashboardScreen(
                    state = BridgeState(suctionIntensity = initialLevel, suctionMode = mode),
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

    private fun tapLevel(level: Int) {
        compose.onNode(suctionSlider).performTouchInput {
            click(Offset((width * level / 5f).coerceIn(1f, width - 1f), centerY))
        }
    }

    @Test
    fun tapCommitsEachNewLevelExactlyOnce() {
        showDashboard()
        for (level in 1..5) {
            tapLevel(level)
            compose.runOnIdle {
                assertEquals((1..level).map { it / 5.0 to 5 }, commands)
            }
        }
    }

    @Test
    fun tapCanLowerLevelWithoutDragging() {
        showDashboard(initialLevel = 5)
        tapLevel(2)
        compose.runOnIdle { assertEquals(listOf(0.4 to 5), commands) }
    }

    @Test
    fun tapZeroStopsSuction() {
        showDashboard(initialLevel = 4)
        tapLevel(0)
        compose.runOnIdle { assertEquals(listOf(0.0 to 5), commands) }
    }

    @Test
    fun tapPreservesSelectedMode() {
        showDashboard(mode = 1)
        tapLevel(3)
        compose.runOnIdle { assertEquals(listOf(0.6 to 1), commands) }
    }

    @Test
    fun dragStillCommitsOnlyAtRelease() {
        showDashboard()
        compose.onNode(suctionSlider).performTouchInput {
            swipe(Offset(1f, centerY), Offset(width * 0.8f, centerY), durationMillis = 300)
        }
        compose.runOnIdle { assertEquals(listOf(0.8 to 5), commands) }
    }
}
