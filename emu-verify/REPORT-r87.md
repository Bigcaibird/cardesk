# 第八十七轮 · 照参考工程 UNI Launcher 补「播放/暂停」与「全局 Dock」

> 诉求原文：
> 1. 「参考一下 UNI-Luancher 关于播放器播放暂停的逻辑，优化一下 cardesk 相关模块」
> 2. 「参考 UNI dock 栏保持替换系统 dock 栏，并能在其他应用显示的逻辑，优化 cardesk dock 栏」
>
> 日期：2026-10-31　被测包：`com.ecarx.cardesk` **4158** / `1.0.1062-system`
> 设备：`emulator-5554`（AVD `cardesk`，android-10 / API 29 / 1920×1080@160dpi / test-keys）
> APK：`dist/CarDesk-ecarx-cardesk-4158.apk`，2,033,622 bytes，
> SHA-256 `295e8d272ecdbe2e1656c15bfd7b18ed2914a17a542c1de5a65d1774f65d75d1`
> 参考实现：`uni-launcher/app/android/app/src/main/java/xyz/i4o/uni/launcher/`
> （`integration/MediaAdapter.java` 246 行、`integration/NeteaseMediaAdapter.java` 179 行、
> `integration/OfficialMediaBridge.java` 250 行、`GlobalDockService.java` 458 行、
> `overlay/NavigationInsets.java` 98 行、`overlay/SystemNavigationPolicy.java` 43 行）

---

## 0. 一句话结论

| 诉求 | 做了什么 | 状态 |
|---|---|---|
| 播放/暂停 | ①幂等 `setPlaying(desired)`（已满足不下发）②原厂音源「有回调没 setActions」补位 ③网易云定向按键通道 ④**方控/仪表 MediaCenter 桥**（原来的死开关接上了） | 代码 + 单测 + 模拟器实测通过；桥的真车链路待验 |
| Dock 栏 | 前台服务 + 跨应用常驻覆盖窗（112px），原车导航栏两条 policy 分支、桌面回前台自动隐身、长按主页 = 返回、设置页可开关 | 模拟器实测通过；「系统真让出 112px」的 RRO 一档受 ROM 限制，见 §5 |
| 单测 | **1295 tests / 0 failures / 0 errors**（含新增 55 例：TransportIntent 12、MediaButtonTransport 15、MediaBridgePolicy 10、SystemNavigationPolicy 18、NavInsetsAsset 4） | ✅ |
| 稳定性 | `check 15`：pid 不变、新墓碑 0、FATAL 0 | ✅ |

---

## 1. 参考工程里到底是怎么做的（对照表）

| 机制 | UNI Launcher | cardesk 第八十六轮之前 | cardesk 第八十七轮 |
|---|---|---|---|
| 播放/暂停下发 | `setPlaying(boolean)`：读当前 `PlaybackState`，**desired == actual 就不发**，只发一次 `toggle-play` | `playPause()` 每次无条件下发（连点 = 翻两次） | `TransportIntent.decide(observed, desired, actions)` 决策后才发；`setPlaying(desired, force)` 唯一入口 |
| 会话观察 | 对**所有**活跃会话注册 callback（「播放器状态可能在不出现在活跃列表变化时改变」） | 只对选中的那条注册 callback + 2.5s 重扫 | 保持原状（本身已经比 UNI 细），只补决策层 |
| 网易云 | `PlaybackState` 没有 `setActions()` ⇒ 按 `FLAG_HANDLES_MEDIA_BUTTONS` 判能力，`dispatchMediaButtonEvent(KeyEvent)` **DOWN + UP**（它只消费 UP） | 走 `transportControls`，失败才全局按键 | 新增 `MediaButtonTransport`：网易云定向按键通道；**并把「缺 actions」补位推广到所有原厂音源** |
| 方控 / 仪表 | `OfficialMediaBridge`：反射注册进原车 MediaCenter（`ONLINE = 6`），回调 `onPlay/onPause/onNext/onPrevious` 转回播放器 | 设置页有「桥接开关」，但**没有任何消费者**（死开关） | `core/media/OfficialMediaBridge.kt` + `MediaBridgePolicy.kt`，开关接上、**即时生效**、无 SDK 优雅降级 |
| 系统 Dock 替换 | RRO 把系统 `navigation_bar_height` 改成 96dp（四套 qualifier），`IOverlayManager` 启用；`policy_control` 只摘状态栏；窗口 `TYPE_NAVIGATION_BAR_PANEL` | 用 `policy_control immersive.navigation` 把原车 Dock 整条摘掉，**其它应用底部完全空白** | 前台服务 + 覆盖窗；有 RRO 走 2024 精确让位，没 RRO 走 2038 盖住原车 Dock；两条分支都在运行时判 |
| Dock 跨应用 | `GlobalDockService`（458 行）：一个常驻底栏窗 + 一个复用面板窗，各自一个 FlutterEngine；面板打开时前台任务变了就收起 | 只有 HOME 进程内的 `View`，离开桌面就看不到 | `GlobalDockService`（734 行）+ `GlobalDockPolicyGate`；**HOME 回前台自动隐藏**，离开桌面才显示（避免双 Dock） |
| 返回入口 | 面板走 Flutter 的 `popRoute`；底栏常驻时靠 `system-back` 命令 | 原车导航栏被摘掉后**没有返回键** | 长按主页键 = `Instrumentation.sendKeyDownUpSync(KEYCODE_BACK)`（后台线程；缺 INJECT_EVENTS 时 toast） |

