package com.yingti.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import com.yingti.app.auth.ConnectionConfig
import com.yingti.app.history.*
import com.yingti.app.patterns.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONObject

@Composable
fun PatternLibraryScreen(config: ConnectionConfig, connected: Boolean, history: ActivityStore, onPlay: (JSONObject) -> Unit) {
    val api = remember(config) { PatternApi(config) }
    val scope = rememberCoroutineScope()
    var patterns by remember(config) { mutableStateOf<List<PatternSummary>>(emptyList()) }
    var total by remember(config) { mutableIntStateOf(0) }
    var loading by remember(config) { mutableStateOf(false) }
    var error by remember(config) { mutableStateOf<String?>(null) }
    var detail by remember(config) { mutableStateOf<JSONObject?>(null) }
    var deleting by remember(config) { mutableStateOf<PatternSummary?>(null) }
    var notice by remember(config) { mutableStateOf<String?>(null) }

    suspend fun load(more: Boolean = false) {
        loading = true; error = null
        try {
            val page = api.list(if (more) patterns.size else 0)
            patterns = if (more) (patterns + page.patterns).distinctBy { it.id } else page.patterns
            total = page.total
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: "读取波形库失败" }
        finally { loading = false }
    }
    LaunchedEffect(api) { load() }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("波形库", style = MaterialTheme.typography.headlineSmall)
                TextButton(enabled = !loading, onClick = { scope.launch { load() } }) { Text("刷新") }
            }
        }
        if (loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        error?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
        notice?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.primary) } }
        if (!loading && patterns.isEmpty() && error == null) item { Text("还没有保存的波形。让 AI 创建并保存后，点击刷新。") }
        items(patterns, key = { it.id }) { pattern ->
            Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(pattern.name, style = MaterialTheme.typography.titleMedium)
                    if (pattern.description.isNotBlank()) Text(pattern.description, maxLines = 3, style = MaterialTheme.typography.bodySmall)
                    Text("${pattern.steps} 步 × ${pattern.repeat} · ${pattern.totalMs / 1000.0} 秒", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(enabled = !loading, onClick = {
                            scope.launch {
                                loading = true; error = null
                                try { detail = api.get(pattern.id) }
                                catch (e: CancellationException) { throw e }
                                catch (e: Exception) { error = e.message ?: "获取详情失败" }
                                finally { loading = false }
                            }
                        }) { Text("查看 / 重放") }
                        TextButton(enabled = !loading, onClick = { deleting = pattern }) { Text("删除") }
                    }
                }
            }
        }
        if (patterns.size < total) item {
            OutlinedButton(enabled = !loading, onClick = { scope.launch { load(true) } }, modifier = Modifier.fillMaxWidth()) {
                Text("加载更多（${patterns.size} / $total）")
            }
        }
    }

    detail?.let { data ->
        AlertDialog(
            onDismissRequest = { if (!loading) detail = null },
            title = { Text(data.optString("name", "波形详情")) },
            text = {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(data.optString("description"))
                    Text("${data.optInt("repeat", 1)} 次循环 · 强度系数 ${data.optDouble("intensity_scale", 1.0)}")
                    WaveformPreview(data)
                    val steps = data.optJSONArray("steps")
                    if (steps != null) repeat(steps.length()) { index ->
                        val step = steps.getJSONObject(index)
                        Text("${index + 1}. ${step.optLong("duration_ms")}ms · 震 ${step.optDouble("vibrate", 0.0)} / 吸 ${step.optDouble("constrict", 0.0)} · 模式 ${if (step.isNull("constrict_mode")) 5 else step.optInt("constrict_mode")}",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    if (!connected) Text("设备未连接，暂不能重放。", color = MaterialTheme.colorScheme.error)
                }
            },
            confirmButton = {
                Button(
                    enabled = connected && !loading,
                    onClick = {
                        scope.launch {
                            loading = true; error = null
                            try {
                                val command = playbackCommand(api.get(data.getString("id")))
                                onPlay(command)
                                notice = "已提交本机重放请求，执行结果请查看日志"
                                detail = null
                            } catch (e: CancellationException) { throw e }
                            catch (e: Exception) { error = e.message ?: "无法重放波形"; detail = null }
                            finally { loading = false }
                        }
                    },
                ) {
                    Text(if (loading) "正在读取…" else "重放")
                }
            },
            dismissButton = { TextButton(enabled = !loading, onClick = { detail = null }) { Text("关闭") } },
        )
    }
    deleting?.let { pattern -> AlertDialog(
        onDismissRequest = { if (!loading) deleting = null }, title = { Text("删除“${pattern.name}”？") },
        text = { Text("从当前用户的云端波形库永久删除，AI 端也将无法再重放它。删除不会停止已在运行的波形。") },
        confirmButton = { TextButton(enabled = !loading, onClick = {
            scope.launch {
                loading = true; error = null
                val id = history.begin(OperationSource.PHONE, "删除云端波形")
                try {
                    api.delete(pattern.id)
                    history.update(id, OperationStatus.DONE)
                    deleting = null
                    load()
                } catch (e: CancellationException) { history.update(id, OperationStatus.INTERRUPTED); throw e }
                catch (e: Exception) { history.update(id, OperationStatus.FAILED); error = e.message ?: "删除失败"; deleting = null }
                finally { loading = false }
            }
        }) { Text("删除") } },
        dismissButton = { TextButton(enabled = !loading, onClick = { deleting = null }) { Text("取消") } },
    ) }
}

@Composable
private fun WaveformPreview(data: JSONObject) {
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.tertiary
    val steps = data.optJSONArray("steps") ?: return
    val scale = data.optDouble("intensity_scale", 1.0).coerceIn(0.0, 1.0)
    val total = (0 until steps.length()).sumOf { steps.getJSONObject(it).optLong("duration_ms") }.coerceAtLeast(1)
    Canvas(Modifier.fillMaxWidth().height(110.dp)) {
        for ((key, color) in listOf("vibrate" to primary, "constrict" to secondary)) {
            var elapsed = 0L
            var previousY = size.height
            for (index in 0 until steps.length()) {
                val step = steps.getJSONObject(index)
                val x = elapsed.toFloat() / total * size.width
                elapsed += step.optLong("duration_ms")
                val endX = elapsed.toFloat() / total * size.width
                val y = size.height * (1 - (step.optDouble(key, 0.0) * scale).coerceIn(0.0, 1.0).toFloat())
                drawLine(color, Offset(x, previousY), Offset(x, y), strokeWidth = 2.dp.toPx())
                drawLine(color, Offset(x, y), Offset(endX, y), strokeWidth = 2.dp.toPx())
                previousY = y
            }
        }
    }
}
