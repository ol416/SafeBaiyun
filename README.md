# 白云通 · SafeBaiyun

> 广州市白云区出租屋 / 社区蓝牙门禁的**离线**控制端。只要拿到门禁的 MAC 地址与加密 Key，断网也能开门。

`Kotlin 2.2.10` · `Jetpack Compose` · `minSdk 21` · `targetSdk 37` · 无网络权限

## 简介

官方 App「平安回家」（`com.huacheng.baiyunuser`）开门时依赖网络与官方鉴权。本项目通过分析其本地缓存数据与 BLE 通信协议，把开门所需的东西压缩到两个值：

- **MAC**：门禁设备的蓝牙地址，例如 `AA:BB:CC:DD:EE:FF`
- **Key**：官方数据库里的 `PRODUCT_KEY`，用于生成开门指令的 DES 密钥

填好这两个值后，App 直接按 MAC 连接门禁、读取挑战码、加密写回，全程不联网。整个安装包**没有申请 `INTERNET` 权限**，从系统层面就排除了「偷偷上传配置」的可能。

## 功能特性

- **离线开门**：不做蓝牙扫描，按 MAC 直连；无网络、无账号、无服务端。
- **实时进度**：连接 → 发现服务 → 读取挑战 → 写入密钥，四步进度 + 失败原因与下一步操作。
- **桌面快捷方式**：一键开门，结果卡浮在桌面之上；成功 1.4 秒后自动关闭，失败可就地重试。
- **桌面小部件**：2×2 与 2×1 两种尺寸，显示最近一次开门结果，点击即开门。
- **本地配置与记录**：MAC / Key / 上次结果存于 `SharedPreferences`；每次开门的完整运行日志按会话存于应用私有文件，主页可翻看最近 20 次记录。两者都已从云备份和换机迁移中排除。
- **设备名称**：开门记录会尝试读取系统蓝牙名称（API 30+ 再回退读取系统别名）；按 MAC 直连、不扫描时系统常常没有缓存名称，此时降级显示脱敏 MAC。
- **现代化界面**：Compose + Material 3，固定品牌配色、亮暗两套主题、edge-to-edge 布局，适配 Android 5.0 ~ Android 17。

## 快速开始

### 1. 提取 MAC 与 Key

详细步骤见 [extract.md](extract.md)：

- **Root 手机**：`/data/data/com.huacheng.baiyunuser/databases/(32 位 hash).db`
- **无 Root**：用 MIUI「备份与恢复」只备份「平安回家」，解压备份包里的 `apps/com.huacheng.baiyunuser/db/(32 位 hash).db`
- 用任意 SQLite 查看器打开数据库，查询 `t_device` 表：`MAC_NUM` 是 MAC，`PRODUCT_KEY` 是 Key

### 2. 填写配置

打开 App → 右上角「编辑」→ 粘贴 MAC 与 Key → 保存。可点眼睛图标核对 Key，格式错误会直接提示。

### 3. 开门

点主页的大圆形按钮即可。若按钮下方提示未授权 / 未配置 / 蓝牙未开，按钮会自动变成「申请权限」「去配置」「打开蓝牙」等对应操作。

## 三种入口

| 入口                   | 说明                                                                                 |
| :--------------------- | :----------------------------------------------------------------------------------- |
| **主页**         | 状态条、解锁卡、设备卡（脱敏显示 / 复制 / 编辑）、上次结果、快捷入口、可折叠运行日志（可翻看历史会话） |
| **桌面快捷方式** | 长按桌面添加，或从 App 内「创建快捷方式」；点击后以半透明结果卡开门，不进入最近任务  |
| **桌面小部件**   | 2×2 / 2×1，显示最近一次成功或失败及时间；开门后自动刷新                            |

> 部分国产系统（MIUI / ColorOS 等）需要手动授予「创建桌面快捷方式」权限；部分启动器不支持 `requestPinAppWidget`，可改从桌面小部件列表手动添加。

## 状态与失败原因

一次开门的状态机为 `Idle → Running(step) → Success(elapsed) | Failed(reason)`，失败原因均可在界面上转换成可点击的下一步操作：

