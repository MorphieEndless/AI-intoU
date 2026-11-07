package com.yingti.app.patterns

import com.yingti.app.auth.ConnectionConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.coroutines.EmptyCoroutineContext

class PatternLibraryRepositoryTest {
    @get:Rule val tmp = TemporaryFolder()
    private val configA = ConnectionConfig("https://a.example.com", token = "tok-a")
    private val configB = ConnectionConfig("https://b.example.com", token = "tok-b")

    private class FakeApi : LibraryApi {
        var listCalls = 0
        var gate: CompletableDeferred<Unit>? = null
        var listEtag: String? = null // Honor If-None-Match like the real server.
        var failList = false
        val receivedEtags = mutableListOf<String?>()
        val values = linkedMapOf(
            "builtin-wave" to PatternDefinition("builtin-wave", "示例波形-波浪", "", "yingti", 1, 1.0, listOf(WaveStep(30000, .2, 0.0)), builtin = true),
            "abc123456789" to PatternDefinition("abc123456789", "慢慢升起", "留白", "yingti", 2, 1.0, listOf(WaveStep(12000, .5, 0.0))),
        )
        override suspend fun list(offset: Int, filter: String, query: String): PatternPage {
            listCalls++
            gate?.await()
            if (failList) error("模拟读取失败")
            val list = values.values.filter { (filter != "liked" || it.isLiked) && (filter != "favorites" || it.isFavorite) && (it.name + it.description).contains(query.trim(), ignoreCase = true) }
            return PatternPage(list.drop(offset).map { PatternSummary(it.id, it.name, it.description, it.steps.size, it.repeat, it.totalMs, it) }, list.size, listEtag)
        }
        override suspend fun listIfChanged(offset: Int, filter: String, query: String, etag: String?): PatternPage {
            receivedEtags += etag
            if (etag != null && etag == listEtag) return PatternPage(emptyList(), 0, etag, notModified = true)
            return list(offset, filter, query)
        }
        override suspend fun get(id: String) = values.getValue(id).toJson()
        override suspend fun patch(id: String, changes: JSONObject): JSONObject {
            val p = values.getValue(id)
            val updated = p.copy(
                isLiked = if (changes.has("is_liked")) changes.getBoolean("is_liked") else p.isLiked,
                isFavorite = if (changes.has("is_favorite")) changes.getBoolean("is_favorite") else p.isFavorite,
                description = if (changes.has("description")) changes.getString("description") else p.description,
                repeat = if (changes.has("repeat")) changes.getInt("repeat") else p.repeat,
            )
            values[id] = updated
            return updated.toJson()
        }
        override suspend fun delete(id: String) { values.remove(id) }
        override suspend fun restore(id: String): JSONObject {
            val p = PatternDefinition(id, "慢慢升起", "留白", "yingti", 2, 1.0, listOf(WaveStep(12000, .5, 0.0)))
            values[id] = p
            return p.toJson()
        }
    }

    @Test fun ensureLoadedLoadsOnceAndSecondCallMakesNoRequest() = runTest {
        val api = FakeApi()
        val repo = PatternLibraryRepository({ api }, { 0L }, scope = backgroundScope, background = EmptyCoroutineContext)
        repo.bind(configA)
        repo.ensureLoaded()
        assertEquals(1, api.listCalls)
        repo.ensureLoaded()
        assertEquals(1, api.listCalls)
        assertTrue(repo.state.value.loaded)
        assertEquals(2, repo.state.value.patterns.size)
    }

    @Test fun ensureLoadedNeverRefetchesAfterSuccess() = runTest {
        val api = FakeApi()
        var now = 0L
        val repo = PatternLibraryRepository({ api }, { now }, scope = backgroundScope, background = EmptyCoroutineContext)
        repo.bind(configA)
        repo.ensureLoaded()
        now = 60 * 60_000
        repo.ensureLoaded()
        assertEquals(1, api.listCalls)
        assertTrue(repo.state.value.loaded)
    }