**刻意没有照抄的**：UNI 的底栏内容用 Flutter 画（要跑两个 FlutterEngine），cardesk 的
`DockBarView` 是自绘 View —— 直接复用同一个 `DockBarView` 类，不需要第二个渲染引擎，
也不会有「窗口尺寸一变就重建纹理」的坑。

---

## 2. 媒体：四个改动点

### 2.1 幂等决策器（`core/media/TransportIntent.kt`，纯逻辑）

```
decide(observedState, desiredPlaying, actions) -> Decision(send, desiredPlaying, reason)
```

- `reason = already-satisfied` ⇒ **不下发**；`no-action`（播放器不支持）⇒ **不下发且 force 也越不过去**；
- 用户意图窗口 800ms（`nextToggleTarget`）：连点是「翻一次」而不是「互相抵消」；
- 日志（`CarDesk/Media`）：`播放/暂停指令（幂等）` / `播放/暂停指令（幂等）：不下发`，
  带 `desired / state / force / actions / buttons / transport / netease` —— 现场排障直接看这一行。

### 2.2 原厂音源补位（同文件 + `MediaButtonTransport.effectiveActions`）

参考工程这条补丁是专门给网易云写的（它「实现了回调但没 `setActions()`」）。
cardesk 把它**推广**：`actions == 0` 且带 `FLAG_HANDLES_TRANSPORT_CONTROLS`
或 `FLAG_HANDLES_MEDIA_BUTTONS` ⇒ 只补 `ACTION_PLAY | ACTION_PAUSE`。
不补的后果很实在：DMT / mediacenter / 爱趣听这类原厂音源会被「幂等」这一层直接挡死
（老实现是无条件下发，反而「能用」）。

### 2.3 网易云定向按键通道（`core/media/MediaButtonTransport.kt`）

- 包名 `com.netease.cloudmusic.iot`；
- `dispatchMediaButtonEvent(KeyEvent DOWN)` + `(KeyEvent UP)`，**两条都要**（它只消费 UP）；
- 返回值是「派发是否被接受」，**不是**「播放态变了」⇒ 不据此重试（否则二次翻转）；
- 能力位按 `FLAG_HANDLES_MEDIA_BUTTONS` 判。

### 2.4 方控 / 仪表桥（`core/media/OfficialMediaBridge.kt` + `MediaBridgePolicy.kt`）

- 开关：设置页「方控仪表 → 桥接开关」= `session_bridge_enabled`（默认 false），
  **第八十七轮补了即时生效**（`MusicController.syncBridgePreference()`）——
  以前只有桌面启动时读一次，用户开了开关得重启桌面才生效；
- 只接管三方在线播放器白名单（网易云车机版 / QQ 音乐车机版 / QQ 音乐），
  原车电台 / USB / 蓝牙仍归原车；
- 反射 `com.ecarx.sdk.mediacenter.MediaCenterAPI` / `IMusicClient` / `IMusicPlaybackInfo` / `IMediaInfo`，
  bind `ecarx.xsf.MEDIA_CENTER_SERVICE` → `ecarx.xsf.mediacenter.MediaCenterService`，
  `registerMusic` + `updateMediaSourceTypeList(ONLINE = 6)`；
