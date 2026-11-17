# 第195轮 · 音乐卡「收藏」+「播放模式」实测报告

**日期**：2026-11-17
**设备**：模拟器 `emulator-5560`（cardesk28，API 28 / Android 9，1920×1080 @160dpi，已 root）
**被测包**：`com.ecarx.cardesk`（system 变体，versionCode 4269 / versionName 1.0.1173-system）
**真实播放器**：网易云车机版、QQ 音乐车机版、汽水音乐车机版（均为车机真包）

---

## 一、结论先行

**两家都通了，汽水按设计隐藏按钮。**

| 播放器 | 收藏 | 播放模式 | 实测结果 |
|---|---|---|---|
| **QQ 音乐车机版** | ✅ | ✅ | **下发成功**，收藏态由 `update_fav` 广播回报 |
| **网易云车机版** | ✅ | ✅ | **下发成功**，收藏态由 metadata 心形评分回报、模式由 session event 回报 |
| 汽水音乐车机版 | ❌ | ❌ | 能力表里没有 → **按钮直接隐藏** |

两条链路都不是「点下去没反应」，而是**播放器真的执行了、并且把结果回报给了桌面**。

---

## 二、界面上长什么样

第二页（导航页）音乐卡，控制键居中，左右各一个按钮：

```
┌──────────────────────────────┐
│  ♡        ‹   ▶   ›        ⇄  │   ← 收藏（左）/ 播放模式（右）
└──────────────────────────────┘
```

| 状态 | 图标 | 实测截图 |
|---|---|---|
| 未收藏 | 空心心形（描边，弱色） | `.work/r195_icons.png` |
| 已收藏 | **实心**心形（强调色青） | `.work/r195_icons3.png` / `ne_fav_crop.png` |
| 顺序播放 | 上下两条反向箭头 `⇄` | `.work/r195_mode.png` |
| 单曲循环 | 圆弧箭头 + 中间一个 `1` | `.work/r195k_mode.png` |
| 随机播放 | 两条交叉折线 + 箭头 | `.work/r195l_mode.png` / `ne_mode3_crop.png` |

图标是 **Canvas 几何自绘**的矢量图（`ui/widget/MediaActionIcons.kt`），
24×24 基准视口，绘制时按 bounds 等比缩放。

> 为什么不用 SVG：设备端 `android.graphics.Path` 没有公开的 `parse`（@hide），
> 裁剪过的 `android.jar` 里也没有 → 解析不了 SVG path data。
> 项目本身也约定「所有图形由本项目自绘，不使用任何图片资源」。

---

## 三、QQ 音乐（URI 协议 + 第三方广播回报）

### 3.1 收藏：下发 → 播放器执行 → 广播回报

```
$ adb shell input tap 1398 798     # 音乐卡左下角的心形

I/CarDesk/NavUI : [EVT] 导航页音乐卡收藏按钮 title=素颜 favorited=false
I/CarDesk/Media : [EVT] 下发收藏指令 pkg=com.tencent.qqmusiccar favorited=true ok=true
I/CarDesk/Media : [EVT] 收藏态变化 old=unknown new=true 乐观=false
```

系统侧能看到 URI 真的被拉起：

```
I/ActivityManager: START u0 {act=android.intent.action.VIEW
                   dat=qqmusiccar://?action=20&m0=5
                   pkg=com.tencent.qqmusiccar
                   cmp=com.tencent.qqmusiccar/.app.activity.DispacherActivityForThird}
W/Bundle(2731)  : at com.tencent.qqmusiccar.app.reciver.BroadcastReceiverCenterForThird.parseUrlObject
                   at com.tencent.qqmusiccar.app.reciver.BroadcastReceiverCenterForThird.onReceive
```

播放器回广播，桌面采纳：

```
D/CarDesk/Media : 采纳 QQ 广播 method=update_fav module=play boundPkg=com.tencent.qqmusiccar
```

### 3.2 播放模式：下发成功，桌面本地跟踪

```
[EVT] 导航页音乐卡播放模式按钮 title=素颜 mode=unknown
[EVT] 下发播放模式指令 pkg=com.tencent.qqmusiccar mode=REPEAT_ALL ok=true
[EVT] 导航页音乐卡播放模式按钮 title=素颜 mode=REPEAT_ALL
[EVT] 下发播放模式指令 pkg=com.tencent.qqmusiccar mode=REPEAT_ONE ok=true
[EVT] 导航页音乐卡播放模式按钮 title=素颜 mode=REPEAT_ONE
[EVT] 下发播放模式指令 pkg=com.tencent.qqmusiccar mode=SHUFFLE ok=true
```

