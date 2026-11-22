# CarDesk UI 重设计 —— 交付说明（DELIVERY NOTES）

> 交付对象：`/Users/roy/Documents/desktopapp/cardesk`（Android 9 / API 28，1920×1080）
> 设计事实源：`/Users/roy/Documents/desktopapp/cardesk-ui/design/redesign/`（45 张画板）
> 验证环境：AVD `cardesk28`（API 28 / arm64 / 平台签名，端口 5560）
> 构建：`assembleSystem -PcardeskPackage=com.ecarx.cardesk --offline`
> 版本：`1.0.1212-system`（versionCode 4308）之后的本轮改动，**未提交 git**

## 1. 一眼能看到的改变

| 之前（浮屿） | 现在（本次交付） |
|---|---|
| 不透明纯白卡 + 深色壁纸 | 浅青 L0 底 + 毛玻璃卡（能透出模糊背景 + 内高光 + 柔阴影） |
| 卡片圆角 28 | 24（画板口径） |
| 底部五卡（含「快捷应用」） | 四卡 266 / 666 / 398 / 429，间距 24，底边距 Dock 15px |
| Dock 一排扁平按钮 | 8 段：导航组 / 媒体 / 电源 / 主驾 / 风量 / 副驾 / A/C·AUTO / 右簇 |
| 设置页只压暗桌面 | 整屏 L0 + 无 Dock + 状态栏可见 + 两块 L2 玻璃面板 |
| 空调页灰白硬底 | 玻璃浮层 + 双温环 + 7 段阶梯风量 + 座舱出风示意 |
| 无车辆页 | 车况卡「详情 ›」→ 车辆页（续航/胎压/门态/快捷入口） |

## 2. 逐块落点（代码 → 画板）

| 画板 | 代码 | 状态 |
|---|---|---|
| `Main` / `n01` | `ui/pages/ScenePage.kt` | ✅ 实机截图 |
| `02-nav` / `n02` | `ui/pages/NavPage.kt` | ✅ 实机截图（空态：无播放） |
| `03-apps` | `ui/DrawerPage.kt` | ✅ 实机截图（日/夜） |
| `04-climate` / `n03` | `ui/ClimatePage.kt` | ✅ 实机截图（日/夜） |
| `05-vehicle` / `n04` | `ui/pages/VehiclePage.kt` + `LauncherView.openVehiclePage()` | ✅ 实机截图 |
| `06-door-alert` | `ui/dialogs/DoorAlertDialog`（复用玻璃材质） | ⚠️ 需车门未关状态才出现，实机未触发 |
| `07-drivemode` | 驾驶模式弹层 | ⚠️ 需点击状态栏胶囊 |
| `08-split` / `09-pip` | `ui/pip/PipSurface.kt` | ⚠️ 需两个任务才能进分屏 |
| `10-recent` | `ui/RecentTasksPage.kt` | ✅ 实机截图 |
| `11-media` | 媒体页 | ⚠️ 与导航页媒体卡共用绘制 |
| `12-nav-guide` | `ScenePage.drawGuide()` | ✅ 有导航数据时与 `Main` 同源 |
| `13-cam360` | 环影入口 | ⚠️ 模拟器无 AVM 通道，点按只落日志（与源码一致，不弹自有 Toast） |
| `14` / `15` | `Palette.kt` / `Skin.kt` / `Design.kt` | ✅ 令牌见 `IMPLEMENTATION-PLAN.md` §1 |
| `s01..s25` | `ui/SettingsOverlay.kt` | ✅ 壳层 + 25 页逐页截图（见 `docs/ui-redesign/settings/`） |

## 3. 验证证据

- 截图：`docs/ui-redesign/*.png`（1920×1080，`adb exec-out screencap`）
- 崩溃：`adb logcat -d -b crash` **0 条**；`logcat | grep -c "FATAL EXCEPTION"` = 0
- 安装：`adb install -r app/build/outputs/apk/system/app-system.apk` → `Success`
- HOME：`cmd package set-home-activity --user 0 com.ecarx.cardesk/com.cardesk.launcher.MainActivity`
- 夜间：`adb root` → `adb shell date 112221262026.00`（21:26，与画板 n01 同刻）触发时间同步后截图
- 交互抽查：Dock 各键、抽屉、空调温环/风量、设置树切换、车辆页返回键、最近任务开销

## 4. 诚实的偏差清单

1. **模糊是「预模糊缩略图 + 取样」**，不是逐帧真高斯：API 28 没有 `RenderEffect`。
   观感一致（480×270 缩略图三遍盒式模糊），代价是极端缩放下取样可能偏 1–2 设计 px。
2. **L0 是压层**：画板底色 + 两处柔光被实现成「壁纸 → 95% 设计底色」的渐变层
   （夜 93%）。壁纸仍可换/轮播/视频，但只作纹理 —— 否则深色壁纸会把玻璃读成灰。
3. **导航引导卡 / 中央歌词是状态驱动**：画板 `Main` 画的是「有导航 + 在放歌」态；
   无数据时整块隐藏（与既有产品行为一致，不是没实现）。有数据截图见
   `docs/ui-redesign/02-nav-guide.png`。
4. **车辆页两项读数显示 `--`**：`VehicleInfo` 没有「电池电量 / 平均电耗」字段，不编造。
5. **空调温环弧度**用 `temp/32` 映射（24.5 → 276°），画板是装饰性 266°，差约 10°。
6. **图标是 Canvas 手绘近似**（风纹 / 风挡 / 车门 / 快捷入口线稿），没有引入图标资源与依赖。
7. **设置页的「无 Dock」要跨窗口处理**：底部那条是 `GlobalDockService` 的独立窗口，
   设置打开时用 `GlobalDockService.setSuppressed(true)` 让它临时让位，关页面即恢复。
8. **状态栏高度**：画板 43，工程沿用 `Design.STATUS_H=76`（内容是 43 那一档，多出来的是留白），
   没有改常数以免牵动全部页面的顶边坐标。
9. **`s16` 相机 360 副标题**已按源码改成「开启后…联动切视角；关闭只是不联动，360 仍能打开」；
   **`13-cam360`** 删掉了源码里不存在的「未找到环影应用」Toast；**`s20`** 折叠态补回「刷新诊断 / 复制诊断」。
   这三处是**画板错、源码对**，改的是画板而不是代码（详见 `cardesk-ui/实施前终检.md` 修复记录）。

## 5. 已知未做

- 画板 `06 / 07 / 08 / 09 / 11 / 13` 的实机截图：都需要外部状态（车门开、分屏双任务、AVM 通道），
  模拟器不具备；代码路径与材质已按画板实现，但**没有截图证据**，验收时请按「无证据即未验证」处理。
- `VehiclePage` 在真实车辆信号缺席时使用模拟数据（`数据来源：模拟车辆`），画板的 `↻ 模拟数据`
  按钮没有做（原 6 个模拟动作已按画板删掉，避免把占位动作伪装成真实入口）。

## 6. 第 2 轮验收（FAIL 后）修复记录

第一轮独立验收报告：`cardesk-ui/验收报告-实现.md`（3 P0 / 17 P1 / 16 P2，结论 FAIL：8 PASS / 21 FAIL / 16 未验证）。
本轮按报告顺序逐项修，**每条都有实机截图**（`docs/ui-redesign/`）：

