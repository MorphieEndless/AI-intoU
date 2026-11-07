package com.yingti.app.patterns

import com.yingti.app.auth.ConnectionConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext

data class LibraryState(
    val key: String? = null,
    val filter: String = "all",
    val query: String = "",
    val patterns: List<PatternDefinition> = emptyList(),
    val total: Int = 0,
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val loaded: Boolean = false,
    val error: String? = null,
    val loadedAtMs: Long = 0,
)

/** Process-level waveform cache scoped to one connection/account; the default view is also kept on disk. */
class PatternLibraryRepository(
    private val apiFactory: (ConnectionConfig) -> LibraryApi,
    private val clock: () -> Long = System::currentTimeMillis,
    private val cacheDir: File? = null,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    private val background: CoroutineContext = Dispatchers.IO,
) {
    private val _state = MutableStateFlow(LibraryState())
    val state: StateFlow<LibraryState> = _state.asStateFlow()
    var api: LibraryApi? = null
        private set
    private val generation = AtomicInteger()
    private data class View(val key: String?, val filter: String, val query: String, val more: Boolean)
    private class Load(val ticket: Int, val view: View, val job: Job)
    private data class Snapshot(val patterns: List<PatternDefinition>, val total: Int, val etag: String?, val savedAt: Long)
    private var inFlight: Load? = null
    private var defaultView: Snapshot? = null
    private var cacheRead = false
    private val writes = AtomicInteger()
    private val writeLock = Mutex()

    fun bind(config: ConnectionConfig) {
        val key = config.normalizedBaseUrl + "\n" + config.token.trim()
        if (_state.value.key == key) return
        api = apiFactory(config)
        generation.incrementAndGet()
        inFlight?.job?.cancel()
        inFlight = null
        defaultView = null
        cacheRead = false
        _state.value = LibraryState(key = key)
    }

    fun clear() {
        api = null
        generation.incrementAndGet()
        inFlight?.job?.cancel()
        inFlight = null
        defaultView = null
        cacheRead = false
        _state.value = LibraryState()
        writes.incrementAndGet() // Invalidate queued writes before the files are deleted.
        val dir = cacheDir ?: return
        scope.launch(background) { writeLock.withLock { dir.listFiles()?.forEach { it.delete() } } }
    }

    suspend fun ensureLoaded() {
        if (api == null || _state.value.loaded) return
        load(more = false, user = false)
    }

    suspend fun refresh(more: Boolean = false) = load(more, user = true)

    private suspend fun load(more: Boolean, user: Boolean) {
        val api = api ?: return
        val s = _state.value
        val view = View(s.key, s.filter, s.query, more)
        val pending = inFlight
        if (pending != null && pending.view == view && pending.ticket == generation.get() && pending.job.isActive) {
            pending.job.join() // Identical request already running: share it, never restart.
            return
        }
        val ticket = generation.incrementAndGet()
        val job = scope.launch(start = CoroutineStart.UNDISPATCHED) { fetch(api, ticket, view, user) }
        inFlight = Load(ticket, view, job)
        job.join() // The job lives in `scope`; cancelling this caller must not abort it.
    }

    private suspend fun fetch(api: LibraryApi, ticket: Int, view: View, user: Boolean) {
        fun current() = generation.get() == ticket &&
            _state.value.let { it.key == view.key && it.filter == view.filter && it.query == view.query }
        val isDefault = view.filter == "all" && view.query.isEmpty()
        var silent = false
        try {
            if (!view.more && !_state.value.loaded) _state.update { it.copy(loading = true, error = null) }
            if (isDefault && !view.more && !cacheRead) {
                cacheRead = true
                val snap = readDisk(view.key)
                if (snap != null) {
                    if (defaultView == null) defaultView = snap
                    if (current() && !_state.value.loaded) _state.update {
                        it.copy(patterns = snap.patterns, total = snap.total, loaded = true, loading = false, loadedAtMs = snap.savedAt)
                    }
                    if (!current()) return
                }
            }
            silent = !user && _state.value.loaded
            _state.update { if (silent) it.copy(refreshing = true) else it.copy(loading = true, error = null) }
            val etag = if (!view.more && isDefault && _state.value.loaded) defaultView?.etag else null
            val offset = if (view.more) _state.value.patterns.size else 0
            val page = api.listIfChanged(offset, view.filter, view.query, etag)
            if (page.notModified) {
                if (current()) _state.update { it.copy(loadedAtMs = clock(), error = null) }
                return
            }
            val values = withContext(background) {
                page.patterns.map { it.definition ?: error("服务器尚未升级到新版波形库，请先部署服务端") }.also(WaveformGeometry::prewarm)
            }
            if (!current()) return
            _state.update { s ->
                s.copy(
                    patterns = if (view.more) (s.patterns + values).distinctBy { it.id } else values,
                    total = page.total,
                    loaded = true,
                    loadedAtMs = clock(),
                    error = null,
                )
            }
            if (isDefault) snapshotDefault(if (view.more) defaultView?.etag else page.etag)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            if (current() && !silent) _state.update { it.copy(error = e.message ?: "读取波形库失败") }
        } finally {
            if (current()) _state.update { it.copy(loading = false, refreshing = false) }
        }
    }

    suspend fun select(filter: String, query: String) {
        val s = _state.value
        if (s.filter == filter && s.query == query && s.loaded) return
        generation.incrementAndGet()
        val cached = if (filter == "all" && query.isEmpty()) defaultView else null
        _state.update {
            it.copy(filter = filter, query = query, patterns = cached?.patterns ?: emptyList(), total = cached?.total ?: 0,
                loading = cached == null, refreshing = false, loaded = cached != null, error = null)
        }
        load(more = false, user = false)
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
        if (!isDefaultView()) editDefault { it.copy(patterns = it.patterns.filterNot { p -> p.id == id }, total = (it.total - 1).coerceAtLeast(0)) }
        return index
    }

    suspend fun restore(id: String, index: Int): PatternDefinition {
        val p = PatternDefinition.from((api ?: error("波形库未绑定")).restore(id))
        generation.incrementAndGet()
        _state.update { s ->
            if (s.patterns.any { it.id == id }) s.copy(loading = false, refreshing = false)
            else s.copy(patterns = s.patterns.toMutableList().apply { add(index.coerceIn(0, s.patterns.size), p) },
                total = s.total + 1, loading = false, refreshing = false)
        }
        if (isDefaultView()) snapshotDefault(null) else editDefault { it }
        return p
    }

    fun replace(p: PatternDefinition) {
        generation.incrementAndGet() // A list response captured before a write must not overwrite it.
        _state.update { s -> s.copy(patterns = s.patterns.map { if (it.id == p.id) p else it }, loading = false, refreshing = false) }
        if (isDefaultView()) snapshotDefault(null)
        else editDefault { it.copy(patterns = it.patterns.map { x -> if (x.id == p.id) p else x }) }
    }

    fun removeLocal(id: String) {
        generation.incrementAndGet()
        _state.update { s -> s.copy(patterns = s.patterns.filterNot { it.id == id }, total = (s.total - 1).coerceAtLeast(0), loading = false, refreshing = false) }
        if (isDefaultView()) snapshotDefault(null)
    }

    private fun isDefaultView() = _state.value.filter == "all" && _state.value.query.isEmpty()

    private fun snapshotDefault(etag: String?) {
        val s = _state.value
        val key = s.key ?: return
        defaultView = Snapshot(s.patterns, s.total, etag, clock())
        persist(key, defaultView!!)
    }

    /** Applies a mutation to the mirrored "all" cache; the ETag is always dropped so writes revalidate. */
    private fun editDefault(transform: (Snapshot) -> Snapshot) {
        val key = _state.value.key ?: return
        val snap = defaultView ?: return
        defaultView = transform(snap).copy(etag = null)
        persist(key, defaultView!!)
    }

    private fun sha256Hex(text: String) =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    private suspend fun readDisk(key: String?): Snapshot? {
        val dir = cacheDir ?: return null
        if (key == null) return null
        return withContext(background) {
            runCatching {
                val file = File(dir, sha256Hex(key) + ".json")
                if (!file.isFile) return@runCatching null
                val json = JSONObject(file.readText())
                if (json.getInt("v") != 1) return@runCatching null
                val raw = json.getJSONArray("patterns")
                val patterns = (0 until raw.length()).map { PatternDefinition.from(raw.getJSONObject(it)) }
                WaveformGeometry.prewarm(patterns)
                Snapshot(patterns, json.getInt("total"), if (json.isNull("etag")) null else json.getString("etag"), json.getLong("saved_at"))
            }.getOrNull()
        }
    }

    private fun persist(key: String?, snap: Snapshot) {
        val dir = cacheDir ?: return
        if (key == null) return
        val version = writes.incrementAndGet()
        scope.launch(background) {
            writeLock.withLock {
                if (version != writes.get()) return@withLock
                runCatching {
                    dir.mkdirs()
                    val file = File(dir, sha256Hex(key) + ".json")
                    val tmp = File(dir, file.name + ".tmp")
                    val body = JSONObject().put("v", 1).put("saved_at", snap.savedAt).put("total", snap.total)
                        .put("etag", snap.etag ?: JSONObject.NULL)
                        .put("patterns", JSONArray().apply { snap.patterns.forEach { put(it.toJson()) } })
                    tmp.writeText(body.toString())
                    if (!tmp.renameTo(file)) { file.delete(); check(tmp.renameTo(file)) }
                    dir.listFiles()?.forEach { if (it.name != file.name) it.delete() }
                }
            }
        }
    }
}
