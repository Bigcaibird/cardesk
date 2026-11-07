# 第133轮 验证报告：仪表盘歌词副屏投射（参考 UNI 桌面 0.7.10 的实现逻辑）

> 本轮把「仪表 Overlay 歌词 / 位置 / 行数 / 样式」那一堆**只写偏好、不执行**的滑杆，
> 接上了真正的执行端：**往那块屏自己的窗口管理器上加一个窗口**。

## 1. 环境

| 项 | 值 |
|---|---|
| 模拟器 | `emulator-5558`（API 28 / SDK 29 / arm64-v8a / 1920×1080） |
| 包名 | `com.cardesk.launcher`（debug 变体 + androidTest 变体） |
| 副屏 | instrumented 测试**自己造**的 `VirtualDisplay`：1920×720@240dpi（`CarDesk 测试仪表`，ID 1） |
| 桌面版本 | 第132轮基线（4230 / 1.0.1134） |

## 2. 之前是什么状态

设置 → 仪表歌词 → 布局参数对话框里那一片全是**只写偏好、不执行的滑杆**：

- 勾上「仪表 Overlay 歌词」只往 SharedPreferences 写 `desk_cluster_overlay = true`；
- 全仓**没有第二个窗口**，也就没有任何东西能被加到副屏上；
- `desk_overlay_x` / `desk_overlay_y`（水平 / 垂直位置）**连设置 UI 都没有**，
  只有写入方没有读取方。

按 `analysis/compare/cardesk-features.md` 的判据，这叫「仅设置项」（条目 S03 / R04）。

## 3. 参考对象：UNI 桌面 0.7.10

上游 `uni-launcher` 在 `ce11799 feat(media): 方控接管、仪表歌词投屏、词级歌词时间轴`
里带进了完整实现，本轮按它的口径移植：

| UNI 的东西 | cardesk 这边的落点 |
| --- | --- |
| `ClusterPanelGeometry.java`（纯排版数学） | `cluster/ClusterPanelGeometry.kt`（纯 Kotlin，可单测） |
| `DisplayPicker.java`（选屏，宁可判不了也不猜） | `cluster/DisplayPicker.kt` |
| `ClusterLyricsView.java`（Canvas 面板） | `cluster/ClusterLyricsView.kt` |
| `ClusterLyricsService.java`（`createDisplayContext` + `addView`） | `cluster/ClusterLyricsService.kt` |
| `LyricCast.java`（进程内广播牌喂数） | **不复用**：cardesk 的 `MusicController` 是同进程单例，服务直接读 |
| `CLUSTER_LYRICS_CAST.md`（方案与验证顺序） | README 第133轮 + `EMULATOR-VERIFY.md` H11 |

## 4. 核心做法与两个坑

不是「把歌词发给仪表」，也不需要车厂接口或投屏协议 ——
做法是**把一个窗口加到那块屏自己的窗口管理器上**：

1. `createDisplayContext(display)` 给出**那块屏**的 Context，它的 `WINDOW_SERVICE` 属于那块屏；
2. 往那个 WindowManager 里 `addView(TYPE_APPLICATION_OVERLAY)`，窗口就落在那块屏上。

两个坑别的方案基本都死在这两条上：

- **必须用 `createDisplayContext`** —— 用主 Context 加窗口，窗口永远落在主屏上，
  `params.x/y` 怎么调都只是在主屏上乱跑；
- **必须查那块屏自己的 density** —— 主屏 240 dpi、仪表 320 dpi 很常见，用错密度字号会差三成。

窗口 flags：`NOT_FOCUSABLE | NOT_TOUCHABLE | NOT_TOUCH_MODAL | LAYOUT_IN_SCREEN |
LAYOUT_NO_LIMITS | HARDWARE_ACCELERATED`，`PixelFormat.TRANSLUCENT`，`gravity = TOP|START`。
`NOT_TOUCHABLE` 必须有，否则会**吞掉副屏上的点击**。

### 4.1 为什么这个服务是**前台**服务

