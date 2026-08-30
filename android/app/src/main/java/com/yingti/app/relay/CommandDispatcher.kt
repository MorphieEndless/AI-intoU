package com.yingti.app.relay

import com.yingti.app.AppState
import com.yingti.app.ble.BleController
import com.yingti.app.ble.SvakomProtocol
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt
import kotlin.math.sin

class CommandDispatcher(
    private val ble: BleController,
    private val scope: CoroutineScope,
) {
    private var activeJob: Job? = null
    private var timedStop: Job? = null

    suspend fun dispatch(command: JSONObject): JSONObject {
        val type = command.optString("type")
        val requestId = command.optString("request_id").takeIf { it.isNotBlank() }
        return try {
            when (type) {
                "command" -> direct(command, requestId)
                "pattern" -> pattern(command, requestId)
                "custom_pattern" -> customPattern(command, requestId)
                "stop" -> stop(requestId)
                "scan" -> {
                    ble.scan()
                    ack(true, "Scan started", requestId)
                }
                "read_sensor" -> ack(false, "SX589B battery sensor is not available", requestId)
                else -> ack(false, "Unknown command type: $type", requestId)
            }
        } catch (t: Throwable) {
            ack(false, t.message ?: "Command failed", requestId)
        }
    }

    private suspend fun direct(cmd: JSONObject, requestId: String?): JSONObject {
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
        val steps = if (action == "constrict") SvakomProtocol.SUCTION_STEPS else 10
        val applied = level.toDouble() / steps

        if (duration > 0) timedStop = scope.launch {
            delay((duration * 1000).toLong())
            writer(0.0)
        }
        val modeText = mode?.let { ", mode $it" } ?: ""
        return ack(
            true,
            "Set $action level $level/$steps$modeText (requested $intensity, applied $applied) on yingti",
            requestId,
        )
    }

    /** 输出通道 → BLE 写入器。constrict 可固定 byte4 mode。 */
    private fun outputWriter(action: String, mode: Int? = null): (suspend (Double) -> Result<Int>)? = when (action) {
        "vibrate" -> ble::setVibration
        "constrict" -> { intensity ->
            ble.setSuction(intensity, mode ?: SvakomProtocol.SUCTION_DEFAULT_MODE)
        }
        else -> null
    }

    private suspend fun pattern(cmd: JSONObject, requestId: String?): JSONObject {
        val device = cmd.optString("device", "all")
        if (!targetsDevice(device)) return ack(false, "Device not found. Available: [yingti]", requestId)
        val output = cmd.optString("output_type", "vibrate")
        val mode = if (output == "constrict") suctionModeField(cmd) else null
        val writer = outputWriter(output, mode) ?: return ack(false, "SX589B does not support output: $output", requestId)

        val name = cmd.optString("pattern", "pulse")
        val intensity = numericField(cmd, "intensity", 0.6).coerceIn(0.0, 1.0)
        val duration = numericField(cmd, "duration", 10.0).coerceAtLeast(0.0)
        val hold = numericField(cmd, "hold_seconds", 0.0).coerceAtLeast(0.0)
        if (name !in setOf("pulse", "wave", "escalate")) return ack(false, "Unknown pattern: $name", requestId)

        cancelJobs()
        activeJob = scope.launch {
            try {
                when (name) {
                    "pulse" -> runPulse(writer, intensity, duration)
                    "wave" -> runWave(writer, intensity, duration)
                    "escalate" -> runEscalate(writer, intensity, duration, hold)
                }
            } finally {
                withContext(NonCancellable) { writer(0.0) }
            }
        }
        return ack(true, "Pattern $name started on yingti${mode?.let { " (constrict mode $it)" } ?: ""}", requestId)
    }

    /**
     * 执行服务端展开后的固定 steps。每步支持 constrict_mode，缺省 5（持续）。
     * 手机端再次执行边界校验，不能信任上游 JSON。
     */
    private suspend fun customPattern(cmd: JSONObject, requestId: String?): JSONObject {
        val device = cmd.optString("device", "yingti")
        if (!targetsDevice(device)) return ack(false, "Device not found. Available: [yingti]", requestId)
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
        activeJob = scope.launch {
            try {
                var lastVibrateLevel: Int? = null
                var lastSuctionLevel: Int? = null
                var lastSuctionMode: Int? = null
                repeat(repetitions) {
                    for (step in steps) {
                        val vibrateLevel = SvakomProtocol.levelFor(step.vibrate)
                        val suctionLevel = SvakomProtocol.suctionLevelFor(step.constrict)
                        if (vibrateLevel != lastVibrateLevel) {
                            ble.setVibration(step.vibrate).getOrThrow()
                            lastVibrateLevel = vibrateLevel
                        }
                        if (suctionLevel != lastSuctionLevel || (suctionLevel > 0 && step.constrictMode != lastSuctionMode)) {
                            ble.setSuction(step.constrict, step.constrictMode).getOrThrow()
                            lastSuctionLevel = suctionLevel
                            lastSuctionMode = step.constrictMode
                        }
                        delay(step.durationMs)
                    }
                }
            } finally {
                withContext(NonCancellable) { ble.stopAll() }
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
        cancelJobs()
        ble.stopAll()
    }

    private suspend fun runPulse(writer: suspend (Double) -> Result<Int>, intensity: Double, duration: Double) {
        val started = System.nanoTime()
        while (duration <= 0 || elapsedSeconds(started) < duration) {
            writer(intensity).getOrThrow()
            delay(500)
            writer(0.0).getOrThrow()
            delay(300)
        }
    }

    private suspend fun runWave(writer: suspend (Double) -> Result<Int>, intensity: Double, duration: Double) {
        val started = System.nanoTime()
        while (duration <= 0 || elapsedSeconds(started) < duration) {
            val value = ((sin(elapsedSeconds(started) * 2.0) + 1.0) / 2.0) * intensity
            writer(value).getOrThrow()
            delay(100)
        }
    }

    private suspend fun runEscalate(writer: suspend (Double) -> Result<Int>, peak: Double, duration: Double, hold: Double) {
        val steps = 20
        val stepDelay = if (duration > 0) (duration * 1000 / steps).toLong() else 0L
        for (i in 0..steps) {
            writer((i.toDouble() / steps) * peak).getOrThrow()
            if (stepDelay > 0) delay(stepDelay)
        }
        if (hold > 0) delay((hold * 1000).toLong()) else awaitCancellation()
    }

    private suspend fun cancelJobs() {
        timedStop?.cancel()
        timedStop = null
        activeJob?.let { job ->
            activeJob = null
            job.cancelAndJoin()
        }
    }

    private fun targetsDevice(device: String) = device == "all" || device == "yingti"
    private fun elapsedSeconds(start: Long) = (System.nanoTime() - start) / 1_000_000_000.0

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

    fun deviceList(): JSONObject = JSONObject().put("type", "device_list").put(
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
