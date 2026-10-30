# 第八十五轮 · 红绿灯画反 + 氛围灯动感

> 诉求原文：
> 1. 「导航卡的红绿灯，绿灯显示错误，用的红灯显示」
> 2. 「氛围灯目前颜色以及亮度等变化动态太僵硬，没有动感，需要优化一下」
>
> 日期：2026-10-30　被测包：`com.ecarx.cardesk` 4156 / `1.0.1060-system`
> 设备：`emulator-5554`（AVD `cardesk-aosp`，android-10 / API 29 / 1920×1080@160dpi）

---

## 1. 结论

### 1.1 红绿灯：绿灯被画成红灯 —— 根因是**灯态字段被整个丢掉了**

- 官方标准协议（`KEY_TYPE=60073`）里**有**灯态字段 `trafficLightStatus`，
  而我们以前**完全没用它**，只按「`redLightCountDownSeconds > 0` 就判红」。
  那个倒计时在**绿灯期间照样可能是正数** ⇒ 绿灯被画成红灯。
- 现在的取值是**从高德自己的代码里查出来的**（不是猜的、也不是抄文档）：
  从模拟器里 pull 出车机同款高德（`com.autonavi.amapautp.yh`），
  用 `jadx` 解 `classes.dex`，得到
  **官方协议 `trafficLightStatus`：`1` = 红灯、`4` = 绿灯**。
  ⚠️ 与**巡航协议正好相反**（巡航 `0`=红 / `1`=绿），两套表不能互相套用。
- 改法：**灯态看 `trafficLightStatus`，读秒跟着灯态走**
  （红→`redLightCountDownSeconds`，绿→`greenLightLastSecond`）；
  万一高德改了枚举，**不猜**，改用**自带字段名**的读秒兜底（绿秒优先），
  绝不退回「永远画红灯」那个老毛病。
- 模拟器已核验：红灯档画红、绿灯档画绿（**那一帧同时带着 19 秒的"红灯倒计时"**，
  正是老代码会被带偏的地方，新代码没被带偏）。

### 1.2 氛围灯：查出来的根因比预想的深一层 —— **色相压根就没动过**

原来的判断（「频谱→颜色一步到位、没有时间维度的平滑」）是对的，但只说对了一半。
在模拟器上把频谱真正跑起来之后才看清：

> **512 点 FFT 在 44.1kHz 下的 bin 间隔是 86.1Hz，
> 于是「低频四段」（60~149Hz）全部落进同一个 bin ⇒ 四段功率永远相等
> ⇒ 「取最响的那一段」永远取到第 0 段 ⇒ 色相恒为 0°（红）。**

也就是说：**跟随低频模式下，灯的颜色从来就没变过**，
低频能量还饱和 ⇒ 亮度钉死在最高 —— 「红、拉满、一动不动」，
这是「僵硬」的极端形态。

本轮一共修了四层：

| 层 | 以前 | 现在 |
|---|---|---|
| **色相** | 离散挑段，段与段之间硬跳 | 新增 `AmbientMotion`：指数平滑 + **最短路径** + **限速 90°/s** |
| **亮度** | 频谱强弱原样乘进 RGB | **快起慢落**（attack τ=50ms / decay τ=320ms）+ 起拍脉冲 |
| **频谱分辨率** | 512 点 ⇒ 低频四段塌进同一 bin ⇒ 色相恒 0° | **1024 点**（设备范围自动夹取）⇒ 四段分得开 |
| **写入节流** | 120ms + 幅度阈值 8（把平滑重新切成台阶） | **100ms + 阈值 2** |

外加修掉一个**顺手查出来的真缺陷**：
`ambient_enabled` 这个开关**只写不读** —— 桌面/车机一重启，
设置页里开关还显示「开」，但采集与写灯**根本没启动**，灯再也不亮。

### 1.3 量化结果

同一份真实频谱（模拟器上 10Hz 的逐帧日志，最终包 4156）：

| | 单步最大色相跳变 | 单步最大 RGB 距离 |
|---|---|---|
| **老链路**（目标色相直接用） | **51.0°** | **184.4** |
| **新链路**（动感层平滑后） | **12.5°** | **45.2** |
| | **改善 4.1×** | **改善 4.1×** |

（多次跑下来是 **4~5×**：频谱本身每一轮不完全一样，
目标跳变在 45~51° 之间波动，平滑后稳定落在 9~13°。）

单测里同一段合成鼓点的离线复算：**197.6 → 33.0（6.0×）**。

### 1.4 单测

全量 **1222** 个用例全绿（上一轮 1216，本轮 +6）。

---

## 2. 红绿灯：证据链（不是猜的）

### 2.1 怎么拿到证据

模拟器里装的就是**车机同款**高德，所以不用找文档、不用猜：

```bash
adb shell pm path com.autonavi.amapautp.yh
#   → /data/app/…/base.apk
adb pull <base.apk> /tmp/amap_apk/yh-base.apk      # 167,168,086 B
#   sha256 3533b505146a97ac66e1bb5c24b3568e17d5655a6a53dcd56eccd0a460990a0d
unzip -p yh-base.apk classes.dex > classes.dex
jadx --no-res --no-imports --show-bad-code \
  --single-class com.autonavi.floatwindow.FloatViewUtils \
  --single-class-output out/FloatViewUtils.java classes.dex
```

