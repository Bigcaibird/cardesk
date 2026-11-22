# CarDesk 桌面 UI 重设计 —— 实施计划

> 事实源：`/Users/roy/Documents/desktopapp/cardesk-ui/design/redesign/`（45 张 1920×1080 画板）
> 加设计说明 `design/验收包.md`、`design/实施前终检.md`。
> 实施对象：`/Users/roy/Documents/desktopapp/cardesk`（Android 9 / API 28，Kotlin 自绘 View）。

## 0. 一句话

把「浮屿」那一版**不透明白卡 + 深壁纸**的桌面，换成设计稿的
**浅青 L0 底 + 毛玻璃分层（L1/L2/L3）+ 青绿强调色**，并把首页、导航页、抽屉、
空调、设置（25 页）、最近任务、弹层、日夜双模逐块对齐画板。

## 1. 令牌层（先做，一处改全局生效）

| 设计令牌 | 落点 | 值 |
|---|---|---|
| L0 底色 + 两处柔光 | `ui/widget/BackgroundView.kt` `drawDesignWash()` | 日 `bgTop #EEF2F5 → bgBottom #E7EEF3`，覆盖度 `WASH_DAY=0xF2`；右上青 `#00C2B2`、左下蓝 `#0E8FD8` 两处 RadialGradient |
| 夜间 L0 | 同上 | `#0B0F14`，`WASH_NIGHT=0xEC`，柔光 alpha 降到 0x21/0x1A |
| L1 卡 | `core/Palette.kt` `Palettes.day/night` | 日 `surface 0x9EFFFFFF`（62%）、`stroke 0x59FFFFFF`；夜 `surface 0xA8161B22`、`stroke 0x1FFFFFFF` |
| L2 浮层 | 同上 `surfaceStrong` | 日 `0xC7FFFFFF`（78%）、夜 `0xCC1B212A` |
| 强调色 | 同上 | 日 `#00A9A0`、夜 `#16C2B2`；ok `#2FBF71`、warn `#F5A623`、danger `#E5484D` |
| 圆角 | `core/Design.kt` `Shape.CARD` 28 → **24** | 卡片 24、浮层 28、按钮 18–20、胶囊 999 |
| 玻璃画法 | **新增 `core/Glass.kt`** | 预模糊背景（480×270）+ `BitmapShader` 取样 + 顶部 8%→0 内高光 + 1px rim + 4 圈柔阴影 |
| View 玻璃 | **新增 `core/GlassDrawable.kt`** | `Skin.flat()/glass()` 的返回类型换成它 —— 抽屉/空调/设置/最近任务/弹层一次性玻璃化 |

> **为什么不是真模糊**：API 28 没有 `RenderEffect`（API 31+）。这里用
> 「壁纸缩到 480×270 做三遍盒式模糊 → 每帧按卡片矩形取样」，
> 视觉上是毛玻璃，成本是一次性缩略图（`Glass.publish()`）。

## 2. 画板 → 代码映射

