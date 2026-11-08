package com.yingti.app.ui

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class SettingsPagesTest {
    @get:Rule
    val compose = createComposeRule()

    private fun showSettings(devMode: Boolean, showAdmin: Boolean) {
        compose.setContent {
            MaterialTheme {
                SettingsPages(
                    devMode = devMode,
                    configured = true,
                    lastMessage = "",
                    onRaw = {},
                    onLogout = {},
                    showAdmin = showAdmin,
                    onAdmin = {},
                ) { Text("settings-body") }
            }
        }
    }

    @Test
    fun devModeOffKeepsAdmin() {
        showSettings(devMode = false, showAdmin = true)
        compose.onNodeWithText("管理").assertIsDisplayed()
        compose.onNodeWithText("协议调试").assertDoesNotExist()
    }

    @Test
    fun devModeOnShowsBoth() {
        showSettings(devMode = true, showAdmin = true)
        compose.onNodeWithText("管理").assertIsDisplayed()
        compose.onNodeWithText("协议调试").assertIsDisplayed()
    }

    @Test
    fun nonOwnerNeverSeesAdmin() {
        showSettings(devMode = true, showAdmin = false)
        compose.onNodeWithText("管理").assertDoesNotExist()
        showSettings(devMode = false, showAdmin = false)
        compose.onNodeWithText("管理").assertDoesNotExist()
    }

    @Test
    fun togglingDevModeOffKeepsAdminEntry() {
        var devMode by mutableStateOf(true)
        compose.setContent {
            MaterialTheme {
                SettingsPages(
                    devMode = devMode,
                    configured = true,
                    lastMessage = "",
                    onRaw = {},
                    onLogout = {},
                    showAdmin = true,
                    onAdmin = {},
                ) { Text("settings-body") }
            }
        }
        compose.onNodeWithText("协议调试").assertIsDisplayed()
        compose.runOnIdle { devMode = false }
        compose.onNodeWithText("管理").assertIsDisplayed()
        compose.onNodeWithText("协议调试").assertDoesNotExist()
    }
}
