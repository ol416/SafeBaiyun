package cn.huacheng.safebaiyun.unlock

import android.util.Log
import cn.huacheng.safebaiyun.util.ContextHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.Executors

/**
 * 一次开门流程的终态。
 *
 * [RUNNING] 只表示「当前正在进行」；进程在开门途中被杀时会留下 RUNNING 占位文件，
 * 下次加载统一转成 [INTERRUPTED]，避免历史里出现一条永远转不完的圈。
 */
enum class LogOutcome {
    RUNNING,
    SUCCESS,
    FAILED,
    CANCELLED,
    INTERRUPTED
}

/**
 * 一次开门的完整记录：摘要元数据 + 逐步运行日志。
 * 所有字段不可变，便于在单线程 IO 队列里安全地持有快照。
 */
data class LogSession(
    val id: Long,
    val startedAt: Long,
    val outcome: LogOutcome = LogOutcome.RUNNING,
    val endedAt: Long? = null,
    val reason: UnlockFailure? = null,
    val detail: String? = null,
    val deviceName: String? = null,
    val macLabel: String = "",
    val elapsedMs: Long = 0L,
    val lines: List<String> = emptyList()
)

/**
 * 开门日志的本地持久化层。
 *
 * 设计要点：
 * - 每会话一个纯文本文件（应用私有 filesDir/unlock_logs），无序列化依赖、无新增权限；
 *   文件以「@key=value 头部 + @body 分隔 + 一行一条日志」存储，可直接阅读与复制。
 * - 内存 [sessions] 是 UI 的单一数据源，按开始时间倒序；磁盘操作全部排在单线程 IO 队列，
 *   不会阻塞运行在 Main.immediate 上的 GATT 回调。
 * - begin 先写头部占位，运行中每行按顺序追加到同一文件，finish 再整体重写：
 *   进程在开门途中被回收时，已经写出的步骤仍留在文件里。会话数（20）与单会话行数（60）
 *   都有上限轮转，避免日志无限增长。日志目录需在备份规则中排除（见 backup_rules.xml）。
 */
object LogRepo {

    private const val TAG = "LogRepo"
    private const val DIR_NAME = "unlock_logs"
    private const val FORMAT_VERSION = 1
    private const val MAX_SESSIONS = 20
    private const val MAX_LINES = 60
    private const val MAX_FIELD_LENGTH = 300
    private const val MAX_LINE_LENGTH = 500
    private const val HEADER_PREFIX = "@"
    private const val BODY_MARKER = "@body"
    private const val LOG_SUFFIX = ".log"

