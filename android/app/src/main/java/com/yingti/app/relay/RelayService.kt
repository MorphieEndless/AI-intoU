package com.yingti.app.relay

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.yingti.app.AppState
import com.yingti.app.MainActivity
import com.yingti.app.R
import com.yingti.app.YingtiApp
import com.yingti.app.ble.BleController
import kotlinx.coroutines.*

class RelayService : Service() {
    private val serviceJob = SupervisorJob()
    private val scope = CoroutineScope(serviceJob + Dispatchers.Default)
    private lateinit var ble: BleController
    private lateinit var dispatcher: CommandDispatcher
    private var relay: RelayClient? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, notification("正在启动"))
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "YingtiBridge::Relay").apply { acquire() }
        ble = BleController(this, scope)
        dispatcher = CommandDispatcher(ble, scope)
        AppState.update { it.copy(serviceRunning = true, lastMessage = "服务已启动") }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action ?: ACTION_START) {
            ACTION_START -> startBridge()
            ACTION_SCAN -> ble.scan()
            ACTION_VIBRATE -> scope.launch { ble.setVibration(intent?.getDoubleExtra(EXTRA_INTENSITY, 0.0) ?: 0.0) }
            ACTION_SUCTION -> scope.launch {
                ble.setSuction(intent?.getDoubleExtra(EXTRA_INTENSITY, 0.0) ?: 0.0, intent?.getIntExtra(EXTRA_MODE, 5) ?: 5)
            }
            ACTION_RAW -> scope.launch {
                val hex = intent?.getStringExtra(EXTRA_HEX)
                if (!hex.isNullOrBlank()) ble.writeRaw(hex)
            }
            ACTION_STOP_ALL -> scope.launch {
                dispatcher.emergencyStop()
                relay?.sendPhoneEmergencyStop()
                updateNotification("已紧急停止")
            }
            ACTION_RESTART -> scope.launch {
                dispatcher.emergencyStop()
                relay?.stop()
                relay = null
                startBridge()
            }
            ACTION_SHUTDOWN -> {
                scope.launch { dispatcher.emergencyStop() }
                stopSelf()
            }
        }
        return START_STICKY
    }

    /** Activity/task 消失不等于用户要求关闭桥接；持续连接由前台服务负责。 */
    override fun onTaskRemoved(rootIntent: Intent?) {
        updateNotification("后台运行中 · 蓝牙与 Relay 保持连接")
        super.onTaskRemoved(rootIntent)
    }

    private fun startBridge() {
        val app = application as YingtiApp
        val token = app.tokenStore.token
        if (token.isNullOrBlank()) {
            AppState.update { it.copy(error = "尚未登录", relayStatus = "缺少令牌") }
            return
        }
        ble.start()
        if (relay == null) {
            relay = RelayClient(scope, dispatcher) { dispatcher.emergencyStop() }.also {
                it.start(app.tokenStore.websocketUrl, token)
            }
        }
        updateNotification("蓝牙与 Relay 正在连接")
    }

    override fun onDestroy() {
        runBlocking { runCatching { dispatcher.emergencyStop() } }
        relay?.stop()
        ble.close()
        wakeLock?.takeIf { it.isHeld }?.release()
        serviceJob.cancel()
        AppState.reset()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notification(status: String): Notification {
        val openIntent = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stopIntent = PendingIntent.getService(this, 1, Intent(this, RelayService::class.java).setAction(ACTION_STOP_ALL), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_yingti)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(status)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, "STOP ALL", stopIntent)
            .build()
    }

    private fun updateNotification(status: String) {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, notification(status))
    }

    private fun createChannel() {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW).apply {
            description = getString(R.string.notification_description)
        })
    }

    companion object {
        const val ACTION_START = "com.yingti.app.START"
        const val ACTION_SCAN = "com.yingti.app.SCAN"
        const val ACTION_VIBRATE = "com.yingti.app.VIBRATE"
        const val ACTION_SUCTION = "com.yingti.app.SUCTION"
        const val ACTION_RAW = "com.yingti.app.RAW"
        const val ACTION_STOP_ALL = "com.yingti.app.STOP_ALL"
        const val ACTION_RESTART = "com.yingti.app.RESTART"
        const val ACTION_SHUTDOWN = "com.yingti.app.SHUTDOWN"
        const val EXTRA_INTENSITY = "intensity"
        const val EXTRA_MODE = "mode"
        const val EXTRA_HEX = "hex"
        private const val CHANNEL_ID = "yingti_relay"
        private const val NOTIFICATION_ID = 589

        fun send(context: Context, action: String, intensity: Double? = null, mode: Int? = null) {
            val intent = Intent(context, RelayService::class.java).setAction(action)
            intensity?.let { intent.putExtra(EXTRA_INTENSITY, it) }
            mode?.let { intent.putExtra(EXTRA_MODE, it) }
            ContextCompat.startForegroundService(context, intent)
        }

        fun sendRaw(context: Context, hex: String) {
            val intent = Intent(context, RelayService::class.java).setAction(ACTION_RAW).putExtra(EXTRA_HEX, hex)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