> 喂 `jadx` 的是 **`classes.dex` 而不是 APK** —— 喂 APK 会因为
> 资源表与多 dex 的关系报一堆错，输出反而不全。

### 2.2 取到的结论

| 结论 | 出处（反编译原文） |
|---|---|
| 巡航协议灯态 `0`=红 / `1`=绿 | `FloatViewUtils.<clinit>`：`TYPE_BGS = new int[]{R.drawable.red_night, R.drawable.green_night, R.drawable.green_night}`，浮窗用 `TYPE_BGS[status]` 取背景 |
| 官方协议 `trafficLightStatus == 1` ⇒ **当前是红灯** | `floatapp.trafficlight.GuideTrafficLightVoice.playTrafficLightAnnouncement`：`== 1` 播「即将绿灯」、`== 4` 播「即将红灯」 |
| 官方协议 `dir`：`1`=左转 / `2`=右转 / `4`=直行 | 同一模块的方向分支 |
| `greenLightLastSecond` 高德 8.1.0 起**恒带** | `ib.t()` = `i.a \|\| majorVersion >= 810`；车机装的是 9.5.0 |
| 官方广播的固定形态 | `wh0.c()`：`KEY_TYPE=60073` + `trafficLightStatus` + `dir` + `redLightCountDownSeconds` + `waitRound` +（`ib.t()` 为真时）`greenLightLastSecond` |
| `CameraLightInfo` 字段序 | `waitNum, direction, status, countDown, showType` |

**为什么「== 1 ⇒ 红灯」这么绕**：高德语音模块把 `1` 播成「即将绿灯」，
而高德语音设置项把这句话的意思写死了：

> 开启后红绿灯播报**不播绿灯状态下的即将红灯**，只播报**红灯状态下的即将绿灯**

也就是「即将绿灯」发生在**当前是红灯**的时候 ⇒ `trafficLightStatus == 1` ⇒ 当前**红灯**；
同理 `== 4` ⇒ 当前**绿灯**。

### 2.3 真车走的是哪一套

那份 APK 里**没有** `CruiseTrafficLightBroadcaster` ⇒ 真车发的是**官方标准协议**。
（它倒是**有** `RspTrafficLightsCountdownInfoModel`、`CameraLightInfo/Interact`、
`floatapp/trafficlight/*`、`com/xlsyn/light/*` 这些官方标准协议的东西。）

### 2.4 改法

`core/nav/AmapTrafficLight.kt`：

- 新增常量 `STATUS_STANDARD_RED=1` / `STATUS_STANDARD_GREEN=4` /
  `DIR_STANDARD_LEFT=1` / `DIR_STANDARD_RIGHT=2` / `DIR_STANDARD_STRAIGHT=4`；
- `standard()` 改成按 `trafficLightStatus` 判灯态，**读秒跟着灯态走**；
- 新增 `standardDirectionLabel(dir)`（日志里直接看方向）；
- 枚举认不出来时用 `lampFromStandardStatus()` 兜底：
  **绿灯秒 > 0 ⇒ 绿**，**红灯秒 > 0 ⇒ 红**，否则 `UNKNOWN`（不猜）。

`core/nav/AmapDesktopGuide.kt`：官方协议的日志多带一个 `dir` 字段
（`收到红绿灯数据（官方协议） lamp=… rawStatus=… rawDir=… dir=直行`）。

### 2.5 模拟器核验

```bash
python3 tools/traffic_shot.py standard red 12   r85-traffic-standard-red
python3 tools/traffic_shot.py standard green 8  r85-traffic-standard-green
```

`standard green` 这一档**故意同时带 `redLightCountDownSeconds = 19`** ——
复现老代码的错法，验证新代码不会被它带偏。

| 档 | 日志 | 检出的灯色块 | 红色像素 |
|---|---|---|---|
| 红 12 秒 | `lamp=RED countdown=12 rawStatus=1 rawDir=4 dir=直行` | x[475,519] y[117,162] **45×46** | 1836 |
| 绿 8 秒（带 19 秒红灯倒计时） | `lamp=GREEN countdown=8 rawStatus=4 rawDir=4 dir=直行` | x[478,523] y[117,162] **46×46** | **0** |

图：`emu-verify/r85-traffic-standard-red-zoom.png`、`emu-verify/r85-traffic-standard-green-zoom.png`
（红灯读秒「11 秒」是红的，绿灯读秒「7 秒」是绿的）。

**巡航协议回归**：`lamp=GREEN countdown=8 rawStatus=1` ——
巡航的 `1` 是绿、官方的 `1` 是红，两套表各自正确，没有互相串。

---

## 3. 氛围灯：为什么「僵硬」

### 3.1 原来那层原因（频谱→颜色一步到位）

```
频谱(10Hz) ──► 选最响的低频段 ──► 色相 ──► 乘亮度 ──► 写车机
```

三个地方天然是「跳」的：

1. **色相是离散挑出来的** —— 取「最响的那一段」，段与段之间差 `360/段数`，
   鼓点一变最响的段就换 ⇒ 色相在档位之间**跳变**；
