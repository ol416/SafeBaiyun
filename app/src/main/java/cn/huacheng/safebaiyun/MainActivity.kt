package cn.huacheng.safebaiyun

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.slideIn
import androidx.compose.animation.slideOut
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import cn.huacheng.safebaiyun.compose.HelpView
import cn.huacheng.safebaiyun.compose.MainView
import cn.huacheng.safebaiyun.theme.SafeBaiyunTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Android 15（targetSdk 35）起系统强制 edge-to-edge；显式启用后由 Compose 处理系统栏内边距
        enableEdgeToEdge()
        setContent {
            SafeBaiyunTheme {
                val navController = rememberNavController()
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    NavHost(
                        navController = navController,
                        startDestination = "main"
                    ) {
                        composable(
                            route = "main",
                            enterTransition = { slideIn { IntOffset(-it.width, 0) } },
                            exitTransition = { slideOut { IntOffset(-it.width, 0) } },
                            popEnterTransition = { slideIn { IntOffset(-it.width, 0) } },
                            popExitTransition = { slideOut { IntOffset(-it.width, 0) } }
                        ) {
                            MainView(navController)
                        }

                        composable(
                            route = "helper",
                            enterTransition = { slideIn { IntOffset(it.width, 0) } },
                            exitTransition = { slideOut { IntOffset(it.width, 0) } },
                            popEnterTransition = { slideIn { IntOffset(it.width, 0) } },
                            popExitTransition = { slideOut { IntOffset(it.width, 0) } }
                        ) {
                            HelpView(navController)
                        }
                    }
                }
            }
        }
    }
}
