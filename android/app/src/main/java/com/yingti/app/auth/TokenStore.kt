package com.yingti.app.auth

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class TokenStore(context: Context) {
    private val prefs = EncryptedSharedPreferences.create(
        context,
        "yingti_secure",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    var token: String?
        get() = prefs.getString("token", null)
        set(value) = prefs.edit().apply { if (value == null) remove("token") else putString("token", value) }.apply()

    var username: String
        get() = prefs.getString("username", "") ?: ""
        set(value) = prefs.edit().putString("username", value).apply()

    var serverBaseUrl: String
        get() = prefs.getString("server", "") ?: ""
        set(value) = prefs.edit().putString("server", value.takeIf { it.isBlank() } ?: normalizeBaseUrl(value)).apply()

    var authMode: AuthMode
        get() = runCatching { AuthMode.valueOf(prefs.getString("auth_mode", AuthMode.TOKEN.name)!!) }
            .getOrDefault(AuthMode.TOKEN)
        set(value) = prefs.edit().putString("auth_mode", value.name).apply()

    var mcpPath: String
        get() = prefs.getString("mcp_path", ConnectionConfig.DEFAULT_MCP_PATH) ?: ConnectionConfig.DEFAULT_MCP_PATH
        set(value) = prefs.edit().putString("mcp_path", ConnectionConfig.normalizePath(value, "MCP Path")).apply()

    var relayPath: String
        get() = prefs.getString("relay_path", ConnectionConfig.DEFAULT_RELAY_PATH) ?: ConnectionConfig.DEFAULT_RELAY_PATH
        set(value) = prefs.edit().putString("relay_path", ConnectionConfig.normalizePath(value, "Phone Relay Path")).apply()

    val websocketUrl: String
        get() = currentConfig().websocketUrl

    val isConfigured: Boolean
        get() = !token.isNullOrBlank() && serverBaseUrl.isNotBlank()

    fun currentConfig(): ConnectionConfig = ConnectionConfig(
        serverBaseUrl = serverBaseUrl,
        authMode = authMode,
        token = token.orEmpty(),
        username = username,
        mcpPath = mcpPath,
        relayPath = relayPath,
    )

    fun save(result: ConnectionTestResult) {
        serverBaseUrl = result.config.normalizedBaseUrl
        authMode = result.config.authMode
        mcpPath = result.config.mcpPath
        relayPath = result.config.relayPath
        username = result.username
        token = result.token
    }

    fun clearSession() {
        token = null
    }

    fun clearAll() {
        prefs.edit().clear().apply()
    }

    companion object {
        fun normalizeBaseUrl(value: String): String = ConnectionConfig.normalizeBaseUrl(value)
    }
}
