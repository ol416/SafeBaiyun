package cn.huacheng.safebaiyun.widget

import androidx.glance.appwidget.updateAll
import cn.huacheng.safebaiyun.util.ContextHolder
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

/**
 * 开门结束后刷新桌面小部件上的“上次结果”。
 */
object WidgetRefresher {

    @OptIn(DelicateCoroutinesApi::class)
    fun refresh() {
        val context = ContextHolder.get()
        GlobalScope.launch(Dispatchers.IO) {
            runCatching { LargeWidget.updateAll(context) }
            runCatching { MediumWidget.updateAll(context) }
        }
    }
}
