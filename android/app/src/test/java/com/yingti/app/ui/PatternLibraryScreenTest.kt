package com.yingti.app.ui

import android.app.Application
import android.os.Looper
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.activity.ComponentActivity
import com.yingti.app.auth.ConnectionConfig
import com.yingti.app.history.ActivityStore
import com.yingti.app.patterns.*
import org.json.JSONObject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
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
    private var pauses = 0
    private var resumes = 0

    private fun show(connected: Boolean = true, playing: String? = null, paused: Boolean = false,
                     dark: Boolean = false, palette: String = "wine", repo: PatternLibraryRepository? = null) {
        compose.setContent { YingtiTheme(darkTheme = dark, paletteKey = palette) {
            PatternLibraryScreen(ConnectionConfig("https://example.com", token = "offline-test-only"), connected,
                ActivityStore(RuntimeEnvironment.getApplication()), { played += it }, onStop = { stops++ },
                playingId = playing, playbackProgress = if (playing != null) .4f else null, libraryApi = api,
                repository = repo, paused = paused, onPause = { pauses++ }, onResume = { resumes++ })
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
        compose.waitUntil(10000) {
            compose.onAllNodesWithTag("card-abc123456789").fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodesWithTag("card-builtin-wave").fetchSemanticsNodes().isEmpty() }
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
    @Test fun runningCardOffersPauseAndRealStopAndCannotDelete() {
        show(playing = "builtin-wave")
        compose.onNodeWithTag("play-builtin-wave").performClick()
        compose.onNodeWithTag("stop-builtin-wave").performClick()
        compose.runOnIdle { assertEquals(1, pauses); assertEquals(1, stops); assertTrue(played.isEmpty()) }
        compose.onNodeWithTag("delete-builtin-wave").assertIsNotEnabled()
    }
    @Test fun pausedCardOffersResume() {
        show(playing = "builtin-wave", paused = true)
        compose.onNodeWithTag("play-builtin-wave").assertTextContains("继续")
        compose.onNodeWithTag("play-builtin-wave").performClick()
        compose.runOnIdle { assertEquals(1, resumes); assertEquals(0, pauses) }
        compose.onNodeWithTag("delete-builtin-wave").assertIsNotEnabled()
    }
    @Test fun switchingAwayAndBackUsesTheCache() {
        val repo = PatternLibraryRepository({ api })
        var visible by mutableStateOf(true)
        compose.setContent { YingtiTheme {
            if (visible) PatternLibraryScreen(ConnectionConfig("https://example.com", token = "offline-test-only"), true,
                ActivityStore(RuntimeEnvironment.getApplication()), { played += it }, repository = repo)
        } }
        compose.waitUntil(10000) { compose.onAllNodesWithTag("card-builtin-wave").fetchSemanticsNodes().isNotEmpty() }
        visible = false
        compose.waitForIdle()
        visible = true
        compose.waitForIdle()
        compose.onNodeWithTag("card-builtin-wave").assertExists()
        compose.runOnIdle { assertEquals(1, api.listCalls) }
    }
    @Test fun enteringDuringStartupLoadDoesNotRestartIt() {
        val cfg = ConnectionConfig("https://example.com", token = "offline-test-only")
        val repo = PatternLibraryRepository({ api })
        api.holdList = true
        repo.bind(cfg)
        CoroutineScope(SupervisorJob() + Dispatchers.Main).launch { repo.ensureLoaded() } // Simulates MainActivity's preload.
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(api.listStarted)
        compose.setContent { YingtiTheme {
            PatternLibraryScreen(cfg, true, ActivityStore(RuntimeEnvironment.getApplication()), { played += it }, repository = repo)
        } }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(1, api.listCalls) }
        api.releaseList.complete(Unit)
        compose.waitUntil(10000) { compose.onAllNodesWithTag("card-builtin-wave").fetchSemanticsNodes().isNotEmpty() }
        compose.runOnIdle { assertEquals(1, api.listCalls) }
    }
    @Test fun cachedLibraryRendersBeforeNetworkReturns() {
        val dir = File(RuntimeEnvironment.getApplication().filesDir, "test_pattern_cache").apply { deleteRecursively(); mkdirs() }
        val cfg = ConnectionConfig("https://example.com", token = "offline-test-only")
        val repo1 = PatternLibraryRepository({ api }, cacheDir = dir,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO), background = Dispatchers.IO)
        runBlocking { repo1.bind(cfg); repo1.ensureLoaded() }
        compose.waitUntil(10000) { dir.listFiles()?.any { it.extension == "json" } == true }
        api.holdList = true
        val repo2 = PatternLibraryRepository({ api }, cacheDir = dir)
        compose.setContent { YingtiTheme {
            PatternLibraryScreen(cfg, true, ActivityStore(RuntimeEnvironment.getApplication()), { played += it }, repository = repo2)
        } }
        compose.waitUntil(10000) { compose.onAllNodesWithTag("card-builtin-wave").fetchSemanticsNodes().isNotEmpty() }
        compose.runOnIdle { assertEquals(2, api.listCalls) } // Cached card shown while the revalidation is still held.
        api.releaseList.complete(Unit)
    }
    @Test fun addNoteEntryOpensDetailWithNoteField() {
        show()
        compose.onNodeWithTag("add-note-def000111222").performScrollTo().performClick()
        compose.onNodeWithTag("wave-note").assertExists()
    }
    @Test fun repeatEditorUpdatesCardAndCapsAtTenMinutes() {
        show()
        compose.onNodeWithTag("detail-abc123456789").performScrollTo().performClick()
        repeat(3) { compose.onNodeWithTag("repeat-plus").performScrollTo().performClick() }
        compose.onNodeWithTag("repeat-summary").performScrollTo().assertTextContains("24秒 × 5 = 120秒")
        compose.onNodeWithTag("save-repeat").performClick()
        compose.waitForIdle()
        assertEquals(5, api.values["abc123456789"]!!.repeat)
        compose.onNodeWithTag("close-detail").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("24×5·120秒").assertExists()

        compose.onNodeWithTag("detail-def000111222").performScrollTo().performClick()
        compose.onNodeWithTag("repeat-plus").performScrollTo().performClick() // 19 → 20, the 10-minute cap
        compose.onNodeWithTag("repeat-plus").assertIsNotEnabled()
        compose.onNodeWithText("已达 10 分钟上限").assertExists()
    }
    @Test fun saveRepeatSendsOnlyRepeatAndLeavesNoteDirty() {
        show()
        compose.onNodeWithTag("detail-abc123456789").performScrollTo().performClick()
        compose.onNodeWithTag("wave-note").performScrollTo().performTextReplacement("草稿备注")
        compose.onNodeWithTag("repeat-plus").performScrollTo().performClick() // 2 → 3
        compose.onNodeWithTag("save-repeat").performClick()
        compose.waitForIdle()
        assertEquals(3, api.values["abc123456789"]!!.repeat)
        assertEquals("留白", api.values["abc123456789"]!!.description) // The unsaved note was not sent.
        compose.onNodeWithTag("close-detail").performScrollTo().performClick()
        compose.onNodeWithText("未保存").assertExists()
    }
    @Test fun builtinHasNoRepeatEditor() {
        show()
        openDetail()
        compose.onNodeWithTag("repeat-plus").assertDoesNotExist()
        compose.onNodeWithTag("wave-note").assertExists()
    }
    @Test fun deleteRequiresConfirmationAndUndoRestores() {
        show()
        compose.onNodeWithTag("delete-abc123456789").performScrollTo().performClick()
        assertEquals(3, api.values.size)
        compose.onAllNodesWithText("删除").onLast().performClick()
        compose.waitForIdle()
        assertEquals(2, api.values.size)
        compose.onNodeWithText("撤销").performClick()
        compose.waitForIdle()
        assertEquals(3, api.values.size)
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
            "abc123456789" to PatternDefinition("abc123456789", "慢慢升起", "留白", "yingti", 2, 1.0, listOf(WaveStep(6000, .1, 0.0), WaveStep(6000, .2, 0.0), WaveStep(6000, .7, 0.0), WaveStep(6000, .2, 0.0))),
            "def000111222" to PatternDefinition("def000111222", "静息长波", "", "yingti", 19, 1.0, listOf(WaveStep(30000, .4, 0.0))))
        val deleted = mutableMapOf<String, PatternDefinition>()
        var failPatch = false
        var holdList = false
        var listCalls = 0
        @Volatile var listStarted = false
        val releaseList = CompletableDeferred<Unit>()
        override suspend fun list(offset: Int, filter: String, query: String): PatternPage {
            listCalls++
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
                description = if (changes.has("description")) changes.getString("description").trim() else p.description,
                repeat = if (changes.has("repeat")) changes.getInt("repeat") else p.repeat)
            values[id] = updated
            return updated.toJson()
        }
        override suspend fun delete(id: String) { deleted[id] = values.remove(id)!! }
        override suspend fun restore(id: String): JSONObject { val p = deleted.remove(id)!!; values[id] = p; return p.toJson() }
    }
}
