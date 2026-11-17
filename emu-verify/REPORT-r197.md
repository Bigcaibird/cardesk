# 第197轮 · Dock 媒体卡点「封面 / 歌名」区 → 拉起正在使用的音乐 App 实测报告

**日期**：2026-11-17
**设备**：模拟器 `emulator-5560`（cardesk28，API 28 / Android 9，1920×1080 @160dpi）
**被测包**：`com.ecarx.cardesk`（system 变体，versionCode 4270 / versionName 1.0.1174-system）
**真实播放器**：QQ 音乐车机版 `com.tencent.qqmusiccar`、网易云车机版 `com.netease.cloudmusic.iot`、汽水音乐车机版 `com.luna.music.car`

---

## 一、结论先行

**功能可用：点 Dock 媒体卡的封面 / 歌名区 → 拉起当前正在使用的那���音乐 App；三家播放器实测全通。**

| 播放器 | 点歌名区拉起谁 | 实测 |
|---|---|---|
| **QQ 音乐车机版** | `com.tencent.qqmusiccar` | ✅ 前台切到 QQ 音乐 |
| **网易云车机版** | `com.netease.cloudmusic.iot` | ✅ 前台切到网易云 |
| **汽水音乐车机版** | `com.luna.music.car` | ✅ 前台切到汽水 |

**「拉起正在使用的那个」这一点是实测出来的，不是写死的**：把会话从 QQ 音乐换成网易云、
再换成汽水，桌面每次拉起的都是**当时正在出声的那个**。

另外本轮**顺带发现并修掉了一个既有 bug**：媒体卡的**长按从来没生效过**
（详见第四节）——这个 bug 本轮必须修，因为点击改成「拉起 App」之后，
长按（媒体浮窗）成了唯一的替代入口。

---

## 二、需求与改动前后的行为对照

### 2.1 需求

> dock栏音乐播放控件新增一个功能，点击该控件除播放按钮外的区域时，打开对应正在使用的音乐app
> 应该是说除了上下曲切换和播放按钮外的区域

### 2.2 改动前后

| 点在哪里 | 改动前 | 改动后 |
|---|---|---|
| 播放键 | 播放 / 暂停 | 播放 / 暂停（**不变**） |
| 上一首 / 下一首键 | 切歌 | 切歌（**不变**） |
| **封面 + 歌名区** | **跳我们自己的第二页音乐卡** | **拉起正在使用的音乐 App** |
| 媒体卡左右滑动 | 切歌 | 切歌（**不变**） |
| 媒体卡长按 | ~~开媒体浮窗~~（**从未生效**，见第四节） | **开媒体浮窗**（修好了） |

「跳我们自己的第二页音乐卡」这个入口本轮按用户要求**覆盖**。

---

## 三、「正在使用的音乐 App」怎么判定

### 3.1 用当前会话的包名，不是用户选的默认来源

用的是 `MusicController.track.pkg` —— **此刻正在出声的那个 App**。

**不用** `prefs.musicSource`（用户上次选的默认来源）的理由：

- 默认来源是**用户上次选的**，而实际在播的可能是另一个 App
  （用户在 QQ 音乐里放歌、但默认来源还停在网易云）；
- 「正在使用」问的是**当下**，答案只存在于**会话**里。

### 3.2 不用会话的 sourceId

- `sourceId` 是**我们自己的分类标签**（`MediaSources` 映射出来的）；
- 「通用音乐」这个 id（`MediaSources.GENERIC_ID`）**不对应任何 App** ——
  拿它去拉起 App 必然落空；
- **包名**才是能直接喂给 `PackageManager` 的东西。

### 3.3 判定分层（`core/media/DockMediaLaunch.kt`）

```
会话包名 → resolve() → Action
   ├─ 空 / 空白        → NoSession     → 提示「当前没有正在使用的音乐 App」
   ├─ 有包但拉不起来    → NotLaunchable → 提示「该音乐应用无法打开」
   └─ 有包且拉得起来    → OpenApp(pkg)  → startActivity(FLAG_ACTIVITY_NEW_TASK)
```

`resolve()` 与 `messageFor()` 是**纯逻辑**（JVM 单测覆盖）；
`openCurrentApp()` 才碰 Android。

---

## 四、顺带修掉的既有 bug：媒体卡长按从来没生效过

### 4.1 现象

第一次实测长按时，发现**没有开浮窗**，反而把音乐 App 拉起来了。

### 4.2 根因（`DockBarView.onTouchEvent` ACTION_DOWN）

```kotlin
if (key == "media") {
    swipeTracking = true
    ...
} else if (key == "home" || key == "split" || key == "media" || key.startsWith("quick")) {
    scheduleLongPress(key)
}
```

`key == "media"` 被**第一个** `if` 先匹配走了，
所以 `else if` 里那句 `key == "media"` 是**死代码** ——
`scheduleLongPress()` 对媒体卡**永远不会被调用**。

`git log -S` 确认：这段从**首次公开**（`22d5b75`）起就是这样，
是**既有 bug**，不是本轮引入的。

### 4.3 为什么本轮必须修

以前无所谓：点媒体卡是「进我们自己的音乐页」，长按失效只是少个浮窗入口。
第197轮点击改成「拉起音乐 App」之后，**长按就成了唯一的替代入口**
（想看自己桌面的音乐卡只能长按），不能是坏的。

### 4.4 改法

把 `scheduleLongPress` 从 `else if` 里**提出来单独一个 `if`**，
让「滑动跟踪」与「长按调度」两条逻辑**互不排斥**。

