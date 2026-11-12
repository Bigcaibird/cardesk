# 第149轮工作报告

> 项目：CarDesk（Android 车机桌面）· `/Users/roy/Documents/desktopapp/cardesk`
> 设备：emulator-5560（arm64，1920×1080，density 160）
> 构建：`:app:assembleSystem -PcardeskPackage=com.ecarx.cardesk` → `app/build/outputs/apk/system/app-system.apk`（2.70 MB）

---

## 一、本轮四条需求

| # | 需求 | 状态 | 关键证据 |
|---|------|------|----------|
| ① | 桌面启动完成后唤起默认媒体来源 App 播放，并在设置里做成可选项 | ✅ | 真机：`启动后唤起媒体 App：已拉起 pkg=com.tencent.qqmusiccar` → `会话已到，已下发播放`；前台变成 `com.tencent.qqmusiccar/.app.activity.AppStarterActivity`，`playing=true` |
| ② | 整理设置页各页设置项，同模块归并成一个大项、减少重复，并优化 UI | ✅ | 导航树 28 项 → **25 项**（5 组），死代码删除，旧 key 仍可路由；真机截图见下 |
| ③ | 修设置页滑杆滑动触发桌面左右滑动 | ✅ | 真机 A/B：旧版甩动 → `抢事件=true` + `切页 from=0 to=1`；新版同样甩动 → **零切页日志**，滑杆跟手走到 55px |
| ④ | 仪表歌词最小宽度 30% → 10% | ✅ | 真机：模块宽度滑杆拖到最左停在 **10%**，prefs `desk_overlay_w=10`（旧下限是 30%） |

---

## 二、需求①：启动后唤起默认媒体源 App 并播放

### 实现

新建 `app/src/main/java/com/cardesk/launcher/core/media/MediaAppLauncher.kt`：

- `shouldLaunch(autoLaunchEnabled, sourceId)` —— 纯逻辑：开关关 / 「通用音乐」/ 空 id 都返回 false；
- `resolvePackage(sourceId, isInstalled)` —— 纯逻辑：默认源 → 包名（取第一个已装的候选）；
- `sessionMatches(trackPkg, targetPkg)` —— 纯逻辑：只有**目标 App 自己**的会话才下播放指令；
- `launchAndPlay(context, autoLaunchEnabled, sourceId, isInstalled, onStarted)` —— 起 Intent → 轮询等 `MediaSession`（1200ms 起、400ms 一次、15s 超时）→ 会话到了调 `MusicSessionTracker.setPlaying(true)`。

接线在 `MainActivity.startLaunchServices()` 里「默认媒体源锁定」那段之后。

### 三个关键决策（以及为什么）

1. **开关判断放在 `launchAndPlay` 内部**，不放外面。
   放外面的话每个调用方都得自己判一遍，早晚有地方忘；忘了就变成「用户明明关了开关，开机还是弹出音乐 App」。
   `MediaAppLauncherLaunchTest.开关判断放在函数内部_调用方忘了判也拦得住` 专门盯这条。

2. **播放指令用 `setPlaying(true)`，不用 `playPause()`**。
   `playPause` 是「翻转」语义 —— 启动那一刻会话刚建立、播放态还没上报，翻转一次很可能变成「暂停」。
   `setPlaying` 是「表达期望状态」的幂等语义：已经在放就什么都不做。

3. **不发 `ACTION_MEDIA_BUTTON` 广播**。
   广播打的是「当前正在出声的 App」，启动那一刻可能还没有 App 在出声，广播就丢了；
   等会话出现后精确打到那个 App 上才靠谱。

### 默认关闭

`Prefs.autoLaunchMediaApp`（键 `auto_launch_media_app`）默认 **false**：
开机就弹出一个音乐 App 挺打扰，而且默认源是「通用音乐」时根本没有对应的 App 可拉。

### 设置页入口

新增独立的「媒体源」页（原来藏在「系统诊断」里，现在是一级菜单）：

- 「默认媒体源」分区：`默认来源` 选项条 + **「启动后自动播放」开关** + 提示文案；
- 「媒体来源（固定 9 个）」分区：9 个来源各自的安装状态。

---

## 三、需求②：设置页重构

### 导航树：28 项 → 25 项