    @Test fun bindWithNewKeyResetsAndSameKeyKeepsCache() = runTest {
        var api = FakeApi()
        val repo = PatternLibraryRepository({ api }, { 0L }, scope = backgroundScope, background = EmptyCoroutineContext)
        repo.bind(configA)
        repo.ensureLoaded()
        assertEquals(1, api.listCalls)
        repo.bind(configA) // same key: no-op
        repo.ensureLoaded()
        assertEquals(1, api.listCalls)
        api = FakeApi()
        repo.bind(configB) // new key: new api, state reset
        assertEquals(0, repo.state.value.patterns.size)
        assertFalse(repo.state.value.loaded)
        repo.ensureLoaded()
        assertEquals(1, api.listCalls)
    }

    @Test fun patchDeleteRestoreUpdateTheCache() = runTest {
        val api = FakeApi()
        val repo = PatternLibraryRepository({ api }, { 0L }, scope = backgroundScope, background = EmptyCoroutineContext)
        repo.bind(configA)
        repo.ensureLoaded()
        val updated = repo.patch("abc123456789", JSONObject().put("repeat", 5))
        assertEquals(5, updated.repeat)
        assertEquals(60_000L, repo.state.value.patterns.single { it.id == "abc123456789" }.totalMs)
        val index = repo.delete("abc123456789")
        assertEquals(1, index)
        assertNull(repo.state.value.patterns.find { it.id == "abc123456789" })
        assertEquals(1, repo.state.value.total)
        repo.restore("abc123456789", index)
        assertEquals("abc123456789", repo.state.value.patterns[1].id)
        assertEquals(2, repo.state.value.total)
    }

    @Test fun staleListResponseCannotOverwriteNewerWrite() = runTest {
        val api = FakeApi()
        val repo = PatternLibraryRepository({ api }, { 0L }, scope = backgroundScope, background = EmptyCoroutineContext)
        repo.bind(configA)
        repo.ensureLoaded()
        api.gate = CompletableDeferred()
        val refresh = launch { repo.refresh() }
        runCurrent()
        repo.patch("abc123456789", JSONObject().put("is_liked", true))
        api.gate!!.complete(Unit)
        advanceUntilIdle()
        refresh.join()
        assertTrue(repo.state.value.patterns.single { it.id == "abc123456789" }.isLiked)
    }

    @Test fun removeLocalDuringInFlightRefreshStillClearsLoading() = runTest {
        val api = FakeApi()
        val repo = PatternLibraryRepository({ api }, { 0L }, scope = backgroundScope, background = EmptyCoroutineContext)
        repo.bind(configA)
        repo.ensureLoaded()
        api.gate = CompletableDeferred()
        val refresh = launch { repo.refresh() }
        runCurrent()
        assertTrue(repo.state.value.loading)
        repo.removeLocal("abc123456789")
        api.gate!!.complete(Unit)
        advanceUntilIdle()
        refresh.join()
        assertFalse(repo.state.value.loading)
        repo.refresh()
        assertEquals(3, api.listCalls)
    }

    @Test fun selectWithSameFilterAndQueryIsNoOp() = runTest {
        val api = FakeApi()
        val repo = PatternLibraryRepository({ api }, { 0L }, scope = backgroundScope, background = EmptyCoroutineContext)
        repo.bind(configA)
        repo.ensureLoaded()
        repo.select("all", "")
        assertEquals(1, api.listCalls)
        repo.select("favorites", "")
        assertEquals(2, api.listCalls)
    }

    @Test fun concurrentEnsureLoadedSharesOneRequest() = runTest {
        val api = FakeApi()
        api.gate = CompletableDeferred()
        val repo = PatternLibraryRepository({ api }, { 0L }, scope = backgroundScope, background = EmptyCoroutineContext)
        repo.bind(configA)
        val first = launch { repo.ensureLoaded() }
        val second = launch { repo.ensureLoaded() }
        runCurrent()
        assertEquals(1, api.listCalls)
        api.gate!!.complete(Unit)
        advanceUntilIdle()
        first.join(); second.join()
        assertTrue(repo.state.value.loaded)
        assertEquals(2, repo.state.value.patterns.size)
    }

