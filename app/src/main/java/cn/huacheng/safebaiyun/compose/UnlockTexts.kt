package cn.huacheng.safebaiyun.compose

import cn.huacheng.safebaiyun.unlock.UnlockFailure

/**
 * 失败原因的文案集中在这里，主页与快捷方式结果卡共用。
 */
fun unlockFailureTitle(reason: UnlockFailure): String = when (reason) {
    UnlockFailure.NO_CONFIG -> "还没有配置门禁"
    UnlockFailure.INVALID_MAC -> "MAC 地址格式不正确"
    UnlockFailure.INVALID_KEY -> "加密密钥格式不正确"
    UnlockFailure.BLUETOOTH_OFF -> "蓝牙未开启"
    UnlockFailure.NO_BLUETOOTH -> "设备不支持蓝牙"
    UnlockFailure.PERMISSION_DENIED -> "缺少蓝牙权限"
    UnlockFailure.CONNECT_FAILED -> "连接门禁失败"
    UnlockFailure.SERVICE_NOT_FOUND -> "没有找到门禁服务"
    UnlockFailure.CHARACTERISTIC_MISSING -> "门禁特征不完整"
    UnlockFailure.READ_FAILED -> "读取挑战码失败"
    UnlockFailure.WRITE_FAILED -> "写入密钥失败"
    UnlockFailure.TIMEOUT -> "连接超时"
    UnlockFailure.UNKNOWN -> "开门失败"
}

fun unlockFailureHint(reason: UnlockFailure): String = when (reason) {
    UnlockFailure.NO_CONFIG -> "请先填写门禁 MAC 与加密密钥"
    UnlockFailure.INVALID_MAC -> "格式应为 AA:BB:CC:DD:EE:FF"
    UnlockFailure.INVALID_KEY -> "请填写官方 App 中的 PRODUCT_KEY（十六进制）"
    UnlockFailure.BLUETOOTH_OFF -> "请开启蓝牙后重试"
    UnlockFailure.NO_BLUETOOTH -> "当前设备没有可用的蓝牙适配器"
    UnlockFailure.PERMISSION_DENIED -> "Android 12 及以上需要「附近的设备」权限"
    UnlockFailure.CONNECT_FAILED -> "请靠近门禁，确认门禁已上电后重试"
    UnlockFailure.SERVICE_NOT_FOUND -> "设备不是受支持的门禁，或距离过远"
    UnlockFailure.CHARACTERISTIC_MISSING -> "门禁固件不匹配，无法完成开门"
    UnlockFailure.READ_FAILED -> "门禁没有返回挑战码，请重试"
    UnlockFailure.WRITE_FAILED -> "密钥可能不正确，请核对 PRODUCT_KEY"
    UnlockFailure.TIMEOUT -> "门禁无响应，可能已休眠或距离过远"
    UnlockFailure.UNKNOWN -> "请重试"
}

fun unlockFailureShort(reason: UnlockFailure?): String = when (reason) {
    UnlockFailure.NO_CONFIG, UnlockFailure.INVALID_MAC, UnlockFailure.INVALID_KEY -> "配置有误"
    UnlockFailure.BLUETOOTH_OFF, UnlockFailure.NO_BLUETOOTH -> "蓝牙不可用"
    UnlockFailure.PERMISSION_DENIED -> "缺少权限"
    UnlockFailure.CONNECT_FAILED -> "连接失败"
    UnlockFailure.SERVICE_NOT_FOUND -> "未找到服务"
    UnlockFailure.CHARACTERISTIC_MISSING -> "固件不匹配"
    UnlockFailure.READ_FAILED -> "读码失败"
    UnlockFailure.WRITE_FAILED -> "写入失败"
    UnlockFailure.TIMEOUT -> "超时"
    null, UnlockFailure.UNKNOWN -> "失败"
}
