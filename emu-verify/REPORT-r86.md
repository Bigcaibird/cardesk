# 第八十六轮 · 红绿灯读秒锯齿（3↔2 来回跳）

> 诉求原文：
> 1. 「绿灯时倒计时一直在 3-2 秒之间，未能正确反应倒计时」
> 2. 「你现在能拿到绿灯倒计时了吗？你这么改如果没拿到绿灯倒计时，不一样还是有问题？」
>
> 日期：2026-10-31　被测包：`com.ecarx.cardesk` 4157 / `1.0.1061-system`
> 设备：`emulator-5554`（AVD `cardesk-aosp`，android-10 / API 29 / 1920×1080@160dpi）

---

## 1. 结论

### 1.1 根因：读秒**每收到一帧就重新锚回原始值**

旧显示算法是这么写的（`AmapDesktopGuide.withRemaining`）：

```
显示值 = 原始值 − (本机时钟 − 最后一帧到达时刻) / 1000
```

问题在 **「最后一帧到达时刻」每次收到新帧就被刷新**：

- 高德**不是每秒都刷**这个字段（真车帧间隔约 2 秒，模拟器实测 **2.04 秒**）；
- 于是「本机经过的秒数」每 2 秒就被清零一次 ——
  **已经走掉的秒数被原样加回去**。

用户看到的「3 → 2 → 3 → 2」就是这个：`3` 是刚收到帧时的原始值，
`2` 是本机又走了 1 秒的中间态，2 秒后新帧一到又回到 `3`。
**读秒永远不会走到 0。**

这不是「绿灯特殊」，是**所有灯态**的读秒都有这个毛病（红灯读秒同样受益于本轮修复）。

### 1.2 改法：新增读秒**显示状态机** `TrafficCountdown`（只减不增）

新规则（同一灯态内）：

| 情况 | 处理 |
|---|---|
| **灯态变了**（红↔绿） | 重新起一轮，直接拿这一帧的原始读秒当锚点 |
| 原始读秒比当前显示**更小** | 供应商说「不多了」→ **以它为准**，重新锚点 |
| 原始读秒**没变小**（多半是供应商没刷新） | **沿用自己减出来的值**，只把时钟挪到这一帧，继续往下走 |
| 读秒**归零** | **不再显示数字**，直到灯态变化（不猜灯态） |
| 信号断档超过 TTL（5 秒） | 判成「这一轮结束了」，重新起一轮 |

关键点：**供应商的原始值只用来「往下压」，不用来把秒数加回去。**

### 1.3 直接回答用户的追问：「你现在能拿到绿灯倒计时了吗？」

**诚实回答：还不能下定论。**

分开说两件事：

1. **「显示层不再锯齿」—— 这件事已经做到，而且不依赖任何未验证的假设。**
   「同一个灯态里读秒只减不增」是显示层的自洽要求，
   与 `greenLightLastSecond` 到底是什么语义**无关**。
   模拟器上已经用 A/B 对比证明：旧包 `6→5→6→5`（回跳），
   新包 `6→5→4→3→2→1→消失`（不回跳）。

2. **「拿到的这个数字，是不是高德真正意义上的绿灯剩余秒数」—— 这一条仍然没有证据。**
   本轮把高德车机版整包反编译了一遍，结论是**高德自己都不用这个字段画读秒**
   （见 §2），也就是说**这个字段的语义只存在于服务端**，
   本地没有任何可对照的实现。

   所以本轮的做法是**装探针、不猜**：
   官方协议的 INFO 日志现在同时打出**两个原始字段**
   `rawRed`（`redLightCountDownSeconds`）与 `rawGreen`（`greenLightLastSecond`），
   一次真车导航跑完就能看出**到底是哪一个字段在动、往哪个方向动**。

   > 换句话说：**如果真车日志显示 `rawGreen` 一直不变、而 `rawRed` 在往下走**，
   > 那就说明绿灯读秒该取 `redLightCountDownSeconds`（也就是说高德把两个字段的用法
   > 跟字段名对不上），届时把取值那一行改掉即可，**状态机一行都不用动**。
   > 这也是为什么把「不锯齿」和「取哪个字段」拆成两件事做。

