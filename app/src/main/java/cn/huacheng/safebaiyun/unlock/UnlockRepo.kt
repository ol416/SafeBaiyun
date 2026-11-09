package cn.huacheng.safebaiyun.unlock

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import cn.huacheng.safebaiyun.util.ContextHolder
import cn.huacheng.safebaiyun.util.DeviceConfig
import cn.huacheng.safebaiyun.util.LockBiz
import cn.huacheng.safebaiyun.widget.WidgetRefresher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * 门禁 BLE 控制层。
 *
 * 相比旧实现，这里补齐了：
 * - 可观察的状态机（连接 / 发现服务 / 读挑战 / 写密钥 / 成功 / 失败原因）
 * - 全流程 12 秒看门狗（旧实现只在连接阶段有超时，连上之后可能永远挂着）
 * - 单实例互斥（连点两次不会产生两套 GATT 连接）
 * - 所有回调的防御式处理（服务/特征缺失时不再抛 UninitializedPropertyAccessException 崩溃）
 * - 带时间戳的运行日志（供界面排查）
 */
@SuppressLint("MissingPermission")
object UnlockRepo {

    private const val TAG = "UnlockRepo"
    private const val MAGIC_SERVICE = "14839ac4-7d7e-415c-9a42-167340cf2339"
    private const val TIMEOUT_MS = 12_000L
    private const val MAX_LOG_LINES = 60

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val logLock = Any()

    private var gatt: BluetoothGatt? = null
    private var readableCharacteristic: BluetoothGattCharacteristic? = null
    private var writeableCharacteristic: BluetoothGattCharacteristic? = null
    private var config: Pair<String, String> = "" to ""
    private var watchdog: Job? = null
    private var startedAt = 0L

    private val _state = MutableStateFlow<UnlockState>(UnlockState.Idle)
    val state: StateFlow<UnlockState> = _state.asStateFlow()

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    fun isRunning(): Boolean = _state.value is UnlockState.Running