2. **亮度直接乘进 RGB** —— 频谱的强弱原样落成明暗 ⇒ 灯跟着 10Hz 的频谱一起抖；
3. **车机亮度只有三档**（HIGH / LOW / OFF）⇒ 连续变化只能靠我们自己写 RGB，
   写一次是一级台阶。

### 3.2 更深一层：色相**其实压根没动过**（模拟器上才看出来）

频段划分（24 段，60Hz~14kHz 对数分布）里，「低频」取前 4 段 = 60~149Hz。
而 FFT 的频率分辨率 = 采样率 / 采集点数：

| 采集点数 | 44.1kHz 下的 bin 间隔 | 低频四段落在哪些 bin |
|---|---|---|
| 512 | **86.1 Hz** | 四段**全部塌进 bin 1** |
| 1024 | **43.1 Hz** | `[1]` / `[1,2]` / `[2]` / `[2,3]` —— 分得开 |
| 2048 | 21.5 Hz | 分得更开 |

512 点下四段读的是**同一批 bin** ⇒ 功率**永远相等** ⇒
`bassHue` 的「取最响的那一段」**永远取第 0 段** ⇒ **色相恒为 0°（红）**。

（48kHz 下一样：512 点 ⇒ 四段仍全塌进 bin 1。）

实测（模拟器，逐帧日志）：

```
氛围灯帧 targetHue=0.0 hue=0.0 targetLevel=99 brightness=99 bass=0.988 hex=#FC2626
```

`targetHue` **恒为 0.0**，亮度钉在 99 —— 「红、拉满、一动不动」。
这就是用户说的「太僵硬」。

### 3.3 改法

**（1）采集点数 512 → 1024**（`core/audio/SpectrumCapture.kt`）

```kotlin
const val PREFERRED_CAPTURE_SIZE = 1024
const val FALLBACK_CAPTURE_SIZE = 512   // 设备报支持却实际拒绝时退回老口径
```

- `resolveCaptureSize()` 会把它**夹到设备支持的范围**里 ——
  设备只支持 512 就还是 512（行为不比从前差）；
- 实测设备范围：`Visualizer.getCaptureSizeRange()` = **128 ~ 1024**；
- `setCaptureSize` 被拒时**退回 512**（`core/audio/SpectrumAnalyzer.kt`），
  宁可分辨率低一点，也别整个频谱采集挂掉。

**（2）新增 `AmbientMotion`（动感层，纯状态机）**

`core/ambient/AmbientMotion.kt` —— **不碰 Android**（没有 Context、没有 Handler），
只吃「上一帧状态 + 目标 + dt」，所以能直接写 JVM 单测：

| | 做法 | 参数 |
|---|---|---|
| **颜色** | 指数平滑 + **最短路径** + **限速** | τ=320ms，90°/s |
| **亮度** | **快起慢落** | attack τ=50ms / decay τ=320ms |
| **律动** | 低频**起拍检测** + 衰减脉冲 | 包络 τ=160ms，比值 1.15，底噪门 0.08，脉冲 τ=170ms，增益 0.45 |

- 平滑系数用 `1 − e^(−dt/τ)` ⇒ **帧率无关**（掉帧时观感一致）；
- `dt` 夹在 1..1000ms（切后台再回来不该「一步补上」几秒的变化）；
- 色相走**最短路径**：359°→1° 只走 2°，不绕 358°；
- **第一帧直接落位**（不从 0° 扫过去）。

**（3）`AmbientPalette` 拆出目标值**

`targetHue()` / `targetLevel()` / `colorOf(hue, brightness)` ——
平滑要的是**色相本身**，不是已经转成 RGB 的颜色（RGB 再转回色相有量化误差）。
`colorFrom()` 行为不变（仍由三者组合）。

**（4）频谱分辨不了低频时的兜底**（`AmbientPalette.isBassUnresolvable` / `intensityHue`）

设备只支持 512 点时，低频四段仍会塌在一起 ⇒ 色相还是死的。
这时候「音高→色相」是**假信息**，于是改成用低频**强度**映射色相
（安静 260° 深紫 → 响 20° 橙红）：宁可给一条粗但**真的**曲线，
也不要一条卡住不动、看起来像「没声音」的假曲线。

**（5）写入节流跟着改**（`AdaptapiAmbientLightApi`）

`MIN_INTERVAL_MS` 120 → **100ms**（对齐 10Hz 频谱帧率）；
`MIN_COLOR_DELTA` 8 → **2** ——
平滑之后每帧只挪一点点，阈值还是 8 的话**一半以上的帧会被判「没变」丢掉**，
平滑又被切回台阶。

### 3.4 顺手修掉的真缺陷：氛围灯**重启后不恢复**

`ambient_enabled` 这个开关以前**只写不读** ——
全仓只有 `setEnabled()` 写它、`isEnabled()` 读它，
**没有任何地方在进程启动时把它读回来**。

现场：用户开了氛围灯 → 桌面重启 / 车机重启 → 设置页里开关**还显示「开」**，
但采集与写灯**根本没启动**，灯再也不亮，而界面上看不出任何异常。

