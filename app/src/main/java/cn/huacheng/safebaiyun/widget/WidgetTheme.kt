package cn.huacheng.safebaiyun.widget

import androidx.glance.material3.ColorProviders
import cn.huacheng.safebaiyun.theme.DarkColors
import cn.huacheng.safebaiyun.theme.LightColors

/**
 * 桌面小部件与 App 使用同一套品牌配色，避免跟随壁纸后“纯黑 + 淡蓝”的割裂感。
 */
val WidgetColorProviders = ColorProviders(light = LightColors, dark = DarkColors)