### 1.4 量化结果

| 项 | 旧包（4156） | 新包（4157） |
|---|---|---|
| 模拟器读秒取值序列（原始值恒为 6，帧间隔 2.04s） | `6 → 6 → … → 5 → 6 → … → 5` | `6 → 5 → 4 → 3 → 2 → 1 → 消失` |
| 不同取值个数（distinct） | **2** | **6** |
| 回跳（离开后又回来） | **有（锯齿）** | **无（单调）** |
| 新包日志里的显示值 | 旧包无此日志 | `shown=6 → 5 → 4 → 3 → 2 → 1 → null` |

红灯同样验了一遍（原始值恒为 12）：旧包同样锯齿，新包 `12 → 11 → … → 4` 单调。

---

## 2. 取证：`greenLightLastSecond` 到底是什么（**不猜**）

### 2.1 怎么拿到的证据

跟第八十五轮同一套路：从模拟器里 pull 出**车机同款高德**（`com.autonavi.amapautp.yh`），
全量反编译 `classes.dex`。

```bash
adb shell pm path com.autonavi.amapautp.yh
adb pull <base.apk> /tmp/amap_apk/yh-base.apk      # 167,168,086 B
shasum -a 256 /tmp/amap_apk/yh-base.apk
#   → 3533b505146a97ac66e1bb5c24b3568e17d5655a6a53dcd56eccd0a460990a0d
unzip -o -q yh-base.apk classes.dex classes2.dex
jadx --no-res --no-imports --show-bad-code -d out classes.dex
#   → 3042 个类
```

### 2.2 查到的结论

| 结论 | 出处（反编译源码逐字可对） |
|---|---|
| 模型只有 5 个 int 字段：`dir` / `trafficLightStatus` / `redLightCountDownSeconds` / `waitRound` / `greenLightLastSecond` | `com/autonavi/amapauto/protocol/model/service/RspTrafficLightsCountdownInfoModel.java`（`setProtocolID(80189)`） |
| **本地不做任何计算**，服务端透传 | 上面这个类里只有 getter/setter，没有一行业务逻辑 |
| 广播构造只做 `putExtra` | `defpackage/wh0.c()`：`intent.putExtra(KEY_TYPE, 60073)` + 灯态 + `dir` + 红灯读秒 + `waitRound`，`greenLightLastSecond` 还带**版本门** `if (ib.t())`（8.1.0 起才带） |
| **高德自己没有任何消费端** | 全仓 `getGreenLightLastSecond()` 只有 4 处命中：`wh0.java`（putExtra）、JSON 序列化器、JSON 反序列化器、模型自己的 getter —— **没有任何画 UI / 算读秒的地方** |
| 官方协议灯态 `1`=红 / `4`=绿（沿用上轮结论，再确认一次） | `floatapp/trafficlight/GuideTrafficLightVoice.java` 第 105~108 行：`trafficLightStatus == 1` → 播「即将绿灯」；`== 4` → 播「即将红灯」（配合高德语音设置项文案 ⇒ 「即将红灯」说的是**当前是绿灯**） |
| 巡航协议的 `countdown` = **距下次变灯的秒数** | `floatapp/trafficlight/CruiseTrafficLightVoice.java`：`status==1`（绿）时用 `countdown` 播「即将红灯」 |

> 参照工程 `analysis/findings/16-amap-traffic-light.md` §3.2 早就写着
> 「`greenLightLastSecond` **不当成绿灯倒计时用**（有条件门、语义未验证）」，
> `uni-launcher/analysis/amap-traffic-evidence.json` 的 unverified 清单里也列着它。
> 本轮把这两条**用新反编译重新核实了一遍**，结论一致。

### 2.3 结论落地到代码

