package cn.huacheng.safebaiyun.unlock

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.Build
import cn.huacheng.safebaiyun.util.ContextHolder
import cn.huacheng.safebaiyun.util.LockBiz
import cn.huacheng.safebaiyun.util.showToast
import java.util.UUID
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch


@SuppressLint("MissingPermission")
object UnlockRepo {

    private const val MAGIC_SERVICE = "14839ac4-7d7e-415c-9a42-167340cf2339"
    private const val UNLOCK_TIMEOUT_MS = 20_000L
    // 与 BluetoothStatusCodes.SUCCESS 相同；直接写常量避免在老系统上引用 API 33 的类
    private const val GATT_STATUS_SUCCESS = 0
    private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private var gatt: BluetoothGatt? = null

    private var readableCharacteristic: BluetoothGattCharacteristic? = null

    private var writeableCharacteristic: BluetoothGattCharacteristic? = null

    private lateinit var config: Pair<String, String>

    private var autoDisconnectJob: Job? = null

    /** 一次解锁流程是否已经结束（成功或失败），用于忽略关闭连接后迟到的回调 */
    private var unlockFinished = false

    /** 待写入的通知描述符，BLE 同一时刻只允许一个 GATT 操作，必须排队逐个写 */
    private val descriptorQueue = ArrayDeque<Pair<BluetoothGattDescriptor, ByteArray>>()

    private val logList = mutableListOf("Hello World")
    val logFlow: MutableStateFlow<List<String>> = MutableStateFlow(logList.toList())

    @OptIn(DelicateCoroutinesApi::class)
    fun unlock() {
        closeGatt()
        autoDisconnectJob?.cancel()
        autoDisconnectJob = null
        unlockFinished = false
        descriptorQueue.clear()
        readableCharacteristic = null
        writeableCharacteristic = null

        val bluetoothAdapter =
            (ContextHolder.get()
                .getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

        val saved = DataRepo.readData()
        // Mac 统一成大写：checkBluetoothAddress 和部分 ROM 对大小写敏感；十六进制 key 解析本身不区分大小写
        val mac = saved.first.trim().uppercase()
        val key = saved.second.trim()

        if (!BluetoothAdapter.checkBluetoothAddress(mac)) {
            showToast("Mac地址格式错误")
            return
        }
        if (!isHexKey(key)) {
            showToast("Key格式错误：应为偶数位十六进制，例如 0123456789abcdef")
            return
        }
        config = mac to key

        connect(bluetoothAdapter)

        autoDisconnectJob = GlobalScope.launch {
            delay(UNLOCK_TIMEOUT_MS)
            if (!unlockFinished) {
                fail("20s超时，自动断开链接")
            }
        }
    }

    private fun connect(bluetoothAdapter: BluetoothAdapter) {
        val remoteDevice = bluetoothAdapter.getRemoteDevice(config.first)
        gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            remoteDevice.connectGatt(
                ContextHolder.get(),
                false,
                callback,
                BluetoothDevice.TRANSPORT_LE
            )
        } else {
            remoteDevice.connectGatt(
                ContextHolder.get(),
                false,
                callback
            )
        }

        log("尝试连接蓝牙 ${gatt != null}")
    }

    private val callback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            log("连接状态改变 status=$status,newState=$newState")
            if (gatt !== UnlockRepo.gatt || unlockFinished) {
                return
            }
            if (status != BluetoothGatt.GATT_SUCCESS) {
                fail("连接失败 status=$status")
                return
            }
            when (newState) {
                BluetoothGatt.STATE_CONNECTED -> {
                    log("开始搜索服务")
                    gatt?.discoverServices()
                }

                BluetoothGatt.STATE_DISCONNECTED -> fail("连接已断开")
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            log("搜索服务结果 status=$status")
            if (gatt !== UnlockRepo.gatt || unlockFinished) {
                return
            }
            if (status != BluetoothGatt.GATT_SUCCESS) {
                fail("搜索服务失败 status=$status")
                return
            }
            val services = gatt?.services ?: emptyList()
            log("搜索到以下服务：${services.joinToString(",") { it.uuid.toString() }}")
            handleService(services.find { it.uuid.toString().equals(MAGIC_SERVICE, ignoreCase = true) })
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            //android13以上走这里
            log("特征码读取回调 status=$status,size=${value.size}")
            if (gatt !== UnlockRepo.gatt) {
                return
            }
            handleCharacteristicRead(value, status)
        }