| 原因                                               | 含义                          | 建议                               |
| :------------------------------------------------- | :---------------------------- | :--------------------------------- |
| `NO_CONFIG` / `INVALID_MAC` / `INVALID_KEY`  | 未配置或格式错误              | 打开配置面板重新填写               |
| `PERMISSION_DENIED`                              | 缺少「附近的设备」权限        | 申请权限                           |
| `NO_BLUETOOTH` / `BLUETOOTH_OFF`               | 设备无蓝牙 / 蓝牙未开         | 打开蓝牙设置                       |
| `CONNECT_FAILED` / `TIMEOUT`                   | 连不上门禁（12 秒看门狗超时） | 靠近门禁、确认已上电后重试         |
| `SERVICE_NOT_FOUND` / `CHARACTERISTIC_MISSING` | 不是受支持的门禁或固件不匹配  | 不属于本项目支持范围               |
| `READ_FAILED` / `WRITE_FAILED`                 | 读挑战码 / 写指令失败         | 重试；仍失败请核对 `PRODUCT_KEY` |
| `UNKNOWN`                                        | 其他 GATT 错误                | 重试                               |

前置检查类失败（未配置、无权限、蓝牙未开等）不会覆盖「上次开门」记录。

## 构建

**环境要求**：JDK 17、Android SDK Platform 37（`compileSdk = 37`）、可写的 `~/.gradle`。

```bash
# 在 local.properties 中指定 sdk.dir 后：
./gradlew :app:assembleDebug     # 产物：app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:assembleRelease   # R8 混淆 + 资源压缩；未配置签名，产物未签名
```

Windows 下把 `./gradlew` 换成 `gradlew.bat`。`gradle.properties` 里的 `org.gradle.java.home` 指向本机 JDK 17 路径（`C:/Program Files/Java/jdk-17`），换机器时需要改成自己的。

仓库目前**没有** `src/test` 与 `src/androidTest` 源码，`./gradlew test` 只会执行空用例集合。

## 技术栈

| 维度        | 选型                                                                                          |
| :---------- | :-------------------------------------------------------------------------------------------- |
| 语言 / 构建 | Kotlin 2.2.10、Gradle 9.8.2、AGP 9.5.1、Version Catalog、JVM target 1.8                       |
| SDK         | `minSdk 21` / `targetSdk 37` / `compileSdk 37`，`versionCode 2` / `versionName 1.1` |
| UI          | Compose BOM 2024.05.00（Compose 1.6.7 + Material3 1.2.1），Navigation Compose 2.7.7           |
| 桌面小部件  | Glance 1.1.1（`glance-appwidget` + `glance-material3`）                                   |
| 持久化      | SharedPreferences（`data`）+ 应用私有文件（`filesDir/unlock_logs/*.log`）                     |
| 异步        | kotlinx-coroutines-android 1.7.1，`StateFlow` 状态机                                        |
| 蓝牙        | Android BLE / GATT API，DES/ECB/NoPadding                                                     |

## 项目结构

```
app/src/main/java/cn/huacheng/safebaiyun/
├── MainActivity.kt           # 单 Activity + Compose NavHost（main / helper）
├── ShortcutActivity.kt       # 快捷方式 / 小部件入口的半透明结果卡
├── compose/                  # 界面层：MainView、EditDialog、HelpView、结果卡、文案
├── theme/                    # Material 3 配色、形状、字体
├── unlock/                   # 核心层：DataRepo、UnlockState、UnlockRepo（GATT + 看门狗）、LogRepo（日志会话）
├── util/                     # 协议组包（LockBiz）、DES（FDes）、校验、格式化
└── widget/                   # Glance 大/中部件、刷新器、快捷方式辅助
```

## 工作原理

### BLE 流程

1. 由 MAC 直接 `connectGatt`，启动 **12 秒全流程看门狗**；已有任务运行时忽略重复请求。
2. 发现门禁专用服务 `14839ac4-7d7e-415c-9a42-167340cf2339`，挑选读 / 写特征，并**串行订阅通知特征的 CCCD**：BLE 同一时刻只允许一个 GATT 操作，描述符写完并收到回调后才能继续，否则紧随其后的 `readCharacteristic` 会被系统以 `Device busy` 拒绝。
3. 等待随机挑战码：既发起特征读取，也接受门禁通过通知主动下发，两条通道谁先返回有效数据就用谁；读请求被拒会短延迟重试，5 秒内都取不到才判定失败。
4. 用挑战码 + MAC + Key 组包并 DES 加密，写入写特征，成功后断开连接并刷新小部件。

### 通信帧