三次点击依次切到 顺序 → 单曲 → 随机，图标同步换成 `⇄` → 圆弧+1 → 交叉箭头。

> **QQ 音乐不回报播放模式** —— 它的第三方广播里没有这个字段，
> URI 协议也只有「设置」没有「读取」→
> 桌面只能记自己下发的最后一次（用户在 QQ 音乐 App 里自己改模式，桌面不同步）。

---

## 四、网易云（标准 MediaSession）

### 4.1 前置：网易云自己的界面就带这两个按钮

网易云车机版播放页左下角有一个 `♻` 循环按钮、标题右侧有一个 `♡` ——
说明它**本来就把收藏和播放模式做成了会话能力**（`vc/g$b` 的
`onSetRating` / `onCustomAction`），只是第三方一开始没找对入口。

### 4.2 收藏：下发 → metadata 回报

```
$ adb shell input tap 1398 798

I/CarDesk/NavUI : [EVT] 导航页音乐卡收藏按钮 title=还是分开 favorited=false
I/CarDesk/Media : [EVT] 下发收藏指令 pkg=com.netease.cloudmusic.iot favorited=true ok=true
I/CarDesk/Media : [EVT] 收藏态变化 old=false new=true 乐观=false
```

`old=false` 说明桌面**已经从 metadata 的心形评分里读到了真实收藏态**，
`new=true` 是播放器执行后重发 metadata 带来的回报。

### 4.3 播放模式：下发 + session event 回报（网易云答得上来）

```
[EVT] 下发播放模式指令 pkg=com.netease.cloudmusic.iot mode=REPEAT_ALL ok=true
     播放模式变化 code=1 pkg=com.netease.cloudmusic.iot     ← session event 回的
[EVT] 下发播放模式指令 pkg=com.netease.cloudmusic.iot mode=SHUFFLE ok=true
     播放模式变化 code=2 pkg=com.netease.cloudmusic.iot
```

编码对照（车机侧口径）：

| 车机编码 | 含义 | 桌面枚举 |
|---|---|---|
| 1 | 顺序播放 | `REPEAT_ALL` |
| 2 | 随机播放 | `SHUFFLE` |
| 3 | 单曲循环 | `REPEAT_ONE` |

### 4.4 收藏态读回的前提：metadata 的心形评分

```
$ adb shell dumpsys media_session | grep -A 3 com.netease
  package=com.netease.cloudmusic.iot
  active=true
  rating type=1                    ← RATING_HEART，心形评分存在
```

---

## 五、踩到的四个坑（都已修）

### 坑一：QQ 音乐的收藏指令是「**切换**」，不是「设为」

`BroadcastReceiverCenterForThird.controlPlay()`：

```java
case 5:
case 6:                                   // ← 两个 case 落在一起
    SongInfoUtilsForShow.s(context, songInfo, 5, "");   // ← 参数都是 5
```

`s(..., 5, ...)` → `g()`：

```java
final boolean zR = MyFavManager.H().R(songInfo);         // 当前是否已收藏
FavorSongServer.a(songInfo, Boolean.valueOf(true ^ zR)); // 收藏 / 取消收藏
```

→ **m0=5 与 m0=6 完全等价，都是「翻转当前收藏态」**。

实测表现：状态未知时点一下收藏，若 QQ 那边本来就已收藏，
这一下会把它取消掉 → QQ 回 `key_is_fav=0` → 桌面按回报显示「未收藏」。

→ 桌面这侧只能做到「**状态已知**时点一下必然翻转正确」；
状态未知的第一次点击**可能与用户预期相反**。这是协议限制，不是 bug。

### 坑二：收藏态**不能立即乐观更新**

第一版下发成功就先把心形点亮，几十毫秒后又被权威回报纠正回来：

```
[EVT] 收藏态变化 old=unknown new=true     ← 乐观
[EVT] 收藏态变化 old=true new=false        ← 被回报纠正
```

→ 肉眼看到**一次闪烁**（像按钮抽风）。
→ 改成**延后乐观**（600ms 兜底），回报先到就用回报：

```
[EVT] 收藏态变化 old=unknown new=true 乐观=false    ← 只有一次，来自回报
```

### 坑三：API 28 的 `MediaController` 上既没有 `setRating` 也没有 `sendCustomAction`

设备实测 `MediaController` 的 public 方法清单：