    private val lock = Any()
    private val io = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "LogRepo-IO").apply { isDaemon = true }
    }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + io)

    // by lazy：ContextHolder 在 Application.onCreate 初始化，避免类加载期就取 Context
    private val logDir: File by lazy { File(ContextHolder.get().filesDir, DIR_NAME) }

    private val _sessions = MutableStateFlow<List<LogSession>>(emptyList())
    val sessions: StateFlow<List<LogSession>> = _sessions.asStateFlow()

    init {
        // 第一个排入单线程队列的任务，保证加载先于任何写入
        scope.launch { loadFromDisk() }
    }

    /**
     * 开启一次会话并写入占位文件。id 由调用方保证唯一（同时用作文件名）。
     */
    fun begin(id: Long, startedAt: Long, macLabel: String, deviceName: String?) {
        val session = LogSession(
            id = id,
            startedAt = startedAt,
            macLabel = clean(macLabel, MAX_FIELD_LENGTH),
            deviceName = cleanNullable(deviceName)
        )
        synchronized(lock) {
            _sessions.value = (listOf(session) + _sessions.value.filter { it.id != id })
                .sortedByDescending { it.startedAt }
                .take(MAX_SESSIONS)
        }
        scope.launch {
            if (stillTracked(id)) {
                writeSession(session)
                rotate()
            }
        }
    }

    /**
     * 追加一行日志。仅供 UnlockRepo.log() 调用；找不到活跃会话时静默忽略，
     * 例如「已有开门任务进行中」的重复请求早退。
     *
     * 内存更新后立刻把这一行按顺序写入会话文件，因此开门途中进程被回收时，
     * 已产生的步骤仍然可读，而不只是一个「中断」占位。
     */
    fun append(sessionId: Long, line: String) {
        if (sessionId <= 0L) return
        val safe = clean(line, MAX_LINE_LENGTH)
        var snapshot: LogSession? = null
        synchronized(lock) {
            val current = _sessions.value
            val index = current.indexOfFirst { it.id == sessionId }
            if (index < 0) return
            val target = current[index]
            if (target.outcome != LogOutcome.RUNNING) return
            val updated = target.copy(lines = (target.lines + safe).takeLast(MAX_LINES))
            _sessions.value = current.toMutableList().also { it[index] = updated }
            snapshot = updated
        }
        val session = snapshot ?: return
        scope.launch {
            if (stillTracked(sessionId)) appendLine(session, safe)
        }
    }

    /**
     * 连接前后补读系统蓝牙名称。名称只在此刻和 finish 落盘：
     * 进程在开门途中被回收时，中断记录里的设备行也能显示名称，
     * 不会与日志里的「已获取门禁蓝牙名称」相互矛盾。
     */
    fun updateDeviceName(sessionId: Long, name: String?) {
        val safe = cleanNullable(name) ?: return
        var snapshot: LogSession? = null
        synchronized(lock) {
            val current = _sessions.value
            val index = current.indexOfFirst { it.id == sessionId }
            if (index < 0) return
            val target = current[index]
            if (!target.deviceName.isNullOrBlank() || target.outcome != LogOutcome.RUNNING) return
            val updated = target.copy(deviceName = safe)
            _sessions.value = current.toMutableList().also { it[index] = updated }
            snapshot = updated
        }
        val session = snapshot ?: return
        scope.launch {
            if (stillTracked(sessionId)) writeSession(session)
        }
    }

    /** 结束会话并覆盖写完整文件。 */
    fun finish(
        sessionId: Long,
        outcome: LogOutcome,
        endedAt: Long,
        elapsedMs: Long,
        reason: UnlockFailure? = null,
        detail: String? = null
    ) {
        var snapshot: LogSession? = null
        synchronized(lock) {
            val current = _sessions.value
            val index = current.indexOfFirst { it.id == sessionId }
            if (index >= 0) {
                val updated = current[index].copy(
                    outcome = outcome,
                    endedAt = endedAt,
                    elapsedMs = elapsedMs,
                    reason = reason,
                    detail = cleanNullable(detail)
                )
                _sessions.value = current.toMutableList().also { it[index] = updated }
                snapshot = updated
            }
        }
        val session = snapshot ?: return
        scope.launch {
            if (stillTracked(sessionId)) {
                writeSession(session)
                rotate()
            }
        }
    }

    /**
     * 清空历史。仍在进行中的会话要整体跳过：内存里保留，磁盘文件也不删。
     * 只删内存的话，清空后进程立刻被杀，下次就恢复不出这条记录，
     * 与对话框承诺的「进行中的记录会保留」以及进程中断保留步骤的设计相矛盾。
     */
    fun clear() {
        val keep = synchronized(lock) {
            _sessions.value = _sessions.value.filter { it.outcome == LogOutcome.RUNNING }
            _sessions.value.mapTo(HashSet()) { fileName(it.id) }
        }
        scope.launch {
            runCatching {
                dir().listFiles()?.forEach { file ->
                    if (file.isFile && file.name !in keep) runCatching { file.delete() }
                }
            }.onFailure { Log.w(TAG, "清空日志失败：${it.message}") }
        }
    }

    // ---------------------------------------------------------------------
    // 磁盘读写
    // ---------------------------------------------------------------------

    private fun dir(): File = logDir

    private fun fileName(id: Long): String =
        "${id.toString().padStart(19, '0')}$LOG_SUFFIX"

    private fun stillTracked(sessionId: Long): Boolean = synchronized(lock) {
        _sessions.value.any { it.id == sessionId }
    }

    private fun loadFromDisk() {
        val fromDisk = runCatching {
            val directory = dir()
            val files = directory.listFiles()
                ?.filter { it.isFile && it.name.endsWith(LOG_SUFFIX) }
                ?: emptyList()
            files.sortedByDescending { it.name }
                .take(MAX_SESSIONS)
                .mapNotNull(::parse)
        }.onFailure {
            Log.w(TAG, "加载历史日志失败：${it.message}")
        }.getOrDefault(emptyList())

        synchronized(lock) {
            // 加载任务排在写入任务之前；若 begin 已抢先创建了会话，它不在磁盘结果里，
            // 这里必须保留，否则冷启动从快捷方式开门时会丢掉当前会话。
            val diskIds = fromDisk.map { it.id }.toHashSet()
            val pending = _sessions.value.filter { it.id !in diskIds }
            _sessions.value = (fromDisk + pending)
                .sortedByDescending { it.startedAt }
                .take(MAX_SESSIONS)
        }
        rotate()
    }

    /**
     * 进行中的会话逐行落盘，避免进程被回收后只剩元数据。
     *
     * 占位文件还不存在（begin 的写任务尚未执行，或被 clear 删掉）时整体重写，
     * 保证头部完整；内存中已经到行数上限时也整体重写，把文件裁剪回 [MAX_LINES] 行。
     */
    private fun appendLine(session: LogSession, line: String) {
        runCatching {
            val directory = dir()
            if (!directory.exists()) directory.mkdirs()
            val target = File(directory, fileName(session.id))
            if (!target.exists() || session.lines.size >= MAX_LINES) {
                writeSession(session)
            } else {
                target.appendText(line + "\n")
            }
        }.onFailure { Log.w(TAG, "追加日志失败：${it.message}") }
    }

    private fun writeSession(session: LogSession) {
        runCatching {
            val directory = dir()
            if (!directory.exists()) directory.mkdirs()
            val name = fileName(session.id)
            val target = File(directory, name)
            val tmp = File(directory, "$name.tmp")
            tmp.writeText(serialize(session))
            // Android（Linux）的 rename 会原子替换已有文件；先 delete 会留下
            // 「旧文件已删、新文件未就位」的窗口，此时进程被杀就只剩一条占位。
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
        }.onFailure { Log.w(TAG, "写入日志失败：${it.message}") }
    }

    /** 文件名字典序即时间序，超出上限的直接删除最旧文件。 */
    private fun rotate() {
        runCatching {
            val files = dir().listFiles()
                ?.filter { it.isFile && it.name.endsWith(LOG_SUFFIX) }
                ?: emptyList()
            files.sortedByDescending { it.name }
                .drop(MAX_SESSIONS)
                .forEach { file -> runCatching { file.delete() } }
        }.onFailure { Log.w(TAG, "轮转日志失败：${it.message}") }
    }

    private fun serialize(session: LogSession): String = buildString {
        append("@format=$FORMAT_VERSION\n")
        append("@id=${session.id}\n")
        append("@startedAt=${session.startedAt}\n")
        append("@outcome=${session.outcome.name}\n")
        append("@endedAt=${session.endedAt ?: ""}\n")
        append("@reason=${session.reason?.name ?: ""}\n")
        append("@detail=${cleanNullable(session.detail).orEmpty()}\n")
        append("@deviceName=${cleanNullable(session.deviceName).orEmpty()}\n")
        append("@macLabel=${clean(session.macLabel, MAX_FIELD_LENGTH)}\n")
        append("@elapsedMs=${session.elapsedMs}\n")
        append(BODY_MARKER).append('\n')
        session.lines.forEach { append(it).append('\n') }
    }

    private fun parse(file: File): LogSession? = try {
        var id = 0L
        var startedAt = 0L
        var format = 0
        var outcome = LogOutcome.RUNNING
        var endedAt: Long? = null
        var reason: UnlockFailure? = null
        var detail: String? = null
        var deviceName: String? = null
        var macLabel = ""
        var elapsedMs = 0L
        var inBody = false
        val lines = mutableListOf<String>()

        file.readLines().forEach { raw ->
            if (inBody) {
                lines += raw
                return@forEach
            }
            if (raw == BODY_MARKER) {
                inBody = true
                return@forEach
            }
            if (!raw.startsWith(HEADER_PREFIX)) return@forEach
            val key = raw.substringBefore('=').removePrefix(HEADER_PREFIX)
            val value = raw.substringAfter('=', "")
            when (key) {
                "format" -> format = value.toIntOrNull() ?: 0
                "id" -> id = value.toLongOrNull() ?: 0L
                "startedAt" -> startedAt = value.toLongOrNull() ?: 0L
                "outcome" -> outcome = runCatching { LogOutcome.valueOf(value) }
                    .getOrDefault(LogOutcome.RUNNING)
                "endedAt" -> endedAt = value.toLongOrNull()
                "reason" -> reason = runCatching { UnlockFailure.valueOf(value) }.getOrNull()
                "detail" -> detail = value.takeIf { it.isNotEmpty() }
                "deviceName" -> deviceName = value.takeIf { it.isNotEmpty() }
                "macLabel" -> macLabel = value
                "elapsedMs" -> elapsedMs = value.toLongOrNull() ?: 0L
            }
        }

        when {
            id <= 0L || startedAt <= 0L -> null
            // 以后改格式时，旧版本不认识新文件，宁可跳过也不要把字段误读成另一段记录
            format > FORMAT_VERSION -> {
                Log.w(TAG, "跳过格式版本过新的日志文件 ${file.name}（format=$format）")
                null
            }
            else -> LogSession(
                id = id,
                startedAt = startedAt,
                // 进程被杀留下的占位文件：标记为中断，而不是一直显示「进行中」
                outcome = if (outcome == LogOutcome.RUNNING) {
                    LogOutcome.INTERRUPTED
                } else {
                    outcome
                },
                endedAt = endedAt,
                reason = reason,
                detail = detail,
                deviceName = deviceName,
                macLabel = macLabel,
                elapsedMs = elapsedMs,
                lines = lines.takeLast(MAX_LINES)
            )
        }
    } catch (t: Throwable) {
        Log.w(TAG, "跳过损坏的日志文件 ${file.name}：${t.message}")
        null
    }

    // ---------------------------------------------------------------------
    // 文本清洗：保证「一行一条」不被换行符破坏，并限制异常长文本
    // ---------------------------------------------------------------------

    private fun clean(value: String, limit: Int): String =
        value.replace('\n', ' ').replace('\r', ' ').trim().take(limit)

    private fun cleanNullable(value: String?): String? =
        value?.let { clean(it, MAX_FIELD_LENGTH) }?.takeIf { it.isNotEmpty() }
}
