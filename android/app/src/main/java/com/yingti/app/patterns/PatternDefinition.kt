package com.yingti.app.patterns

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

fun secondsLabel(ms: Long): String = if (ms % 1000L == 0L) (ms / 1000L).toString() else
    String.format(Locale.ROOT, "%.1f", ms / 1000.0)

data class WaveStep(val durationMs: Long, val vibrate: Double, val constrict: Double, val mode: Int = 5) {
    fun output(channel: String) = if (channel == "vibrate") vibrate else constrict
}

data class PatternDefinition(
    val id: String, val name: String, val description: String, val device: String,
    val repeat: Int, val intensityScale: Double, val steps: List<WaveStep>,
    val isLiked: Boolean = false, val isFavorite: Boolean = false, val builtin: Boolean = false,
) {
    val oneMs get() = steps.sumOf { it.durationMs }
    val totalMs get() = oneMs * repeat
    val durationLabel get() = "${secondsLabel(oneMs)}×$repeat·${secondsLabel(totalMs)}秒"
    fun hasChannel(channel: String) = steps.any { it.output(channel) > 0.0 }
    fun toJson() = JSONObject().put("id", id).put("name", name).put("description", description).put("device", device)
        .put("repeat", repeat).put("intensity_scale", intensityScale).put("is_liked", isLiked).put("is_favorite", isFavorite)
        .put("builtin", builtin).put("steps", JSONArray().apply { steps.forEach { step ->
            put(JSONObject().put("duration_ms", step.durationMs).put("vibrate", step.vibrate)
                .put("constrict", step.constrict).put("constrict_mode", step.mode))
        } })
    companion object {
        fun from(json: JSONObject): PatternDefinition {
            val raw = json.getJSONArray("steps")
            val steps = (0 until raw.length()).map { index ->
                val s = raw.getJSONObject(index)
                val ms = s.getLong("duration_ms")
                require(s.getDouble("duration_ms") == ms.toDouble() && ms in 100..600_000)
                val v = s.optDouble("vibrate", 0.0)
                val c = s.optDouble("constrict", 0.0)
                val m = if (s.isNull("constrict_mode")) 5 else s.getInt("constrict_mode")
                require(v.isFinite() && c.isFinite() && v in 0.0..1.0 && c in 0.0..1.0 && m in 1..8)
                require(s.isNull("constrict_mode") || s.getDouble("constrict_mode") == m.toDouble())
                WaveStep(ms, v, c, m)
            }
            val repeat = json.getInt("repeat")
            val scale = json.getDouble("intensity_scale")
            require(repeat in 1..60 && json.getDouble("repeat") == repeat.toDouble() && steps.size in 1..128)
            require(scale.isFinite() && scale in 0.0..1.0 && steps.sumOf { it.durationMs } * repeat <= 600_000)
            return PatternDefinition(json.getString("id"), json.getString("name"), json.optString("description"),
                json.optString("device", "yingti"), repeat, scale, steps, json.optBoolean("is_liked"),
                json.optBoolean("is_favorite"), json.optBoolean("builtin"))
        }
    }
}
