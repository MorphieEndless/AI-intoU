package com.yingti.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.yingti.app.history.*
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters

@Composable
fun ActivityScreen(history: ActivityHistory) {
    var source by remember { mutableStateOf<OperationSource?>(null) }
    // 页头（标题、热力图、筛选）固定，只有下方日志列表滚动。
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("日志", style = MaterialTheme.typography.headlineSmall)
        }
        UsageHeatmap(history.days)
        if (history.storageError) {
            Text(
                "本地记录写入异常，当前显示可能尚未保存。",
                Modifier.padding(top = 8.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(selected = source == null, onClick = { source = null }, label = { Text("全部") })
            OperationSource.entries.forEach { s ->
                FilterChip(selected = source == s, onClick = { source = s }, label = { Text(s.label) })
            }
        }
        val visible = history.events.filter { source == null || it.source == source }
        LazyColumn(
            Modifier.fillMaxWidth().weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 16.dp),
        ) {
            if (visible.isEmpty()) item { Text("暂无操作记录。连接设备后的新操作会显示在这里。") }
            items(visible, key = { it.id }) { event -> LogEntry(event) }
        }
    }
}

@Composable
private fun LogEntry(event: OperationEvent) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(event.action, style = MaterialTheme.typography.titleMedium)
            Text(
                "${event.source.label} · ${event.status.label}",
                color = if (event.status == OperationStatus.FAILED) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                Instant.ofEpochMilli(event.time).atZone(ZoneId.systemDefault())
                    .format(DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")),
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
private fun UsageHeatmap(days: Map<String, Int>) {
    val today = LocalDate.now()
    val first = today.minusDays(181).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    val weeks = ((java.time.temporal.ChronoUnit.DAYS.between(first, today) / 7) + 1).toInt()
    var selected by remember { mutableStateOf(today) }
    val scheme = MaterialTheme.colorScheme
    val colors = listOf(scheme.surfaceContainerHighest, scheme.primary.copy(alpha = .25f),
        scheme.primary.copy(alpha = .45f), scheme.primary.copy(alpha = .7f), scheme.primary)
    val scroll = rememberScrollState()
    LaunchedEffect(weeks, scroll.maxValue) { scroll.scrollTo(scroll.maxValue) }
    Surface(shape = RoundedCornerShape(24.dp), color = scheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("使用频率", style = MaterialTheme.typography.titleLarge)
            Text("今日 ${days[today.toString()] ?: 0} 次 · 近 7 天 ${(0L..6L).sumOf { days[today.minusDays(it).toString()] ?: 0 }} 次",
                color = scheme.primary)
            Row(Modifier.horizontalScroll(scroll), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                repeat(weeks) { week ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        repeat(7) { weekday ->
                            val date = first.plusDays((week * 7 + weekday).toLong())
                            val count = days[date.toString()] ?: 0
                            val level = when { count == 0 -> 0; count == 1 -> 1; count < 5 -> 2; count < 10 -> 3; else -> 4 }
                            Box(Modifier.size(12.dp)
                                .background(if (date > today) scheme.surface else colors[level], RoundedCornerShape(3.dp))
                                .semantics { contentDescription = if (date > today) "$date 尚未到来" else "$date 使用 $count 次" }
                                .clickable(enabled = date <= today) { selected = date })
                        }
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("少", style = MaterialTheme.typography.labelSmall)
                colors.forEach { color -> Box(Modifier.size(12.dp).background(color, RoundedCornerShape(3.dp))) }
                Text("多 · $selected · ${days[selected.toString()] ?: 0} 次", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
