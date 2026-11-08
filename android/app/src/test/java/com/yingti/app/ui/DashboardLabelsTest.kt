package com.yingti.app.ui

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.yingti.app.BridgeState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class DashboardLabelsTest {
    @get:Rule
    val compose = createComposeRule()

    private fun showDashboard() {
        compose.setContent {
            MaterialTheme {
                DashboardScreen(
                    state = BridgeState(),
                    server = "",
                    darkTheme = false,
                    devMode = false,
                    onToggleTheme = {},
                    onScan = {},
                    onVibrate = {},
                    onStop = {},
                    onSettings = {},
                    onLogout = {},
                )
            }
        }
    }

    private fun assertNoLegacyLabels() {
        compose.onAllNodes(hasText("节奏 0", substring = true)).assertCountEquals(0)
        compose.onAllNodes(hasText("节奏 B", substring = true)).assertCountEquals(0)
    }

    @Test
    fun toyPresetsUseBodyFeelNames() {
        showDashboard()
        compose.onNodeWithText("玩具预设").performScrollTo()
        listOf("颤动", "深吸", "先短后长").forEach { label ->
            compose.onNodeWithText(label).assertExists()
        }
        assertNoLegacyLabels()
    }

    @Test
    fun freePanelUsesRegularSuctionName() {
        showDashboard()
        compose.onNodeWithText("自由组合").performScrollTo().performClick()
        compose.onNodeWithText("规律吮吸").assertExists()
        assertNoLegacyLabels()
    }
}
