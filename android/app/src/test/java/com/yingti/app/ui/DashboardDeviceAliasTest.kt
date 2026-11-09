package com.yingti.app.ui

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import com.yingti.app.BridgeState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class DashboardDeviceAliasTest {
    @get:Rule
    val compose = createComposeRule()

    private fun showDashboard(
        state: BridgeState = BridgeState(),
        alias: String? = null,
        onRenameDevice: (String?) -> Unit = {},
    ) {
        compose.setContent {
            MaterialTheme {
                DashboardScreen(
                    state = state,
                    server = "",
                    darkTheme = false,
                    devMode = false,
                    onToggleTheme = {},
                    onScan = {},
                    onVibrate = {},
                    onStop = {},
                    onSettings = {},
                    onLogout = {},
                    deviceAlias = alias,
                    onRenameDevice = onRenameDevice,
                )
            }
        }
    }

    @Test
    fun defaultShowsBleFallbackName() {
        showDashboard()
        compose.onNodeWithTag("device-name").assert(hasText("SX589B"))
    }

    @Test
    fun aliasOverridesReportedDeviceName() {
        showDashboard(state = BridgeState(deviceName = "SX589B-1234"), alias = "卧室")
        compose.onNodeWithTag("device-name").assert(hasText("卧室"))
    }

    @Test
    fun savingTrimmedAliasReportsNormalizedValue() {
        var received: String? = "unset"
        showDashboard(
            state = BridgeState(deviceName = "SX589B-1234"),
            alias = "卧室",
            onRenameDevice = { received = it },
        )
        compose.onNodeWithTag("device-name").performClick()
        compose.onNodeWithTag("device-alias-input").performTextReplacement("  新名字  ")
        compose.onNodeWithText("保存").performClick()
        assertEquals("新名字", received)
    }

    @Test
    fun savingEmptyAliasReportsNull() {
        var received: String? = "unset"
        showDashboard(
            state = BridgeState(deviceName = "SX589B-1234"),
            alias = "卧室",
            onRenameDevice = { received = it },
        )
        compose.onNodeWithTag("device-name").performClick()
        compose.onNodeWithTag("device-alias-input").performTextReplacement("")
        compose.onNodeWithText("保存").performClick()
        assertNull(received)
    }
}
