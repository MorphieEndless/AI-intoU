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
    private var gatt: BluetoothGatt? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private val writeMutex = Mutex()
    @Volatile private var shouldRun = true
    @Volatile private var connectionGeneration = 0L

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
                if (scannerCallback !== callback) return
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
        // 防止极短时序下旧回调把新连接状态覆盖掉。
        if (generation != connectionGeneration) {
            runCatching { newGatt.close() }
        }
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
            // Android BLE 回调可能晚到；旧 GATT 绝不能覆盖当前连接状态。
            if (!isCurrent(g)) {
                runCatching { g.close() }
                return
            }
            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                AppState.update { it.copy(bleStatus = "发现服务…") }
                if (!g.discoverServices()) {
                    handleDisconnect(g, "服务发现启动失败")
                }
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
            // BALANCED 比 HIGH 更适合持续后台连接，避免部分手机在切后台后频繁断链。
            runCatching { g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_BALANCED) }
            AppState.update { it.copy(bleStatus = "已连接", deviceName = runCatching { g.device.name }.getOrNull() ?: it.deviceName, error = null) }
            scope.launch { stopAll() }
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
        if (reconnect && shouldRun) scheduleScan(1_000)
    }

    suspend fun setVibration(intensity: Double): Result<Int> {
        val level = SvakomProtocol.levelFor(intensity)
        return write(SvakomProtocol.vibrate(level)).map {
            AppState.update { state -> state.copy(intensity = level, lastMessage = "震动 $level 档") }
            level
        }
    }

    suspend fun setSuction(intensity: Double, mode: Int = SvakomProtocol.SUCTION_DEFAULT_MODE): Result<Int> {
        val level = SvakomProtocol.suctionLevelFor(intensity)
        val frame = runCatching { SvakomProtocol.suction(level, mode) }.getOrElse { return Result.failure(it) }
        return write(frame).map {
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
        for (frame in SvakomProtocol.stopFrames) {
            write(frame).onFailure { failure = it }
            delay(35)
        }
        AppState.update { it.copy(intensity = 0, suctionIntensity = 0, lastMessage = "全部停止") }
        return failure?.let { Result.failure(it) } ?: Result.success(Unit)
    }

    @SuppressLint("MissingPermission")
    private suspend fun write(frame: ByteArray): Result<Unit> = writeMutex.withLock {
        withContext(Dispatchers.IO) {
            runCatching {
                if (!hasBlePermissions()) error("缺少蓝牙权限")
                val g = gatt ?: error("设备未连接")
                val c = writeCharacteristic ?: error("写特征未就绪")
                val accepted = if (Build.VERSION.SDK_INT >= 33) {
                    g.writeCharacteristic(c, frame, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) == BluetoothStatusCodes.SUCCESS
                } else {
                    @Suppress("DEPRECATION") c.value = frame
                    @Suppress("DEPRECATION") g.writeCharacteristic(c)
                }
                if (!accepted) error("BLE 写入被拒绝")
            }.onFailure {
                if (shouldRun && isConnected) scheduleScan(1_000)
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun close() {
        shouldRun = false
        scanTimeoutJob?.cancel()
        reconnectJob?.cancel()
        stopScan()
        context.unregisterReceiver(bluetoothStateReceiver)
        val oldGatt = gatt
        gatt = null
        writeCharacteristic = null
        scope.launch {
            runCatching { stopAll() }
            runCatching { oldGatt?.disconnect() }
            runCatching { oldGatt?.close() }
        }
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
}
