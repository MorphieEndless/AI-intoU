package com.yingti.app.history

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class ActivityHistoryTest {
    private val today = LocalDate.of(2026, 9, 10)
    private fun history() = ActivityHistory().add(OperationEvent("one", 1L, OperationSource.AI, "自定义波形"))
    @Test fun waveformCountsOnlyOnceAcrossUpdates() {
        val h = history().update("one", OperationStatus.RUNNING, true, today)
            .update("one", OperationStatus.RUNNING, true, today)
            .update("one", OperationStatus.DONE, false, today)
        assertEquals(1, h.days[today.toString()])
        assertEquals(OperationStatus.DONE, h.events.first().status)
    }
    @Test fun stopQueriesFailuresAndQueuedCommandsDoNotCount() {
        for (status in OperationStatus.entries) {
            if (status !in setOf(OperationStatus.RUNNING, OperationStatus.SENT)) {
                assertTrue(history().update("one", status, true, today).days.isEmpty())
            }
        }
        assertTrue(history().update("one", OperationStatus.SENT, false, today).days.isEmpty())
    }
    @Test fun countUsesFirstWriteDayNotCreationDay() {
        val h = history().update("one", OperationStatus.RUNNING, true, today.plusDays(1))
            .update("one", OperationStatus.DONE, false, today.plusDays(2))
        assertEquals(mapOf(today.plusDays(1).toString() to 1), h.days)
    }
    @Test fun entriesBoundedButDailyCountsRetained() {
        var h = ActivityHistory()
        repeat(501) { i ->
            h = h.add(OperationEvent("$i", i.toLong(), OperationSource.PHONE, "震动"))
                .update("$i", OperationStatus.SENT, true, today)
        }
        assertEquals(500, h.events.size)
        assertEquals(501, h.days[today.toString()])
        assertEquals("500", h.events.first().id)
    }
    @Test fun recoverMarksUnfinishedAndPreservesCounts() {
        val h = history().update("one", OperationStatus.RUNNING, true, today).recover()
        assertEquals(OperationStatus.INTERRUPTED, h.events.first().status)
        assertEquals(1, h.days[today.toString()])
    }
    @Test fun oneYearRetentionAndClearDoNotInventEvents() {
        val h = history().copy(days = mapOf(today.minusDays(366).toString() to 8))
            .update("one", OperationStatus.SENT, true, today)
        assertEquals(mapOf(today.toString() to 1), h.days)
        assertEquals(ActivityHistory(), ActivityHistory().update("one", OperationStatus.RUNNING, true, today))
    }
}
