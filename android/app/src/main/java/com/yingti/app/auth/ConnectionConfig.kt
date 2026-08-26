package com.yingti.app.auth

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

enum class AuthMode { TOKEN, ACCOUNT }

data class ConnectionConfig(
    val serverBaseUrl: String,
    val authMode: AuthMode = AuthMode.TOKEN,
    val token: String = "",
    val username: String = "",
    val mcpPath: String = DEFAULT_MCP_PATH,
    val relayPath: String = DEFAULT_RELAY_PATH,
) {
    val normalizedBaseUrl: String get() = normalizeBaseUrl(serverBaseUrl)
    val mcpUrl: String get() = resolveHttpPath(normalizedBaseUrl, mcpPath)
    val websocketUrl: String get() = resolveWebSocketPath(normalizedBaseUrl, relayPath)
    val usesCleartext: Boolean get() = normalizedBaseUrl.startsWith("http://")

    fun validate(password: String = "") {
        normalizeBaseUrl(serverBaseUrl)
        normalizePath(mcpPath, "MCP Path")
        normalizePath(relayPath, "Phone Relay Path")
        when (authMode) {
            AuthMode.TOKEN -> require(token.isNotBlank()) { "Bearer Token 不能为空" }
            AuthMode.ACCOUNT -> {
                require(username.isNotBlank()) { "用户名不能为空" }
                require(password.isNotBlank() || token.isNotBlank()) { "密码不能为空" }
            }
        }
    }

    fun rikkaHubJson(tokenOverride: String = token): String {
        val escapedUrl = escapeJson(mcpUrl)
        val escapedAuthorization = escapeJson("Bearer ${tokenOverride.trim()}")
        return """{
  "mcpServers": {
    "yingti": {
      "type": "streamableHttp",
      "url": "$escapedUrl",
      "headers": {
        "Authorization": "$escapedAuthorization"
      }
    }
  }
}"""
    }

    companion object {
        const val DEFAULT_MCP_PATH = "/mcp"
        const val DEFAULT_RELAY_PATH = "/ws/phone"

        fun normalizeBaseUrl(value: String): String {
            val trimmed = value.trim().trimEnd('/')
            require(trimmed.isNotBlank()) { "服务器地址不能为空" }
            val withScheme = if (trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true)) {
                trimmed
            } else {
                "https://$trimmed"
            }
            val url = withScheme.toHttpUrlOrNull() ?: error("服务器地址格式无效")
            require(url.scheme == "http" || url.scheme == "https") { "服务器仅支持 HTTP 或 HTTPS" }
            require(url.query == null && url.fragment == null) { "服务器地址不能包含查询参数或锚点" }
            return url.toString().trimEnd('/')
        }

        fun normalizePath(value: String, label: String = "Path"): String {
            val path = value.trim()
            require(path.isNotBlank()) { "$label 不能为空" }
            require('?' !in path && '#' !in path) { "$label 不能包含查询参数或锚点" }
            return "/" + path.trim('/')
        }

        fun resolveHttpPath(baseUrl: String, path: String): String {
            val base = normalizeBaseUrl(baseUrl).toHttpUrlOrNull() ?: error("服务器地址格式无效")
            return appendPath(base, normalizePath(path)).toString()
        }

        fun resolveWebSocketPath(baseUrl: String, path: String): String {
            val httpUrl = resolveHttpPath(baseUrl, path)
            return when {
                httpUrl.startsWith("https://") -> "wss://${httpUrl.removePrefix("https://")}" 
                httpUrl.startsWith("http://") -> "ws://${httpUrl.removePrefix("http://")}" 
                else -> error("服务器地址格式无效")
            }
        }

        private fun escapeJson(value: String): String = buildString(value.length + 8) {
            value.forEach { char ->
                when (char) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\b' -> append("\\b")
                    '\u000C' -> append("\\f")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> if (char.code < 0x20) append("\\u%04x".format(char.code)) else append(char)
                }
            }
        }

        private fun appendPath(base: HttpUrl, path: String): HttpUrl {
            val baseSegments = base.encodedPathSegments.filter { it.isNotBlank() }
            val extraSegments = path.trim('/').split('/').filter { it.isNotBlank() }
            return base.newBuilder().encodedPath("/").apply {
                (baseSegments + extraSegments).forEach(::addEncodedPathSegment)
            }.build()
        }
    }
}

data class ConnectionTestResult(
    val config: ConnectionConfig,
    val token: String,
    val username: String,
    val message: String,
)
