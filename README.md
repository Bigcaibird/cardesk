# CarDesk · 车控桌面

一个从零实现的 **Android 车机桌面 App**（Kotlin）。

桌面由场景页（首页）、导航组合页（地图 + 播放器 + 车况）、应用抽屉、气候整页、设置面板组成，
底部是一条一体化 Dock（应用入口 + 媒体控制 + 全量空调控制）。界面对齐 1920×1080 设计坐标系，
按实际屏幕等比缩放，支持日间 / 夜间两套配色。

车控部分（空调、车窗、车门、引擎盖、胎压、续航、油量、车况、天气）通过反射调用车机内置的
ECARX `AdaptAPI` 与 AOSP `Car` 属性通道；读不到真车数据时自动降级为模拟数据，
所以桌面的全部界面在普通设备上也能完整体验。
**其中车门 / 车窗状态在本车上读不到真实数据**，见下面的「已知限制」。

设置里的**氛围灯**会解析正在播放的音乐频谱（`Visualizer` 挂在播放会话上），
换算成灯光的颜色与亮度；**车机灯珠的下发通道暂未接入**，所以灯还不会真的亮，
同样见「已知限制」。

| | |
| --- | --- |
| 语言 / 依赖 | Kotlin，纯 framework API，**无任何运行时依赖** |
| 目标平台 | Android 11+（API 30+） |
| 包名 | `com.ecarx.cardesk` |
| 构建变体 | `system`（平台签名 + `sharedUserId=android.uid.system`）/ `debug`（普通安装） |
| 在线更新 | App 内「设置 → 在线更新」**手动**检测本仓库的 GitHub Release，有新版可一键下载并安装 |

## 已知限制

下面两项**还没有真正生效**。代码写了、界面也画了，但达不到可用状态 ——
用的时候请按「还没做」来理解。

### 1. 车门 / 车窗状态：读不到真车数据（暂不支持）

- 桌面上的**车门、车窗状态显示的是模拟值**，不是这台车的真实状态；
- 代码里接了两条通道：OEM 的 `IDoorAndWindow`（`getDoorAndWindowState`），
  以及 AOSP 的 `DOOR_POS` / `WINDOW_POS` 属性；
- 但在本车（领克 dcy11 / Android 9）上**两条都读不到** ——
  OEM 通道返回「该功能不支持」，属性通道本车没有注册；
- 所以**不要**把界面上的车门/车窗图标当成真实车况；
- 读到的值一律按「读不到」处理，不会当成「关着」（逐门逐窗独立判定）。

### 2. 音乐氛围灯联动：灯珠下发通道未接入（尚未实现）

- 「设置 → 车控 → 氛围灯」里的**频谱解析**和**颜色换算是真跑的**：
  `Visualizer` 挂在正在播放的音频会话上取 FFT，按频段换算成颜色与亮度，
  设置页里能看到实时频谱条和当前颜色；
- 但**车机灯珠的下发通道是空的** —— `core/ambient/AmbientLightApi.kt`
  只有接口和一个空实现（`NullAmbientLightApi`），
  所以**灯不会真的亮**，颜色只停在计算结果上；
- 等拿到车机氛围灯的真实 API，写一个 `AmbientLightApi` 实现、
  挂到 `AmbientLightController.setApi()` 即可，上层一行都不用改；
- 采集本身还依赖 `RECORD_AUDIO` / `MODIFY_AUDIO_SETTINGS` 两个权限
  （system 变体走 privapp 白名单），拿不到时状态栏会直接写明缺哪个。

## 目录结构