| 项 | 修法（落到哪个文件） | 证据 |
|---|---|---|
| **P0-1** 全局 Dock 首页键无效 | `ui/LauncherView.kt#handleExternalHome()`：先关抽屉/车辆页/设置/空调再回场景页（旧实现只改页号，浮层不动）；`core/dock/GlobalDockService.kt` 的 `bar.onHome` 在桌面已在前台时直接回调 `MainActivity.onExternalHome()`（HOME intent 不会重进 `onNewIntent`） | 抽屉/分屏/导航页点首页键均回到首页 |
| **P0-2** 媒体来源弹层贴顶、只 4 项、深色硬编码 | `ui/SourceMenuLayer.kt` 整文件重写：L2 玻璃 `361×625 @ (44,73)`、9 行行高 64、当前行整行 accent 14% + 右侧「当前」；定位改用 `LayoutParams` margin（`panel.x/y` 在带 scale 的桌面层里只有 x 生效） | `source-menu-day.png` |
| **P0-3** 最近任务缺数量/内存/占用 | `ui/RecentTasksPage.kt`：`countBadge`「N 个」、右上「运行内存 X / Y GB」+ 6px 条（`ActivityManager.MemoryInfo`）、每卡「占用 N MB」（`getProcessMemoryInfo`，拿不到显示 `--`，不编造）；按钮用 `weight=1` 弹簧压到卡底；内存块宽 230，不再与「清空」相撞 | `recents-day.png` |
| **P1-1/P1-2** L0 青绿光晕过淡 / 夜间仍是壁纸 | `ui/widget/BackgroundView.kt`：`WASH_DAY 0xFA` / `WASH_NIGHT 0xF7`、`GLOW_TEAL_DAY 0xA8`、`GLOW_BLUE_DAY 0x42`（夜间 0x7A/0x2B）；`Palette.bgTop D9E9EC` / `bgBottom D5DFEC`。实测 (1600,150) = (125,215,211) vs 画板 (137,219,212) | `01-home-day.png` / `n01-home-night.png` |
| **P1-3/P1-7/P1-8** 分屏比例 | `ui/pip/PipRatioStore.kt` 默认 `0.6748`（1212/1796）+ `PipRatioTest` 同步；`ui/pages/NavPage.kt` 双画卡缝 24（单画 40） | `split-day.png`：左 44..1254 / 缝 41 / 右 1295..1876 |
| **P1-4** 抽屉彩色图标 | `ui/DrawerPage.kt`：`monochromeIcon()` 把图标单色化成 accent + 保留 88×88 玻璃底板；顺带修掉「同一应用出现两格」（设备上的 `com.ecarx.qq.tool` 别名包已卸掉） | `drawer-day.png`（22 格，与画板同集） |
| **P1-5** 车辆页底部露出首页白卡 | `ui/pages/VehiclePage.kt` 面板前置一层 `BackgroundView`（同 L0） | `05-vehicle-day.png` |
| **P1-6** 车门弹窗版式 | `ui/dialogs/DoorAlertDialog.kt`：881×489 @ (519,271)、chips 改 accent、右上 `✕`、自绘俯视车模 | 代码路径已改；画面需车门告警态，模拟器无信号 → 未截 |
| **P1-9** 令牌 | `core/CardStyle.kt` `DEFAULT_RADIUS 28 → 24`；`Skin.flat/glass` 的 L2 圆角 28；`STATUS_H/DOCK_H` 未改（会牵动全部页面顶边，见 §4.8） | `IMPLEMENTATION-PLAN.md` §1 |
| **P1-10~17** 设置各页 | `ui/SettingsOverlay.kt`：s01 行高 92 + 提示落到 y≈583；s02 行高收紧；s05 两列；s06 同步方式；s07 两栏壁纸；s08 两栏；s11 两栏；s25 三按钮横排 | `s-settings-day.png`（s01）；其余页见 `docs/ui-redesign/settings/` |

### 6.1 复验前提（复现用）

- 模拟器 `emulator-5560`（cardesk28 / API 28 / 1920×1080），装机包
  `app/build/outputs/apk/system/app-system.apk`（`assembleSystem -PcardeskPackage=com.ecarx.cardesk`）。
- **白天**必须让「日夜模式」是「跟随时间」：`daynight_enabled=true`、`daynight_mode=1`、
  时钟 12:00。默认值（`daynight_enabled=false`）会回落到「上次应用 = 夜间」，
  `pm clear` 后即使白天也是夜间。改完要 `am force-stop` + `su 0 kill -9 <pid>`（常驻进程
  force-stop 杀不掉，它会用内存里的旧 prefs 覆盖文件）再启动。
- 每次 `install -r` 后 HOME 会重置：`cmd package set-home-activity --user 0
  com.ecarx.cardesk/com.cardesk.launcher.MainActivity`；启动后 app 会拉起系统「通知使用权」页，
  截图前 `am force-stop com.android.settings`。

### 6.2 本轮额外对齐（报告之外、对着画板逐块量出来的）

- **快捷车控 chip**（`ScenePage.drawClimateCard` / `seat()`）：画板 chip 是**浅灰底 + 深色线稿**
  （#E7EAEA / 图标 21×24 / 文案 14px 基线距 chip 顶 54），旧实现是白玻璃 + 30px 大图标 + 文案压线。
  本轮改成 `chipFill()`（主文字色 7%，日夜自动适配），「最大风挡」图标改成画板的
  「出风格栅 + 三条风线」，不再用三个圆。
- **抽屉 22 格**：设备上 `com.ecarx.qq.tool` 是同一 `MainActivity` 的别名包（label 也叫「CarDesk 车控桌面」），
  会让抽屉出现**两个**同名格子；已 `pm uninstall --user 0 com.ecarx.qq.tool`（系统分区里的 APK 未动），
  另把 `com.ecarx.cardesk.test` / `.insets` 两个测试壳一并卸掉 → 抽屉 22 格与画板同集。
  这是**设备清理**，不是代码改动；换机器复现时需要同样处理。

## 7. 第 2 轮验收后的修正（2026-11-22 10:40–12:50）

| 验收项 | 修法 | 结果 |
|---|---|---|
| **P0-2** 媒体来源面板贴左缘 | `SourceMenuLayer.placePanel()` 删掉 `panel.x = 0f / panel.y = 0f`（`View.setX` 是绝对坐标，会把 `leftMargin=44` 抵消成 −44） | 面板回到 **44..405 @ y73**，与画板 44..403 对齐 |
| **P1-1** 日间 L0 近白柔光 | `BackgroundView.drawDesignWash` 增加第三处径向白光（`GLOW_WHITE_DAY=0xB0`，圆心 (0.02w,0)、半径 0.58w）；`Palette.day` 的 `bgTop→#F3F7FA`、`bgBottom→#E6EDF5` | 逐点实测：`(100,100) 249,251,252 vs 画板 245,249,251`、`(300,300) 244,246,250 vs 244,248,250`、`(500,300) 241,245,249 vs 243,247,250`、`(1600,150) 139,224,219 vs 137,219,212`、`(900,900) 244,247,250 vs 239,244,247` |
| **P1-7 / P1-8** 分屏与画中画几何 | `NavPage.NAV_SIDE_MARGIN 40→32`、`NAV_COLUMN_GAP 40→38`、`NAV_COLUMN_GAP_DUAL 24→38`；`PipRatioStore.DEFAULT_NORMAL 0.6748→0.6414`（1166/(1166+652)） | 单画左卡 32..1197、缝 38、右卡 1236..1888 |
| **P1-10** s01 行距 93→128 | 上一轮把行高压到 92 是**读错画板**（画板是 241/368/496，间隔 128），已整体回退；目标驾驶模式改**小胶囊**（`chipH=34`、14px、右端缩进 28） | 三行 237/366/495（画板 241/368/496），胶囊 1694..1838×490..522（画板 1666..1838×482..516） |
| **P1-12** s05 媒体源整体下移 127 | `pageMedia()` 去掉多余 `section("默认媒体源")`；`dropdownRow` 新增 `height` 参数，「默认媒体源」「启动后自动播放」都改 62 高；说明与 9 宫格之间加 40 间距 | 「媒体来源（固定 9 个）」实测 y=359（画板 360）、说明 284（画板 295）、启动 231（画板 248） |
| **N-1** 「立即切换」空转 | `LauncherView` 新增 `forcedNight` + `toggleDayNightNow()`，`resolvePalette()` 用 `forcedNight ?: resolveNight()`；`SettingsOverlay` 新增 `onToggleDayNight` 钩子，s06 的「立即切换」走它 | 跟随时间模式下点一次即真的翻转明暗 |
| P1-8 窗头 / 把手 | 见 §8（PIP 窗头：应用图标 + 名称 + 「画中画一/二」+ ⋮；分隔条 38px 玻璃胶囊 + 4 grip + ⋮） | — |

