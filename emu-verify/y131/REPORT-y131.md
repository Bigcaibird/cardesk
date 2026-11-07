# 第131轮 验证报告：没打开播放器时，方控改为控制桌面默认选定的播放器

> 用户诉求：
> 「目前的方控在我没打开 QQ 音乐或是其他第三方音乐播放器的情况下，
> 如果我按播放按钮，默认打开的还是爱趣听，
> 能不能做到在没有打开任何播放器的情况下，让方控控制我们桌面默认选定的播放器，
> 而不是打开爱趣听。」

## 1. 环境

| 项 | 值 |
|---|---|
| 模拟器 | `emulator-5556`（Android 10 / SDK 29 / arm64-v8a / 1920×1080） |
| 包名 | `com.ecarx.cardesk`（**system 变体**，与真车同包名，uid 1000） |
| 桌面上的媒体源 | `design_music_source_v1 = qq`（卡片来源行选了 QQ音乐） |
| 车机原厂音源 | `com.tencent.wecarflow`（**爱趣听**一类，模拟器上也装了） |
| 三方播放器 | `com.tencent.qqmusiccar` / `com.netease.cloudmusic.iot` / `com.luna.music.car` |
| 桌面版本 | 4230 / 1.0.1134-system |

## 2. 根因：按键**落进了空里**

方控（方向盘按键）在真车上是走**原车媒体中心**的私有 Binder：

```
方向盘按键 → 原车 MediaCenter → 我们（OEM 媒体桥）→ 目标播放器
```

`OfficialMediaBridge.dispatch()` 里，「目标是谁」这一层**是对的**（第128轮做的）：
有歌名就用歌名的来源，没歌名就用**桌面选定的媒体源**当兜底目标。

断的是**下一层** —— `MusicSessionTracker.mediaBridgeCommands.send()`：

```kotlin
// 修复前
val c = controller
if (c != null && c.packageName == source) { ...正常下发... }
// —— 目标播放器没在运行 → 没有它的 MediaSession → controller == null ——
Logx.i(LogTags.MEDIA, "OEM 方控回调已过期，忽略", ...)
return false
```

目标播放器**没在运行** → 系统里没有它的 `MediaSession` → `controller == null` →
按键被**直接丢掉** → 原车只好自己把它的在线音源（**爱趣听**）拉起来 →
**用户看到的就是「打开爱趣听」**。

同一处毛病在「桌面音乐卡的播放键」那条路上也在：
`playWithoutSession()` 的目标是「**记住的上次播放器**」（历史），
而不是「**桌面现在选定的播放器**」（用户当下的选择）——
真车诊断包实测就是这种错位（用户选了 QQ 音乐，上次播的是原厂音源）。

## 3. 改了什么

| 文件 | 改动 |
|---|---|
| `core/media/PlayTarget.kt`（新） | 纯逻辑：① 车机原厂音源判定 ② **播放目标解析**（卡片来源行 → 设置页「默认媒体源」→ 记住的播放器 → 默认音乐应用列表）③ 走新路还是旧路 ④ QQ 音乐车机版判定 |
| `core/media/MusicSessionTracker.kt` | ① `mediaBridgeCommands.send()`：**没有会话时把目标播放器拉起来**（不再丢弃按键）② `playWithoutSession()`：目标改用 [PlayTarget.resolve] ③ 新增 `selectedSourcePkg()` / `isPackageInstalled()` / `launchMusicApp()` |

### 3.1 目标解析的优先级（`PlayTarget.resolve`）

| 优先级 | 来源 | 说明 |
|---|---|---|
| 1 | **卡片上当前选定的来源**（`design_music_source_v1`） | 桌面上一眼能看见的那个 |
| 2 | **设置页「默认媒体源」**（`default_music_source_v1`） | 桌面启动时锁定的那个（第 1 条是「通用音乐」时用它） |
| 3 | **记住的上次播放器**（`last_music_package`） | **跳过车机原厂音源**（用户明确不要它被打开） |
| 4 | **默认音乐应用列表** | 最后兜底（QQ音乐 → 爱趣听 → 酷我 → 网易云 → 汽水） |

### 3.2 两条不能碰的线

1. **车机原厂音源（爱趣听一类）不由我们接管** ——
   原车的收音机 / USB / 蓝牙有它们自己的 owner
   （见 `MediaBridgePolicy` 顶部的红线）。目标落到原厂音源时**保持旧逻辑**。
2. **判据不可靠时宁可不动** —— 目标没装、没有目标 → 不乱拉 App。

### 3.3 目标拉起来之后怎么控制

- **QQ 音乐车机版**：走它自己的三方协议 `qqmusiccar://?action=20&m0=N`
  （`action=20` 才会**真的开始播**；而且它没在运行时会把 App 自己拉起来
  —— 正是「按播放」该有的样子）；
- **其它播放器**：先发一路**定向** `MEDIA_BUTTON(PLAY)`，再把 App 拉起来。

## 4. 模拟器端到端验证

### 4.1 修复前：按播放 → 打开的是「记住的上次播放器」

```
11-07 11:16:06.338 I CarDesk/Media: 播放指令（无会话）：打开默认音乐应用 pkg=com.cardesk.ncprobe
11-07 11:16:06.355 I CarDesk/Media: 定向播放事件 pkg=com.cardesk.ncprobe reason=无会话：上次播放器
11-07 11:16:06.489 I CarDesk/Media: 已打开默认音乐应用 pkg=com.cardesk.ncprobe
```

目标 = **历史**（上次播过谁），不是**桌面现在选定的**（QQ音乐）。

