package com.yingti.app.patterns

/** Phone-side job timeline, not a physical-device acknowledgement or sensor reading. */
data class PatternPlayback(val id: String?, val event: String, val startedNanos: Long, val totalMs: Long, val pausedAtMs: Long? = null) {
    val paused get() = pausedAtMs != null
    fun elapsedMs(nowNanos: Long): Double =
        pausedAtMs?.toDouble() ?: ((nowNanos - startedNanos).coerceAtLeast(0) / 1_000_000.0)
    fun progress(nowNanos: Long): Float = (elapsedMs(nowNanos) / totalMs.coerceAtLeast(1)).toFloat().coerceIn(0f, 1f)
    fun pausedAt(nowNanos: Long) = if (paused) this else copy(pausedAtMs = elapsedMs(nowNanos).toLong())
    fun resumedAt(nowNanos: Long) = pausedAtMs?.let { copy(startedNanos = nowNanos - it * 1_000_000, pausedAtMs = null) } ?: this
}
