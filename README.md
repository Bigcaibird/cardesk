# CarDesk · 车控桌面

一个从零实现的 **Android 车机桌面 App**（Kotlin）。

纯 framework API 实现，**无任何第三方运行时依赖**；界面对齐 1920×1080 设计坐标系，
按实际屏幕等比缩放，日间 / 夜间两套配色。

车控能力（空调、车窗、车门、胎压、续航、油量、里程、行程与油耗、车况、天气）
通过反射调用车机内置的 ECARX `AdaptAPI` 与 AOSP `Car` 属性通道；读不到真车数据时
自动降级为模拟数据，所以桌面的全部界面在普通设备上也能完整体验。

逐轮开发记录见 [CHANGELOG.md](CHANGELOG.md)。

---

## 特性一览

### 桌面与界面

| 能力 | 说明 |
| --- | --- |
| 场景页（首页） | 时间 / 天气 / 车辆卡片 / 歌词 / 导航引导卡 |
| 导航组合页 | 地图 + 播放器 + 车况，与高德联动 |
| 气候整页 | 全量空调控制的大屏形态 |
| 应用抽屉 | 全部可启动应用、图标缓存、应用禁用 / 启用 |
| 最近任务 | 车机侧任务栈 |
| 车辆信息页 | 俯视车模 + 车门 / 车窗 / 胎压实时状态 |
| 设置面板 | 5 组 25 个设置页，分组导航 |
| 翻页交互 | 单指左右滑**循环**翻页，全屏任意位置均可滑 |
| 页面过渡 | 横滑 + 交叉淡入 + 轻微缩放，260ms 强减速曲线 |
| 设计坐标系 | 1920×1080，按屏宽等比缩放 |
| 日夜模式 | 内置切换，可与高德同步 |
| 自定义壁纸 | 本地图片 / 纯色 / 渐变 |
| 桌面卡片 | 顺序、显隐、样式均可配置 |

### 动效

| 能力 | 说明 |
| --- | --- |
| 统一动效层 | `ui/anim/MotionSpec.kt`（纯 Kotlin 数值规格，可 JVM 单测）+ `ui/anim/Motion.kt`（执行层） |
| 按压反馈 | **每一个**可点元素都有：按下缩小、松手回弹（60ms / 90ms） |
| 状态切换 | 开关、选中的按钮会「鼓一下」，底色与文字颜色渐变过去，不是硬切 |
| 开关滑块 | 圆点从一头滑到另一头（200ms），不是瞬移 |
| 数值变化 | 温度 ±、风量 ±、播放进度都是平滑过渡，数字不硬跳 |
| 对话框进出 | 全部对话框上滑 + 淡入 + 轻微放大进场，退场比进场慢（240ms vs 200ms） |
| 列表入场 | 应用抽屉、任务列表等逐项淡入上移（错峰上限 12 项） |
| 自绘控件 | Dock、状态栏、场景页、温度盘等自绘界面的按压反馈按命中区逐个缩放 |

### Dock 与导航栏

| 能力 | 说明 |
| --- | --- |
| 底部一体化 Dock | 左段四个导航键（主页 / 应用 / 分屏 / 360）+ 媒体控制 + 全量空调 |
| 全局 Dock | 前台服务 + 跨应用覆盖窗，叠在其它应用之上 |
| 导航预留带 | RRO 覆盖系统导航栏高度（112dp），与 Dock 严丝合缝 |
| Dock 让位 | 仅在沉浸型播放器（网易云 / 汽水等）前台时收起；探测不可信时**宁可不让位** |
| 隐藏原车 Dock / 状态栏 | 走 `policy_control`，可全局生效 |
| Dock 可调项 | 圆角、底边距、快捷应用槽位、导航栏形态 |
| 增强导航栏 | 槽位条形态，槽位可自由配置 |

### 车控

| 能力 | 说明 |
| --- | --- |
| 空调 | 温度（主/副驾）、风量、模式、除霜、内外循环、A/C、AUTO |
| 车窗 / 车门 | 逐扇开度、四门 + 尾门状态（读不到时显示 `--`，不误判为「关着」） |
| 胎压 | 四轮胎压 + 胶囊式呈现 |
| 续航 / 油量 | 传感器直读 |
| 里程与油耗 | 总里程、行程里程、行程时长、平均油耗 |
| 车况卡 / 车辆状态面板 | 俯视车模 + 开门动画 + 实时信号 |
| 360 环影 | 车辆状态面板内嵌 |
| 启停控制 | 发动机启停 |
| 驾驶模式 | 切换与状态显示 |
| 车门提醒 | 开门时弹窗提醒 |
| VIN | 实车读取 |