    @Test fun cancelledCallerDoesNotAbortSharedLoad() = runTest {
        val api = FakeApi()
        api.gate = CompletableDeferred()
        val repo = PatternLibraryRepository({ api }, { 0L }, scope = backgroundScope, background = EmptyCoroutineContext)
        repo.bind(configA)
        val first = launch { repo.ensureLoaded() }
        runCurrent()
        first.cancel()
        api.gate!!.complete(Unit)
        repo.ensureLoaded()
        assertEquals(1, api.listCalls)
        assertTrue(repo.state.value.loaded)
        assertEquals(2, repo.state.value.patterns.size)
    }

    @Test fun diskCacheShowsImmediatelyThenRevalidatesSilently() = runTest {
        val dir = tmp.newFolder("cache")
        val api1 = FakeApi()
        val repo1 = PatternLibraryRepository({ api1 }, { 0L }, dir, scope = backgroundScope, background = EmptyCoroutineContext)
        repo1.bind(configA)
        repo1.ensureLoaded()
        advanceUntilIdle()
        assertTrue(dir.listFiles()!!.any { it.extension == "json" })

        val api2 = FakeApi()
        api2.gate = CompletableDeferred()
        val repo2 = PatternLibraryRepository({ api2 }, { 0L }, dir, scope = backgroundScope, background = EmptyCoroutineContext)
        repo2.bind(configA)
        val job = launch { repo2.ensureLoaded() }
        runCurrent()
        assertTrue(repo2.state.value.loaded)
        assertEquals(2, repo2.state.value.patterns.size)
        assertFalse(repo2.state.value.loading)
        assertTrue(repo2.state.value.refreshing)
        api2.gate!!.complete(Unit)
        advanceUntilIdle()
        job.join()
        assertFalse(repo2.state.value.refreshing)
        assertEquals(1, api2.listCalls)
    }

    @Test fun silentRefreshFailureKeepsCacheWithoutError() = runTest {
        val dir = tmp.newFolder("cache")
        val api1 = FakeApi()
        val repo1 = PatternLibraryRepository({ api1 }, { 0L }, dir, scope = backgroundScope, background = EmptyCoroutineContext)
        repo1.bind(configA)
        repo1.ensureLoaded()
        advanceUntilIdle()

        val api2 = FakeApi()
        api2.failList = true
        val repo2 = PatternLibraryRepository({ api2 }, { 0L }, dir, scope = backgroundScope, background = EmptyCoroutineContext)
        repo2.bind(configA)
        repo2.ensureLoaded()
        assertNull(repo2.state.value.error)
        assertEquals(2, repo2.state.value.patterns.size)
        assertTrue(repo2.state.value.loaded)
    }

    @Test fun mutationsPersistToDisk() = runTest {
        val dir = tmp.newFolder("cache")
        val api = FakeApi()
        val repo = PatternLibraryRepository({ api }, { 0L }, dir, scope = backgroundScope, background = EmptyCoroutineContext)
        repo.bind(configA)
        repo.ensureLoaded()
        repo.patch("abc123456789", JSONObject().put("is_liked", true))
        val index = repo.delete("abc123456789")
        repo.restore("abc123456789", index)
        advanceUntilIdle()

        val api2 = FakeApi()
        val repo2 = PatternLibraryRepository({ api2 }, { 0L }, dir, scope = backgroundScope, background = EmptyCoroutineContext)
        repo2.bind(configA)
        api2.gate = CompletableDeferred()
        val job = launch { repo2.ensureLoaded() }
        runCurrent()
        val cached = repo2.state.value.patterns.single { it.id == "abc123456789" }
        assertTrue(cached.isLiked)
        api2.gate!!.complete(Unit)
        advanceUntilIdle()
        job.join()
    }

