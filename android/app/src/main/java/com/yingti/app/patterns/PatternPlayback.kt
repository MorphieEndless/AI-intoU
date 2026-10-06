package com.yingti.app.patterns

/** Phone-side job timeline, not a physical-device acknowledgement or sensor reading. */
data class PatternPlayback(val id: String?, val event: String, val startedNanos: Long, val totalMs: Long) {
    fun progress(nowNanos: Long): Float = ((nowNanos - startedNanos).coerceAtLeast(0) / 1_000_000.0 / totalMs.coerceAtLeast(1)).toFloat().coerceIn(0f, 1f)
}