修法（`MainActivity.kt`）：启动后 5.2 秒（**晚于**录音权限检查）判一次，
是开的就 `AmbientLightController.start()`。
日志留痕：`CarDesk/Ambient : 恢复上次开着的氛围灯`。

### 3.5 加了逐帧日志（「在不在流动」从此看得见）

以前「灯在不在流动」看不出来：车控通道写成功才记颜色（模拟器上根本写不出去），
设置页又只显示**当前**一个颜色。

`AmbientLightController` 现在每帧（100ms）记一行 **D 级**日志
（D 级默认**不落盘**，所以不会把日志文件撑爆；要用 `adb logcat` 抓）：

```
氛围灯帧 targetHue=45.0 hue=38.7 targetLevel=99 brightness=99 bass=0.979 dtMs=143 hex=#FCB026
```

**目标值与平滑后的当前值并排**，于是「目标是台阶、当前是斜坡」一眼可见，
还能直接算出两条链路的单帧跳变。

---

## 4. 模拟器验证：造了一个「能在后台持续播放的音源」

### 4.1 为什么以前验不了

`EMULATOR-VERIFY.md` 第 0 节写过：**模拟器里没有任何能在后台持续播放的音源**，
所以「频谱条在动」「灯随音乐变色」一直只能标成「模拟器验不到，要真机」。

### 4.2 这一轮补上了：`media-probe` 的 `ToneActivity`

用 **`AudioTrack`** 播一段**鼓 + 贝斯**的循环（120 BPM，一小节 2 秒）：

| 成分 | 频率 | 作用 |
|---|---|---|
| 贝斯 | 65.41 / 82.41 / 98.00 / 130.81 Hz 循环 | 低频主能量 ⇒ 色相跟着它走 |
| 底鼓 | 120→45 Hz 下滑 | 低频**瞬态** ⇒ 亮度起拍 |
| 军鼓 | 白噪声 | 中频能量 |
| 踩镲 | 高通白噪声 | 高频能量 |

**为什么必须是 `AudioTrack`**：它走 **AudioFlinger**，
输出混音的 `Visualizer(0)` 才抓得到；
用 `tinyplay` 直接写 `/dev/snd` 是**绕过 AudioFlinger** 的，抓不到。

贝斯的四个音分别落在 60~149Hz 的四段里（也就是色相 0°/15°/30°/45°），
所以「色相在流动」这件事是**可预测**的。

> `media-probe` 是**仓库外的测试工具**（`/Users/roy/Documents/desktopapp/media-probe`），
> 不进 CarDesk 仓库、不进 APK。

### 4.3 新增的取证脚本 `tools/ambient_shot.py`

一气呵成：起 logcat 抓取 → 起音源 → 进氛围灯页（**靠 App 自己的日志**确认进没进去，
不靠截图猜）→ 确保开关是开的 → 连抓 N 帧 → **量化两条链路的单帧跳变**。

```bash
python3 tools/ambient_shot.py 14 r85-ambient
```

### 4.4 结果

氛围灯页（`emu-verify/r85-ambient-05.png`）：

- 频谱状态：**采集中 · 来源：输出混音 · 通道：ECARX AdaptAPI**
- 当前颜色：**#F77025 · 亮度 97%**（不再是恒定的死红）
- 频谱预览：24 根条有形状（低频高、高频低），颜色就是当前氛围灯颜色

逐帧日志（251 行）量化：

```
目标色相（老链路直接用的那个）：单步最大跳 51.0° / RGB 184.4
平滑后色相（新链路）        ：单步最大跳 12.5° / RGB  45.2
⇒ 单步色相跳变降到 4.1×
```

前 12 帧（目标 → 平滑后）：

```
targetHue= 15.0°  hue= 21.1°     ← 目标在台阶之间硬跳
targetHue= 15.0°  hue= 19.4°
targetHue=  0.0°  hue= 12.0°
targetHue= 45.0°  hue= 21.0°     ← 平滑后是斜坡
targetHue= 15.0°  hue= 18.8°
targetHue= 31.9°  hue= 23.5°
targetHue= 15.0°  hue= 20.4°
targetHue= 15.0°  hue= 18.4°
targetHue= 15.0°  hue= 17.5°
targetHue= 15.0°  hue= 16.5°
targetHue= 15.0°  hue= 16.1°
targetHue= 15.0°  hue= 15.7°
```

### 4.5 模拟器上验不到的部分（如实标注）

- **亮度的快起慢落**：模拟器的音频链路对输出做了归一化，
  合成音源的增益从 0.12 调到 0.003，低频段读数都还在 0.93~0.98（顶格），
  所以**目标亮度在模拟器上几乎是常量**，「attack/decay」看不出来。
  这部分靠 `AmbientMotionTest` 的单测覆盖（构造 0 → 1 → 0 的目标值序列，
  断言上升快、下降慢）。
- **真实音乐的效果**：模拟器上只有合成音源，
  真实音乐的频谱分布与真车车机链路（`AdaptAPI` 写灯）只能在真车验。

---

## 5. 改动清单

**新增**

