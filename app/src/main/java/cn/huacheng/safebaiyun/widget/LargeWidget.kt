package cn.huacheng.safebaiyun.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.actionStartActivity
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.components.CircleIconButton
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import cn.huacheng.safebaiyun.R
import cn.huacheng.safebaiyun.ShortcutActivity
import cn.huacheng.safebaiyun.unlock.DataRepo
import cn.huacheng.safebaiyun.unlock.LastUnlock
import cn.huacheng.safebaiyun.util.formatClockTime

class LargeReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget
        get() = LargeWidget
}

/**
 * 2x2 大尺寸部件。使用 SizeMode.Exact + fillMaxSize 跟随桌面实际格子大小，
 * 不再写死 170dp 高度（旧实现会被 provider 的 110dp 上限裁切）。
 */
object LargeWidget : GlanceAppWidget() {

    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val last = DataRepo.lastResult()
        provideContent {
            GlanceTheme(colors = WidgetColorProviders) {
                WidgetContent(last)
            }
        }
    }

    @Composable
    private fun WidgetContent(last: LastUnlock?) {
        val size = LocalSize.current
        val compact = size.height < 120.dp
        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .cornerRadius(24.dp)
                .background(GlanceTheme.colors.surface)
                .padding(
                    start = 16.dp,
                    end = 16.dp,
                    top = if (compact) 8.dp else 16.dp,
                    bottom = if (compact) 8.dp else 16.dp
                )
        ) {
            Row(
                modifier = GlanceModifier.fillMaxWidth(),
                verticalAlignment = Alignment.Vertical.CenterVertically
            ) {
                Image(
                    modifier = GlanceModifier.size(if (compact) 40.dp else 56.dp, if (compact) 40.dp else 56.dp),
                    provider = ImageProvider(R.drawable.guangzhou),
                    contentScale = ContentScale.Fit,
                    contentDescription = null
                )
                Spacer(modifier = GlanceModifier.defaultWeight())
                CircleIconButton(
                    imageProvider = ImageProvider(R.drawable.unlock),
                    contentDescription = "解锁门禁",
                    backgroundColor = GlanceTheme.colors.primary,
                    contentColor = GlanceTheme.colors.onPrimary,
                    onClick = actionStartActivity<ShortcutActivity>()
                )
            }
            Spacer(modifier = GlanceModifier.defaultWeight())
            Text(
                text = "白云通",
                style = TextStyle(
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = GlanceTheme.colors.onSurface
                )
            )
            Text(
                text = statusText(last),
                style = TextStyle(
                    fontSize = 12.sp,
                    color = GlanceTheme.colors.onSurfaceVariant
                )
            )
        }
    }
}

internal fun statusText(last: LastUnlock?): String = when {
    last == null -> "点击开门"
    last.success -> "上次成功 ${formatClockTime(last.at)}"
    else -> "上次失败 ${formatClockTime(last.at)}"
}
