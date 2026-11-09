package com.yingti.app.patterns

import android.util.AtomicFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class PatternPlayStat(val id: String, val name: String, val count: Int, val lastPlayedAt: Long)

data class PatternStats(val entries: Map<String, PatternPlayStat> = emptyMap()) {
    /** 记一次成功播放：次数 +1、名字更新为最新、lastPlayedAt = at。超出上限时先淘汰次数最少、再淘汰最久未播；刚记录的条目永不淘汰。 */
    fun record(id: String, name: String, at: Long): PatternStats {
        val prev = entries[id]
        val next = entries + (id to PatternPlayStat(id, name, (prev?.count ?: 0) + 1, at))
        if (next.size <= MAX_ENTRIES) return PatternStats(next)
        val evict = next.entries.filter { it.key != id }
            .minWith(compareBy<Map.Entry<String, PatternPlayStat>>({ it.value.count }, { it.value.lastPlayedAt }))
            .key
        return PatternStats(next - evict)
    }

    /** 次数降序 → 最近播放降序 → 名字升序 → id 升序。 */
    fun top(n: Int = 3): List<PatternPlayStat> =
        if (n <= 0) emptyList()
        else entries.values.sortedWith(
            compareByDescending<PatternPlayStat> { it.count }
                .thenByDescending { it.lastPlayedAt }
                .thenBy { it.name }
                .thenBy { it.id }
        ).take(n)

    private companion object { const val MAX_ENTRIES = 200 }
}

/** 成功播放的接收方：波形自然播完（status == DONE）时记一次，被停止 / 替换 / 失败不计。 */
fun interface PatternPlayRecorder {
    fun recordSuccess(id: String, name: String)
}

internal object PatternStatsCodec {
    fun encode(stats: PatternStats): String = JSONObject().apply {
        put("version", 1)
        put("patterns", JSONArray().apply {
            stats.entries.values.forEach { s ->
                put(JSONObject().put("id", s.id).put("name", s.name)
                    .put("count", s.count).put("last", s.lastPlayedAt))
            }
        })
    }.toString()

    fun decode(text: String): PatternStats {
        val array = JSONObject(text).optJSONArray("patterns") ?: return PatternStats()
        val entries = buildMap {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val id = item.optString("id")
                val count = item.optInt("count", 0)
                if (id.isBlank() || count <= 0) continue
                put(id, PatternPlayStat(id, item.optString("name", id), count, item.optLong("last", 0L)))
            }
        }
        return PatternStats(entries)
    }
}

/** App-private 本地播放统计：原子文件落盘，无网络。文件极小，同步写即可。 */
class PatternStatsStore(
    file: File,
    private val clock: () -> Long = System::currentTimeMillis,
) : PatternPlayRecorder {
    private val atomic = AtomicFile(file)
    private val mutable = MutableStateFlow(load())
    val state: StateFlow<PatternStats> = mutable.asStateFlow()

    private fun load(): PatternStats = try {
        PatternStatsCodec.decode(atomic.openRead().bufferedReader().use { it.readText() })
    } catch (_: Exception) { PatternStats() } // 缺文件或损坏都按空统计起步，不崩溃。

    @Synchronized
    override fun recordSuccess(id: String, name: String) {
        mutable.value = mutable.value.record(id, name, clock())
        persist()
    }

    fun topPatterns(n: Int = 3) = state.value.top(n)

    @Synchronized
    fun clear() {
        mutable.value = PatternStats()
        persist()
    }

    private fun persist() {
        val stream = try { atomic.startWrite() } catch (_: Exception) { return }
        try {
            stream.write(PatternStatsCodec.encode(mutable.value).toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
        } catch (_: Exception) {
            atomic.failWrite(stream)
        }
    }
}