- `AmapTrafficLight.EXTRA_GREEN_LAST_SECOND` 的 KDoc 已重写：
  写明「**语义至今未验证**」「上一轮照字段名当倒计时用了，真车实测绿灯读秒 3↔2 跳」
  「服务端透传、**高德自己都没有消费端**」「靠真车日志 `rawRed`/`rawGreen` 定论，**不猜**」。
- `AmapTrafficLight.standard()` **暂时不改取值**：读秒仍跟着灯态走
  （红→`redLightCountDownSeconds`，绿→`greenLightLastSecond`），
  等真车日志给出结论再调。**显示层已经与这个选择解耦**（§1.2）。

---

## 3. 代码改动

| 文件 | 改动 |
|---|---|
| `core/nav/TrafficCountdown.kt` | **新增**。读秒显示状态机（**纯逻辑**，不碰 Android，可直接 JVM 单测）。方法：`accept(lamp, raw, atMs)` / `current(atMs)`（**不推进状态**）/ `reset()` / `lastAnchorAt()` |
| `core/nav/AmapDesktopGuide.kt` | 新增 `standardCountdown` / `cruiseCountdown` 两个状态机；两套协议的接帧处喂状态机（断档超 TTL 先 `reset()`）；**删掉旧的 `withRemaining()`**（每帧重锚就是锯齿根因）；`trafficLight()` 改用状态机的 `current(now)`；新增 **D 级日志「红绿灯读秒显示」**（`lamp`/`raw`/`shown`）；官方协议 INFO 日志**新增 `rawRed`/`rawGreen` 两个原始字段**；`stopGuide()` 里 `reset()` 两个状态机 |
| `core/nav/AmapTrafficLight.kt` | `EXTRA_GREEN_LAST_SECOND` 的 KDoc 重写（语义未验证 + 取证结论 + 怎么定论） |
| `tools/traffic_countdown_shot.py` | **新增**。读秒**时间序列**取证脚本（见 §4.1） |
| `tools/traffic_shot.py` | 清脚本的匹配条件放宽（见 §4.2 的坑） |

### 3.1 为什么状态机要单独一个类

- 它是**纯逻辑**（不 import 任何 Android 类），可以像 `AmbientMotion` 那样直接 JVM 单测；
- 显示层与「取哪个字段」**解耦** —— 以后无论是换字段还是改语义，
  状态机都不用动；
- KDoc 里写清**为什么**（每帧重锚 = 锯齿），避免以后有人「优化」回去。

---

## 4. 模拟器取证

### 4.1 新增取证脚本 `tools/traffic_countdown_shot.py`

`tools/traffic_shot.py` 只截**一帧**（判灯色、看数字和「秒」字有没有重叠），
但锯齿是**时间维度**上的缺陷，截一帧看不出来。
新脚本按秒连抓 N 帧，对**读秒那一小块**做 md5：

- 同一个数字 ⇒ 逐像素相同 ⇒ 同一个哈希；
- 于是哈希序列直接给出「读秒到底在几个值之间跳」。

判定口径（脚本自己会打印）：

- `distinct = 2` 且**回跳=有** ⇒ 锯齿（离开某个值后又回来了）；
- `distinct ≥ 3` 且**回跳=无** ⇒ 单调递减。

脚本还会把 logcat 里的 `红绿灯读秒显示`（`raw`/`shown`）一并抓出来 ——
新包有这行日志，**数字是直接读出来的**，不用靠像素猜。

```bash
python3 tools/traffic_countdown_shot.py r86-after --lamp green --raw 6 --frames 14 --interval 0.45 --warmup 0.8
```

### 4.2 抓图时踩的三个坑（都出过假图）