    @Test fun notModifiedKeepsListAndSendsStoredEtag() = runTest {
        val api = FakeApi()
        api.listEtag = "\"v1\""
        val repo = PatternLibraryRepository({ api }, { 0L }, scope = backgroundScope, background = EmptyCoroutineContext)
        repo.bind(configA)
        repo.ensureLoaded()
        val before = repo.state.value.patterns
        api.receivedEtags.clear()
        repo.refresh()
        assertEquals("\"v1\"", api.receivedEtags.last())
        assertSame(before, repo.state.value.patterns)
        repo.patch("abc123456789", JSONObject().put("is_liked", true))
        repo.refresh()
        assertNull(api.receivedEtags.last())
    }

    @Test fun cacheIsPerAccount() = runTest {
        val dir = tmp.newFolder("cache")
        val apiA = FakeApi()
        val repoA = PatternLibraryRepository({ apiA }, { 0L }, dir, scope = backgroundScope, background = EmptyCoroutineContext)
        repoA.bind(configA)
        repoA.ensureLoaded()
        advanceUntilIdle()
        val names = dir.listFiles()!!.map { it.name }
        assertTrue(names.isNotEmpty())
        names.forEach { assertFalse(it.contains("tok-a")); assertFalse(it.contains("tok-b")) }

        val apiB = FakeApi()
        apiB.gate = CompletableDeferred()
        val repoB = PatternLibraryRepository({ apiB }, { 0L }, dir, scope = backgroundScope, background = EmptyCoroutineContext)
        repoB.bind(configB)
        val job = launch { repoB.ensureLoaded() }
        runCurrent()
        assertFalse(repoB.state.value.loaded) // Account B must not see account A's cache.
        assertTrue(repoB.state.value.loading)
        apiB.gate!!.complete(Unit)
        advanceUntilIdle()
        job.join()
        assertTrue(repoB.state.value.loaded)
    }

    @Test fun clearDeletesCacheFiles() = runTest {
        val dir = tmp.newFolder("cache")
        val api = FakeApi()
        val repo = PatternLibraryRepository({ api }, { 0L }, dir, scope = backgroundScope, background = EmptyCoroutineContext)
        repo.bind(configA)
        repo.ensureLoaded()
        advanceUntilIdle()
        assertTrue(dir.listFiles()!!.isNotEmpty())
        repo.clear()
        advanceUntilIdle()
        assertTrue(dir.listFiles()!!.isEmpty())
    }

    @Test fun userRefreshDuringSilentRevalidationJoinsIt() = runTest {
        val api = FakeApi()
        val repo = PatternLibraryRepository({ api }, { 0L }, scope = backgroundScope, background = EmptyCoroutineContext)
        repo.bind(configA)
        repo.ensureLoaded()
        repo.select("favorites", "")
        api.gate = CompletableDeferred()
        val back = launch { repo.select("all", "") }
        runCurrent()
        assertTrue(repo.state.value.refreshing)
        val refresh = launch { repo.refresh() }
        runCurrent()
        assertEquals(3, api.listCalls) // The user refresh joined the silent revalidation.
        api.gate!!.complete(Unit)
        advanceUntilIdle()
        back.join(); refresh.join()
        assertFalse(repo.state.value.refreshing)
    }

    @Test fun selectBackToDefaultShowsCachedListInstantly() = runTest {
        val api = FakeApi()
        val repo = PatternLibraryRepository({ api }, { 0L }, scope = backgroundScope, background = EmptyCoroutineContext)
        repo.bind(configA)
        repo.ensureLoaded()
        repo.select("favorites", "")
        api.gate = CompletableDeferred()
        val back = launch { repo.select("all", "") }
        runCurrent()
        assertEquals(2, repo.state.value.patterns.size) // Memory mirror, before the network answers.
        assertTrue(repo.state.value.loaded)
        api.gate!!.complete(Unit)
        advanceUntilIdle()
        back.join()
    }
}
