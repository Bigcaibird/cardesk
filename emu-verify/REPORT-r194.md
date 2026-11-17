# 第194轮 · 「启动后自动播放」后台起播实测报告

**日期**：2026-11-17
**设备**：模拟器 `emulator-5560`（API 28 / Android 9，1920×1080 @160dpi，已 root）
**被测包**：`com.cardesk.launcher`（本轮改动版，debug 构建）
**真实播放器**：网易云车机版 6.2.81 / 6002081、汽水音乐车机版、QQ 音乐车机版（均为车机真包）

---

## 一、用户提的需求

> 设置里的启动自动播放，现在会把音乐app唤醒到前台再播放，
> 可不可以做到不把音乐app唤醒到前台，在后台直接播放

对应代码：`MediaAppLauncher.launchAndPlay`（第149轮）→ `getLaunchIntentForPackage` +
`startActivity` → **音乐 App 被拉到前台** → 轮询等会话 → 下「播放」。

---

## 二、结论先行

**能做到，但只对一部分播放器能做到。**

| 播放器 | 后台通道 | 本轮实测结果 | 前台 |
|---|---|---|---|
| **网易云车机版** `com.netease.cloudmusic.iot` | 有 `MediaBrowserService` | ✅ **后台起播成功** | **桌面**（网易云没抢到） |
| 汽水音乐车机版 `com.luna.music.car` | 有，但 `onGetRoot()` 返回 null | ❌ 连不上 → 降级拉前台 | 汽水 |
| QQ 音乐车机版 `com.tencent.qqmusiccar` | **没有** | ❌ 直接走前台 | QQ 音乐 |

**功能零退化**：不支持后台起播的播放器，行为与第149轮**完全一致**（照样能播，只是仍会拉前台）。

---

## 三、关键实测规律：什么通道能在后台冷启动播放器

这是本轮最重要的发现，决定了整个方案。

| 通道 | App 没运行时能唤起吗 | 会拉前台吗 |
|---|---|---|
| **绑定 `MediaBrowserService`** | **能**（绑定会拉起它的进程） | **不会**（起的是 Service，不是 Activity） |
| 发定向 `MEDIA_BUTTON` 广播 | **不能**（App 没运行 → 无进程 → 广播无人接） | 不会 |
| 发定向第三方广播（QQ 音乐那套） | **不能**（同上） | 不会（但也唤不起来） |
| `startActivity`（原实现） | 能 | **会** |

> → **唯一能在后台冷启动播放器的正规通道是 `MediaBrowserService`。**

### 3.1 广播唤不起冷启动播放器的实测

```
# 冷启动（QQ音乐进程不存在）后发定向广播
$ adb shell "am broadcast -a com.tencent.qqmusiccar.action --ei action 100 \
      --el pull_from 45124 -p com.tencent.qqmusiccar"
Broadcast completed: result=0
$ adb shell ps -A | grep qqmusiccar
(无进程)                       ← 广播无人接收，App 根本没起来
```

汽水同样：定向 `MEDIA_BUTTON` 广播在 App 未运行时**唤不起进程**；
但 App 已在运行时，接收器确实收到（`BachMediaButtonReceiver.onReceive` 有日志）。

### 3.2 `MediaBrowserService` 能唤起冷启动播放器

```
# 强停网易云 → 探针绑定其浏览器服务
$ adb shell am force-stop com.netease.cloudmusic.iot
$ adb shell am start -n com.cardesk.bgprobe/.ProbeActivity \
      --es pkg com.netease.cloudmusic.iot \
      --es cls com.netease.cloudmusic.service.AutomotiveService

BgProbe: foreground before: com.cardesk.bgprobe/.ProbeActivity
BgProbe: connecting MediaBrowser -> com.netease.cloudmusic.iot/…AutomotiveService
BgProbe: MediaBrowser CONNECTED              ← 271ms
BgProbe: controller created, sending PLAY
BgProbe: PLAY sent
BgProbe: foreground after connect: com.cardesk.bgprobe/.ProbeActivity   ← 前台没变
```

会话侧印证：

