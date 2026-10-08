package com.yingti.app.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionConfigTest {
    @Test fun defaultsToHttpsAndDerivesEndpoints() {
        val config = ConnectionConfig(serverBaseUrl = "example.com", token = "secret")
        assertEquals("https://example.com", config.normalizedBaseUrl)
        assertEquals("https://example.com/mcp", config.mcpUrl)
        assertEquals("wss://example.com/ws/phone", config.websocketUrl)
        assertFalse(config.usesCleartext)
    }

    @Test fun preservesBasePathAndSupportsHttp() {
        val config = ConnectionConfig(
            serverBaseUrl = "http://192.168.1.2:8420/bridge/",
            token = "secret",
            mcpPath = "api/mcp",
            relayPath = "/relay/phone",
        )
        assertEquals("http://192.168.1.2:8420/bridge", config.normalizedBaseUrl)
        assertEquals("http://192.168.1.2:8420/bridge/api/mcp", config.mcpUrl)
        assertEquals("ws://192.168.1.2:8420/bridge/relay/phone", config.websocketUrl)
        assertTrue(config.usesCleartext)
    }

    @Test fun rejectsQueryAndMissingCredentials() {
        assertThrows(IllegalArgumentException::class.java) {
            ConnectionConfig.normalizeBaseUrl("https://example.com?token=bad")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ConnectionConfig(serverBaseUrl = "https://example.com", token = "").validate()
        }
        assertThrows(IllegalArgumentException::class.java) {
            ConnectionConfig(
                serverBaseUrl = "https://example.com",
                authMode = AuthMode.ACCOUNT,
                username = "user",
            ).validate(password = "")
        }
    }

    @Test fun generatesRikkaHubStreamableHttpConfig() {
        val json = ConnectionConfig(serverBaseUrl = "example.com", token = "abc123").rikkaHubJson()
        assertTrue(json.contains("\"type\": \"streamableHttp\""))
        assertTrue(json.contains("https://example.com/mcp"))
        assertTrue(json.contains("Bearer abc123"))
    }

    @Test fun websocketConversionDoesNotDependOnHttpUrlSupportingWsSchemes() {
        assertEquals(
            "wss://example.com/base/ws/phone",
            ConnectionConfig.resolveWebSocketPath("https://example.com/base", "/ws/phone"),
        )
        assertEquals(
            "ws://127.0.0.1:8420/ws/phone",
            ConnectionConfig.resolveWebSocketPath("http://127.0.0.1:8420", "/ws/phone"),
        )
    }

    @Test fun escapesCredentialsInGeneratedJson() {
        val json = ConnectionConfig(serverBaseUrl = "example.com", token = "a\"b\\c").rikkaHubJson()
        assertTrue(json.contains("Bearer a\\\"b\\\\c"))
    }

    @Test fun phoneTokenFormatIsCheckedWithAHelpfulReason() {
        assertEquals(null, CredentialFormat.phoneTokenProblem(" aiu_phone_" + "a".repeat(32) + " "))
        assertTrue(CredentialFormat.phoneTokenProblem("aiu_agent_" + "a".repeat(32))!!.contains("AI"))
        assertTrue(CredentialFormat.phoneTokenProblem("legacy-static-token-value")!!.contains("已停用"))
        assertTrue(CredentialFormat.phoneTokenProblem("   ")!!.contains("不能为空"))
    }

    @Test fun tokenNamesAreTrimmedToTheServerLimit() {
        assertEquals("RikkaHub", CredentialFormat.tokenName("  RikkaHub "))
        assertEquals(64, CredentialFormat.tokenName("名".repeat(80)).length)
    }

    @Test fun shortDateKeepsTheDayOnly() {
        assertEquals("2026-10-08", shortDate("2026-10-08T01:02:03.456+00:00"))
        assertEquals("", shortDate(null))
    }
}