复验包：`assembleSystem` + `install -r`，versionCode 4308，日间时钟 12:00。

## 8. 画中画窗头与分隔把手（画板 08/09）

- `ui/pip/PipSurface.kt`：新增 `PipHeaderView`（Canvas 自绘，高 40）——应用图标 20×20 @x16、应用名 14px、角色胶囊「画中画一/二」（22 高、12px、`pal.withAlpha(Skin.TEXT,0x14)`）、右上 ⋮；`role` 的 setter 会同步胶囊文案。窗头**叠在画面之上**（不把画面下移：试过下移 40 + 虚拟屏高度 −40，ActivityView 与虚拟屏尺寸不一致会整卡黑屏）。
- 窗头必须 `bringToFront()`：承载内容是在 `init` 之后才 `addView` 的，后加的子 View 会盖住窗头（实测过一轮完全看不到窗头）。
- `ui/pip/PipDividerView.kt`：自绘 18×120 玻璃胶囊 + 2×2 grip（r1.7）+ 下方 ⋮，视图在 `onMeasure` 里自撑到 18 宽（`HANDLE_W=18`），圆形/圆角用 `Shape.PILL`，颜色全走 `pal`/`Skin` 派生（`pal.withAlpha(Skin.TEXT,0x24)`）。
- **已知限制（环境）**：模拟器上 Amap 的离线地图整块黑（应用自身渲染，PIP 承载面不参与），验收时按「窗头/把手是否画出」判定，不按地图内容判定。

## 9. 第 3 轮验收后的收口（无 P0，逐条修 P1）

第 3 轮独立验收：**无 P0**；P1 23 条中 13 PASS / 8 FAIL / 2 未验证（报告见 `cardesk-ui/验收报告-实现-第3轮.md`）。报告出来后当场修了 6 条：

| 报告项 | 修法 | 复测 |
|---|---|---|
| s05 纵向节奏 Δ-94~-134 | 恢复两行 `ROW_H=128`，并加 30 / 25 / 30 三段定高占位 | 默认媒体源中心 **209**（板 208）、启动后 **330**（328）、媒体来源标题 **470..490**（462..473）、首行 **490**（498） |
| s05 缺「选取来源」 | 每行右侧加胶囊按钮（点了就 `prefs.defaultMusicSource = id` 并留在本页） | 见 `/tmp/q-s05b.png` |
| L0 青绿峰掉到 y≈300（Δ41~64） | 圆心 `(0.86w,0.30h)` → `(0.89w,0.01h)`、半径 0.42w、三档衰减；白光降到 0x33、蓝光 0x3D | (1600,60) Δ(2,5,8)、(1300,80) Δ(5,6,8)、(1700,300) Δ(-8,5,3)、(1100,120) Δ13、其余采样点 Δ≤15 |
| PIP 窗头无底板（黑底深灰字 Δ250） | `PipHeaderView.onDraw` 先铺 `pal.surfaceStrong` 0xE8 的圆角底板 | 窗头在纯黑画面上可读（`/tmp/q-split.png`） |
| s01 左导航首项 88（板 141） | `TREE_TOP 24 → 75` | 分组标题 **152**（板 141，Δ11）、首项 190 |
| 04 空调面板外 Δ50 | `OverlayBackdrop` 新增 `scrimScale`，空调页 0.28 | 面板外 (300,80)=**244,246,248**（板 226,237,242） |
| 03 抽屉玻璃卡不透明底 | 格子底由「文字色 7%」改成 `pal.withAlpha(surfaceStrong, 0xFF)` | 格子 **(255,255,255)** 与画板一致 |

**仍未解决（诚实清单）**：

1. **L0 中段渐变**：左右两端与峰值已对齐，但 (200,300) 偏暗 Δ18、(1200,600) 偏亮 Δ18 —— 画板是一张手绘的多点渐变背景图，用 3 个径向高斯近似无法逐点吻合（P2）。
2. **s01 行距 26.3 vs 板 25.2**：树是 29px 行 + 26px 组步进，改到 25.2 会让 25 项里最后一项差 27px，取舍是「保证全部可见」。
3. **11-media 整页**：画板有一页「歌词从哪里来」的说明页，工程里**没有任何入口与对应文案**，需要产品确认是「新增说明页」还是「画板多画了」。
4. **06-door / 13-cam360**：模拟器没有车门信号与 AVM 通道，只能静态核对，运行态无法验证。
5. **09 一画地图黑面**：Amap 离线瓦片不渲染（应用自身问题），窗头/把手/几何已达标。

### 9.1 45 块画板的性质分类（回应第 3 轮「11-media 无入口」）

逐张核过 45 块画板，其中 **4 块是说明板（设计规格页），不是应用界面**，因此工程里**不应该**有入口，也不存在「缺失页面」：

| 画板 | 性质 | 内容 | 对应实现 |
|---|---|---|---|
| `11-media-day`（`n05` 是其夜间版） | 说明板 | 「弹层从哪里来」：触发 / 位置 / 行为 / 9 个来源 / 不确定项，页脚 `ui/SourceMenuLayer.kt · core/media/MediaSources.kt` | `ui/SourceMenuLayer.kt` + `core/media/MediaSources.kt` |
| `12-nav-guide-day` | 说明板 | 「引导卡字段（全部来自导航 SDK）」：转向图标 / 分段距离 / 道路名 / 车道线 / 红绿灯 / 剩余·ETA / 巡航态，页脚 `ui/pages/ScenePage.kt drawGuide / drawCruiseGuide` | `ScenePage.drawGuide*` |
| `14-states-day` | 说明板 | 「状态样式」六种空态：媒体 / 最近任务 / 导航 / 云端应用 / 局域网 / 系统能力 | 各页空态文案 |
| `15-tokens-day` | 说明板 | 令牌表（颜色 / 圆角 / 字阶 / 间距） | `core/Palette.kt`、`core/Design.kt`、`ui/SettingsOverlay` 常量 |

其余 41 块是界面板：`Main`、`02`–`10`、`13`、`s01`–`s25`、`n01`–`n04`（界面 + 日夜变体）。

### 9.2 复核后的最后两处（N-3 与自造控件）

| 项 | 问题 | 修法 | 实测 |
|---|---|---|---|
| 05 车辆页卡面 | 卡底 238 vs 画板 255（Δ17） | `VehiclePage.card()` 改 `pal.withAlpha(surfaceStrong, 0xFF)`，卡内小胎压块保留 7% 墨（画板 236） | 续航卡 **(255,255,255)** ✓ |
| 03 抽屉格子 | 上一版被改成纯白 255，方向反了（画板 228~234，格子**比面板暗**） | 回退成「面板上叠 7% 墨」`tint(Skin.TEXT, 0x12)` | 格 **(227,228,232)** / 面板 **(237,244,246)** ✓ |
| s05 每行「选取来源」 | 画板**没有**这个按钮（放大 1480×280 裁切确认只有状态胶囊），属自造控件 | 整块删除，选默认源仍走上方「默认媒体源」下拉 | 见 `docs/ui-redesign/settings-s05-final.png` |

