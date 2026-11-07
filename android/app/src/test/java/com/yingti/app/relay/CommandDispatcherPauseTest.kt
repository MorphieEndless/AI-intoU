package com.yingti.app.relay

import android.app.Application
import com.yingti.app.AppState
import com.yingti.app.PausedOutput
import com.yingti.app.ToyControl
import com.yingti.app.ble.SvakomProtocol
import com.yingti.app.ble.ToyOutput
import com.yingti.app.history.ActivityStore
import com.yingti.app.history.OperationSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class CommandDispatcherPauseTest {
    private class FakeToy : ToyOutput {
        var connected = true
        val writes = mutableListOf<String>()
        override val isConnected get() = connected
        override fun scan() {}
        override suspend fun setVibration(intensity: Double): Result<Int> {
            val level = SvakomProtocol.levelFor(intensity)
            writes += "vib:$level"
            AppState.update { it.copy(intensity = level, lastMessage = "震动 $level 档") }
            return Result.success(level)
        }
        override suspend fun setSuction(intensity: Double, mode: Int): Result<Int> {
            val level = SvakomProtocol.suctionLevelFor(intensity)
            writes += "suc:$level@$mode"
            AppState.update { it.copy(suctionIntensity = level, suctionMode = if (level > 0) mode else it.suctionMode) }
            return Result.success(level)
        }
        override suspend fun stopAll(): Result<Unit> {
            writes += "stopAll"
            AppState.update { it.copy(intensity = 0, suctionIntensity = 0, lastMessage = "全部停止") }
            return Result.success(Unit)
        }
        override suspend fun writeRaw(hexText: String): Result<String> = Result.success(hexText)
    }

    private val toy = FakeToy()
    private lateinit var history: ActivityStore

    @Before fun setUp() {
        AppState.reset()
        history = ActivityStore(RuntimeEnvironment.getApplication())
    }

    @After fun tearDown() { AppState.reset() }

    private fun customPattern() = JSONObject()
        .put("type", "custom_pattern").put("id", "abc123456789").put("repeat", 2)
        .put("steps", JSONArray()
            .put(JSONObject().put("duration_ms", 1000).put("vibrate", .5))
            .put(JSONObject().put("duration_ms", 1000).put("constrict", .6).put("constrict_mode", 3)))

    @Test fun customPatternPauseFreezesTimelineAndResumeReappliesStep() = runTest {
        val dispatcher = CommandDispatcher(toy, backgroundScope, history) { testScheduler.currentTime * 1_000_000 }
        assertTrue(dispatcher.dispatch(customPattern(), OperationSource.PHONE).getBoolean("success"))
        advanceTimeBy(1500); runCurrent()
        assertEquals(ToyControl.RUNNING, AppState.state.value.toyControl)

        val pauseAck = dispatcher.pause()
        assertTrue(pauseAck.getBoolean("success"))
        val paused = AppState.state.value
        assertEquals("stopAll", toy.writes.last())
        assertEquals(0, paused.intensity)
        assertEquals(0, paused.suctionIntensity)
        assertEquals(true, paused.patternPlayback?.paused)
        assertEquals(1500L, paused.patternPlayback?.pausedAtMs)
        assertEquals(ToyControl.PAUSED, paused.toyControl)

        val writesAtPause = toy.writes.size
        advanceTimeBy(60_000); runCurrent()
        assertEquals(writesAtPause, toy.writes.size) // Frozen: no writes while paused.
        assertEquals(ToyControl.PAUSED, AppState.state.value.toyControl)

        assertTrue(dispatcher.resume().getBoolean("success"))
        runCurrent()
        assertFalse(AppState.state.value.patternPlayback!!.paused)
        // The interrupted constrict step is re-applied on resume.
        assertEquals(2, toy.writes.count { it == "suc:3@3" })

        advanceTimeBy(500); runCurrent()
        assertEquals("suc:0@5", toy.writes.last()) // Round 2 started its first step.
        advanceTimeBy(2500); runCurrent()
        assertNull(AppState.state.value.patternPlayback)
        assertEquals(ToyControl.IDLE, AppState.state.value.toyControl)
    }

    @Test fun fixedOutputsSnapshotAndRestoreOnResume() = runTest {
        val dispatcher = CommandDispatcher(toy, backgroundScope, history) { testScheduler.currentTime * 1_000_000 }
        dispatcher.dispatch(JSONObject().put("type", "command").put("action", "vibrate").put("intensity", .7), OperationSource.PHONE)
        dispatcher.dispatch(JSONObject().put("type", "command").put("action", "constrict").put("intensity", .6).put("mode", 6), OperationSource.PHONE)
        runCurrent()

        assertTrue(dispatcher.pause().getBoolean("success"))
        assertEquals(PausedOutput(7, 3, 6), AppState.state.value.pausedOutput)
        assertEquals(ToyControl.PAUSED, AppState.state.value.toyControl)

        assertTrue(dispatcher.resume().getBoolean("success"))
        runCurrent()
        assertNull(AppState.state.value.pausedOutput)
        assertEquals("vib:7", toy.writes[toy.writes.size - 2])
        assertEquals("suc:3@6", toy.writes.last())
        assertEquals(ToyControl.RUNNING, AppState.state.value.toyControl)
    }

    @Test fun pausedTimedCommandKeepsItsRemainingDuration() = runTest {
        val dispatcher = CommandDispatcher(toy, backgroundScope, history) { testScheduler.currentTime * 1_000_000 }
        dispatcher.dispatch(JSONObject().put("type", "command").put("action", "vibrate").put("intensity", .5).put("duration", 10), OperationSource.PHONE)
        runCurrent()
        advanceTimeBy(3000); runCurrent()

        assertTrue(dispatcher.pause().getBoolean("success"))
        val before = toy.writes.size
        advanceTimeBy(60_000); runCurrent()
        assertEquals(before, toy.writes.size) // The timed stop does not fire while paused.

        assertTrue(dispatcher.resume().getBoolean("success"))
        runCurrent()
        advanceTimeBy(6900); runCurrent()
        assertEquals(5, AppState.state.value.intensity)
        advanceTimeBy(200); runCurrent()
        assertEquals(0, AppState.state.value.intensity)
    }

    @Test fun newCommandsClearThePausedSnapshot() = runTest {
        val dispatcher = CommandDispatcher(toy, backgroundScope, history) { testScheduler.currentTime * 1_000_000 }
        dispatcher.dispatch(JSONObject().put("type", "command").put("action", "vibrate").put("intensity", .7), OperationSource.PHONE)
        runCurrent()
        dispatcher.pause()
        assertNotNull(AppState.state.value.pausedOutput)
        dispatcher.dispatch(JSONObject().put("type", "command").put("action", "vibrate").put("intensity", .3), OperationSource.AI)
        assertNull(AppState.state.value.pausedOutput)
    }

    @Test fun stopCancelsAPausedPatternJob() = runTest {
        val dispatcher = CommandDispatcher(toy, backgroundScope, history) { testScheduler.currentTime * 1_000_000 }
        dispatcher.dispatch(customPattern(), OperationSource.PHONE)
        advanceTimeBy(500); runCurrent()
        dispatcher.pause()
        assertEquals(ToyControl.PAUSED, AppState.state.value.toyControl)
        dispatcher.dispatch(JSONObject().put("type", "stop"), OperationSource.AI)
        runCurrent()
        assertNull(AppState.state.value.patternPlayback)
        assertEquals(ToyControl.IDLE, AppState.state.value.toyControl)
    }

    @Test fun togglePauseFollowsTheToyControlState() = runTest {
        val dispatcher = CommandDispatcher(toy, backgroundScope, history) { testScheduler.currentTime * 1_000_000 }
        dispatcher.togglePause()
        assertEquals("stopAll", toy.writes.last()) // IDLE degrades to a real stop.

        dispatcher.dispatch(JSONObject().put("type", "command").put("action", "vibrate").put("intensity", .5), OperationSource.PHONE)
        runCurrent()
        dispatcher.togglePause()
        assertEquals(ToyControl.PAUSED, AppState.state.value.toyControl)
        dispatcher.togglePause()
        assertEquals(ToyControl.RUNNING, AppState.state.value.toyControl)
    }

    @Test fun resumeWhileDisconnectedFailsAndStaysPaused() = runTest {
        val dispatcher = CommandDispatcher(toy, backgroundScope, history) { testScheduler.currentTime * 1_000_000 }
        dispatcher.dispatch(JSONObject().put("type", "command").put("action", "vibrate").put("intensity", .7), OperationSource.PHONE)
        runCurrent()
        dispatcher.pause()
        toy.connected = false
        val ack = dispatcher.resume()
        assertFalse(ack.getBoolean("success"))
        assertEquals("设备未连接", ack.getString("message"))
        assertEquals(ToyControl.PAUSED, AppState.state.value.toyControl)
        assertNotNull(AppState.state.value.pausedOutput)
    }

    @Test fun builtinPulsePauseAndResumeKeepsFiveSecondsOfActiveTime() = runTest {
        val dispatcher = CommandDispatcher(toy, backgroundScope, history) { testScheduler.currentTime * 1_000_000 }
        dispatcher.dispatch(JSONObject().put("type", "pattern").put("pattern", "pulse").put("intensity", .6).put("duration", 5), OperationSource.PHONE)
        advanceTimeBy(1200); runCurrent()
        assertTrue(dispatcher.pause().getBoolean("success"))
        assertEquals("stopAll", toy.writes.last())
        advanceTimeBy(60_000); runCurrent()
        assertTrue(dispatcher.resume().getBoolean("success"))
        runCurrent()
        advanceTimeBy(3000); runCurrent()
        // Only 4.2s of unpaused time has elapsed; the 5s pulse is still running.
        assertNotNull(AppState.state.value.patternPlayback)
        advanceTimeBy(1500); runCurrent()
        // Over 5s of active time: the pattern has finished.
        assertNull(AppState.state.value.patternPlayback)
        assertEquals(ToyControl.IDLE, AppState.state.value.toyControl)
    }
}
