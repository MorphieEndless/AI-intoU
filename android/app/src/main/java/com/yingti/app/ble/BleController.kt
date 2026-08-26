package com.yingti.app.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
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
    private var gatt: BluetoothGatt? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private val writeMutex = Mutex()
    @Volatile private var shouldRun = true

    val isConnected: Boolean get() = gatt != null && writeCharacteristic != null

    fun start() {
        shouldRun = true
        scan()
    }

    @SuppressLint("MissingPermission")
    fun scan() {
        if (!hasBlePermissions()) {
            AppState.update { it.copy(bleStatus = "缺少蓝牙权限", error = "请授予附近设备权限") }
            return
        }
        if (!adapter.isEnabled) {
            AppState.update { it.copy(bleStatus = "蓝牙未开启") }
            return
        }
        stopScan()
        AppState.update { it.copy(bleStatus = "正在扫描…", error = null) }
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = runCatching { result.device.name }.getOrNull() ?: result.scanRecord?.deviceName ?: return
                if (name.equals("SX589B", true) || name.startsWith("SX", true) || name.startsWith("SL", true)) {
                    stopScan()
                    connect(result.device, name)
                }
            }
            override fun onScanFailed(errorCode: Int) {
                AppState.update { it.copy(bleStatus = "扫描失败 $errorCode") }
                scheduleReconnect()
            }
        }
        scannerCallback = callback
        adapter.bluetoothLeScanner?.startScan(callback)
        scope.launch {
            delay(10_000)
            if (scannerCallback === callback) {
                stopScan()
                AppState.update { it.copy(bleStatus = "未发现 SX589B") }
                scheduleReconnect()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun connect(device: BluetoothDevice, name: String) {
        AppState.update { it.copy(bleStatus = "正在连接…", deviceName = name) }
        gatt?.close()
        writeCharacteristic = null
        gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        } else device.connectGatt(context, false, callback)
    }

    private val callback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                AppState.update { it.copy(bleStatus = "发现服务…") }
                g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                writeCharacteristic = null
                if (gatt === g) gatt = null
                runCatching { g.close() }
                AppState.update { it.copy(bleStatus = "已断开", intensity = 0, suctionIntensity = 0) }
                scheduleReconnect()
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val service = g.getService(UUID.fromString(SvakomProtocol.SERVICE_UUID))
            val characteristic = service?.getCharacteristic(UUID.fromString(SvakomProtocol.WRITE_UUID))
            if (status != BluetoothGatt.GATT_SUCCESS || characteristic == null) {
                AppState.update { it.copy(bleStatus = "不兼容：缺少 FFE1", error = "设备协议与预期不符") }
                runCatching { g.disconnect() }
                return
            }
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            writeCharacteristic = characteristic
            // 缩短连接间隔：降低断连概率，延长电量可接受
            runCatching { g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH) }
            AppState.update { it.copy(bleStatus = "已连接", deviceName = g.device.name, error = null) }
            scope.launch { stopAll() }
        }
    }

    suspend fun setVibration(intensity: Double): Result<Int> {
        val level = SvakomProtocol.levelFor(intensity)
        return write(SvakomProtocol.vibrate(level)).map {
            AppState.update { state -> state.copy(intensity = level, lastMessage = "震动 $level 档") }
            level
        }
    }

    /** 吮吸通道：强度 1-5 档，模式 byte4 透传 1-8，默认 05 持续。 */
    suspend fun setSuction(
        intensity: Double,
        mode: Int = SvakomProtocol.SUCTION_DEFAULT_MODE,
    ): Result<Int> {
        val level = SvakomProtocol.suctionLevelFor(intensity)
        val frame = runCatching { SvakomProtocol.suction(level, mode) }
            .getOrElse { return Result.failure(it) }
        return write(frame).map {
            AppState.update { state ->
                state.copy(
                    suctionIntensity = level,
                    suctionMode = if (level > 0) mode else state.suctionMode,
                    lastMessage = if (level > 0) "吮吸 模式 $mode · $level/5 档" else "吮吸已停止",
                )
            }
            level
        }
    }

    /** 调试用：发送任意 hex 帧（空格或逗号分隔，如 "55 03 00 00 01 01 00"）。 */
    suspend fun writeRaw(hexText: String): Result<String> {
        val tokens = hexText.trim().split(Regex("[\\s,]+")).filter { it.isNotBlank() }
        if (tokens.isEmpty()) return Result.failure(IllegalArgumentException("空的 HEX 输入"))
        val bytes = try {
            tokens.map { it.toInt(16).toByte() }.toByteArray()
        } catch (e: NumberFormatException) {
            return Result.failure(IllegalArgumentException("无效 HEX：$hexText"))
        }
        return write(bytes).map {
            AppState.update { state -> state.copy(lastMessage = "已发送 $hexText") }
            hexText
        }
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
                    @Suppress("DEPRECATION")
                    c.value = frame
                    @Suppress("DEPRECATION")
                    g.writeCharacteristic(c)
                }
                if (!accepted) error("BLE 写入被拒绝")
            }.onFailure {
                // 写失败说明链路异常：安排一次快速重连
                if (shouldRun && gatt != null) scheduleReconnect()
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun close() {
        shouldRun = false
        stopScan()
        scope.launch { runCatching { stopAll() }; gatt?.disconnect(); gatt?.close(); gatt = null; writeCharacteristic = null }
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        scannerCallback?.let { runCatching { adapter.bluetoothLeScanner?.stopScan(it) } }
        scannerCallback = null
    }

    private fun scheduleReconnect() {
        if (!shouldRun) return
        scope.launch { delay(1_000); if (shouldRun && !isConnected) scan() }
    }

    private fun hasBlePermissions(): Boolean = if (Build.VERSION.SDK_INT >= 31) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    } else ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
}
