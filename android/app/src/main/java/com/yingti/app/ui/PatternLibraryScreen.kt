package com.yingti.app.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yingti.app.auth.ConnectionConfig
import com.yingti.app.history.*
import com.yingti.app.patterns.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PatternLibraryScreen(
    config: ConnectionConfig, connected: Boolean, history: ActivityStore, onPlay: (JSONObject) -> Unit,
    onStop: () -> Unit = {}, playingId: String? = null, playbackProgress: Float? = null,
    libraryApi: LibraryApi? = null,
) {
    val api = libraryApi ?: remember(config) { PatternApi(config) }
    val scope = rememberCoroutineScope()
    var patterns by remember(api) { mutableStateOf<List<PatternDefinition>>(emptyList()) }
    var total by remember(api) { mutableIntStateOf(0) }
    var loading by remember(api) { mutableStateOf(false) }
    var error by remember(api) { mutableStateOf<String?>(null) }
    var detailId by remember(api) { mutableStateOf<String?>(null) }
    var deleting by remember(api) { mutableStateOf<PatternDefinition?>(null) }
    var undoId by remember(api) { mutableStateOf<String?>(null) }
    var filter by remember(api) { mutableStateOf("all") }
    var query by remember(api) { mutableStateOf("") }
    var busy by remember(api) { mutableStateOf<Set<String>>(emptySet()) }
    var generation by remember(api) { mutableIntStateOf(0) }
    val snackbar = remember(api) { SnackbarHostState() }

    suspend fun load(more: Boolean = false) {
        val ticket = ++generation
        val requestedFilter = filter
        val requestedQuery = query
        val offset = if (more) patterns.size else 0
        loading = true; error = null
        try {
            val page = api.list(offset, requestedFilter, requestedQuery)
            val values = page.patterns.map { it.definition ?: error("服务器尚未升级到新版波形库，请先部署服务端") }
            if (generation == ticket && filter == requestedFilter && query == requestedQuery) {
                patterns = if (more) (patterns + values).distinctBy { it.id } else values
                total = page.total
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { if (generation == ticket) error = e.message ?: "读取波形库失败" }
        finally { if (generation == ticket) loading = false }
    }
    LaunchedEffect(api, filter, query) {
        generation++ // Invalidate an in-flight request as soon as the search changes.
        loading = true
        patterns = emptyList(); total = 0
        if (query.isNotBlank()) delay(250)
        load()
    }
    fun replace(p: PatternDefinition) {
        patterns = patterns.map { if (it.id == p.id) p else it }
    }
    fun update(id: String, fields: JSONObject, onSuccess: () -> Unit = {}) {
        if (id in busy) return
        busy = busy + id
        error = null
        scope.launch {
            try {
                val p = PatternDefinition.from(api.patch(id, fields))
                generation++ // A list response captured before this write must not overwrite it.
                loading = false
                replace(p)
                onSuccess()
                // Keep an open detail alive; filtered cards are reconciled after closing it.
                if (detailId == null && ((filter == "liked" && !p.isLiked) || (filter == "favorites" && !p.isFavorite))) {
                    patterns = patterns.filterNot { it.id == id }; total = (total - 1).coerceAtLeast(0)
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "保存失败，请重试" }
            finally { busy = busy - id }
        }
    }
    fun play(p: PatternDefinition) {
        if (p.id == playingId) { onStop(); return }
        if (!connected || p.id in busy) return
        busy = busy + p.id
        scope.launch {
            try {
                val command = playbackCommand(api.get(p.id))
                // The service independently validates and publishes actual execution state.
                onPlay(command)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "无法重放波形" }
            finally { busy = busy - p.id }
        }
    }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize().testTag("wave-library"), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("波形库", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                    FilledTonalIconButton(enabled = !loading, onClick = { scope.launch { load() } }) { Icon(Icons.Outlined.Refresh, "刷新波形库") }
                }
            }
            item {
                LibraryFilters(filter) { value -> filter = value }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(query, { query = it.take(120) }, Modifier.fillMaxWidth().testTag("wave-search"),
                    placeholder = { Text("搜索名字或备注", fontSize = 13.sp) }, leadingIcon = { Icon(Icons.Outlined.Search, null, Modifier.size(19.dp)) },
                    trailingIcon = if (query.isNotEmpty()) { { IconButton(onClick = { query = "" }) { Icon(Icons.Outlined.Close, "清空搜索") } } } else null,
                    singleLine = true, shape = RoundedCornerShape(15.dp), textStyle = MaterialTheme.typography.bodyMedium,
                    colors = OutlinedTextFieldDefaults.colors(unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                        focusedContainerColor = MaterialTheme.colorScheme.surface, unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .45f)))
            }
            if (loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            error?.let { message -> item {
                Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("wave-error"))
            } }
            if (!loading && patterns.isEmpty() && error == null) item {
                Column(Modifier.fillMaxWidth().padding(vertical = 34.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(if (query.isNotBlank()) "没有找到这段波形" else if (filter == "favorites") "收藏夹还空着" else "还没有标记喜欢的波形", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                    Spacer(Modifier.height(8.dp))
                    Text(if (query.isNotBlank()) "也可以搜索你留下的备注" else if (filter == "favorites") "点亮星星，下次就能直接找到" else "点亮爱心，让 AI 了解你的节奏偏好", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            items(patterns, key = { it.id }) { p ->
                PatternCard(p, connected, p.id == playingId, if (p.id == playingId) playbackProgress else null, p.id in busy,
                    onLike = { update(p.id, JSONObject().put("is_liked", !p.isLiked)) },
                    onFavorite = { update(p.id, JSONObject().put("is_favorite", !p.isFavorite)) },
                    onPlay = { play(p) }, onDetail = { error = null; detailId = p.id },
                    onDelete = { if (p.id != playingId) deleting = p })
            }
            if (patterns.size < total) item {
                TextButton(enabled = !loading, onClick = { scope.launch { load(true) } }, modifier = Modifier.fillMaxWidth()) { Text("加载更多（${patterns.size} / $total）") }
            }
            item { Spacer(Modifier.height(48.dp)) }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }
    val detail = patterns.find { it.id == detailId }
    detail?.let { p ->
        PatternDetailSheet(p, connected, p.id == playingId, p.id in busy, error,
            progress = if (p.id == playingId) playbackProgress else null,
            onClose = { detailId = null; error = null; scope.launch { load() } },
            onLike = { update(p.id, JSONObject().put("is_liked", !p.isLiked)) },
            onFavorite = { update(p.id, JSONObject().put("is_favorite", !p.isFavorite)) },
            onSave = { note, saved -> update(p.id, JSONObject().put("description", note), saved) },
            onPlay = { play(p) })
    }
    deleting?.let { p ->
        AlertDialog(onDismissRequest = { if (p.id !in busy) deleting = null }, title = { Text("删除“${p.name}”？") },
            text = { Text(if (p.id == playingId) "正在重放，请先停止后再删除。" else "从你的波形库中删除，短时间内可以撤销。") },
            confirmButton = { TextButton(enabled = p.id !in busy && p.id != playingId, onClick = {
                busy = busy + p.id
                scope.launch {
                    val event = history.begin(OperationSource.PHONE, "删除云端波形")
                    try {
                        api.delete(p.id)
                        history.update(event, OperationStatus.DONE)
                        generation++; loading = false
                        patterns = patterns.filterNot { it.id == p.id }; total = (total - 1).coerceAtLeast(0)
                        deleting = null; undoId = p.id
                        val id = p.id
                        // UI offer is shorter than the server's six-second deadline.
                        scope.launch { delay(5000); if (undoId == id) { undoId = null; snackbar.currentSnackbarData?.dismiss() } }
                        if (snackbar.showSnackbar("已删除“${p.name}”", "撤销", duration = SnackbarDuration.Indefinite) == SnackbarResult.ActionPerformed && undoId == id) {
                            try { api.restore(id); undoId = null; load() }
                            catch (e: CancellationException) { throw e }
                            catch (e: Exception) { error = e.message ?: "无法撤销删除" }
                        }
                    } catch (e: CancellationException) { history.update(event, OperationStatus.INTERRUPTED); throw e }
                    catch (e: Exception) { history.update(event, OperationStatus.FAILED); error = e.message ?: "删除失败"; deleting = null }
                    finally { busy = busy - p.id }
                }
            }) { Text("删除", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(enabled = p.id !in busy, onClick = { deleting = null }) { Text("取消") } })
    }
}

@Composable
internal fun LibraryFilters(selected: String, onSelect: (String) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.primary.copy(alpha = .075f), shape = RoundedCornerShape(15.dp)) {
        Row(Modifier.fillMaxWidth().padding(4.dp).selectableGroup()) {
            for ((key, text) in listOf("all" to "全部", "liked" to "喜欢", "favorites" to "收藏")) {
                val active = key == selected
                TextButton(onClick = { onSelect(key) }, modifier = Modifier.weight(1f).heightIn(min = 40.dp).semantics {
                    this.selected = active; role = Role.Tab
                }.testTag("filter-$key"), shape = RoundedCornerShape(11.dp),
                    colors = ButtonDefaults.textButtonColors(containerColor = if (active) MaterialTheme.colorScheme.surface else Color.Transparent,
                        contentColor = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)) {
                    if (key != "all") {
                        Icon(if (key == "liked") Icons.Outlined.FavoriteBorder else Icons.Outlined.StarBorder, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(5.dp))
                    }
                    Text(text, fontSize = 13.sp, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal)
                }
            }
        }
    }
}

@Composable
internal fun PreferenceMarks(p: PatternDefinition, busy: Boolean, onLike: () -> Unit, onFavorite: () -> Unit) {
    val dark = MaterialTheme.colorScheme.surface.luminance() < .5f
    val gemini = LocalYingtiPaletteKey.current == "gemini"
    val heart = when { gemini && dark -> Color(0xFFFF85B2); gemini -> Color(0xFFE84D8A); dark -> Color(0xFFFF7597); else -> Color(0xFFDE3B5C) }
    val star = when { gemini && dark -> Color(0xFFFCD34D); gemini -> Color(0xFFF59E0B); dark -> Color(0xFFFBBF24); else -> Color(0xFFE58A1F) }
    Row {
        for (liked in listOf(true, false)) {
            val active = if (liked) p.isLiked else p.isFavorite
            val scale by animateFloatAsState(if (active) 1.08f else 1f, label = "preference-pop")
            val name = if (liked) "喜欢" else "收藏"
            IconButton(onClick = if (liked) onLike else onFavorite, enabled = !busy,
                modifier = Modifier.size(44.dp).testTag("${if (liked) "like" else "favorite"}-${p.id}").semantics {
                    stateDescription = if (active) "已$name" else "未$name"
                    contentDescription = "${if (active) "取消" else ""}$name ${p.name}"
                }) {
                Icon(if (liked) { if (active) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder }
                    else { if (active) Icons.Filled.Star else Icons.Outlined.StarBorder }, null, Modifier.size(21.dp).scale(scale),
                    tint = if (active) { if (liked) heart else star } else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .62f))
            }
        }
    }
}

@Composable
internal fun PatternCard(p: PatternDefinition, connected: Boolean, playing: Boolean, progress: Float?, busy: Boolean,
    onLike: () -> Unit, onFavorite: () -> Unit, onPlay: () -> Unit, onDetail: () -> Unit, onDelete: () -> Unit) {
    Surface(Modifier.fillMaxWidth().testTag("card-${p.id}"), shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(if (playing) 1.5.dp else .5.dp, MaterialTheme.colorScheme.primary.copy(alpha = if (playing) .85f else .08f)), shadowElevation = 2.dp) {
        Column(Modifier.padding(start = 18.dp, end = 18.dp, top = 9.dp, bottom = 13.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(p.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                PreferenceMarks(p, busy, onLike, onFavorite)
            }
            Row(Modifier.fillMaxWidth().padding(top = 1.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(p.durationLabel, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                if (p.description.isNotBlank()) {
                    Text(" · ", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    Text(p.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            WaveformPreview(p, progress = progress)
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = onPlay, enabled = playing || (connected && !busy), modifier = Modifier.testTag("play-${p.id}"),
                    contentPadding = PaddingValues(horizontal = 17.dp, vertical = 9.dp), shape = RoundedCornerShape(12.dp)) {
                    Icon(if (playing) Icons.Filled.Stop else Icons.Filled.PlayArrow, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(5.dp)); Text(if (playing) "停止" else "重放", fontSize = 13.sp)
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDetail, modifier = Modifier.testTag("detail-${p.id}"), contentPadding = PaddingValues(horizontal = 8.dp)) { Text("详情", fontSize = 13.sp) }
                TextButton(onClick = onDelete, enabled = !p.builtin && !busy && !playing, modifier = Modifier.testTag("delete-${p.id}"),
                    contentPadding = PaddingValues(horizontal = 8.dp), colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant)) { Text("删除", fontSize = 13.sp) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PatternDetailSheet(p: PatternDefinition, connected: Boolean, playing: Boolean, busy: Boolean, error: String?, progress: Float?,
    onClose: () -> Unit, onLike: () -> Unit, onFavorite: () -> Unit,
    onSave: (String, () -> Unit) -> Unit, onPlay: () -> Unit) {
    var note by remember(p.id) { mutableStateOf(p.description) }
    var savedNote by remember(p.id) { mutableStateOf(p.description) }
    var saved by remember(p.id) { mutableStateOf(false) }
    var unsaved by remember(p.id) { mutableStateOf(false) }
    var command by remember(p.id) { mutableStateOf(false) }
    val dirty = note != savedNote
    val latestDirty by rememberUpdatedState(dirty)
    val latestBusy by rememberUpdatedState(busy)
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { target ->
        if (target == SheetValue.Hidden && latestDirty) { unsaved = true; false } else !latestBusy
    })
    fun close() { if (dirty) unsaved = true else if (!busy) onClose() }
    fun save(closeAfter: Boolean = false) {
        val value = note
        onSave(value) { savedNote = value.trim(); note = savedNote; saved = true; unsaved = false; if (closeAfter) onClose() }
    }
    ModalBottomSheet(onDismissRequest = { close() }, sheetState = sheet, containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = { BottomSheetDefaults.DragHandle() }) {
        Column(Modifier.fillMaxWidth().heightIn(max = 720.dp).verticalScroll(rememberScrollState()).padding(horizontal = 22.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(p.name, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                PreferenceMarks(p, busy, onLike, onFavorite)
                IconButton(onClick = { close() }, enabled = !busy, modifier = Modifier.testTag("close-detail")) { Icon(Icons.Outlined.Close, "关闭详情") }
            }
            Text("${secondsLabel(p.totalMs)} 秒 · ${if (p.hasChannel("vibrate") && p.hasChannel("constrict")) "双通道波形" else if (p.hasChannel("constrict")) "吮吸波形" else "震动波形"}",
                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(16.dp))
            WaveformPreview(p, command, progress)
            Spacer(Modifier.height(22.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("波形备注", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Spacer(Modifier.weight(1f))
                Text("AI 可选择添加，你也可以在这里修改", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(8.dp))
            Surface(shape = RoundedCornerShape(15.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), color = MaterialTheme.colorScheme.surface) {
                Column {
                    TextField(note, { note = it.take(120); saved = false }, Modifier.fillMaxWidth().testTag("wave-note"),
                        placeholder = { Text("为这段节奏留一点备注", fontSize = 13.sp) }, minLines = 3, maxLines = 5,
                        colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent), textStyle = MaterialTheme.typography.bodySmall)
                    Row(Modifier.fillMaxWidth().padding(end = 8.dp, bottom = 4.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                        if (saved) Text("已保存", color = MaterialTheme.colorScheme.primary, fontSize = 11.sp)
                        IconButton(onClick = { save() }, enabled = dirty && !busy, modifier = Modifier.testTag("save-note")) { Icon(Icons.Outlined.Save, "保存备注", Modifier.size(18.dp)) }
                    }
                }
            }
            Text("${note.length}/120", Modifier.align(Alignment.End).padding(top = 4.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp)) }
            HorizontalDivider(Modifier.padding(vertical = 20.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
            Text("步骤与档位", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth()) {
                for (raw in listOf(false, true)) TextButton(onClick = { command = raw }, modifier = Modifier.weight(1f).testTag(if (raw) "command-view" else "rhythm-view"),
                    colors = ButtonDefaults.textButtonColors(containerColor = if (command == raw) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)) {
                    Text(if (raw) "指令值示意" else "节奏示意", fontSize = 12.sp)
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Text("时长", Modifier.weight(.7f), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("震动档位", Modifier.weight(1.4f), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("吮吸", Modifier.weight(1.4f), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            p.steps.forEach { step ->
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .4f))
                Row(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                    Text("${secondsLabel(step.durationMs)}s", Modifier.weight(.7f), fontSize = 11.sp)
                    val gear = kotlin.math.round(step.vibrate * 10).toInt().coerceIn(0, 10)
                    Text(if (command) step.vibrate.toString() else "$gear · ${WaveformGeometry.vibrationNames[gear]}", Modifier.weight(1.4f), fontSize = 11.sp)
                    Text(if (step.constrict == 0.0) "停止" else if (command) "${step.constrict} · 模式 ${step.mode}" else "${kotlin.math.round(step.constrict * 5).toInt()}/5 · ${WaveformGeometry.suctionNames[step.mode]}", Modifier.weight(1.4f), fontSize = 11.sp)
                }
            }
            Text("图形为节奏示意，未经硬件校准。原始指令与强度系数 ${p.intensityScale} 保持不变。", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp, modifier = Modifier.padding(top = 14.dp))
            if (!connected && !playing) Text("设备未连接，暂不能重放。", color = MaterialTheme.colorScheme.error, fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp))
            Button(onClick = onPlay, enabled = playing || (connected && !busy), modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 24.dp), shape = RoundedCornerShape(13.dp)) {
                Text(if (playing) "停止" else "重放")
            }
        }
    }
    if (unsaved) AlertDialog(onDismissRequest = { if (!busy) unsaved = false }, title = { Text("未保存") }, text = { Text("备注还有未保存的修改。") },
        confirmButton = { TextButton(enabled = !busy, onClick = { save(closeAfter = true) }) { Text("保存并关闭") } },
        dismissButton = { Row {
            TextButton(enabled = !busy, onClick = { unsaved = false }) { Text("继续编辑") }
            TextButton(enabled = !busy, onClick = { note = savedNote; unsaved = false; onClose() }) { Text("放弃修改") }
        } })
}
