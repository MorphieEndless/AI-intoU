package com.yingti.app.ui

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.yingti.app.history.ActivityHistory
import com.yingti.app.history.OperationEvent
import com.yingti.app.history.OperationSource
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class ActivityScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private fun eventHistory() =
        ActivityHistory().add(OperationEvent("e1", 1L, OperationSource.AI, "自定义波形"))

    @Test
    fun cancelingClearDialogDoesNotInvokeCallback() {
        var cleared = 0
        compose.setContent {
            MaterialTheme { ActivityScreen(eventHistory(), onClearEvents = { cleared++ }) }
        }
        compose.onNodeWithTag("log-clear").performClick()
        compose.onNodeWithText("取消").performClick()
        assertEquals(0, cleared)
    }

    @Test
    fun confirmingClearInvokesCallbackOnce() {
        var cleared = 0
        compose.setContent {
            MaterialTheme { ActivityScreen(eventHistory(), onClearEvents = { cleared++ }) }
        }
        compose.onNodeWithTag("log-clear").performClick()
        compose.onNodeWithTag("log-clear-confirm").performClick()
        assertEquals(1, cleared)
    }

    @Test
    fun heatmapTitleIsStillShown() {
        compose.setContent { MaterialTheme { ActivityScreen(ActivityHistory()) } }
        compose.onNodeWithText("使用频率").assertExists()
    }

    @Test
    fun togglingSwitchReportsNewValue() {
        var value: Boolean? = null
        compose.setContent {
            MaterialTheme { ActivityScreen(ActivityHistory(), onRecordingChange = { value = it }) }
        }
        compose.onNodeWithTag("log-recording-switch").performClick()
        assertEquals(false, value)
    }

    @Test
    fun clearButtonDisabledWhenEmpty() {
        compose.setContent { MaterialTheme { ActivityScreen(ActivityHistory()) } }
        compose.onNodeWithTag("log-clear").assertIsNotEnabled()
    }
}
