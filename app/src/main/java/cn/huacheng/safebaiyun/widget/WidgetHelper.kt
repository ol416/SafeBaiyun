package cn.huacheng.safebaiyun.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import cn.huacheng.safebaiyun.R
import cn.huacheng.safebaiyun.ShortcutActivity
import cn.huacheng.safebaiyun.util.ContextHolder
import cn.huacheng.safebaiyun.util.showToast

/**
 * 快捷方式 / 桌面小部件 / 权限设置的入口封装。
 */
object WidgetHelper {

    fun requestPermission() {
        val intent = Intent()
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        intent.setAction(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        val uri = Uri.fromParts("package", ContextHolder.get().packageName, null)
        intent.setData(uri)
        ContextHolder.get().startActivity(intent)
    }

    fun createShortcut() {
        val context = ContextHolder.get()
        val intent = Intent(context, ShortcutActivity::class.java)
        intent.setAction(Intent.ACTION_VIEW)
        val shortcut = ShortcutInfoCompat.Builder(context, "Unlock").setIcon(
            IconCompat.createWithResource(context, R.mipmap.ic_launcher)
        ).setShortLabel("开门").setLongLabel("解锁门禁").setIntent(intent).build()
        ShortcutManagerCompat.requestPinShortcut(context, shortcut, null)
    }

    /**
     * 请求把大尺寸小部件固定到桌面（Android 8.0+ 且桌面支持时）。
     */
    fun requestPinWidget() {
        val context = ContextHolder.get()
        val manager = AppWidgetManager.getInstance(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && manager.isRequestPinAppWidgetSupported) {
            val provider = ComponentName(context, LargeReceiver::class.java)
            val requested = manager.requestPinAppWidget(provider, null, null)
            if (!requested) {
                showToast("当前桌面不支持一键添加，请长按桌面手动添加")
            }
        } else {
            showToast("请在桌面长按空白处，选择“小部件”手动添加")
        }
    }
}
