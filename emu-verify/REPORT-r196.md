# 第196轮 · 适配汽水音乐 + 未适配 App 的通用兜底（收藏爱心再放大）实测报告

**日期**：2026-11-17
**设备**：模拟器 `emulator-5560`（cardesk28，API 28 / Android 9，1920×1080 @160dpi，已 root）
**被测包**：`com.ecarx.cardesk`（system 变体，versionCode 4269 / versionName 1.0.1173-system）
**真实播放器**：汽水音乐车机版 `com.luna.music.car`、网易云车机版 `com.netease.cloudmusic.iot`、QQ 音乐车机版 `com.tencent.qqmusiccar`

---

## 一、结论先行

**汽水适配上了；通用兜底对「未适配的 App」实测有效；收藏爱心已放大。**

| 播放器 | 收藏 | 播放模式 | 通道 | 实测结果 |
|---|---|---|---|---|
| **汽水音乐车机版** | ✅ | ❌ | **标准 MediaSession**（`setRating` 心形评分） | 收藏**下发成功**，收藏态由 metadata 心形评分回报 |
| **网易云车机版** | ✅ | ✅ | 标准会话 `star_action` / `play_mode_action` | 收藏**下发成功**（且接住了播放器的结果事件），模式由 session event 回报 |
| **QQ 音乐车机版** | ✅ | ✅ | 第三方 URI 协议 | 回归通过（收藏 / 播放模式都下发成功） |
| **未适配的 App** | 探测 | 探测 | **通用兜底**（标准会话通道 + 运行时探测） | 实测有效：把汽水从能力表摘掉后，桌面靠探测照样认出来、照样能收藏 |

> **第195轮的结论被推翻了**：当时判定「汽水两项都不支持」，
> 本轮重新逆向找到了它的**标准评分回调** → 收藏其实一直是支持的。

---

## 二、汽水重新逆向（第195 轮结论是错的）

### 2.1 收藏：走标准评分通道 ✅

`com/luna/player/impl/remotecontrol/CoreRemoteControlListener.java`：

```java
public void onSetRating(RatingCompat ratingCompat) {
    if (ratingCompat == null) { super.onSetRating(ratingCompat); }
    else { handleCollectOrUnCollectCommand(ratingCompat.hasHeart()); }   // ← 真收藏
}
```

→ 汽水实现了 `MediaSession.Callback.onSetRating`，
桌面下发**标准心形评分**它就真的收藏。

### 2.2 播放模式：确认没有第三方可用的通道 ❌

| 位置 | 内容 |
|---|---|
| `com/luna/biz/command/LunaCommandKeys.java` | `SWITCH_PLAY_MODE` / `QUERY_PLAY_MODE` / `MANAGE_FAVORITE` |
| `com/luna/biz/command/impl/command/player/mode/SwitchPlayModeCommand.java` | 真正切模式的命令 |
| `com/luna/music/car/adaption/platform/BYDMediaListenerImpl.java` | 由 **BYD 语音**通道触发 |

会话回调基类 `RemoteControlListener.onCustomAction` **只打日志**（空实现）。

### 2.3 模拟器实测汽水的会话

```
$ adb shell dumpsys media_session | grep -A 20 com.luna.music.car

    Tag com.luna.music.car/Tag (userId=0)
      package=com.luna.music.car
      active=true
      flags=3
      rating type=1                                  ← 心形评分在
      state=PlaybackState {state=3, position=29288, buffered position=0,
             speed=1.0, updated=..., actions=950, custom actions=[],
             active item id=-1, error=null}           ← 没有 repeatMode / shuffleMode
      metadata:size=7, description=一见如故, 许嵩, 安泊猜想
      queueTitle=null, size=0
```

→ 收藏 ✅（有心形评分）、播放模式 ❌（没有模式字段，也没有 custom action）。

---

## 三、通用兜底：标准 MediaSession 通道 + 运行时探测

### 3.1 为什么是「标准通道」

桌面上还有一堆别的音乐 App，一家一家逆向不现实 →
只能靠 **Android 官方定义的标准会话入口**：

