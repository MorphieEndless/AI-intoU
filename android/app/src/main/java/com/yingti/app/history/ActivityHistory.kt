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
) {
    fun add(event: OperationEvent) = copy(events = (listOf(event) + events).take(500))
    fun update(id: String, status: OperationStatus, usage: Boolean, today: LocalDate): ActivityHistory {
        val event = events.find { it.id == id } ?: return this
        val count = usage && !event.counted && status in setOf(OperationStatus.SENT, OperationStatus.RUNNING)
        val key = today.toString()
        val totals = if (count) days + (key to ((days[key] ?: 0) + 1)) else days
        return copy(
            events = events.map { if (it.id == id) it.copy(status = status, counted = it.counted || count) else it },
            days = totals.filterKeys { it >= today.minusDays(365).toString() && it <= key },
        )
    }
    fun recover() = copy(events = events.map {
        if (it.status in setOf(OperationStatus.QUEUED, OperationStatus.RUNNING))
            it.copy(status = OperationStatus.INTERRUPTED) else it
    })
}
