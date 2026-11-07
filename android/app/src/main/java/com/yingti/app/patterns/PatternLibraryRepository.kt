package com.yingti.app.patterns

import com.yingti.app.auth.ConnectionConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger

data class LibraryState(
    val key: String? = null,
    val filter: String = "all",
    val query: String = "",
    val patterns: List<PatternDefinition> = emptyList(),
    val total: Int = 0,
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val error: String? = null,
    val loadedAtMs: Long = 0,
)

/** Process-level waveform cache scoped to one connection/account. */
class PatternLibraryRepository(
    private val apiFactory: (ConnectionConfig) -> LibraryApi,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _state = MutableStateFlow(LibraryState())
    val state: StateFlow<LibraryState> = _state.asStateFlow()
    var api: LibraryApi? = null
        private set
    private val generation = AtomicInteger()

    fun bind(config: ConnectionConfig) {
        val key = config.normalizedBaseUrl + "\n" + config.token.trim()
        if (_state.value.key == key) return
        api = apiFactory(config)
        generation.incrementAndGet()
        _state.value = LibraryState(key = key)
    }

    fun clear() {
        api = null
        generation.incrementAndGet()
        _state.value = LibraryState()
    }

    suspend fun ensureLoaded(maxAgeMs: Long = 5 * 60_000) {
        val s = _state.value
        if (api == null || s.loading) return
        if (!s.loaded || clock() - s.loadedAtMs > maxAgeMs) refresh()
    }

    suspend fun refresh(more: Boolean = false) {
        val api = api ?: return
        val ticket = generation.incrementAndGet()
        val requested = _state.value
        val offset = if (more) requested.patterns.size else 0
        _state.update { it.copy(loading = true, error = null) }
        try {
            val page = api.list(offset, requested.filter, requested.query)
            val values = page.patterns.map { it.definition ?: error("服务器尚未升级到新版波形库，请先部署服务端") }
            if (generation.get() == ticket && _state.value.filter == requested.filter && _state.value.query == requested.query) {
                _state.update { s ->
                    s.copy(
                        patterns = if (more) (s.patterns + values).distinctBy { it.id } else values,
                        total = page.total,
                        loaded = true,
                        loadedAtMs = clock(),
                    )
                }
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { if (generation.get() == ticket) _state.update { it.copy(error = e.message ?: "读取波形库失败") } }
        finally { if (generation.get() == ticket) _state.update { it.copy(loading = false) } }
    }

    suspend fun select(filter: String, query: String) {
        val s = _state.value
        if (s.filter == filter && s.query == query && s.loaded) return
        generation.incrementAndGet()
        _state.update { it.copy(filter = filter, query = query, patterns = emptyList(), total = 0, loading = true, loaded = false, error = null) }
        refresh()
    }

    suspend fun patch(id: String, changes: JSONObject): PatternDefinition {
        val updated = PatternDefinition.from((api ?: error("波形库未绑定")).patch(id, changes))
        replace(updated)
        return updated
    }

    suspend fun delete(id: String): Int {
        val index = _state.value.patterns.indexOfFirst { it.id == id }
        (api ?: error("波形库未绑定")).delete(id)
        removeLocal(id)
        return index
    }

    suspend fun restore(id: String, index: Int): PatternDefinition {
        val p = PatternDefinition.from((api ?: error("波形库未绑定")).restore(id))
        _state.update { s ->
            if (s.patterns.any { it.id == id }) s
            else s.copy(patterns = s.patterns.toMutableList().apply { add(index.coerceIn(0, s.patterns.size), p) }, total = s.total + 1)
        }
        return p
    }

    fun replace(p: PatternDefinition) {
        generation.incrementAndGet() // A list response captured before a write must not overwrite it.
        _state.update { s -> s.copy(patterns = s.patterns.map { if (it.id == p.id) p else it }, loading = false) }
    }

    fun removeLocal(id: String) {
        generation.incrementAndGet()
        _state.update { s -> s.copy(patterns = s.patterns.filterNot { it.id == id }, total = (s.total - 1).coerceAtLeast(0), loading = false) }
    }
}
