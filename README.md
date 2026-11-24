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
| 场景页（首页） | 时间 / 天气（含海拔）/ 车辆卡片 / 歌词 / 导航引导卡 |
| 导航组合页 | 地图 + 播放器 + 车况，与高德联动 |
| 气候整页 | 全量空调控制的大屏形态 |
| 应用抽屉 | 全部可启动应用、图标缓存、应用禁用 / 启用 |
| 最近任务 | 车机侧任务栈 |
| 车辆信息页 | 俯视车模 + 车门 / 车窗 / 胎压 / 海拔实时状态 |
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
| 360 环影 | 车辆状态面板内嵌；按挡位 / 雷达图形信号自动开关并切视角（倒车、R→D、D 挡、N·P 关闭、雷达触发），视角延迟可调 |
| 海拔 | GPS / NMEA 采集，显示在车辆信息页与场景页天气下方 |
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
| 在线更新 | App 内检测 R2 上的更新清单，一键下载并安装；换更新通道后可直接切到该通道的包（跨通道不比版本号） |
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

第三百零九轮起出包分**两条更新通道**，App 里「设置 → 在线更新 → 更新渠道」选哪条就查哪条清单：

| 通道 | 命令 | versionName | 产物 | R2 位置 |
| --- | --- | --- | --- | --- |
| **开发版**（默认） | `bash tools/release.sh` | `1.0.1224-preview` | `dist/CarDesk-platform-ecarx-cardesk-4320-preview.apk` | `cardesk/preview/…`、`cardesk/preview/latest.json` |
| **正式版** | `bash tools/release.sh --stable` | `1.0.1224-system` | `dist/CarDesk-platform-ecarx-cardesk-4320.apk` | `cardesk/…`、`cardesk/latest.json` |

```bash
# 开发版（默认）：versionName 以 -preview 收尾，更新清单落 dist/cardesk-latest-preview.json
bash tools/release.sh --notes-file tools/notes-r197.md

# 正式版：versionName 保持 -system 后缀，更新清单落 dist/cardesk-latest.json
bash tools/release.sh --stable --notes-file tools/notes-r197.md
```

`tools/release.sh` 会自动：版本号 +1 → 干净目录全量构建 + 跑单测 →
校验包名 / versionCode / versionName（含通道后缀）/ **平台证书指纹** →
算 SHA-256 → 落 `dist/` → 重写对应通道的更新清单。

上传到 R2（凭据只从环境变量读；`--channel` 决定对象前缀与清单 key）：

```bash
export R2_AK=... R2_SK=...
# 开发版
python3 tools/publish_r2.py --bucket file --channel preview \
  --apk dist/CarDesk-platform-ecarx-cardesk-<versionCode>-preview.apk \
  --manifest dist/cardesk-latest-preview.json
# 正式版
python3 tools/publish_r2.py --bucket file --channel stable \
  --apk dist/CarDesk-platform-ecarx-cardesk-<versionCode>.apk \
  --manifest dist/cardesk-latest.json
```

### 发布状态

| 项 | 值 |
| --- | --- |
| 仓库当前版本 | `1.0.1224-preview`（versionCode **4320**，第三百零九轮 / 开发版） |
| 正式版通道 | `1.0.1219-system`（versionCode **4315**）—— `https://file.chuxi.bid/cardesk/latest.json` + `cardesk/CarDesk-platform-ecarx-cardesk-4315.apk`，未变 |
| 开发版通道 | `1.0.1224-preview`（versionCode **4320**）—— `https://file.chuxi.bid/cardesk/preview/latest.json` + `cardesk/preview/CarDesk-platform-ecarx-cardesk-4320-preview.apk`（SHA-256 `f320e80a…84f7fe`，回读校验一致） |
| GitHub Release | [`v1.0.1219`](https://github.com/Bigcaibird/cardesk/releases/tag/v1.0.1219) —— 最近一次 GitHub 发布（带 APK 附件）；车机在线更新读 R2 清单 |

> 第三百零九轮起出包分**开发版（默认）/ 正式版**两条通道，App 里「设置 → 在线更新 → 更新渠道」选哪条就查哪条清单；两条通道各自独立，互不打扰。
>
> 第三百一十四轮起：**切换通道后检查更新不再比较版本号**。两条分支的版本号各自递增，跨分支的数字大小没有意义（开发版可能已经 4326，正式版还在 4315），所以「跨通道」直接给可更新入口，下载后允许降级安装（`setAllowDowngrade`）。同通道内仍按 versionCode 判断要不要提示。

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
├── LICENSE                              许可条款（PolyForm Noncommercial + 署名要求）
└── CHANGELOG.md                         逐轮开发记录
```

---

## 许可

本仓库采用 **[PolyForm Noncommercial License 1.0.0](LICENSE)** + **附加署名要求**，
全文见 [`LICENSE`](LICENSE)。一句话：**只要不是商业用途，随便用、随便改、随便分发，
但要保留署名并声明来源。**

| 场景 | 是否允许 |
| --- | --- |
| 个人使用 / 学习 / 研究 / 改装自己的车机 / 非商业开源项目 | ✅ 免费，可任意使用、修改、分发 |
| 学校、公益、政府、公共研究机构等非商业组织 | ✅ 免费（`PolyForm Noncommercial` 明列） |
| 商业产品、商业服务、以营利为目的分发 | ❌ 需作者书面授权（[Issue](https://github.com/Bigcaibird/cardesk/issues) / 328576666@qq.com） |

**署名要求**：分发本软件或其衍生作品（源码或二进制）时，保留版权声明与
`Required Notice:` 行，并在文档（README / 关于页面 / 发布说明）里声明来源，例如：

```
本项目基于 CarDesk（https://github.com/Bigcaibird/cardesk）开发。
```

3D 车模来自领克官方线上展厅（hall.lynkco.com.cn）公开资产。
**安装包不内置车模图**：写实车模是独立资源包（`lynk01 / lynk03 / lynk05`，合计约 1MB），
启动时后台**一次性补齐**——本地已齐全（三个目录都有版本号）就完全不联网；
缺哪个就在后台把全部车型一次补齐，失败静默重试（5 分钟一次），网络恢复后自动续上。
下载完成前桌面显示自绘俯视车模，**不空窗、不影响任何交互**；
补齐或手动更新后自动失效解码缓存，**换车模不用重启桌面**。
设置页「车辆信息 → 车模资源包」可看已装版本并手动检查（手动检查绕过索引缓存、直接问远端）。
本项目自身已限定非商业用途，
**车模资产不得单独提取再分发或商用**，官方未声明授权。