        @Suppress("DEPRECATION")
        @Deprecated("Deprecated in Java")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?,
            status: Int
        ) {
            //android12及以下走这里
            val value = characteristic?.value ?: ByteArray(0)
            log("特征码读取回调 status=$status,size=${value.size}")
            if (gatt !== UnlockRepo.gatt) {
                return
            }
            handleCharacteristicRead(value, status)
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt?,
            descriptor: BluetoothGattDescriptor?,
            status: Int
        ) {
            // 只有上一次 writeDescriptor 回调之后才能发起下一个 GATT 操作
            log("描述符写入回调 status=$status")
            if (gatt !== UnlockRepo.gatt || unlockFinished) {
                return
            }
            if (status != BluetoothGatt.GATT_SUCCESS) {
                log("订阅通知失败 status=$status，继续读取特征")
            }
            writeNextDescriptor()
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?,
            status: Int
        ) {
            log("特征码写入回调 status=$status")
            if (gatt !== UnlockRepo.gatt || unlockFinished) {
                return
            }
            if (status == BluetoothGatt.GATT_SUCCESS) {
                finish(true, null)
            } else {
                fail("密钥写入失败 status=$status")
            }
        }
    }

    /**
     * 找到对应的characteristic
     */
    private fun handleService(service: BluetoothGattService?) {

        if (service == null) {
            fail("未找到目标服务 $MAGIC_SERVICE")
            return
        }

        log("开始处理服务，共${service.characteristics.size}个特征")
        val notificationCharacteristics = mutableListOf<BluetoothGattCharacteristic>()

        service.characteristics.forEach {
            val properties = it.properties
            log("特征${it.uuid},prop:$properties")
            if (properties and BluetoothGattCharacteristic.PROPERTY_READ != 0) {
                readableCharacteristic = it
            }
            if (properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) {
                writeableCharacteristic = it
            }
            if (properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY
                        or BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) {
                notificationCharacteristics.add(it)
            }
        }

        if (readableCharacteristic == null) {
            fail("没有找到可读特征")
            return
        }
        if (writeableCharacteristic == null) {
            fail("没有找到可写特征")
            return
        }

        val currentGatt = gatt ?: return
        descriptorQueue.clear()
        notificationCharacteristics.forEach { characteristic ->
            val enabled = currentGatt.setCharacteristicNotification(characteristic, true)
            log("开启通知${characteristic.uuid} -> $enabled")
            if (!enabled) {
                return@forEach
            }
            // 通知用 ENABLE_NOTIFICATION_VALUE，指示用 ENABLE_INDICATION_VALUE
            val enableValue =
                if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) {
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                } else {
                    BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                }
            characteristic.descriptors
                .filter { it.uuid == CCCD_UUID }
                .forEach { descriptor ->
                    descriptorQueue.add(descriptor to enableValue)
                }
        }

        // 没有需要订阅的通知时，这里会直接去读特征
        writeNextDescriptor()
    }

    /**
     * 逐个写通知描述符，全部写完后再去读特征。
     * 原来在 writeDescriptor 之后立刻 readCharacteristic，GATT 还处于 busy 状态，
     * Android 会直接返回失败（logcat: readCharacteristic() - Device busy），读取回调永远不会来。
     */
    @Suppress("DEPRECATION")
    private fun writeNextDescriptor() {
        val currentGatt = gatt ?: return
        if (unlockFinished) {
            return
        }
        val next = descriptorQueue.removeFirstOrNull()
        if (next == null) {
            startRead()
            return
        }

        val (descriptor, value) = next
        val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            isGattSuccess(currentGatt.writeDescriptor(descriptor, value))
        } else {
            descriptor.value = value
            isGattSuccess(currentGatt.writeDescriptor(descriptor))
        }

        log("写入描述符${descriptor.uuid} -> $started")
        if (!started) {
            // 返回失败表示操作没有真正提交，不会有回调，直接处理下一个
            writeNextDescriptor()
        }
    }

    private fun startRead() {
        val currentGatt = gatt ?: return
        val characteristic = readableCharacteristic
        if (characteristic == null) {
            fail("没有找到可读特征")
            return
        }

        val result: Any = currentGatt.readCharacteristic(characteristic)
        log("特征读取结果 $result")
        if (!isGattSuccess(result)) {
            fail("读取特征失败，readCharacteristic 返回 $result")
        }
    }

    private fun handleCharacteristicRead(value: ByteArray, status: Int) {
        if (unlockFinished) {
            return
        }
        if (status != BluetoothGatt.GATT_SUCCESS) {
            fail("读取特征失败 status=$status")
            return
        }
        if (value.isEmpty()) {
            fail("读取到空数据")
            return
        }
        handleCharacteristicWrite(value)
    }

    @Suppress("DEPRECATION")
    private fun handleCharacteristicWrite(value: ByteArray) {
        val currentGatt = gatt ?: return
        val characteristic = writeableCharacteristic
        if (characteristic == null) {
            fail("没有找到可写特征")
            return
        }

        log("开始写入密钥")
        val key = LockBiz.encryptData(value, LockBiz.hexToByteArray(config.first), config.second)
        log(key.joinToString())

        characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            isGattSuccess(
                currentGatt.writeCharacteristic(
                    characteristic,
                    key,
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                )
            )
        } else {
            characteristic.value = key
            isGattSuccess(currentGatt.writeCharacteristic(characteristic))
        }
        log("密钥写入结果 $started")
        if (!started) {
            fail("密钥写入失败")
        }
    }

    private fun finish(success: Boolean, error: String?) {
        if (unlockFinished) {
            return
        }
        unlockFinished = true
        autoDisconnectJob?.cancel()
        autoDisconnectJob = null
        descriptorQueue.clear()

        if (success) {
            showToast("开门成功")
        } else if (error != null) {
            showToast(error)
        }
        closeGatt()
    }

    private fun fail(error: String) {
        log(error)
        finish(false, error)
    }

    private fun closeGatt() {
        gatt?.disconnect()
        gatt?.close()
        gatt = null
    }

    /** Android 13 起这些 GATT 方法返回 int 状态码，12 及以下返回 boolean */
    private fun isGattSuccess(result: Any?): Boolean = when (result) {
        is Boolean -> result
        is Int -> result == GATT_STATUS_SUCCESS
        else -> false
    }

    private fun isHexKey(key: String): Boolean {
        if (key.isEmpty() || key.length % 2 != 0) {
            return false
        }
        return key.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
    }

    private fun log(msg: String) {
        println(msg)
    }
}