| 文件 | 作用 |
|---|---|
| `app/.../core/ambient/AmbientMotion.kt` | 动感层（纯状态机：色相平滑 / 亮度快起慢落 / 起拍脉冲） |
| `app/src/test/.../core/ambient/AmbientMotionTest.kt` | 14 个用例（含新旧链路单帧跳变对比） |
| `tools/ambient_shot.py` | 氛围灯取证：起音源 → 进页 → 抓连续帧 → 量化跳变 |

**改**

| 文件 | 改动 |
|---|---|
| `core/nav/AmapTrafficLight.kt` | 官方协议按 `trafficLightStatus` 判灯态（1=红/4=绿）、读秒跟着灯态走、枚举兜底、方向枚举、类 KDoc 写全取证链 |
| `core/nav/AmapDesktopGuide.kt` | 官方协议日志加 `dir` |
| `core/ambient/AmbientPalette.kt` | 拆出 `targetHue()` / `targetLevel()` / `colorOf()`；新增 `isBassUnresolvable()` / `intensityHue()` 兜底 |
| `core/ambient/AmbientLightController.kt` | `update()` 改成「目标 → 动感层 → 颜色」三步；新增逐帧日志 |
| `core/ambient/AdaptapiAmbientLightApi.kt` | 写入节流 120ms→100ms、幅度阈值 8→2 |
| `core/audio/SpectrumCapture.kt` | 采集点数 512→1024（设备范围自动夹取）+ 兜底 512 |
| `core/audio/SpectrumAnalyzer.kt` | `setCaptureSize` 被拒时退回兜底点数 |
| `MainActivity.kt` | 启动时恢复上次开着的氛围灯 |
| `tools/traffic_shot.py` | 新增 `standard` 协议档（绿灯档故意带正数红灯倒计时） |
| `app/src/test/.../core/nav/AmapTrafficLightTest.kt` | 官方协议段整段重写（含「绿灯时不拿红灯倒计时当读秒」） |
| `app/src/test/.../core/ambient/AmbientPaletteTest.kt` | +4 个（低频分辨不出来的兜底 / 强度映射曲线） |
| `app/src/test/.../core/audio/SpectrumCaptureTest.kt` | 采集点数断言随口径更新 +1 个 |

**工具（仓库外）**：`media-probe` 加 `ToneActivity`（合成音源）。

## 6. 单测

全量 **1222** 个用例全绿（上一轮 1216，本轮 +6）。

| 文件 | 新增 | 覆盖 |
|---|---|---|
| `core/ambient/AmbientMotionTest.kt` | 14 | 色相最短路径 / 限速 / 帧率无关 / 亮度快起慢落 / 起拍检测 / dt 夹取 / 第一帧落位 / **新旧链路单帧跳变对比（197.6 → 33.0）** |
| `core/ambient/AmbientPaletteTest.kt` | +4 | 低频四段相等⇒走强度映射 / 不相等⇒照旧取最响段 / 全零不算「分辨不出来」 / 强度→色相曲线 |
| `core/audio/SpectrumCaptureTest.kt` | +1 | 采集点数口径（期望 1024、兜底 512、夹取规则） |
| `core/nav/AmapTrafficLightTest.kt` | 整段重写 | 官方协议红/绿/未知 / **绿灯时不拿红灯倒计时当读秒** / 方向枚举 / 巡航协议回归 |

---

## 7. 发布

- 产物：`dist/CarDesk-ecarx-cardesk-4156.apk`，1,963,484 字节，
  SHA-256 `e2d63ba8014514e4e84ceb460b531bf773c404d3228786e53d1288071b03e194`
- 版本：`versionCode 4156` / `versionName 1.0.1060-system`
- 干净全量构建（`clean assembleSystem testDebugUnitTest`）+ 1222 单测全绿
- 提交 `f0707cb`（第八十五轮：红绿灯画反 + 氛围灯动感）已推 `origin/main`

### 7.1 GitHub Release

| 项 | 值 |
|---|---|
| 仓库 | `Bigcaibird/cardesk`（Public） |
| Tag | `v1.0.1060`（= `versionCode 4156 - 3096`；本轮是**发布时新建** tag，不是复用已存在的 tag） |
| Release 名称 | `CarDesk 4156 / 1.0.1060-system` |
| 资产 | `CarDesk-ecarx-cardesk-4156.apk`（1,963,484 字节） |
| SHA-256 | `e2d63ba8014514e4e84ceb460b531bf773c404d3228786e53d1288071b03e194` |
| Release 标签 | `Latest`（非预发布） |
| 发布提交 | `f0707cb` |
| 发布时间 | `2026-10-30T13:05:54Z` |

**API 回读核验**（`releases/tags/v1.0.1060` → 200）：

```
tag_name        : v1.0.1060
name            : CarDesk 4156 / 1.0.1060-system
draft           : False
prerelease      : False
target_commitish: main
published_at    : 2026-10-30T13:05:54Z
html_url        : https://github.com/Bigcaibird/cardesk/releases/tag/v1.0.1060
body_head       : '**改动点**\r\n\r\n- 修红绿灯：官方标准协议改按 `trafficLight'
asset           : CarDesk-ecarx-cardesk-4156.apk 1963484 bytes uploaded
  digest        : sha256:e2d63ba8014514e4e84ceb460b531bf773c404d3228786e53d1288071b03e194
