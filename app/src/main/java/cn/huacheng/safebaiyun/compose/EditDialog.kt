package cn.huacheng.safebaiyun.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import cn.huacheng.safebaiyun.unlock.DataRepo
import cn.huacheng.safebaiyun.util.DeviceConfig
import cn.huacheng.safebaiyun.util.showToast
import java.util.Locale

/**
 * 门禁配置面板：带格式校验、错误提示、Key 隐藏与键盘避让。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditDialog(onDismiss: () -> Unit, onSaved: () -> Unit) {
    val initial = remember { DataRepo.readData() }
    var mac by remember { mutableStateOf(initial.first) }
    var key by remember { mutableStateOf(initial.second) }
    var showKey by remember { mutableStateOf(false) }
    var submitted by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val macValid = DeviceConfig.isValidMac(mac)
    val keyValid = DeviceConfig.isValidKey(key)

    ModalBottomSheet(
        onDismissRequest = { onDismiss() },
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(text = "门禁配置", style = MaterialTheme.typography.titleLarge)
            Text(
                text = "从官方 App 的 t_device 表提取 MAC_NUM 与 PRODUCT_KEY。配置只保存在本机，不会上传。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            OutlinedTextField(
                value = mac,
                onValueChange = { mac = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("MAC 地址") },
                placeholder = { Text("AA:BB:CC:DD:EE:FF") },
                singleLine = true,
                isError = submitted && !macValid,
                supportingText = {
                    Text(
                        if (submitted && !macValid) "格式应为 6 组十六进制，用冒号分隔"
                        else "官方 App 中 t_device.MAC_NUM"
                    )
                },
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Characters,
                    keyboardType = KeyboardType.Ascii,
                    imeAction = ImeAction.Next
                )
            )

            OutlinedTextField(
                value = key,
                onValueChange = { key = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("加密密钥") },
                placeholder = { Text("十六进制字符串") },
                singleLine = true,
                isError = submitted && !keyValid,
                visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    TextButton(onClick = { showKey = !showKey }) {
                        Text(if (showKey) "隐藏" else "显示")
                    }
                },
                supportingText = {
                    Text(
                        if (submitted && !keyValid) "应为至少 16 位的十六进制字符串，且长度为偶数"
                        else "官方 App 中 t_device.PRODUCT_KEY"
                    )
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Ascii,
                    imeAction = ImeAction.Done
                )
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = { onDismiss() },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("取消")
                }
                Button(
                    onClick = {
                        submitted = true
                        if (macValid && keyValid) {
                            DataRepo.save(
                                mac = DeviceConfig.normalizeMac(mac),
                                key = key.trim().lowercase(Locale.US)
                            )
                            showToast("配置已保存")
                            onDismiss()
                            onSaved()
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("保存")
                }
            }
        }
    }
}
