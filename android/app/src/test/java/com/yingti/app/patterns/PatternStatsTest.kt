package com.yingti.app.patterns

import org.junit.Assert.*
import org.junit.Test

class PatternStatsTest {
    private fun stat(id: String, count: Int = 1, last: Long = 0, name: String = id) =
        PatternPlayStat(id, name, count, last)

    @Test fun recordAccumulatesAndUpdatesName() {
        val s = PatternStats()
            .record("a", "旧名", 1)
            .record("a", "新名", 2)
            .record("b", "乙", 3)
        val a = s.entries.getValue("a")
        assertEquals(2, a.count)
        assertEquals("新名", a.name)
        assertEquals(2L, a.lastPlayedAt)
    }

    @Test fun topSortsByCountDescending() {
        val s = PatternStats(mapOf("a" to stat("a", 2), "b" to stat("b", 5), "c" to stat("c", 1)))
        assertEquals(listOf("b", "a", "c"), s.top(3).map { it.id })
    }

    @Test fun tiesBreakByLastPlayedThenName() {
        val s = PatternStats(mapOf(
            "z" to stat("z", 1, last = 10, name = "Zulu"),
            "a" to stat("a", 1, last = 10, name = "Alpha"),
            "r" to stat("r", 1, last = 20, name = "丙"),
        ))
        assertEquals(listOf("r", "a", "z"), s.top(3).map { it.id })
    }

    @Test fun topThreeOfFiveEntries() {
        val s = PatternStats((1..5).associate { "p$it" to stat("p$it", it) })
        assertEquals(listOf("p5", "p4", "p3"), s.top(3).map { it.id })
    }

    @Test fun topWithFewerEntriesAndNonPositiveN() {
        val s = PatternStats(mapOf("a" to stat("a")))
        assertEquals(1, s.top(3).size)
        assertTrue(s.top(0).isEmpty())
        assertTrue(PatternStats().top(3).isEmpty())
    }

    @Test fun capEvictsLowestCountThenOldestNeverTheNewOne() {
        var s = PatternStats()
        repeat(200) { i -> s = s.record("p$i", "n$i", i.toLong()) }
        // p0..p199 各 1 次；给 p100 加一次让它次数变为 2。
        s = s.record("p100", "n100", 500)
        // 新条目进入，淘汰次数最低（1 次）里最久未播的 p0；p100（2 次）与最近播放的 p199 都保留。
        s = s.record("new", "新", 600)
        assertEquals(200, s.entries.size)
        assertFalse(s.entries.containsKey("p0"))
        assertTrue(s.entries.containsKey("new"))
        assertTrue(s.entries.containsKey("p199"))
    }

    @Test fun codecRoundTrip() {
        val s = PatternStats()
            .record("a", "甲", 11)
            .record("a", "甲", 22)
            .record("b", "乙", 33)
        val decoded = PatternStatsCodec.decode(PatternStatsCodec.encode(s))
        assertEquals(s.entries, decoded.entries)
    }

    @Test fun codecToleratesGarbageEntries() {
        val decoded = PatternStatsCodec.decode(
            """{"version":1,"patterns":[{"id":"ok","name":"甲","count":2,"last":5},""" +
                """{"id":"","count":3},{"id":"neg","count":0},{"count":4},"junk"]}"""
        )
        assertEquals(mapOf("ok" to stat("ok", 2, 5, "甲")), decoded.entries)
        assertTrue(PatternStatsCodec.decode("""{"version":1}""").entries.isEmpty())
    }
}
