package com.yingti.app.history

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class ActivityCodecTest {
    private val today = LocalDate.of(2026, 9, 10)

    @Test fun roundTripKeepsRecordingDaysAndEvents() {
        val h = ActivityHistory(recording = false)
            .add(OperationEvent("a", 5L, OperationSource.PHONE, "震动"))
            .update("a", OperationStatus.RUNNING, true, today)
        val decoded = ActivityCodec.decode(ActivityCodec.encode(h), today)
        assertFalse(decoded.recording)
        assertEquals(h.days, decoded.days)
        assertEquals(h.events, decoded.events)
    }

    @Test fun missingRecordingFieldDefaultsTrue() {
        // v0.19 及更早的存量文件没有 recording 字段。
        val decoded = ActivityCodec.decode("""{"version":1,"events":[],"days":{}}""", today)
        assertTrue(decoded.recording)
    }

    @Test fun pendingIsNotPersisted() {
        val h = ActivityHistory().add(OperationEvent("a", 5L, OperationSource.AI, "波形"))
        val decoded = ActivityCodec.decode(ActivityCodec.encode(h), today)
        assertTrue(decoded.pending.isEmpty())
        assertEquals(1, decoded.events.size)
    }
}
