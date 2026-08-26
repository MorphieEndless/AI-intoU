package com.yingti.app.relay

import com.yingti.app.AppState
import com.yingti.app.ble.BleController
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
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
        val writer = outputWriter(action) ?: return ack(false, "SX589B does not support output: $action", requestId)

        cancelJobs()
        val intensity = numericField(cmd, "intensity", 0.5).coerceIn(0.0, 1.0)
        val duration = numericField(cmd, "duration", 0.0).coerceAtLeast(0.0)
        val result = writer(intensity)
        if (result.isFailure) return ack(false, result.exceptionOrNull()?.message ?: "BLE write failed", requestId)
        val level = result.getOrThrow()
        val steps = if (action == "constrict") 6 else 10
        val applied = level.toDouble() / steps

        if (duration > 0) timedStop = scope.launch {
            delay((duration * 1000).toLong())
            writer(0.0)
        }
        return ack(true, "Set $action level $level/$steps (requested $intensity, applied $applied) on yingti", requestId)
    }

    /** 输出通道 → BLE 写入器。vibrate=震动, constrict=吮吸。 */
    private fun outputWriter(action: String): (suspend (Double) -> Result<Int>)? = when (action) {
        "vibrate" -> ble::setVibration
        "constrict" -> ble::setSuction
        else -> null
    }

    private fun pattern(cmd: JSONObject, requestId: String?): JSONObject {
        val device = cmd.optString("device", "all")
        if (!targetsDevice(device)) return ack(false, "Device not found. Available: [yingti]", requestId)
        val output = cmd.optString("output_type", "vibrate")
        val writer = outputWriter(output) ?: return ack(false, "SX589B does not support output: $output", requestId)

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
                writer(0.0)
            }
        }
        return ack(true, "Pattern $name started on yingti", requestId)
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
            writer(intensity)
            delay(500)
            writer(0.0)
            delay(300)
        }
    }

    private suspend fun runWave(writer: suspend (Double) -> Result<Int>, intensity: Double, duration: Double) {
        val started = System.nanoTime()
        while (duration <= 0 || elapsedSeconds(started) < duration) {
            val value = ((sin(elapsedSeconds(started) * 2.0) + 1.0) / 2.0) * intensity
            writer(value)
            delay(100)
        }
    }

    private suspend fun runEscalate(writer: suspend (Double) -> Result<Int>, peak: Double, duration: Double, hold: Double) {
        val steps = 20
        val stepDelay = if (duration > 0) (duration * 1000 / steps).toLong() else 0L
        for (i in 0..steps) {
            writer((i.toDouble() / steps) * peak)
            if (stepDelay > 0) delay(stepDelay)
        }
        if (hold > 0) delay((hold * 1000).toLong()) else awaitCancellation()
    }

    private fun cancelJobs() {
        activeJob?.cancel(); activeJob = null
        timedStop?.cancel(); timedStop = null
    }

    private fun targetsDevice(device: String) = device == "all" || device == "yingti"
    private fun elapsedSeconds(start: Long) = (System.nanoTime() - start) / 1_000_000_000.0

    /**
     * Read an MCP numeric argument without assuming the model emitted a JSON
     * number. Numeric strings ("0.85") and percentages ("85%") are accepted;
     * malformed values fail the command instead of silently falling back to a
     * dangerous default intensity.
     */
    private fun numericField(cmd: JSONObject, key: String, default: Double): Double {
        if (!cmd.has(key) || cmd.isNull(key)) return default
        return FlexibleNumber.parse(cmd.get(key), key)
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
                    .put("constrict", 6))
                .put("capabilities", JSONObject()
                    .put("vibrate", "single vibration actuator; 10 discrete levels")
                    .put("constrict", "suction actuator; 6 discrete levels"))
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
}