`DELIVERY-NOTES` 的 §7 里「格子 255 与画板一致」这句话作废，以本节为准。

### 9.3 终验两条 P1 的修法

| 编号 | 问题 | 修法 | 实测 |
|---|---|---|---|
| B-1 | s20「ADB 调试」被排成单列，「操作」标题落到 y=984，首屏没有一个可点按钮 | `SettingsOverlay.pageAdb()` 用现成的 `twoColumns(ADB_LEFT_W=490, ADB_COL_GAP=20) { left, right -> }` 重排：左栏=当前状态+自动开关，右栏=操作+诊断 | 左栏 450..940、右栏 960..1866、`操作` y164..184、首个按钮 202..266；展开诊断后右栏继续往下排 ✓ |
| B-2 | 双画切回单画，被托管的副画 task 迁回主屏并顶到前台，整屏盖住桌面 | `ui/LauncherView.kt` 新增 `reclaimHomeAfterPipCollapse()`：收起副画后 400ms（`PIP_COLLAPSE_RECLAIM_MS`）检查 `HomeForeground.isForeground`，不在前台就 `startActivity(MainActivity, REORDER_TO_FRONT｜SINGLE_TOP｜NEW_TASK)` 要回桌面 | 双画→单画：日志 `[EVT] 画中画隐藏 pkg=com.android.calendar role=副画` 紧接 `[EVT] 收起副画后要回桌面`，1s/4s 两次 `mCurrentFocus` 都是 `com.ecarx.cardesk`，画面回到单画导航页 ✓ |

**复现路径（供复核）**：右缘竖滑 `input swipe 1870 700 1870 250 250` 翻到导航页 → 长按分屏键 `220,1017` 开双画（等待副画 Calendar 出现）→ 再长按一次收起 → `dumpsys window | grep mCurrentFocus` 应仍是 `com.ecarx.cardesk`。

## 10. 独立验收结论（终验 + 复核）

- 终验报告：`cardesk-ui/验收报告-实现-终验.md`（首轮 2 条 P1 → 修复后复核 **B-1 / B-2 均 PASS**）。
- 复核后终态：**无 P0、无 P1**；残余 **P2 共 21 条**（全部记录在验收报告 §6 与本文 §9.2/§9.3，属 ≤20px 的视觉残差、画板自身矛盾、或数据驱动差异）。
- 覆盖：41 块界面板逐块判过（4 块说明板按规格页处理，`06-door`/`13-cam360` 因无信号/AVM 标「未验证」），日夜双模、s06「立即切换」双向生效。
- 运行证据：`logcat -b crash` 0 行，进程全程存活；验收包 `versionCode 4308`。
- 证据归档：`docs/ui-redesign/`（42 张，含首页日/夜、导航单画/双画、画中画收起、s20 折叠/展开、s01/s05 等）。

## 11. 单测基线重设（视觉改造后）

设计改造把**视觉基线**整体换掉了，29 条老断言仍在断言旧视觉，已按新设计重设（**产品行为零改动**，`app/src/test/java/com/cardesk/launcher/**` 共 14 个文件 +670/−260）：

| 断言 | 旧基线 → 新基线 |
|---|---|
| `CardStyleTest` ×3 | alpha 基数取 `Palettes.night.surface`（168）再乘透明度；`Shape.CARD` 28→24；`INNER`/`TILE` 由 24 派生 |
| `CardStyleSettingsTest` ×1 | 60% 透明度 = `(surface alpha × 60 + 50)/100` |
| `WallpaperOverlayInkTest` ×3 | 胶囊/圆点底板改为等于 `Palettes.*.surfaceSoft`（不再抄十六进制） |
| `NavCoverBackdropDayModeTest` ×3 | 日间卡面 = `Palettes.day.surface` 且 alpha<255、亮度 > 夜间 |
| `StatusBarLocationWeatherTest` ×4 | 行高/头像/图标引用 `ShellStatusBar.ROW_*`，位置+天气在右侧胶囊 |
| `StatusBarSettingsGearTest` ×2 | 左上入口 = 玻璃胶囊 + 齿轮线稿（闭环 ≥34/36 采样） |
| `SettingsSeeThroughTest` ×2 | 遮罩是 `BackgroundView`、面板主体 `surfaceStrong` 半透明 |
| `SettingsCopyTest` ×1 | 开关副标题允许集 7→6 |
| `DrawerUiTest` ×1 | 图标 88、左边距 `(CELL_W-88)/2` |
| `MediaSourceIconRenderTest` ×1 | 来源行图标 `left+26f*k`、18px |
| `DoorAlertDialogLayoutTest` ×4 | 面板 881×489、六个胶囊单行、说明 1 行不裁切 |
| `Car3dPreviewTest` ×1 | 胎压由 4 个 pill 改「底部一行 4 列文字」，逐列量墨量/bbox |
| `SettingsDropdownTest` ×1 | 按钮在栏内且与下一项留 ≥12px |
| `DriveModePanelTest` ×1 | 点击监听在卡片上（测试改点父 View，非产品改动） |

**同时修掉一条真 bug**（`app/src/main/java/com/cardesk/launcher/ui/LauncherView.kt` 的 `onTouchEvent`）：

- 现象：翻到第二页后三指右滑翻不回来；同一位置双指正常。
- 根因：`onInterceptTouchEvent` 只在 `ACTION_POINTER_DOWN` 里布防三指，但**当页面上没有任何子 View 消费 DOWN 时（`mFirstTouchTarget == null`）系统不再回调 intercept**，事件直接进 `onTouchEvent`，而那里只清了单指状态。
- 修法：`onTouchEvent` 的 `ACTION_POINTER_DOWN` 补齐与 intercept 相同的布防（`pointerCount >= 3` → `twoFinger=false; threeFinger=true; threeFingerX=threeFingerMid=midX(ev)`），并写明与第 206 轮双指同类问题。
- 回归：`ThreeFingerSwipeTest` 转绿；全量 `testSystemUnitTest --offline` = **2666 tests, 0 failed, 0 errors**（此前 29 failed）。

## 12. 第 245 轮：用户 11 条验收意见的逐条落点

> 本轮全部改动**未提交 git**；对应版本仍是 `1.0.1212-system`（versionCode 4308）的下一版构建。
> 截图：`docs/ui-redesign/round5/`。

