package com.yingti.app.auth

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class AccountSessionTest {
    private class FakeStore(
        override var sessionToken: String? = null,
        override var isAdmin: Boolean = false,
        override val username: String = "alice",
        override val savedPassword: String = "",
        override val serverBaseUrl: String,
        override val authMode: AuthMode = AuthMode.ACCOUNT,
    ) : SessionStorage

    private lateinit var server: MockWebServer
    private val requests = CopyOnWriteArrayList<RecordedRequest>()
    private var validSession = "fresh-session"
    private var loginStatus = 200
    private var admin = true

    @Before fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                return when (request.path) {
                    "/auth/login" -> if (loginStatus != 200) json("""{"detail":"账号已停用"}""", loginStatus)
                        else json("""{"user_id":"u1","username":"alice","token":"$validSession","is_admin":$admin}""")
                    "/api/tokens" -> if (request.getHeader("Authorization") == "Bearer $validSession") json("""{"tokens":[]}""")
                        else json("""{"detail":"未登录或凭证已失效，请重新登录"}""", 401)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
    }

    @After fun tearDown() = server.shutdown()

    private fun json(body: String, code: Int = 200) = MockResponse().setResponseCode(code).setBody(body)
    private fun store(session: String? = null, saved: String = "", mode: AuthMode = AuthMode.ACCOUNT) =
        FakeStore(sessionToken = session, savedPassword = saved, serverBaseUrl = server.url("/").toString(), authMode = mode)
    private fun logins() = requests.count { it.path == "/auth/login" }

    @Test fun aValidSessionIsUsedAsIs() = runBlocking {
        val store = store(session = validSession)
        AccountSession(ApiClient(), store).tokens()
        assertEquals(0, logins())
    }

    @Test fun anExpiredSessionIsRenewedWithTheSavedPasswordAndRetried() = runBlocking {
        val store = store(session = "expired", saved = "pw-123456789")
        assertEquals(emptyList<TokenInfo>(), AccountSession(ApiClient(), store).tokens())
        assertEquals(1, logins())
        assertEquals(validSession, store.sessionToken)
        assertTrue(store.isAdmin)
        val login = JSONObject(requests.first { it.path == "/auth/login" }.body.readUtf8())
        assertEquals("alice", login.getString("username"))
    }

    @Test fun withoutASavedPasswordThePersonIsAsked() = runBlocking {
        val store = store(session = "expired")
        try {
            AccountSession(ApiClient(), store).tokens(); fail("expected NeedPasswordException")
        } catch (_: NeedPasswordException) {
        }
        assertEquals(null, store.sessionToken) // the dead session is dropped
        assertEquals(0, logins())
    }

    @Test fun aChangedPasswordFallsBackToAsking() = runBlocking {
        loginStatus = 401
        try {
            AccountSession(ApiClient(), store(saved = "old-password")).tokens(); fail("expected NeedPasswordException")
        } catch (_: NeedPasswordException) {
        }
    }

    @Test fun aDisabledAccountIsReportedNotAskedAbout() = runBlocking {
        loginStatus = 403
        try {
            AccountSession(ApiClient(), store(saved = "pw-123456789")).tokens(); fail("expected ApiException")
        } catch (e: ApiException) {
            assertEquals(403, e.status)
            assertEquals("账号已停用", e.message)
        }
    }

    @Test fun reloginStoresTheNewSession() = runBlocking {
        admin = false
        val store = store(session = "expired")
        val session = AccountSession(ApiClient(), store)
        session.relogin("typed-password")
        assertEquals(validSession, store.sessionToken)
        assertEquals(false, store.isAdmin)
        session.tokens()
        assertEquals(1, logins())
    }

    @Test fun tokenModeNeverTriesToLogIn() = runBlocking {
        try {
            AccountSession(ApiClient(), store(saved = "pw-123456789", mode = AuthMode.TOKEN)).tokens()
            fail("expected NeedPasswordException")
        } catch (_: NeedPasswordException) {
        }
        assertTrue(requests.isEmpty())
    }
}
