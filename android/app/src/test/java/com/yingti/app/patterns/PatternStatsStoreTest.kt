package com.yingti.app.patterns

import android.app.Application
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class PatternStatsStoreTest {
    private fun file() = File(RuntimeEnvironment.getApplication().filesDir, "pattern-stats-test.json").apply { delete() }

    @Test fun persistsAcrossInstances() {
        val file = file()
        var now = 0L
        val store = PatternStatsStore(file) { ++now }
        store.recordSuccess("a", "甲")
        store.recordSuccess("a", "甲")
        store.recordSuccess("b", "乙")
        val reloaded = PatternStatsStore(file) { 0L }
        assertEquals(2, reloaded.state.value.entries.getValue("a").count)
        assertEquals(listOf("a", "b"), reloaded.topPatterns().map { it.id })
    }

    @Test fun corruptFileStartsEmpty() {
        val file = file()
        file.writeText("not json {{{")
        val store = PatternStatsStore(file)
        assertTrue(store.state.value.entries.isEmpty())
    }

    @Test fun clearIsPersisted() {
        val file = file()
        val store = PatternStatsStore(file)
        store.recordSuccess("a", "甲")
        store.clear()
        assertTrue(PatternStatsStore(file).state.value.entries.isEmpty())
    }
}