| 坑 | 现象 | 处置 |
|---|---|---|
| **设置面板还开着** | 截到的是「在线更新」弹窗，红绿灯 chip 根本没渲染；脚本把弹窗的绿色按钮当成灯圆（`x[570,737] 168×62`） | 抓图前自动按两次 BACK 回场景页；灯圆识别**限制在导航卡区域 + 尺寸 38~54px** |
| **残留广播脚本没杀掉** | 上一轮的脚本还活着，和新脚本一起发广播 ⇒ 帧率翻倍、读秒反而不跳了（**假象**） | 新脚本名里没有 "broadcast"，原来的清理逻辑匹配不到 ⇒ 匹配条件改成「含 `.sh` 且名字里有 broadcast **或** countdown」；`traffic_shot.py` 同步放宽 |
| **读秒是倒着走的** | `raw=3` 时等 3 秒再去抓，整段递减已经走完了，截出来全是「没有读秒」 | 脚本加 `--warmup`（起播后等多久开始抓），本轮用 `--raw 6 --warmup 0.8` |

### 4.3 A/B 对比（旧包 4156 vs 新包 4157）

条件完全一致：官方标准协议、`trafficLightStatus=4`（绿灯）、**原始读秒恒定**、
广播 **2 秒一帧**（复刻真车节奏）、每 ~0.9 秒抓一帧。

**旧包 4156**（`emu-verify/r86-before-frames.png`）：

```
连续段序列：ec2eab33 → 53e70225 → ec2eab33 → 53e70225
distinct=2   回跳=有（锯齿：同一个值离开后又回来了）
```

看图就是：`6 秒 → 6 秒 → … → 5 秒 → 6 秒 → 6 秒 → … → 5 秒` ——
**从 5 又跳回 6**，与用户描述的「一直在 3-2 之间」同构。

**新包 4157**（`emu-verify/r86-after-frames.png`）：

```
连续段序列：53e70225 → 5881bec5 → d91bdb0a → 5fca5b43 → 5817c68c → 9b24c14b
distinct=6   回跳=无（单调：一路往下走）
```

看图就是：`5 → 4 → 4 → 3 → 2 → 2 → 1 → 1 → 消失`，**一路往下，不再回来**。

同一份日志里，新包自己打的显示值序列（`raw` 恒为 6 就是模拟器刻意造的「供应商不刷新」）：

```
红绿灯读秒显示 lamp=GREEN raw=6 shown=6
红绿灯读秒显示 lamp=GREEN raw=6 shown=5
红绿灯读秒显示 lamp=GREEN raw=6 shown=4
红绿灯读秒显示 lamp=GREEN raw=6 shown=3
…
红绿灯读秒显示 lamp=GREEN raw=6 shown=null
```

`shown` 一路往下且**再没回到 6** —— 这就是修好的直接证据。

### 4.4 红灯同样受益

`--lamp red --raw 12`：`12 → 11 → 10 → 9 → 8 → 7 → 6 → 5 → 4`，
`distinct=7`、**回跳=无**（`emu-verify/r86-red-frames.png`）。

### 4.5 稳定性

- 抓图全程 pid `21836` 未变（`emu_regress.py check 20` → 「✅ 无崩溃」）；
- logcat 里 `Fatal signal` / `FATAL EXCEPTION` 计数 **0**；
- `/data/tombstones` 无新增。

---

## 5. 单测

全量 **1236** 个用例全绿（上一轮 1222，本轮 **+14**）。

| 文件 | 新增 | 覆盖 |
|---|---|---|
| `core/nav/TrafficCountdownTest.kt` | **14** | 第一帧当锚点 / 无新帧时按时钟减 / `current()` 不推进状态 / 归零不显示 / **原始值恒定 + 2 秒帧间隔时单调递减不回跳（真车症状复现）** / 新帧原值不变不加回秒数 / 原值更小以它为准 / 供应商把秒数加回去时不跟着涨 / 归零不被新帧复活 / 灯态变了重新起一轮 / 灯态变了旧归零不跟过来 / 这帧没带读秒沿用上帧 / `reset()` / `lastAnchorAt()` |
| `core/nav/AmapTrafficLightTest.kt` | 28（回归未破坏） | 官方协议红/绿/未知 / 巡航协议 / 方向枚举 |

---