这个功能的存在意义恰恰是「用户去别的 App 听歌时，仪表上还有词」——
而用户一去音乐 App，桌面（HOME）就转入后台。
Android 8+ 的后台执行限制：前台应用起的**普通** Service，
在应用转入后台约一分钟后会被系统**停掉** ⇒ 开着音乐听两首歌，仪表上的歌词自己就没了。

所以走 `startForegroundService` + `startForeground`（低优先级 channel，不出声、不弹横幅），
通知里带一个「停止投射」的动作（点了会把开关也关掉）。
实测 `dumpsys activity services` 里是
`isForeground=true foregroundId=754 foregroundNoti=Notification(channel=cardesk_cluster_lyrics pri=0 …)`。

### 4.2 「两边歌词不同源」这个坑从根上避开了

UNI 那边踩过：桌面卡片读 LRC、面板读 KRC，同一首歌在两处显示成两回事。

cardesk 这边**不复用 UNI 的 `LyricCast` 广播牌**：面板与桌面卡片读的是**同一个** `MusicController`，
擦除高亮也**直接调用桌面卡片那支画笔**（`LyricWipeText.draw`，`ClusterLyricsView.kt:260-281`），
比例来自同一个 `LyricTimeline.wipeFraction` —— 所以两边的擦除边界**逐像素一致**。

## 5. 改了什么

### 5.1 新增 5 个文件（`app/src/main/java/com/cardesk/launcher/cluster/`）

| 文件 | 作用 |
| --- | --- |
| `ClusterPanelGeometry.kt` | 纯排版数学：宽 / 高 / 左上角（面板中心 = 屏幕百分比） |
| `DisplayPicker.kt` | 选屏：id 精确命中 → 名字唯一命中 → 否则**不投** |
| `ClusterLyricSnapshot.kt` | 不可变快照（三行文字 + 擦除比例），面板每帧只读它 |
| `ClusterLyricsView.kt` | Canvas 面板，胶囊 / 纯文字 / 发光三种样式 |
| `ClusterLyricsService.kt` | 加窗 / 断窗 / 重连 / 喂数 / 退避重试 |

### 5.2 新增偏好与日志

- `Prefs.kt:803-836`：`clusterDisplayId` / `clusterDisplayName` / `clusterRows` / `clusterStyle`
  （`desk_cluster_display_id` / `desk_cluster_display_name` / `desk_cluster_rows` / `desk_cluster_style`）；
- `LogTags.kt:66` 新增 `CLUSTER = "CarDesk/Cluster"`，并加进 `ALL`；
- `AndroidManifest.xml:124` 注册 `.cluster.ClusterLyricsService`（`exported=false`）。

### 5.3 设置页接上执行端（`ui/dialogs/ExtraDialogs.kt`）

| 新增 | 位置 | 说明 |
| --- | --- | --- |
| 投射目标 | `:508-519` | 列出这台车机**实际暴露给应用**的屏，点一块就选中；列表为空 ⇒ 直接把「没暴露副屏」说给用户听 |
| 投射状态 | `:520-527` | 服务最后一次结果：成功 / 失败原因 / 目标屏 / 当前曲目 / 歌词行数 |
| 水平 / 垂直位置 | `:530-541` | 这两个偏好键以前**根本没有 UI**，这里补上滑杆 |
| 显示行数 | `:542-548` | 1 / 2 / 3 行 |
| 面板样式 | `:549-573` | 胶囊 / 纯文字 / 发光 |

**改完立刻生效**：开关一变就 `ClusterLyricsService.apply()`（起 / 停服务），
滑杆拖动时 `refresh()`（**原地**改窗口，不重连）；不用等「应用」按钮。

### 5.4 默认**不画底板**（第133轮末按反馈调整）

原先「仪表背景透明度」默认 **70%**，投到仪表上就是一条半透明深灰带子横在仪表中间 ——
仪表底色本来就是深的，再压一块底反而把车速 / 档位衬得更脏。

现在默认 **0%（不画底板）**：歌词**直接落在仪表画面上**，
「胶囊 / 纯文字 / 发光」的差别只在字本身（发光 = 字带同色光晕）。