- **UI 线程零 Binder**：所有原车调用走单独一条线程，同步 getter 只读不可变 `Sample`；
- 源暂时消失时保留最后采样 2.5s（仪表不闪）；进度推送 1s 节流；
- 无权限 / 无 SDK / 服务不在：`runCatching` 收口，只记 reason（见 §4.4），**不影响普通播放控制**。

---

## 3. Dock：全局覆盖窗怎么工作

### 3.1 窗口

| 项 | 值 |
|---|---|
| 窗口类型 | `NavInsets.applied()` ? `TYPE_NAVIGATION_BAR_PANEL`(2024) : `TYPE_APPLICATION_OVERLAY`(2038)（2024 addView 被拒自动退 2038） |
| 几何 | 宽 = 屏宽，高 = `round(宽 × 112 / 1920)` = **112px**，`y = 屏高 − 高` |
| flags | `NOT_FOCUSABLE`、`NOT_TOUCH_MODAL`、`LAYOUT_IN_SCREEN`、`LAYOUT_NO_LIMITS`、`HARDWARE_ACCELERATED` |
| 内容 | 不透明底 `#FF162E2C` + 1920×112 设计层等比缩放 + 复用 `DockBarView`(1872×104, leftMargin 24) |
| 生命周期 | `addView` 只在启动时一次，之后**只切 `root.visibility`**（不重建 Surface） |
| 隐藏条件 | 桌面自己在前台（`MainActivity.onResume/onPause`）—— 不留双 Dock |

### 3.2 policy 两条分支（`GlobalDockPolicyGate`）

| 条件 | policy | 窗口类型 | 效果 |
|---|---|---|---|
| `globalDockEnabled && NavInsets.applied()` | `SystemNavigationPolicy.apply(...)`（**只摘状态栏、永不写 `immersive.navigation/full`**） | 2024 | 系统真预留 112px，Dock 正好落在那条带上 |
| `globalDockEnabled && !applied()` | `OemDockPolicy.buildPolicyValue(hideDock=true, …)` = `immersive.navigation=*` | 2038 | 原车导航栏整条不画，Dock 盖住底部 112px |
| 关闭 / 服务销毁 | **无条件**恢复「没有全局 Dock 时该有」的 policy | — | 不会留下「既无原车 Dock 也无 CarDesk Dock」 |

### 3.3 交互

- 短按主页 / 应用 / 分屏 / 360 / 媒体卡 / 气候 → HOME intent + `MainActivity.EXTRA_ACTION`
  （`drawer|apps|climate|media|split|split_long|camera360`），回桌面并直接落到对应页；
- 空调直接调 `VehicleController`、媒体调 `MusicController`（**不依赖桌面在前台**）；
- 快捷应用直接 `startActivity`；
- **长按主页 = 返回**：后台线程 `Instrumentation.sendKeyDownUpSync(KEYCODE_BACK)`，
  缺 `INJECT_EVENTS` 时 toast「缺少按键注入权限，无法返回」；
- 刷新：`MusicController.addListener` + `VehicleController.addObserver` + 1s tick，
  `onDestroy` 全部移除（防泄漏）。

---

## 4. 模拟器核验

### 4.1 打开全局 Dock（无人值守脚本）

```bash
python3 tools/r87_verify.py prep        # adb root + appops SYSTEM_ALERT_WINDOW allow + 基线打印
python3 tools/r87_verify.py dock-on     # 改 prefs global_dock_enabled=true → 截 r87-globaldock-on-home
python3 tools/r87_verify.py other       # 起 com.android.settings → 截 r87-globaldock-other
python3 tools/r87_verify.py home        # 回桌面 → 截 r87-globaldock-home
python3 tools/r87_verify.py check 15    # pid / 墓碑 / FATAL
python3 tools/r87_verify.py dock-off    # 关掉 → 截 r87-globaldock-off-home
```