同时补一个保护：长按触发后抬手时**不能**再当成点击去拉起 App
（否则「长按」会同时做两件事：开浮窗 + 拉起 App）。

---

## 五、实测过程与证据

### 5.1 点歌名区 → 拉起 QQ 音乐

会话归属 `com.tencent.qqmusiccar`（`dumpsys media_session` → `active=true`），
桌面上媒体卡显示「赤伶 / HITA」。

```
$ adb shell input tap 445 1010        # Dock 媒体卡的歌名区

[EVT] Dock 按键点击 key=media label=媒体卡 title=赤伶
[EVT] Dock 媒体卡：拉起当前音乐 App pkg=com.tencent.qqmusiccar

$ adb shell dumpsys activity activities | grep mResumedActivity
mResumedActivity: ActivityRecord{4701fb1 u0 com.tencent.qqmusiccar/.app.activity.AppStarterActivity t451}
```

✅ **前台从桌面切到了 QQ 音乐。**

### 5.2 换成网易云 → 拉起网易云（**证明不是写死的**）

`am force-stop com.tencent.qqmusiccar` → 起网易云 → 等会话挂上：

```
$ adb shell dumpsys media_session | grep -E "package=|active="
      package=com.netease.cloudmusic.iot
      active=true
```

回桌面再点歌名区：

```
[EVT] Dock 按键点击 key=media label=媒体卡 title=明知故犯
[EVT] Dock 媒体卡：拉起当前音乐 App pkg=com.netease.cloudmusic.iot

$ adb shell dumpsys activity activities | grep mResumedActivity
mResumedActivity: ActivityRecord{551c966 u0 com.netease.cloudmusic.iot/com.netease.cloudmusic.activity.CloudMusicRNActivity t453}
```

✅ **拉起的是网易云，不是 QQ 音乐。** 桌面媒体卡上的歌名也同步成了「明知故犯」。

### 5.3 换成汽水 → 拉起汽水

```
      package=com.luna.music.car
      active=true

[EVT] Dock 按键点击 key=media label=媒体卡 title=荒
[EVT] Dock 媒体卡：拉起当前音乐 App pkg=com.luna.music.car

$ adb shell dumpsys activity activities | grep mResumedActivity
mResumedActivity: ActivityRecord{973090 u0 com.luna.music.car/.app.framework.ui.MainActivity t454}
```

✅ **拉起汽水。**

### 5.4 三个键仍然互斥（**没有被新功能抢走**）

```
点播放键   → [EVT] Dock 按键点击 key=play      label=播放键
点下一首   → [EVT] Dock 按键点击 key=mediaNext  label=下一首
点上一首   → [EVT] Dock 按键点击 key=mediaPrev  label=上一首
```

三次之后 `mResumedActivity` 仍是 `com.ecarx.cardesk/…MainActivity` ——
**前台始终是桌面**，说明三个键**没有**触发拉起 App。

### 5.5 长按（修复后）

```
$ adb shell input swipe 445 1010 445 1010 1200      # 同点长按 1200ms

[EVT] Dock 按键长按 key=media label=媒体卡
```

**没有**「Dock 按键点击」→ **没有误拉起 App**；
`mResumedActivity` 仍是桌面。

> 修复前的对照：同一手势会打出
> `[EVT] Dock 按键点击 key=media` + `[EVT] Dock 媒体卡：拉起当前音乐 App`，
> 并且前台真的切到了汽水 —— **长按 + 抬手 = 同时开浮窗和拉起 App**。

### 5.6 不崩溃

全流程 `logcat` 里**无** `FATAL` / `AndroidRuntime` 异常
（只有 `adb shell input` 命令自身的正常 `RuntimeInit` 日志）。

---

## 六、单测

`app/src/test/java/com/cardesk/launcher/core/media/DockMediaLaunchTest.kt`（新，**13 例**）：

| 断言 | 说明 |
|---|---|
| 有会话且拉得起来 → 拉起那个包 | 主路径 |
| 谁在出声就拉谁（不看默认来源） | 核心语义 |
| 三个车机播放器都能拉起 | QQ / 网易云 / 汽水 |
| 没有会话 → 没有 App 可拉 | 兜底 |
| 会话包名空串 / 只有空白 → 同按没有会话 | 兜底 |
| 会话在但拉不起来 → 明确报「拉不起来」 | 两种失败要能分辨 |
| 没有会话时**不该**去问能不能拉起 | 不拿空串查 PackageManager |
| 判定用去空白后的包名 | 边界 |
| 拉得起来时没有提示文案 | 文案 |
| 两种失败状态的提示语**不一样** | 文案 |

**全量单测**：`2366` 用例，**0 失败 / 0 错误 / 0 跳过**（较第196轮 2353 → **+13**）。

---

## 七、已知限制

1. **「跳我们自己的第二页音乐卡」这个入口没了**（本轮按用户要求覆盖）。
   替代路径：Dock 媒体卡**长按**（媒体浮窗）、页面指示器进第二页、
   首页的「音乐」入口、导航栏媒体槽位。
2. **无会话时给提示**「当前没有正在使用的音乐 App」——
   但实测中这个状态**几乎不会出现**：桌面的「启动后自动播放」会把默认源拉起来，
   会话随即建立（实测里强停汽水后它立刻又被拉回来了）。
3. **拉不起的 App**（没装 / 没有启动入口）给提示「该音乐应用无法打开」。
4. 模拟器是 API 28；真车 API 29 的 `ISessionController` 签名不同 ——
   本轮不涉及会话回调，**不受影响**。
