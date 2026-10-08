package com.yingti.app.auth

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class TokenStore(context: Context) : SessionStorage {
    private val prefs = EncryptedSharedPreferences.create(
        context,
        "yingti_secure",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    /** The phone token (`aiu_phone_…`): relay and waveform library. */
    var token: String?
        get() = prefs.getString("token", null)
        set(value) = prefs.edit().apply { if (value == null) remove("token") else putString("token", value) }.apply()

    /** Login session (account mode): AI 接入 and admin pages. Expires after 24 h. */
    override var sessionToken: String?
        get() = prefs.getString("session_token", null)
        set(value) = prefs.edit().apply { if (value.isNullOrBlank()) remove("session_token") else putString("session_token", value) }.apply()

    override var isAdmin: Boolean
        get() = prefs.getBoolean("is_admin", false)
        set(value) = prefs.edit().putBoolean("is_admin", value).apply()

    /** Server id of this phone's own token, so the AI 接入 page can tell it apart. */
    var phoneTokenId: String?
        get() = prefs.getString("phone_token_id", null)
        set(value) = prefs.edit().apply { if (value.isNullOrBlank()) remove("phone_token_id") else putString("phone_token_id", value) }.apply()

    override var username: String
        get() = prefs.getString("username", "") ?: ""
        set(value) = prefs.edit().putString("username", value).apply()

    override var serverBaseUrl: String
        get() = prefs.getString("server", "") ?: ""
        set(value) = prefs.edit().putString("server", value.takeIf { it.isBlank() } ?: normalizeBaseUrl(value)).apply()

    /** New installs default to account login; existing installs keep what they saved. */
    override var authMode: AuthMode
        get() = runCatching { AuthMode.valueOf(prefs.getString("auth_mode", AuthMode.ACCOUNT.name)!!) }
            .getOrDefault(AuthMode.ACCOUNT)
        set(value) = prefs.edit().putString("auth_mode", value.name).apply()

    var mcpPath: String
        get() = prefs.getString("mcp_path", ConnectionConfig.DEFAULT_MCP_PATH) ?: ConnectionConfig.DEFAULT_MCP_PATH
        set(value) = prefs.edit().putString("mcp_path", ConnectionConfig.normalizePath(value, "MCP Path")).apply()

    var relayPath: String
        get() = prefs.getString("relay_path", ConnectionConfig.DEFAULT_RELAY_PATH) ?: ConnectionConfig.DEFAULT_RELAY_PATH
        set(value) = prefs.edit().putString("relay_path", ConnectionConfig.normalizePath(value, "Phone Relay Path")).apply()

    /** 记住密码（可选）：仅账号模式使用，加密存储；赋空值即清除。 */
    override var savedPassword: String
        get() = prefs.getString("saved_password", "") ?: ""
        set(value) = prefs.edit().apply { if (value.isBlank()) remove("saved_password") else putString("saved_password", value) }.apply()

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
        sessionToken = result.sessionToken
        isAdmin = result.isAdmin
        phoneTokenId = result.phoneTokenId
    }

    /** 退出连接: forget every credential, keep the server address and username. */
    fun clearSession() {
        token = null
        sessionToken = null
        phoneTokenId = null
        isAdmin = false
    }

    fun clearAll() {
        prefs.edit().clear().apply()
    }

    companion object {
        fun normalizeBaseUrl(value: String): String = ConnectionConfig.normalizeBaseUrl(value)
    }
}