| 动作 | 标准入口 | 播放器的回调 |
|---|---|---|
| 收藏 | `setRating(心形评分)` | `MediaSession.Callback.onSetRating` |
| 播放模式 | `sendCustomAction(动作名, 参数)` | `MediaSession.Callback.onCustomAction` |

### 3.2 但不是无脑乱发 —— 靠运行时探测决定亮不亮

| 能力 | 判据 |
|---|---|
| 收藏 | 会话 metadata 里**有**心形评分（`METADATA_KEY_USER_RATING`）|
| 播放模式 | 会话 `PlaybackState` 的 `repeatMode` / `shuffleMode` **被设置过** |

探测不到 → **两个按钮都不亮**（亮一个「点了没反应」的按钮比没有更糟）。

判定入口：`MediaActions.capsOf(packageName, probe)`
—— 能力表里有 → 查表；表里没有 → 看探测结果。

### 3.3 踩到的坑：`REPEAT_MODE_UNSET` 是 API 29 才有的

「设置过播放模式」本来想用 `!= REPEAT_MODE_UNSET` 判，
但 **`REPEAT_MODE_UNSET`（-1）是 API 29 才加的**，
API 28 的 `PlaybackState` 默认值是 **NONE（0）**
→ 用 `!= UNSET` 判的话，**API 28 上每个会话都会「暴露」播放模式** → 按钮到处乱亮。

→ 统一按「**大于 0**」判定，并在 `MediaActions` 里自己定义
`RepeatMode` / `ShuffleMode` 常量（值与 framework 一致，纯 Kotlin、可单测）。

### 3.4 下发参数：两套表达一起给

```
play_mode     = 通用编码（顺序=1 / 单曲=2 / 随机=3）
repeat_mode   = PlaybackState.REPEAT_MODE_*（Android 标准值）
shuffle_mode  = PlaybackState.SHUFFLE_MODE_*（Android 标准值）
```

---

## 四、隐藏 API 代理层（`SessionProxy`）

第195轮这套「找代理 → 找方法 → 按签名拼参数」的逻辑写在 `NeteaseMediaActions` 私有方法里，
但它**不是网易云专有的** → 本轮抽成 `core/media/SessionProxy.kt`，两个执行层共用。

设备实测（API 28）能用的唯一入口仍然是会话控制代理：

| 找法 | 结果 |
|---|---|
| `MediaController.sendCustomAction` / `setRating` | **不存在** |
| `MediaController.mImpl` / `TransportControls.mImpl` | **不存在** |
| `MediaSession.Token.getMediaController()` | **不存在** |
| **`MediaSession.Token.mBinder`** | ✅ **它本身就是代理** |
| `MediaController.mSessionBinder` | ✅ 同一个代理的另一个入口 |

本轮新增：**评分入口的名字随版本变**
（有的版本叫 `rate(...)`，有的叫 `setRating(...)`）→ 两个都找。

实测这台设备上是 `rate`：

```
评分下发成功 方法=rate 参数个数=3 心形=true
```

---

## 五、界面上长什么样

### 5.1 收藏爱心放大（28×28 → 40×40）

| | 内容区 | 外框（点击热区）|
|---|---|---|
| 第195轮 | 28×28 | 48×56 |
| **第196轮** | **40×40** | 48×56（不变）|

外框不变 → 热区不变、版式不动，心形本身放大约 43%。

未收藏（空心描边）：

![未收藏](../.work/r196_soda_heart.png)

已收藏（实心强调色）：

![已收藏](../.work/r196_soda_heart_filled.png)

### 5.2 汽水：只有收藏按钮，播放模式按钮隐藏

![汽水](../.work/r196_soda_page2b.png)

（收藏心形在左下，**右下没有播放模式图标** —— 汽水没有对外的切模式通道。）

### 5.3 网易云：两个按钮都在

![网易云](../.work/r196_ne_btns.png)

---

## 六、端到端实测

### 6.1 汽水收藏（标准兜底通道）

