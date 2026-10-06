package com.yingti.app.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "波形库",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    TextButton(
                        enabled = !loading,
                        onClick = { scope.launch { load() } },
                        modifier = Modifier.semantics { contentDescription = "刷新波形库" },
                    ) {
                        Text("刷新", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
            item {
                LibraryFilters(filter) { value -> filter = value }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it.take(120) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("wave-search"),
                    placeholder = {
                        Text(
                            text = "搜索名字或备注",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        )
                    },
                    leadingIcon = {
                        Icon(Icons.Outlined.Search, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    },
                    trailingIcon = if (query.isNotEmpty()) {
                        {
                            IconButton(onClick = { query = "" }) {
                                Icon(Icons.Outlined.Close, "清空搜索", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    } else null,
                    singleLine = true,
                    shape = RoundedCornerShape(24.dp),
                    textStyle = MaterialTheme.typography.bodyMedium,
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                    ),
                )
            }
            if (loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            error?.let { message -> item {
                Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("wave-error"))
            } }
            if (!loading && patterns.isEmpty() && error == null) item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 36.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = if (query.isNotBlank()) "没有找到这段波形" else if (filter == "favorites") "收藏夹还空着" else "还没有标记喜欢的波形",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = if (query.isNotBlank()) "也可以搜索你留下的备注" else if (filter == "favorites") "点亮星星，下次就能直接找到" else "点亮爱心，让 AI 了解你的节奏偏好",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(patterns, key = { it.id }) { p ->
                PatternCard(
                    p = p,
                    connected = connected,
                    playing = p.id == playingId,
                    progress = if (p.id == playingId) playbackProgress else null,
                    busy = p.id in busy,
                    onLike = { update(p.id, JSONObject().put("is_liked", !p.isLiked)) },
                    onFavorite = { update(p.id, JSONObject().put("is_favorite", !p.isFavorite)) },
                    onPlay = { play(p) },
                    onDetail = { error = null; detailId = p.id },
                    onDelete = { if (p.id != playingId) deleting = p },
                )
            }
            if (patterns.size < total) item {
                TextButton(
                    enabled = !loading,
                    onClick = { scope.launch { load(true) } },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("加载更多（${patterns.size} / $total）", style = MaterialTheme.typography.labelLarge)
                }
            }
            item { Spacer(Modifier.height(48.dp)) }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }
    val detail = patterns.find { it.id == detailId }
    detail?.let { p ->
        PatternDetailSheet(
            p = p,
            connected = connected,
            playing = p.id == playingId,
            busy = p.id in busy,
            error = error,
            progress = if (p.id == playingId) playbackProgress else null,
            onClose = { detailId = null; error = null; scope.launch { load() } },
            onLike = { update(p.id, JSONObject().put("is_liked", !p.isLiked)) },
            onFavorite = { update(p.id, JSONObject().put("is_favorite", !p.isFavorite)) },
            onSave = { note, saved -> update(p.id, JSONObject().put("description", note), saved) },
            onPlay = { play(p) },
        )
    }
    deleting?.let { p ->
        AlertDialog(
            onDismissRequest = { if (p.id !in busy) deleting = null },
            title = { Text("删除“${p.name}”？", style = MaterialTheme.typography.titleLarge) },
            text = { Text(if (p.id == playingId) "正在重放，请先停止后再删除。" else "从你的波形库中删除，短时间内可以撤销。", style = MaterialTheme.typography.bodyMedium) },
            confirmButton = {
                TextButton(
                    enabled = p.id !in busy && p.id != playingId,
                    onClick = {
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
                    },
                ) {
                    Text("删除", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelLarge)
                }
            },
            dismissButton = {
                TextButton(
                    enabled = p.id !in busy,
                    onClick = { deleting = null },
                ) {
                    Text("取消", style = MaterialTheme.typography.labelLarge)
                }
            },
        )
    }
}

@Composable
internal fun LibraryFilters(selected: String, onSelect: (String) -> Unit) {
    val items = remember {
        listOf("all" to "全部", "liked" to "喜欢", "favorites" to "收藏")
    }
    val selectedIndex = when (selected) {
        "liked" -> 1
        "favorites" -> 2
        else -> 0
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        shape = RoundedCornerShape(24.dp),
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .padding(4.dp)
                .height(44.dp),
        ) {
            val tabWidth = maxWidth / 3

            val indicatorOffset by animateDpAsState(
                targetValue = tabWidth * selectedIndex,
                animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
                label = "filter-indicator-offset",
            )

            // 滑动高亮胶囊块
            Box(
                modifier = Modifier
                    .offset(x = indicatorOffset)
                    .width(tabWidth)
                    .fillMaxHeight()
                    .background(
                        color = MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(20.dp),
                    ),
            )

            // 选项列表
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .selectableGroup(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                items.forEach { (key, text) ->
                    val active = key == selected
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(20.dp))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = { onSelect(key) },
                            )
                            .semantics {
                                this.selected = active
                                role = Role.Tab
                            }
                            .testTag("filter-$key"),
                        contentAlignment = Alignment.Center,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            if (key != "all") {
                                Icon(
                                    imageVector = if (key == "liked") Icons.Outlined.FavoriteBorder else Icons.Outlined.StarBorder,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.width(6.dp))
                            }
                            Text(
                                text = text,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
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
            val scale by animateFloatAsState(
                targetValue = if (active) 1.16f else 1f,
                animationSpec = spring(
                    dampingRatio = 0.5f,
                    stiffness = Spring.StiffnessHigh,
                ),
                label = "preference-pop",
            )
            val name = if (liked) "喜欢" else "收藏"
            IconButton(
                onClick = if (liked) onLike else onFavorite,
                enabled = !busy,
                modifier = Modifier
                    .size(44.dp)
                    .testTag("${if (liked) "like" else "favorite"}-${p.id}")
                    .semantics {
                        stateDescription = if (active) "已$name" else "未$name"
                        contentDescription = "${if (active) "取消" else ""}$name ${p.name}"
                    },
            ) {
                Icon(
                    imageVector = if (liked) { if (active) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder }
                                  else { if (active) Icons.Filled.Star else Icons.Outlined.StarBorder },
                    contentDescription = null,
                    modifier = Modifier.size(22.dp).scale(scale),
                    tint = if (active) { if (liked) heart else star } else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .62f),
                )
            }
        }
    }
}

@Composable
internal fun PatternCard(
    p: PatternDefinition, connected: Boolean, playing: Boolean, progress: Float?, busy: Boolean,
    onLike: () -> Unit, onFavorite: () -> Unit, onPlay: () -> Unit, onDetail: () -> Unit, onDelete: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("card-${p.id}"),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(
            width = if (playing) 1.5.dp else 1.dp,
            color = if (playing) MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)
                   else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
        ),
    ) {
        Column(Modifier.padding(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 14.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = p.name,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                PreferenceMarks(p, busy, onLike, onFavorite)
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = p.durationLabel,
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                if (p.description.isNotBlank()) {
                    Text(
                        text = " · ",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = p.description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            WaveformPreview(p, progress = progress)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = onPlay,
                    enabled = playing || (connected && !busy),
                    modifier = Modifier.testTag("play-${p.id}"),
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 9.dp),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Icon(if (playing) Icons.Filled.Stop else Icons.Filled.PlayArrow, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (playing) "停止" else "重放", style = MaterialTheme.typography.labelLarge)
                }
                Spacer(Modifier.weight(1f))
                TextButton(
                    onClick = onDetail,
                    modifier = Modifier.testTag("detail-${p.id}"),
                    contentPadding = PaddingValues(horizontal = 12.dp),
                ) {
                    Text("详情", style = MaterialTheme.typography.labelLarge)
                }
                TextButton(
                    onClick = onDelete,
                    enabled = !p.builtin && !busy && !playing,
                    modifier = Modifier.testTag("delete-${p.id}"),
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
                ) {
                    Text("删除", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PatternDetailSheet(
    p: PatternDefinition, connected: Boolean, playing: Boolean, busy: Boolean, error: String?, progress: Float?,
    onClose: () -> Unit, onLike: () -> Unit, onFavorite: () -> Unit,
    onSave: (String, () -> Unit) -> Unit, onPlay: () -> Unit,
) {
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
    ModalBottomSheet(
        onDismissRequest = { close() },
        sheetState = sheet,
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = { BottomSheetDefaults.DragHandle() },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 720.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = p.name,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                PreferenceMarks(p, busy, onLike, onFavorite)
                IconButton(onClick = { close() }, enabled = !busy, modifier = Modifier.testTag("close-detail")) {
                    Icon(Icons.Outlined.Close, "关闭详情")
                }
            }
            Text(
                text = "${secondsLabel(p.totalMs)} 秒 · ${if (p.hasChannel("vibrate") && p.hasChannel("constrict")) "双通道波形" else if (p.hasChannel("constrict")) "吮吸波形" else "震动波形"}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(16.dp))
            WaveformPreview(p, command, progress)
            Spacer(Modifier.height(22.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("波形备注", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                Text("AI 可选择添加，你也可以在这里修改", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(8.dp))
            Surface(
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                Column {
                    TextField(
                        value = note,
                        onValueChange = { note = it.take(120); saved = false },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("wave-note"),
                        placeholder = {
                            Text(
                                text = "点击输入这段波形的备注",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            )
                        },
                        minLines = 3,
                        maxLines = 5,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                        ),
                        textStyle = MaterialTheme.typography.bodyMedium,
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(end = 8.dp, bottom = 4.dp),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (saved) Text("已保存", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                        IconButton(onClick = { save() }, enabled = dirty && !busy, modifier = Modifier.testTag("save-note")) {
                            Icon(Icons.Outlined.Save, "保存备注", Modifier.size(20.dp))
                        }
                    }
                }
            }
            Text(
                text = "${note.length}/120",
                modifier = Modifier.align(Alignment.End).padding(top = 4.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
            )
            error?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            HorizontalDivider(Modifier.padding(vertical = 20.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
            Text("步骤与档位", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth()) {
                for (raw in listOf(false, true)) {
                    TextButton(
                        onClick = { command = raw },
                        modifier = Modifier.weight(1f).testTag(if (raw) "command-view" else "rhythm-view"),
                        colors = ButtonDefaults.textButtonColors(
                            containerColor = if (command == raw) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                        ),
                    ) {
                        Text(if (raw) "指令值示意" else "节奏示意", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Text("时长", Modifier.weight(.7f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("震动档位", Modifier.weight(1.4f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("吮吸", Modifier.weight(1.4f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            p.steps.forEach { step ->
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .4f))
                Row(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                    Text("${secondsLabel(step.durationMs)}s", Modifier.weight(.7f), style = MaterialTheme.typography.bodyMedium)
                    val gear = kotlin.math.round(step.vibrate * 10).toInt().coerceIn(0, 10)
                    Text(
                        text = if (command) step.vibrate.toString() else "$gear · ${WaveformGeometry.vibrationNames[gear]}",
                        modifier = Modifier.weight(1.4f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = if (step.constrict == 0.0) "停止" else if (command) "${step.constrict} · 模式 ${step.mode}" else "${kotlin.math.round(step.constrict * 5).toInt()}/5 · ${WaveformGeometry.suctionNames[step.mode]}",
                        modifier = Modifier.weight(1.4f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            if (!connected && !playing) {
                Text("设备未连接，暂不能重放。", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 12.dp))
            }
            Button(
                onClick = onPlay,
                enabled = playing || (connected && !busy),
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 24.dp),
                shape = RoundedCornerShape(16.dp),
            ) {
                Text(if (playing) "停止" else "重放", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
    if (unsaved) AlertDialog(
        onDismissRequest = { if (!busy) unsaved = false },
        title = { Text("未保存", style = MaterialTheme.typography.titleLarge) },
        text = { Text("备注还有未保存的修改。", style = MaterialTheme.typography.bodyMedium) },
        confirmButton = {
            TextButton(enabled = !busy, onClick = { save(closeAfter = true) }) {
                Text("保存并关闭", style = MaterialTheme.typography.labelLarge)
            }
        },
        dismissButton = {
            Row {
                TextButton(enabled = !busy, onClick = { unsaved = false }) {
                    Text("继续编辑", style = MaterialTheme.typography.labelLarge)
                }
                TextButton(enabled = !busy, onClick = { note = savedNote; unsaved = false; onClose() }) {
                    Text("放弃修改", style = MaterialTheme.typography.labelLarge)
                }
            }
        },
    )
}