- 偏好默认值改在 `Prefs.kt:758-774`（`desk_overlay_bg_alpha`，70 → 0）；
- 滑杆**保留**：仪表底色太亮、字看不清时，把它拉上去加一层底（设置页里写了说明）；
- 服务侧 `backgroundOf` 逻辑不变（alpha = 0 ⇒ `plateColor` 的 alpha 也是 0
  ⇒ `ClusterLyricsView.onDraw` 直接不画底板）。

### 5.5 启动兜底

- `MainActivity.kt:288`：桌面起来后拉一次投射服务（`startLaunchServices` 里，
  排在 `MusicController.start` 之后，第一帧就能拿到歌名而不是「等待播放」）；
- `BootReceiver.kt:82-87`：开机广播再独立拉一次 ——
  Android 10+ 禁止后台启动 Activity，桌面起不来时 `MainActivity` 那条路走不通。

## 6. 三个「刻意保守」的决定

1. **主屏 id ≤ 0 永远不是目标**（`DisplayPicker.kt:66-70`）。
   单屏设备上主屏 id 是 `0`，名字一旦被存下来，按名字匹配就会把主屏当成仪表，
   于是歌词窗口盖在桌面自己身上：整条半透明底把桌面压暗，字全糊了。
2. **名字不唯一 ⇒ 判不了，不投**（`DisplayPicker.kt:60-73`）。
   车机上的副屏不止一块（仪表 / 副驾屏 / 后退像屏 / HUD）。
   猜错的后果不是「歌词没显示」，而是**把歌词糊到副驾屏或倒车影像上** —— 那比不做还糟。
3. **屏增删改都重新按存下来的身份解析**（不信旧 id）：id 命中 → 名字唯一命中 → 不投。

## 7. 模拟器 / instrumented 实测

证据：`r133-cluster-cast.png`（**副屏抓回来的真实画面**）、`r133-cluster-settings.png`、
`r133-cluster-settings-on.png`、`r133-logcat-cluster.txt`、`r133-cluster-no-plate.png`；
离屏渲染 `preview/30-cluster-pill.png` ~ `preview/35-cluster-plate.png`。

| # | 操作 | 期望 | 实测 |
| --- | --- | --- | --- |
| 1 | instrumented：造一块 1920×720@240dpi 的副屏，投过去 | 窗口挂上 + 画上东西 | ✅ 报告变 `已投射 · CarDesk 测试仪表 · ID 1`；抓回来的画面里有面板（`r133-cluster-cast.png`） |
| 2 | 设置页打开开关 | 服务立刻起来 | ✅ `CarDesk/Cluster: 没有可投的目标屏 reason=service-created displays=0 … cause=no-secondary-display` |
| 3 | 设置页「投射状态」 | 说清楚为什么没投出来 | ✅ 状态行显示「这台车机没有向应用暴露副屏（仪表可能由独立 SoC 驱动）」 |
| 4 | 单屏模拟器（只有主屏） | **不往主屏上投** | ✅ `displays=0`；主屏上没有任何歌词窗口 |
| 5 | 默认底板透明度 | **不画底板** | ✅ 副屏抓帧里只剩文字，没有半透明灰带（`r133-cluster-cast.png` / `r133-cluster-no-plate.png`） |
| 6 | 底板透明度拉到 70% | 灰底回来（仪表底色太亮时用） | ✅ `preview/35-cluster-plate.png` |

**instrumented 测试**：`app/src/androidTest/java/com/cardesk/launcher/cluster/ClusterLyricsCastTest.kt`。
这是本项目**第一个 instrumented 测试**（`app/src/androidTest/` 之前不存在），
原因是这个功能的要害全在 Android 框架侧（`createDisplayContext` / `addView` / 跨屏权限），
Robolectric 一样都模拟不出来。它用 `DisplayManager.createVirtualDisplay` **自己造一块副屏**
（自己拥有 ⇒ 窗口能挂上去），背后接 `ImageReader` 把那块屏的画面**抓回来** ——
抓到非全透明像素 ⇒ 歌词真的画上去了，不是「加了个空窗口」。