| 状态 | `settings get global policy_control` | NavigationBar0 | 截图 |
|---|---|---|---|
| 关（基线） | `immersive.navigation=com.ecarx.cardesk` | `shown=true` `1920×56` | `r87-globaldock-off-home.png` |
| 开 + 桌面前台 | `immersive.navigation=*` | `shown=false` | `r87-globaldock-on-home.png`（桌面自带 Dock，无双 Dock） |
| 开 + Settings 前台 | `immersive.navigation=*` | `shown=false` | **`r87-globaldock-other.png`（底部 112px 处是 CarDesk Dock）** |
| 关（恢复后） | `immersive.navigation=com.ecarx.cardesk` | `shown=true`（原车 3 键导航回来） | — |

窗口实测（`dumpsys window windows`）：

```
Window{b487964 u0 CarDesk 全局 Dock}
  mAttrs={(0,968)(1920x112) gr=TOP LEFT CENTER ty=APPLICATION_OVERLAY fmt=TRANSLUCENT
          fl=NOT_FOCUSABLE NOT_TOUCH_MODAL LAYOUT_IN_SCREEN LAYOUT_NO_LIMITS HARDWARE_ACCELERATED}
  # 桌面回前台后同一窗口： isVisible=false  mDrawState=NO_SURFACE
```

前台服务与通知：

```
ServiceRecord com.ecarx.cardesk/com.cardesk.launcher.core.dock.GlobalDockService
isForeground=true foregroundId=753
Notification(channel=cardesk_global_dock mName=全局 Dock actions=1 contentIntent=startActivity)
```

### 4.2 跨应用按键实测（Settings 在前台）

| 动作 | 命令 | 结果 |
|---|---|---|
| 回桌面 | `input tap 100 1018` | `mResumedActivity` = `com.ecarx.cardesk/.MainActivity` ✅ |
| 分屏页 | `input tap 218 1018` | 回桌面且日志 `Dock 可见性 … page=1`（`EXTRA_ACTION` 路由生效）✅ |
| **长按主页 = 返回** | `input swipe 100 1018 100 1018 700` | `com.android.settings/.SubSettings` → `.Settings`（注入返回生效）✅ |

全局窗内按键坐标（1920×1080，y=1018）：主页 ≈82、应用 ≈150、分屏 ≈218、360 ≈278、播放 ≈615。

### 4.3 播放/暂停幂等（探针 + 连点）

```bash
adb shell am start -n com.cardesk.mediaprobe/.MainActivity --ez play true --es title 海阔天空
python3 tools/r87_verify.py play 2     # 在全局 Dock 播放键连点 2 次（间隔 120ms）
```

| | 播放态（`dumpsys media_session`） | 下发的命令 |
|---|---|---|
| 连点前 | `state=3`（PLAYING） | — |
| 连点后 | `state=3`（**净不变**） | 恰好 2 条：`send-pause` → `send-play` |

原始日志（`CarDesk/Media`）：

```
播放/暂停指令（幂等） pkg=com.cardesk.mediaprobe desired=false state=3 from=true
  reason=send-pause force=false actions=775 buttons=true transport=true netease=false
播放/暂停指令（幂等） pkg=com.cardesk.mediaprobe desired=true  state=2 from=false
  reason=send-play  force=false actions=775 buttons=true transport=true netease=false
```

⇒ 偶数次连点回到原状态、**没有多出一条命令**（老实现会发 4 条，并且因为观察态滞后可能停在错误状态）。

### 4.4 方控桥的降级路径（模拟器没有 ECARX MediaCenter）

设置页开关（`1585,237`）从关到开、再从开到关：

```
CarDesk/Setting: 设置变更 key=session_bridge_enabled name=方控桥接开关 from=true  to=false
CarDesk/Media:   OEM 媒体桥关闭（设置页切换，即时生效）
CarDesk/Media:   OEM 媒体桥停止
CarDesk/Setting: 设置变更 key=session_bridge_enabled name=方控桥接开关 from=false to=true
CarDesk/Media:   OEM 媒体桥启用（设置页切换，即时生效），开始注册到原车媒体中心
CarDesk/Media:   OEM 媒体桥启动 sdk=com.ecarx.sdk.mediacenter
CarDesk/Media:   原车媒体接入失败：NameNotFoundException
```

没有崩溃、没有 ANR，普通播放控制不受影响 —— 这就是「无 SDK 优雅降级」。

### 4.5 设置页