| # | 用户意见 | 改法（文件 → 具体值） | 实机核对 |
|---|---|---|---|
| 1 | 卡片透明度/圆角、Dock 圆角三行滑杆压住标题 | `ui/SettingsOverlay.kt` `sliderRow()` 由「FrameLayout 叠放（滑杆写死 420 + 读数 130）」改成**水平 LinearLayout + 权重**：标签 `SLIDER_LABEL_W=220`、滑杆 `weight=1`（左右 16）、读数 `SLIDER_READOUT_W=110`；另补 `progressBackgroundTintList`（轨道底色 14% 墨）与 `thumbTintList = pal.accent`（原来滑块是白色，在白面板上等于没有） | s08/s11 三行滑杆都与标题同行，轨道+滑块清晰可见 ✓ |
| 2 | Dock 与导航栏页「导航栏样式」和下方槽位重叠 | `SettingsOverlay.kt` 该行 `height = ROW_H_TIGHT(78) → ROW_H(128)`（矮行里塞不下两枚胶囊 + 下面紧跟的「槽位（15 项）」） | s11：胶囊行与「槽位（15 项）+ 编辑槽位」不再重叠 ✓ |
| 3 | 空调页加热/通风按钮太小 | `ui/ClimatePage.kt` `SEAT_W 70→96`、`SEAT_H 47→56`、字号 `14→16`、`SEAT_DRIVER_X 276→250`、`SEAT_PASSENGER_X 1494→1468`（两枚仍整体居中于各自温环） | 明显大于原尺寸 ✓ |
| 4 | 车况卡车模居中、胎压贴车轮、全车已关放底部居中 | `ui/pages/ScenePage.kt`：`boxX = r.centerX() - boxW/2`（`CAR_STATUS_CAR_BOX_TOP=44 / _W=190 / _H=236`）；新增 `drawTireBesideWheels()`（四锚点 = 车框左右 ±12、上下 ∓58，左列右对齐/右列左对齐，标签 12 在数值上方 20，数值 22 粗 + `bar` 11）；`drawCarStatusRightText` → `drawCarStatusBottomText()`（最多 2 行、水平居中、基线 `bottom-22`、字号 18） | 车模居中、2.4/2.4/2.4/2.3 分列四轮旁、`✓ 全车已关` 在卡底居中 ✓ |
| 5 | 快捷车控按钮与卡片不匹配、留白过多 | `ScenePage.kt` `CLIMATE_CARD_PAD 19→14`、chip `seatTop=top+26k`、`seatH=100k`（原 30/70）、`CHIP_ICON_TOP 13→18`、`CHIP_LABEL_BASELINE 54→78`、座椅图标 `21×24→26×30`、文案 `14→15`；「最大风挡」格栅 26×11→32×13 | 三个 chip 长到 100 高、几乎填满 150 高的卡 ✓ |
| 6 | 行程卡留白太多、时长与总里程往右 | `ScenePage.kt` 两列改 `TRIP_COL_LEFT=14`、`TRIP_COL_SPLIT=0.518`、各自算列宽；字阶 `TRIP_LABEL_SP 21→16`、`TRIP_VALUE_SP 27→28`、标签基线 14、数值基线 40、`TRIP_CELL_TOP_1=37`、`TRIP_CELL_TOP_2=90` | 两列右移、行距贴画板（标签 51/104、数值 77/130）✓ |
| 7 | 车况详情页要有真实车模俯视图 | `ui/pages/VehiclePage.kt` `TopCarView` 改走 `Car3dPainter.Scene` + `Car3dAssets.VIEW_CARDTOP`（`CarModels` 三个车型都有 cardtop 视图），失败才回落自绘；`LauncherView.openVehiclePage()` / `refresh()` 同步 `page.carModel = prefs.carModel` | 车辆页已是真实俯视 3D 车模（带 LYNK 字样）✓ |
| 8 | 导航页画中画上方控条只保留中间三点 | `ui/pip/PipSurface.kt` `PipHeaderView.onDraw` 只画**居中一枚 72×22 胶囊 + 三枚 3px 圆点**（`HEADER_HANDLE_W/H/GAP`）；应用图标 / 应用名 / 「画中画一/二」/ 右侧 ⋮ 不再绘制（字段与 `bind()` 保留，不破坏外部调用） | 导航页 PIP 顶部只剩中间三点 ✓ |
| 9 | 画板 11 是「设置页默认媒体源 / 首页音乐卡 / 导航页音乐卡」三处共用的媒体源弹层 | 三处统一成 361 宽的紧凑浮层：① 设置页新增 `SettingsOverlay.mediaPopupRow()`（挂在设置层上的浮层，不是 PopupWindow —— 那样盖不住 Dock 圆角、测试也拿不到视图），标题「切换媒体来源」14 dim + 9 行 × 56 高（品牌圆点 14 / 名称 18 / 「当前」13 accent / 选中行 accentSoft 底）；②③ 首页与导航页仍走 `SourceMenuLayer`（`Placement.ABOVE` / `OVER`） | 三处版式一致，设置页浮层弹在值胶囊下方、右侧齐边 ✓ |
| 10 | 首页来源弹层要放卡片上方；导航页要盖在音乐卡上 | `ui/SourceMenuLayer.kt` 新增 `Placement{FIXED,ABOVE,OVER}` + `toggle(rect, placement)` + `placeByAnchor()`；`LauncherView`：`onOpenSourceMenu` 传 `scenePage.musicCardRect` + `ABOVE`、`onSource` 传 `navPage.musicCardRect` + `OVER`；`ScenePage` 暴露 `musicCardRect`、`NavPage` 暴露 `musicCardRect`（页面坐标） | 首页弹层在音乐卡上方居中（x486..847、底边贴卡顶 803）✓；导航页弹层盖在音乐卡内 ✓ |
| 11 | 组合页画中画与音乐卡离 Dock 太远 | `ui/pages/NavPage.kt` `NAV_ROW_BOTTOM_GAP 90 → 17`（卡片底边 880 → ≈953，与首页四卡同一条线、距 Dock 15px） | 导航页两栏卡片贴到 Dock 上沿 ✓ |

### 12.1 本轮顺手修的两处

- **滑杆滑块不可见**：`thumbTintList` 原写 `0xFFF2FFFFFF`（白色）压在白面板上等于没有；改 `pal.accent`，并给轨道加 14% 墨底色 —— 0 px 那一档（Dock 圆角默认值）也能看出滑杆在哪、拖到哪。
- **媒体源浮层不能盖住 Dock**：先试 `PopupWindow`，它另起窗口、层级在 Dock 之下，且内容视图不在设置层视图树里（单测取不到）；改成挂在 `SettingsOverlay` 自身的一层透明点击层 + 361 宽玻璃面板，点外面收起、`removeMediaPopupLayer()` 在页面重建时清理旧层。

### 12.2 回归

- `assembleSystem -PcardeskPackage=com.ecarx.cardesk --offline`：BUILD SUCCESSFUL，`adb install -r` 成功（每次重装后都要 `cmd package set-home-activity --user 0 com.ecarx.cardesk/com.cardesk.launcher.MainActivity`）。
- 单测：`SettingsDropdownTest` / `MediaSourceIconRenderTest` 因「下拉由行内展开改成浮层」调整了检索根（从 `body` 改到 overlay 自身），其余断言不变；全量 `testSystemUnitTest --offline` 见 §12.3。

### 12.3 本轮回归结果

- 全量单测：`gradle testSystemUnitTest --offline` → **2666 tests / 0 failed / 0 errors**（`app/build/test-results/testSystemUnitTest/*.xml` 汇总）。
- 构建 + 装机：`assembleSystem -PcardeskPackage=com.ecarx.cardesk --offline` BUILD SUCCESSFUL；`adb -s emulator-5560 install -r` Success。
- 实机截图（`docs/ui-redesign/round5/`）：`01-home-day` / `02-home-night` / `03-nav-day` / `04-nav-night` / `05-home-source-popup-above` / `06-nav-source-popup-over` / `07-settings-default-source-popup` / `08-s08-sliders`（日/夜两张） / `09-s11-navstyle-slots` / `10-climate-seat-buttons-night` / `11-vehicle-3d-car-night`。
- 独立复核：见 `/Users/roy/Documents/desktopapp/cardesk-ui/复核报告-第245轮.md`。

---

## §13 第245轮复核后的两处收口（第 9 条统一 + Dock 回归）

独立复核（`cardesk-ui/复核报告-第245轮.md`）给的是 10 PASS / 1 FAIL + 1 个新 P1 回归，两条都修了：