    /**
     * 开始一次开门。会先做配置 / 权限 / 蓝牙的前置检查，
     * 前置条件不满足时同样落到 [UnlockState.Failed]，由界面给出下一步操作。
     */
    fun unlock() {
        if (_state.value is UnlockState.Running) {
            log("已有开门任务进行中，忽略本次请求")
            return
        }
        val context = ContextHolder.get()
        config = DataRepo.readData()
        startedAt = System.currentTimeMillis()
        readableCharacteristic = null
        writeableCharacteristic = null
        _logs.value = emptyList()

        if (!DataRepo.isConfigured()) {
            fail(UnlockFailure.NO_CONFIG, persist = false)
            return
        }
        if (!DeviceConfig.isValidMac(config.first)) {
            fail(UnlockFailure.INVALID_MAC, persist = false)
            return
        }
        if (!DeviceConfig.isValidKey(config.second)) {
            fail(UnlockFailure.INVALID_KEY, persist = false)
            return
        }
        if (!hasBluetoothPermission(context)) {
            fail(UnlockFailure.PERMISSION_DENIED, persist = false)
            return
        }
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        if (adapter == null) {
            fail(UnlockFailure.NO_BLUETOOTH, persist = false)
            return
        }
        if (!adapter.isEnabled) {
            fail(UnlockFailure.BLUETOOTH_OFF, persist = false)
            return
        }

        _state.value = UnlockState.Running(UnlockStep.CONNECTING)
        log("开始连接 ${DeviceConfig.maskMac(config.first)}")
        startWatchdog()

        try {
            val device = adapter.getRemoteDevice(config.first)
            gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            } else {
                device.connectGatt(context, false, callback)
            }
            if (gatt == null) {
                fail(UnlockFailure.CONNECT_FAILED, "connectGatt 返回空")
            }
        } catch (t: Throwable) {
            fail(UnlockFailure.CONNECT_FAILED, t.message)
        }
    }

    /**
     * 取消进行中的开门（快捷方式结果卡上的“取消”）。
     */
    fun cancel() {
        if (_state.value !is UnlockState.Running) return
        log("用户取消开门")
        watchdog?.cancel()
        watchdog = null
        closeGatt()
        _state.value = UnlockState.Idle
    }

    /**
     * 把上一次的成功/失败状态清掉，避免新入口一进来就显示旧结果。
     */
    fun reset() {
        if (_state.value !is UnlockState.Running) {
            _state.value = UnlockState.Idle
        }
    }

    // ---------------------------------------------------------------------
    // 状态与日志
    // ---------------------------------------------------------------------

    private fun startWatchdog() {
        watchdog?.cancel()
        watchdog = scope.launch {
            delay(TIMEOUT_MS)
            if (_state.value is UnlockState.Running) {
                fail(UnlockFailure.TIMEOUT, "12 秒内没有收到门禁响应，已自动断开")
            }
        }
    }

    private fun succeed() {
        watchdog?.cancel()
        watchdog = null
        closeGatt()
        val elapsed = (System.currentTimeMillis() - startedAt).coerceAtLeast(0L)
        val now = System.currentTimeMillis()
        // 先落盘再改状态，保证界面重组时“上次开门”已经是新结果
        DataRepo.saveLastResult(LastUnlock(success = true, at = now))
        _state.value = UnlockState.Success(elapsed, now)
        log("开门成功，用时 ${elapsed}ms")
        WidgetRefresher.refresh()
    }

    private fun fail(reason: UnlockFailure, detail: String? = null, persist: Boolean = true) {
        watchdog?.cancel()
        watchdog = null
        closeGatt()
        val now = System.currentTimeMillis()
        if (persist) {
            DataRepo.saveLastResult(LastUnlock(success = false, at = now, reason = reason, detail = detail))
        }
        _state.value = UnlockState.Failed(reason, detail, now)
        log("开门失败：${reason.name}${detail?.let { "（$it）" } ?: ""}")
        if (persist) {
            WidgetRefresher.refresh()
        }
    }

    private fun log(message: String) {
        Log.d(TAG, message)
        val timestamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        synchronized(logLock) {
            _logs.value = (_logs.value + "$timestamp  $message").takeLast(MAX_LOG_LINES)
        }
    }

    private fun closeGatt() {
        val current = gatt ?: return
        gatt = null
        readableCharacteristic = null
        writeableCharacteristic = null
        try {
            current.disconnect()
        } catch (_: Throwable) {
        }
        try {
            current.close()
        } catch (_: Throwable) {
        }
    }

    private fun hasBluetoothPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.BLUETOOTH_CONNECT
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun isActiveFlow(): Boolean = _state.value is UnlockState.Running

    // ---------------------------------------------------------------------
    // GATT 回调
    // ---------------------------------------------------------------------

    private val callback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            if (!isActiveFlow()) return
            log("连接状态变化 status=$status newState=$newState")
            when {
                status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothGatt.STATE_CONNECTED -> {
                    _state.value = UnlockState.Running(UnlockStep.DISCOVERING)
                    val started = gatt?.discoverServices() == true
                    log(if (started) "已连接，开始发现服务" else "discoverServices 启动失败")
                    if (!started) {
                        fail(UnlockFailure.CONNECT_FAILED, "无法启动服务发现")
                    }
                }

                newState == BluetoothGatt.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS -> {
                    fail(UnlockFailure.CONNECT_FAILED, "status=$status")
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            if (!isActiveFlow()) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                fail(UnlockFailure.SERVICE_NOT_FOUND, "status=$status")
                return
            }
            val service = gatt?.getService(UUID.fromString(MAGIC_SERVICE))
            if (service == null) {
                fail(UnlockFailure.SERVICE_NOT_FOUND, "没有找到门禁专用服务")
                return
            }
            log("发现门禁服务，共 ${service.characteristics.size} 个特征")
            readableCharacteristic = null
            writeableCharacteristic = null
            service.characteristics.forEach { characteristic ->
                val properties = characteristic.properties
                if (properties and BluetoothGattCharacteristic.PROPERTY_READ != 0) {
                    readableCharacteristic = characteristic
                }
                if (properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) {
                    writeableCharacteristic = characteristic
                }
                // 说明：旧实现会在这里写 CCCD 描述符来“开启通知”，但既没有 onCharacteristicChanged
                // 处理，也会在 Android 的单操作 GATT 队列里占住一次写操作，导致紧随其后的
                // readCharacteristic 被拒绝。开门流程只依赖“读挑战码 + 写加密帧”，因此不再开启通知。
            }

            val readable = readableCharacteristic
            if (readable == null || writeableCharacteristic == null) {
                fail(
                    UnlockFailure.CHARACTERISTIC_MISSING,
                    "缺少可读或可写特征"
                )
                return
            }
            _state.value = UnlockState.Running(UnlockStep.READING)
            if (gatt?.readCharacteristic(readable) != true) {
                fail(UnlockFailure.READ_FAILED, "readCharacteristic 启动失败")
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            if (!isActiveFlow()) return
            log("读取挑战码 status=$status size=${value.size}")
            handleChallenge(status, value)
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?,
            status: Int
        ) {
            if (!isActiveFlow()) return
            val value = characteristic?.value ?: return
            log("读取挑战码(旧 API) status=$status size=${value.size}")
            handleChallenge(status, value)
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?,
            status: Int
        ) {
            if (!isActiveFlow()) return
            if (status == BluetoothGatt.GATT_SUCCESS) {
                succeed()
            } else {
                fail(UnlockFailure.WRITE_FAILED, "写入回调 status=$status")
            }
        }
    }

    private fun handleChallenge(status: Int, challenge: ByteArray) {
        if (status != BluetoothGatt.GATT_SUCCESS) {
            fail(UnlockFailure.READ_FAILED, "读取回调 status=$status")
            return
        }
        if (challenge.isEmpty()) {
            fail(UnlockFailure.READ_FAILED, "门禁返回的挑战码为空")
            return
        }
        writeFrame(challenge)
    }

    private fun writeFrame(challenge: ByteArray) {
        val writeable = writeableCharacteristic ?: run {
            fail(UnlockFailure.CHARACTERISTIC_MISSING, "写特征已失效")
            return
        }
        val frame = try {
            LockBiz.encryptData(challenge, LockBiz.hexToByteArray(config.first), config.second)
        } catch (t: Throwable) {
            fail(UnlockFailure.WRITE_FAILED, "组包异常：${t.message}")
            return
        }
        if (frame.isEmpty()) {
            fail(UnlockFailure.WRITE_FAILED, "组包结果为空")
            return
        }
        _state.value = UnlockState.Running(UnlockStep.WRITING)
        try {
            val accepted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt?.writeCharacteristic(
                    writeable,
                    frame,
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                ) == BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                writeable.value = frame
                writeable.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                @Suppress("DEPRECATION")
                gatt?.writeCharacteristic(writeable) == true
            }
            if (!accepted) {
                fail(UnlockFailure.WRITE_FAILED, "写入请求被系统拒绝")
            }
        } catch (t: Throwable) {
            fail(UnlockFailure.WRITE_FAILED, t.message)
        }
    }
}
