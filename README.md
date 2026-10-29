# CarDesk · 车控桌面

一个从零实现的 **Android 车机桌面 App**（Kotlin）。

桌面由场景页（首页）、导航组合页（地图 + 播放器 + 车况）、应用抽屉、气候整页、设置面板组成，
底部是一条一体化 Dock（应用入口 + 媒体控制 + 全量空调控制）。界面对齐 1920×1080 设计坐标系，
按实际屏幕等比缩放，支持日间 / 夜间两套配色。

车控部分（空调、车窗、车门、引擎盖、胎压、续航、油量、车况、天气）通过反射调用车机内置的
ECARX `AdaptAPI` 与 AOSP `Car` 属性通道；读不到真车数据时自动降级为模拟数据，
所以桌面的全部界面在普通设备上也能完整体验。
**车窗**在本车上已能读到逐扇开度；**四个侧门与引擎盖**仍读不到（车机固件没有这两个信号），
见下面的「已知限制」。

设置里的**氛围灯**会解析正在播放的音乐频谱（`Visualizer` 挂在播放会话上），
换算成灯光的颜色与亮度，并下发到车机氛围灯通道（颜色 / 亮度 / 音乐律动）。

| | |
| --- | --- |
| 语言 / 依赖 | Kotlin，纯 framework API，**无任何运行时依赖** |
| 目标平台 | Android 11+（API 30+） |
| 包名 | `com.ecarx.cardesk` |
| 构建变体 | `system`（平台签名 + `sharedUserId=android.uid.system`）/ `debug`（普通安装） |
| 在线更新 | App 内「设置 → 在线更新」**手动**检测本仓库的 GitHub Release，有新版可一键下载并安装 |

## 已知限制

下面这一项**还没有真正生效**。代码写了、界面也画了，但达不到可用状态 ——
用的时候请按「还没做」来理解。

### 1. 车门状态：侧门与尾门改走车机属性通道（**待真机验证**），引擎盖仍读不到

- **车窗**已经能读到真值：逐扇开度走 `AdaptAPI` 的
  `getCustomizeFunctionValue(BCM_FUNC_WINDOW_POS, 窗区)`，
  窗区号按名字解析（`WINDOW_ROW_1_LEFT` 等），读不到的那一扇显示 `--`，
  不会当成「关着」；
- **四个侧门 + 尾门**在车机端有独立的属性（`GeelyVehicleProperty` 里的
  `PA_CP_DoorDrvrSts` / `DoorDrvrReSts` / `DoorPassSts` / `DoorPassReSts` / `TrSts`），
  在车机的 `car_service` 清单里**标为只读、确实存在**。
  第七十二轮之前读不到，是因为**属性通道压根没建起来** ——
  我们调的 `Car.createCar(Context)` 这个重载在车机端那份 `android.car` 里
  **压根不存在**（它只有带 `ServiceConnection` 的重载），
  诊断包里那句「Car.createCar 返回 null」就是这么来的。
  签名改对之后这条通道才有机会起来；
- 但**这一档还没在真机上验证过**：属性值是 `0`（关）/ `1`（开）还是反过来，
  要等装到车上开一次门看日志里的原始值才能定；
- **引擎盖**仍然读不到：车机端那份 jar 里**没有** `PA_CP_Hood*` 之类的属性，
  `BCM_FUNC_DOOR` 也只为 `DOOR_REAR`（后备箱）实现了一个区域；
- 老的 `IDoorAndWindow`（`getDoorAndWindowState`）通道在这台车机上
  根本不存在这个类；
- 所以在验证之前，**不要**把界面上的侧门图标当成真实车况 ——
  读不到时显示 `--`，不会当成「关着」。

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

