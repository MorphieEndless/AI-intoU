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
import com.yingti.app.auth.ConnectionConfig
import com.yingti.app.relay.RelayService
import com.yingti.app.ui.*
import kotlinx.coroutines.launch

enum class AppScreen { DASHBOARD, PATTERNS, LOGS, SETTINGS }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as YingtiApp
        setContent {
            val context = LocalContext.current
            var darkTheme by remember { mutableStateOf(UiPrefs.darkTheme(context)) }
            var paletteKey by remember { mutableStateOf(UiPrefs.paletteKey(context)) }
            var devMode by remember { mutableStateOf(UiPrefs.devMode(context)) }
            YingtiTheme(darkTheme = darkTheme, paletteKey = paletteKey) {
                val scope = rememberCoroutineScope()
                val bridge by AppState.state.collectAsStateWithLifecycle()
                var configured by remember { mutableStateOf(app.tokenStore.isConfigured) }
                var screen by rememberSaveable { mutableStateOf(if (configured) AppScreen.DASHBOARD else AppScreen.SETTINGS) }
                var loading by remember { mutableStateOf(false) }
                var connectionStatus by remember { mutableStateOf<String?>(null) }
                var connectionError by remember { mutableStateOf<String?>(null) }
                var splashVisible by remember { mutableStateOf(true) }

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

                fun saveAndStart(config: ConnectionConfig, password: String, rememberPassword: Boolean) {
                    loading = true
                    connectionStatus = null
                    connectionError = null
                    scope.launch {
                        app.apiClient.testConnection(config, password)
                            .onSuccess { result ->
                                connectionStatus = result.message
                                val wasConfigured = configured
                                app.tokenStore.save(result)
                                app.tokenStore.savedPassword = if (rememberPassword) password else ""
                                configured = true
                                screen = AppScreen.DASHBOARD
                                if (wasConfigured && bridge.serviceRunning) {
                                    RelayService.send(context, RelayService.ACTION_RESTART)
                                }
                            }
                            .onFailure { connectionError = it.message ?: "连接失败" }
                        loading = false
                    }
                }

                LaunchedEffect(configured) {
                    if (configured) {
                        requestPermissionsAndStart()
                        requestBatteryOptimizationExemption(context)
                    }
                }

                val history by app.history.state.collectAsStateWithLifecycle()
                fun logout() {
                    RelayService.send(context, RelayService.ACTION_SHUTDOWN)
                    app.tokenStore.clearSession()
                    configured = false
                    connectionStatus = null
                    connectionError = null
                    screen = AppScreen.SETTINGS
                }

                Box(Modifier.fillMaxSize()) {
                    AppNavigation(screen, { screen = it }) {
                        when (screen) {
                            AppScreen.DASHBOARD -> DashboardScreen(
                                state = bridge,
                                server = app.tokenStore.serverBaseUrl,
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
                            AppScreen.PATTERNS -> if (!configured) {
                                PatternLibraryPlaceholder(onSettings = {
                                    connectionStatus = null
                                    connectionError = null
                                    screen = AppScreen.SETTINGS
                                })
                            } else {
                                PatternLibraryScreen(
                                    app.tokenStore.currentConfig(),
                                    bridge.serviceRunning && bridge.bleStatus.contains("已连接"),
                                    app.history,
                                    { RelayService.playPattern(context, it) }
                                )
                            }
                            AppScreen.LOGS -> ActivityScreen(history)
                            AppScreen.SETTINGS -> SettingsPages(
                                devMode, configured, bridge.lastMessage,
                                { RelayService.sendRaw(context, it) }, ::logout
                            ) {
                                ConnectionSettingsScreen(
                                    initialConfig = app.tokenStore.currentConfig(),
                                    initialPassword = app.tokenStore.savedPassword,
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
                                )
                            }
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
