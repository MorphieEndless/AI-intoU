package com.yingti.app

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yingti.app.auth.TokenStore
import com.yingti.app.relay.RelayService
import com.yingti.app.ui.DashboardScreen
import com.yingti.app.ui.LoginScreen
import com.yingti.app.ui.YingtiTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as YingtiApp
        setContent {
            YingtiTheme {
                val context = LocalContext.current
                val scope = rememberCoroutineScope()
                val bridge by AppState.state.collectAsStateWithLifecycle()
                var loggedIn by remember { mutableStateOf(!app.tokenStore.token.isNullOrBlank()) }
                var loading by remember { mutableStateOf(false) }
                var loginError by remember { mutableStateOf<String?>(null) }

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

                fun requestBatteryOptimizationExemption(context: android.content.Context) {
                    val pm = context.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
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

                LaunchedEffect(loggedIn) {
                    if (loggedIn) {
                        requestPermissionsAndStart()
                        requestBatteryOptimizationExemption(context)
                    }
                }

                if (!loggedIn) {
                    LoginScreen(
                        initialUsername = app.tokenStore.username,
                        initialServer = app.tokenStore.serverBaseUrl,
                        loading = loading,
                        error = loginError,
                    ) { username, password, server ->
                        loading = true
                        loginError = null
                        scope.launch {
                            app.apiClient.login(server, username, password)
                                .onSuccess {
                                    app.tokenStore.serverBaseUrl = TokenStore.normalizeBaseUrl(server)
                                    app.tokenStore.username = it.username
                                    app.tokenStore.token = it.token
                                    loggedIn = true
                                }
                                .onFailure { loginError = it.message ?: "登录失败" }
                            loading = false
                        }
                    }
                } else {
                    DashboardScreen(
                        state = bridge,
                        server = app.tokenStore.serverBaseUrl,
                        onScan = { RelayService.send(context, RelayService.ACTION_SCAN) },
                        onVibrate = { RelayService.send(context, RelayService.ACTION_VIBRATE, it) },
                        onStop = { RelayService.send(context, RelayService.ACTION_STOP_ALL) },
                        onRawFrame = { RelayService.sendRaw(context, it) },
                        onSuction = { RelayService.send(context, RelayService.ACTION_SUCTION, it) },
                        onLogout = {
                            RelayService.send(context, RelayService.ACTION_SHUTDOWN)
                            app.tokenStore.clearSession()
                            loggedIn = false
                        },
                    )
                }
            }
        }
    }
}