### 媒体与歌词

| 能力 | 说明 |
| --- | --- |
| 媒体会话接管 | 会话监听、幂等播放 / 暂停、上下曲 |
| 车机版 App 接入 | QQ 音乐 / 网易云 / 汽水音乐，含方向盘方控桥 |
| 歌词 | 行级 / 逐字（酷狗 KRC）/ 在线歌词，逐字擦除效果 |
| KTV 歌词模式 | 三行 / 四行 / 五行、两行错落阶梯 |
| 仪表歌词投射 | 投射到副屏，可选主副歌词配色 |
| 专辑封面 / 进度条 | 实时封面与播放进度 |
| 媒体源切换 | 默认媒体源可配置，卡片显示来源图标 |
| 收藏 / 播放模式 | 音乐卡图标按钮：收藏走标准心形评分，未适配的 App 也能自动识别 |

### 导航

| 能力 | 说明 |
| --- | --- |
| 导航引导卡 | 高德导航 / 巡航识别、车道箭头 |
| 巡航红绿灯 | 灯态大圆 + 读秒，无信号不占位 |
| 画中画 | 单画中画 / 双画中画，黑屏分级自愈 |

### 天气与信息

| 能力 | 说明 |
| --- | --- |
| 天气 | 车机天气 SDK + 城市定位（高德广播） |
| 状态栏 | 位置、天气、蓝牙、WiFi、信号、油量、时钟 |
| 仪表盘 | 歌词投射 + 状态显示 |

### 系统与扩展

| 能力 | 说明 |
| --- | --- |
| 在线更新 | App 内检测 R2 上的更新清单，一键下载并安装 |
| 云端应用库 | 列出传到 R2 的 APK，点一下直接下载安装 |
| 局域网传输 | 桌面端 HTTP 服务，手机浏览器直传 |
| 按键增强 | 方控按键映射增强 |
| 计划任务 | 定时 / 周期任务，可下发到应用 |
| ADB 调试 | App 内开启 ADB 通道 |
| 自动热点 | 按需自动开热点 |
| 原厂设置 / 系统设置 | 入口直达车机各系统页 |
| 诊断日志 | 一键导出诊断包（含原始值与证据链） |
| 重启车机 | 面板内直接重启 |

---

## 编译环境

| 项 | 版本 / 说明 |
| --- | --- |
| 语言 | Kotlin 1.9.24（`jvmTarget 1.8`，Java 8 字节码） |
| Android Gradle Plugin | 8.5.2 |
| Gradle | 8.7（仓库自带 `../toolchain/gradle-8.7`，**无 wrapper**） |
| JDK | 17（仓库自带 `../toolchain/jdk-17.0.20.1+1`） |
| Android SDK | platform **29**；build-tools **34.0.0**（`aapt2` / `apksigner`） |
| 依赖 | **零第三方运行时依赖**，仅 framework API + Kotlin stdlib |
| 仓库源 | 阿里云镜像（gradle-plugin / google / public）+ `google()` + `mavenCentral()` |
| 签名 | `system` 变体用 AOSP platform 证书（私钥不入库，缺失时自动退回 debug 签名） |

工具链默认放在仓库**上一级**的 `toolchain/` 目录：

```
<workspace>/
├── toolchain/              # gradle-8.7 / jdk-17.0.20.1+1 / sdk
└── cardesk/                # 本仓库
```

环境变量可覆盖：

| 变量 | 默认值 |
| --- | --- |
| `CARDESK_TOOLCHAIN` | `<repo>/../toolchain` |
| `CARDESK_GRADLE` | `$CARDESK_TOOLCHAIN/gradle-8.7/bin/gradle` |
| `CARDESK_AAPT2` | `$CARDESK_TOOLCHAIN/sdk/build-tools/34.0.0/aapt2` |
| `GRADLE_USER_HOME` | `<repo>/../.gradle-home` |
| `JAVA_HOME` | `$CARDESK_TOOLCHAIN/jdk-17.0.20.1+1/Contents/Home` |

---

## 支持的 Android 版本

| 项 | 值 |
| --- | --- |
| `minSdk` | **26**（Android 8.0 Oreo） |
| `targetSdk` | **29**（Android 10） |
| `compileSdk` | **29**（Android 10） |
| 实测真机 | 领克 dcy11 / **Android 9（API 28）** 车机 |
| 实测模拟器 | API 28（AVD `cardesk28`，1920×1080 @160dpi）、API 29 |

