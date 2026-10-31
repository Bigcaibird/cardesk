# 第八十八轮 · 「暂停后按播放恢复不了」真车缺陷修复

日期：2026-10-31　设备：模拟器 emulator-5554（1920×1080@160dpi，sdk 29，test-keys）
验证包：`com.cardesk.r88verify`（`-PcardeskPackage=com.cardesk.r88verify -PcardeskVersionCode=9001` 的 `assembleSystem`）

## 1. 用户诉求

> 音乐播放器暂停后还是不能恢复播放，对照UNI看看怎么修复

「还是」= 老问题。第八十七轮把播放/暂停做成「幂等 `setPlaying(desired)`」之后，
用户侧表现为：**暂停后按播放键没有任何反应**，要么界面不变，要么会话停在暂停。

## 2. 真车证据（工作区里的历史日志包）

把 `cardesk-logs-*.zip`（真车 = Android 9 / SDK 28 / 1920×944 / ECARX dcy11 /
1.0.1046 code 4142）解包后 grep 得到两类现场：

**A. 会话状态滞后于界面（`cardesk-logs-20261024-150545`）**

```
15:03:07.955 播放/暂停指令 pkg=com.tencent.wecarflow from=true to=false state=3
15:03:08.065 播放状态变化 pkg=com.tencent.wecarflow playing=false positionMs=0
15:03:09.213 播放/暂停指令 pkg=com.tencent.wecarflow from=false to=true state=2
```

即：第二次按键时桌面读到的 `state` 还是 **3（PLAYING）**，于是按「切换」算出的
目标态仍是 **暂停**，又发了一条暂停；用户看到的就是「按播放没反应」。

**B. 会话不声明 action 位 / 恢复瞬间会话消失**

```
15:03:09.240 车机多媒体播放：补发媒体按键
15:03:09.281 播放状态变化 pkg=com.tencent.wecarflow playing=true positionMs=0
15:03:09.649 车机多媒体播放兜底重试 reason=dmt-resume+400 → 按键 PLAY
15:03:10.050 车机多媒体播放兜底重试 reason=dmt-resume+1000 → 按键 PLAY
15:03:10.141 会话候选变化 count=1 usable=1 detail=pkg=com.android.bluetooth   ← wecarflow 会话整体消失
```

恢复瞬间一次性齐发 `transportControls.play()` + 全局 `KEYCODE_MEDIA_PLAY` +
定向 `MEDIA_BUTTON` 广播（再加两轮兜底），把会话打没了。参考工程
`uni-launcher/.../integration/MediaAdapter.java` 的控制通道**只有** `transportControls`
一条（只有网易云走 `dispatchMediaButtonEvent`），这一点是对照 UNI 的直接结论。

## 3. 根因

两条叠加，都是第八十七轮「幂等化」留下的：

- **R1 切换基准取了会话实测态**：`playPause()` 用 `resolvePlaying(用户意图窗口, 实测)`
  的结果当基准。界面已经暂停（乐观 UI），会话还报 PLAYING → 目标态算成「暂停」→
  再发一次暂停 → 永远恢复不了。
- **R2 `no-action` 闸门把用户操作整条吞掉**：`TransportIntent.shouldDispatch` 当时是
  `decision.send || (force && reason == already-satisfied)`，`no-action` 一律不下发。
  爱趣听/车机多媒体经常不声明 `ACTION_PLAY`（甚至 `actions == 0` 且 flags 为 0，
  `effectiveActions` 的补位也不生效）→ 用户按下的播放一个字节都没发。

附带 R3：DMT 恢复一次性齐发三条通道 + 两轮兜底，真车日志显示会话被打消失。

## 4. 修复

| 文件 | 改动 |
| --- | --- |
| `app/src/main/java/com/cardesk/launcher/core/media/TransportIntent.kt` | ①`shouldDispatch = decision.send \|\| reason == no-action \|\| force`；②新增 `Toggle(desiredPlaying, force, coalesced)` 与 `toggleFromDisplayed(displayed, observed, userIntentAtMs, nowMs, windowMs=800)`：**以界面显示态为基准**，实测与目标一致时 `force=true` 强制越过 `already-satisfied` |
| `app/src/main/java/com/cardesk/launcher/core/media/MusicSessionTracker.kt` | ①`playPause()` 改用 `current?.playing ?: observedPlaying` + `toggleFromDisplayed`，连点仲裁时记「不下发」；②`no-action` 时记 W 日志后**照常下发**；③`playTransport()` 改阶梯升级 `escalateDmtResume(1/2)`（t=0 只 transport，+400ms 补全局 PLAY 键，+1000ms 再补，仅爱趣听加定向广播），删除 `retryDmtResume` |
| `app/src/main/java/com/cardesk/launcher/core/dock/GlobalDockService.kt` | 全局 Dock 播放键改走 `MusicController.playPause()`（原实现 `setPlaying(!playing)` 的 `force` 恒 false，最容易被「已满足」吞掉） |
| `app/src/test/java/com/cardesk/launcher/core/media/TransportIntentTest.kt` | 旧语义用例换成 `shouldDispatch_onlyAlreadySatisfiedIsSkipped`，新增 4 个 `toggleFromDisplayed_*`（含 `staleSessionStillResumes`） |