```
SessionTag com.netease.cloudmusic.iot/SessionTag (userId=0)
  package=com.netease.cloudmusic.iot
  active=true
  flags=3
```

→ 网易云**进程起来了、活跃会话建好了**，而**前台始终是调用方**。

---

## 四、各播放器的清单事实（`aapt dump xmltree` 实读）

### 网易云车机版 6.2.81

```
[service] com.netease.cloudmusic.service.AutomotiveService
   exported=true
   action: android.media.browse.MediaBrowserService      ← 标准通道，无权限要求

[service] com.netease.cloudmusic.service.PlayService
   exported=true

[receiver] com.netease.cloudmusic.iot.mediacenter.MediaButtonEventReceiver
   exported=true
   action: android.intent.action.MEDIA_BUTTON
```

### 汽水音乐车机版 1.1.27

```
[service] com.luna.player.impl.playerservice.MediaBrowserPlayerService
   exported=true
   permission: com.luna.player.permission.MEDIA_BROWSE
   action: android.media.browse.MediaBrowserService

[service] com.luna.player.impl.playerservice.PlayerService
   exported=false
```

→ 服务在，但**实测连不上**（`MediaBrowser CONNECTION FAILED`），
原因与 `SodaMediaSource` 里早已记录的事实一致：`onGetRoot()` 返回 null，不接外部客户端。

### QQ 音乐车机版

```
（无 android.media.browse.MediaBrowserService）
[service] com.tencent.qqmusiccar/.service.MainService   (有 action，但拉起后仍会走 Activity)
[service] com.tencent.qqmusiccar/.third.api.QQMusicApiService  (第三方 AIDL，依赖对方 AIDL 契约)
[receiver] com.tencent.qqmusiccar/.app.reciver.BroadcastReceiverCenterForThird
   action: com.tencent.qqmusiccar.action
   action: android.intent.action.VIEW  scheme: qqmusiccar
```

→ 全包 grep 无 `MediaBrowserService` / `MediaBrowserCompat` / `MediaSessionService`；
第三方广播通道在 App 未运行时唤不起进程 → **只能拉前台**。

---

## 五、实现

新增两个文件，改一个：

| 文件 | 作用 |
|---|---|
| `core/media/BackgroundPlayback.kt` | **纯逻辑**：通道表 + 判定（可 JVM 单测） |
| `core/media/BackgroundPlayerConnector.kt` | **执行层**：`MediaBrowser` 连接 + 下发播放 + 失败回调 |
| `core/media/MediaAppLauncher.kt` | 接入：先后台、连不上降级前台 |

流程：

```
桌面起来
  └─ 试后台起播
       ├─ 查该包自己的 MediaBrowserService（按 serviceInfo.packageName 筛）
       │    └─ 没有 → 直接拉前台
       └─ 有 → MediaBrowser.connect()
            ├─ 连上 → MediaController.transportControls.play()  ✅ 不拉前台
            └─ 连不上 → 降级：拉前台 + 轮询会话 + 下播放（与第149轮一致）
```

### 5.1 踩到的三个坑（都已修）

**坑一：类名形态判断错了。**
`serviceInfo.name` 在 Android 9 上给的是**相对类名**
（`com.netease.cloudmusic.service.AutomotiveService`）——它**含点号但不含包名**。
一开始用「含不含点号」判断完整名 vs 相对名，结果：
- 网易云的相对名被错补成 `com.netease.cloudmusic.iot.com.netease…` → 连不上；
- 更糟的是把**别的 App** 的完整名也错补一遍 → 连到了系统音乐
  （日志实测：`service=com.netease.cloudmusic.iot.com.android.music.MediaPlaybackService`）。

→ 正确判据是「**是不是以目标包名开头**」，并且用 `serviceInfo.packageName`
先把**别的包**的服务筛掉。

**坑二：`ComponentName(pkg, cls)` 要相对类名。**
我们手里是完整类名，直接塞进去会拼成 `pkg.pkg.相对名`。