| 截图 | 内容 |
|---|---|
| `r87-settings-dock.png` | Dock 状态栏页顶部（原来的几项没乱） |
| `r87-settings-dock2.png` | 新增 **「全局 Dock 栏（其他应用也显示）」** 开关 + 副标题「在其它应用底部常驻 Dock，遮住原车导航栏；长按主页键 = 返回」 |
| `r87-settings-dock3.png` | 打开后立即生效（policy 变 `immersive.navigation=*`、服务起来） |
| `r87-settings-dock4.png` | 能力 note：没有 RRO 时「会盖住其它应用底部 112px / 需要平台签名才能接管」+ 互斥说明 |
| `r87-settings-cluster.png` | 方控仪表页：桥接开关 + 新文案「已接入仪表/方控媒体桥（需车机 MediaCenter SDK 与平台权限）；无 SDK 时自动降级。开关即时生效，不用重启桌面。」 |

---

## 5. 「让系统真让出 112px」：RRO 实测记录（**这一档没做成，原因不是代码**）

目标：照参考工程的做法，用一个无代码 RRO 把系统 `navigation_bar_height` 改成 112dp，
让**系统自己**预留这条带子，Dock 用 `TYPE_NAVIGATION_BAR_PANEL` 落进去。

### 5.1 做了什么

`cardesk/insets/`（package `com.ecarx.cardesk.insets`，
`<overlay android:targetPackage="android" android:isStatic="false"/>`，`hasCode=false`）
+ `tools/build_insets.py`：

```bash
aapt2 compile --dir insets/res -o resources.zip
aapt2 link -I toolchain/sdk/platforms/android-9/android.jar \
  --manifest insets/AndroidManifest.xml --auto-add-overlay --no-resource-deduping \
  -o unsigned.apk -R resources.zip
java -cp toolchain/sdk/build-tools/34.0.0/lib/apksigner.jar com.android.apksigner.ApkSignerTool sign \
  --key platform-key/platform.pk8 --cert platform-key/platform.x509.pem \
  --v1-signing-enabled true --v2-signing-enabled true \
  --v3-signing-enabled false --v4-signing-enabled false
```

产物：`app/src/main/assets/cardesk-nav-insets.apk`（8,538 bytes，
SHA-256 `78b8c298576af3fe19f6224b2a4039a844c2026792cf4f8be00648a0799cd976`），
证书 SHA-256 `c8a2e9bccf597c2fb6dc66bee293fc13f2fc47ec77bc6b2b0d52c11f51192ab8`
（与 `platform-key/platform.x509.pem`、参考工程的 nav-insets 包一致）。

> **踩到的坑（已写进 `build_insets.py` 注释）**：不加 `--no-resource-deduping` 时，
> 四套 qualifier 会被 aapt2 去重成只有 default 一套 —— 构建、签名全绿，但覆盖无效。
> 这个包必须有 `values/`、`values-sw600dp/`、`values-sw720dp/`、`values-sw900dp/`
> 四套（framework 里 `navigation_bar_height` 的默认值是 `48dp`、`sw900dp` 是 `56dp`、
> `car_mode` 是 `96dp`，模拟器 1920×1080@160dpi 命中的正是 `sw900dp` 那档 56dp）。

### 5.2 结果：**启用成功，但高度不变**

```bash
adb install -r -g cardesk-nav-insets-test.apk        # Success
cmd overlay list | grep cardesk                       # [ ] → cmd overlay enable 后 [x]
cmd overlay dump com.ecarx.cardesk.insets.test2       # mState: STATE_ENABLED, mTargetPackageName: android
dumpsys window windows | grep NavigationBar0          # 仍然 1920 x 56
adb reboot                                            # 重启后依旧 56
```

排除项：不是 qualifier 的问题（四套都写了，`aapt2 dump resources` 自检通过）、
不是导航模式的问题（`navigation_mode=0` 三键；切 gestural/threebutton 都不变）、
不是签名的问题（platform key，`apksigner verify` 证书一致）。

### 5.3 结论

**AOSP Android 10 不允许 `/data` 安装的 overlay 改 framework-res 的资源**
（framework-res 的 overlay 需要预装进 `/system/overlay` 一类系统分区）。
想验证「预装」这条路，模拟器上 `adb root` 可以，但 `adb remount` 直接失败
（`E Consider providing all the dependencies to enable overlayfs`，exit 10），
所以**这一档只能到真车上验**，真车 ROM 允不允许由 ROM 决定
（参考工程的车机允许，所以它 96dp 生效）。