**保持不变**：暂停仍走 `stop()` 优先（有历史真车事故记录）、网易云仍走定向按键、
无会话路径（`playWithoutSession` / `tryResumeDmtPlayback`）不动。

## 5. 单测

```
:app:testDebugUnitTest --tests 'com.cardesk.launcher.core.media.*'   → 350 tests / 0 fail
:app:testDebugUnitTest（全量）                                        → 1386 tests / 0 fail / 0 error（116 个测试类）
```

命令（仓库没有 `./gradlew`）：

```bash
cd /Users/roy/Documents/desktopapp/cardesk
GRADLE_USER_HOME=$PWD/../.gradle-home \
JAVA_HOME=/Users/roy/Documents/desktopapp/toolchain/jdk-17.0.20.1+1/Contents/Home \
/Users/roy/Documents/desktopapp/toolchain/gradle-8.7/bin/gradle :app:compileDebugKotlin --console=plain
```

## 6. 模拟器复现与验证

### 6.1 给探针加「真车缺陷模式」

`media-probe/app/src/main/java/com/cardesk/mediaprobe/MainActivity.kt` 新增两个 `--es` 参数：

- `actions=full|noPlay|none`：控制会话 `PlaybackState.setActions()` 的内容；
- `lag=<ms>`：暂停后这段时间内**仍上报 STATE_PLAYING**（复现 R1）。

### 6.2 实验 A（R1：会话状态滞后）

```bash
adb shell am start -n com.cardesk.mediaprobe/.MainActivity \
  --es title 海阔天空 --es artist Beyond --es play true --es lag 60000
# 拉起 CarDesk → 点播放键（暂停；此时会话仍报 PLAYING）→ 等 >800ms → 再点播放
```

日志（新代码，目标态 force=true 强推，探针恢复播放）：

```
I CarDesk/Media: 播放/暂停指令（切换） pkg=com.cardesk.mediaprobe state=3 displayed=false observed=true desired=true force=true
I CarDesk/Media: 播放/暂停指令（幂等） pkg=com.cardesk.mediaprobe desired=true state=3 from=false reason=already-satisfied force=true actions=775 buttons=true transport=true netease=false
```

`dumpsys media_session`：`state=3, speed=1.0`，position 继续前进（确实恢复播放）。
截图：`emu-verify/r88-stale-paused.png`（界面已暂停、会话仍报 PLAYING）、
`emu-verify/r88-resumed.png`（恢复播放）。**旧代码在这一步会算成 `desired=false` 再发暂停。**

### 6.3 实验 B（R2：不声明 action 位）

```bash
adb shell am start -n com.cardesk.mediaprobe/.MainActivity --es play false --es actions none
# 点 CarDesk 播放键
```

日志（不再「不下发」，而是补位下发并真的开始播放）：

```
I CarDesk/Media: 播放/暂停指令（切换） pkg=com.cardesk.mediaprobe state=2 displayed=false observed=false desired=true force=false
I CarDesk/Media: 播放/暂停指令（幂等） pkg=com.cardesk.mediaprobe desired=true state=2 from=false reason=send-play force=false actions=0 buttons=true transport=true netease=false
```

`dumpsys media_session` 由 `state=2` → `state=3`。

### 6.4 连点与暂停回归

- 连点播放键两次（间隔 120ms）：`dumpsys` 前后状态一致（净不变），日志两次下发、
  无第三次；500ms 内重复触摸命中 800ms 仲裁窗口时记「播放/暂停指令（连点仲裁）：不下发」。
- 暂停：`播放/暂停指令（幂等） … reason=send-pause` → `state=3 → 2`。
- 全程 `logcat` 无 `FATAL EXCEPTION`、无新增 tombstone、进程 pid 不变。

## 7. 复跑命令

```bash
# 构建验证包（不影响其他会话在用的 com.ecarx.cardesk）
GRADLE_USER_HOME=/Users/roy/Documents/desktopapp/.gradle-home \
JAVA_HOME=/Users/roy/Documents/desktopapp/toolchain/jdk-17.0.20.1+1/Contents/Home \
/Users/roy/Documents/desktopapp/toolchain/gradle-8.7/bin/gradle \
  :app:assembleSystem -PcardeskPackage=com.cardesk.r88verify -PcardeskVersionCode=9001

adb -s emulator-5554 install -r app/build/outputs/apk/system/app-system.apk
adb -s emulator-5554 shell cmd notification allow_listener \
  com.cardesk.r88verify/com.cardesk.launcher.core.media.MediaNotificationListener
# 必须先停掉 UNI 的全局 Dock 悬浮窗，否则它盖住底部 112px 吃掉触摸
adb -s emulator-5554 shell am force-stop xyz.i4o.uni.launcher
```

## 8. 局限与待验

- 真车播放器的实际行为（`stop()` 后 `PlaybackState` 多久追上、`actions` 到底有没有位）
  是用日志倒推的，**没有在真车上重跑**；本次只在模拟器上用探针复现了两种缺陷模式。
- `pauseTransport` 的「DMT 优先 `stop()`」**本轮没动**（历史真车事故记录要求保留），
  如果真车上「暂停」本身也不稳，那是另一条线。
- 同一工作树另有会话在做氛围灯重构，本轮**只提交媒体/Dock 相关 4 个文件 + 本文档/README**，
  没有动 `core/ambient/**`、`core/Prefs.kt`、`ui/SettingsOverlay.kt`、`tools/release.sh`。