### 为什么 targetSdk 停在 29

Android 的隐藏 API（hidden API）限制按 `targetSdk` 分级：
`targetSdk 29` 可以用「API 29 及以下支持」的成员，`targetSdk 34` 只能用「API 27 及以下支持」的成员。

车机的天气主通路等**系统私有类**属于新一档 —— `targetSdk 29` 才拿得到，
这与参考版车机应用一致。

### 安装方式

- **`system` 变体**：平台签名 + `sharedUserId=android.uid.system`，
  装到 `/system/priv-app/` 并重启后生效（需要平台签名才能拿到车控与氛围灯权限）。
- **`debug` 变体**：普通签名，普通安装即可运行，车控能力降级为模拟数据。

---

## 构建

```bash
export JAVA_HOME=<repo>/../toolchain/jdk-17.0.20.1+1/Contents/Home
export GRADLE_USER_HOME=<repo>/../.gradle-home
export PATH=$JAVA_HOME/bin:<repo>/../toolchain/sdk/platform-tools:$PATH

GRADLE=<repo>/../toolchain/gradle-8.7/bin/gradle

# 普通安装包（包名 com.cardesk.launcher）
$GRADLE assembleDebug --offline

# 平台签名系统包（包名 com.ecarx.cardesk，versionName 带 -system 后缀）
$GRADLE assembleSystem -PcardeskPackage=com.ecarx.cardesk --offline

# 全量 JVM 单测
$GRADLE testSystemUnitTest --offline
```

### 出包

```bash
bash tools/release.sh --notes-file tools/notes-r197.md
```

`tools/release.sh` 会自动：版本号 +1 → 干净目录全量构建 + 跑单测 →
校验包名 / versionCode / **平台证书指纹** → 算 SHA-256 → 落 `dist/` →
重写更新清单 `dist/cardesk-latest.json`。

产物：

```
dist/CarDesk-platform-ecarx-cardesk-<versionCode>.apk
dist/cardesk-latest.json        # R2 更新清单（App 在线更新读它）
```

上传到 R2（凭据只从环境变量读）：

```bash
export R2_AK=... R2_SK=...
python3 tools/publish_r2.py --bucket file \
  --apk dist/CarDesk-platform-ecarx-cardesk-<versionCode>.apk \
  --manifest dist/cardesk-latest.json
```

### 发布状态

| 项 | 值 |
| --- | --- |
| 当前版本 | `1.0.1176-system`（versionCode **4272**，第198轮） |
| GitHub Release | [`v1.0.1176`](https://github.com/Bigcaibird/cardesk/releases/tag/v1.0.1176) —— 已发布 |
| R2 上传 | **未做** —— 本机没有 `R2_AK` / `R2_SK` 凭据（凭据只从环境变量读） |

各轮产物与发布记录见 [CHANGELOG.md](CHANGELOG.md)。

---

## 目录结构

```
cardesk/
├── app/                                 Android 应用模块
│   └── src/main/java/com/cardesk/launcher/
│       ├── MainActivity.kt              唯一 Activity，承载桌面
│       ├── core/                        基础层（设计坐标系、配色、偏好、媒体、
│       │                                车控通道、导航、日志、更新、应用库）
│       ├── vehicle/                     车控层（AdaptAPI / AOSP Car 属性通道 + 模拟通道）
│       └── ui/                          界面层（页面、状态栏与 Dock、组件、对话框、
│                                        浮窗、画中画、应用嵌入）
│           └── anim/                    动效层（MotionSpec 数值规格 + Motion 执行层）
├── app/src/test/                        JVM 单测（含离屏渲染）
├── design/                              设计板 PNG 原图 + 生成脚本
├── insets/                              导航预留区 RRO 覆盖包源码
├── system-patch/                        system 变体补丁（hiddenapi 白名单等）
├── tools/                               构建、比对、发布、诊断脚本
├── worker/                              云端应用库上传端（Cloudflare Worker）
├── emu-verify/                          模拟器验证记录与截图
├── dist/                                交付产物（APK 不入库，走 GitHub Release）
├── version.properties                   versionCode / versionName（唯一来源）
└── CHANGELOG.md                         逐轮开发记录
```

---

## 许可

本仓库**暂未附带 LICENSE 文件**：在作者补充许可条款之前，请勿将本项目视为已授予
再分发或商用授权；二次开发或分发前请先与作者确认。

3D 车模来自领克官方线上展厅（hall.lynkco.com.cn）公开资产，仅供本 App 个人自用，
官方未声明授权，请勿商用再分发。
