package com.yingti.app.auth

import kotlinx.coroutines.runBlocking
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A tiny fake AI-intoU server: health, login/register, the token API and a relay that
 * only accepts the phone token it handed out.
 */
class ApiClientTest {
    private lateinit var server: MockWebServer
    private val requests = CopyOnWriteArrayList<RecordedRequest>()
    private val relayTokens = CopyOnWriteArrayList<String>()
    private var mintStatus = 200
    private var loginStatus = 200
    private val phoneToken = "aiu_phone_" + "p".repeat(32)
    private val sessionToken = "session-jwt-test-only"

    private val api = ApiClient()

    @Before fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                val path = request.path.orEmpty()
                return when {
                    path == "/base/health" -> json("""{"status":"ok"}""")
                    path == "/base/auth/login" -> if (loginStatus == 200) json(session(false))
                        else json("""{"detail":"用户名或密码错误"}""", loginStatus)
                    path == "/base/auth/register" -> {
                        val body = JSONObject(request.body.clone().readUtf8())
                        if (body.optString("invite_code").isBlank()) json("""{"detail":"这个服务器需要邀请码才能注册"}""", 403)
                        else json(session(false))
                    }
                    path == "/base/api/tokens" && request.method == "POST" ->
                        if (mintStatus != 200) MockResponse().setResponseCode(mintStatus).setBody("<html>not found</html>")
                        else json("""{"token":"$phoneToken","replaced":1,"info":${tokenInfo("tok-1", "phone")}}""")
                    path == "/base/api/tokens" -> json("""{"tokens":[${tokenInfo("tok-1", "phone")},${tokenInfo("tok-2", "agent")}]}""")
                    path.startsWith("/base/api/tokens/") -> json("""{"revoked":true}""")
                    path == "/base/ws/phone" -> MockResponse().withWebSocketUpgrade(RelayListener())
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
    }

    @After fun tearDown() = server.shutdown()