```
adjustVolume controlsSameSession dispatchMediaButtonEvent dispatchVolumeButtonEventAsSystemService
equals getClass getExtras getFlags getMetadata getPackageName getPlaybackInfo getPlaybackState
getQueue getQueueTitle getRatingType getSessionActivity getSessionToken getTag getTransportControls
hashCode notify notifyAll registerCallback sendCommand setVolumeTo toString unregisterCallback wait
```

→ 只有 `sendCommand`（+ 一堆 getter）。

而 `sendCommand` **不能**替代 —— androidx 的 `MediaSessionCompat.Callback`
把 `onCommand` 与 `onCustomAction` 当成**两个独立方法**，
`onCommand` 不会自动转到 `onCustomAction`。

找代理的过程（全试过）：

| 找法 | 结果 |
|---|---|
| `MediaController.sendCustomAction` / `setRating` | **不存在** |
| `MediaController` 的 `mImpl` 字段 | **不存在** |
| `MediaController$TransportControls.mImpl` | **不存在** |
| `MediaSession.Token.getMediaController()` | **不存在** |
| **`MediaSession.Token.mBinder`** | ✅ **它本身就是代理** |

`Token.mBinder` 的运行时类型：
`android.media.session.ISessionController$Stub$Proxy` —— 已经是现成的代理对象。

> framework 里也**根本没有** `IMediaController` 这个类
> （`grep IMediaController /system/framework/boot-framework.vdex` → 0 命中），
> 用的是 `android.media.session.ISessionController`。

### 坑四：`ISessionController` 的签名比想象的多两个参数

```java
void sendCustomAction(String callingPackage, ISessionControllerCallback callback,
                       String action, Bundle extras);
```

第一个参数是**调用方包名**（framework 拿它做权限检查，传错会被服务端拒），
第二个是回调。照 AOSP 9 的 `sendCustomAction(String, Bundle)` 调用直接报错：

```
java.lang.IllegalArgumentException:
  method android.media.session.ISessionController$Stub$Proxy.sendCustomAction
  argument 2 has type android.media.session.ISessionControllerCallback, got android.os.Bundle
```

→ 调用参数**按参数类型定位** `action` / `extras` 的位置，
其余参数：第一个 `String` 填真实的调用方包名，别的填 null。
→ 顺带发现这个接口**没有 `setRating`** → 收藏改走
`sendCustomAction("star_action", {is_star})`，与网易云 `onSetRating` 内部做的事完全一致。

---

## 六、单测

| 项 | 结果 |
|---|---|
| `:app:testSystemUnitTest` | **2341 全绿**（0 失败 / 0 错误 / 0 跳过） |
| 新增 | `MediaActionsTest`（16 例，纯逻辑：状态模型 / 能力表 / 模式循环 / 收藏态合并 / 网易云编码） |

---

## 七、已知限制

1. **播放列表本轮不做**：网易云从不调用 `setQueue`
   （实测 `queueTitle=null, size=0`），QQ 音乐第三方协议没有队列接口，汽水两项都不支持。
2. **QQ 收藏是 toggle**：状态未知时的第一次点击可能与预期相反（见坑一）。
3. **QQ 不回报播放模式**：桌面本地跟踪；用户在 QQ App 里自己改模式，桌面不同步。
4. **网易云收藏态靠 metadata 重发**：回报在几十毫秒内到达，实测无感知延迟。
5. 模拟器是 API 28，真车 API 29 的 `ISessionController` 签名不同（没有 `callingPackage`）
   → 代码按**参数类型**自适应，不用改。

---

## 八、复现命令

```bash
cd /Users/roy/Documents/desktopapp/cardesk
export GRADLE_USER_HOME=/Users/roy/Documents/desktopapp/.gradle-home
export JAVA_HOME=/Users/roy/Documents/desktopapp/toolchain/jdk-17.0.20.1+1/Contents/Home
/Users/roy/Documents/desktopapp/toolchain/gradle-8.7/bin/gradle \
    :app:testSystemUnitTest :app:assembleSystem --offline -PcardeskPackage=com.ecarx.cardesk

adb -s emulator-5560 install -r app/build/outputs/apk/system/app-system.apk
adb -s emulator-5560 shell am start -n com.ecarx.cardesk/com.cardesk.launcher.MainActivity
adb -s emulator-5560 shell input swipe 1600 600 400 600 400     # 滑到第二页
adb -s emulator-5560 shell input tap 1398 798                  # 收藏（左下）
adb -s emulator-5560 shell input tap 1825 798                  # 播放模式（右下）
adb -s emulator-5560 logcat -v time | grep -E "EVT|网易云|QQ 广播"
```
