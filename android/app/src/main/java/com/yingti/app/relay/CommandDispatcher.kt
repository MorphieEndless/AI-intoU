package com.yingti.app.relay

import com.yingti.app.AppState
import com.yingti.app.PausedOutput
import com.yingti.app.ToyControl
import com.yingti.app.patterns.PatternPlayback
import com.yingti.app.patterns.PatternPlayRecorder
import com.yingti.app.history.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.yingti.app.ble.SvakomProtocol
import com.yingti.app.ble.ToyOutput
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt
import kotlin.math.sin

class CommandDispatcher(
    private val ble: ToyOutput,
    private val scope: CoroutineScope,
    private val history: ActivityStore,
    private val patternStats: PatternPlayRecorder? = null,
    private val nanoTime: () -> Long = System::nanoTime,
) : RelayCommands {
    override suspend fun dispatch(command: JSONObject): JSONObject = dispatch(command, OperationSource.AI)

    private var activeJob: Job? = null
    private var timedStop: Job? = null
    private var timedChannel: String? = null
    private var timedMode: Int? = null
    private var timedDeadline = 0L
    private var pausedTimer: PausedTimer? = null
    private var gate: PauseGate? = null

    private val commandMutex = Mutex()
    private val outputMutex = Mutex()

    /** Pausing freezes one pattern job's timeline without cancelling it. */
    private inner class PauseGate {
        val paused = MutableStateFlow(false)
        var reapply: (suspend () -> Unit)? = null
        private var pausedSince = 0L
        private var pausedTotal = 0L
        private var wasPaused = false

        private fun reconcile(now: Long) {
            if (paused.value && !wasPaused) pausedSince = now
            if (!paused.value && wasPaused) pausedTotal += now - pausedSince
            wasPaused = paused.value
        }

        fun activeNanosSince(start: Long): Long {
            val now = nanoTime()
            reconcile(now)
            return now - start - pausedTotal - (if (wasPaused) now - pausedSince else 0)
        }

        suspend fun write(block: suspend () -> Unit) = outputMutex.withLock { if (!paused.value) block() }

        suspend fun delay(ms: Long) {
            var remainingNanos = if (ms == Long.MAX_VALUE) Long.MAX_VALUE else ms * 1_000_000L
            while (true) {
                reconcile(nanoTime())
                if (paused.value) {
                    paused.first { !it }
                    outputMutex.withLock { if (!paused.value) reapply?.invoke() }
                    continue
                }
                if (remainingNanos == Long.MAX_VALUE) {
                    paused.first { it } // Forever wait only observes pause/resume.
                    continue
                }
                val start = nanoTime()
                val interrupted = withTimeoutOrNull((remainingNanos + 999_999) / 1_000_000) { paused.first { it } } != null
                if (!interrupted) return
                remainingNanos = (remainingNanos - (nanoTime() - start)).coerceAtLeast(0)
            }
        }
    }

    private data class PausedTimer(val channel: String, val mode: Int?, val remainingNanos: Long)

    suspend fun dispatch(command: JSONObject, source: OperationSource): JSONObject = commandMutex.withLock {
        val type = command.optString("type")
        val requestId = command.optString("request_id").takeIf { it.isNotBlank() }
        if (type in setOf("command", "pattern", "custom_pattern", "stop", "raw")) {
            pausedTimer = null
            AppState.update { it.copy(pausedOutput = null) }
        }
        // Only store whitelisted labels and numbers, never raw JSON, server errors or tokens.
        val label = when (type) {
            "command" -> when (command.optString("action")) {
                "constrict" -> "吮吸 · ${safeLevel(command, 5)}/5"
                else -> "震动 · ${safeLevel(command, 10)}/10"
            }
            "pattern" -> "内置波形"
            "custom_pattern" -> "自定义波形"
            "stop" -> "全部停止"
            "scan" -> "蓝牙扫描"
            "read_sensor" -> "传感器查询"
            "raw" -> "协议调试"
            else -> "未知操作"
        }
        val event = history.begin(source, label)
        val result = try {
            when (type) {
                "command" -> direct(command, requestId, event)
                "pattern" -> pattern(command, requestId, event)
                "custom_pattern" -> customPattern(command, requestId, event)
                "stop" -> stop(requestId)
                "scan" -> { ble.scan(); ack(true, "Scan started", requestId) }
                "raw" -> {
                    require(source == OperationSource.PHONE) { "Raw writes are local-only" }
                    cancelJobs()
                    val written = ble.writeRaw(command.getString("hex"))
                    ack(written.isSuccess, if (written.isSuccess) "调试帧已提交" else "调试帧发送失败", requestId)
                }
                "read_sensor" -> ack(false, "SX589B battery sensor is not available", requestId)
                else -> ack(false, "Unknown command type", requestId)
            }
        } catch (e: CancellationException) {
            history.update(event, OperationStatus.CANCELLED)
            throw e
        } catch (_: Exception) { ack(false, "操作失败，请检查蓝牙连接与指令参数", requestId) }
        if (!result.optBoolean("success")) history.update(event, OperationStatus.FAILED)
        else if (type !in setOf("pattern", "custom_pattern", "command")) history.update(event, OperationStatus.SENT)
        result
    }

    suspend fun pause(source: OperationSource = OperationSource.PHONE): JSONObject = commandMutex.withLock {
        val event = history.begin(source, "暂停输出")
        val result = pauseLocked(null)
        history.update(event, if (result.optBoolean("success")) OperationStatus.SENT else OperationStatus.FAILED)
        result
    }

    suspend fun resume(source: OperationSource = OperationSource.PHONE): JSONObject = commandMutex.withLock {
        val event = history.begin(source, "继续输出")
        val result = resumeLocked(null)
        history.update(event, if (result.optBoolean("success")) OperationStatus.SENT else OperationStatus.FAILED)
        result
    }

    suspend fun togglePause(source: OperationSource = OperationSource.PHONE): JSONObject =
        when (AppState.state.value.toyControl) {
            ToyControl.PAUSED -> resume(source)
            ToyControl.RUNNING -> pause(source)
            ToyControl.IDLE -> dispatch(JSONObject().put("type", "stop"), source)
        }

    private suspend fun pauseLocked(requestId: String?): JSONObject {
        val g = gate
        if (g != null && activeJob?.isActive == true) {
            g.paused.value = true
            outputMutex.withLock { ble.stopAll() }
            AppState.update { state ->
                state.patternPlayback?.let { state.copy(patternPlayback = it.pausedAt(nanoTime())) } ?: state
            }
            return ack(true, "已暂停，点按继续从断点播放", requestId)
        }
        val current = AppState.state.value
        if (current.intensity > 0 || current.suctionIntensity > 0) {
            val snapshot = PausedOutput(current.intensity, current.suctionIntensity, current.suctionMode)
            timedStop?.takeIf { it.isActive && timedDeadline > 0 }?.let { job ->
                val remaining = (timedDeadline - nanoTime()).coerceAtLeast(0)
                job.cancelAndJoin()
                timedStop = null
                pausedTimer = PausedTimer(timedChannel ?: "vibrate", timedMode, remaining)
            }
            ble.stopAll()
            AppState.update { it.copy(pausedOutput = snapshot) }
            return ack(true, "已暂停，点按继续恢复档位", requestId)
        }
        return ack(false, "当前没有正在运行的输出", requestId)
    }

    private suspend fun resumeLocked(requestId: String?): JSONObject {
        if (!ble.isConnected) return ack(false, "设备未连接", requestId)
        val g = gate
        if (g != null && g.paused.value && activeJob?.isActive == true) {
            AppState.update { state ->
                state.patternPlayback?.let { state.copy(patternPlayback = it.resumedAt(nanoTime())) } ?: state
            }
            g.paused.value = false
            return ack(true, "已继续", requestId)
        }
        val paused = AppState.state.value.pausedOutput
        if (paused != null) {
            if (paused.vibrateLevel > 0 && ble.setVibration(paused.vibrateLevel / 10.0).isFailure) {
                return ack(false, "恢复输出失败，请检查蓝牙连接", requestId)
            }
            if (paused.suctionLevel > 0 && ble.setSuction(paused.suctionLevel / 5.0, paused.suctionMode).isFailure) {
                return ack(false, "恢复输出失败，请检查蓝牙连接", requestId)
            }
            AppState.update { it.copy(pausedOutput = null) }
            pausedTimer?.let { timer ->
                pausedTimer = null
                outputWriter(timer.channel, timer.mode)?.let { writer ->
                    if (timer.remainingNanos > 0) armTimedStop(writer, timer.remainingNanos / 1_000_000)
                }
            }
            return ack(true, "已继续", requestId)
        }
        return ack(false, "当前没有已暂停的输出", requestId)
    }

    private fun safeLevel(command: JSONObject, steps: Int): Int {
        val value = command.optDouble("intensity", 0.0)
        return if (value.isFinite()) (value.coerceIn(0.0, 1.0) * steps).roundToInt() else 0
    }

    /** A pattern counts once, on its first positive write accepted by the BLE stack. */
    private fun tracked(event: String, writer: suspend (Double) -> Result<Int>): suspend (Double) -> Result<Int> {
        var counted = false
        return { value ->
            writer(value).also { result ->
                if (!counted && result.getOrNull()?.let { it > 0 } == true) {
                    counted = true
                    history.update(event, OperationStatus.RUNNING, usage = true)
                }
            }
        }
    }

    /**
     * played = (统计 id, 显示名)；只有整个波形自然播完（status == DONE，含暂停后继续播完）
     * 才记一次「成功播放」。被停止 / 被新指令替换 / 执行失败 / 清理失败都不计——
     * 所以 duration ≤ 0 的内置波形只能被停止，永远不会被计入。
     */
    private fun launchPattern(event: String, cleanup: suspend () -> Unit, patternId: String? = null, totalMs: Long = 0, played: Pair<String, String>? = null, block: suspend (PauseGate) -> Unit): Job {
        val thisGate = PauseGate()
        gate = thisGate
        return scope.launch {
            AppState.update { it.copy(patternPlayback = PatternPlayback(patternId, event, nanoTime(), totalMs)) }
            history.update(event, OperationStatus.RUNNING)
            var status = OperationStatus.DONE
            try { block(thisGate) }
            catch (e: CancellationException) { status = OperationStatus.CANCELLED; throw e }
            catch (_: Exception) {
                status = OperationStatus.FAILED
                AppState.update { it.copy(error = "波形执行失败，请检查蓝牙连接") }
            } finally {
                withContext(NonCancellable) {
                    try { cleanup() } catch (_: Exception) { status = OperationStatus.FAILED }
                }
                if (gate === thisGate) gate = null
                AppState.update { state ->
                    if (state.patternPlayback?.event == event) state.copy(patternPlayback = null) else state
                }
                history.update(event, status)
                if (status == OperationStatus.DONE) played?.let { (id, name) ->
                    runCatching { patternStats?.recordSuccess(id, name) }
                }
            }
        }
    }

    private suspend fun direct(cmd: JSONObject, requestId: String?, event: String): JSONObject {
        val device = cmd.optString("device", "all")
        if (!targetsDevice(device)) return ack(false, "Device not found. Available: [yingti]", requestId)
        val action = cmd.optString("action", cmd.optString("output_type", "vibrate"))
        val mode = if (action == "constrict") suctionModeField(cmd) else null
        val writer = outputWriter(action, mode) ?: return ack(false, "SX589B does not support output: $action", requestId)

        cancelJobs()
        val intensity = numericField(cmd, "intensity", 0.5).coerceIn(0.0, 1.0)
        val duration = numericField(cmd, "duration", 0.0).coerceAtLeast(0.0)
        val result = writer(intensity)
        if (result.isFailure) return ack(false, result.exceptionOrNull()?.message ?: "BLE write failed", requestId)
        val level = result.getOrThrow()
        history.update(event, OperationStatus.SENT, usage = level > 0)
        val steps = if (action == "constrict") SvakomProtocol.SUCTION_STEPS else 10
        val applied = level.toDouble() / steps

        if (duration > 0) {
            timedChannel = action
            timedMode = mode
            timedDeadline = nanoTime() + (duration * 1_000_000_000).toLong()
            armTimedStop(writer, (duration * 1000).toLong())
        }
        val modeText = mode?.let { ", mode $it" } ?: ""
        return ack(
            true,
            "Set $action level $level/$steps$modeText (requested $intensity, applied $applied) on yingti",
            requestId,
        )
    }

    private fun armTimedStop(writer: suspend (Double) -> Result<Int>, delayMs: Long) {
        timedStop = scope.launch {
            delay(delayMs)
            val stopped = writer(0.0)
            val stopEvent = history.begin(OperationSource.SYSTEM, "定时停止")
            history.update(stopEvent, if (stopped.isSuccess) OperationStatus.SENT else OperationStatus.FAILED)
        }
    }

    /** 输出通道 → BLE 写入器。constrict 可固定 byte4 mode。 */
    private fun outputWriter(action: String, mode: Int? = null): (suspend (Double) -> Result<Int>)? = when (action) {
        "vibrate" -> ble::setVibration
        "constrict" -> { intensity ->
            ble.setSuction(intensity, mode ?: SvakomProtocol.SUCTION_DEFAULT_MODE)
        }
        else -> null
    }

    private suspend fun pattern(cmd: JSONObject, requestId: String?, event: String): JSONObject {
        val device = cmd.optString("device", "all")
        if (!targetsDevice(device)) return ack(false, "Device not found. Available: [yingti]", requestId)
        val output = cmd.optString("output_type", "vibrate")
        val mode = if (output == "constrict") suctionModeField(cmd) else null
        val writer = outputWriter(output, mode) ?: return ack(false, "SX589B does not support output: $output", requestId)

        if (!ble.isConnected) return ack(false, "设备未连接", requestId)
        val measuredWriter = tracked(event, writer)
        val name = cmd.optString("pattern", "pulse")
        val intensity = numericField(cmd, "intensity", 0.6).coerceIn(0.0, 1.0)
        val duration = numericField(cmd, "duration", 10.0).coerceAtLeast(0.0)
        val hold = numericField(cmd, "hold_seconds", 10.0).coerceAtLeast(0.0)
        if (name !in setOf("pulse", "wave", "escalate")) return ack(false, "Unknown pattern: $name", requestId)
        val totalMs = when {
            name == "escalate" && duration > 0 && hold > 0 -> ((duration + hold) * 1000).toLong()
            name != "escalate" && duration > 0 -> (duration * 1000).toLong()
            else -> 0L
        }

        val played = when (name) {
            "pulse" -> "pattern:pulse" to "脉冲"
            "wave" -> "pattern:wave" to "波浪"
            else -> "pattern:escalate" to "渐强"
        }
        cancelJobs()
        activeJob = launchPattern(event, cleanup = { writer(0.0).getOrThrow() }, totalMs = totalMs, played = played) { g ->
            when (name) {
                "pulse" -> runPulse(g, measuredWriter, intensity, duration)
                "wave" -> runWave(g, measuredWriter, intensity, duration)
                "escalate" -> runEscalate(g, measuredWriter, intensity, duration, hold)
            }
        }
        return ack(true, "Pattern $name started on yingti${mode?.let { " (constrict mode $it)" } ?: ""}", requestId)
    }

    /**
     * 执行服务端展开后的固定 steps。每步支持 constrict_mode，缺省 5（持续）。
     * 手机端再次执行边界校验，不能信任上游 JSON。
     */
    private suspend fun customPattern(cmd: JSONObject, requestId: String?, event: String): JSONObject {
        val device = cmd.optString("device", "yingti")
        if (!targetsDevice(device)) return ack(false, "Device not found. Available: [yingti]", requestId)
        if (!ble.isConnected) return ack(false, "设备未连接", requestId)
        val stepsJson = cmd.optJSONArray("steps") ?: return ack(false, "custom_pattern requires steps", requestId)
        if (stepsJson.length() !in 1..MAX_CUSTOM_STEPS) {
            return ack(false, "steps must contain 1-$MAX_CUSTOM_STEPS items", requestId)
        }
        val scale = numericField(cmd, "intensity_scale", 1.0).coerceIn(0.0, 1.0)
        val repetitions = integerField(cmd, "repeat", 1)
        if (repetitions !in 1..MAX_CUSTOM_REPEAT) return ack(false, "repeat must be 1-$MAX_CUSTOM_REPEAT", requestId)

        val steps = buildList {
            var totalMs = 0L
            for (index in 0 until stepsJson.length()) {
                val item = stepsJson.optJSONObject(index)
                    ?: throw IllegalArgumentException("steps[$index] must be an object")
                val durationMs = integerField(item, "duration_ms", 0)
                if (durationMs < MIN_STEP_MS) throw IllegalArgumentException("steps[$index].duration_ms must be >= $MIN_STEP_MS")
                totalMs += durationMs
                val vibrate = numericField(item, "vibrate", 0.0).coerceIn(0.0, 1.0) * scale
                val constrict = numericField(item, "constrict", 0.0).coerceIn(0.0, 1.0) * scale
                val constrictMode = suctionModeField(item)
                add(CustomStep(durationMs.toLong(), vibrate, constrict, constrictMode))
            }
            if (totalMs * repetitions > MAX_CUSTOM_DURATION_MS) {
                throw IllegalArgumentException("custom pattern may run for at most ${MAX_CUSTOM_DURATION_MS / 60_000} minutes")
            }
        }

        cancelJobs()
        val name = cmd.optString("name", "custom")
        // 统计 id 优先用波形库 / 服务端传来的 id，否则按名字兜底；显示名最长 40。
        val statId = cmd.optString("id").takeIf { it.isNotBlank() } ?: "name:$name"
        val statName = cmd.optString("name").takeIf { it.isNotBlank() }?.take(40) ?: statId.take(40)
        activeJob = launchPattern(event, cleanup = { ble.stopAll().getOrThrow() },
            patternId = cmd.optString("id").takeIf { it.isNotBlank() }, totalMs = steps.sumOf { it.durationMs } * repetitions,
            played = statId to statName) { g ->
                var counted = false
                var lastVibrateLevel: Int? = null
                var lastSuctionLevel: Int? = null
                var lastSuctionMode: Int? = null
                suspend fun applyStep(step: CustomStep, force: Boolean) {
                    val vibrateLevel = SvakomProtocol.levelFor(step.vibrate)
                    val suctionLevel = SvakomProtocol.suctionLevelFor(step.constrict)
                    if (force || vibrateLevel != lastVibrateLevel) {
                        ble.setVibration(step.vibrate).getOrThrow()
                        if (!counted && vibrateLevel > 0) {
                            counted = true
                            history.update(event, OperationStatus.RUNNING, usage = true)
                        }
                        lastVibrateLevel = vibrateLevel
                    }
                    if (force || suctionLevel != lastSuctionLevel || (suctionLevel > 0 && step.constrictMode != lastSuctionMode)) {
                        ble.setSuction(step.constrict, step.constrictMode).getOrThrow()
                        lastSuctionLevel = suctionLevel
                        lastSuctionMode = step.constrictMode
                    }
                    if (!counted && (vibrateLevel > 0 || suctionLevel > 0)) {
                        counted = true
                        history.update(event, OperationStatus.RUNNING, usage = true)
                    }
                }
                repeat(repetitions) {
                    for (step in steps) {
                        g.reapply = { applyStep(step, force = true) }
                        g.write { applyStep(step, force = false) }
                        g.delay(step.durationMs)
                    }
                }
        }
        return ack(true, "Custom pattern $name started (${steps.size} steps × $repetitions)", requestId)
    }

    private suspend fun stop(requestId: String?): JSONObject {
        cancelJobs()
        val result = ble.stopAll()
        return if (result.isSuccess) ack(true, "Stopped yingti", requestId)
        else ack(false, result.exceptionOrNull()?.message ?: "Stop failed", requestId)
    }

    suspend fun emergencyStop() {
        dispatch(JSONObject().put("type", "stop"), OperationSource.SYSTEM)
    }

    /** Pause-gated write: skipped while paused; reapply restores the value on resume. */
    private suspend fun PauseGate.output(writer: suspend (Double) -> Result<Int>, value: Double): Result<Int> {
        reapply = { writer(value) }
        var result: Result<Int> = Result.success(0)
        write { result = writer(value) }
        return result
    }

    private suspend fun runPulse(g: PauseGate, writer: suspend (Double) -> Result<Int>, intensity: Double, duration: Double) {
        val started = nanoTime()
        while (duration <= 0 || g.activeNanosSince(started) / 1e9 < duration) {
            g.output(writer, intensity).getOrThrow()
            g.delay(500)
            g.output(writer, 0.0).getOrThrow()
            g.delay(300)
        }
    }

    private suspend fun runWave(g: PauseGate, writer: suspend (Double) -> Result<Int>, intensity: Double, duration: Double) {
        val started = nanoTime()
        while (duration <= 0 || g.activeNanosSince(started) / 1e9 < duration) {
            val value = ((sin(g.activeNanosSince(started) / 1e9 * 2.0) + 1.0) / 2.0) * intensity
            g.output(writer, value).getOrThrow()
            g.delay(100)
        }
    }

    private suspend fun runEscalate(g: PauseGate, writer: suspend (Double) -> Result<Int>, peak: Double, duration: Double, hold: Double) {
        val steps = 20
        val stepDelay = if (duration > 0) (duration * 1000 / steps).toLong() else 0L
        for (i in 0..steps) {
            g.output(writer, (i.toDouble() / steps) * peak).getOrThrow()
            if (stepDelay > 0) g.delay(stepDelay)
        }
        if (hold > 0) g.delay((hold * 1000).toLong()) else g.delay(Long.MAX_VALUE)
    }

    private suspend fun cancelJobs() {
        timedStop?.cancelAndJoin()
        timedStop = null
        timedChannel = null
        timedMode = null
        timedDeadline = 0
        activeJob?.let { job ->
            activeJob = null
            job.cancelAndJoin()
        }
    }

    private fun targetsDevice(device: String) = device == "all" || device == "yingti"

    private fun numericField(cmd: JSONObject, key: String, default: Double): Double {
        if (!cmd.has(key) || cmd.isNull(key)) return default
        return FlexibleNumber.parse(cmd.get(key), key)
    }

    private fun integerField(cmd: JSONObject, key: String, default: Int): Int {
        val value = numericField(cmd, key, default.toDouble())
        require(value.isFinite() && value == value.roundToInt().toDouble()) { "$key must be an integer" }
        return value.roundToInt()
    }

    private fun suctionModeField(cmd: JSONObject): Int {
        val mode = when {
            cmd.has("mode") && !cmd.isNull("mode") -> integerField(cmd, "mode", SvakomProtocol.SUCTION_DEFAULT_MODE)
            else -> integerField(cmd, "constrict_mode", SvakomProtocol.SUCTION_DEFAULT_MODE)
        }
        require(mode in SvakomProtocol.SUCTION_MIN_MODE..SvakomProtocol.SUCTION_MAX_MODE) {
            "constrict mode must be ${SvakomProtocol.SUCTION_MIN_MODE}-${SvakomProtocol.SUCTION_MAX_MODE}"
        }
        return mode
    }

    override fun deviceList(): JSONObject = JSONObject().put("type", "device_list").put(
        "devices", JSONArray().put(
            JSONObject()
                .put("short_name", "yingti")
                .put("name", "SVAKOM SX589B")
                .put("device_name", "SX589B")
                .put("intensity_floor", 0.0)
                .put("output_steps", JSONObject()
                    .put("vibrate", 10)
                    .put("constrict", SvakomProtocol.SUCTION_STEPS))
                .put("output_options", JSONObject()
                    .put("constrict_mode", JSONObject()
                        .put("min", SvakomProtocol.SUCTION_MIN_MODE)
                        .put("max", SvakomProtocol.SUCTION_MAX_MODE)
                        .put("default", SvakomProtocol.SUCTION_DEFAULT_MODE)))
                .put("capabilities", JSONObject()
                    .put("vibrate", "single vibration actuator; 10 discrete levels")
                    .put("constrict", "suction actuator; 5 strength levels and modes 1-8"))
                .put("available_outputs", JSONArray().put("vibrate").put("constrict"))
                .put("notes", "SVAKOM SX589B direct BLE control")
        )
    )

    private fun ack(success: Boolean, message: String, requestId: String?, data: JSONObject? = null): JSONObject =
        JSONObject().put("type", "command_ack").put("success", success).put("message", message).apply {
            requestId?.let { put("request_id", it) }
            data?.let { put("data", it) }
            AppState.update { state -> state.copy(lastMessage = message, error = if (success) null else message) }
        }

    private data class CustomStep(
        val durationMs: Long,
        val vibrate: Double,
        val constrict: Double,
        val constrictMode: Int,
    )

    private companion object {
        const val MIN_STEP_MS = 100
        const val MAX_CUSTOM_STEPS = 128
        const val MAX_CUSTOM_REPEAT = 60
        const val MAX_CUSTOM_DURATION_MS = 10 * 60 * 1000L
    }
}