releases/latest = v1.0.1060（与 tag release 同一个 id）
```

**同一个包，三处一致** —— 本地 `dist/`、GitHub 资产、GitHub 自算 digest，
sha256 全是 `e2d63ba8…3e194`；再从 Release 的下载链接把包拉下来 `cmp`，
**逐字节相同**（下载回来 1,963,484 字节，sha256 复算一致）。

Release 正文只写改动点，不写实现细节（与用户要求一致）：

```
**改动点**

- 修红绿灯：官方标准协议改按 `trafficLightStatus` 判灯态（`1`=红 / `4`=绿），读秒跟着灯态走 —— 绿灯不再被画成红灯
- 氛围灯：颜色不再「跳」而是「流」—— 色相最短路径 + 限速平滑，亮度快起慢落，低频起拍再叠一下脉冲
- 修氛围灯色相恒定不变：频谱采集点数 512 → 1024（512 点下低频四段会塌进同一个 FFT bin，色相永远是 0°）
- 修氛围灯重启后不亮：开关状态以前只写不读，桌面/车机重启后采集与写灯根本没启动
- 氛围灯加逐帧日志（目标色相 / 平滑后色相 / 亮度），并新增模拟器取证脚本
```

---

## 8. 这一轮新踩的 GitHub 发布坑

1. **会话压缩后旧的 ref 全部失效，得重新 inspect** ——
   本轮是从压缩恢复的，第一件事就是重新 `inspect` 取当前 ref；
   压缩前记下的 `browser-element:8-…` 拿去用，`upload_files` 直接报
   `STALE_ELEMENT_REF: Browser target expired`。
2. **Tag 面板里的「Create」按钮 ref 会指到 markdown 工具栏上** ——
   `Create new tag` 面板打开后，DOM 里那个 `Create` 按钮的 rect（x=413,y=466）
   正好压在 markdown 工具栏「Unordered list」按钮（x=433,y=469）上，
   按 ref 点击**落到了「无序列表」**（动作后的自动截图里能看到 tooltip）。
   ⇒ **tag 面板里的按钮别按 ref 点**。本轮的正确做法是：填完 tag 名后
   **不再点面板的 Create** —— 面板上那句「v1.0.1060 will be created on publish」
   已经说明 tag 选中了，直接点「Publish release」即可（GitHub 在发布时建 tag）。
3. **`upload_files` 报 `browser_action_failed`，文件其实已经挂上了**（老坑又踩一次）——
   报错后页面 URL 从 `releases/new` 变成
   `releases/edit/untagged-69f27a382702bf1fb3c1`（草稿自动落成），
   资产行 `data-state="uploaded"`、`id=692130430`、大小 `(1.87 MB)`、
   文件名 `CarDesk-ecarx-cardesk-4156.apk`。⇒ 别重传。
4. **资产行的 `input.js-release-asset-filename` 的 `value=` 仍然是空字符串** ——
   文件名写在 `delete-pending` 那个 `role=status` 里的
   `span.js-release-asset-filename`。要判断「资产在不在」，
   看 `ul.uploaded-files li[data-state="uploaded"]` 的
   `.js-release-asset-filesize`（`(1.87 MB)`）和 asset id，别看 `value=`。
5. **发布按钮的 `click` 这轮报 `UNEXPECTED_NAVIGATION`（换了种报错）** ——
   `click` + `selector: button.btn-primary[type="submit"]` 返回
   「Ordinary click changed the active Browser page unexpectedly」，
   同时 `navigation.urlChanged: true`、`generation: 3`。
   **这其实是发布成功的正常表现**：紧接着 `query(kind="text")` 已经在
   `/releases/tag/v1.0.1060` 页面，读到了正文 5 条改动点、资产名和 digest。
   判成败依旧一律看 API。
6. **想验证资产名，`query(kind="semantic", text=文件名)` 不好使** ——
   它把大半个表单都算成匹配项，payload 巨大且没直接给出资产行。
   `query(kind="dom", selector="[class*=\"asset\"]")` 一次就拿到整个
   `ul.uploaded-files`，最省事。

---

## 9. 未能覆盖 / 待办

- **红绿灯枚举建议真车复核一次**：官方协议 `trafficLightStatus` 的取值
  1=红 / 4=绿是从**高德自己的代码**里查出来的（语音模块 + 语音设置项文案，
  两处互相印证），但仍建议真车跑一次导航时抓这行日志复核：
  `CarDesk/NavUI: 收到红绿灯数据（官方协议） lamp=… rawStatus=… dir=…`。
- **真车的 `greenLightLastSecond`**：版本门 `ib.t()` 表明高德 ≥ 8.1.0 恒带这个字段，
  车机装的是 9.5.0 —— 但**真车上这个字段到底带不带、语义是不是「绿灯剩余秒数」**
  仍要真车跑一次绿灯确认。
- **真车能否支持 1024 点 FFT**：模拟器实测范围是 128~1024，
  车机 ROM 的 `Visualizer.getCaptureSizeRange()` **只能在真车上读**。
  若真车只支持 512，色相会退回「低频强度→色相」那条兜底曲线
  （仍会变，但不再是「跟着音高走」）。
- **亮度的 attack/decay 只在单测里验过**：模拟器音频链路有归一化，
  目标亮度几乎恒定，看不出快起慢落（见 §4.5）。
- **真实音乐的效果**：合成音源只能证明「链路通、颜色会动、跳变降了 4 倍多」，
  真实音乐的频谱分布与最终观感要在真车上听/看。
- **Worker 仍未部署**（上一轮遗留）：`cd worker && npx wrangler login &&
  npx wrangler secret put UPLOAD_TOKEN && npx wrangler deploy`。车机侧不依赖它。
- **酷狗 KRC 在车机网络下的可达性**（更早遗留）：仍待真机验证。
- **R2 密钥暴露已核查并立项处置**：Access Key ID 的完整明文在 r83 报告的
  3 个提交里公开过（仓库是 Public，现在仍能匿名下载），Secret 从未外泄。
  处置方式已定 = **在 Cloudflare 控制台轮换整对 AK/SK**；
  核查范围、证据与处置步骤见 **§10**。

---

## 10. R2 密钥暴露核查与处置

用户问起「Access Key ID 与 Secret 发布到过外部吗」，本轮做了一次**实测核查**
（不靠记忆）：扫了当前工作树、全部历史提交、全部 git blob、14 个 Release 正文、
所有提交信息、远端分支/tag，以及仓库外的 `docs/` `analysis/` `media-probe/`。

### 10.1 结论速览

| 凭据 | 是否外泄 | 范围 |
|---|---|---|
| **Access Key ID（32 位）** | **是 —— 现在仍可匿名公开取到** | `emu-verify/REPORT-r83.md` 的 3 个提交（`12432f3`、`5b3b2cb`、`ff4ccf5`），完整明文各 2 行 |
| **Secret Access Key（64 位）** | **否 —— 从未进过任何公开渠道** | 仓库 / 全部历史 / 全部 blob / Release 正文 / 提交信息 / 仓库外目录，均无 |

### 10.2 暴露面（实测证据）

仓库可见性：`GET /repos/Bigcaibird/cardesk` → `private: false` / `visibility: public`。

匿名直取历史版本（无需登录、无需 token）：

```
https://raw.githubusercontent.com/Bigcaibird/cardesk/12432f3/emu-verify/REPORT-r83.md  → 含完整 AK 的行数：2
https://raw.githubusercontent.com/Bigcaibird/cardesk/5b3b2cb/emu-verify/REPORT-r83.md  → 含完整 AK 的行数：2
https://raw.githubusercontent.com/Bigcaibird/cardesk/ff4ccf5/emu-verify/REPORT-r83.md  → 含完整 AK 的行数：2
https://raw.githubusercontent.com/Bigcaibird/cardesk/main/emu-verify/REPORT-r83.md      → 含完整 AK 的行数：0（已遮盖）
```

- 当前 `main` 里的 `REPORT-r83.md` 已遮盖成 `0b6ea892…d628f2`（头 8 + 尾 6），
  遮盖动作发生在 `b628640`（第八十四轮）。
- 但**历史不会消失**：`git show 12432f3:emu-verify/REPORT-r83.md` 同样取得到完整明文。
- 干净的地方（也都实测过）：14 个 Release 的正文（连 AK 的 8 位片段都没有）、
  所有提交信息、其它分支与 tag、仓库外的 `docs/` `analysis/` `media-probe/`。
- 仓库里**没有** `.env` / `.dev.vars` / pem / key 之类会夹带密钥的文件。

### 10.3 Secret 为什么判定「未外泄」

- 不在仓库任何文件、**任何历史提交、任何 git blob**
  （逐提交 `git grep` + 全部对象 `cat-file` 扫描，均无命中）
- 不在任何 Release 正文（14 个全查）、不在任何提交信息
- 不在仓库外的 `docs/`、`analysis/`、`media-probe/`
- 代码侧的凭据来源本身是干净的：
  - `worker/src/index.js` 只读 `env.UPLOAD_TOKEN`，没配就 fail closed
  - `tools/publish_apks.py` 只读 `os.environ["R2_AK"] / ["R2_SK"]`，缺了就退出
- 它只出现在**本机会话日志**里 —— 来自用户当初粘贴的那条消息。
  说清楚：把密钥发进对话的那一刻，它必然随对话内容经过模型服务，
  这是「交给助手」的固有路径；但它没进代码库、没进 Release、没进任何公开渠道。

### 10.4 风险评估

- **AK 单独拿到签不了名** —— S3 签名要 AK + SK 配对，缺一把就签不出来，
  所以这把 AK 不能直接用来读写 bucket。
- 真实风险是两条：① **SK 一旦泄露，AK 立刻变成可用凭据**；
  ② AK 作为公开标识，可被用于定向攻击或钓鱼。
- 结论：**按已泄露处理**。仓库是 Public，历史里躺着一个完整的长期凭据 ID，
  第三方爬虫随时可能抓走快照。

### 10.5 处置（已与用户确认 · 已执行完，证据见 §10.7）

**唯一的有效处置是轮换整对密钥**（在 Cloudflare 控制台执行，需要用户自己的账号）：

1. Cloudflare 控制台 → R2 → API Tokens → 轮换 / 新建一对 AK/SK
2. 本机更新环境变量：`R2_AK` / `R2_SK`（`tools/publish_apks.py` 只认这两个）
3. 旧 AK 作废；Worker 侧若存过，也一并更新
4. 以后任何文档、报告、提交里都不再出现完整 AK/SK

**为什么不做「洗 git 历史」**：`git filter-repo` 能重写所有 commit SHA 和 14 个 tag，
但改不掉「已经公开过」这个事实 —— 爬虫快照、已克隆的副本都收不回来，
代价（所有人克隆失配、tag 全变）远大于收益。**轮换才是把风险真正关掉的动作。**

### 10.6 下次自查（命令留在报告里，随时可复跑）

```bash
cd /Users/roy/Documents/desktopapp/cardesk
# 1) 当前工作树 + 全部历史提交
#    注意：用「前缀 + 长度通配」的形状匹配，别把真串写进命令行（命令行会进日志）
git grep -nE '0b6ea892[0-9a-f]{24}|a90b[0-9a-f]{60}' -- .
for c in $(git rev-list --all); do git grep -lE '0b6ea892[0-9a-f]{24}' "$c" -- .; done
# 2) 公开面：Release 正文
curl -s "https://api.github.com/repos/Bigcaibird/cardesk/releases?per_page=100" \
  | grep -oE '0b6ea892[0-9a-f]{24}|a90b[0-9a-f]{60}' | sort -u
