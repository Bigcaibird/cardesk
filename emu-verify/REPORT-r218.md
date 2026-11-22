# 第218轮 · 修两个实车反馈的问题（视频壁纸选不到文件 / 按键增强配了没用）

> 用户反馈（实车诊断包）：
> 1. **动态/视频壁纸在车机上提示「没有可用的视频浏览器/选择器」**；
> 2. **按键增强配置了没用**。
>
> 本轮把这两条链路从「只有配置界面」补成「真能跑」，
> 并在 cardesk AVD（`emulator-5560`）上实测。

---

## 一、诊断包读到了什么

诊断包解压在 `/tmp/cardesk-diag-2026/`（`summary.txt` + 4 份日志）。

### 1.1 车机身份

| 项 | 值 |
|---|---|
| 机型 | `geely/dcy11_a1/dcy11:9`（领克 820A，**Android 9 / API 28**） |
| 芯片 | `QUALCOMM MSM8996 for arm64` |
| 屏幕 | 1920×968 @160dpi |
| 装的是 | 4293 |
| 车机自带文件管理器 | `com.estrongs.android.pop`（FileExplorerActivity / PopVideoPlayer） |

### 1.2 问题1 的现场

```
12:32:25.533  视频壁纸：收到选视频结果 resultCode=0 uri=null
12:32:25.533  视频壁纸：进入导入处理 uri=null
```

⇒ **两个症状叠在一起**：

1. 用户点「导入视频」，弹「没有可用的视频选择器」——**根本没打开文件管理器**；
2. 即使打开了，车机文件管理器返回的 Intent **没有 `data` 字段** ⇒ `resultCode=0` 但 `uri=null`。

### 1.3 问题2 的现场

```
12:33:51.858  设置变更 key=custom_keys_enabled ... from=false to=true
12:34:15.362  设置变更 key=voice_double_action name=双击 from=无 to=360环影(开关)
12:34:36.732  设置变更 key=voice_click_action name=单击 from=无 to=切换驾驶模式
12:34:42.909  设置变更 key=voice_long_action name=长按 from=无 to=切换驾驶模式
```

⇒ 配置**确实写进 pref 了**，但按下去什么都不会发生。

---

## 二、根因

### 2.1 问题1：选择器没有降级 + uri 只认 `data`

| 位置 | 原实现 | 问题 |
|---|---|---|
| `MainActivity.startVideoWallpaperImport()` | `ACTION_OPEN_DOCUMENT` + `type="video/*"` 直接 `startActivityForResult` | 车机上**没有 app 接管这个组合** ⇒ 抛 `ActivityNotFoundException` ⇒ 弹「没有可用的视频选择器」 |
| `MainActivity.onActivityResult()` | `data?.data` | 车机文件管理器返回的 Intent **没有 `data`** ⇒ uri 为 null |

### 2.2 问题2：只有配置界面，没有执行点

全仓搜索：`keysEnhanced` / `voiceClickAction` / `voiceDoubleAction` / `voiceLongAction` /
`mediaPrevLongAction` / `mediaNextLongAction` **只在 `SettingsOverlay.kt` / `KeysDialog.kt`
里被读写**，主源码里**没有任何一处**在按键事件发生时去读它们。

⇒ 配了等于没配。**这不是 bug，是这条链路从来没写过。**

---

## 三、改了什么

### 3.1 问题1 · 视频壁纸导入

#### 新增 `core/VideoPicker.kt`（纯逻辑，可 JVM 单测）

| 函数 | 作用 |
|---|---|
| `strategies()` | **三级降级链**：`OPEN_DOCUMENT`+视频通配 → `OPEN_DOCUMENT`+不限类型 → `GET_CONTENT`+视频通配 |
| `buildIntent()` | 按策略造 intent（带 `CATEGORY_OPENABLE`） |
| `extractUri(data)` | **uri 三级兜底**：`data` → `clipData[0]` → `EXTRA_STREAM` |
| `looksLikeVideo()` | 按扩展名粗筛（降级到「不限类型」时挡掉图片/文档） |

