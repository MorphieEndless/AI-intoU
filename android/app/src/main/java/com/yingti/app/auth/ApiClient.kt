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

class ApiClient(private val client: OkHttpClient = defaultClient()) {
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    // ════════════════════════════════════════════════════════════════════
    // Connect: what the settings page's 登入 / 注册 buttons run
    // ════════════════════════════════════════════════════════════════════

    /**
     * TOKEN mode: the pasted phone token must open the relay.
     * ACCOUNT mode: log in, mint this phone's own phone token, prove it opens the relay.
     * [deviceLabel] names the phone token (one per device; re-login replaces it).
     */
    suspend fun testConnection(
        config: ConnectionConfig,
        password: String = "",
        deviceLabel: String = DEFAULT_DEVICE_LABEL,
    ): Result<ConnectionTestResult> = withContext(Dispatchers.IO) {
        runCatching {
            config.validate(password)
            when (config.authMode) {
                AuthMode.TOKEN -> {
                    CredentialFormat.phoneTokenProblem(config.token)?.let { throw IllegalArgumentException(it) }
                    checkHealth(config.normalizedBaseUrl)
                    val token = config.token.trim()
                    verifyPhoneRelay(config.websocketUrl, token)
                    ConnectionTestResult(
                        config = config.copy(token = token),
                        token = token,
                        username = config.username.trim(),
                        message = "连接成功：Health 与 Phone Relay 均已通过",
                    )
                }
                AuthMode.ACCOUNT -> {
                    checkHealth(config.normalizedBaseUrl)
                    val session = login(config.normalizedBaseUrl, config.username.trim(), password).getOrThrow()
                    pairPhone(config, session, deviceLabel, "连接成功：已登录并为这台手机签发专用 token")
                }
            }
        }
    }

    /** Invite registration, then the same pairing as an account login. */
    suspend fun registerAndConnect(
        config: ConnectionConfig,
        inviteCode: String,
        password: String,
        deviceLabel: String = DEFAULT_DEVICE_LABEL,
    ): Result<ConnectionTestResult> = withContext(Dispatchers.IO) {
        runCatching {
            val account = config.copy(authMode = AuthMode.ACCOUNT)
            account.validate(password)
            require(inviteCode.isNotBlank()) { "请填写邀请码" }
            checkHealth(account.normalizedBaseUrl)
            val session = register(account.normalizedBaseUrl, inviteCode, account.username.trim(), password).getOrThrow()
            pairPhone(account, session, deviceLabel, "注册成功：欢迎，${session.username}！")
        }
    }

    private suspend fun pairPhone(
        config: ConnectionConfig,
        session: LoginResult,
        deviceLabel: String,
        message: String,
    ): ConnectionTestResult {
        val phone = try {
            mintToken(config.normalizedBaseUrl, session.token, deviceLabel, "phone", replaceExisting = true).getOrThrow()
        } catch (e: ApiException) {
            if (e.status == 404) throw IllegalStateException("服务器版本过旧，不支持账号签发 token，请先升级服务器")
            throw e
        }
        verifyPhoneRelay(config.websocketUrl, phone.token)
        return ConnectionTestResult(
            config = config.copy(token = phone.token, username = session.username),
            token = phone.token,
            username = session.username,
            message = message,
            sessionToken = session.token,
            isAdmin = session.isAdmin,
            phoneTokenId = phone.info.id,
        )
    }

    // ════════════════════════════════════════════════════════════════════
    // Accounts
    // ════════════════════════════════════════════════════════════════════

    suspend fun login(baseUrl: String, username: String, password: String): Result<LoginResult> =
        withContext(Dispatchers.IO) {
            runCatching {
                val body = JSONObject().put("username", username).put("password", password)
                LoginResult.from(call("POST", baseUrl, "/auth/login", body = body, fallback = "登录失败"), username)
            }
        }

    suspend fun register(baseUrl: String, inviteCode: String, username: String, password: String): Result<LoginResult> =
        withContext(Dispatchers.IO) {
            runCatching {
                val body = JSONObject().put("username", username).put("password", password)
                    .put("invite_code", inviteCode.trim())
                LoginResult.from(call("POST", baseUrl, "/auth/register", body = body, fallback = "注册失败"), username)
            }
        }

    // ── tokens (session only) ───────────────────────────────────────────

    suspend fun listTokens(baseUrl: String, session: String): Result<List<TokenInfo>> = withContext(Dispatchers.IO) {
        runCatching {
            val list = call("GET", baseUrl, "/api/tokens", session).getJSONArray("tokens")
            (0 until list.length()).map { TokenInfo.from(list.getJSONObject(it)) }
        }
    }