```
$ adb shell input tap 1398 798

I/CarDesk/NavUI : [EVT] 导航页音乐卡收藏按钮 title=荒 favorited=false
I/CarDesk/Media : 评分下发成功 方法=rate 参数个数=3 心形=true
I/CarDesk/Media : [EVT] 下发收藏指令 pkg=com.luna.music.car favorited=true 通道=标准兜底 ok=true
I/CarDesk/Media : 收藏态变化 favorited=true pkg=com.luna.music.car
I/CarDesk/Media : [EVT] 收藏态变化 old=false new=true 乐观=false
```

`乐观=false` → 这是**汽水重发 metadata 带来的权威回报**，不是乐观更新。

取消收藏同样通：

```
[EVT] 下发收藏指令 pkg=com.luna.music.car favorited=false 通道=标准兜底 ok=true
评分下发成功 方法=rate 参数个数=3 心形=false
收藏态变化 favorited=false pkg=com.luna.music.car
```

### 6.2 通用兜底（模拟「未适配的 App」）

**做法**：临时把汽水从 `MediaActions.CAPS` 能力表里摘掉
（等价于「一个桌面没逆向过的音乐 App」），重新打包安装。

```
[EVT] 导航页音乐卡收藏按钮 title=富士山下 favorited=true
[EVT] 下发收藏指令 pkg=com.luna.music.car favorited=false 通道=标准兜底 ok=true
评分下发成功 方法=rate 参数个数=3 心形=false
收藏态变化 favorited=false pkg=com.luna.music.car
[EVT] 收藏态变化 old=true new=false 乐观=false
```

→ **表里没有的包，靠运行时探测（会话发了心形评分）照样认出来、照样能收藏**；
同时它的**播放模式按钮仍然是隐藏的**
（汽水没暴露模式字段 → 探不到 → 不亮）。

![探测路径](../.work/r196_probe_btns2.png)

> 验证完已把能力表**恢复原样**并重新打包安装。

### 6.3 网易云（收藏 + 取消收藏 + 播放模式）

```
$ adb shell input tap 1398 798
[EVT] 下发收藏指令 pkg=com.netease.cloudmusic.iot favorited=true 通道=网易云 ok=true
自定义动作下发成功 action=star_action 参数个数=4
收到网易云收藏结果 pkg=com.netease.cloudmusic.iot code=0 message=操作成功
[EVT] 收藏态变化 old=false new=true 乐观=false          ← 权威回报

$ adb shell input tap 1398 798     （取消）
[EVT] 下发收藏指令 pkg=com.netease.cloudmusic.iot favorited=false 通道=网易云 ok=true
收到网易云收藏结果 pkg=com.netease.cloudmusic.iot code=0 message=操作成功
[EVT] 收藏态变化 old=true new=false 乐观=false

$ adb shell input tap 1820 798     （切播放模式）
自定义动作下发成功 action=play_mode_action 参数个数=4
[EVT] 下发播放模式指令 pkg=com.netease.cloudmusic.iot mode=SHUFFLE 通道=网易云 ok=true
播放模式变化 code=2 pkg=com.netease.cloudmusic.iot        ← session event 回的
```

播放器侧也确认收到了：

```
I/MediaSessionManager_267146857: [onCustomAction] action=star_action
        extras=Bundle[mParcelledData.dataSize=32], threadId:2, threadName=main
```

### 6.4 QQ 音乐（回归）

```
[EVT] 下发收藏指令 pkg=com.tencent.qqmusiccar favorited=true 通道=QQ协议 ok=true
采纳 QQ 广播 method=update_fav module=play boundPkg=com.tencent.qqmusiccar
[EVT] 收藏态变化 old=unknown new=false 乐观=false
[EVT] 下发播放模式指令 pkg=com.tencent.qqmusiccar mode=REPEAT_ALL 通道=QQ协议 ok=true
采纳 QQ 广播 method=update_play_mode module=play boundPkg=com.tencent.qqmusiccar
```

---

## 七、顺手修掉的两个问题

### 7.1 网易云收藏态：接住播放器的**结果事件**

排障时发现网易云**进程内缓存的收藏态**和它发出来的 metadata **可能不一致**
（缓存 true、metadata false）→ 桌面按 metadata 判「未收藏」，
再发一次收藏指令，网易云回「已经是收藏状态了」→ **什么都不会变**，
界面却停在「未收藏」。