> 踩到的两个 API 坑：
> - `DisplayManager.createVirtualDisplay` 的**公开**签名是
>   `(name, w, h, dpi, surface, flags)` —— **surface 在 flags 前面**
>   （AOSP 内部那版是 flags 在前，公开 SDK 这版反过来）；
>   返回的 `android.hardware.display.VirtualDisplay` **不是** `android.view.Display` 的子类，
>   真正那块屏要从 `getDisplay()` 拿。
> - `VIRTUAL_DISPLAY_FLAG_PUBLIC` 会被系统当成「屏幕共享 / 投屏」，
>   要 `CAPTURE_VIDEO_OUTPUT` 权限（普通应用拿不到）→ 直接抛 `SecurityException`。
>   测试里用 flags = 0 的**私有**虚拟屏：一样出现在自己的 `getDisplays()` 里，一样能挂窗口。

## 8. 单测

| 项 | 值 |
|---|---|
| 全量单测 | **1814 项全绿**（第132轮 1765 → +49） |
| 新增单测 | `cluster/ClusterPanelGeometryTest.kt`（19 项）、`cluster/DisplayPickerTest.kt`（15 项）、`cluster/ClusterLyricSnapshotTest.kt`（11 项）、`cluster/ClusterLyricsRenderTest.kt`（4 项，渲染 PNG） |

## 9. 产物

| 项 | 值 |
|---|---|
| 文件 | `dist/CarDesk-platform-ecarx-cardesk-4231.apk` |
| 包名 | `com.ecarx.cardesk`（system 变体，与真车同包名） |
| versionCode / versionName | 4231 / 1.0.1135-system |
| 大小 | 2 306 709 字节 |
| SHA-256 | `e228d78deae0c1709519b844a7040110372b92725900ec8e9bf01e827b340e7f` |
| 签名 | AOSP platform（SHA-256 `c8a2e9bccf597c2fb6dc66bee293fc13f2fc47ec77bc6b2b0d52c11f51192ab8`） |

## 10. 渲染时抓出来并修掉的三个 bug

离屏渲染（`preview/30-cluster-pill.png`）一上来就暴露了真问题：

1. **三行歌词重叠成一团**。
   原来基线算的是 `top = 行号 × (本行字号 × 行距)`：
   第 2 行（大字）基线 = `1 × 大字行高 + 大字行高 × 0.72 = 117`，
   第 3 行（小字）基线 = `2 × 小字行高 + 小字行高 × 0.72 = 119` —— 两行几乎重合。
   根因：第 3 行的起点用**小字**的行高去乘行号，但第 2 行实际占掉的是**大字**的高度，两套口径。
   现在改成**逐行累加**（`ClusterLyricsView.kt:172-188`、`:361`）。
2. **「发光」样式和「胶囊」一模一样** —— 原来根本没实现。
   现在用 `Paint.setShadowLayer` 给字加同色光晕（`ClusterLyricsView.kt:309-322`）。
3. **空态文字贴在底板上沿**，看着像面板画歪了 → 改成垂直居中（`drawCenteredRow`，`:327`）。

## 11. 已知限制（**真车还没验**）

1. **能不能在真车上生效，取决于车机是否向应用暴露副屏**。
   真车日志包（`cardesk-logs-20261106-085332.zip`）里**没有 `dumpsys display`**，
   `ICarInfo.getPresentationDisplay(int)` 在 jar 里**返回 null（是桩）**，
   所以目前**无法证明**这台领克 05 把副屏开放给了第三方应用。
   仪表很可能由**独立 SoC / 总线**驱动 —— 那样第三方窗口画不上去，**这不是设置问题**。
   设置页会直接这么说，不会装作能投。
2. **一旦能枚举到副屏，权限仍可能拦一道**：往**别人的**私有屏加窗口会被系统拒
   （`SecurityException`），这时状态行会显示「投射失败 · …」并按指数退避重试。
3. **歌词来源仍是 `MusicController`**：面板只显示桌面卡片**正在显示的那一份**，
   桌面卡片没有歌词时面板也没有。
4. **位置滑杆是百分比**，不是像素；目标屏分辨率变了，位置会按比例跟着变。
5. 本轮**没做 GitHub Release、没传 R2**（等明确指示）。