#### `MainActivity.startVideoWallpaperImport()`

改成逐级 `resolveActivity` **预检**（不等抛异常），第一个能解析的就用它；
全都不行 ⇒ 弹明确文案：

> 车机没有视频选择器，请用文件管理器把视频放到「我的车机」里再试

#### `MainActivity.onActivityResult()`

`REQ_VIDEO_WALLPAPER` 分支改用 `VideoPicker.extractUri(data)`；
`resultCode==OK && uri==null` 时提示「没取到视频文件，请重新选择」。

### 3.2 问题2 · 按键增强执行链路

分成三层，前两层是**纯 Kotlin**（可 JVM 单测），第三层才碰 Android：

| 层 | 文件 | 职责 |
|---|---|---|
| 识别 | `core/KeyGesture.kt`（新） | 手势状态机：单击 / 双击 / 长按。**不持有时间源**，时间由调用方传进来 |
| 解析 | `core/ActionResolver`→`core/KeyActionResolver.kt`（新） | 「哪个键 × 哪种手势」→ 「做什么动作」 |
| 执行 | `ui/KeyActionExecutor.kt`（新） | 真的去切驾驶模式 / 开 360 / 唤起语音助手 |

**接入点**：`MainActivity.dispatchKeyEvent` 开头
（比 View 树更早，能拿到所有按键事件）。

**支持的动作**：

| 动作 | 实现 |
|---|---|
| 切换驾驶模式 | `VehicleController.setDriveMode(target){ok,message}` |
| 切换 360 环影 | 新增 `VehicleController.is360On` + `LauncherView.toggleCam360()` |
| 唤起语音助手 | 4 个候选 intent 依次 `resolveActivity`（车机语音助手包名未知） |
| 切换导航投屏 | 本机 `dimNaviCastSupported` 为 false ⇒ 保留分支并提示不支持 |

#### ⚠️ 单击必须延迟 280ms

用户可以同时配「单击 = A」和「双击 = B」。
若抬手就立刻发单击，双击时会**先触发 A 再触发 B**。
⇒ 单击延迟到双击窗口结束（`DOUBLE_WINDOW_MS = 280`）才发。

#### 语音键按「候选 keyCode」覆盖

| keyCode | 常量 | 说明 |
|---|---|---|
| **108** | `KEYCODE_VOICE_ASSIST`（Android 13 及以下） | **本车（API 28）用它** |
| **231** | `KEYCODE_VOICE_ASSIST`（Android 14 起改值） | 新系统 |
| 84 | `KEYCODE_SEARCH` | 一些车机把语音键映射成搜索键 |
| 204 | `KEYCODE_ASSIST` | 另一些车机 |
| 112 | `KEYCODE_VOICE_COMMAND` | 少数车机 |

> **踩坑记录**：源码里**不能**写 `KeyEvent.KEYCODE_VOICE_ASSIST` ——
> 本工程 compileSdk 是 34，而 Android 14 把这个常量从 **108 改成了 231**，
> 写常量会编译成 231，拿 API 28 的车机去对就对不上。只能写**字面量**，两个都收。
> （这个坑是写 Robolectric 测试时**用常量当期望值、9 个用例集体失败**才暴露出来的。）

**实车真实 keyCode 仍未知** ⇒ 本轮补上了「**不认识的按键也留痕**」：
`KeyActionExecutor` 会把不认识的 keyCode 以 **INFO** 级别记进日志
（`Logx` 的 DEBUG 默认不落盘 ⇒ 诊断包里看不到，所以必须 INFO），
用户按一次语音键，诊断包里就能看到真实值，下一轮把候选表收窄即可。

---

## 四、顺带修掉的真实缺陷

### 4.1 长按成立后抬手会多发一次单击

`KeyGesture.onUp()` 原来在抬手时无条件挂一个待发单击。
若本次按下已经判成长按 ⇒ 长按动作触发后，抬手再等 280ms 会**再触发一次单击动作**
⇒ 用户看到「切了两次」。

修法：`onUp()` 里 `if (longFired) return Gesture.NONE` —— 长按抬手不再挂单击。

