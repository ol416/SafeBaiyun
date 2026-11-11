package cn.huacheng.safebaiyun.util

import java.util.Locale

/**
 * 门禁配置（MAC / Key）的格式校验与脱敏展示。
 */
object DeviceConfig {

    private val MAC_REGEX = Regex("^([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$")
    private const val MIN_KEY_HEX_LENGTH = 16

    fun isValidMac(mac: String): Boolean = MAC_REGEX.matches(mac.trim())

    fun normalizeMac(mac: String): String = mac.trim().uppercase(Locale.US)

    /**
     * Key 为十六进制字符串，DES 取前 8 字节，因此至少 16 个十六进制字符且为偶数长度。
     */
    fun isValidKey(key: String): Boolean {
        val value = key.trim()
        if (value.length < MIN_KEY_HEX_LENGTH || value.length % 2 != 0) return false
        return value.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
    }

    /**
     * 只展示 MAC 的后两段，避免在公共场合完整暴露设备标识。
     */
    fun maskMac(mac: String): String {
        val value = mac.trim()
        if (value.isEmpty()) return "未配置"
        val parts = value.split(":")
        return if (parts.size == 6) {
            "${parts[0]}:${parts[1]}:••:••:${parts[4]}:${parts[5]}"
        } else {
            value
        }
    }

    /**
     * Key 完全不回显，只保留末 4 位用于确认“是不是这把钥匙”。
     */
    fun maskKey(key: String): String = when {
        key.isBlank() -> "未配置"
        key.length <= 4 -> "••••"
        else -> "••••••${key.takeLast(4)}"
    }
}