```
cardesk/
├── app/                                    Android 应用模块
│   └── src/
│       ├── main/java/com/cardesk/launcher/
│       │   ├── MainActivity.kt             唯一 Activity，承载桌面
│       │   ├── BootReceiver.kt             开机兜底拉起
│       │   ├── core/                       基础层
│       │   │   ├── Design.kt               1920×1080 设计坐标系 + 等比缩放
│       │   │   ├── Palette.kt / Skin.kt    日夜配色、玻璃/胶囊等材质
│       │   │   ├── Prefs.kt                SharedPreferences 偏好
│       │   │   ├── NavSlots.kt             导航栏槽位模型
│       │   │   ├── SystemOps.kt            系统层操作（隐藏原车 Dock/状态栏等）
│       │   │   ├── ScheduledTasks.kt       计划任务模型与编解码
│       │   │   ├── media/                  媒体接管（会话监听、来源、歌词）
│       │   │   ├── nav/                    导航页逻辑
│       │   │   └── log/                    日志
│       │   ├── vehicle/                    车控层
│       │   │   ├── VehicleGateway.kt       车辆通道抽象
│       │   │   ├── AdaptapiGateway.kt      真车通道（反射 ECARX AdaptAPI）
│       │   │   ├── AdaptapiSensors.kt      续航 / 油量 / 胎压等传感器读取
│       │   │   ├── CarLibGateway.kt        AOSP Car 属性通道
│       │   │   ├── HvacIds.kt              车控属性号表（换车机只改这里）
│       │   │   ├── HvacCodec.kt            属性值编解码
│       │   │   ├── HvacLogic.kt            乐观更新 + 写后屏蔽 + 联动规则
│       │   │   ├── MockVehicleGateway.kt   模拟车辆通道
│       │   │   └── CarSignalMath.kt        信号 → 展示值的纯函数换算
│       │   └── ui/                         界面层
│       │       ├── LauncherView.kt         桌面根视图（页面宿主 + 状态栏 + Dock + 浮层）
│       │       ├── ClimatePage.kt          气候整页
│       │       ├── DrawerPage.kt           应用抽屉
│       │       ├── SourceMenuLayer.kt      媒体来源切换菜单
│       │       ├── SettingsOverlay.kt      设置面板
│       │       ├── NavSlotDialog.kt        导航栏槽位管理
│       │       ├── pages/ shell/ widget/   场景页 / 状态栏与 Dock / 桌面组件
│       │       ├── dialogs/ floats/ embed/ pip/   对话框 / 浮窗 / 应用嵌入 / 画中画
│       ├── main/res/                       drawable、values 资源
│       └── test/java/com/cardesk/launcher/  JVM 单测（含离屏渲染）
├── design/                                 设计板：PNG 原图 + 生成脚本
├── dist/                                   交付产物目录（APK 不入库，走 Release）
├── emu-verify/                             模拟器验证记录与截图
├── system-patch/                           system 变体所需补丁（hiddenapi 白名单等）
├── tools/                                  构建、比对、发布、日志采集脚本
├── gradle/                                 Gradle wrapper
├── build.gradle.kts                        全局构建脚本
├── settings.gradle.kts                     模块与仓库声明
├── gradle.properties                       Gradle 属性
└── version.properties                      versionCode / versionName
```

## 构建

需要 JDK 17 + Android SDK（platform 34、build-tools 34）。

```bash
echo "sdk.dir=/path/to/android-sdk" > local.properties
./gradlew assembleDebug        # 普通安装包
./gradlew testDebugUnitTest    # 纯 JVM 单测
```

> `platform-key/`（平台签名私钥）不在仓库里。有平台密钥时 `assembleSystem` 用平台签名，
> 没有时退化为 debug 签名 —— 也就是 clone 下来就能编译，只是装进 `/system/priv-app/`
> 需要你自己的平台密钥。

## 许可

本仓库**暂未附带 LICENSE 文件**：在作者补充许可条款之前，请勿将本项目视为已授予
再分发或商用授权；二次开发或分发前请先与作者确认。
（App 内「设置 → 开源与声明」页展示的是同一口径。）

## 不随仓库发布的内容

- `platform-key/`：平台签名私钥（`.gitignore` 排除）；
- `preview/`：单测跑出来的离屏渲染截图（`.gitignore` 排除，跑一次单测即可重新生成）；
- 编译产物与 `dist/*.apk`：**最新安装包通过 GitHub Release 发布**；
- 逆向分析文档：只保留在开发机，不随本仓库发布。
