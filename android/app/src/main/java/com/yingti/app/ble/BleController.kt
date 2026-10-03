package com.yingti.app.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.yingti.app.AppState
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

class BleController(private val context: Context, private val scope: CoroutineScope) {
    private val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    private var scannerCallback: ScanCallback? = null
    private var scanTimeoutJob: Job? = null
    private var reconnectJob: Job? = null
    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private val writeMutex = Mutex()
    private val frameCache = FrameCache()
    private var lastWriteAt = 0L
    @Volatile private var shouldRun = true
    @Volatile private var connectionGeneration = 0L

    // 当前输出状态：用于固件看门狗保活，以及断线重连后自动恢复输出。
    @Volatile private var currentVibrateLevel = 0
    @Volatile private var currentSuctionLevel = 0
    @Volatile private var currentSuctionMode = SvakomProtocol.SUCTION_DEFAULT_MODE
    private var keepAliveJob: Job? = null

    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(receiverContext: Context?, intent: Intent?) {
            if (intent?.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                BluetoothAdapter.STATE_ON -> {
                    AppState.update { it.copy(bleStatus = "正在重新连接…", error = null) }
                    if (shouldRun) scheduleScan(250)
                }
                BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> {
                    invalidateConnection()
                    stopScan()
                    AppState.update { it.copy(bleStatus = "蓝牙未开启", intensity = 0, suctionIntensity = 0) }
                }
            }
        }
    }

    init {
        ContextCompat.registerReceiver(
            context,
            bluetoothStateReceiver,
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    val isConnected: Boolean get() = gatt != null && writeCharacteristic != null

    fun start() {
        shouldRun = true
        scan()
    }

    @SuppressLint("MissingPermission")
    fun scan() {
        if (!shouldRun) return
        if (!hasBlePermissions()) {
            AppState.update { it.copy(bleStatus = "缺少蓝牙权限", error = "请授予附近设备权限") }
            return
        }
        if (!adapter.isEnabled) {
            AppState.update { it.copy(bleStatus = "蓝牙未开启") }
            return
        }
        if (isConnected) {
            AppState.update { it.copy(bleStatus = "已连接", error = null) }
            return
        }
        stopScan()
        AppState.update { it.copy(bleStatus = "正在扫描…", error = null) }
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = runCatching { result.device.name }.getOrNull()
                    ?: result.scanRecord?.deviceName
                    ?: return
                if (name.equals("SX589B", true) || name.startsWith("SX", true) || name.startsWith("SL", true)) {
                    stopScan()
                    connect(result.device, name)
                }
            }

            override fun onScanFailed(errorCode: Int) {
                stopScan()
                AppState.update { it.copy(bleStatus = "扫描失败 $errorCode") }
                scheduleScan(1_000)
            }
        }
        scannerCallback = callback
        adapter.bluetoothLeScanner?.startScan(callback)
        scanTimeoutJob = scope.launch {
            delay(10_000)
            if (scannerCallback === callback) {
                stopScan()
                if (!isConnected) {
                    AppState.update { it.copy(bleStatus = "未发现 SX589B") }
                    scheduleScan(1_000)
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun connect(device: BluetoothDevice, name: String) {
        if (!shouldRun) return
        val generation = ++connectionGeneration
        val oldGatt = gatt
        gatt = null
        writeCharacteristic = null
        runCatching { oldGatt?.disconnect() }
        runCatching { oldGatt?.close() }
        AppState.update { it.copy(bleStatus = "正在连接…", deviceName = name) }
        val newGatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        } else device.connectGatt(context, false, callback)
        gatt = newGatt
        if (generation != connectionGeneration) runCatching { newGatt.close() }
    }

    private fun isCurrent(g: BluetoothGatt): Boolean = gatt === g

    private fun invalidateConnection() {
        connectionGeneration++
        val oldGatt = gatt
        gatt = null
        writeCharacteristic = null
        runCatching { oldGatt?.disconnect() }
        runCatching { oldGatt?.close() }
    }

    private val callback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (!isCurrent(g)) {
                runCatching { g.close() }
                return
            }
            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                AppState.update { it.copy(bleStatus = "发现服务…") }
                if (!g.discoverServices()) handleDisconnect(g, "服务发现启动失败")
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                handleDisconnect(g, "已断开")
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (!isCurrent(g)) {
                runCatching { g.close() }
                return
            }
            val service = g.getService(UUID.fromString(SvakomProtocol.SERVICE_UUID))
            val characteristic = service?.getCharacteristic(UUID.fromString(SvakomProtocol.WRITE_UUID))
            if (status != BluetoothGatt.GATT_SUCCESS || characteristic == null) {
                AppState.update { it.copy(bleStatus = "不兼容：缺少 FFE1", error = "设备协议与预期不符") }
                handleDisconnect(g, "设备协议与预期不符", reconnect = false)
                return
            }
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            writeCharacteristic = characteristic
            // 稳定优先：保持高连接优先级，牺牲一部分电量换取后台抗断链能力。
            runCatching { g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH) }
            AppState.update { it.copy(bleStatus = "已连接", deviceName = runCatching { g.device.name }.getOrNull() ?: it.deviceName, error = null) }
            scope.launch {
                // 重连后恢复输出；无输出时才做启动即停的清理。
                if (currentVibrateLevel > 0 || currentSuctionLevel > 0) {
                    if (currentVibrateLevel > 0) runCatching { write(SvakomProtocol.vibrate(currentVibrateLevel)) }
                    if (currentSuctionLevel > 0) runCatching { write(SvakomProtocol.suction(currentSuctionLevel, currentSuctionMode)) }
                } else {
                    stopAll()
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun handleDisconnect(g: BluetoothGatt, reason: String, reconnect: Boolean = true) {
        if (!isCurrent(g)) return
        connectionGeneration++
        gatt = null
        writeCharacteristic = null
        runCatching { g.close() }
        AppState.update { it.copy(bleStatus = reason, intensity = 0, suctionIntensity = 0) }
        if (reconnect && shouldRun) scheduleScan(250)
    }

    suspend fun setVibration(intensity: Double): Result<Int> {
        val level = SvakomProtocol.levelFor(intensity)
        return write(SvakomProtocol.vibrate(level), deduplicate = level > 0).map {
            currentVibrateLevel = level
            updateKeepAlive()
            AppState.update { state -> state.copy(intensity = level, lastMessage = "震动 $level 档") }
            level
        }
    }

    suspend fun setSuction(intensity: Double, mode: Int = SvakomProtocol.SUCTION_DEFAULT_MODE): Result<Int> {
        val level = SvakomProtocol.suctionLevelFor(intensity)
        val frame = runCatching { SvakomProtocol.suction(level, mode) }.getOrElse { return Result.failure(it) }
        return write(frame, deduplicate = level > 0).map {
            currentSuctionLevel = level
            if (level > 0) currentSuctionMode = mode
            updateKeepAlive()
            AppState.update { state -> state.copy(suctionIntensity = level, suctionMode = if (level > 0) mode else state.suctionMode, lastMessage = if (level > 0) "吮吸 模式 $mode · $level/5 档" else "吮吸已停止") }
            level
        }
    }

    suspend fun writeRaw(hexText: String): Result<String> {
        val tokens = hexText.trim().split(Regex("[\\s,]+")).filter { it.isNotBlank() }
        if (tokens.isEmpty()) return Result.failure(IllegalArgumentException("空的 HEX 输入"))
        val bytes = try { tokens.map { it.toInt(16).toByte() }.toByteArray() }
        catch (e: NumberFormatException) { return Result.failure(IllegalArgumentException("无效 HEX：$hexText")) }
        return write(bytes).map { AppState.update { state -> state.copy(lastMessage = "已发送 $hexText") }; hexText }
    }

    suspend fun stopAll(): Result<Unit> {
        var failure: Throwable? = null
        // Clear intent and cancel watchdog first, including when the device is offline.
        currentVibrateLevel = 0
        currentSuctionLevel = 0
        keepAliveJob?.cancelAndJoin()
        keepAliveJob = null
        for (frame in SvakomProtocol.stopFrames) {
            write(frame).onFailure { failure = it }
        }
        AppState.update { it.copy(intensity = 0, suctionIntensity = 0, lastMessage = "全部停止") }
        return failure?.let { Result.failure(it) } ?: Result.success(Unit)
    }

    /** 固件看门狗保活：任一通道在输出时，周期重发当前帧，防止设备自动停机。 */
    private fun updateKeepAlive() {
        val active = currentVibrateLevel > 0 || currentSuctionLevel > 0
        if (!active) {
            keepAliveJob?.cancel()
            keepAliveJob = null
            return
        }
        if (keepAliveJob?.isActive == true) return
        keepAliveJob = scope.launch {
            while (isActive) {
                delay(KEEP_ALIVE_INTERVAL_MS)
                if (currentVibrateLevel > 0) runCatching { write(SvakomProtocol.vibrate(currentVibrateLevel)) }
                if (currentSuctionLevel > 0) runCatching { write(SvakomProtocol.suction(currentSuctionLevel, currentSuctionMode)) }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun write(frame: ByteArray, deduplicate: Boolean = false): Result<Unit> = writeMutex.withLock {
        withContext(Dispatchers.IO) {
            if (deduplicate && isConnected && frameCache.contains(connectionGeneration, frame)) {
                return@withContext Result.success(Unit)
            }
            val now = SystemClock.elapsedRealtime()
            val waitMs = (WRITE_INTERVAL_MS - (now - lastWriteAt)).coerceAtLeast(0L)
            if (waitMs > 0) delay(waitMs)
            val currentGatt = gatt
            val currentCharacteristic = writeCharacteristic
            val generation = connectionGeneration
            val result = runCatching {
                if (!hasBlePermissions()) error("缺少蓝牙权限")
                val g = currentGatt ?: error("设备未连接")
                val c = currentCharacteristic ?: error("写特征未就绪")
                val accepted = if (Build.VERSION.SDK_INT >= 33) {
                    g.writeCharacteristic(c, frame, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) == BluetoothStatusCodes.SUCCESS
                } else {
                    @Suppress("DEPRECATION") c.value = frame
                    @Suppress("DEPRECATION") g.writeCharacteristic(c)
                }
                if (!accepted) error("BLE 写入被拒绝")
                lastWriteAt = SystemClock.elapsedRealtime()
                if (frame.size == 7 && frame[0] == 0x55.toByte() &&
                    frame[1].toInt() in setOf(3, 9) && isCurrent(g) && generation == connectionGeneration) {
                    frameCache.record(generation, frame)
                } else {
                    frameCache.clear() // raw writes must not leave an optimistic cache
                }
            }
            if (result.isFailure && currentGatt != null && isCurrent(currentGatt)) {
                // 写失败时立即清空当前连接；否则 scan() 会误判为“仍在线”而不重连。
                handleDisconnect(currentGatt, "BLE 写入失败，正在重连")
            }
            result
        }
    }

    @SuppressLint("MissingPermission")
    fun close() {
        shouldRun = false
        scanTimeoutJob?.cancel()
        reconnectJob?.cancel()
        keepAliveJob?.cancel()
        keepAliveJob = null
        stopScan()
        context.unregisterReceiver(bluetoothStateReceiver)
        val oldGatt = gatt
        gatt = null
        writeCharacteristic = null
        // The service has already attempted stop while the transport was usable.
        // Closing must not enqueue work in a scope about to be cancelled.
        runCatching { oldGatt?.disconnect() }
        runCatching { oldGatt?.close() }
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        scanTimeoutJob?.cancel()
        scanTimeoutJob = null
        scannerCallback?.let { runCatching { adapter.bluetoothLeScanner?.stopScan(it) } }
        scannerCallback = null
    }

    private fun scheduleScan(delayMs: Long) {
        if (!shouldRun || reconnectJob?.isActive == true) return
        reconnectJob = scope.launch {
            delay(delayMs)
            reconnectJob = null
            if (shouldRun && !isConnected) scan()
        }
    }

    private fun hasBlePermissions(): Boolean = if (Build.VERSION.SDK_INT >= 31) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    } else ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private companion object {
        const val WRITE_INTERVAL_MS = 100L
        const val KEEP_ALIVE_INTERVAL_MS = 10_000L
    }
}