> 这个缺陷是**第218轮新写的 Robolectric 端到端测试抓出来的**，
> 不是靠读代码看出来的。

### 4.2 长按抬手不参与双击判定

长按的抬手**不更新** `lastUpAt` ⇒ 「长按之后马上再点一下」不会被误判成双击。

---

## 五、验证

### 5.1 单测

| 测试类 | 用例 | 覆盖 |
|---|---|---|
| `core/KeyGestureTest.kt`（新） | 9 | 手势状态机（纯 JVM） |
| `core/KeyActionResolverTest.kt`（新） | 8 | 动作解析 + 候选 keyCode（含 231） |
| `core/VideoPickerTest.kt`（新） | 8 | 降级链 + uri 三级兜底 |
| `ui/KeyActionExecutorTest.kt`（新） | 17 | **执行端端到端**（单击/双击/长按/限流/开关） |
| `VideoWallpaperImportTest.kt`（新） | 3 | MainActivity 上的降级链与兜底提示 |

### 5.2 为什么长按只能用 Robolectric 验

模拟器上 `input keyevent --longpress` 的 down/up 落在**同一毫秒**（不真按住）；
`sendevent /dev/input/event0` 注入的 evdev 事件**不被 Android 输入子系统消费**
（该设备的键盘节点是 `qwerty2`）⇒ **模拟器根本造不出「按住 600ms」这种输入**。
⇒ 长按链路改用 Robolectric 虚拟时钟
（`shadowOf(Looper.getMainLooper()).idleFor(...)`）端到端验证。

### 5.3 变异检查（13 项，全捕获）

脚本：`/tmp/cardesk-r218/mutate-r218.sh`

| # | 变异 | 结果 |
|---|---|---|
| ① | 双击窗口判断失效 | ✅ 捕获 |
| ② | 长按阈值判断失效 | ✅ 捕获 |
| ③ | 单击发出去后不清 pending | ✅ 捕获 |
| ④ | 去掉 clipData 兜底 | ✅ 捕获 |
| ⑤ | 去掉 EXTRA_STREAM 兜底 | ✅ 捕获 |
| ⑥ | 降级链少一级 | ✅ 捕获 |
| ⑦ | 双击手势错取单击的配置 | ✅ 捕获 |
| ⑧ | 长按成立后抬手不再拦单击 | ✅ 捕获 |
| ⑨ | 语音键候选表漏掉 231 | ✅ 捕获 |
| ⑩ | 长按定时器延后到 5 秒 | ✅ 捕获 |
| ⑪ | 选择器降级链只试第一级就放弃 | ✅ 捕获 |
| ⑫ | 没有选择器时不给提示（静默失败） | ✅ 捕获 |
| ⑬ | 不认识的按键不留痕 | ✅ 捕获 |

> 脚本本轮加了 **`verify_restore`**（还原后逐个比对源码与备份）：
> 前两版脚本的 `restore()` 各漏了一个文件，导致**基线跑在变异态上**被误判成回归。
> 现在每次还原都自校验（5 个文件全部一致才继续）。

### 5.4 AVD 实测（`emulator-5560` / `cardesk28` / API 28 / arm64 / 1920×1080@160）

| 场景 | 操作 | 结果 |
|---|---|---|
| 单击 | `input keyevent 108` | ✅ 280ms 后执行「切换驾驶模式」⇒ `已切换到 运动模式` |
| 双击 | `input keyevent 108 108` | ✅ 只触发 1 次「切换 360」，**无误发单击** |
| 视频导入（正常） | 点「导入视频…」 | ✅ 走**第一级** `OPEN_DOCUMENT (video/*)`，由 `com.android.documentsui` 接管 |
| 视频导入（降级） | `pm disable-user com.android.documentsui` 后再点 | ✅ 自动降级到**第三级** `GET_CONTENT (video/*)`，由 `com.android.gallery3d` 接管 |
| 视频导入（全挂） | 再 `pm disable-user com.android.gallery3d` | ✅ 弹 Toast「车机没有视频选择器，请用文件管理器把视频放到「我的车机」里再试」，**FATAL 0 / 无崩溃** |
| 视频壁纸播放 | 重启应用 | ✅ `视频壁纸已开始播放 bytes=60878141 size=1920x1080 backend=SurfaceView` |
| 长按 | — | ⚠️ 模拟器造不出（见 §5.2），改由 Robolectric 覆盖 |