| 分组 | 项 |
|------|-----|
| 智能功能 | 启动与开机 · 按键增强 · 方控仪表 · 计划任务 · **媒体源** |
| 布局与显示 | 日夜模式 · 壁纸 · 桌面卡片 · **歌词** · 双画中画 · **Dock 与导航栏** |
| 网络与升级 | 局域网传输 · 云端应用 · 在线更新 |
| 车控 | 氛围灯 · 360 环影 · 启停控制 · 车辆信息 · 自动热点 · ADB 调试 |
| 系统 | 原厂设置 · 默认设置 · 系统诊断 · 重启车机 · 诊断日志 |

合并关系（−5 项，+2 项，净 −3）：

- `启动切换` + `桌面自启` → **启动与开机**
- `桌面卡片` + `卡片风格` → **桌面卡片**
- `桌面歌词` + `仪表歌词` → **歌词**
- `Dock 状态栏` + `Dock 快捷应用` → **Dock 与导航栏**
- 新开 **媒体源**（从「系统诊断」里独立出来）

### 旧 key 仍保留路由

`autostart` / `cardstyle` / `desktoplyric` / `clusterlyric` / `quickapps` 这五个 key 外部深链还在用，
它们现在指向合并后的页面，并在 `select()` 里单独给标题映射（否则深链进来标题空白）。

### 去重

- Dock 页两对「同键同选择器」里的 `actionButton("指定隐藏原车 Dock 的应用（已选 N 个）…")` 与
  `actionButton("指定隐藏状态栏的应用…")` 删除 —— 保留 `pickerRow`，它的值显示具体 App 名
  （「QQ音乐、设置 等3个」），比「已选 N 个」信息更全。`selectedAppCount()` 随之成为死代码，已删。
- 日夜模式页 3 个 `cardChoice` 写的是同一个 `daynight_mode`，那是**单选组**（不是重复项），保留。

### 删掉的死代码

`globalGroups` / `systemGroups` 两份 list、`tabGlobal` / `tabSystem` 字段、`tabView()` / `paintTab()`、
`private var tab = 0`。
旧的 `globalGroups` / `systemGroups` 只喂两个**从没被 `addView` 进视图树**的计数徽标 —— 留着就是每次开设置页白构造一遍。

> **踩过的坑**：按 `globalGroups` 起点到 `mergedGroups` 起点整段删除时，
> **误删了夹在中间的 5 个字段声明**（`selectedKey` / `pageTeardown` / `clusterDisplayList` /
> `clusterStatusText` / `onShowClusterLyricColor`），编译报一堆 `Unresolved reference`。
> 备份在 `/tmp/SettingsOverlay.kt.bak`，已补回。

### UI 优化

- `section()`：`22f textFaint` + `topMargin 8` → **`24f textDim` 粗体** + 高 44 + `topMargin 20` + 下方 1px `Skin.NAV_STROKE` 细线。
  以前那个字号配最弱那档颜色，在 1920 宽的车机屏上**几乎看不见**。
- 导航树组标题：`16f textFaint` → `17f textDim`。
- 仪表歌词分区收敛：`投射目标` / `投射状态` 两个 section 去掉，改用 `note()`；
  `面板位置与样式` 改名 `面板位置、样式与颜色`；删掉空的 `section("歌词颜色")`（5 分区 → 2 分区）。
- 桌面歌词页 3 处裸键 `readInt/writeInt("desk_desk_compact_sub_sp" / "desk_desk_expanded_main_sp" / "desk_desk_expanded_sub_sp")`
  改回 typed 属性 `prefs.deskCompactSubSp` / `prefs.deskExpandedMainSp` / `prefs.deskExpandedSubSp`
  —— 裸键绕过了 `Prefs` 的 `coerceIn` 钳制，读侧也不兜底。

---

## 四、需求③：滑杆滑动触发桌面翻页

### 根因

设置页是桌面的**子 View**（`LauncherView.content` 里 `addView` 进去的），
所以桌面的 `onInterceptTouchEvent` 对设置页里的控件**同样生效**：
桌面把「单指横滑超过阈值（`SINGLE_FINGER_SLOP = 24px`）」当成翻页手势，会把 MOVE 事件抢走。

### 踩过的坑：置位时机比「置不置位」重要得多