# 3) 匿名直取历史版本（最直接的暴露面）
for c in $(git rev-list --all); do
  echo -n "$c "
  curl -s "https://raw.githubusercontent.com/Bigcaibird/cardesk/$c/emu-verify/REPORT-r83.md" \
    | grep -c '0b6ea892[0-9a-f]{24}'
done
```

> **坑（这轮踩到的）**：`git log --all -S'0b6ea892'` 会报出一条**假阳性**
> （本轮是 `587db2f`，其父提交与它自己的树里都查不到这串，diff 里也没有），
> 所以**别拿 `-S` 当判据**，以逐提交 `git grep` 扫描为准。

### 10.7 轮换闭环（已完成）

用户在 Cloudflare 控制台**删掉旧令牌、新建了一对新的 R2 API Token**
（账号级、bucket = `file`、对象读写），按下面四步验证闭环。

**① 旧令牌确认失效** —— 用旧 AK/SK 打 R2，明文不进命令行
（从本地会话记录里按形状取，见 §10.6）：

```
旧 AK/SK → GET file/cardesk/latest.json → HTTP 401 Unauthorized
```

⇒ **git 历史里那把 AK 现在已经没有任何权限**，轮换真正生效了。

**② 新令牌上传 4156**（`tools/publish_r2.py`，脚本自带回读校验）：

```
== 上传成功：cardesk/CarDesk-ecarx-cardesk-4156.apk
== 回读一致：1963484 字节，sha256 e2d63ba8014514e4e84ceb460b531bf773c404d3228786e53d1288071b03e194
== 上传成功：cardesk/latest.json
== 回读一致：852 字节，sha256 50c1e62ff6282c46f592f53ee4123b64a1a791fa7727a87d36f6cbf1fbae686f
```

云端应用库也同步挂上了 4156（`tools/publish_apks.py`）。

**③ 公网匿名读取核验**（自定义域 `file.chuxi.bid`，不需要任何凭据）：

```
cardesk/CarDesk-ecarx-cardesk-4156.apk → http=200  1963484 字节  用时 64.7 秒
  sha256 e2d63ba8014514e4e84ceb460b531bf773c404d3228786e53d1288071b03e194
  cmp 本地 dist/CarDesk-ecarx-cardesk-4156.apk → BYTE_IDENTICAL