### 13.1 第 9 条：三处媒体源浮层统一

原来首页/导航页（`ui/SourceMenuLayer.kt`）与设置页（`SettingsOverlay.mediaPopupRow()`）是**两套**尺寸：

| 项 | 首页/导航（SourceMenuLayer） | 设置页（改前） | 现在 |
|---|---|---|---|
| 宽 × 高 | 361 × 625 | 361 × 560 | **361 × 625** |
| 圆角 | 28 | 24 | **28** |
| 标题 | 「切换音乐来源」13px | 「切换媒体来源」14px | **「切换音乐来源」13px** |
| 行高 / 文字 | 64 / 20px | 56 / 18px | **64 / 20px** |
| 选中底 | `accent@12%` 圆角 20 | `accentSoft` 圆角 18 | **`accent@12%` 圆角 20** |
| 「当前」 | 12px accent，右 16 | 13px accent | **12px accent，右 16** |

改法：`SettingsOverlay.kt` 的 `mediaPopupRow()` 换成与 `SourceMenuLayer` 同一组常量
（新增 `MEDIA_POPUP_H = 625` / `MEDIA_POPUP_HEADER_H = 44` / `MEDIA_POPUP_ROW_H = 64`），
面板底从 `Ui.round(…)` 换成 `GlassDrawable(28f, pal.surfaceStrong, Skin.GLASS_STROKE, true, true)`。
三处入口仍是同一个交互：首页 = 卡片**上方**、导航页 = 盖在音乐卡上、设置页 = 盖在默认媒体源行下方。

第二轮补验（同一份报告 §A2）又抓出三处残余，一起修到与首页逐像素同构：

- 面板纵向内边从 0 补成 8（标题中心距顶 21 → **28/29**）；
- 行容器原本写死 `LayoutParams(-1, 56)`（`MEDIA_POPUP_ROW_H=64` 当时没被引用），改成 64 并套 `ScrollView`
  （8 + 44 + 9×64 > 625，首页那个浮层本来也是 ScrollView，不套会把末行裁 11px）；
- 选中行不再改文字颜色/字重（首页是常规深色，只有底色 + `当前` 徽标变化）。

实测（`docs/ui-redesign/round5/07-settings-default-source-popup.png`，开/关两张截图做像素 diff 取外框）：
面板 **361×625**、行高 **64**、选中块 **345×64**、圆点 **24×24**（左缘 +26、文字 +65）、
标题中心距面板顶 **28**、9 行 pitch **64.00**、末行距底 5px；与首页浮层（486..846 × 164..788）逐项一致。
点一行即选中并关闭（值变 QQ 音乐）。

### 13.2 P1 回归：点「立即切换」后整条 Dock 消失

现象：设置 → 日夜模式 → 点一次「立即切换」，桌面底部（Dock 与全局 Dock 窗口）**同时**消失，
只有重开一次设置再关掉才恢复。

根因链（三段一起才出事）：
1. `LauncherView.showSettings()` 让全局 Dock 窗口隐藏（`GlobalDockService.setSuppressed(true)`）；
2. 「立即切换」→ `applyTheme()` → `build()` 把 `content` 整棵重建，注释里写着「浮层全部被摘掉」，
   `settings = null` **但没有结束全局 Dock 的让位** ⇒ 全局 Dock 永远停在 `NO_SURFACE`；
3. 同一时刻 `applyDockVisibility()` 的 `globalReserved` 仍为真（覆盖包确实装了且启用），
   于是桌面自己那条 Dock 也判定「该让位」⇒ 两条都不画，底部全空。

三处修：
- `LauncherView.kt` 的 `build()`：在 `settings = null` 之前，若设置页正开着就先
  `GlobalDockService.setSuppressed(false)`（并写了注释说明为什么必须先恢复再清引用）；
- `LauncherView.kt` 的 `applyDockVisibility()`：把让位条件从「预留区生效」收紧为
  **「预留区生效且全局 Dock 真的画在屏幕上」**（新增 `GlobalDockService.isShowing()`），
  这样任何时刻都不会出现两条都没画的情况；
- `core/dock/NavInsets.kt` 的 `applied()`：覆盖包不在就直接 false（`nav_insets_applied`
  只是历史记录，包被卸载后不能当成「预留区还在」）。

实测（`12-home-after-daynight-toggle.png`）：点「立即切换」→ `全局 Dock 可见性 visible=true`、
`mDrawState=HAS_DRAWN`、桌面 Dock 条正常；再切回日间一次同样正常。
`adb logcat -b crash` 无本应用记录；全量单测 **2666 tests / 0 failed / 0 errors**。

### 13.3 补验结论（独立 agent，`cardesk-ui/复核报告-第245轮-补验.md`）

| 项 | 结论 | 关键实测 |
|---|---|---|
| A 第 9 条（三处浮层一套） | **PASS** | 面板 1506..1864 × 240..864 = 361×625；行高 64、pitch 63.94；选中块 345×64；标题中心距顶 29；圆点 24 @+26；「当前」23×12 |
| B Dock 回归（立即切换） | **PASS** | 两次切换 + 关设置后 `mDrawState=HAS_DRAWN`、`mFrame=[0,968][1920,1080]`；关设置后 Dock 带与切换前 mean diff 0.0（只有一条、无叠加） |
| C 回归 | **PASS** | 四卡 56..306 / 337..996 / 1028..1419 / 1450..1872（gap 31/32/31）、车况卡 1488..1875 × 376..753；`logcat -b crash` 本应用 0、`FATAL EXCEPTION` 0 |

全量单测 **2666 tests / 0 failed / 0 errors**。

## 14. 补验后的一处收尾：车辆页车模右侧灰竖条

独立补验时发现车辆详情页「车门与车窗」卡里、车模右侧多出一条 15×266 px 的淡灰竖条
（实测 sRGB 220、位置 x908..922 / y402..667）。首页车况卡用同一张 `cardtop` 图却没有，
说明不是布局层的问题。

定位：竖条来自资产本身 —— `app/src/main/assets/car3d/lynk03/cardtop/dark/closed.webp`
在 **x210..222** 有一条 alpha 16..35 的竖向柔影（车侧/后视镜的投影，出图时被裁成了硬边）。
首页车况卡把车模压到 190px 宽并 `fit=false` 拉伸，它被压成 5px、糊在白卡里看不出来；
车辆页是 300px 宽 + `fit=true` 等比放大，它就成了 14px、266px 高的硬边灰条。

修法（不动资产、不影响首页）：`ui/pages/VehiclePage.kt` 里新增
`private const val CAR3D_CLIP_RIGHT = 0.785f`，`TopCarView.onDraw` 画 3D 车模前
`canvas.clipRect(0f, 0f, w * CAR3D_CLIP_RIGHT, h)`、画完 `restoreToCount`。
0.785 正好是车身右缘（资产 x 212 ≈ 视图宽 78.5%），只裁掉影子、不切车身与后视镜。

复测：同一区域 `(220,220,220)` 像素数 **1863 → 0**；车模、四门胶囊、胎压卡均无变化。

首页车况卡用的是同一张图（190px 宽、`fit=false`），这条柔影被压成 5px 的一根细线，
放大后同样能看见，所以 `ui/pages/ScenePage.kt` 也加了同一刀：新增文件级
`private const val CAR3D_CLIP_RIGHT = 0.785f`，`drawCarStatusCard` 画车模前按
`imageRect.left + imageRect.width() * CAR3D_CLIP_RIGHT` 裁一刀、画完还原。
两处裁的是同一条资产柔影，车身与后视镜都在 78.5% 以内，不受影响。
截图 `docs/ui-redesign/round5/13-vehicle-3d-day.png`。