## 6. 改动清单与发布

- 干净全量构建（`clean assembleSystem testDebugUnitTest`）+ **1236 单测全绿**；
- 提交 `0385247`「第八十六轮：红绿灯读秒锯齿（3↔2 来回跳）+ 真车取证探针」已推 `origin/main`；
- 产物：`dist/CarDesk-ecarx-cardesk-4157.apk`
  - `com.ecarx.cardesk` / versionCode **4157** / versionName **1.0.1061-system**
  - 1,964,472 B
  - SHA-256 `2bc9301bb4a87d8516e6e9782145ce45e244bc6fe717f82781f71d74c1782de3`
- R2 更新清单：`dist/cardesk-latest.json`，对象 key `cardesk/CarDesk-ecarx-cardesk-4157.apk`

### 6.1 GitHub Release：**表单已备好，但用户选择「先不发布」**

浏览器里（github.com / Bigcaibird，仓库 `Bigcaibird/cardesk`）的
「New release」表单已经按下面的内容填好并**上传了 APK 附件**：

| 项 | 值 |
|---|---|
| Tag | `v1.0.1061`（新建，Target `main` ⇒ 提交 `0385247`） |
| 标题 | `CarDesk 4157 / 1.0.1061-system` |
| 正文 | 只写改动点的 4 条（读秒锯齿 / 读秒状态机 / `rawRed`·`rawGreen` 取证日志 / 取证脚本） |
| 附件 | `CarDesk-ecarx-cardesk-4157.apk`（1.87 MB） |
| Release label | Latest（非预发布、非草稿） |

> **用户在被问到「确认发布吗」时选择了「先不发布，保持表单不动」**，
> 所以**没有点 Publish**，页面上这一版 Release **并不存在**（不会出现在 Releases 列表里）。
> 表单内容原样保留在浏览器标签页里，用户改主意时点一下 Publish 即可。

### 6.2 R2 上传：**本轮没做**（凭据不在我这边）

R2 的 Access Key / Secret 只从环境变量读，而当前环境里**没有** R2 相关环境变量，
所以本轮**没有上传** `cardesk/CarDesk-ecarx-cardesk-4157.apk` 与 `cardesk/latest.json`。
需要用户在自己的终端执行（与上一轮同一套流程）：

```bash
python3 tools/publish_r2.py \
  cardesk/CarDesk-ecarx-cardesk-4157.apk \
  cardesk/latest.json
python3 tools/publish_apks.py     # 挂上云端应用库
```

> 在 R2 更新之前，车机上的「在线更新 → 检查更新」仍然只会看到上一版 4156。

---

## 7. 未能覆盖 / 待办

### 7.1 **需要真车配合的一件事**（本轮唯一的硬待办）

**装上 4157 跑一次真车导航**，把 logcat 里这几行回传：

```
adb logcat -s CarDesk/NavUI:I
#   → 收到红绿灯数据（官方协议） lamp=… countdown=… rawRed=… rawGreen=… rawStatus=…
```

要看的就两件事：

1. `rawRed` 与 `rawGreen` **哪个在动**、**往哪个方向动**；
2. 帧间隔大约多少秒。

有了这个才能定论「绿灯读秒该取哪个字段」——
**在那之前不改取值**（改了就是猜）。

### 7.2 其它沿用项

- 官方协议 `trafficLightStatus` 真车复核（灯态本身已在高德代码里查实，但要真车再确认一次）；
- 车机 ROM `Visualizer.getCaptureSizeRange()` 支持到多少；
- 亮度快起慢落 / 起拍脉冲在灯珠上的观感；
- 车机能否访问 `file.chuxi.bid`（DNS/TLS）；
- 酷狗 KRC 在车机网络下的可达性。

### 7.3 Cloudflare Worker 仍未部署

`cd worker && npx wrangler login && npx wrangler secret put UPLOAD_TOKEN && npx wrangler deploy`
（车机侧不依赖它，`tools/publish_apks.py` 直接传已跑通。）