网易云处理完 `star_action` 会回一条同名 session event（`yc/s0.java`）：

| code | message | 含义 |
|---|---|---|
| 0 | 操作成功 | 生效 |
| -2 | 已经是收藏状态了 / 已经是未收藏状态了 | 幂等空操作（状态本来就是这样）|
| -1 | （App 里的失败文案） | 失败 |

→ 桌面现在接住这条回报：`0` / `-2` 都按**请求的目标态**校正，
其它（失败）**回滚**到下发前的状态。
判定逻辑在 `MediaActions.favoriteStateFromResult()`（纯逻辑，可单测）。

### 7.2 隐藏 API 代理层抽成公共文件

见第四节。

---

## 八、单测

| 项 | 结果 |
|---|---|
| `:app:testSystemUnitTest` | **2353 全绿**（0 失败 / 0 错误 / 0 跳过） |
| `MediaActionsTest` | 16 例 → **28 例** |

新增用例覆盖：

- 汽水能力表：收藏 ✅ / 播放模式 ❌；
- 探测判定：探测到心形评分 → 支持收藏；没探测到 → 都不支持；
- 已知包**不受**探测结果影响（能力表优先）；
- `exposesPlayMode`（> NONE 才算暴露，含 API 28 默认 NONE 的坑）；
- `playModeFromState`（随机优先于重复）；
- 通用编码 vs 网易云编码**不是一回事**；
- 通用下发的两套参数值一致；
- 收藏结果码 → 状态校正 / 失败返回 null。

---

## 九、已知限制

1. **通用兜底的播放模式是「自定约定」**：
   `com.cardesk.action.SET_PLAY_MODE` 没有行业标准，
   只对实现了 `onCustomAction` 且认这个约定的播放器有效。
   收藏走标准 `setRating` 才是**真正通用**的那条。
2. **通用兜底的收藏探测依赖「会话发布心形评分」**：
   播放器实现了 `onSetRating` 但不在 metadata 里发心形评分 → 探不到 → 按钮不亮。
3. **汽水的播放模式仍然隐藏**（进程内指令中心 + 语音通道，第三方接不上）。
4. **QQ 音乐的收藏是 toggle**：状态未知时第一次点击可能与预期相反。
5. **QQ 音乐不回报播放模式** → 桌面本地跟踪自己下发的最后一次。
6. **网易云的收藏态有两个来源**（metadata 心形评分 + `star_action` 结果事件），
   不一致时以**结果事件**为准。
7. 模拟器是 API 28；真车 API 29 的 `ISessionController` 签名不同
   （没有 `callingPackage`）→ 代码按**参数类型**自适应，不用改。

---

## 十、复现命令

```bash
cd /Users/roy/Documents/desktopapp/cardesk
export GRADLE_USER_HOME=/Users/roy/Documents/desktopapp/.gradle-home
export JAVA_HOME=/Users/roy/Documents/desktopapp/toolchain/jdk-17.0.20.1+1/Contents/Home
/Users/roy/Documents/desktopapp/toolchain/gradle-8.7/bin/gradle \
    :app:testSystemUnitTest :app:assembleSystem --offline -PcardeskPackage=com.ecarx.cardesk

adb -s emulator-5560 install -r app/build/outputs/apk/system/app-system.apk
adb -s emulator-5560 shell am start -n com.ecarx.cardesk/com.cardesk.launcher.MainActivity

# 切到汽水
adb -s emulator-5560 shell am force-stop com.netease.cloudmusic.iot
adb -s emulator-5560 shell am force-stop com.tencent.qqmusiccar
adb -s emulator-5560 shell monkey -p com.luna.music.car -c android.intent.category.LAUNCHER 1

# 滑到第二页（模拟器窗口要在前台才收得到手势）
adb -s emulator-5560 shell input swipe 1500 300 500 300 500
adb -s emulator-5560 shell input tap 1398 798     # 收藏（左下）
adb -s emulator-5560 shell input tap 1820 798     # 播放模式（右下）

adb -s emulator-5560 logcat -v time | grep -E "EVT|评分下发|自定义动作|收藏结果"
```
