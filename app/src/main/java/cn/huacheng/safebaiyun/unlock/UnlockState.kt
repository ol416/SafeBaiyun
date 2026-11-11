package cn.huacheng.safebaiyun.unlock

/**
 * 一次开门流程中的阶段，用于在界面上给出可感知的进度。
 */
enum class UnlockStep(val label: String) {
    CONNECTING("连接"),
    DISCOVERING("发现服务"),
    READING("读取挑战"),
    WRITING("写入密钥");

    val index: Int get() = ordinal

    companion object {
        val ORDERED: List<UnlockStep> = values().toList()
    }
}

/**
 * 开门失败的原因。界面据此给出可操作的建议（开蓝牙 / 授权 / 去配置 / 重试）。
 */
enum class UnlockFailure {
    NO_CONFIG,
    INVALID_MAC,
    INVALID_KEY,
    BLUETOOTH_OFF,
    NO_BLUETOOTH,
    PERMISSION_DENIED,
    CONNECT_FAILED,
    SERVICE_NOT_FOUND,
    CHARACTERISTIC_MISSING,
    READ_FAILED,
    WRITE_FAILED,
    TIMEOUT,
    UNKNOWN
}

/**
 * 开门状态机。UI 直接收集这个 Flow 即可展示“进行中 / 成功 / 失败”。
 */
sealed interface UnlockState {

    data object Idle : UnlockState

    data class Running(val step: UnlockStep) : UnlockState

    data class Success(val elapsedMs: Long, val at: Long) : UnlockState

    data class Failed(
        val reason: UnlockFailure,
        val detail: String? = null,
        val at: Long = System.currentTimeMillis()
    ) : UnlockState
}

/**
 * 最近一次开门结果（持久化，供主页与桌面小部件展示）。
 */
data class LastUnlock(
    val success: Boolean,
    val at: Long,
    val reason: UnlockFailure? = null,
    val detail: String? = null
)
