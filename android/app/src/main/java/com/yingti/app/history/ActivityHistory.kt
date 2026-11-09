package com.yingti.app.history

import java.time.LocalDate

enum class OperationSource(val label: String) { AI("AI / MCP"), PHONE("手机"), SYSTEM("系统") }
enum class OperationStatus(val label: String) {
    QUEUED("待处理"), SENT("已提交蓝牙"), RUNNING("手机端运行中"), DONE("手机端已完成"),
    FAILED("失败"), CANCELLED("已停止 / 被替换"), INTERRUPTED("上次进程已结束")
}
data class OperationEvent(
    val id: String, val time: Long, val source: OperationSource, val action: String,
    val status: OperationStatus = OperationStatus.QUEUED, val counted: Boolean = false,
)
data class ActivityHistory(
    val events: List<OperationEvent> = emptyList(),
    val days: Map<String, Int> = emptyMap(),
    val storageError: Boolean = false,
    /** 记录开关：关闭后新操作不再写入日志条目，但「使用频率」热力图照常累计（有意为之）。 */
    val recording: Boolean = true,
    /** 本进程 begin 过、尚未计数的操作 id。只在内存里，不持久化：进程重启即作废。 */
    val pending: List<String> = emptyList(),
) {
    fun add(event: OperationEvent) = copy(
        events = if (recording) (listOf(event) + events).take(500) else events,
        pending = (pending + event.id).takeLast(128),
    )
    fun update(id: String, status: OperationStatus, usage: Boolean, today: LocalDate): ActivityHistory {
        val event = events.find { it.id == id }
        if (id !in pending && event == null) return this
        val count = usage && id in pending && status in setOf(OperationStatus.SENT, OperationStatus.RUNNING)
        val terminal = status in setOf(OperationStatus.DONE, OperationStatus.FAILED,
            OperationStatus.CANCELLED, OperationStatus.INTERRUPTED)
        val key = today.toString()
        val totals = if (count) days + (key to ((days[key] ?: 0) + 1)) else days
        return copy(
            events = events.map { if (it.id == id) it.copy(status = status, counted = it.counted || count) else it },
            pending = if (count || terminal) pending.filterNot { it == id } else pending,
            days = totals.filterKeys { it >= today.minusDays(365).toString() && it <= key },
        )
    }
    /** 只清日志列表；热力图与未完成的计数保留，进行中的操作完成后照常计一次。 */
    fun clearEvents() = copy(events = emptyList())
    /** 破坏性清空：日志 + 热力图全清，仅保留「记录日志」开关与本进程未完成操作。 */
    fun clearAll() = ActivityHistory(recording = recording, pending = pending)
    fun recover() = copy(events = events.map {
        if (it.status in setOf(OperationStatus.QUEUED, OperationStatus.RUNNING))
            it.copy(status = OperationStatus.INTERRUPTED) else it
    })
}
