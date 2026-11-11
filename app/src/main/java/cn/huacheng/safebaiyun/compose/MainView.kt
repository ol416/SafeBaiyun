package cn.huacheng.safebaiyun.compose

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.NavHostController
import cn.huacheng.safebaiyun.unlock.DataRepo
import cn.huacheng.safebaiyun.unlock.LastUnlock
import cn.huacheng.safebaiyun.unlock.LogOutcome
import cn.huacheng.safebaiyun.unlock.LogRepo
import cn.huacheng.safebaiyun.unlock.LogSession
import cn.huacheng.safebaiyun.unlock.UnlockFailure
import cn.huacheng.safebaiyun.unlock.UnlockRepo
import cn.huacheng.safebaiyun.unlock.UnlockState
import cn.huacheng.safebaiyun.unlock.UnlockStep
import cn.huacheng.safebaiyun.util.DeviceConfig
import cn.huacheng.safebaiyun.util.formatClockTime
import cn.huacheng.safebaiyun.util.formatElapsed
import cn.huacheng.safebaiyun.util.showToast
import cn.huacheng.safebaiyun.widget.WidgetHelper

/**
 * 主页：状态条 + 解锁卡 + 设备卡 + 上次结果 + 快捷入口 + 运行日志。
 *
 * 页面本身不持有业务状态，全部来自 [UnlockRepo.state] 与 [DataRepo]，
 * 因此从快捷方式/小部件开门后回到主页，也能看到同一次操作的结果。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainView(navController: NavHostController) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var configVersion by remember { mutableIntStateOf(0) }
    var hasPermission by remember { mutableStateOf(hasBluetoothPermission(context)) }
    var bluetoothOn by remember { mutableStateOf(isBluetoothEnabled(context)) }
    var showEditDialog by remember { mutableStateOf(false) }

    val state by UnlockRepo.state.collectAsState()
    val sessions by LogRepo.sessions.collectAsState()

    val config = remember(configVersion) { DataRepo.readData() }
    val configured = config.first.isNotBlank() && config.second.isNotBlank()
    val lastResult = remember(state, configVersion) { DataRepo.lastResult() }

    // 从系统设置 / 权限弹窗返回后刷新状态（旧实现放在 SideEffect 里，返回后不会刷新）
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasPermission = hasBluetoothPermission(context)
                bluetoothOn = isBluetoothEnabled(context)
                configVersion++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasPermission = granted
        if (granted) {
            bluetoothOn = isBluetoothEnabled(context)
            showToast("蓝牙权限已授予")
        } else {
            showToast("未授予「附近的设备」权限，无法连接门禁")
        }
    }

    val requestPermission = {
        if (hasPermission) {
            WidgetHelper.requestPermission()
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                permissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
            }
        }
    }
    val openBluetooth = { openBluetoothSettings(context) }
    val openConfig = { showEditDialog = true }
    val startUnlock = {
        when {
            !hasPermission -> requestPermission()
            !configured -> openConfig()
            !bluetoothOn -> openBluetooth()
            else -> UnlockRepo.unlock()
        }
    }

    Scaffold(
        topBar = {
            MainTopBar(
                onEditClick = openConfig,
                onHelperClick = { navController.navigate("helper") }
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .widthIn(max = 600.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
                    .padding(top = 12.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                StatusStrip(
                    hasPermission = hasPermission,
                    bluetoothOn = bluetoothOn,
                    configured = configured,
                    onPermissionClick = requestPermission,
                    onBluetoothClick = openBluetooth,
                    onConfigClick = openConfig
                )
                UnlockPanel(
                    state = state,
                    configured = configured,
                    hasPermission = hasPermission,
                    bluetoothOn = bluetoothOn,
                    macLabel = DeviceConfig.maskMac(config.first),
                    onUnlock = startUnlock,
                    onOpenConfig = openConfig,
                    onRequestPermission = requestPermission,
                    onOpenBluetooth = openBluetooth
                )
                DeviceCard(
                    mac = config.first,
                    key = config.second,
                    onEdit = openConfig
                )
                if (lastResult != null) {
                    LastResultCard(lastResult)
                }
                QuickActions()
                LogPanel(sessions)
                Text(
                    text = "离线门禁 · 数据仅保存在本机",
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }
    }

    if (showEditDialog) {
        EditDialog(
            onDismiss = { showEditDialog = false },
            onSaved = {
                configVersion++
                showToast("下次开门将使用新配置")
            }
        )
    }
}

// ---------------------------------------------------------------------------
// 状态条
// ---------------------------------------------------------------------------

@Composable
private fun StatusStrip(
    hasPermission: Boolean,
    bluetoothOn: Boolean,
    configured: Boolean,
    onPermissionClick: () -> Unit,
    onBluetoothClick: () -> Unit,
    onConfigClick: () -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatusChip(
            label = "蓝牙",
            value = when {
                !hasPermission -> "待授权"
                bluetoothOn -> "已开启"
                else -> "未开启"
            },
            ok = hasPermission && bluetoothOn,
            onClick = onBluetoothClick,
            modifier = Modifier.weight(1f)
        )
        StatusChip(
            label = "权限",
            value = if (hasPermission) "已授权" else "未授权",
            ok = hasPermission,
            onClick = onPermissionClick,
            modifier = Modifier.weight(1f)
        )
        StatusChip(
            label = "设备",
            value = if (configured) "已配置" else "未配置",
            ok = configured,
            onClick = onConfigClick,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun StatusChip(
    label: String,
    value: String,
    ok: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val container = if (ok) {
        MaterialTheme.colorScheme.tertiaryContainer
    } else {
        MaterialTheme.colorScheme.errorContainer
    }
    val content = if (ok) {
        MaterialTheme.colorScheme.onTertiaryContainer
    } else {
        MaterialTheme.colorScheme.onErrorContainer
    }
    val dot = if (ok) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(container)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(dot)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = content
            )
        }
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = content,
            maxLines = 1
        )
    }
}

// ---------------------------------------------------------------------------
// 解锁卡
// ---------------------------------------------------------------------------

@Composable
private fun UnlockPanel(
    state: UnlockState,
    configured: Boolean,
    hasPermission: Boolean,
    bluetoothOn: Boolean,
    macLabel: String,
    onUnlock: () -> Unit,
    onOpenConfig: () -> Unit,
    onRequestPermission: () -> Unit,
    onOpenBluetooth: () -> Unit
) {
    val running = state is UnlockState.Running
    val runningStep = (state as? UnlockState.Running)?.step
    val success = state as? UnlockState.Success
    val failed = state as? UnlockState.Failed

    val accent: Color
    val buttonColor: Color
    val onButtonColor: Color
    when {
        success != null -> {
            accent = MaterialTheme.colorScheme.tertiary
            buttonColor = accent
            onButtonColor = MaterialTheme.colorScheme.onTertiary
        }

        failed != null -> {
            accent = MaterialTheme.colorScheme.error
            buttonColor = accent
            onButtonColor = MaterialTheme.colorScheme.onError
        }

        else -> {
            accent = MaterialTheme.colorScheme.primary
            buttonColor = accent
            onButtonColor = MaterialTheme.colorScheme.onPrimary
        }
    }

    val buttonIcon = when {
        success != null -> Icons.Default.CheckCircle
        failed != null -> Icons.Default.Refresh
        !hasPermission || !bluetoothOn -> Icons.Default.Warning
        else -> Icons.Default.Lock
    }
    val buttonLabel = when {
        running -> "开门中"
        success != null -> "已开门"
        failed != null -> "重试"
        !configured -> "去配置"
        !hasPermission -> "去授权"
        !bluetoothOn -> "开蓝牙"
        else -> "解锁"
    }

    val title = when {
        running -> when (runningStep) {
            UnlockStep.CONNECTING -> "正在连接门禁…"
            UnlockStep.DISCOVERING -> "正在发现门禁服务…"
            UnlockStep.READING -> "正在读取挑战码…"
            UnlockStep.WRITING -> "正在写入密钥…"
            null -> "正在开门…"
        }

        success != null -> "开门成功"
        failed != null -> unlockFailureTitle(failed.reason)
        !configured -> "还没有配置门禁"
        !hasPermission -> "需要蓝牙权限"
        !bluetoothOn -> "蓝牙未开启"
        else -> "准备就绪"
    }

    val subtitle = when {
        running -> "请让手机靠近门禁，保持页面在前台"
        success != null -> "用时 ${formatElapsed(success.elapsedMs)} · ${formatClockTime(success.at)}"
        failed != null -> unlockFailureHint(failed.reason)
        !configured -> "填写官方 App 中提取的 MAC 与 Key，即可离线开门"
        !hasPermission -> "Android 12 及以上需要「附近的设备」权限"
        !bluetoothOn -> "开启蓝牙后才能连接门禁"
        else -> "点击上方按钮打开门禁 · $macLabel"
    }

    val action: Pair<String, () -> Unit>? = when {
        failed != null -> when (failed.reason) {
            UnlockFailure.NO_CONFIG,
            UnlockFailure.INVALID_MAC,
            UnlockFailure.INVALID_KEY -> "去配置" to onOpenConfig

            UnlockFailure.PERMISSION_DENIED -> "授予权限" to onRequestPermission
            UnlockFailure.BLUETOOTH_OFF,
            UnlockFailure.NO_BLUETOOTH -> "打开蓝牙" to onOpenBluetooth

            else -> "重试一次" to onUnlock
        }

        else -> null
    }

    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Surface(
                onClick = onUnlock,
                enabled = !running,
                modifier = Modifier.size(152.dp),
                shape = CircleShape,
                color = buttonColor,
                contentColor = onButtonColor,
                shadowElevation = 6.dp
            ) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (running) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(40.dp),
                            color = onButtonColor,
                            strokeWidth = 3.dp
                        )
                    } else {
                        Icon(
                            imageVector = buttonIcon,
                            contentDescription = null,
                            modifier = Modifier.size(44.dp),
                            tint = onButtonColor
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = buttonLabel,
                        color = onButtonColor,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            Spacer(Modifier.height(18.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(18.dp))
            StepIndicator(state = state, accent = accent)

            if (action != null) {
                Spacer(Modifier.height(16.dp))
                OutlinedButton(onClick = action.second) {
                    Text(action.first)
                }
            }
        }
    }
}

@Composable
private fun StepIndicator(state: UnlockState, accent: Color) {
    val currentIndex = when (state) {
        is UnlockState.Running -> state.step.index
        is UnlockState.Success -> UnlockStep.ORDERED.lastIndex
        else -> -1
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.Top
    ) {
        UnlockStep.ORDERED.forEach { step ->
            val reached = currentIndex >= 0 && step.index <= currentIndex
            val isCurrent = step.index == currentIndex
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier
                        .size(if (isCurrent) 10.dp else 7.dp)
                        .clip(CircleShape)
                        .background(
                            when {
                                reached -> accent
                                else -> MaterialTheme.colorScheme.outlineVariant
                            }
                        )
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = step.label,
                    fontSize = 10.sp,
                    color = if (isCurrent) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 设备 / 上次结果 / 快捷入口 / 日志
// ---------------------------------------------------------------------------

@Composable
private fun DeviceCard(mac: String, key: String, onEdit: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "门禁设备",
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onEdit) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("编辑")
                }
            }
            DeviceRow(
                label = "MAC",
                value = if (mac.isBlank()) "未配置" else DeviceConfig.maskMac(mac),
                monospace = true,
                trailing = if (mac.isBlank()) null else {
                    {
                        TextButton(onClick = {
                            clipboard.setText(AnnotatedString(mac))
                            showToast("MAC 已复制")
                        }) {
                            Text("复制")
                        }
                    }
                }
            )
            DeviceRow(
                label = "密钥",
                value = DeviceConfig.maskKey(key),
                monospace = false,
                trailing = null
            )
        }
    }
}

@Composable
private fun DeviceRow(
    label: String,
    value: String,
    monospace: Boolean,
    trailing: (@Composable () -> Unit)?
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            modifier = Modifier.width(48.dp),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default,
            maxLines = 1
        )
        if (trailing != null) {
            trailing()
        }
    }
}

@Composable
private fun LastResultCard(last: LastUnlock) {
    val color = if (last.success) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (last.success) Icons.Default.CheckCircle else Icons.Default.Warning,
                contentDescription = null,
                tint = color
            )
            Spacer(Modifier.width(10.dp))
            Column {
                Text(
                    text = "上次开门",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "${formatClockTime(last.at)} · " +
                        if (last.success) "成功" else "失败（${unlockFailureShort(last.reason)}）",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

@Composable
private fun QuickActions() {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedButton(
            onClick = { WidgetHelper.createShortcut() },
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp)
        ) {
            Text("创建快捷方式", maxLines = 1)
        }
        OutlinedButton(
            onClick = { WidgetHelper.requestPinWidget() },
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp)
        ) {
            Text("添加小部件", maxLines = 1)
        }
    }
}

@Composable
private fun LogPanel(sessions: List<LogSession>) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var selectedId by rememberSaveable { mutableStateOf<Long?>(null) }
    var showClearConfirm by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current

    // selectedId 为 null 表示跟随最新会话；一旦有新开门就自动跳回实时日志。
    // seenLatestId 一并保存：旋屏重建时 latestId 并没有变，不能把用户选中的历史会话重置掉。
    val latestId = sessions.firstOrNull()?.id
    var seenLatestId by rememberSaveable { mutableStateOf(latestId) }
    LaunchedEffect(latestId) {
        if (latestId != seenLatestId) {
            selectedId = null
            seenLatestId = latestId
        }
    }

    val selected = sessions.find { it.id == selectedId } ?: sessions.firstOrNull()
    val accent = if (selected != null) {
        outcomeColor(selected.outcome)
    } else {
        MaterialTheme.colorScheme.primary
    }

    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "运行日志",
                    style = MaterialTheme.typography.titleSmall
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = "${sessions.size} 次",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = if (expanded) "收起日志" else "展开日志",
                    modifier = Modifier.rotate(if (expanded) 180f else 0f)
                )
            }
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 8.dp)
                ) {
                    if (sessions.isEmpty()) {
                        Text(
                            text = "暂无日志。开门后会在这里显示每一步的时间与结果，"
                                + "并保留最近 20 次记录供回看。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        val current = selected ?: sessions.first()
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            sessions.forEach { session ->
                                SessionChip(
                                    label = sessionChipLabel(session),
                                    selected = session.id == current.id,
                                    accent = outcomeColor(session.outcome),
                                    onClick = { selectedId = session.id }
                                )
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        SessionSummary(current, accent)
                        Spacer(Modifier.height(8.dp))
                        if (current.lines.isEmpty()) {
                            Text(
                                text = "本次没有产生运行日志。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            // 单会话最多 60 行，整段铺开会把页面撑出好几屏；
                            // 这里限高并在卡片内部滚动，摘要与「复制本次」始终可见。
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 240.dp)
                                    .verticalScroll(rememberScrollState())
                            ) {
                                current.lines.forEach { line ->
                                    Text(
                                        text = line,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp,
                                        lineHeight = 16.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            TextButton(
                                onClick = {
                                    clipboard.setText(AnnotatedString(sessionText(current)))
                                    showToast("本次日志已复制")
                                }
                            ) {
                                Text("复制本次")
                            }
                            TextButton(onClick = { showClearConfirm = true }) {
                                Text("清空历史")
                            }
                        }
                    }
                }
            }
        }
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("清空历史日志") },
            text = {
                Text("将删除本机保存的全部开门日志，进行中的记录会保留。此操作不可撤销。")
            },
            confirmButton = {
                TextButton(onClick = {
                    LogRepo.clear()
                    selectedId = null
                    showClearConfirm = false
                    showToast("历史日志已清空")
                }) {
                    Text("清空")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun SessionChip(
    label: String,
    selected: Boolean,
    accent: Color,
    onClick: () -> Unit
) {
    val container = if (selected) {
        accent.copy(alpha = 0.18f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    Text(
        text = label,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(container)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        style = MaterialTheme.typography.labelMedium,
        color = if (selected) accent else MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1
    )
}

@Composable
private fun SessionSummary(session: LogSession, accent: Color) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(accent)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = outcomeLabel(session) + " · " + formatClockTime(session.startedAt) +
                    durationText(session)?.let { " · $it" }.orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                color = accent
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = "设备：${deviceLabel(session)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (!session.detail.isNullOrBlank()) {
            Text(
                text = "详情：${session.detail}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun outcomeColor(outcome: LogOutcome): Color = when (outcome) {
    LogOutcome.SUCCESS -> MaterialTheme.colorScheme.tertiary
    LogOutcome.FAILED -> MaterialTheme.colorScheme.error
    LogOutcome.RUNNING -> MaterialTheme.colorScheme.primary
    LogOutcome.CANCELLED, LogOutcome.INTERRUPTED -> MaterialTheme.colorScheme.outline
}

private fun outcomeShort(outcome: LogOutcome): String = when (outcome) {
    LogOutcome.SUCCESS -> "成功"
    LogOutcome.FAILED -> "失败"
    LogOutcome.CANCELLED -> "已取消"
    LogOutcome.INTERRUPTED -> "中断"
    LogOutcome.RUNNING -> "进行中"
}

private fun sessionChipLabel(session: LogSession): String =
    "${formatClockTime(session.startedAt)} ${outcomeShort(session.outcome)}"

private fun outcomeLabel(session: LogSession): String = when (session.outcome) {
    LogOutcome.SUCCESS -> "开门成功"
    LogOutcome.FAILED -> "开门失败（${unlockFailureShort(session.reason)}）"
    LogOutcome.CANCELLED -> "已取消开门"
    LogOutcome.INTERRUPTED -> "开门中断（应用曾退出）"
    LogOutcome.RUNNING -> "开门进行中"
}

/**
 * 会话耗时的可读文案：进行中返回 null（不显示），中断会话返回「耗时未知」，
 * 避免把没有结束时间的会话显示成「用时 0 毫秒」。摘要行与复制文本共用这一份逻辑。
 */
