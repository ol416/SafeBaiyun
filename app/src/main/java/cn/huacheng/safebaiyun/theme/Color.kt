package cn.huacheng.safebaiyun.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// ---------------------------------------------------------------------------
// 白云蓝品牌配色
//
// 不再跟随系统动态取色：动态色会让深色模式出现“纯黑背景 + 淡蓝标题栏”，
// 门禁状态（成功/失败）也缺少稳定的语义色。这里固定品牌色，深色模式单独给一套。
// ---------------------------------------------------------------------------

val BaiyunBlue = Color(0xFF1F5F9E)
val BaiyunBlueDark = Color(0xFF0B2B4A)
val BaiyunSky = Color(0xFFD5E4F5)
val BaiyunGreen = Color(0xFF2F6B55)
val BaiyunGreenLight = Color(0xFFD3EBDE)
val BaiyunAmber = Color(0xFF8A5A00)
val BaiyunAmberLight = Color(0xFFFFE7B8)
val BaiyunInk = Color(0xFF16202B)
val BaiyunInkSoft = Color(0xFF44525F)
val BaiyunPaper = Color(0xFFF7F9FC)

val LightColors = lightColorScheme(
    primary = BaiyunBlue,
    onPrimary = Color.White,
    primaryContainer = BaiyunSky,
    onPrimaryContainer = BaiyunBlueDark,
    inversePrimary = Color(0xFFA6C8FF),
    secondary = Color(0xFF4C6274),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD8E4EF),
    onSecondaryContainer = Color(0xFF0F2C3F),
    tertiary = BaiyunGreen,
    onTertiary = Color.White,
    tertiaryContainer = BaiyunGreenLight,
    onTertiaryContainer = Color(0xFF0A2E21),
    // 注意：M3 的 error 系列同时承担“开门失败”的语义色
    error = Color(0xFFB3261E),
    onError = Color.White,
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
    background = BaiyunPaper,
    onBackground = BaiyunInk,
    surface = Color.White,
    onSurface = BaiyunInk,
    surfaceVariant = Color(0xFFE3EAF2),
    onSurfaceVariant = BaiyunInkSoft,
    surfaceTint = BaiyunBlue,
    inverseSurface = Color(0xFF2B3138),
    inverseOnSurface = Color(0xFFEFF1F5),
    outline = Color(0xFF73818F),
    outlineVariant = Color(0xFFC9D3DD),
    scrim = Color.Black
)

val DarkColors = darkColorScheme(
    primary = Color(0xFFA6C8FF),
    onPrimary = Color(0xFF00325A),
    primaryContainer = Color(0xFF17456F),
    onPrimaryContainer = BaiyunSky,
    inversePrimary = BaiyunBlue,
    secondary = Color(0xFFB4C8DA),
    onSecondary = Color(0xFF1E3445),
    secondaryContainer = Color(0xFF33495B),
    onSecondaryContainer = Color(0xFFD8E4EF),
    tertiary = Color(0xFF9DD3B9),
    onTertiary = Color(0xFF00382A),
    tertiaryContainer = Color(0xFF1C4E3C),
    onTertiaryContainer = Color(0xFFD3EBDE),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF0F1419),
    onBackground = Color(0xFFE2E8EF),
    surface = Color(0xFF151B22),
    onSurface = Color(0xFFE2E8EF),
    surfaceVariant = Color(0xFF2A3441),
    onSurfaceVariant = Color(0xFFBAC6D2),
    surfaceTint = Color(0xFFA6C8FF),
    inverseSurface = Color(0xFFE2E8EF),
    inverseOnSurface = Color(0xFF2B3138),
    outline = Color(0xFF8A98A6),
    outlineVariant = Color(0xFF3B4754),
    scrim = Color.Black
)