    private inner class RelayListener : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            val token = JSONObject(text).optString("token")
            relayTokens += token
            if (token == phoneToken) webSocket.send("""{"type":"auth_ok","user_id":"u1"}""")
            else webSocket.send("""{"type":"auth_error","message":"凭证无效或已被撤销"}""")
        }

        override fun onOpen(webSocket: WebSocket, response: Response) {}
    }

    private fun json(body: String, code: Int = 200) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    private fun session(admin: Boolean) =
        """{"user_id":"u1","username":"alice","token":"$sessionToken","token_type":"session","expires_in_hours":24,"is_admin":$admin}"""

    private fun tokenInfo(id: String, kind: String) =
        """{"id":"$id","name":"樱趣 App · Pixel","kind":"$kind","prefix":"aiu_${kind}_ab","state":"active","created_at":"2026-10-08T01:02:03+00:00","last_used_at":null,"expires_at":null,"revoked_at":null}"""

    private fun base() = server.url("/base").toString()

    private fun account() = ConnectionConfig(base(), authMode = AuthMode.ACCOUNT, username = "alice")

    private fun findRequest(path: String) = requests.first { it.path == path }

    // ── account login pairs this phone ────────────────────────────────────

    @Test fun accountLoginMintsAPhoneTokenAndVerifiesTheRelayWithIt() = runBlocking {
        val result = api.testConnection(account(), "pw-123456789", "樱趣 App · Pixel").getOrThrow()
        assertEquals(phoneToken, result.token)
        assertEquals(phoneToken, result.config.token)
        assertEquals(sessionToken, result.sessionToken)
        assertEquals("tok-1", result.phoneTokenId)
        assertEquals("alice", result.username)
        assertFalse(result.isAdmin)
        // the relay saw the phone token, never the session
        assertEquals(listOf(phoneToken), relayTokens.toList())

        val mint = findRequest("/base/api/tokens")
        assertEquals("Bearer $sessionToken", mint.getHeader("Authorization"))
        val body = JSONObject(mint.body.readUtf8())
        assertEquals("phone", body.getString("kind"))
        assertEquals("樱趣 App · Pixel", body.getString("name"))
        assertTrue(body.getBoolean("replace_existing"))
    }

    @Test fun wrongPasswordShowsTheServersMessage() = runBlocking {
        loginStatus = 401
        val error = api.testConnection(account(), "nope-nope-nope").exceptionOrNull()
        assertEquals("用户名或密码错误", error?.message)
        assertTrue(relayTokens.isEmpty())
    }

    @Test fun anOldServerWithoutTheTokenApiIsExplained() = runBlocking {
        mintStatus = 404
        val error = api.testConnection(account(), "pw-123456789").exceptionOrNull()
        assertTrue(error?.message.orEmpty().contains("服务器版本过旧"))
        assertFalse(error?.message.orEmpty().contains("html"))
    }

    // ── invite registration ───────────────────────────────────────────────

    @Test fun registrationSendsTheInviteThenPairs() = runBlocking {
        val result = api.registerAndConnect(account(), " abcd-efgh-jkmn ", "pw-123456789").getOrThrow()
        assertEquals(phoneToken, result.token)
        assertEquals(AuthMode.ACCOUNT, result.config.authMode)
        val body = JSONObject(findRequest("/base/auth/register").body.readUtf8())
        assertEquals("abcd-efgh-jkmn", body.getString("invite_code"))
        assertEquals("alice", body.getString("username"))
        assertTrue(result.message.contains("注册成功"))
    }

    @Test fun registrationNeedsAnInviteCode() = runBlocking {
        val error = api.registerAndConnect(account(), "  ", "pw-123456789").exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        assertTrue(requests.none { it.path == "/base/auth/register" })
    }

    // ── token mode ────────────────────────────────────────────────────────

    @Test fun tokenModeUsesThePastedPhoneToken() = runBlocking {
        val config = ConnectionConfig(base(), authMode = AuthMode.TOKEN, token = " $phoneToken ")
        val result = api.testConnection(config).getOrThrow()
        assertEquals(phoneToken, result.token)
        assertNull(result.sessionToken)
        assertTrue(requests.none { it.path.orEmpty().startsWith("/base/auth") })
    }

    @Test fun tokenModeRejectsAgentAndLegacyTokensBeforeTouchingTheNetwork() = runBlocking {
        for (token in listOf("aiu_agent_" + "a".repeat(32), "legacy-static-token-0123456789abcdef")) {
            val error = api.testConnection(ConnectionConfig(base(), authMode = AuthMode.TOKEN, token = token)).exceptionOrNull()
            assertTrue(token, error is IllegalArgumentException)
        }
        assertTrue(requests.isEmpty())
    }

    // ── session-only endpoints ────────────────────────────────────────────

    @Test fun tokenListAndRevokeUseTheSession() = runBlocking {
        val tokens = api.listTokens(base(), sessionToken).getOrThrow()
        assertEquals(listOf("phone", "agent"), tokens.map { it.kind })
        assertTrue(tokens.all { it.isActive })
        assertNull(tokens.first().lastUsedAt)
        api.revokeToken(base(), sessionToken, "tok-2").getOrThrow()
        val revoke = requests.last()
        assertEquals("DELETE", revoke.method)
        assertEquals("/base/api/tokens/tok-2", revoke.path)
        assertEquals("Bearer $sessionToken", revoke.getHeader("Authorization"))
    }

    @Test fun idsAreValidatedBeforeBuildingAPath() = runBlocking {
        try {
            api.revokeToken(base(), sessionToken, "../admin").getOrThrow()
            fail("path traversal accepted")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test fun httpErrorsCarryTheStatus() = runBlocking {
        val error = api.listInvites(base(), sessionToken).exceptionOrNull()
        assertTrue(error is ApiException)
        assertEquals(404, (error as ApiException).status)
    }

    @Test fun deviceLabelIsBoundedAndHasAFallback() {
        assertEquals("樱趣 App", ApiClient.deviceLabel(null))
        assertEquals("樱趣 App · Pixel 8", ApiClient.deviceLabel(" Pixel 8 "))
        assertEquals(64, ApiClient.deviceLabel("x".repeat(100)).length)
    }
}
