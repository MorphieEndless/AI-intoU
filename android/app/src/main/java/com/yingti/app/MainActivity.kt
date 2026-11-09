package com.yingti.app

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yingti.app.auth.AccountSession
import com.yingti.app.auth.ApiClient
import com.yingti.app.auth.AuthMode
import com.yingti.app.auth.ConnectionConfig
import com.yingti.app.auth.ConnectionTestResult
import com.yingti.app.relay.RelayService
import com.yingti.app.ui.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.roundToInt

enum class AppScreen {
    DASHBOARD, PATTERNS, LOGS, SETTINGS,

    /** Sub-pages of 设置: the bottom bar keeps 设置 highlighted, back returns to 设置. */
    AI_ACCESS, ADMIN, UPDATE;

    val parentTab: AppScreen get() = if (this == AI_ACCESS || this == ADMIN || this == UPDATE) SETTINGS else this
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as YingtiApp
        setContent {
            val ready by app.ready.collectAsStateWithLifecycle()
            val initializationError by app.initializationError.collectAsStateWithLifecycle()
            if (!ready) {
                YingtiTheme {
                    Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                        if (initializationError == null) androidx.compose.material3.CircularProgressIndicator()
                        else androidx.compose.material3.Text(initializationError.orEmpty())
                    }
                }
                return@setContent
            }
            val context = LocalContext.current
            var darkTheme by remember { mutableStateOf(UiPrefs.darkTheme(context)) }
            var paletteKey by remember { mutableStateOf(UiPrefs.paletteKey(context)) }
            var devMode by remember { mutableStateOf(UiPrefs.devMode(context)) }
            YingtiTheme(darkTheme = darkTheme, paletteKey = paletteKey) {
                val scope = rememberCoroutineScope()
                val bridge by AppState.state.collectAsStateWithLifecycle()
                val serviceRunning = bridge.serviceRunning
                var connectionConfig by remember { mutableStateOf(app.tokenStore.currentConfig()) }
                var savedPassword by remember { mutableStateOf(app.tokenStore.savedPassword) }
                var configured by remember { mutableStateOf(app.tokenStore.isConfigured) }
                var screen by rememberSaveable { mutableStateOf(if (configured) AppScreen.DASHBOARD else AppScreen.SETTINGS) }
                var loading by remember { mutableStateOf(false) }
                var connectionStatus by remember { mutableStateOf<String?>(null) }
                var connectionError by remember { mutableStateOf<String?>(null) }
                var splashVisible by remember { mutableStateOf(true) }
                var isAdmin by remember { mutableStateOf(app.tokenStore.isAdmin) }
                var aiWelcome by remember { mutableStateOf(false) }
                var aiAccessDone by remember { mutableStateOf(UiPrefs.aiAccessDone(context)) }
                var onboardingDismissed by remember { mutableStateOf(UiPrefs.onboardingDismissed(context)) }
                var onboardingReplay by remember { mutableStateOf<Int?>(null) }
                var deviceAlias by remember { mutableStateOf(UiPrefs.deviceAlias(context)) }
                var overlayEnabled by remember { mutableStateOf(UiPrefs.overlayEnabled(context)) }
                var overlayPermitted by remember { mutableStateOf(android.provider.Settings.canDrawOverlays(context)) }
                var overlayPromptDismissed by remember { mutableStateOf(UiPrefs.overlayPromptDismissed(context)) }
                val toyConnected = bridge.bleStatus == "已连接"
                val accountSession = remember { AccountSession(app.apiClient, app.tokenStore) }
                val deviceLabel = remember { ApiClient.deviceLabel(Build.MODEL) }

                val permissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions()
                ) { RelayService.send(context, RelayService.ACTION_START) }

