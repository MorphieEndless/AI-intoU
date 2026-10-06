package com.yingti.app.patterns

import com.yingti.app.auth.ConnectionConfig
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PatternApiTest {
    private fun pattern() = JSONObject("""{"id":"abc123456789","device":"yingti","name":"test","repeat":2,"intensity_scale":0.4,"steps":[{"duration_ms":100,"vibrate":0.5,"constrict":0.2,"constrict_mode":null}]}""")
    @Test fun preservesScaleForExactlyOneApplicationInDispatcher() {
        val command = playbackCommand(pattern())
        assertEquals("custom_pattern", command.getString("type"))
        assertEquals(0.4, command.getDouble("intensity_scale"), 0.0)
        assertEquals(0.5, command.getJSONArray("steps").getJSONObject(0).getDouble("vibrate"), 0.0)
    }
    @Test fun invalidPlaybackBoundariesRejected() {
        val bad = listOf(pattern().put("repeat", 61), pattern().put("intensity_scale", 1.1),
            pattern().put("device", "unknown"), pattern().apply { getJSONArray("steps").getJSONObject(0).put("duration_ms", 99) },
            pattern().apply { getJSONArray("steps").getJSONObject(0).put("constrict_mode", 1.5) },
            pattern().apply { getJSONArray("steps").getJSONObject(0).put("duration_ms", 600000) })
        bad.forEach { value ->
            try { playbackCommand(value); fail("invalid waveform accepted") } catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun listUsesSavedServerPrefixAndAuthorization() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setBody("""{"patterns":[],"total":0}"""))
            val api = PatternApi(ConnectionConfig(server.url("/bridge").toString(), token = "test-only-token"))
            assertEquals(0, api.list().total)
            val request = server.takeRequest()
            assertEquals("/bridge/patterns?offset=0&limit=30&include_steps=true&filter=all&q=", request.path)
            assertEquals("Bearer test-only-token", request.getHeader("Authorization"))
        } finally { server.shutdown() }
    }
    @Test fun metadataPatchUsesTypedBodyAndBuiltinId() = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            server.enqueue(MockResponse().setBody(pattern().put("id", "builtin-wave").put("is_liked", true).toString()))
            val api = PatternApi(ConnectionConfig(server.url("/bridge").toString(), token = "test-only-token"))
            assertTrue(api.patch("builtin-wave", JSONObject().put("is_liked", true)).getBoolean("is_liked"))
            val request = server.takeRequest()
            assertEquals("PATCH", request.method)
            assertEquals("/bridge/patterns/builtin-wave", request.path)
            assertTrue(JSONObject(request.body.readUtf8()).getBoolean("is_liked"))
            assertEquals("Bearer test-only-token", request.getHeader("Authorization"))
        } finally { server.shutdown() }
    }
    @Test fun searchAndFilterAreEncodedBeforePagination() = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            server.enqueue(MockResponse().setBody("""{"patterns":[],"total":0}"""))
            val api = PatternApi(ConnectionConfig(server.url("/").toString(), token = "test-only-token"))
            api.list(30, "favorites", "留白 & 呼吸")
            val url = server.takeRequest().requestUrl!!
            assertEquals("favorites", url.queryParameter("filter"))
            assertEquals("留白 & 呼吸", url.queryParameter("q"))
            assertEquals("30", url.queryParameter("offset"))
            assertEquals("true", url.queryParameter("include_steps"))
        } finally { server.shutdown() }
    }
    @Test fun restoreIsAuthenticatedPostNotPlayback() = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            server.enqueue(MockResponse().setBody(pattern().toString()))
            val api = PatternApi(ConnectionConfig(server.url("/").toString(), token = "test-only-token"))
            api.restore("abc123456789")
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/patterns/abc123456789/restore", request.path)
        } finally { server.shutdown() }
    }
    @Test fun unauthorizedFailsWithoutExposingServerBody() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(401).setBody("secret-should-not-appear"))
            val api = PatternApi(ConnectionConfig(server.url("/").toString(), token = "test-only-token"))
            try { api.list(); fail("401 accepted") }
            catch (e: IllegalStateException) { assertFalse(e.message.orEmpty().contains("secret-should-not-appear")) }
        } finally { server.shutdown() }
    }
}