private fun durationText(session: LogSession): String? = when {
    session.outcome == LogOutcome.RUNNING -> null
    session.outcome == LogOutcome.INTERRUPTED || session.endedAt == null -> "耗时未知"
    else -> "用时 ${formatElapsed(session.elapsedMs)}"
}

/** 设备行只展示脱敏 MAC；未配置时明确说明。 */
private fun deviceLabel(session: LogSession): String =
    session.macLabel.takeIf { it.isNotBlank() } ?: "未配置"

private fun sessionText(session: LogSession): String = buildString {
    append("开门日志 · ${formatClockTime(session.startedAt)}\n")
    append("结果：${outcomeLabel(session)}\n")
    append("设备：${deviceLabel(session)}\n")
    durationText(session)?.let { append(it).append('\n') }
    if (!session.detail.isNullOrBlank()) append("详情：${session.detail}\n")
    append('\n')
    session.lines.forEach { append(it).append('\n') }
}

// ---------------------------------------------------------------------------
// 系统状态工具
// ---------------------------------------------------------------------------

internal fun hasBluetoothPermission(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
    return ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.BLUETOOTH_CONNECT
    ) == PackageManager.PERMISSION_GRANTED
}

internal fun isBluetoothEnabled(context: Context): Boolean {
    return try {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        manager?.adapter?.isEnabled == true
    } catch (_: SecurityException) {
        false
    }
}

internal fun openBluetoothSettings(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }.onFailure {
        showToast("无法打开蓝牙设置")
    }
}