    suspend fun mintToken(
        baseUrl: String,
        session: String,
        name: String,
        kind: String,
        replaceExisting: Boolean = false,
    ): Result<CreatedToken> = withContext(Dispatchers.IO) {
        runCatching {
            val body = JSONObject().put("name", CredentialFormat.tokenName(name)).put("kind", kind)
                .put("replace_existing", replaceExisting)
            CreatedToken.from(call("POST", baseUrl, "/api/tokens", session, body))
        }
    }

    suspend fun revokeToken(baseUrl: String, session: String, id: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching { call("DELETE", baseUrl, "/api/tokens/${pathId(id)}", session); Unit }
    }

    // ── admin (owner session) ───────────────────────────────────────────

    suspend fun listInvites(baseUrl: String, session: String): Result<List<InviteInfo>> = withContext(Dispatchers.IO) {
        runCatching {
            val list = call("GET", baseUrl, "/api/admin/invites", session).getJSONArray("invites")
            (0 until list.length()).map { InviteInfo.from(list.getJSONObject(it)) }
        }
    }

    suspend fun createInvite(baseUrl: String, session: String, maxUses: Int, days: Int, note: String): Result<CreatedInvite> =
        withContext(Dispatchers.IO) {
            runCatching {
                val body = JSONObject().put("max_uses", maxUses).put("expires_in_days", days).put("note", note.trim())
                CreatedInvite.from(call("POST", baseUrl, "/api/admin/invites", session, body))
            }
        }

    suspend fun revokeInvite(baseUrl: String, session: String, id: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching { call("DELETE", baseUrl, "/api/admin/invites/${pathId(id)}", session); Unit }
    }

    suspend fun listUsers(baseUrl: String, session: String): Result<List<UserInfo>> = withContext(Dispatchers.IO) {
        runCatching {
            val list = call("GET", baseUrl, "/api/admin/users", session).getJSONArray("users")
            (0 until list.length()).map { UserInfo.from(list.getJSONObject(it)) }
        }
    }

    suspend fun setUserActive(baseUrl: String, session: String, id: String, active: Boolean): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                call("POST", baseUrl, "/api/admin/users/${pathId(id)}/active", session, JSONObject().put("is_active", active))
                Unit
            }
        }

    suspend fun resetUserPassword(baseUrl: String, session: String, id: String, password: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                call("POST", baseUrl, "/api/admin/users/${pathId(id)}/password", session, JSONObject().put("password", password))
                Unit
            }
        }

    // ════════════════════════════════════════════════════════════════════
    // Transport
    // ════════════════════════════════════════════════════════════════════

    suspend fun health(baseUrl: String): Boolean = withContext(Dispatchers.IO) {
        runCatching { checkHealth(baseUrl) }.isSuccess
    }

    private fun call(
        method: String,
        baseUrl: String,
        path: String,
        bearer: String? = null,
        body: JSONObject? = null,
        fallback: String = "请求失败",
    ): JSONObject {
        val request = Request.Builder()
            .url(ConnectionConfig.resolveHttpPath(baseUrl, path))
            .header("Accept", "application/json")
            .apply { if (bearer != null) header("Authorization", "Bearer ${bearer.trim()}") }
            .method(method, body?.toString()?.toRequestBody(jsonType))
            .build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw ApiException(response.code, errorDetail(text) ?: "$fallback（HTTP ${response.code}）")
            return if (text.isBlank()) JSONObject() else JSONObject(text)
        }
    }

    /** Only a structured `detail` is shown — never a raw body (it could be a proxy's HTML page). */
    private fun errorDetail(text: String): String? = runCatching {
        JSONObject(text).let { json ->
            sequenceOf("detail", "error", "message")
                .map { key -> json.opt(key) }
                .mapNotNull { value ->
                    when (value) {
                        is String -> value
                        is JSONObject -> value.optString("message").takeIf { it.isNotBlank() }
                        else -> null
                    }
                }
                .firstOrNull { it.isNotBlank() }
        }
    }.getOrNull()

    private fun pathId(id: String): String {
        require(id.matches(Regex("[A-Za-z0-9-]{1,64}"))) { "无效的 id" }
        return id
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

    companion object {
        const val DEFAULT_DEVICE_LABEL = "樱趣 App"

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()

        /** "樱趣 App · Pixel 8" — one phone token per device, replaced on every re-login. */
        fun deviceLabel(model: String?): String =
            CredentialFormat.tokenName(if (model.isNullOrBlank()) DEFAULT_DEVICE_LABEL else "$DEFAULT_DEVICE_LABEL · ${model.trim()}")
    }
}
