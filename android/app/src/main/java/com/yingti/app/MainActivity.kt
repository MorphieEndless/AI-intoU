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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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

enum class AppScreen {
    DASHBOARD, PATTERNS, LOGS, SETTINGS,

    /** Sub-pages of 设置: the bottom bar keeps 设置 highlighted, back returns to 设置. */
    AI_ACCESS, ADMIN;

    val parentTab: AppScreen get() = if (this == AI_ACCESS || this == ADMIN) SETTINGS else this
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
                val runningFlow = remember { AppState.state.map { it.serviceRunning }.distinctUntilChanged() }
                val serviceRunning by runningFlow.collectAsStateWithLifecycle(initialValue = false)
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
                val toyFlow = remember { AppState.state.map { it.bleStatus == "已连接" }.distinctUntilChanged() }
                val toyConnected by toyFlow.collectAsStateWithLifecycle(initialValue = false)
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
                                val bridge by AppState.state.collectAsStateWithLifecycle()
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
                                    onStop = { RelayService.send(context, RelayService.ACTION_STOP_ALL) },
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
                                )
                            }
                            AppScreen.PATTERNS -> if (!configured) {
                                PatternLibraryPlaceholder(onSettings = {
                                    connectionStatus = null
                                    connectionError = null
                                    screen = AppScreen.SETTINGS
                                })
                            } else {
                                val connectedFlow = remember {
                                    AppState.state.map { it.serviceRunning && it.bleStatus == "已连接" }.distinctUntilChanged()
                                }
                                val connected by connectedFlow.collectAsStateWithLifecycle(initialValue = false)
                                val playbackFlow = remember { AppState.state.map { it.patternPlayback }.distinctUntilChanged() }
                                val playback by playbackFlow.collectAsStateWithLifecycle(initialValue = null)
                                var progress by remember(playback) { mutableStateOf<Float?>(playback?.progress(System.nanoTime())) }
                                LaunchedEffect(playback) {
                                    while (playback != null) {
                                        progress = playback?.progress(System.nanoTime())
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
                                )
                            }
                            AppScreen.LOGS -> {
                                val history by app.history.state.collectAsStateWithLifecycle()
                                ActivityScreen(history)
                            }
                            AppScreen.SETTINGS -> {
                                val messageFlow = remember { AppState.state.map { it.lastMessage }.distinctUntilChanged() }
                                val lastMessage by messageFlow.collectAsStateWithLifecycle(initialValue = "")
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
                                        onCancel = {
                                            connectionStatus = null
                                            connectionError = null
                                            screen = AppScreen.DASHBOARD
                                        },
                                        onSave = { config, password, rememberPassword ->
                                            saveAndStart(config, password, rememberPassword)
                                        },
                                        onCopy = ::copyToClipboard,
                                        onClearHistory = app.history::clear,
                                        onRegister = ::registerAndStart,
                                        onOpenAiAccess = {
                                            aiWelcome = false
                                            screen = AppScreen.AI_ACCESS
                                        },
                                        configured = configured,
                                        onboarding = {
                                            val step = Onboarding.current(configured, toyConnected, aiAccessDone)
                                            if (Onboarding.visible(step, onboardingDismissed)) {
                                                OnboardingCard(
                                                    step = step,
                                                    toyConnected = toyConnected,
                                                    onOpenToy = { screen = AppScreen.DASHBOARD },
                                                    onOpenAiAccess = {
                                                        aiWelcome = false
                                                        screen = AppScreen.AI_ACCESS
                                                    },
                                                    onDismiss = {
                                                        onboardingDismissed = true
                                                        UiPrefs.setOnboardingDismissed(context, true)
                                                    },
                                                )
                                            }
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
                        }
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