| 画板 | 落点 | 说明 |
|---|---|---|
| `Main.png` / `n01-home-night` | `ui/pages/ScenePage.kt` | 导航引导卡 620 宽 @ (44,73)、中央歌词 56/40/34（中心 Y=500）、天气 96px、车况卡 388×380 @ top 376、底部四卡 266/666/398/429 @ y803..953、Dock 8 段 |
| `02-nav-day` / `n02` | `ui/pages/NavPage.kt` | 左「请选择应用」白圆 + 右媒体卡（封面 126 r28、标题 34、进度条、白圆播放键 72） |
| `03-apps-day` | `ui/DrawerPage.kt` | 面板 1836×880 @ (44,67) r28、标题 28、全屏/小窗白胶囊分段、6 列 278×156 卡、图标底板 88×88 |
| `04-climate-day` / `n03` | `ui/ClimatePage.kt` | 玻璃浮层 (143,73) 1642×867、双温环 139.5/18、风量 7 段阶梯 62×(49+9i)、吹风模式/功能 chips、座舱出风块 |
| `05-vehicle-day` / `n04` | `ui/pages/VehiclePage.kt` | 续航 490×203、胎压 490×568、中列 782×786、右列快捷动作 6 行 |
| `06-door-alert-day` | 车门告警弹层 | 复用 `DoorAlert` + `DialogSheet` 玻璃材质 |
| `07-drivemode-day` | 驾驶模式弹层 | 复用弹层材质与行组件 |
| `08-split-day` / `09-pip-day` | `ui/pip/PipSurface.kt`、`LauncherView` 分屏 | 画中画承载面与分隔条按画板材质 |
| `10-recent-day` | `ui/RecentTasksPage.kt` | 标题 + 右上清空/关闭、任务卡 |
| `11-media-day` / `n05` | 媒体页 | 同上材质 |
| `12-nav-guide-day` | `ScenePage.drawGuide()` | 与首页引导卡同一套绘制 |
| `13-cam360-day` | 环影占位 | 只留画板里的文案与材质，**不弹**自定义 Toast（源码里没有） |
| `14-states-day` / `15-tokens-day` | `Palette.kt` / `Skin.kt` | 令牌表 |
| `s01..s25` | `ui/SettingsOverlay.kt` | 左 372 分组树 + 右 1476 内容区、两块 L2 玻璃面板、行 128、开关 64×36；**25 页共用一套壳层**，逐页只换内容 |
| `s06-daynight` | 日夜模式页 | 同步开关 + 三种来源卡 + 时间 + 立即切换/自定义主题色 |

## 3. 状态驱动（画板画的是「有数据态」）

- **导航引导卡**：只在导航 App 通过 `AmapNav` 上报转向数据时出现；无数据整卡隐藏（画板 `Main` 是有数据态）。
- **中央歌词**：只在有播放且拿到歌词窗口时出现；无播放时让位给壁纸（画板 `Main` 有歌词）。
- 这两条**不是缺失**，是数据状态不同；验收时用「有数据截图」与「空态截图」各一张说明。

## 4. 顺序与验收

1. 令牌层（Palette / Design / Glass / GlassDrawable / BackgroundView）→ 编译、装机、截首页。
2. 首页几何与四卡 → 逐卡对照 `Main.png` 裁切比对。
3. 状态栏 + Dock（8 段）→ 对照 `dock-crop` / `status-crop`。
4. 导航页、抽屉、空调、车辆、最近任务、弹层。
5. 设置壳层 + 25 页（子 agent 完成壳层与行组件，我统一编译）。
6. 夜间：把模拟器时钟设到 21:26（`adb root; adb shell date 112221262026.00`）触发时间同步，逐页截图。
7. 全量：`assembleSystem -PcardeskPackage=com.ecarx.cardesk --offline` → `adb install -r` →
   `cmd package set-home-activity` → 截图 + `logcat -b crash` + `dumpsys`。
8. 独立子 agent 逐条验收（45 画板 + 设计令牌 + 日夜双模 + 交互不回归）。

## 5. 风险与取舍

- **真实高斯模糊不可用**（API 28）：用预模糊缩略图 + 半透明 + 内高光近似，视觉一致、成本可控。
- **L0 与用户壁纸的关系**：画板 L0 是「近白渐变 + 两处柔光」，用户壁纸只作纹理。
  实现是「壁纸 → 95%（夜 93%）设计底色 + 柔光」的压层；壁纸仍可换、视频壁纸仍可播，
  只是不再主导画面 —— 与画板一致，也避免深色壁纸把玻璃读成灰。
- **全局 Dock 是独立窗口**：设置页要「没有 Dock」必须让它临时让位
  （`GlobalDockService.setSuppressed()`），桌面里 `dock.visibility=GONE` 管不到它。
- **领克车辆数据字段不全**：`VehicleInfo` 没有「电池电量 / 平均电耗」，车辆页这两项显示 `--`（不编造）。
- **VehiclePage 无入口**：画板有 `05-vehicle`，但桌面现有导航里没有「车辆」页入口 —— 保留页面与画板一致，不擅自加入口。
