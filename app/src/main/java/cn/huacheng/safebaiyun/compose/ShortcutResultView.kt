package cn.huacheng.safebaiyun.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.huacheng.safebaiyun.unlock.UnlockFailure
import cn.huacheng.safebaiyun.unlock.UnlockState
import cn.huacheng.safebaiyun.unlock.UnlockStep
import cn.huacheng.safebaiyun.util.formatElapsed

/**
 * 快捷方式 / 小部件点击后的结果卡。
 * 旧的实现是全屏转圈 + 立刻 finish + Toast，用户根本不知道有没有开成。
 */
@Composable
fun ShortcutResultView(
    state: UnlockState,
    macLabel: String,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onClose: () -> Unit,
    onOpenConfig: () -> Unit,
    onOpenPermission: () -> Unit,
    onOpenBluetooth: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClose
            ),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier
                .widthIn(max = 340.dp)
                .padding(24.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { },
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 10.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                when (val current = state) {
                    is UnlockState.Success -> SuccessContent(current)
                    is UnlockState.Failed -> FailedContent(
                        state = current,
                        onRetry = onRetry,
                        onOpenConfig = onOpenConfig,
                        onOpenPermission = onOpenPermission,
                        onOpenBluetooth = onOpenBluetooth
                    )

                    else -> RunningContent(
                        step = (state as? UnlockState.Running)?.step,
                        macLabel = macLabel,
                        onCancel = onCancel
                    )
                }
                Spacer(Modifier.height(12.dp))
                TextButton(onClick = onClose) {
                    Text("关闭")
                }
            }
        }
    }
}

@Composable
private fun RunningContent(step: UnlockStep?, macLabel: String, onCancel: () -> Unit) {
    CircularProgressIndicator(modifier = Modifier.size(44.dp), strokeWidth = 3.dp)
    Spacer(Modifier.height(18.dp))
    Text(text = stepTitle(step), style = MaterialTheme.typography.titleLarge)
    Spacer(Modifier.height(6.dp))
    Text(
        text = "门禁 $macLabel",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(4.dp))
    Text(
        text = "请让手机靠近门禁，保持屏幕开启",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center
    )
    Spacer(Modifier.height(12.dp))
    OutlinedButton(onClick = onCancel) {
        Text("取消")
    }
}

@Composable
private fun SuccessContent(state: UnlockState.Success) {
    Icon(
        imageVector = Icons.Default.CheckCircle,
        contentDescription = null,
        modifier = Modifier.size(56.dp),
        tint = MaterialTheme.colorScheme.tertiary
    )
    Spacer(Modifier.height(14.dp))
    Text(text = "开门成功", style = MaterialTheme.typography.titleLarge)
    Spacer(Modifier.height(6.dp))
    Text(
        text = "用时 ${formatElapsed(state.elapsedMs)}",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun FailedContent(
    state: UnlockState.Failed,
    onRetry: () -> Unit,
    onOpenConfig: () -> Unit,
    onOpenPermission: () -> Unit,
    onOpenBluetooth: () -> Unit
) {
    Icon(
        imageVector = Icons.Default.Warning,
        contentDescription = null,
        modifier = Modifier.size(56.dp),
        tint = MaterialTheme.colorScheme.error
    )
    Spacer(Modifier.height(14.dp))
    Text(
        text = unlockFailureTitle(state.reason),
        style = MaterialTheme.typography.titleLarge,
        textAlign = TextAlign.Center
    )
    Spacer(Modifier.height(6.dp))
    Text(
        text = unlockFailureHint(state.reason),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center
    )
    Spacer(Modifier.height(18.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        when (state.reason) {
            UnlockFailure.NO_CONFIG,
            UnlockFailure.INVALID_MAC,
            UnlockFailure.INVALID_KEY -> Button(onClick = onOpenConfig) { Text("去配置") }

            UnlockFailure.PERMISSION_DENIED -> Button(onClick = onOpenPermission) { Text("授予权限") }
            UnlockFailure.BLUETOOTH_OFF,
            UnlockFailure.NO_BLUETOOTH -> Button(onClick = onOpenBluetooth) { Text("打开蓝牙") }

            else -> Button(onClick = onRetry) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text("重试")
            }
        }
    }
}

private fun stepTitle(step: UnlockStep?): String = when (step) {
    UnlockStep.CONNECTING -> "正在连接门禁…"
    UnlockStep.DISCOVERING -> "正在发现门禁服务…"
    UnlockStep.READING -> "正在读取挑战码…"
    UnlockStep.WRITING -> "正在写入密钥…"
    null -> "正在开门…"
}
