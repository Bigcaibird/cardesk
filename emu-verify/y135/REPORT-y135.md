# 第135轮 验证报告

> 主题：① 自动切换驾驶模式接上执行端；② 没打开播放器时方控不再唤起原车爱趣听。
>
> 依据：用户上传的 **4231 版真车诊断包**（两个问题）。

## 一、问题1 · 自动切换驾驶模式

### 根因

设置页「启动切换」页的「自动切换驾驶模式」=
`Prefs.startSwitchEnabled`（`start_switch_enabled`）+
`Prefs.startSwitchMode`（`start_switch_mode`）。

```
$ git log --oneline -S "start_switch_enabled" -- app/src/main/java
22d5b75 CarDesk · 车控桌面（首次公开）
```

**全仓只有 `SettingsOverlay` 在写，没有任何执行端** → 典型的「只存不执行」。
（别和第132轮的 `iss_keep_enabled`（发动机启停，`537002240`）搞混。）

写入路径也不对：参考 App（君君桌面）的
`com.claunchercard.manager.DriveModeController.writeDriveModeAdaptApi` 是
**两参优先 → zone 2 → zone 1**，我们的 `writeFunction` 只有两参一条路。

### 模拟器证据（`emulator-5556`，Android 10 / SDK 29 / 1920×1080）

预置 `start_switch_enabled=true`、`start_switch_mode=570491139`（运动）后启动桌面：

```
11-07 20:54:12.560 I CarDesk/Vehicle: 写入驾驶模式 func=0x22010100 value=570491139 label=运动模式 ok=true reason=null
11-07 20:54:12.561 I CarDesk/Vehicle: 切换驾驶模式成功 value=570491139 label=运动模式 written=true channel=mock reason=null
11-07 20:54:13.281 I CarDesk/Signal: 驾驶模式变化 from=null to=运动 prevMode=null mode=570491139 label=运动
11-07 20:54:13.518 I CarDesk/Vehicle: 自动切换驾驶模式：结果 trigger=桌面启动 target=570491139 ok=true message=已切换到 运动模式
```

| 证据 | 结论 |
|---|---|
| `y135-log-drive-mode.txt` | 执行端真的跑起来了，写的是**正确的功能号与模式值** |
| `y135-04-mode-applied.png` | 状态栏胶囊变成「驾驶模式 · 运动」（原来是「待命」） |
| `y135-02-settings-drive-mode.png` | 设置页读到的是持久化值（开关绿、目标=运动模式） |

### 冷却

第二次启动：

```
11-07 20:54:28.287 I CarDesk/Vehicle: 自动切换驾驶模式：冷却中，跳过 trigger=桌面启动 target=570491139 sinceLastMs=15732
```

**写入 0 次**。冷却时间戳持久化，**跨进程重启仍然有效**（正式包复验时
`sinceLastMs=517689` 也一样被挡住）。

### 正式包复验（4233）

```
11-07 21:03:09.121 I CarDesk/Vehicle: 写入驾驶模式 func=0x22010100 value=570491139 label=运动模式 ok=true reason=null
11-07 21:03:09.444 I CarDesk/Vehicle: 自动切换驾驶模式：结果 trigger=桌面启动 target=570491139 ok=true message=已切换到 运动模式
```

## 二、问题2 · 方控唤起爱趣听

### 根因

诊断包（4231）实测：

```
bridge.registered: true          bridge.playClaimed: false
bridge.callbackCount: 0          bridge.sourceType: -1
bridge.controlTarget: com.tencent.qqmusiccar
```

「OEM 方控回调」在所有日志里 **0 条**。

`MediaBridgePolicy.shouldAcquire` 要求「有样本**且正在播**」才 claim
（`playing && (stateChanged || sourceChanged)`），
于是「没打开播放器」时：没有样本 → 不 claim → 原车认为当前音源还是爱趣听
→ 方控不交给我们 → **我们连回调都收不到** → 第131轮的「拉起目标播放器」那段代码根本没机会执行。

那条被认可的路是「**启用即 claim**」：`SessionBridge.setBridgeEnabled(true)` →
`pushToMediaCenter(true)` → `t.a()`（claim）；
反向的日志更直白：`setBridgeEnabled(false)` → 「bridge disabled → MediaCenter
unregistered, **OEM source can take over**」—— **不 claim，音源就归原车**。