把 `android-all`（android-14）的 `ViewGroup` 反编译出来看字节码，结论是这样的：

1. `dispatchTouchEvent` 在 **ACTION_DOWN** 时会调 `resetTouchState()`，
   而 `resetTouchState()` 干的第一件事就是 `mGroupFlags &= ~FLAG_DISALLOW_INTERCEPT`
   （`FLAG_DISALLOW_INTERCEPT = 0x80000`）——
   **每次新手势开始，祖先身上的「禁止拦截」标志都会被清掉**。
2. 同一段字节码里，`disallowIntercept` 为真时 **不会**去调 `onInterceptTouchEvent`（这正是我们要的效果）。
3. `resetTouchState()` 还会在手势**结束**（UP / CANCEL）时再调一次。

由此推翻了两个「看起来对」的写法：

- **「挂上去时置位」没用**：标志会在下一次按下时被清掉，等于每次手势都没保护。
- **「等滑杆回调了再置」也太晚**：`AbsSeekBar.onTouchEvent` 里，如果自己在一个 `ScrollView` 中
  （设置页正文就在 `ScrollView` 里），按下时走的是 `isInScrollingContainer()` 分支，
  **不会**调 `startDrag`，要等**位移超过 touch slop 的第一个 MOVE** 才调 ——
  而那个 MOVE 恰恰就是桌面判定「横滑超阈值 → 抢事件」的那个 MOVE。
  桌面先抢，滑杆连 `onStartTrackingTouch` 都等不到。（手指划得快，一个 MOVE 位移就超过阈值，正是最容易复现的场景。）

> 这两点都做了**实证**：把旧写法（`onAttachedToWindow` 置位）装回去，
> `SettingsTouchInterceptTest` 4 条**全红**（`桌面抢走了 1 个 MOVE`）。

### 最终修法

```kotlin
override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
    val handled = super.dispatchTouchEvent(ev)
    requestDisallowInterceptTouchEvent(true)
    return handled
}
```

`super.dispatchTouchEvent(ev)` 返回时这次事件已经派发完毕，而祖先的**下一个 MOVE 还没开始** ——
此时置位，**第一个 MOVE 就已经被保护**。

顺序也关键：**必须在 `super` 之后调**。
`super` 内部在 ACTION_DOWN 时会先 `resetTouchState()` 清掉自己身上的标志；
如果在 `super` 之前调，`requestDisallowInterceptTouchEvent` 里「已经是这个状态就直接返回」的短路
会让标志**传不上去**（子节点还留着 → 认为重复调用 → 直接返回 → 祖先没被置位）。
（反向验证第 2 项专门盯这条。）

滑杆自己的 `onStartTrackingTouch` 里仍然置一次位 —— 冗余但无害，属于第二道保险。
`onDetachedFromWindow` 里把标志撤掉，否则关掉设置页后桌面就再也翻不了页。

### 真机 A/B 对照

模拟器的 `input swipe` 会把位移均摊成很多小步（每个几像素），永远触发不了「单个 MOVE 超 24px」，
所以用 `tools/raw_touch.sh` 直接写原始 evdev 事件，构造 `DOWN → MOVE(位移 300px) → UP`。

| | 旧版（第148轮 APK） | 新版（第149轮 APK） |
|---|---|---|
| 日志 | `手势：单指横滑翻页 dx=299.9 抢事件=`<br>`true` + `切页 from=0 to=1` | **无任何切页日志** |
| 滑杆 | 卡住不动（水平位置仍 19%） | 跟手走到 55px |
| 桌面 | **翻页了**（背景从场景页变成导航页） | 没翻 |

---

## 五、需求④：仪表歌词最小宽度 30% → 10%

**三处都改了**（只改一处会出现「设置里拖到 10%，实际还是 20%」的错位）：

| 层 | 位置 | 改动 |
|----|------|------|
| 几何层 | `cluster/ClusterPanelGeometry.kt` | `MIN_WIDTH_RATIO = 0.2f` → `0.1f` |
| 存储层 | `core/Prefs.kt` | `overlayWidthPct` 的 `coerceIn(30,100)` → `coerceIn(10,100)`（getter + setter 两处） |
| UI 层 | `ui/SettingsOverlay.kt` | `sliderRow("模块宽度", prefs.overlayWidthPct, 30, 100, "%")` → `10, 100` |

