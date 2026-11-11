package cn.huacheng.safebaiyun.unlock

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
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
 * 挑战码可能由「读特征」返回，也可能由门禁通过通知主动下发，因此两条通道都会等待：
 * - 服务发现后先串行订阅所有通知特征（CCCD），等描述符写入回调到齐再发起读请求；
 *   BLE 同一时刻只允许一个 GATT 操作，写完描述符立刻 readCharacteristic 会被系统以
 *   "Device busy" 拒绝，这正是旧版本读取失败的根源。
 * - 读请求被拒绝时短延迟重试；读回调异常或数据为空时不立即判失败，继续等待通知。
 * - 谁先拿到有效挑战码就用谁，避免两个通道重复组包写帧。
 */
@SuppressLint("MissingPermission")
object UnlockRepo {

    private const val TAG = "UnlockRepo"
    private const val MAGIC_SERVICE = "14839ac4-7d7e-415c-9a42-167340cf2339"
    private const val CCCD_UUID = "00002902-0000-1000-8000-00805f9b34fb"
    // 与 BluetoothStatusCodes.SUCCESS 相同；用常量避免在低版本系统引用 API 33 的类
    private const val GATT_STATUS_SUCCESS = 0
    private const val TIMEOUT_MS = 12_000L
    private const val CHALLENGE_WAIT_MS = 5_000L
    private const val READ_RETRY_DELAY_MS = 200L
    private const val MAX_READ_RETRIES = 3
    private const val MAX_WRITE_RETRIES = 3
    private const val DESCRIPTOR_TIMEOUT_MS = 1_500L
    private const val MAX_LOG_LINES = 60

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val logLock = Any()
    private val cccdUuid = UUID.fromString(CCCD_UUID)
    private val descriptorQueue = ArrayDeque<Pair<BluetoothGattDescriptor, ByteArray>>()

    private var gatt: BluetoothGatt? = null
    private var readableCharacteristic: BluetoothGattCharacteristic? = null
    private var writeableCharacteristic: BluetoothGattCharacteristic? = null
    private var config: Pair<String, String> = "" to ""
    private var watchdog: Job? = null
    private var challengeWaiter: Job? = null
    private var readRetryJob: Job? = null
    private var writeRetryJob: Job? = null
    private var descriptorWatchdog: Job? = null
    private var descriptorPending = false
    private var challengeHandled = false
    private var readRetries = 0
    private var readRequestAccepted = false
    private var lastReadStatus: Int? = null
    private var notificationCount = 0
    private var pendingChallenge: ByteArray? = null
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
        resetChallengeFlow()
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
        challengeWaiter?.cancel()
        challengeWaiter = null
        readRetryJob?.cancel()
        readRetryJob = null
        writeRetryJob?.cancel()
        writeRetryJob = null
        descriptorWatchdog?.cancel()
        descriptorWatchdog = null
        descriptorPending = false
        descriptorQueue.clear()
        pendingChallenge = null
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

            val readableCandidates = mutableListOf<BluetoothGattCharacteristic>()
            val writeableCandidates = mutableListOf<BluetoothGattCharacteristic>()
            val notifyCandidates = mutableListOf<BluetoothGattCharacteristic>()
            service.characteristics.forEach { characteristic ->
                val properties = characteristic.properties
                log("特征 ${characteristic.uuid} prop=$properties")
                if (properties and BluetoothGattCharacteristic.PROPERTY_READ != 0) {
                    readableCandidates += characteristic
                }
                if (properties and (BluetoothGattCharacteristic.PROPERTY_WRITE
                            or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) {
                    writeableCandidates += characteristic
                }
                if (properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY
                            or BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) {
                    notifyCandidates += characteristic
                }
            }

            // 纯通知特征不适合作主动读取；写特征优先选带 WRITE 属性、可收到写回调的
            readableCharacteristic = readableCandidates.firstOrNull {
                it.properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY
                        or BluetoothGattCharacteristic.PROPERTY_INDICATE) == 0
            } ?: readableCandidates.firstOrNull()
            writeableCharacteristic = writeableCandidates.firstOrNull {
                it.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0
            } ?: writeableCandidates.firstOrNull()

            if (writeableCharacteristic == null) {
                fail(UnlockFailure.CHARACTERISTIC_MISSING, "缺少可写特征")
                return
            }
            if (readableCharacteristic == null && notifyCandidates.isEmpty()) {
                fail(UnlockFailure.CHARACTERISTIC_MISSING, "缺少可读或通知特征")
                return
            }
            val readableUuid = readableCharacteristic?.uuid?.toString() ?: "无"
            log("选用读特征 $readableUuid，写特征 ${writeableCharacteristic?.uuid}，通知特征 ${notifyCandidates.size} 个")