### 4.2 修复后：按播放 → 拉起桌面选定的 QQ 音乐

`y131-01-play-launches-qqmusic.png`、`y131-log-play-target.txt`（调试包），
最终产物复验见 4.4

```
11-07 11:18:25.896 I CarDesk/Media: 播放指令（无会话）：走桌面选定的媒体源 pkg=com.tencent.qqmusiccar remembered=com.cardesk.ncprobe
11-07 11:18:25.984 I CarDesk/Media: QQ 音乐方控命令 action=play ok=true
```

- 目标从 `com.cardesk.ncprobe`（记住的）换成 **`com.tencent.qqmusiccar`**（桌面选定的）；
- QQ 音乐的三方协议控制命令**发成功**；
- 进程起来了：`u0_a135 6193 … com.tencent.qqmusiccar`；
- 前台 Activity：`com.tencent.qqmusiccar/.app.activity.AppStarterActivity`；
- **爱趣听 `com.tencent.wecarflow` 没有被拉起** ✓。

### 4.3 两种「桌面选定的来源」都覆盖

| 场景 | 走的哪一级 | 结果 |
|---|---|---|
| 卡片来源行选了 QQ音乐（`design_music_source_v1=qq`） | 第 1 级 | QQ音乐被拉起并收到播放命令 ✓ |
| 卡片是「通用音乐」、设置页选了 QQ音乐 | 第 2 级 | 同上 ✓（单测覆盖） |
| 什么都没选、上次播的是爱趣听 | 第 3 级**跳过**原厂音源 → 第 4 级 | 兜底到 QQ音乐，**不是爱趣听** ✓（单测覆盖） |

### 4.4 最终产物（4230 / 1.0.1134-system）复验

正式包装到模拟器后重跑同一场景（`y131-02-release-4230-launches-qqmusic.png`、
`y131-log-release-4230.txt`）：

```
11-07 11:25:34.929 I CarDesk/Media: 播放指令（无会话）：走桌面选定的媒体源 pkg=com.tencent.qqmusiccar remembered=com.cardesk.ncprobe
11-07 11:25:35.073 I CarDesk/Media: QQ 音乐方控命令 action=play ok=true
```

- 前台 Activity：`com.tencent.qqmusiccar/.app.activity.AppStarterActivity`；
- 进程：`u0_a135 … com.tencent.qqmusiccar`；
- **爱趣听 `com.tencent.wecarflow` / 网易云 / 汽水 / ncprobe 全部没被拉起** ✓。

## 5. 单测与反向验证

| 项 | 结果 |
|---|---|
| 全量单测 | **1750 项全绿**（第130轮 1736 → +14） |
| 新增单测 | `PlayTargetTest`（14 项：原厂音源判定 / 目标解析 7 级 / 走哪条路 / QQ 协议） |
| 反向验证 | `tools/verify_y131_reverse.sh`：**9 条全部变红**，还原后 0 红灯 |

反向验证逐条结果（`RED` = 失败用例数）：

| # | 改坏的地方 | RED |
|---|---|---|
| 1 | 让「记住的上次播放器」优先于「桌面选定的来源」（旧行为） | 4 |
| 2 | 不跳过车机原厂音源（爱趣听又会被选中） | 1 |
| 3 | 目标解析不检查安装状态（会拉起没装的 App） | 1 |
| 4 | 原厂音源也算我们接管（会去抢原车音源） | 2 |
| 5 | 原厂音源判定失效（认不出爱趣听） | 4 |
| 6 | 去掉「设置页默认源」这一级 | 2 |
| 7 | 去掉「默认音乐应用列表」兜底 | 2 |
| 8 | 没有目标时也走「桌面选定媒体源」那条路 | 1 |
| 9 | QQ 音乐车机版认不出来（不走三方协议） | 1 |

## 6. 产物

| 项 | 值 |
|---|---|
| 文件 | `dist/CarDesk-platform-ecarx-cardesk-4230.apk` |
| versionCode / versionName | 4230 / 1.0.1134-system |
| 大小 | 2 266 222 字节 |
| SHA-256 | `c0cbca5a2b13c9cc993fdc2c3c59027a40822fb7bcd8f25af5e6b2857dd47f52` |
| 签名 | AOSP platform（SHA-256 `c8a2e9bccf597c2fb6dc66bee293fc13f2fc47ec77bc6b2b0d52c11f51192ab8`） |

## 7. 遗留 / 待真车复验

1. **原车方控那条路没能在模拟器上端到端跑** ——
   原车媒体中心 SDK（`com.ecarx.qq.tool`）模拟器上没有，
   `OfficialMediaBridge.dispatch()` → `mediaBridgeCommands.send()` 这段靠单测与代码走查覆盖，
   **真车上必须复验**（诊断包看 `bridge.callbackCount` 有没有涨）。
2. **原车桥的白名单里没有酷我 / 酷狗** ——
   `MediaBridgePolicy.eligiblePackages()` 只有 QQ音乐（车机版/手机版）、网易云、汽水。
   桌面选的是酷我/酷狗时，原车桥**会忽略**按键（那两家的车机版没在真机上验证过，
   第127轮只加过汽水）。要支持得先在真车上看它们发不发会话。
3. **「默认媒体源」和「卡片来源行」是两个键** ——
   真车诊断包实测：用户只在卡片来源行选过 QQ音乐
   （`design_music_source_v1=qq`），设置页的 `default_music_source_v1` 从没写过。
   本轮两条都读，**卡片来源行优先**。
4. **没有发 GitHub Release、没有传 R2**（等明确指示）。
