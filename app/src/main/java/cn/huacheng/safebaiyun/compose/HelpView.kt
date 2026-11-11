package cn.huacheng.safebaiyun.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import cn.huacheng.safebaiyun.widget.WidgetHelper

/**
 * 使用说明：按“首次配置 / 状态含义 / 快捷入口 / 常见问题”分卡片展示，
 * 长文本不再堆成一堵墙。
 */
@Composable
fun HelpView(navController: NavController) {
    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .widthIn(max = 600.dp)
        ) {
            HelperTopBar(navController)
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .navigationBarsPadding(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 12.dp,
                    bottom = 24.dp
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item { AboutCard() }
                item { SetupCard() }
                item { StatusCard() }
                item { EntryCard() }
                item { FaqCard() }
            }
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            content()
        }
    }
}

@Composable
private fun BodyText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun AboutCard() {
    SectionCard(title = "关于白云通") {
        BodyText("白云通是广州市白云区蓝牙门禁的离线控制端：只需要门禁的 MAC 地址与加密 Key，不依赖网络即可开门。")
        BodyText("配置只保存在本机（SharedPreferences），已排除系统云备份，不会上传到任何服务器。")
    }
}

@Composable
private fun SetupCard() {
    SectionCard(title = "首次配置") {
        BodyText("1. 提取 MAC 与 Key")
        BodyText("Root 手机：/data/data/com.huacheng.baiyunuser/databases/ 下的 (32 位 hash).db")
        BodyText("无 Root：用 MIUI 备份「平安回家」，解压备份包中的 apps/com.huacheng.baiyunuser/db/(32 位 hash).db")
        BodyText("2. 用任意 SQLite 查看器打开 .db，查询 t_device 表：MAC_NUM 是 MAC，PRODUCT_KEY 是 Key")
        BodyText("3. 回到主页点右上角编辑，粘贴 MAC 与 Key 并保存")
        BodyText("4. 点击「解锁」即可开门，也可以创建快捷方式或添加桌面小部件")
    }
}

@Composable
private fun StatusCard() {
    SectionCard(title = "状态说明") {
        BodyText("主页会显示蓝牙、权限、设备三项状态，以及开门流程的四个步骤：")
        StatusRow("连接", "正在建立 BLE 连接")
        StatusRow("发现服务", "校验门禁专用服务与读写特征")
        StatusRow("读取挑战", "读取门禁下发的随机数")
        StatusRow("写入密钥", "加密后写入开门指令")
        BodyText("成功会显示用时并记录“上次开门”；失败会给出具体原因与下一步操作（开蓝牙 / 授权 / 去配置 / 重试）。")
    }
}

@Composable
private fun StatusRow(label: String, detail: String) {
    Row(verticalAlignment = Alignment.Top) {
        Text(
            text = label,
            modifier = Modifier.width(72.dp),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = detail,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun EntryCard() {
    SectionCard(title = "快捷方式与桌面小部件") {
        BodyText("快捷方式点击图标即可开门；部分国产系统（OPPO / MIUI 等）需要手动授予“创建桌面快捷方式”权限。")
        BodyText("桌面小部件有大（2×2）与中（2×1）两种样式，会显示最近一次开门结果。Android 12 以下的小部件显示可能不正常。")
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedButton(
                onClick = { WidgetHelper.createShortcut() },
                modifier = Modifier.weight(1f)
            ) {
                Text("创建快捷方式", maxLines = 1)
            }
            OutlinedButton(
                onClick = { WidgetHelper.requestPinWidget() },
                modifier = Modifier.weight(1f)
            ) {
                Text("添加小部件", maxLines = 1)
            }
        }
        Row(modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = { WidgetHelper.requestPermission() },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("打开应用权限设置")
            }
        }
    }
}

@Composable
private fun FaqCard() {
    SectionCard(title = "常见问题") {
        BodyText("· 提示“连接失败 / 超时”：靠近门禁、确认门禁已上电，再重试。")
        BodyText("· 提示“没有找到门禁服务 / 特征不完整”：该设备不是受支持的门禁，或固件不匹配。")
        BodyText("· 提示“写入密钥失败”：多半是 PRODUCT_KEY 填错，请重新核对官方 App 中的值。")
        BodyText("· 蓝牙已开启仍提示未授权：Android 12 及以上需要授予「附近的设备」权限。")
    }
}
