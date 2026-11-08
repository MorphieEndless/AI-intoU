package com.yingti.app.ui

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.yingti.app.auth.AccountApi
import com.yingti.app.auth.AuthMode
import com.yingti.app.auth.ConnectionConfig
import com.yingti.app.auth.CreatedInvite
import com.yingti.app.auth.CreatedToken
import com.yingti.app.auth.InviteInfo
import com.yingti.app.auth.TokenInfo
import com.yingti.app.auth.UserInfo
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class AiAccessScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val tokensCalls = AtomicInteger(0)

    private val active = TokenInfo(
        id = "t1", name = "我的 RikkaHub", kind = "agent", prefix = "aiu_agent_abc1",
        state = "active", createdAt = "2026-11-01T00:00:00Z", lastUsedAt = null,
    )
    private val revoked = TokenInfo(
        id = "t2", name = "旧平板", kind = "agent", prefix = "aiu_agent_xyz9",
        state = "revoked", createdAt = "2026-10-01T00:00:00Z", lastUsedAt = null,
    )

    private val api = object : AccountApi {
        override val username = "owner"
        override suspend fun tokens(): List<TokenInfo> {
            tokensCalls.incrementAndGet()
            return listOf(active, revoked)
        }
        override suspend fun createToken(name: String, kind: String) =
            CreatedToken("unused", active)
        override suspend fun revokeToken(id: String) {}
        override suspend fun relogin(password: String) {}
        override suspend fun invites() = emptyList<InviteInfo>()
        override suspend fun createInvite(maxUses: Int, days: Int, note: String) =
            CreatedInvite(
                "unused",
                InviteInfo("i1", "pref", "", maxUses, 0, emptyList(), "active", "", null),
            )
        override suspend fun revokeInvite(id: String) {}
        override suspend fun users() = emptyList<UserInfo>()
        override suspend fun setUserActive(id: String, active: Boolean) {}
        override suspend fun resetUserPassword(id: String, password: String) {}
    }

    private fun showScreen() {
        compose.setContent {
            MaterialTheme {
                AiAccessScreen(
                    api = api,
                    config = ConnectionConfig(
                        serverBaseUrl = "https://server.example.com",
                        authMode = AuthMode.ACCOUNT,
                        username = "owner",
                    ),
                    phoneTokenId = null,
                    welcome = false,
                    onBack = {},
                    onCopy = { _, _ -> },
                    onHasAiAccess = {},
                )
            }
        }
        compose.waitUntil(5_000) { tokensCalls.get() >= 1 }
    }

    @Test
    fun activeTokenShownInactiveHidden() {
        showScreen()
        compose.onNodeWithText("我的 RikkaHub").performScrollTo().assertExists()
        compose.onNodeWithText("旧平板").assertDoesNotExist()
        compose.onAllNodes(hasText("已撤销或过期", substring = true)).assertCountEquals(0)
    }

    @Test
    fun refreshIsTextButtonAndReloads() {
        showScreen()
        compose.onNodeWithTag("subpage-refresh")
            .assertExists()
            .assert(hasText("刷新"))
        compose.onNodeWithContentDescription("刷新").assertDoesNotExist()
        compose.onNodeWithTag("subpage-refresh").performClick()
        compose.waitUntil(5_000) { tokensCalls.get() >= 2 }
    }
}
