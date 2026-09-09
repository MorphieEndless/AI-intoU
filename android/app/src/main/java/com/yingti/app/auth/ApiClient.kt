package com.yingti.app.auth

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class ApiClient {
    private val jsonType = "application/json; charset=utf-8".toMediaType()
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    data class LoginResult(val token: String, val username: String)

    suspend fun login(baseUrl: String, username: String, password: String): Result<LoginResult> = withContext(Dispatchers.IO) {
        runCatching {
            val body = JSONObject().put("username", username).put("password", password)
                .toString().toRequestBody(jsonType)
            val request = Request.Builder()
                .url(ConnectionConfig.resolveHttpPath(baseUrl, "/auth/login"))
                .post(body)
                .build()
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val detail = runCatching {
                        JSONObject(text).let { json ->
                            sequenceOf("detail", "error", "message")
                                .map { key -> json.optString(key) }
                                .firstOrNull { it.isNotBlank() }
                        }
                    }.getOrNull()
                    error(detail ?: "登录失败（HTTP ${response.code}）")
                }
                val json = JSONObject(text)
                LoginResult(json.getString("token"), json.optString("username", username))
            }
        }
    }

    suspend fun testConnection(config: ConnectionConfig, password: String = ""): Result<ConnectionTestResult> = withContext(Dispatchers.IO) {
        runCatching {
            config.validate(password)
            checkHealth(config.normalizedBaseUrl)

            val credentials = when (config.authMode) {
                AuthMode.TOKEN -> LoginResult(config.token.trim(), config.username.trim())
                AuthMode.ACCOUNT -> login(config.normalizedBaseUrl, config.username.trim(), password).getOrThrow()
            }
            verifyPhoneRelay(config.websocketUrl, credentials.token)
            ConnectionTestResult(
                config = config.copy(token = credentials.token, username = credentials.username),
                token = credentials.token,
                username = credentials.username,
                message = "连接成功：Health 与 Phone Relay 均已通过",
            )
        }
    }

    suspend fun health(baseUrl: String): Boolean = withContext(Dispatchers.IO) {
        runCatching { checkHealth(baseUrl) }.isSuccess
    }

    private fun checkHealth(baseUrl: String) {
        val url = ConnectionConfig.resolveHttpPath(baseUrl, "/health")
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) error("Health 检查失败（HTTP ${response.code}）")
        }
    }

    private suspend fun verifyPhoneRelay(websocketUrl: String, token: String) {
        val outcome = CompletableDeferred<Result<Unit>>()
        var socket: WebSocket? = null
        socket = client.newWebSocket(Request.Builder().url(websocketUrl).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(JSONObject().put("type", "phone_auth").put("token", token).toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val message = runCatching { JSONObject(text) }.getOrNull() ?: return
                when (message.optString("type")) {
                    "auth_ok" -> outcome.complete(Result.success(Unit))
                    "auth_error", "error" -> outcome.complete(
                        Result.failure(IllegalStateException(message.optString("message", message.optString("detail", "Relay 认证失败"))))
                    )
                    "heartbeat_ping", "ping" -> webSocket.send(
                        JSONObject().put("type", "heartbeat_pong")
                            .put("timestamp", message.optDouble("timestamp", System.currentTimeMillis() / 1000.0)).toString()
                    )
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                outcome.complete(Result.failure(IllegalStateException(t.message ?: "无法连接 Phone Relay")))
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!outcome.isCompleted) outcome.complete(Result.failure(IllegalStateException("Phone Relay 已关闭（$code）")))
            }
        })
        try {
            withTimeout(12_000) { outcome.await().getOrThrow() }
        } finally {
            socket?.cancel()
        }
    }
}