> **出处要说准（第135轮末修正）**：上面这条 claim 口径的出处是**参考 App（君君桌面）**
> —— 包名 `com.ecarx.qq.tool`，`app_name` 是「君君桌面」，**第三方**（平台签名预装）。
> 它的桥 SDK 是 `SessionBridge`，设置页开关在内部包
> `com.claunchercard.manager.DesignSettings`（`:515/1130`），
> 开机流程在 `com.claunchercard.manager.BootMediaCompetitorKiller`。
>
> **不存在「官方桌面」**：`com.claunchercard.manager.*` 是**同一个 APK 内部**的 Java 包名
> —— `inner_dec` / `outer_dec` 两个反编译产物的 manifest `package` 都是 `com.ecarx.qq.tool`，
> `app_name` 都是「君君桌面」，`outer_dec` 只是 `com.yqsh.protector.shell` 加固过的同款构建。
>
> ⇒ 这条证据的性质是「**另一个第三方桌面在同款车机上就是这么干的**」，
> **不是厂商规定的口径**；跟它对齐是因为它跑通过，**能不能成最终看真车复验**。
>
> 另记一条**君君桌面的做法**（`BootMediaCompetitorKiller`）：它开机 1s/5s/15s
> 会 `am force-stop` 掉 `com.ecarx.multimedia` 与 `com.tencent.wecarflow`（爱趣听），
> 除非用户锁定 / 上次在放 / 显式保护了它，然后才 claim。
> **我们不做强杀**（越过红线），只 claim 自己的播放入口。

### 主体分清楚，根因才闭合

| 主体 | 包名 / 版本 | 身份 | 与 MediaCenter 的关系 |
|---|---|---|---|
| **参考 App（君君桌面）** | `com.ecarx.qq.tool`（`desktop.apk` / `1.0.20273116-session-desktop`；加固版 `1.0.01257-lynkco-tool-pro`） | **第三方**桌面（平台签名预装），**不是车机官方桌面** | 自带 `SessionBridge` 桥 SDK（`com.claunchercard.manager.*` 是它**内部**的 Java 包名） |
| **UNI 工程** | `xyz.i4o.uni.launcher` | 第三方参考工程 | 我们的 `OfficialMediaBridge` 从它移植（第87轮） |

**君君桌面那个「桥接开关」默认是关的**（`bridge_control/enabled` 默认 false），
而它的开关原文与我们一字不差（`DesignSettings.java:515`）：
「桥接开关」+「连接车机仪表与方控通道」→ `SessionBridge.setBridgeEnabled`。

⇒ **「方控唤起爱趣听」不是 bug，是「都没 claim」**：
君君桌面默认不 claim（用户没开它那个开关）→ MediaCenter 当前音源一直是原车音源；
我们注册了但也从不 claim → 原车同样不认我们 → 方控落回爱趣听。

### 模拟器能验到什么、验不到什么

| | 能不能在模拟器上验 | 证据 |
|---|---|---|
| claim 的**判定逻辑**（白名单 / 车机自带音源在放 / 只 claim 一次） | ✅ JVM 单测 | `MediaBridgePolicyTest` 7 项 |
| 桥**真的接上**了 claim（源码接线） | ✅ 反向验证的接线断言 | `tools/verify_y135_reverse.sh` 反向 11 / 12 |
| 「车机自带音源在放」这个信号从 tracker 传到桥 | ✅ 反向验证的接线断言 + 纯逻辑单测 | 反向 13 + `hasNativeSourcePlaying` 单测 |
| **原车真的把方控交给我们** | ❌ 模拟器没有原车 `ecarx.xsf.mediacenter` 服务，也没有仪表（DIM） | **必须真车复验** |

### 真车复验要看什么

1. 装包后**不打开任何播放器**，按方向盘播放键
   → 应该拉起**桌面上选定的那个播放器**（QQ 音乐车机版），而不是爱趣听；
2. 诊断包看这两行：

```
bridge.claimTarget: com.tencent.qqmusiccar   <- 空 = 从来没接管过
bridge.callbackCount: 3                       <- >0 = 原车把按键交给我们了
bridge.nativeSourcePlaying: false             <- 车机自带音源在放时我们不会抢
```

## 三、诊断包新字段

`summary.txt` 新增一段（`y135-summary.txt`）：

```
---- 驾驶模式自动切换（第135轮）----
enabled: true
target: 570491139 (运动模式)
lastAt: 1794056052555
lastResult: 已切到运动模式
```

媒体诊断段新增两行：

```
bridge.claimTarget:
bridge.nativeSourcePlaying: false
```

## 四、单测与反向验证

- 全量 **1871 项全绿**（第134轮 1841 → +30）
- 反向验证 **13 项全部变红**（`tools/verify_y135_reverse.sh`），还原后 0 红灯
