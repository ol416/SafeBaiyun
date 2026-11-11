package cn.huacheng.safebaiyun

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import cn.huacheng.safebaiyun.compose.ShortcutResultView
import cn.huacheng.safebaiyun.compose.openBluetoothSettings
import cn.huacheng.safebaiyun.theme.SafeBaiyunTheme
import cn.huacheng.safebaiyun.unlock.DataRepo
import cn.huacheng.safebaiyun.unlock.UnlockRepo
import cn.huacheng.safebaiyun.unlock.UnlockState
import cn.huacheng.safebaiyun.util.DeviceConfig
import kotlinx.coroutines.delay

/**
 * 快捷方式 / 桌面小部件的统一入口。
 *
 * 旧实现先铺一屏全屏转圈，再立刻 finish，只留一个 Toast；
 * 现在改为半透明窗口上的结果卡：成功自动关闭，失败保留原因与重试入口。
 */
class ShortcutActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (intent.action == Intent.ACTION_CREATE_SHORTCUT) {
            createShortcut()
            return
        }

        setFinishOnTouchOutside(true)
        // 清掉上一次的旧结果，避免一进来就显示上一轮的成功/失败
        UnlockRepo.reset()

        setContent {
            SafeBaiyunTheme {
                val context = LocalContext.current
                val state by UnlockRepo.state.collectAsState()
                val config = remember { DataRepo.readData() }

                ShortcutResultView(
                    state = state,
                    macLabel = DeviceConfig.maskMac(config.first),
                    onCancel = {
                        UnlockRepo.cancel()
                        finish()
                    },
                    onRetry = { UnlockRepo.unlock() },
                    onClose = { finish() },
                    onOpenConfig = { openMain() },
                    onOpenPermission = { openMain() },
                    onOpenBluetooth = { openBluetoothSettings(context) }
                )

                LaunchedEffect(state) {
                    if (state is UnlockState.Success) {
                        delay(1400)
                        finish()
                    }
                }
            }
        }

        UnlockRepo.unlock()
    }

    private fun openMain() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        finish()
    }

    private fun createShortcut() {
        val intent = Intent()
        val icon = Intent.ShortcutIconResource.fromContext(this, R.mipmap.ic_launcher)
        intent.putExtra(Intent.EXTRA_SHORTCUT_NAME, getString(R.string.unlock_door))
        intent.putExtra(Intent.EXTRA_SHORTCUT_ICON_RESOURCE, icon)
        intent.putExtra(Intent.EXTRA_SHORTCUT_INTENT, Intent(this, ShortcutActivity::class.java))
        setResult(RESULT_OK, intent)
        finish()
    }
}
