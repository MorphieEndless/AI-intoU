package com.yingti.app.patterns

import com.yingti.app.auth.ConnectionConfig
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** The app reads the MCP user's existing server library; it never scrapes tool prose. */
class PatternApi(private val config: ConnectionConfig) {
    private val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS).callTimeout(20, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build()

    suspend fun list(offset: Int = 0): PatternPage {
        val json = JSONObject(request("GET", "?offset=$offset&limit=30"))
        val list = json.getJSONArray("patterns")
        return PatternPage((0 until list.length()).map { PatternSummary.from(list.getJSONObject(it)) }, json.getInt("total"))
    }
    suspend fun get(id: String): JSONObject {
        require(id.matches(Regex("[0-9a-f]{12}")))
        return JSONObject(request("GET", "/$id")).also { playbackCommand(it) }
    }
    suspend fun delete(id: String) {
        require(id.matches(Regex("[0-9a-f]{12}")))
        request("DELETE", "/$id")
    }
    private suspend fun request(method: String, suffix: String): String = suspendCancellableCoroutine { continuation ->
        val request = Request.Builder().url(ConnectionConfig.resolveHttpPath(config.normalizedBaseUrl, "/patterns") + suffix)
            .header("Authorization", "Bearer ${config.token.trim()}").header("Accept", "application/json")
            .method(method, null).build()
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(IOException("无法连接波形库，请检查网络和服务器"))
            }
            override fun onResponse(call: Call, response: Response) {
                val result = runCatching {
                    response.use {
                        check(it.isSuccessful) {
                            when (it.code) {
                                401, 403 -> "波形库认证失败，请确认手机与 AI 使用同一用户的 Token"
                                404 -> "波形不存在，或服务器尚未升级到支持客户端波形库的版本"
                                429 -> "请求过于频繁，请稍后刷新"
                                else -> "波形库请求失败（HTTP ${it.code}）"
                            }
                        }
                        val source = it.body?.source() ?: error("波形库响应为空")
                        source.request(2_000_001)
                        check(source.buffer.size <= 2_000_000) { "波形库响应过大" }
                        source.readUtf8()
                    }
                }
                if (continuation.isActive) result.fold({ continuation.resume(it) }, { continuation.resumeWithException(it) })
            }
        })
    }
}

data class PatternPage(val patterns: List<PatternSummary>, val total: Int)
data class PatternSummary(val id: String, val name: String, val description: String, val steps: Int, val repeat: Int, val totalMs: Long) {
    companion object {
        fun from(j: JSONObject) = PatternSummary(j.getString("id"), j.getString("name"), j.optString("description"),
            j.getInt("step_count"), j.getInt("repeat"), j.getLong("total_ms"))
    }
}

/** Validate before IPC; the dispatcher independently validates again. Scale is applied there exactly once. */
fun playbackCommand(pattern: JSONObject): JSONObject {
    val steps = pattern.getJSONArray("steps")
    val repeat = pattern.getInt("repeat")
    val scale = pattern.getDouble("intensity_scale")
    require(pattern.getDouble("repeat") == repeat.toDouble())
    require(steps.length() in 1..128 && repeat in 1..60 && scale.isFinite() && scale in 0.0..1.0)
    var total = 0L
    for (i in 0 until steps.length()) {
        val step = steps.getJSONObject(i)
        val ms = step.getLong("duration_ms")
        require(ms in 100..600_000 && step.getDouble("duration_ms") == ms.toDouble())
        total += ms
        for (key in listOf("vibrate", "constrict")) {
            val value = step.optDouble(key, 0.0)
            require(value.isFinite() && value in 0.0..1.0)
        }
        if (!step.isNull("constrict_mode")) {
            val mode = step.getDouble("constrict_mode")
            require(mode in 1.0..8.0 && mode == mode.toInt().toDouble())
        }
    }
    require(total * repeat <= 600_000)
    val device = pattern.optString("device", "yingti")
    require(device == "yingti" || device == "all") { "本客户端暂不支持此波形的设备" }
    return JSONObject(pattern.toString()).put("type", "custom_pattern").put("device", device)
}