                fun requestPermissionsAndStart() {
                    val permissions = buildList {
                        if (Build.VERSION.SDK_INT >= 31) {
                            add(Manifest.permission.BLUETOOTH_SCAN)
                            add(Manifest.permission.BLUETOOTH_CONNECT)
                        } else add(Manifest.permission.ACCESS_FINE_LOCATION)
                        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    permissionLauncher.launch(permissions.toTypedArray())
                }

                fun requestBatteryOptimizationExemption(context: Context) {
                    val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
                    if (!pm.isIgnoringBatteryOptimizations(context.packageName)) {
                        runCatching {
                            context.startActivity(
                                Intent(
                                    android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                    android.net.Uri.parse("package:${context.packageName}"),
                                )
                            )
                        }
                    }
                }

                fun copyToClipboard(label: String, value: String) {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
                    Toast.makeText(context, "$label 已复制", Toast.LENGTH_SHORT).show()
                }

                fun runConnect(
                    password: String,
                    rememberPassword: Boolean,
                    afterSuccess: AppScreen,
                    call: suspend () -> Result<ConnectionTestResult>,
                ) {
                    loading = true
                    connectionStatus = null
                    connectionError = null
                    scope.launch {
                        call()
                            .onSuccess { result ->
                                connectionStatus = result.message
                                val wasConfigured = configured
                                connectionConfig = withContext(Dispatchers.IO) {
                                    app.tokenStore.save(result)
                                    app.tokenStore.savedPassword = if (rememberPassword) password else ""
                                    app.tokenStore.currentConfig()
                                }
                                savedPassword = if (rememberPassword) password else ""
                                isAdmin = app.tokenStore.isAdmin
                                configured = true
                                screen = afterSuccess
                                if (wasConfigured && serviceRunning) {
                                    RelayService.send(context, RelayService.ACTION_RESTART)
                                }
                            }
                            .onFailure { connectionError = it.message ?: "连接失败" }
                        loading = false
                    }
                }

                fun saveAndStart(config: ConnectionConfig, password: String, rememberPassword: Boolean) =
                    runConnect(password, rememberPassword, AppScreen.DASHBOARD) {
                        app.apiClient.testConnection(config, password, deviceLabel)
                    }

                fun registerAndStart(config: ConnectionConfig, invite: String, password: String, rememberPassword: Boolean) {
                    aiWelcome = true
                    runConnect(password, rememberPassword, AppScreen.AI_ACCESS) {
                        app.apiClient.registerAndConnect(config, invite, password, deviceLabel)
                    }
                }

                LaunchedEffect(configured) {
                    if (configured) {
                        requestPermissionsAndStart()
                        requestBatteryOptimizationExemption(context)
                    }
                }

                LaunchedEffect(configured, connectionConfig) {
                    if (configured) {
                        app.patternLibrary.bind(connectionConfig)
                        app.patternLibrary.ensureLoaded()
                    } else app.patternLibrary.clear()
                }

                LifecycleResumeEffect(serviceRunning, configured, overlayEnabled) {
                    fun syncOverlay() {
                        val permitted = android.provider.Settings.canDrawOverlays(context)
                        overlayPermitted = permitted
                        if (serviceRunning || (configured && overlayEnabled && permitted)) {
                            RelayService.send(context, RelayService.ACTION_OVERLAY_REFRESH)
                        }
                    }
                    syncOverlay()
                    val recheckJob = scope.launch {
                        delay(350)
                        syncOverlay()
                        delay(750)
                        syncOverlay()
                    }
                    onPauseOrDispose { recheckJob.cancel() }
                }

                fun requestOverlayPermission() {
                    runCatching {
                        context.startActivity(
                            Intent(
                                android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                android.net.Uri.parse("package:${context.packageName}"),
                            )
                        )
                    }
                }

                fun logout() {
                    RelayService.send(context, RelayService.ACTION_SHUTDOWN)
                    app.tokenStore.clearSession()
                    connectionConfig = connectionConfig.copy(token = "")
                    configured = false
                    isAdmin = false
                    connectionStatus = null
                    connectionError = null
                    screen = AppScreen.SETTINGS
                }

                Box(Modifier.fillMaxSize()) {
                    AppNavigation(screen, { screen = it }) {
                        when (screen) {
                            AppScreen.DASHBOARD -> {
                                DashboardScreen(
                                    state = bridge,
                                    server = connectionConfig.normalizedBaseUrl,
                                    darkTheme = darkTheme,
                                    devMode = false,
                                    onToggleTheme = {
                                        darkTheme = !darkTheme
                                        UiPrefs.setDarkTheme(context, darkTheme)
                                    },
                                    onScan = { RelayService.send(context, RelayService.ACTION_SCAN) },
                                    onVibrate = { RelayService.send(context, RelayService.ACTION_VIBRATE, it) },
                                    onStop = { RelayService.send(context, RelayService.ACTION_TOGGLE_PAUSE) },
                                    onRawFrame = { RelayService.sendRaw(context, it) },
                                    onSuction = { intensity, mode ->
                                        RelayService.send(context, RelayService.ACTION_SUCTION, intensity, mode)
                                    },
                                    onSettings = {
                                        connectionStatus = null
                                        connectionError = null
                                        screen = AppScreen.SETTINGS
                                    },
                                    onLogout = ::logout,
                                    deviceAlias = deviceAlias,
                                    onRenameDevice = {
                                        deviceAlias = it
                                        UiPrefs.setDeviceAlias(context, it)
                                    },
                                )
                            }
                            AppScreen.PATTERNS -> if (!configured) {
                                PatternLibraryPlaceholder(onSettings = {
                                    connectionStatus = null
                                    connectionError = null
                                    screen = AppScreen.SETTINGS
                                })
                            } else {
                                val connected = bridge.serviceRunning && bridge.bleStatus == "已连接"
                                val playback = bridge.patternPlayback
                                var progress by remember(playback) { mutableStateOf<Float?>(playback?.progress(System.nanoTime())) }
                                LaunchedEffect(playback) {
                                    while (playback != null) {
                                        progress = playback.progress(System.nanoTime())
                                        delay(100)
                                    }
                                }
                                PatternLibraryScreen(
                                    connectionConfig,
                                    connected,
                                    app.history,
                                    { RelayService.playPattern(context, it) },
                                    onStop = { RelayService.send(context, RelayService.ACTION_STOP_ALL) },
                                    playingId = playback?.id,
                                    playbackProgress = progress,
                                    repository = app.patternLibrary,
                                    paused = playback?.paused == true,
                                    onPause = { RelayService.send(context, RelayService.ACTION_PAUSE) },
                                    onResume = { RelayService.send(context, RelayService.ACTION_RESUME) },
                                )
                            }
                            AppScreen.LOGS -> {
                                val history by app.history.state.collectAsStateWithLifecycle()
                                ActivityScreen(
                                    history,
                                    onRecordingChange = app.history::setRecording,
                                    onClearEvents = app.history::clearEvents,
                                )
                            }
                            AppScreen.SETTINGS -> {
                                val lastMessage = bridge.lastMessage
                                SettingsPages(
                                    devMode, configured, lastMessage,
                                    { RelayService.sendRaw(context, it) }, ::logout,
                                    showAdmin = configured && isAdmin && connectionConfig.authMode == AuthMode.ACCOUNT,
                                    onAdmin = { screen = AppScreen.ADMIN },
                                ) {
                                    ConnectionSettingsScreen(
                                        initialConfig = connectionConfig,
                                        initialPassword = savedPassword,
                                        loading = loading,
                                        status = connectionStatus,
                                        error = connectionError,
                                        canCancel = false,
                                        darkTheme = darkTheme,
                                        paletteKey = paletteKey,
                                        devMode = devMode,
                                        onDarkThemeChange = {
                                            darkTheme = it
                                            UiPrefs.setDarkTheme(context, it)
                                        },
                                        onPaletteChange = {
                                            paletteKey = it
                                            UiPrefs.setPaletteKey(context, it)
                                        },
                                        onDevModeChange = {
                                            devMode = it
                                            UiPrefs.setDevMode(context, it)
                                        },
                                        overlayEnabled = overlayEnabled,
                                        overlayPermitted = overlayPermitted,
                                        onOverlayChange = {
                                            overlayEnabled = it
                                            UiPrefs.setOverlayEnabled(context, it)
                                            if (serviceRunning || configured) RelayService.send(context, RelayService.ACTION_OVERLAY_REFRESH)
                                        },
                                        onRequestOverlayPermission = ::requestOverlayPermission,
                                        onCancel = {
                                            connectionStatus = null
                                            connectionError = null
                                            screen = AppScreen.DASHBOARD
                                        },
                                        onSave = { config, password, rememberPassword ->
                                            saveAndStart(config, password, rememberPassword)
                                        },
                                        onCopy = ::copyToClipboard,
                                        onClearHistory = {
                                            app.history.clear()
                                            app.patternStats.clear()
                                        },
                                        onRegister = ::registerAndStart,
                                        onOpenAiAccess = {
                                            aiWelcome = false
                                            screen = AppScreen.AI_ACCESS
                                        },
                                        onCheckUpdate = { screen = AppScreen.UPDATE },
                                        configured = configured,
                                        onboarding = {
                                            val step = Onboarding.current(configured, toyConnected, aiAccessDone)
                                            if (Onboarding.visible(step, onboardingDismissed, replaying = onboardingReplay != null)) {
                                                OnboardingCard(
                                                    step = step,
                                                    toyConnected = toyConnected,
                                                    onOpenToy = { screen = AppScreen.DASHBOARD },
                                                    onOpenAiAccess = {
                                                        aiWelcome = false
                                                        screen = AppScreen.AI_ACCESS
                                                    },
                                                    onDismiss = {
                                                        onboardingReplay = null
                                                        onboardingDismissed = true
                                                        UiPrefs.setOnboardingDismissed(context, true)
                                                    },
                                                    replayIndex = onboardingReplay,
                                                    onReplayNext = {
                                                        onboardingReplay = onboardingReplay?.let(Onboarding::nextReplay)
                                                    },
                                                )
                                            }
                                        },
                                        onReplayOnboarding = {
                                            onboardingDismissed = false
                                            UiPrefs.setOnboardingDismissed(context, false)
                                            onboardingReplay = 0
                                        },
                                    )
                                }
                            }
                            AppScreen.AI_ACCESS -> AiAccessScreen(
                                api = if (connectionConfig.authMode == AuthMode.ACCOUNT) accountSession else null,
                                config = connectionConfig,
                                phoneTokenId = app.tokenStore.phoneTokenId,
                                welcome = aiWelcome,
                                onBack = { screen = AppScreen.SETTINGS },
                                onCopy = ::copyToClipboard,
                                onHasAiAccess = {
                                    aiAccessDone = true
                                    UiPrefs.setAiAccessDone(context, true)
                                },
                            )
                            AppScreen.ADMIN -> AdminScreen(
                                api = accountSession,
                                serverUrl = connectionConfig.normalizedBaseUrl,
                                onBack = { screen = AppScreen.SETTINGS },
                                onCopy = ::copyToClipboard,
                            )
                            AppScreen.UPDATE -> UpdateScreen(
                                updater = app.updater,
                                onBack = { screen = AppScreen.SETTINGS },
                            )
                        }
                    }

                    // 当系统级悬浮球尚未挂载（如尚未授予悬浮窗权限或服务未就绪）时，在应用内顶层无缝展示同一颗 56dp 红色圆形悬浮球；
                    // 一旦系统级悬浮球挂载成功（切后台也持续存在），应用内悬浮球自动让位避免重叠。
                    if (!splashVisible && overlayEnabled && !bridge.systemOverlayShowing) {
                        InAppFloatingBall(
                            control = bridge.toyControl,
                            onClick = { RelayService.send(context, RelayService.ACTION_TOGGLE_PAUSE) },
                        )
                    }

                    if (!splashVisible && configured && overlayEnabled && !overlayPermitted && !overlayPromptDismissed) {
                        AlertDialog(
                            onDismissRequest = {
                                overlayPromptDismissed = true
                                UiPrefs.setOverlayPromptDismissed(context, true)
                            },
                            title = { androidx.compose.material3.Text("开启后台悬浮球") },
                            text = { androidx.compose.material3.Text("授予“显示在其他应用上层”权限后，就算把樱趣切到后台，红色悬浮球也会持续留在屏幕上供你随时暂停 / 继续。") },
                            confirmButton = {
                                androidx.compose.material3.TextButton(onClick = {
                                    overlayPromptDismissed = true
                                    UiPrefs.setOverlayPromptDismissed(context, true)
                                    requestOverlayPermission()
                                }) { androidx.compose.material3.Text("去开启") }
                            },
                            dismissButton = {
                                androidx.compose.material3.TextButton(onClick = {
                                    overlayPromptDismissed = true
                                    UiPrefs.setOverlayPromptDismissed(context, true)
                                }) { androidx.compose.material3.Text("暂不") }
                            },
                        )
                    }

                    // 开屏启动过渡动画：顺时针舒展绽放，约 650ms 展开就绪后平滑淡出，消除白屏且不拖延加载
                    AnimatedVisibility(
                        visible = splashVisible,
                        exit = fadeOut(animationSpec = tween(durationMillis = 260))
                    ) {
                        SakuraSplashScreen(onFinished = { splashVisible = false })
                    }
                }
            }
        }
    }
}