真机：模块宽度滑杆拖到最左停在 **10%**，prefs `desk_overlay_w=10`。

---

## 六、测试

### 新增用例（+29 条）

| 文件 | 条数 | 锁住什么 |
|------|------|----------|
| `ui/SettingsTouchInterceptTest.kt`（新） | 4 | 桌面**一次都不许**抢设置页里的 MOVE；祖先身上必须挂着 `FLAG_DISALLOW_INTERCEPT`；关掉设置页要撤掉标志 |
| `core/media/MediaAppLauncherTest.kt`（扩） | 12 | 开关/通用音乐/空 id 的判定；包名解析；`sessionMatches` 比对 |
| `core/media/MediaAppLauncherLaunchTest.kt`（新） | 8 | 执行层：开关关着不启动任何 Activity；开关开着且装了才启动；通用音乐不启动 |
| `ui/SettingsLayoutTest.kt`（新） | 9 | 合并后的页面都能打开；导航项正好 25 个；旧 key 仍能路由；死代码真的删掉了；歌词宽度下限 10% |

### 全量回归

```
testSystemUnitTest: tests=2063 fail=0 err=0
testDebugUnitTest:  tests=2063 fail=0 err=0
```

（上轮 2034 条，本轮 +29）

### 反向验证脚本 `tools/verify_y149_reverse.sh`

18 条反向项，**每条都确认「改坏 → 断言必红 → 还原 → 恢复全绿」**：

```
基线（应当 0 红灯）        RED=0 TOTAL=55 / RED=0 TOTAL=1
反向1  滑杆保护整个去掉          RED=4
反向2  置位挪到 super 之前        RED=2
反向3  改回只在 onAttachedToWindow RED=4   ← 旧写法实测无效
反向4  关设置页时不撤标志          RED=1
反向5  开关判断挪到函数外面        RED=2
反向6  shouldLaunch 不看开关      RED=3
反向7  通用音乐也去拉             RED=1
反向8  播放指令换成 playPause      RED=1（接线断言）
反向9  会话比对放行所有包名        RED=1
反向10 自动播放默认改成开          RED=1
反向11 MainActivity 不接线        RED=1（接线断言）
反向12 设置页不给开关落盘          RED=1（接线断言）
反向13 导航树少一项               RED=3
反向14 旧 key 不再路由             RED=1
反向15 死代码复活                 RED=1
反向16 几何层下限改回 0.2          RED=1
反向17 存储层下限改回 30           RED=1
反向18 UI 层滑杆下限改回 30        RED=1（接线断言）
还原后自检（应当 0 红灯）        RED=0 TOTAL=55 / RED=0 TOTAL=1
```

> **踩过的坑（脚本本身）**：接线检查里的 `no_text(settings, 'globalGroups')` 最初**恒红** ——
> 因为剥注释只处理了 `//` 行注释，KDoc 块注释 `/** */` 里提到「globalGroups」这个**名字**也被当成了代码。
> 改成同时剥块注释后基线才真正 0 红灯。
> 另一处：反向11 最初只注释了 `launchAndPlay(` 那一行，后面的参数列表变成悬空语法 → 编译失败 → 单测一条都跑不了。
> 改成整段注释才既编译得过、又真的由源码断言把关。

---

## 七、真机验证

### 需求① 启动后自动播放

开关**关**（默认态）：

```
CarDesk/Media: 启动后唤起媒体 App：开关关着或没有对应 App，不动作 enabled=false sourceId=generic
```

开关**开** + 默认源 = QQ音乐：

```
CarDesk/Media: 启动后唤起媒体 App：已拉起 pkg=com.tencent.qqmusiccar sourceId=qq
CarDesk/Media: 启动后唤起媒体 App：会话已到，已下发播放 pkg=com.tencent.qqmusiccar

mResumedActivity: com.tencent.qqmusiccar/.app.activity.AppStarterActivity
media_session: package=com.tencent.qqmusiccar  active=true
CarDesk/Media: 播放进度推送 pkg=com.tencent.qqmusiccar playing=true positionMs=81019
```

### 需求② 设置页新 UI