cardesk/latest.json → version 1.0.1060 / versionCode 4156 / tag v1.0.1060
                      apk.url = cardesk/CarDesk-ecarx-cardesk-4156.apk
cardesk/apks.json   → 2 个条目（CarDesk 4156、CarDesk 4150）
```

**④ 车机侧端到端**（模拟器 `emulator-5554`，装的就是 4156）：
设置 →「在线更新」→「检查更新」，日志：

```
[EVT] 检查更新（手动触发） url=https://file.chuxi.bid/cardesk/latest.json
检查更新：已是最新 current=1.0.1060-system latest=1.0.1060
```

对话框显示「状态：已是最新版本（1.0.1060-system）」「线上最新：1.0.1060」。
截图：`r85-settings.png`（设置面板）、`r85-update-page.png`（在线更新页）、
`r85-update-check.png`（对话框初始态）、`r85-update-result.png`（检查结果）。

> 这一条证明的是：**清单能公网拉到 → 能解析 → 版本比较正确**。
> 模拟器上装的就是 4156，所以显示「已是最新」；
> 「有新版本 → 下载 → 校验 → 静默安装」那条链路本轮没重跑
> （要把版本降回 4150 才触发得了，而 `release.sh` 只升版本不降）。

**闭环结论**：历史里那把 AK 已经作废（401），新令牌能写 R2，
线上清单与包和本地逐字节一致，车机端能正常读到并比对版本。
**这轮安全事件的处置到此闭环**；剩下的只有 §9 里那些真车项。