## 15. 车辆页改成燃油口径（电池电量 → 油量、平均电耗 → 平均油耗）

画板 05 是电车口径（`78% / 14.2 kWh/100km`），但这台车是燃油车，数据层也一直是燃油字段。
原先按「不编造数据」把两行都写成 `--`，现在接上真实字段：

| 行 | 之前 | 现在 | 数据来源 |
|---|---|---|---|
| 卡片标题 | 续航与电量 | **续航与油量** | — |
| 第二行 | 电池电量 `--` | **油量 `62%`** | `VehicleInfo.fuelPercent`（16px，和画板百分比同字号） |
| 第三行 | 平均电耗 `--` | **平均油耗 `6.8 L/100km`** | `VehicleInfo.avgFuelConsumption`（`%.1f`，14px） |

实现要点：
- `ui/pages/VehiclePage.kt` 的 `kvRow(key, value)` 加 `valueSp: Float = 14f` 参数 ——
  原先按「值以 % 结尾」猜字号，初值 `--` 时会猜错、数据到位后字号也不跟着变；
- 两行保留 `TextView` 引用（`fuelValue` / `avgFuelValue`），`bind()` 里刷新；
  读不到时仍显示 `--`，不编数字。
- 实测（`docs/ui-redesign/round5/14-vehicle-fuel-day.png`）：`油量 62%`、`平均油耗 6.8 L/100km`，
  本应用 crash 记录 0。

## 16. 第 246 轮：常规体验 5 条（Dock 右侧键、空调图标、后备箱行、左树、s11）

