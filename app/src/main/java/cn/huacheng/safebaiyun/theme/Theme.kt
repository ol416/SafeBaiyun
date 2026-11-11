package cn.huacheng.safebaiyun.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * 应用主题。
 *
 * Android 15（targetSdk 35）起 `Window.statusBarColor` / `navigationBarColor` 已失效，
 * 系统强制 edge-to-edge，状态栏直接透出应用内容。因此这里不再设置任何栏颜色，
 * 只根据顶栏（primary）的明度决定状态栏图标使用深色还是浅色。
 */
@Composable
fun SafeBaiyunTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColors else LightColors
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            val controller = WindowCompat.getInsetsController(window, view)
            // 顶栏是 primary 色，状态栏图标要与之形成对比
            controller.isAppearanceLightStatusBars = colorScheme.primary.luminance() > 0.5f
            // 导航栏区域透出页面背景
            controller.isAppearanceLightNavigationBars = colorScheme.background.luminance() > 0.5f
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