截图：`emu-verify/shots-r218/`

> 降级那一档顺带证明了一件事：**真机 Android 的 intent 匹配是按 MIME 通配的** ——
> 注册了「不限类型」的 app **也会**接管「视频通配」的查询
> ⇒ 降级链真正起作用的分界是 **action**（`OPEN_DOCUMENT` → `GET_CONTENT`），
> 也就是「有些车机只注册了 `GET_CONTENT`」的情况。
> （这直接改写了第3个测试的写法。）

### 5.5 全量回归与出包

| 项 | 结果 |
|---|---|
| `:app:assembleSystem -PcardeskPackage=com.ecarx.cardesk` | **BUILD SUCCESSFUL** ✅ |
| 全量 JVM 单测 | **2555 / 0 failures / 0 errors / 0 skipped**（第217轮基线 2510 ⇒ 新增 45 例全摘净） |
| 变异检查 | **13 项全部被捕获** ✅ |
| 出包 | **4294** / `1.0.1198-system` / 3,627,463 B<br>SHA-256 `09240b93f28d5857f02704902428afb1fd5b00d12fdcda5e077afbda421d0abe`<br>装机 md5 `8d8df8ea73913c0508309e32bfd037bf`（本地 = 设备端）<br>平台证书 SHA-256 `c8a2e9bccf597c2fb6dc66bee293fc13f2fc47ec77bc6b2b0d52c11f51192ab8` ✅ |

### 5.6 装机冒烟（最终包 4294）

| 项 | 期望 | 实测 |
|---|---|---|
| 启动 | 无崩溃 | `桌面启动 sdk=28`、**FATAL 0**、crash buffer 空 ✅ |
| 单击 | 切驾驶模式 | `执行动作 source=语音键单击 action=0` ⇒ `已切换到 运动模式` ✅ |
| 双击 | 切 360，且**只触发一次** | `执行动作 source=语音键双击 action=1` ⇒「执行动作」条数 **1** ✅ |
| **陌生键码留痕** | 记进日志 | `按键增强：不认识的按键 keyCode=24 keyName=KEYCODE_VOLUME_UP` ✅ |
| 日间冒烟 | 桌面正常 | 截图 `01-桌面-日间.png` ✅ |
| 夜间冒烟 | 桌面正常 | `日夜态变化 night=true was=false enabled=true mode=1`，截图 `07-夜间-视频壁纸.png` ✅ |
| 视频壁纸 | 照常播放 | `视频壁纸已开始播放 bytes=60878141 size=1920x1080 backend=SurfaceView` ✅ |

> `Exception` 计数里的 2 条是**系统** `Codec2Client: querySupportedValues -- call failed. Error code = 38`
> （pid 1494 = 模拟器媒体服务，**不是本应用** pid 1317），与本轮改动无关。


---

## 六、留给实车的三个未知数

| # | 未知 | 本轮做了什么铺垫 |
|---|---|---|
| 1 | **语音键的真实 keyCode** | 候选表覆盖 108/231/84/204/112，且**陌生 keyCode 会记进诊断包**（INFO 级）⇒ 用户按一次语音键再出诊断包即可定位 |
| 2 | **车机语音助手包名** | 4 个候选 intent 依次 `resolveActivity`；全失败弹「没找到语音助手应用」 |
| 3 | **视频壁纸在真机的解码开销** | 车机是 MSM8996，硬件解码能力与模拟器不同 ⇒ 需实车看 CPU 占用 |

---

## 七、本轮未做

- **没做实车复测**（实车不在手边）。
- **没自建文件选择器**（车机上自研 SAF 成本高，优先级低于把入口修通）。
- **未提交 Git**（工作区仍有大量未提交改动，按约定不擅自 commit）。