**因此本轮的默认路径不依赖 RRO**：拿不到 RRO 时全局 Dock 一切功能照常，只是
**其它应用底部 112px 会被 Dock 盖住**（相当于牺牲一条状态行）；拿到 RRO 时自动切到 2024
精确让位。这个分支在运行时判断（启动 / 切开关 / 开机都会重判），不需要用户选。

---

## 6. 构建与单测

```bash
# 本仓库没有 gradlew（用 toolchain 里的 gradle）
GRADLE_USER_HOME=/Users/roy/Documents/desktopapp/.gradle-home \
JAVA_HOME=/Users/roy/Documents/desktopapp/toolchain/jdk-17.0.20.1+1/Contents/Home \
/Users/roy/Documents/desktopapp/toolchain/gradle-8.7/bin/gradle :app:testDebugUnitTest

# 出包（release.sh 里 --no-test 的原因见下）
./tools/release.sh --no-bump --no-test
```

- **单测：1295 tests / 0 failures / 0 errors / 0 skipped**（110 个测试类，XML 聚合）；
  新增 55 例：`TransportIntentTest` 12、`MediaButtonTransportTest` 15、
  `MediaBridgePolicyTest` 10、`SystemNavigationPolicyTest` 18、`NavInsetsAssetTest` 4。
- **⚠️ 沙箱限制（不是代码问题）**：`release.sh` 会在**临时目录**跑一遍单测，
  而 Robolectric 要往 `$HOME` 写 `~/.robolectric-download-lock`，在本工作区的
  DSH 沙箱（workspace-write）里**必然失败**：
  `java.lang.IllegalStateException: Couldn't create lock file /Users/roy/.robolectric-download-lock`
  @`MavenDependencyResolver.whileLocked:117`（`touch ~/.dsh-write-test` 同样 Operation not permitted）。
  `HOME=…` / `-Duser.home=…` 都改不动（Java 的 `user.home` 来自 passwd）。
  ⇒ 全量单测在**仓库构建目录**（jar 已在 `~/.m2` 缓存里）跑，是 1295/0；
  出包用 `--no-test` 跳过那份重复的临时目录测试。
- `:app:compileDebugKotlin` exit 0、无 `e:` 行；`:app:assembleDebug` BUILD SUCCESSFUL。

---

## 7. 还没在真车验的（照例写清楚）

1. **桥的整条链路**：`ecarx.oem.permission.OPENAPI_MEDIACENTER_PERMISSION` 权限名、
   `com.ecarx.sdk.mediacenter` 包名、`ecarx.xsf.mediacenter/.MediaCenterService` 组件、
   `ONLINE = 6`、`wirePlayback` 的 1/0 语义 —— 全部沿用参考工程（它在那台车机上市过），
   cardesk 首次上真车看这三行日志即可判定：
   `OEM 媒体桥启动 sdk=…` → `原车媒体接入失败：…` 或注册成功 → `原车媒体未接受播放信息`。
2. **网易云**只认 `com.netease.cloudmusic.iot`（车机版）；手机版包名不同，需要再确认。
3. **RRO 一档**（见 §5.3）：真车 `/data` 能不能装、能不能生效，要看 ROM。
4. **原车 Dock 的真实高度**：没有 RRO 时，全局 Dock 的 112px 与原车 Dock 高度如果差得多，
   顶部/底部会有几条像素的露出或压盖 —— 真车上量一次就知道要不要把 `DOCK_H` 调成实测值。
5. **`setPlaying(true)` 在「没有会话」时**会走定向唤醒 + 拉起音乐 App，返回 `true`
   只代表命令发出去了，**不保证真的响**（媒体会话本身的边界）。

---

## 8. 复跑清单

```bash
python3 tools/r87_verify.py prep
python3 tools/r87_verify.py dock-on
python3 tools/r87_verify.py other
python3 tools/r87_verify.py home
python3 tools/r87_verify.py play 2
python3 tools/r87_verify.py check 15
python3 tools/r87_verify.py dock-off
./tools/build_insets.py --check        # RRO 资产自检（证书 / qualifier / 四条 dimen）
```

判据（与 `EMULATOR-VERIFY.md` 一致）：进程 pid 不变 + 无 `Fatal signal` / `FATAL EXCEPTION`
+ `/data/tombstones` 无新增 + 截图能看到功能在渲染。
