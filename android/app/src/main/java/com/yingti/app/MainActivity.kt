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
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yingti.app.auth.ConnectionConfig
import com.yingti.app.relay.RelayService
import com.yingti.app.ui.ConnectionSettingsScreen
import com.yingti.app.ui.DashboardScreen
import com.yingti.app.ui.YingtiTheme
import kotlinx.coroutines.launch

enum class AppScreen { DASHBOARD, SETTINGS }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as YingtiApp
        setContent {
            YingtiTheme {
                val context = LocalContext.current
                val scope = rememberCoroutineScope()
                val bridge by AppState.state.collectAsStateWithLifecycle()
                var configured by remember { mutableStateOf(app.tokenStore.isConfigured) }
                var screen by remember { mutableStateOf(if (configured) AppScreen.DASHBOARD else AppScreen.SETTINGS) }
                var loading by remember { mutableStateOf(false) }
                var connectionStatus by remember { mutableStateOf<String?>(null) }
                var connectionError by remember { mutableStateOf<String?>(null) }

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

                fun runConnectionTest(config: ConnectionConfig, password: String, save: Boolean) {
                    loading = true
                    connectionStatus = null
                    connectionError = null
                    scope.launch {
                        app.apiClient.testConnection(config, password)
                            .onSuccess { result ->
                                connectionStatus = result.message
                                if (save) {
                                    val wasConfigured = configured
                                    app.tokenStore.save(result)
                                    configured = true
                                    screen = AppScreen.DASHBOARD
                                    if (wasConfigured && bridge.serviceRunning) {
                                        RelayService.send(context, RelayService.ACTION_RESTART)
                                    }
                                }
                            }
                            .onFailure { connectionError = it.message ?: "连接测试失败" }
                        loading = false
                    }
                }

                LaunchedEffect(configured) {
                    if (configured) {
                        requestPermissionsAndStart()
                        requestBatteryOptimizationExemption(context)
                    }
                }

                when (screen) {
                    AppScreen.SETTINGS -> ConnectionSettingsScreen(
                        initialConfig = app.tokenStore.currentConfig(),
                        loading = loading,
                        status = connectionStatus,
                        error = connectionError,
                        canCancel = configured,
                        onCancel = {
                            connectionStatus = null
                            connectionError = null
                            screen = AppScreen.DASHBOARD
                        },
                        onTest = { config, password -> runConnectionTest(config, password, false) },
                        onSave = { config, password -> runConnectionTest(config, password, true) },
                        onCopy = ::copyToClipboard,
                    )
                    AppScreen.DASHBOARD -> DashboardScreen(
                        state = bridge,
                        server = app.tokenStore.serverBaseUrl,
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
                        onLogout = {
                            RelayService.send(context, RelayService.ACTION_SHUTDOWN)
                            app.tokenStore.clearSession()
                            configured = false
                            connectionStatus = null
                            connectionError = null
                            screen = AppScreen.SETTINGS
                        },
                    )
                }
            }
        }
    }
}
