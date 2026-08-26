package com.yingti.app.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
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
            val request = Request.Builder().url("${TokenStore.normalizeBaseUrl(baseUrl)}/auth/login").post(body).build()
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val detail = runCatching { JSONObject(text).optString("detail") }.getOrNull()
                    error(detail?.takeIf { it.isNotBlank() } ?: "登录失败（HTTP ${response.code}）")
                }
                val json = JSONObject(text)
                LoginResult(json.getString("token"), json.optString("username", username))
            }
        }
    }

    suspend fun health(baseUrl: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            client.newCall(Request.Builder().url("${TokenStore.normalizeBaseUrl(baseUrl)}/health").build())
                .execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }
}