**坑三：连接是异步的，不能同步判定「连上了」。**
最初写成「发起连接即认为成功」，结果汽水连不上时**既没拉前台也没播**（用户完全没声音）。
→ 改成：连接器带 `onFailure` 回调，失败时才触发前台降级。

---

## 六、端到端实测（改动后的 cardesk）

配置：`auto_launch_media_app=true` + `default_music_source_v1=<目标>`，
每次都先把网易云/汽水/QQ音乐**全部 force-stop**（冷启动），再启动桌面。

### 6.1 网易云 —— 后台起播 ✅

```
════ 复位 ════
  ✓ 两者都未运行（网易云处于冷启动）
════ 启动桌面 → 触发自动播放 ════
════ 判据 1：前台 Activity ════
mResumedActivity: com.cardesk.launcher/.MainActivity          ← 桌面，不是网易云
════ 判据 2：网易云进程 ════
u0_a82  11684  …  com.netease.cloudmusic.iot                  ← 起来了
════ 判据 3：有没有走前台降级 ════
后台起播：系统枚举到的浏览器服务 pkg=com.netease.cloudmusic.iot
        services=com.netease.cloudmusic.iot.com.netease.cloudmusic.service.AutomotiveService
启动后唤起媒体 App：已发起后台起播，不拉前台
后台起播：浏览器服务已连接                                       ← 167ms
后台起播：已连上并下发播放                                       ← 769ms
（**没有**「已拉起（走前台）」这一行）
```

### 6.2 汽水 —— 有通道但连不上 → 降级 ✅

```
后台起播：系统枚举到的浏览器服务 pkg=com.luna.music.car
        services=com.luna.music.car.com.luna.player.impl.playerservice.MediaBrowserPlayerService
启动后唤起媒体 App：已发起后台起播，不拉前台
后台起播：连不上 reason=播放器不接受外部连接
后台起播：连不上，降级到拉前台
启动后唤起媒体 App：已拉起（后台通道不可用，走前台）
mResumedActivity: com.luna.music.car/.app.framework.ui.MainActivity
```

### 6.3 QQ 音乐 —— 无通道 → 直接走前台 ✅

```
后台起播：该播放器没有浏览器服务，只能拉前台 pkg=com.tencent.qqmusiccar
启动后唤起媒体 App：已拉起（后台通道不可用，走前台） pkg=com.tencent.qqmusiccar sourceId=qq
启动后唤起媒体 App：会话已到，已下发播放 pkg=com.tencent.qqmusiccar
mResumedActivity: com.tencent.qqmusiccar/.app.activity.AppStarterActivity
```

→ 会话照样等到、播放照样下发，**与第149轮行为一致**。

---

## 七、单测

| 测试类 | 用例数 | 结果 |
|---|---|---|
| `BackgroundPlaybackTest`（新增） | 13 | 全绿 |
| `BackgroundPlaybackLaunchTest`（新增） | 4 | 全绿 |
| `MediaAppLauncherTest`（原有，回归） | 12 | 全绿 |
| `MediaAppLauncherLaunchTest`（原有，回归） | 5 | 全绿 |

新增的 `BackgroundPlaybackLaunchTest` 锁住的核心断言：
**「后台通道可用时，一个 Activity 都不该被启动」** —— 这正是用户要的那条性质。

---

## 八、已知限制（只能装车验证）

1. **真车上「真的在放」尚未验证**。模拟器上网易云未登录、无播放列表，
   会话起来了但 `state=null`（播放器拒绝播放）。
   → 装车判据：开机后看桌面音乐卡有没有歌名、有没有出声，
   **且**全程没有网易云的界面闪一下。

2. **汽水的连接失败原因**在模拟器上是 `onGetRoot()` 返回 null；
   真车版本若有差异，表现可能不同 —— 但**降级路径保证不影响功能**。

3. **QQ 音乐仍是拉前台**。它没有 `MediaBrowserService`，
   第三方 AIDL（`QQMusicApiService`）能用但依赖对方的 AIDL 契约，
   本轮没做（收益/代价比不合适）。

4. 模拟器是 API 28；真车是 API 29，`MediaBrowser` 行为一致（同一套 AOSP 实现）。