@Composable
private fun InAppFloatingBall(
    control: ToyControl,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val ballSizePx = with(density) { 56.dp.toPx() }
    val defaultMarginPx = with(density) { 16.dp.toPx() }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val maxW = constraints.maxWidth.toFloat().coerceAtLeast(ballSizePx)
        val maxH = constraints.maxHeight.toFloat().coerceAtLeast(ballSizePx)
        val limitX = (maxW - ballSizePx).coerceAtLeast(0f)
        val limitY = (maxH - ballSizePx).coerceAtLeast(0f)

        var offsetX by remember(maxW) {
            val saved = UiPrefs.overlayX(context)
            mutableFloatStateOf(
                if (saved >= 0) saved.toFloat().coerceIn(0f, limitX)
                else (maxW - ballSizePx - defaultMarginPx).coerceIn(0f, limitX)
            )
        }
        var offsetY by remember(maxH) {
            val saved = UiPrefs.overlayY(context)
            mutableFloatStateOf(
                if (saved >= 0) saved.toFloat().coerceIn(0f, limitY)
                else (maxH / 3f).coerceIn(0f, limitY)
            )
        }

        Box(
            modifier = Modifier
                .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
                .size(56.dp)
                .shadow(6.dp, CircleShape)
                .clip(CircleShape)
                .background(Color(0xFFB3261E), CircleShape)
                .border(1.5.dp, Color.White.copy(alpha = 0.2f), CircleShape)
                .pointerInput(limitX, limitY) {
                    detectDragGestures(
                        onDragEnd = {
                            UiPrefs.setOverlayPosition(context, offsetX.roundToInt(), offsetY.roundToInt())
                        },
                        onDragCancel = {
                            UiPrefs.setOverlayPosition(context, offsetX.roundToInt(), offsetY.roundToInt())
                        },
                    ) { change, dragAmount ->
                        change.consume()
                        offsetX = (offsetX + dragAmount.x).coerceIn(0f, limitX)
                        offsetY = (offsetY + dragAmount.y).coerceIn(0f, limitY)
                    }
                }
                .clickable(onClick = onClick)
                .testTag("floating-overlay-ball"),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = when (control) {
                    ToyControl.RUNNING -> Icons.Filled.Pause
                    ToyControl.PAUSED -> Icons.Filled.PlayArrow
                    ToyControl.IDLE -> Icons.Filled.Stop
                },
                contentDescription = when (control) {
                    ToyControl.RUNNING -> "暂停全部输出"
                    ToyControl.PAUSED -> "继续播放"
                    ToyControl.IDLE -> "STOP ALL"
                },
                tint = Color.White,
                modifier = Modifier.size(28.dp),
            )
        }
    }
}
