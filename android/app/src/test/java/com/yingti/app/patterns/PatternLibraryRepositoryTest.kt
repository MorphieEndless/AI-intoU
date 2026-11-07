package com.yingti.app.patterns

import com.yingti.app.auth.ConnectionConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PatternLibraryRepositoryTest {
    private val configA = ConnectionConfig("https://a.example.com", token = "tok-a")
    private val configB = ConnectionConfig("https://b.example.com", token = "tok-b")

    private class FakeApi : LibraryApi {
        var listCalls = 0
        var gate: CompletableDeferred<Unit>? = null
        val values = linkedMapOf(
            "builtin-wave" to PatternDefinition("builtin-wave", "示例波形-波浪", "", "yingti", 1, 1.0, listOf(WaveStep(30000, .2, 0.0)), builtin = true),
            "abc123456789" to PatternDefinition("abc123456789", "慢慢升起", "留白", "yingti", 2, 1.0, listOf(WaveStep(12000, .5, 0.0))),
        )
        override suspend fun list(offset: Int, filter: String, query: String): PatternPage {
            listCalls++
            gate?.await()
            val list = values.values.filter { (filter != "liked" || it.isLiked) && (filter != "favorites" || it.isFavorite) && (it.name + it.description).contains(query.trim(), ignoreCase = true) }
            return PatternPage(list.drop(offset).map { PatternSummary(it.id, it.name, it.description, it.steps.size, it.repeat, it.totalMs, it) }, list.size)
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
        val repo = PatternLibraryRepository({ api }, { 0L })
        repo.bind(configA)
        repo.ensureLoaded()
        assertEquals(1, api.listCalls)
        repo.ensureLoaded()
        assertEquals(1, api.listCalls)
        assertTrue(repo.state.value.loaded)
        assertEquals(2, repo.state.value.patterns.size)
    }

    @Test fun staleCacheRefreshesInBackgroundWhileKeepingListVisible() = runTest {
        val api = FakeApi()
        var now = 0L
        val repo = PatternLibraryRepository({ api }, { now })
        repo.bind(configA)
        repo.ensureLoaded()
        now = 6 * 60_000
        api.gate = CompletableDeferred()
        val job = launch { repo.ensureLoaded() }
        yield()
        assertEquals(2, api.listCalls)
        // The current list stays visible while the refresh is in flight.
        assertEquals(2, repo.state.value.patterns.size)
        api.gate!!.complete(Unit)
        job.join()
        assertTrue(repo.state.value.loaded)
    }

    @Test fun bindWithNewKeyResetsAndSameKeyKeepsCache() = runTest {
        var api = FakeApi()
        val repo = PatternLibraryRepository({ api }, { 0L })
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
        val repo = PatternLibraryRepository({ api }, { 0L })
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
        val repo = PatternLibraryRepository({ api }, { 0L })
        repo.bind(configA)
        repo.ensureLoaded()
        api.gate = CompletableDeferred()
        val refresh = launch { repo.refresh() }
        yield()
        repo.patch("abc123456789", JSONObject().put("is_liked", true))
        api.gate!!.complete(Unit)
        refresh.join()
        assertTrue(repo.state.value.patterns.single { it.id == "abc123456789" }.isLiked)
    }

    @Test fun removeLocalDuringInFlightRefreshStillClearsLoading() = runTest {
        val api = FakeApi()
        val repo = PatternLibraryRepository({ api }, { 0L })
        repo.bind(configA)
        repo.ensureLoaded()
        api.gate = CompletableDeferred()
        val refresh = launch { repo.refresh() }
        yield()
        assertTrue(repo.state.value.loading)
        repo.removeLocal("abc123456789")
        api.gate!!.complete(Unit)
        refresh.join()
        assertFalse(repo.state.value.loading)
        repo.refresh()
        assertEquals(3, api.listCalls)
    }

    @Test fun selectWithSameFilterAndQueryIsNoOp() = runTest {
        val api = FakeApi()
        val repo = PatternLibraryRepository({ api }, { 0L })
        repo.bind(configA)
        repo.ensureLoaded()
        repo.select("all", "")
        assertEquals(1, api.listCalls)
        repo.select("favorites", "")
        assertEquals(2, api.listCalls)
    }
}
