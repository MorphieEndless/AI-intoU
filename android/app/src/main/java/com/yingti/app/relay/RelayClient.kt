package com.yingti.app.relay

import com.yingti.app.AppState
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.min

class RelayClient(
    private val scope: CoroutineScope,
    private val dispatcher: RelayCommands,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .build(),
    private val onDisconnected: suspend () -> Unit,
) {
    private var connection: Connection? = null
    private var reconnectJob: Job? = null
    private var running = false
    private var url = ""
    private var token = ""
    private var attempts = 0

    @Synchronized
    fun start(url: String, token: String) {
        check(!running) { "Relay is already started" }
        this.url = url
        this.token = token
        running = true
        connect()
    }

    @Synchronized
    private fun connect() {
        if (!running || connection != null) return
        AppState.update { it.copy(relayStatus = "正在连接…") }
        val next = Connection()
        connection = next // listener ownership exists before asynchronous callbacks
        next.socket = http.newWebSocket(Request.Builder().url(url).build(), next)
    }

    private inner class Connection : WebSocketListener() {
        var socket: WebSocket? = null
        val inbox = Channel<JSONObject>(64)
        val worker = scope.launch {
            for (msg in inbox) {
                val ack = dispatcher.dispatch(msg)
                if (isCurrent(this@Connection)) socket?.send(ack.toString())
                if (msg.optString("type") == "scan") {
                    // Don't block the command inbox waiting for a device refresh.
                    scope.launch {
                        delay(750)
                        if (isCurrent(this@Connection)) socket?.send(dispatcher.deviceList().toString())
                    }
                }
            }
        }

        override fun onOpen(webSocket: WebSocket, response: Response) {
            synchronized(this@RelayClient) {
                if (!isCurrent(this)) { webSocket.cancel(); return }
                webSocket.send(JSONObject().put("type", "phone_auth").put("token", token).toString())
                AppState.update { it.copy(relayStatus = "正在认证…") }
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            synchronized(this@RelayClient) {
                if (!isCurrent(this)) return
                val msg = runCatching { JSONObject(text) }.getOrNull() ?: return
                when (msg.optString("type")) {
                    "auth_ok" -> {
                        attempts = 0
                        AppState.update { it.copy(relayStatus = "已连接", error = null) }
                    }
                    "heartbeat_ping", "ping" -> webSocket.send(
                        JSONObject().put("type", "heartbeat_pong")
                            .put("timestamp", msg.optDouble("timestamp", System.currentTimeMillis() / 1000.0)).toString()
                    )
                    "command", "pattern", "custom_pattern", "stop", "read_sensor", "scan" -> {
                        if (msg.optString("type") == "stop") {
                            while (true) {
                                val queued = inbox.tryReceive().getOrNull() ?: break
                                reject(webSocket, queued, "Cancelled by stop")
                            }
                        }
                        if (inbox.trySend(msg).isFailure) reject(webSocket, msg, "Command queue is full")
                    }
                }
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, reason)
        }
        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = disconnected(this, "已断开 ($code)")
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = disconnected(this, t.message ?: "连接失败")

        fun cancel() {
            inbox.cancel()
            worker.cancel()
            socket?.cancel()
        }
    }

    @Synchronized
    private fun isCurrent(candidate: Connection) = running && connection === candidate

    @Synchronized
    private fun disconnected(owner: Connection, reason: String) {
        if (!isCurrent(owner)) return
        connection = null
        owner.cancel()
        AppState.update { it.copy(relayStatus = "已断开", error = reason) }
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            // Old commands and failsafe complete before a replacement accepts work.
            owner.worker.join()
            onDisconnected()
            attempts++
            delay(min(30_000L, 2_000L * attempts))
            connect()
        }
    }

    private fun reject(socket: WebSocket, message: JSONObject, reason: String) {
        socket.send(JSONObject().put("type", "command_ack").put("success", false)
            .put("message", reason).apply {
                if (message.has("request_id")) put("request_id", message.get("request_id"))
            }.toString())
    }

    @Synchronized
    fun sendPhoneEmergencyStop() {
        connection?.socket?.send(JSONObject().put("type", "phone_emergency_stop").toString())
    }

    @Synchronized
    fun stop() {
        running = false
        reconnectJob?.cancel()
        connection?.cancel()
        connection = null
        // OkHttp owns thread lifetime; cancelling sockets is enough and permits reuse.
    }
}
