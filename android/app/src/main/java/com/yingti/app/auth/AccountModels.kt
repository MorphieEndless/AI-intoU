package com.yingti.app.auth

import org.json.JSONArray
import org.json.JSONObject

/**
 * Credential families the server issues (it never accepts one in place of another):
 *  - phone token `aiu_phone_…`: this App's relay connection and waveform library;
 *  - agent token `aiu_agent_…`: an AI client's MCP connection;
 *  - session (login, 24 h): managing tokens and, for the owner, invites and accounts.
 */
object CredentialFormat {
    const val PHONE_PREFIX = "aiu_phone_"
    const val AGENT_PREFIX = "aiu_agent_"

    /** Why [token] can't be used as this App's relay credential, or null if it looks right. */
    fun phoneTokenProblem(token: String): String? {
        val value = token.trim()
        return when {
            value.isEmpty() -> "手机 Token 不能为空"
            value.startsWith(AGENT_PREFIX) ->
                "这是 AI 接入 token（aiu_agent_ 开头），要填在 AI 客户端里。App 需要 aiu_phone_ 开头的手机 token，或者改用账号登录"
            !value.startsWith(PHONE_PREFIX) ->
                "旧版 Bearer Token 已停用：请改用账号登录，或填写 aiu_phone_ 开头的手机 token"
            else -> null
        }
    }

    /** The server limits token names to 64 characters. */
    fun tokenName(raw: String): String = raw.trim().take(64)
}

/** Presets for 新建 AI 接入: the token's default name and which config snippet the client takes. */
enum class AiPlatform(val label: String, val defaultName: String, val usesMcpRemote: Boolean, val hint: String) {
    RIKKAHUB("RikkaHub", "RikkaHub", false, "在 RikkaHub 的 MCP 设置里新增服务器：类型选 Streamable HTTP，填 MCP URL 和 Authorization 请求头。"),
    CHERRY("Cherry Studio", "Cherry Studio", false, "在 Cherry Studio 的 MCP 服务器设置里新增：类型选 Streamable HTTP，填 MCP URL 和 Authorization 请求头。"),
    CLAUDE_DESKTOP("Claude Desktop", "Claude Desktop", true, "Claude Desktop 只能启动本地 MCP：把 JSON 合并进 claude_desktop_config.json（需要电脑上装有 Node.js），然后重启 Claude。"),
    OTHER("其他", "", false, "在客户端里新增一个 Streamable HTTP 类型的 MCP 服务器，填 URL 和 Authorization 请求头。");

    /** The config snippet to copy for this client. */
    fun configJson(config: ConnectionConfig, token: String): String =
        if (usesMcpRemote) config.mcpRemoteJson(token) else config.rikkaHubJson(token)
}

/** A REST call failed; [message] is the server's own (Chinese) explanation when it sent one. */
class ApiException(val status: Int, message: String) : IllegalStateException(message)

/** The session expired and no saved password could renew it: ask the person. */
class NeedPasswordException(message: String = "登录已过期，请输入密码继续") : IllegalStateException(message)

data class LoginResult(
    val token: String,
    val username: String,
    val isAdmin: Boolean = false,
    val userId: String = "",
) {
    companion object {
        fun from(json: JSONObject, fallbackUsername: String) = LoginResult(
            token = json.getString("token"),
            username = json.optString("username", fallbackUsername),
            isAdmin = json.optBoolean("is_admin", false),
            userId = json.optString("user_id", ""),
        )
    }
}

data class TokenInfo(
    val id: String,
    val name: String,
    val kind: String,
    val prefix: String,
    val state: String,
    val createdAt: String,
    val lastUsedAt: String?,
) {
    val isActive: Boolean get() = state == "active"

    companion object {
        fun from(json: JSONObject) = TokenInfo(
            id = json.getString("id"),
            name = json.optString("name"),
            kind = json.optString("kind"),
            prefix = json.optString("prefix"),
            state = json.optString("state", "active"),
            createdAt = json.optString("created_at"),
            lastUsedAt = json.optStringOrNull("last_used_at"),
        )
    }
}

data class CreatedToken(val token: String, val info: TokenInfo, val replaced: Int = 0) {
    companion object {
        fun from(json: JSONObject) = CreatedToken(
            token = json.getString("token"),
            info = TokenInfo.from(json.getJSONObject("info")),
            replaced = json.optInt("replaced", 0),
        )
    }
}

data class InviteInfo(
    val id: String,
    val prefix: String,
    val note: String,
    val maxUses: Int,
    val usedCount: Int,
    val usedBy: List<String>,
    val state: String,
    val createdAt: String,
    val expiresAt: String?,
) {
    companion object {
        fun from(json: JSONObject) = InviteInfo(
            id = json.getString("id"),
            prefix = json.optString("prefix"),
            note = json.optString("note"),
            maxUses = json.optInt("max_uses", 1),
            usedCount = json.optInt("used_count", 0),
            usedBy = json.optJSONArray("used_by").strings(),
            state = json.optString("state", "active"),
            createdAt = json.optString("created_at"),
            expiresAt = json.optStringOrNull("expires_at"),
        )
    }
}

data class CreatedInvite(val code: String, val info: InviteInfo) {
    companion object {
        fun from(json: JSONObject) = CreatedInvite(json.getString("code"), InviteInfo.from(json.getJSONObject("info")))
    }
}

data class UserInfo(
    val id: String,
    val username: String,
    val isAdmin: Boolean,
    val isActive: Boolean,
    val phoneOnline: Boolean,
    val activeTokens: Map<String, Int>,
    val createdAt: String,
) {
    companion object {
        fun from(json: JSONObject): UserInfo {
            val counts = json.optJSONObject("active_tokens")
            return UserInfo(
                id = json.getString("id"),
                username = json.optString("username"),
                isAdmin = json.optBoolean("is_admin", false),
                isActive = json.optBoolean("is_active", true),
                phoneOnline = json.optBoolean("phone_online", false),
                activeTokens = counts?.keys()?.asSequence()?.associateWith { counts.optInt(it) }.orEmpty(),
                createdAt = json.optString("created_at"),
            )
        }
    }
}

internal fun JSONObject.optStringOrNull(key: String): String? =
    if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

internal fun JSONArray?.strings(): List<String> =
    if (this == null) emptyList() else (0 until length()).map { optString(it) }.filter { it.isNotBlank() }

/** "2026-10-08T03:04:05.123+00:00" → "2026-10-08" — enough for a list row. */
fun shortDate(iso: String?): String = iso?.take(10).orEmpty()
