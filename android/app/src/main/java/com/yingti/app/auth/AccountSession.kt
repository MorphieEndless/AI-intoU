package com.yingti.app.auth

/** What [AccountSession] needs from storage (TokenStore in the App, a fake in tests). */
interface SessionStorage {
    var sessionToken: String?
    var isAdmin: Boolean
    val username: String
    val savedPassword: String
    val serverBaseUrl: String
    val authMode: AuthMode
}

/** Everything the AI 接入 and admin pages do. Session renewal is hidden behind it. */
interface AccountApi {
    val username: String

    suspend fun tokens(): List<TokenInfo>
    suspend fun createToken(name: String, kind: String = "agent"): CreatedToken
    suspend fun revokeToken(id: String)

    /** Renew the session with a password the person just typed. */
    suspend fun relogin(password: String)

    suspend fun invites(): List<InviteInfo>
    suspend fun createInvite(maxUses: Int, days: Int, note: String): CreatedInvite
    suspend fun revokeInvite(id: String)
    suspend fun users(): List<UserInfo>
    suspend fun setUserActive(id: String, active: Boolean)
    suspend fun resetUserPassword(id: String, password: String)
}

/**
 * Runs session-only calls. Sessions last 24 h; the phone token (relay, library)
 * never expires, so only these management pages ever need a fresh login:
 *  - no session, or the server answered 401 → log in again with the saved
 *    password (if the person chose 记住密码) and retry once;
 *  - otherwise → [NeedPasswordException]: the page asks for the password and
 *    calls [relogin].
 */
class AccountSession(
    private val api: ApiClient,
    private val store: SessionStorage,
) : AccountApi {
    override val username: String get() = store.username

    private suspend fun <T> withSession(block: suspend (base: String, session: String) -> Result<T>): T {
        if (store.authMode != AuthMode.ACCOUNT) throw NeedPasswordException("需要用账号登录才能管理")
        val base = store.serverBaseUrl
        val current = store.sessionToken
        if (!current.isNullOrBlank()) {
            val first = block(base, current)
            val error = first.exceptionOrNull()
            if (error !is ApiException || error.status != 401) return first.getOrThrow()
            store.sessionToken = null
        }
        val renewed = renewWithSavedPassword(base) ?: throw NeedPasswordException()
        return block(base, renewed).getOrThrow()
    }

    private suspend fun renewWithSavedPassword(base: String): String? {
        val password = store.savedPassword
        if (password.isBlank() || store.username.isBlank()) return null
        val result = api.login(base, store.username, password)
        val error = result.exceptionOrNull()
        if (error is ApiException && error.status == 401) return null // password changed elsewhere
        val login = result.getOrThrow() // 403 停用 / network: show as-is
        store.sessionToken = login.token
        store.isAdmin = login.isAdmin
        return login.token
    }

    override suspend fun relogin(password: String) {
        require(password.isNotBlank()) { "请输入密码" }
        val login = api.login(store.serverBaseUrl, store.username, password).getOrThrow()
        store.sessionToken = login.token
        store.isAdmin = login.isAdmin
    }

    override suspend fun tokens() = withSession { b, s -> api.listTokens(b, s) }
    override suspend fun createToken(name: String, kind: String) = withSession { b, s -> api.mintToken(b, s, name, kind) }
    override suspend fun revokeToken(id: String) = withSession { b, s -> api.revokeToken(b, s, id) }
    override suspend fun invites() = withSession { b, s -> api.listInvites(b, s) }
    override suspend fun createInvite(maxUses: Int, days: Int, note: String) =
        withSession { b, s -> api.createInvite(b, s, maxUses, days, note) }
    override suspend fun revokeInvite(id: String) = withSession { b, s -> api.revokeInvite(b, s, id) }
    override suspend fun users() = withSession { b, s -> api.listUsers(b, s) }
    override suspend fun setUserActive(id: String, active: Boolean) = withSession { b, s -> api.setUserActive(b, s, id, active) }
    override suspend fun resetUserPassword(id: String, password: String) =
        withSession { b, s -> api.resetUserPassword(b, s, id, password) }
}