| # | 用户意见 | 改法 | 实测 |
|---|---|---|---|
| 1 | Dock 右侧图标太小 | `DockBarView`：`RIGHT_FIRST_CX 1627→1608`、`RIGHT_PITCH 57→64`、`RIGHT_KEY 46→56`、`RIGHT_RADIUS 16→18`；键内图形同步放大（循环 r11/9→12.5/10、除霜 26×14→29×16、空槽「+」±7→±9） | 右缘仍 1892（W-28），与 A/C·AUTO 右缘 1547 留 33；`HIT_MIN=56` 与键一一对齐 |
| 2 | Dock 中间空调图标「不明所以」 | 删掉 `windGlyph`，改 `fanGlyph`：设计板那枚「风」（三条横线，上条右端向上卷、下条左端向下卷，3px 圆头描边）+ **7 颗档位点**（亮到第 `fanLevel` 颗，AUTO 时整排 75% accent，0 档不画） | 三线形状与画板 `Main.png` 的风键一致；档位点实测 4 档时亮 4 颗 |
| 3 | 车辆页缺后备箱开关显示 | `ui/pages/VehiclePage.kt`：`states` 容器 192→240，第五行「后备箱」，`trunkLabel`/`trunkColor` 记录，( `bind()` 里按 `info.trunkClosed` 写「已关闭 / 已打开 / --」并渐变底色 | 实测「后备箱 已关闭」；数据层 `VehicleInfo.trunkClosed: Boolean?` |
| 4 | 设置左栏字太小、分类标题不明显 | `NAV_ITEM 21`、`NAV_LABEL 17`、`TREE_ITEM_H/STEP 34/36`、`TREE_GROUP_STEP 30`、`TREE_GROUP_END 16`、`TREE_TOP 62`；组标题改 **accent 色 + 半粗 + 0.14 字距**，并与条目**同一条左边线**（原先组标题 x=32、条目 x=17，缩进差 15px 才是「既不正对齐也不居中」的根源） | 一屏放不下 26 条 + 5 组，`navScroll` 打开 `isVerticalFadingEdgeEnabled`（底部渐隐提示可滚） |
| 5 | s11「编辑槽位」被遮挡 | `DOCK_SLOT_ROW_H 62→84`（按钮 48 + 上下各 18），`DOCK_SLOT_BTN_H=48` 与 `bottomMargin=12` 保留 | 实测「槽位（15 项）」736..820、「编辑槽位」742..790、「恢复默认 15 槽位」839..903，互不重叠 |

回归：`gradle testSystemUnitTest --offline` 全绿（2666 条），装机后 crash 缓冲空。

## 17. 第 247 轮：左树层级与空调图标返工

- **左树**：上一版把组标题也放大到 18px、颜色与条目同为 `textDim`，结果「像一堆文字堆在一起」。
  这一版把层级拆成两种材质：组标题 **17px / 半粗 / accent 色 / 0.14 字距**（是「分区」），
  条目 **21px / 常规 / `pal.text`**（是「可点的项」），行距 36、组间 16 —— 不靠缩进、靠颜色与字重分层。
- **空调图标**：先做的「圆罩 + 四片楔形扇叶」被否（56px 上读成十字/警示牌）。
  按用户「要么按设计版画一个，要么用旧版 AI 那个图标」的要求，回到画板原样的「风」三条线，
  并按用户「要能体现出几档风量」在上方 plus 一排 **7 颗档位点**（0 档不画，AUTO 全亮 75%）。
  三点式图标 + 点阵在 56px 上互不干扰：颜色/形状负责「是什么」，点负责「几档」。

## 18. 第 248 轮：首页四卡比例、车况卡胎压、座椅三档、音乐卡封面、诊断日志级别

| # | 用户意见 | 改法 | 实测 |
|---|---|---|---|
| 1 | 车况卡 4 个胎压样式不统一、去掉位置描述、数字放大、再靠近车模 | `ScenePage.drawTireBesideWheels` 重写：删掉「左前/右前/左后/右后」，数值 `22→26` 半粗、单位 `bar 11→10`，**整组（数值+3px+单位）居中**到镜像锚点（不再「左列右对齐 / 右列左对齐」，那会让 bar 在左右两侧一个贴车一个离车最远）；`CAR_STATUS_TIRE_GAP_X 12→34`、`CAR_STATUS_TIRE_INSET_Y 58→52` | 四块字身高、颜色、对齐完全一致，左右严格镜像；数字明显变大、离车约 14px |
| 2 | 通风加热是三档，现在没有档位体现 | `ScenePage.seat()` 在 chip **右上角**画三根竖条（`3.2px` 宽、5/8.5/12 高、间距 6），已开档数用 `tint` 填充、其余 38% 墨；放在文案下面会被读成省略号「…」，所以放右上角 | 主驾加热点两下 → 亮两根，与 `level` 一致；0 档三根全灰；「最大风挡」是开关，不画档位 |
| 3 | 音乐卡太长，四卡比例不协调 | `RANGE_CARD_W 266→300`、`MUSIC_CARD_W 666→600`、`CLIMATE_CARD_W 398→420`、`TRIP_BOTTOM_CARD_W 429→440`（+3×24 = 1832 = 可用宽） | 首页底排四卡 300/600/420/440，间距 24，右缘 1876 |
| 4 | 「媒体源（QQ音乐）」压住封面 | 封面 `top + 27` → `top + 36`（来源行基线在 `top+26`，只差 1px，14px 字看着就压在图上） | 来源行与封面之间留出 ~6px 净空 |
| 5 | 「快捷车控」压住下方按钮 | `cardTitle(...)` 加 `size`/`baseline` 参数，本卡用 `16px / top+23`；chip `seatTop 26→34`、`seatH 100→96` | 标题底边 806、chip 顶边 837，净空 31px |
| 6 | 诊断日志页「日志级别」四个胶囊没全显示 | `chipRowCore` 在没有标签/副标题时**不再预留** `CHIP_LABEL_W=300`（`labelTaken = 0`，不 add 标签盒、`scroll.leftMargin = 0`） | 4 枚胶囊「仅错误 / 错误+警告 / 错误+警告+信息 / 全部（含调试）」一屏全显，右对齐到栏右内边 |

顺带修掉一个导航树 bug：最后一条「诊断日志」滚不出来。子 View 全靠 `topMargin` 定位，
`FrameLayout` 的 `wrap_content` 在 `ScrollView` 里量出来比实际内容矮一截，最大滚动位置差一行
（uiautomator 里那颗节点一直是 `bounds=[0,0][0,0]`）。现在按累加出的 `y` 直接给 `navList` 定高，
滚动范围必定覆盖最后一条。

回归：`gradle testSystemUnitTest --offline` = **2666 tests / 0 failed / 0 errors**；装机后 crash 缓冲空。
截图：`docs/ui-redesign/round5/15-home-day-cards.png`、`16-home-night-cards.png`、
`16-home-night-seat-level.png`、`17-settings-logs-levels.png`。

## 19. 第 249 轮：胎压放大 / 单位配比 / 空调档位挪到按钮底部（10 条后再一轮）

| # | 用户意见 | 落点 | 实测 |
|---|---|---|---|
| 1 | 车况卡胎压字体再放大 1.5 倍 | `ui/pages/ScenePage.kt` `drawTireBesideWheels`：数值 26 → **39**；改为「先量整组宽、贴车侧留 6px、整组向外排」，车模 190 → **150** 宽 | 39px 数字四组左右对称、不压车身 |
| 2 | 胎压单位也要放大，比例失调 | 同上：单位 `bar` 10 → **21**，与数值间距 3 → 4，基线 `cy-3` | 「39 + 21」比例接近画板字阶 |
| 3 | 空调弹窗加热/通风档位改成底部 `---` | `ui/ChipButton.kt` `onDraw`：三根横杠 `12×3.2dp`、间距 4、整组居中、`cy = h-10dp`；`showLevel` 时生效，`ClimatePage` 四个座椅按钮已开 | 主驾加热 2 档：两根亮 teal + 一根 45% 灰；通风 0 档：三根全灰 |
| 4 | 首页快捷车控 chip 同形 | `ui/pages/ScenePage.kt` `seat()`：**同一形制**（12×3.2、间距 4、居中、离底 11px），并把 `CHIP_LABEL_BASELINE 78 → 72` —— 否则横杠紧贴文字会被读成省略号「…」 | 主驾通风 1 档：一根亮 + 两根 45% 灰，与弹窗完全一致 |

验证：`assembleSystem` 成功、`testSystemUnitTest` **2666 / 0 / 0**、`logcat -b crash` 本应用 0 条、`FATAL EXCEPTION` 0 条。
截图：`docs/ui-redesign/round5/15-home-day-tire-xl.png`、`16-climate-seat-level-dashes.png`。

### 19.1 第 250 轮：车况卡「下方空」——车模放大 + 整体下移

| 项 | 改法 | 数值 |
|---|---|---|
| 车模放大 | `CAR_STATUS_CAR_BOX_W 150 → 210`、`CAR_STATUS_CAR_BOX_H 236 → 290`、`TOP 44 → 30` | 绘制车模约 120×178 → **190×270** |
| 轮胎文字腾空间 | `bar` 从「与数字同行」改成**数字下方一行**（21px），整组按数字宽对齐 | 组宽 107 → **68px**，车模才有横向空间 |
| 胎压贴轮 | 锚点从固定 px 偏移改成按车高比例落前后轴：`front = car.top + h*0.44 + 14`、`rear = car.top + h*0.72 + 14`（车模放大后旧偏移会飘到车顶外） | 数字中心与前后轮对齐 |
| 常量清理 | 删除不再使用的 `CAR_STATUS_TIRE_INSET_Y`；`Car3dPreviewTest` 的锚点/文字框同步新布局（阈值 40 → 60 墨） | 单测 2666/0/0 |

验证：`assembleSystem` 成功、`testSystemUnitTest` **2666 / 0 / 0**、`logcat -b crash` 本应用 0 条。

### 19.2 第 251 轮：前排胎压上移（并按用户澄清撤回「贴轮」改法）

- 用户先反馈「前排的胎压不在对应的车胎位置旁边了」，随后澄清「我的意思是前排的两个胎压应该往上挪一点，不是让你把胎压改到离车胎近一点」。
- 因此**撤回了按车体墨迹横向内收的改法**（横向仍按车模 rect 边缘 + `CAR_STATUS_TIRE_GAP_X=6`，左右对称留白），只调整纵向比例：
  - 前轴 `0.44 → 0.35`（数字视觉中心从压在车窗下沿抬到前轮）
  - 后轴 `0.72 → 0.69`
  - 纵向比例相对车模 rect 高度，车模尺寸再变也不用重算像素。
- 删除不再使用的 `CAR_STATUS_CAR_INK_W`；`Car3dPreviewTest` 的锚点同步 0.35 / 0.69。
- 验证：`assembleSystem` 成功、`testSystemUnitTest` **2666 / 0 / 0**、`logcat -b crash` 本应用 0 条。

### 19.3 第 252 轮：前排胎压再上移一个身位

- 用户：「再把前排的两个胎压往上挪一个身位」。
- 前轴比例 `0.35 → 0.18`（相对车模 rect 高度，约上移 50px），后排保持 `0.69`。
- 效果：前排两组落到车头两侧（与「车况 / 详情」同一带的下方），后排仍在后轮两侧，四个值更像围着车头车尾布局。
- 验证：`assembleSystem` 成功、`testSystemUnitTest` **2666 / 0 / 0**、`logcat -b crash` 本应用 0 条。

### 20. 第 253 轮：开门弹窗的车模换成实车 3D

| 项 | 改法 |
|---|---|
| 车模 | `ui/dialogs/DoorAlertDialog.kt` 的 `DoorCarView` 内嵌 `Car3dPainter.Scene`，走 `Car3dAssets.VIEW_CARDTOP`（与首页车况卡、车辆详情页同一套资产） |
| 姿态 | 新增 `applyPose(...)`：`CarPose.of(doorsClosed, windowsOpen, hoodClosed, trunkClosed)`，开着的门由资产里的开门图体现（截图里左前门是开着的） |
| 车型 | `render()` 里把上层传入的 `carModel` 写进 `DoorCarView.carModel`（原来这个参数被标了 `UNUSED_PARAMETER` 丢掉） |
| 回退 | 资产缺失 / `Car3dSettings.enabled=false` 时 `paint()` 返回 false，仍画原来的 2D 自绘车身 |
| 等比 | 槽位 220×250 与 cardtop 资产 264×356 比例不同，用 `fit = true` 等比放进矩形，不拉扁 |
| 面板 | 顺手把弹窗面板 alpha 提到 94%：玻璃化后 78% 的 `surfaceStrong` 会让播放中的歌词**清楚地**透在面板上（我们的模糊底只糊壁纸、糊不到页面内容） |

验证：临时把 `MockVehicleGateway.simDoorsClosed` 改成左前门开（截图后已还原）→ 弹窗弹「有车门未关 / 左前门 还没关好，请检查」，车模为实车俯视 3D、左前门开启；还原后重装无弹窗、首页正常。

| 判据 | 结果 |
|---|---|
| 编译 | `assembleSystem` 通过 |
| 单测 | `testSystemUnitTest` **2666 / 0 / 0** |
| 实机 | cardesk28（5560）装机，`logcat -b crash` 本应用 **0** 条 |
| 截图 | `/tmp/door3d2.png`（弹窗）、`/tmp/final253.png`（还原后首页） |
