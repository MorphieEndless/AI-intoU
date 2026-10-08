package com.yingti.app.ui

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.activity.ComponentActivity
import com.yingti.app.auth.ConnectionConfig
import com.yingti.app.history.ActivityStore
import com.yingti.app.patterns.*
import org.json.JSONObject
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "w390dp-h844dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PatternLibraryScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val api = FakeLibrary()
    private val played = mutableListOf<JSONObject>()
    private var stops = 0

    private fun show(connected: Boolean = true, playing: String? = null, dark: Boolean = false, palette: String = "wine") {
        compose.setContent { YingtiTheme(darkTheme = dark, paletteKey = palette) {
            PatternLibraryScreen(ConnectionConfig("https://example.com", token = "offline-test-only"), connected,
                ActivityStore(RuntimeEnvironment.getApplication()), { played += it }, onStop = { stops++ },
                playingId = playing, playbackProgress = if (playing != null) .4f else null, libraryApi = api)
        } }
        compose.waitUntil(10000) { compose.onAllNodesWithTag("card-builtin-wave").fetchSemanticsNodes().isNotEmpty() }
    }
    private fun openDetail() { compose.onNodeWithTag("detail-builtin-wave").performScrollTo().performClick() }
    private fun writeNote() { compose.onNodeWithTag("wave-note").performScrollTo().performTextReplacement("新的备注") }

    @Test fun cardsFollowPrototypeAndExampleCannotBeDeleted() {
        show()
        compose.onNodeWithText("30×1·30秒").assertExists()
        compose.onNodeWithTag("like-builtin-wave").assertExists()
        compose.onNodeWithTag("favorite-builtin-wave").assertExists()
        compose.onNodeWithTag("delete-builtin-wave").assertIsNotEnabled()
        assertTrue(played.isEmpty())
        screenshot("library-light")
    }
    @Test fun darkPaletteKeepsPreferenceColorsAndChartReadable() {
        show(dark = true)
        compose.onNodeWithTag("like-builtin-wave").performClick()
        compose.onNodeWithTag("favorite-builtin-wave").performClick()
        compose.waitForIdle()
        screenshot("library-dark")
    }
    @Test fun geminiPalettePreservesLayout() {
        show(palette = "gemini")
        compose.onNodeWithTag("like-builtin-wave").performClick()
        compose.onNodeWithTag("favorite-builtin-wave").performClick()
        compose.waitForIdle()
        screenshot("library-gemini")
    }
    @Test @Config(qualifiers = "w320dp-h720dp") fun narrowCardKeepsActionsVisible() {
        show()
        compose.onNodeWithTag("play-builtin-wave").assertIsDisplayed()
        compose.onNodeWithTag("detail-builtin-wave").assertIsDisplayed()
        compose.onNodeWithTag("favorite-builtin-wave").assertIsDisplayed()
        screenshot("library-320")
    }
    @Test fun likingIsQuietIndependentAndNeverControls() {
        show()
        compose.onNodeWithTag("like-builtin-wave").performClick()
        compose.waitForIdle()
        assertTrue(api.values["builtin-wave"]!!.isLiked)
        assertFalse(api.values["builtin-wave"]!!.isFavorite)
        compose.onNodeWithText("已保存").assertDoesNotExist()
        assertTrue(played.isEmpty())
    }
    @Test fun favoritesFilterAggregatesOnlyFavorites() {
        show()
        compose.onNodeWithTag("favorite-builtin-wave").performClick()
        compose.onNodeWithTag("filter-favorites").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("card-builtin-wave").assertExists()
        compose.onNodeWithTag("card-abc123456789").assertDoesNotExist()
        assertTrue(played.isEmpty())
    }
    @Test fun oldRefreshResponseCannotUndoNewPreferenceWrite() {
        show()
        api.holdList = true
        compose.onNodeWithContentDescription("刷新波形库").performClick()
        compose.waitUntil(10000) { api.listStarted }
        compose.onNodeWithTag("like-builtin-wave").performClick()
        compose.waitForIdle()
        api.releaseList.complete(Unit)
        compose.waitForIdle()
        compose.onNodeWithTag("like-builtin-wave").assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "已喜欢"))
        assertTrue(api.values["builtin-wave"]!!.isLiked)
    }
    @Test fun searchIncludesNotesAndHasSingleClearControl() {
        show()
        compose.onNodeWithTag("wave-search").performTextReplacement("留白")
        compose.waitUntil(10000) { compose.onAllNodesWithTag("card-abc123456789").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("card-builtin-wave").assertDoesNotExist()
        compose.onAllNodesWithContentDescription("清空搜索").assertCountEquals(1)
    }
    @Test fun detailsDoNotPlayAndSaveNoteInline() {
        show(); openDetail(); writeNote()
        compose.onNodeWithTag("save-note").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("已保存").assertExists()
        assertEquals("新的备注", api.values["builtin-wave"]!!.description)
        assertTrue(played.isEmpty())
        screenshot("library-detail")
    }
    @Test fun dirtyCloseOffersContinueAndDiscard() {
        show(); openDetail(); writeNote()
        compose.onNodeWithTag("close-detail").performScrollTo().performClick()
        compose.onNodeWithText("未保存").assertExists()
        compose.onNodeWithText("继续编辑").performClick()
        compose.onNodeWithTag("wave-note").assertExists()
        compose.onNodeWithTag("close-detail").performScrollTo().performClick()
        compose.onNodeWithText("放弃修改").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("wave-note").assertDoesNotExist()
        assertNotEquals("新的备注", api.values["builtin-wave"]!!.description)
    }
    @Test fun dirtyCloseCanSaveBeforeDismissal() {
        show(); openDetail(); writeNote()
        compose.onNodeWithTag("close-detail").performScrollTo().performClick()
        compose.onNodeWithText("保存并关闭").performClick()
        compose.waitForIdle()
        assertEquals("新的备注", api.values["builtin-wave"]!!.description)
        compose.onNodeWithTag("wave-note").assertDoesNotExist()
    }
    @Test fun failedSaveKeepsDraftAndIsNotSuccess() {
        show(); openDetail(); writeNote(); api.failPatch = true
        compose.onNodeWithTag("save-note").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("已保存").assertDoesNotExist()
        compose.onNodeWithTag("wave-note").assertTextContains("新的备注")
        assertNotEquals("新的备注", api.values["builtin-wave"]!!.description)
        assertTrue(played.isEmpty())
    }
    @Test fun disconnectedPlaybackIsDisabledButPreferencesWork() {
        show(connected = false)
        compose.onNodeWithTag("play-builtin-wave").assertIsNotEnabled()
        compose.onNodeWithTag("favorite-builtin-wave").performClick()
        compose.waitForIdle()
        assertTrue(api.values["builtin-wave"]!!.isFavorite)
        assertTrue(played.isEmpty())
    }
    @Test fun playbackIsExplicitAndPreservesCommand() {
        show()
        compose.onNodeWithTag("play-builtin-wave").performClick()
        compose.waitForIdle()
        assertEquals(1, played.size)
        assertEquals("custom_pattern", played.single().getString("type"))
        assertEquals(.2, played.single().getJSONArray("steps").getJSONObject(0).getDouble("vibrate"), 0.0)
    }
    @Test fun runningCardOffersRealStopAndCannotDelete() {
        show(playing = "builtin-wave")
        compose.onNodeWithTag("play-builtin-wave").performClick()
        compose.runOnIdle { assertEquals(1, stops); assertTrue(played.isEmpty()) }
        compose.onNodeWithTag("delete-builtin-wave").assertIsNotEnabled()
    }
    @Test fun deleteRequiresConfirmationAndUndoRestores() {
        show()
        compose.onNodeWithTag("delete-abc123456789").performScrollTo().performClick()
        assertEquals(2, api.values.size)
        compose.onAllNodesWithText("删除").onLast().performClick()
        compose.waitForIdle()
        assertEquals(1, api.values.size)
        compose.onNodeWithText("撤销").performClick()
        compose.waitForIdle()
        assertEquals(2, api.values.size)
        assertTrue(played.isEmpty())
    }
    private fun screenshot(name: String) {
        val dir = File("build/library-previews").apply { mkdirs() }
        // Manual view rendering avoids PixelCopy, unavailable in this host test runner.
        compose.runOnIdle {
            val global = Class.forName("android.view.WindowManagerGlobal")
            val instance = global.getMethod("getInstance").invoke(null)
            val field = global.getDeclaredField("mViews").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            val views = field.get(instance) as List<android.view.View>
            val view = views.lastOrNull() ?: compose.activity.window.decorView
            val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
            view.draw(android.graphics.Canvas(bitmap))
            File(dir, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
    private class FakeLibrary : LibraryApi {
        val values = linkedMapOf(
            "builtin-wave" to PatternDefinition("builtin-wave", "示例波形-波浪", "起伏之间，认识你的第一段节奏", "yingti", 1, 1.0, listOf(WaveStep(30000, .2, 0.0)), builtin = true),
            "abc123456789" to PatternDefinition("abc123456789", "慢慢升起", "留白", "yingti", 2, 1.0, listOf(WaveStep(6000, .1, 0.0), WaveStep(6000, .2, 0.0), WaveStep(6000, .7, 0.0), WaveStep(6000, .2, 0.0))))
        val deleted = mutableMapOf<String, PatternDefinition>()
        var failPatch = false
        var holdList = false
        @Volatile var listStarted = false
        val releaseList = CompletableDeferred<Unit>()
        override suspend fun list(offset: Int, filter: String, query: String): PatternPage {
            val list = values.values.filter { (filter != "liked" || it.isLiked) && (filter != "favorites" || it.isFavorite) && (it.name + it.description).contains(query.trim(), ignoreCase = true) }
            val page = PatternPage(list.drop(offset).map { p -> PatternSummary(p.id, p.name, p.description, p.steps.size, p.repeat, p.totalMs, p) }, list.size)
            if (holdList) { listStarted = true; releaseList.await() }
            return page
        }
        override suspend fun get(id: String) = values.getValue(id).toJson()
        override suspend fun patch(id: String, changes: JSONObject): JSONObject {
            if (failPatch) error("模拟保存失败")
            val p = values.getValue(id)
            val updated = p.copy(isLiked = if (changes.has("is_liked")) changes.getBoolean("is_liked") else p.isLiked,
                isFavorite = if (changes.has("is_favorite")) changes.getBoolean("is_favorite") else p.isFavorite,
                description = if (changes.has("description")) changes.getString("description").trim() else p.description)
            values[id] = updated
            return updated.toJson()
        }
        override suspend fun delete(id: String) { deleted[id] = values.remove(id)!! }
        override suspend fun restore(id: String): JSONObject { val p = deleted.remove(id)!!; values[id] = p; return p.toJson() }
    }
}