            _state.value = UnlockState.Running(UnlockStep.READING)
            descriptorQueue.clear()
            notifyCandidates.forEach { characteristic ->
                val enabled = gatt?.setCharacteristicNotification(characteristic, true) == true
                log("开启通知 ${characteristic.uuid} -> $enabled")
                if (!enabled) return@forEach
                val enableValue = if (characteristic.properties and
                    BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
                ) {
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                } else {
                    BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                }
                characteristic.descriptors
                    .filter { it.uuid == cccdUuid }
                    .forEach { descriptorQueue.addLast(it to enableValue) }
            }
            log("待订阅描述符 ${descriptorQueue.size} 个")
            writeNextDescriptor()
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            if (!isActiveFlow()) return
            log("读取挑战码 status=$status size=${value.size}")
            consumeChallenge(status, value, "读取")
        }

        @Suppress("DEPRECATION")
        @Deprecated("Deprecated in Java")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?,
            status: Int
        ) {
            if (!isActiveFlow()) return
            val value = characteristic?.value ?: ByteArray(0)
            log("读取挑战码(旧 API) status=$status size=${value.size}")
            consumeChallenge(status, value, "读取")
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (!isActiveFlow()) return
            notificationCount++
            log("收到通知 size=${value.size}")
            consumeChallenge(BluetoothGatt.GATT_SUCCESS, value, "通知")
        }

        @Suppress("DEPRECATION")
        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?
        ) {
            if (!isActiveFlow()) return
            notificationCount++
            val value = characteristic?.value ?: ByteArray(0)
            log("收到通知(旧 API) size=${value.size}")
            consumeChallenge(BluetoothGatt.GATT_SUCCESS, value, "通知")
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt?,
            descriptor: BluetoothGattDescriptor?,
            status: Int
        ) {
            if (!isActiveFlow()) return
            log("描述符写入回调 status=$status")
            handleDescriptorWrite(status)
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

    private fun resetChallengeFlow() {
        challengeWaiter?.cancel()
        challengeWaiter = null
        readRetryJob?.cancel()
        readRetryJob = null
        writeRetryJob?.cancel()
        writeRetryJob = null
        descriptorWatchdog?.cancel()
        descriptorWatchdog = null
        descriptorPending = false
        descriptorQueue.clear()
        challengeHandled = false
        readRetries = 0
        readRequestAccepted = false
        lastReadStatus = null
        notificationCount = 0
        pendingChallenge = null
    }

    /** 部分 GATT 方法在 API 33 起返回 int 状态码，12 及以下返回 boolean */
    private fun isGattSuccess(result: Any?): Boolean = when (result) {
        is Boolean -> result
        is Int -> result == GATT_STATUS_SUCCESS
        else -> false
    }

    /**
     * 逐个写 CCCD，等 onDescriptorWrite 回调到齐后再发起读请求。
     * 描述符写完立刻 readCharacteristic 会被 GATT 队列以 Device busy 拒绝。
     */
    private fun writeNextDescriptor() {
        if (!isActiveFlow() || (challengeHandled && pendingChallenge == null)) return
        val current = gatt ?: return
        val next = descriptorQueue.removeFirstOrNull()
        if (next == null) {
            val pending = pendingChallenge
            if (pending != null) {
                pendingChallenge = null
                writeFrame(pending)
            } else {
                startReadWithRetry()
            }
            return
        }
        val (descriptor, value) = next
        // 先标记，避免写入回调在赋值之前就到达
        descriptorPending = true
        val started = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                isGattSuccess(current.writeDescriptor(descriptor, value))
            } else {
                @Suppress("DEPRECATION")
                descriptor.value = value
                @Suppress("DEPRECATION")
                isGattSuccess(current.writeDescriptor(descriptor))
            }
        } catch (t: Throwable) {
            log("写入描述符异常：${t.message}")
            false
        }
        log("写入描述符 ${descriptor.uuid} -> $started")
        if (!started) {
            // 操作没有提交就不会有回调，直接处理下一个
            descriptorPending = false
            writeNextDescriptor()
            return
        }
        descriptorWatchdog?.cancel()
        descriptorWatchdog = scope.launch {
            delay(DESCRIPTOR_TIMEOUT_MS)
            if (isActiveFlow() && descriptorPending) {
                log("描述符写入回调超时，继续后续流程")
                descriptorPending = false
                writeNextDescriptor()
            }
        }
    }

    private fun handleDescriptorWrite(status: Int) {
        if (!descriptorPending) return
        descriptorPending = false
        descriptorWatchdog?.cancel()
        descriptorWatchdog = null
        if (status != BluetoothGatt.GATT_SUCCESS) {
            log("订阅通知失败 status=$status，继续读取特征")
        }
        writeNextDescriptor()
    }

    /** 读请求可能因 GATT 队列仍忙被拒绝，短延迟重试；仍失败则等待通知通道 */
    private fun startReadWithRetry() {
        if (!isActiveFlow() || challengeHandled) return
        val current = gatt ?: return
        val readable = readableCharacteristic
        if (readable == null) {
            // 挑战码完全由通知下发时，不需要读取特征
            log("没有可读特征，仅等待通知通道下发挑战码")
            startChallengeWaiter()
            return
        }
        readRetries++
        val result: Any? = try {
            current.readCharacteristic(readable)
        } catch (t: Throwable) {
            log("readCharacteristic 异常：${t.message}")
            false
        }
        val accepted = isGattSuccess(result)
        log("读挑战码请求 #$readRetries 特征=${readable.uuid} -> $result")
        if (!accepted && readRetries < MAX_READ_RETRIES) {
            readRetryJob?.cancel()
            readRetryJob = scope.launch {
                delay(READ_RETRY_DELAY_MS)
                startReadWithRetry()
            }
            return
        }
        readRequestAccepted = accepted
        if (!accepted) {
            log("readCharacteristic 连续被拒绝，等待通知通道下发挑战码")
        }
        startChallengeWaiter()
    }

    /** 读特征与通知都取不到有效挑战码时，给出带上下文的失败原因 */
    private fun startChallengeWaiter() {
        challengeWaiter?.cancel()
        challengeWaiter = scope.launch {
            delay(CHALLENGE_WAIT_MS)
            if (!isActiveFlow() || challengeHandled) return@launch
            val detail = buildString {
                append("等待挑战码超时：读请求")
                append(if (readRequestAccepted) "已受理" else "被拒绝 $readRetries 次")
                lastReadStatus?.let { append("，读取回调 status=$it") }
                append("，通知 $notificationCount 条")
            }
            fail(UnlockFailure.READ_FAILED, detail)
        }
    }

    /** 读回调与通知回调统一入口，谁先拿到有效挑战码就用谁 */
    private fun consumeChallenge(status: Int, challenge: ByteArray, source: String) {
        if (!isActiveFlow() || challengeHandled) return
        if (source == "读取") lastReadStatus = status
        if (status != BluetoothGatt.GATT_SUCCESS) {
            log("$source 返回 status=$status，继续等待通知挑战码")
            return
        }
        if (challenge.isEmpty()) {
            log("$source 返回空数据，继续等待挑战码")
            return
        }
        log("取得挑战码（$source）size=${challenge.size}")
        challengeHandled = true
        challengeWaiter?.cancel()
        challengeWaiter = null
        readRetryJob?.cancel()
        readRetryJob = null
        if (descriptorPending || descriptorQueue.isNotEmpty()) {
            // 订阅还没写完，此时写特征会与描述符写操作争抢 GATT 队列
            pendingChallenge = challenge
            log("挑战码已收到，等待描述符订阅完成后再写入")
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
        val writeType = if (writeable.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        }
        log("写入加密帧 writeType=$writeType size=${frame.size}")
        submitFrame(writeable, frame, writeType, 1)
    }

    /**
     * 通知通道可能比读回调更早带回挑战码，此时前一个 GATT 操作未必结束，
     * 写入被拒绝时短延迟重试，避免直接误报「写入密钥失败」。
     */
    private fun submitFrame(
        writeable: BluetoothGattCharacteristic,
        frame: ByteArray,
        writeType: Int,
        attempt: Int
    ) {
        if (!isActiveFlow()) return
        val accepted = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt?.writeCharacteristic(writeable, frame, writeType) == BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                writeable.value = frame
                writeable.writeType = writeType
                @Suppress("DEPRECATION")
                gatt?.writeCharacteristic(writeable) == true
            }
        } catch (t: Throwable) {
            fail(UnlockFailure.WRITE_FAILED, "写入异常：${t.message}")
            return
        }
        if (accepted) {
            log("加密帧已提交，等待写入回调")
            return
        }
        if (attempt < MAX_WRITE_RETRIES) {
            log("写入请求第 $attempt 次被拒绝，稍后重试")
            writeRetryJob?.cancel()
            writeRetryJob = scope.launch {
                delay(READ_RETRY_DELAY_MS)
                submitFrame(writeable, frame, writeType, attempt + 1)
            }
            return
        }
        fail(UnlockFailure.WRITE_FAILED, "写入请求被系统拒绝（已重试 $attempt 次）")
    }
}
