package cn.huacheng.safebaiyun.util

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 时间展示工具：当天只显示 HH:mm，跨天显示 MM-dd HH:mm。
 */
fun formatClockTime(millis: Long): String {
    if (millis <= 0L) return "--:--"
    val now = Calendar.getInstance()
    val then = Calendar.getInstance().apply { timeInMillis = millis }
    val sameDay = now.get(Calendar.YEAR) == then.get(Calendar.YEAR) &&
        now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
    val pattern = if (sameDay) "HH:mm" else "MM-dd HH:mm"
    return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(millis))
}

/**
 * 把毫秒耗时格式化为“0.8 秒 / 1.2 秒”，避免长小数。
 */
fun formatElapsed(millis: Long): String {
    val safe = millis.coerceAtLeast(0L)
    return if (safe < 1000L) {
        "${safe} 毫秒"
    } else {
        String.format(Locale.US, "%.1f 秒", safe / 1000f)
    }
}