明文块为 `2 字节累加和（小端） + 挑战码`，按 8 字节补零后用 Key 前 8 字节做 `DES/ECB/NoPadding` 加密，取前 8 字节组装成 20 字节帧：

| 偏移 | 长度 | 内容                                    |
| :--- | :--- | :-------------------------------------- |
| 0    | 1    | `0xA5` 帧头                           |
| 1    | 1    | 帧总长度（密文长度 + 12）               |
| 2    | 1    | 指令码 `0x05`                         |
| 3    | 4    | MAC 第 3 ~ 6 字节                       |
| 7    | 3    | 控制字 `0x00 0x01 0x07`               |
| 10   | 8    | DES 密文                                |
| 18   | 1    | 校验和 = `(~整帧无符号累加和) & 0xFF` |
| 19   | 1    | `0x5A` 帧尾                           |

累加和同时包含挑战码与 Key 的全部字节，因此 Key 填错时通常表现为写入失败而非明文报错。

## 隐私与权限

- 仅声明 `BLUETOOTH`、`BLUETOOTH_CONNECT`、`com.android.launcher.permission.INSTALL_SHORTCUT`，**没有** `INTERNET`，也没有 `BLUETOOTH_SCAN`（只按 MAC 直连，不扫描）。
- `android:allowBackup="true"`，但 `backup_rules.xml` 与 `data_extraction_rules.xml` 都排除了 `sharedpref/data.xml` 和日志目录 `file/unlock_logs`：MAC / Key 属于敏感凭据，日志含设备名称与逐步运行记录，均不进云备份、不参与换机迁移。
- 日志不含 Key 明文与挑战码内容，MAC 已脱敏；系统蓝牙名称只写本地文件与界面，不进 logcat。
- 没有蓝牙的设备也能安装查看说明，不会因缺少硬件特性被商店过滤。

## 常见问题

**Q: 一直提示「连接失败 / 超时」？**
靠近门禁、确认门禁已上电；距离过远或门禁正被其他 App 占用时都会连不上。

**Q: 蓝牙已开仍提示「未授权」？**
Android 12 及以上需要授予「附近的设备」（`BLUETOOTH_CONNECT`）权限；国产系统还可能在系统设置里额外限制。

**Q: 换手机 / 重装后配置没了？**
这是刻意设计的：配置不参与备份与迁移。请重新从官方 App 的数据库中提取 MAC 与 Key。

**Q: 小部件显示空白或错位？**
Glance 小部件在 Android 12 以下兼容性有限，建议升级系统或改用桌面快捷方式。

**Q: 能同时管理多个门禁吗？**
当前只保存一套 MAC / Key，一次只能控制一台门禁。

**Q: 日志里的「设备」为什么显示「名称未获取到」？**
本项目按 MAC 直连、不做蓝牙扫描，Android 蓝牙栈缓存里通常没有该设备的广播名，`BluetoothDevice.name` 会返回空。连接前后各尝试读取一次，Android 11 及以上还会再回退读取系统别名 `alias`（同样只读缓存，不触发扫描或额外的 GATT 操作）；仍取不到就显示脱敏 MAC。这不影响开门，也不会为此申请扫描权限。

**Q: 开门日志会保存多久？**
本机保留最近 20 次会话、每次最多 60 行，超出自动轮转；可在主页日志面板中复制或清空。日志不参与云备份与换机迁移，卸载 App 即全部删除。

## 更新日志

- **v1.1（versionCode 2）**：Compose 全面重构，新增状态机与 12 秒看门狗、半透明结果卡、2×2 / 2×1 桌面小部件、失败原因与建议文案；适配 edge-to-edge 与 targetSdk 37；配置排除系统备份。
- 更早改动见 Git 提交历史。

## 免责声明

本项目仅供学习研究与**自有 / 获授权门禁**的便利使用，与「平安回家」官方及任何物业、厂商无关。请勿将提取到的密钥用于非法用途或他人设备；由此产生的一切后果由使用者自行承担。

## 声明

本仓库是 [dogproton/SafeBaiyun](https://github.com/dogproton/SafeBaiyun) 的 Fork，全部代码与文档均由 **DeepSeek** 模型与 **DSH**（DeepSeek Harness）驱动的 AI 修改、生成与维护，属于 AI 驱动的二次创作版本，**与原作者无关**；原作者不对本分支的内容、质量、安全性或任何后果负责。如需原版或了解原始设计意图，请前往上游仓库。
