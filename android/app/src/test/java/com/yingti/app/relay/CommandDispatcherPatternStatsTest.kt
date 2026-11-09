package com.yingti.app.relay

import android.app.Application
import com.yingti.app.AppState
import com.yingti.app.ble.SvakomProtocol
import com.yingti.app.ble.ToyOutput
import com.yingti.app.history.ActivityStore
import com.yingti.app.history.OperationSource
import com.yingti.app.patterns.PatternPlayRecorder
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
class CommandDispatcherPatternStatsTest {
    private class FakeToy : ToyOutput {
        var connected = true
        val writes = mutableListOf<String>()
        override val isConnected get() = connected
        override fun scan() {}
        override suspend fun setVibration(intensity: Double): Result<Int> {
            writes += "vib:${SvakomProtocol.levelFor(intensity)}"
            return Result.success(SvakomProtocol.levelFor(intensity))
        }
        override suspend fun setSuction(intensity: Double, mode: Int): Result<Int> {
            writes += "suc:${SvakomProtocol.suctionLevelFor(intensity)}@$mode"
            return Result.success(SvakomProtocol.suctionLevelFor(intensity))
        }
        override suspend fun stopAll(): Result<Unit> {
            writes += "stopAll"
            return Result.success(Unit)
        }
        override suspend fun writeRaw(hexText: String): Result<String> = Result.success(hexText)
    }

    private class FakeRecorder : PatternPlayRecorder {
        val calls = mutableListOf<Pair<String, String>>()
        override fun recordSuccess(id: String, name: String) { calls += id to name }
    }

    private val toy = FakeToy()
    private val recorder = FakeRecorder()
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

    @Test fun completedCustomPatternIsRecordedOnce() = runTest {
        val dispatcher = CommandDispatcher(toy, backgroundScope, history, recorder) { testScheduler.currentTime * 1_000_000 }
        assertTrue(dispatcher.dispatch(customPattern(), OperationSource.PHONE).getBoolean("success"))
        advanceTimeBy(5000); runCurrent()
        assertEquals(listOf("abc123456789" to "abc123456789"), recorder.calls)
    }

    @Test fun stoppedPatternIsNotRecorded() = runTest {
        val dispatcher = CommandDispatcher(toy, backgroundScope, history, recorder) { testScheduler.currentTime * 1_000_000 }
        dispatcher.dispatch(customPattern(), OperationSource.PHONE)
        advanceTimeBy(500); runCurrent()
        dispatcher.dispatch(JSONObject().put("type", "stop"), OperationSource.AI)
        runCurrent()
        assertTrue(recorder.calls.isEmpty())
    }

    @Test fun patternReplacedByNewCommandIsNotRecorded() = runTest {
        val dispatcher = CommandDispatcher(toy, backgroundScope, history, recorder) { testScheduler.currentTime * 1_000_000 }
        dispatcher.dispatch(customPattern(), OperationSource.PHONE)
        advanceTimeBy(500); runCurrent()
        dispatcher.dispatch(JSONObject().put("type", "command").put("action", "vibrate").put("intensity", .3), OperationSource.AI)
        runCurrent()
        assertTrue(recorder.calls.isEmpty())
    }

    @Test fun pausedThenResumedPatternCompletesAndIsRecordedOnce() = runTest {
        val dispatcher = CommandDispatcher(toy, backgroundScope, history, recorder) { testScheduler.currentTime * 1_000_000 }
        dispatcher.dispatch(customPattern(), OperationSource.PHONE)
        advanceTimeBy(1500); runCurrent()
        assertTrue(dispatcher.pause().getBoolean("success"))
        advanceTimeBy(60_000); runCurrent()
        assertTrue(dispatcher.resume().getBoolean("success"))
        advanceTimeBy(4000); runCurrent()
        assertNull(AppState.state.value.patternPlayback)
        assertEquals(listOf("abc123456789" to "abc123456789"), recorder.calls)
    }

    @Test fun builtinPulseCompletesAndIsRecorded() = runTest {
        val dispatcher = CommandDispatcher(toy, backgroundScope, history, recorder) { testScheduler.currentTime * 1_000_000 }
        dispatcher.dispatch(JSONObject().put("type", "pattern").put("pattern", "pulse").put("intensity", .6).put("duration", 5), OperationSource.PHONE)
        advanceTimeBy(6000); runCurrent()
        assertEquals(listOf("pattern:pulse" to "脉冲"), recorder.calls)
    }

    @Test fun disconnectedToyRecordsNothing() = runTest {
        toy.connected = false
        val dispatcher = CommandDispatcher(toy, backgroundScope, history, recorder) { testScheduler.currentTime * 1_000_000 }
        assertFalse(dispatcher.dispatch(customPattern(), OperationSource.PHONE).getBoolean("success"))
        runCurrent()
        assertTrue(recorder.calls.isEmpty())
    }
}