- 导航树 5 组 25 项；默认页变成「启动与开机」（开机自启 + 启动切换合并）
- 「媒体源」独立成页，含「启动后自动播放」开关
- 「歌词」页 = 桌面歌词 + 仪表歌词
- 分区标题变大变亮 + 下方细分割线

### 需求③ 滑杆

见上面的 A/B 对照表。

### 需求④ 歌词宽度

模块宽度拖到最左 = **10%**，`desk_overlay_w=10`。

---

## 八、产出物

### 新增文件

```
app/src/main/java/com/cardesk/launcher/core/media/MediaAppLauncher.kt     ← 新功能主体
app/src/test/java/com/cardesk/launcher/core/media/MediaAppLauncherTest.kt      ← 扩
app/src/test/java/com/cardesk/launcher/core/media/MediaAppLauncherLaunchTest.kt ← 新
app/src/test/java/com/cardesk/launcher/ui/SettingsTouchInterceptTest.kt          ← 新
app/src/test/java/com/cardesk/launcher/ui/SettingsLayoutTest.kt                  ← 新
tools/verify_y149_reverse.sh    ← 反向验证脚本（18 项）
tools/verify_y149_device.sh     ← 真机验证脚本
tools/raw_touch.sh              ← 原始 evdev 触摸注入（复现「甩动」）
```

### 修改文件

```
app/src/main/java/com/cardesk/launcher/core/Prefs.kt                       autoLaunchMediaApp / overlayWidthPct 下限
app/src/main/java/com/cardesk/launcher/cluster/ClusterPanelGeometry.kt     MIN_WIDTH_RATIO 0.2 → 0.1
app/src/main/java/com/cardesk/launcher/MainActivity.kt                     启动后唤起媒体 App 的接线
app/src/main/java/com/cardesk/launcher/ui/SettingsOverlay.kt               滑杆触摸修法 + 页面合并 + 去重 + UI
app/src/test/java/com/cardesk/launcher/cluster/ClusterPanelGeometryTest.kt  宽度下限用例
app/src/test/java/com/cardesk/launcher/ui/MediaSourceIconRenderTest.kt     导航项 key 变了
```

### 截图

`emu-verify/y149/`

| 文件 | 内容 |
|------|------|
| `01_settings_old.png` | 旧版设置页（28 项导航树） |
| `02_clusterlyric_old.png` | 旧版「仪表歌词」页 |
| `05_rawflick_old.png` | **旧版 bug 复现**：滑杆卡住 + 桌面翻页 |
| `20_settings_navtree_new.png` | 新版设置页（25 项导航树） |
| `21_settings_media.png` | 新增「媒体源」页（含启动后自动播放开关） |
| `22_settings_lyric.png` | 合并后的「歌词」页 |
| `23_slider_flick_new.png` | **新版**：甩动滑杆不翻页 |
| `24_cluster_lyric_width.png` | 仪表歌词分区 |
| `25_width_10pct.png` | 模块宽度下限 10% |
| `26_auto_launch_playing.png` | 启动后 QQ音乐被拉起并在播放 |
| `30_restored_desktop.png` | 还原后的桌面 |

---

## 九、设备状态（已还原）

| 项 | 状态 |
|----|------|
| `pip_app` | `com.autonavi.amapautp.yh` ✓ |
| `nav_cover_backdrop_enabled` | `true` ✓ |
| `auto_launch_media_app` | 已删除 → 默认 false ✓ |
| `default_music_source_v1` | 已删除 → 默认 generic ✓ |
| `desk_overlay_*` | 已删除 → 默认值（100% / 50% / 88%）✓ |
| 高德 | 未被禁用 ✓ |
| 系统时间 | 与主机一致（17:23:38）✓ |
| QQ音乐车机版 3.14.0.4 | 保留安装 ✓ |

> 期间发现一个**待查现象**：16:54 时 `cardesk.xml` 里的 `pip_app` / `nav_cover_backdrop_enabled`
> 等键在 17:20 前的一次重写中消失过。做过探针实验（写入 `y149_probe` 后让 app 完整跑一轮开/关设置页），
> 这三个键都**存活**了，说明当前 build 不会冲掉外部写入的键；
> 且已确认本轮 `Prefs.kt` 只改了取值范围、没碰存储机制。
> 现象本身不影响功能（那些键都有代码默认值兜底），但成因还没完全定位，留待后续排查。
