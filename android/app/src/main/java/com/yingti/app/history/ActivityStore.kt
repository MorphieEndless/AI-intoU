package com.yingti.app.history

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.util.UUID

/** App-private, atomic local journal. No credentials, network payloads or cloud upload. */
class ActivityStore(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "activity-history.json"))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val changes = Channel<(ActivityHistory) -> ActivityHistory>(Channel.UNLIMITED)
    private val mutable = MutableStateFlow(ActivityHistory())
    val state = mutable.asStateFlow()

    init {
        scope.launch {
            mutable.value = try { decode(file.openRead().bufferedReader().use { it.readText() }).recover() }
            catch (_: java.io.FileNotFoundException) { ActivityHistory() }
            catch (_: Exception) { ActivityHistory(storageError = true) }
            for (change in changes) {
                var next = change(mutable.value)
                // A command generates several status transitions. Keep their order
                // and usage counts, but avoid an fsync for every transition.
                delay(50)
                var drained = 0
                while (drained < 127) {
                    val pending = changes.tryReceive().getOrNull() ?: break
                    next = pending(next)
                    drained++
                }
                try {
                    val stream = file.startWrite()
                    try {
                        stream.write(encode(next).toByteArray(Charsets.UTF_8))
                        file.finishWrite(stream)
                    } catch (e: Exception) { file.failWrite(stream); throw e }
                    mutable.value = next.copy(storageError = false)
                } catch (_: Exception) { mutable.value = next.copy(storageError = true) }
            }
        }
    }
    fun begin(source: OperationSource, action: String): String {
        val event = OperationEvent(UUID.randomUUID().toString(), System.currentTimeMillis(), source, action.take(100))
        changes.trySend { it.add(event) }
        return event.id
    }
    fun update(id: String, status: OperationStatus, usage: Boolean = false) {
        val day = LocalDate.now()
        changes.trySend { it.update(id, status, usage, day) }
    }
    fun clear() { changes.trySend { ActivityHistory() } }

    private fun encode(history: ActivityHistory): String = JSONObject().apply {
        put("version", 1)
        put("events", JSONArray().apply { history.events.forEach { e ->
            put(JSONObject().put("id", e.id).put("time", e.time).put("source", e.source.name)
                .put("action", e.action).put("status", e.status.name).put("counted", e.counted))
        } })
        put("days", JSONObject(history.days))
    }.toString()

    private fun decode(text: String): ActivityHistory {
        val json = JSONObject(text)
        val entries = json.getJSONArray("events")
        val events = (0 until minOf(entries.length(), 500)).map { index ->
            val e = entries.getJSONObject(index)
            OperationEvent(e.getString("id"), e.getLong("time"), OperationSource.valueOf(e.getString("source")),
                e.getString("action"), OperationStatus.valueOf(e.getString("status")), e.optBoolean("counted"))
        }
        val days = json.getJSONObject("days")
        val today = LocalDate.now()
        return ActivityHistory(events, days.keys().asSequence().associateWith { days.getInt(it) }
            .filterKeys { it >= today.minusDays(365).toString() && it <= today.toString() })
    }
}
