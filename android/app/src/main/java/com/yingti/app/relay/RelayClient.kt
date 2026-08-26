package com.yingti.app.relay

import com.yingti.app.AppState
import kotlinx.coroutines.*
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.min

class RelayClient(
    private val scope: CoroutineScope,
    private val dispatcher: CommandDispatcher,
    private val onDisconnected: suspend () -> Unit,
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .build()
    private var socket: WebSocket? = null
    private var reconnectJob: Job? = null
    @Volatile private var running = false
    private var url = ""
    private var token = ""
    private var attempts = 0

    fun start(url: String, token: String) {
        this.url = url
        this.token = token
        running = true
        connect()
    }

    private fun connect() {
        if (!running || socket != null) return
        AppState.update { it.copy(relayStatus = "正在连接…") }
        val request = Request.Builder().url(url).build()
        socket = http.newWebSocket(request, listener)
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            attempts = 0
            webSocket.send(JSONObject().put("type", "phone_auth").put("token", token).toString())
            AppState.update { it.copy(relayStatus = "正在认证…") }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val msg = runCatching { JSONObject(text) }.getOrElse { return }
            when (msg.optString("type")) {
                "auth_ok" -> AppState.update { it.copy(relayStatus = "已连接", error = null) }
                "heartbeat_ping", "ping" -> webSocket.send(
                    JSONObject().put("type", "heartbeat_pong")
                        .put("timestamp", msg.optDouble("timestamp", System.currentTimeMillis() / 1000.0)).toString()
                )
                "command", "pattern", "stop", "read_sensor", "scan" -> scope.launch {
                    val ack = dispatcher.dispatch(msg)
                    webSocket.send(ack.toString())
                    if (msg.optString("type") == "scan") {
                        delay(750)
                        webSocket.send(dispatcher.deviceList().toString())
                    }
                }
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, reason)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = disconnected("已断开 ($code)")
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = disconnected(t.message ?: "连接失败")
    }

    @Synchronized
    private fun disconnected(reason: String) {
        socket = null
        AppState.update { it.copy(relayStatus = "已断开", error = reason) }
        scope.launch { onDisconnected() }
        if (!running || reconnectJob?.isActive == true) return
        reconnectJob = scope.launch {
            attempts++
            delay(min(30_000L, 2_000L * attempts))
            connect()
        }
    }

    fun sendPhoneEmergencyStop() {
        socket?.send(JSONObject().put("type", "phone_emergency_stop").toString())
    }

    fun stop() {
        running = false
        reconnectJob?.cancel()
        socket?.close(1000, "Service stopped")
        socket = null
        http.dispatcher.executorService.shutdown()
    }
}
