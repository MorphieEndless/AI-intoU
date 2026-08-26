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
        get() = prefs.getString("username", "morphie") ?: "morphie"
        set(value) = prefs.edit().putString("username", value).apply()

    var serverBaseUrl: String
        get() = prefs.getString("server", DEFAULT_SERVER) ?: DEFAULT_SERVER
        set(value) = prefs.edit().putString("server", normalizeBaseUrl(value)).apply()

    val websocketUrl: String
        get() = serverBaseUrl.replaceFirst("https://", "wss://")
            .replaceFirst("http://", "ws://") + "/ws/phone"

    fun clearSession() { token = null }

    companion object {
        const val DEFAULT_SERVER = "https://<YOUR_SERVER_HOST>"
        fun normalizeBaseUrl(value: String): String = value.trim().trimEnd('/').let {
            when {
                it.startsWith("https://") || it.startsWith("http://") -> it
                else -> "https://$it"
            }
        }
    }
}
