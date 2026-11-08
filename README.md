# CarDesk · 车控桌面

一个从零实现的 **Android 车机桌面 App**（Kotlin）。

桌面由场景页（首页）、导航组合页（地图 + 播放器 + 车况）、应用抽屉、气候整页、设置面板组成，
底部是一条一体化 Dock（左段四个导航键：主页 / 应用 / 分屏 / 360，再接媒体控制与全量空调控制）。
界面对齐 1920×1080 设计坐标系，
按实际屏幕等比缩放，支持日间 / 夜间两套配色。

车控部分（空调、车窗、车门、引擎盖、胎压、续航、油量、总里程、行程与油耗、车况、天气）
通过反射调用车机内置的 ECARX `AdaptAPI` 与 AOSP `Car` 属性通道；读不到真车数据时自动降级为模拟数据，
所以桌面的全部界面在普通设备上也能完整体验。
**车窗**在本车上已能读到逐扇开度；**四个侧门与尾门**的读法已按车机登记的属性类型重写
（**取值口径待真车验证**），**引擎盖则是彻底没有**（车机 jar 里零命中），见下面的「已知限制」。

设置里的**氛围灯**会解析正在播放的音乐频谱（`Visualizer` 挂在**全局输出混音**上，
不追别的 App 的会话、也不依赖隐藏 API），换算成灯光的颜色与亮度，
并下发到车机氛围灯通道（颜色 / 行车亮度 / 静止亮度 / 音乐律动）。
频谱口径与参照工程 `uni-launcher`（同款车机上已跑通）一致：
**24 段对数分频、绝对 dB 标定、非对称平滑（瞬时升 / 0.70 降）**，
**FFT 缓冲区按采集点数给足（N 字节 ⇒ N/2 个频点）**。
**所有对车机的写入都在后台线程上做** —— 主线程一被同步 binder 拖住，
窗口就只剩背景色，再久就被系统看门狗当 ANR 杀掉。

灯光**不是「跳」而是「流」**（第八十五轮）：频谱算出的只是**目标**色相与亮度，
真正下发的颜色经过一层**动感层**——
色相走**指数平滑 + 最短路径 + 限速**（359°→1° 只走 2°，不绕整个色环），
亮度**快起慢落**（鼓点一响立刻亮，音乐停了慢慢暗），
低频再叠一下**起拍脉冲**，于是灯有呼吸感而不是 10Hz 的台阶。
实测同一份频谱下**单帧颜色跳变降到 1/4.1**（见 `emu-verify/REPORT-r85.md`）。
采集点数用 **1024**（设备范围自动夹取）：512 点在 44.1kHz 下低频四段会塌进同一个
FFT bin，色相会**恒定在 0°**——那是「看着有反应、其实颜色从没变过」。

首页的**导航引导卡**还会显示**最近路口的红绿灯状态与读秒**：
**一个大圆**表示灯态（红 / 黄 / 绿），**读秒放在圆的下方**。
高德有两套广播协议，都接了：官方标准协议（`KEY_TYPE = 60073`）与修改版高德的巡航广播。
**有信号才画、没有信号不占位** —— 引导卡高度恒定，不会因为信号来去而长高变矮。

音乐卡的歌词是**逐字擦除**：擦除边界跟着**实际演唱**走，
歌手唱完这一句时高亮**当场到 100%**，不会把「唱完」拖到下一句开始。
逐字时间来自**酷狗 KRC**（`lyrics.kugou.com/download?fmt=krc`，
base64 → `krc1` 头 → 16 字节循环异或 → zlib 解压），
行标签是 `[行首,行时长]`、字标签是 `<相对行首偏移,时长,0>`。
**拿不到逐字数据时仍是行级线性**（与参考 App（君君桌面）公式一致），不猜、不倒退。

顶部状态栏分三段：**左边**是头像与驾驶模式胶囊，**正中**是**翻页指示圆点**，
**右边**是**位置（城市）+ 天气**、蓝牙 / WiFi / 信号 / 油量 / 时钟。
**单指左右滑切换桌面是循环的**（最后页往左滑回第一页，第一页往右滑回最后一页），
而且**手指落在屏幕上哪一块都能滑** ——
以前导航页音乐卡那种「纯自绘的空白区」会把滑动吃掉，
从外面看就像「翻到头了、不能循环」，现在不会再有。

| | |
| --- | --- |
| 语言 / 依赖 | Kotlin，纯 framework API，**无任何运行时依赖** |
| 目标平台 | Android 11+（API 30+） |
| 包名 | `com.ecarx.cardesk` |
| 构建变体 | `system`（平台签名 + `sharedUserId=android.uid.system`）/ `debug`（普通安装） |
| 在线更新 | App 内「设置 → 在线更新」**手动**检测 **Cloudflare R2** 上的更新清单（`file.chuxi.bid`），有新版可一键下载并安装 |
| 云端应用库 | App 内「设置 → 网络与升级 → 云端应用」列出你自己传到 R2 的 APK，点一下直接下载并安装 |

## 已知限制

下面这一项**还没有真正生效**。代码写了、界面也画了，但达不到可用状态 ——
用的时候请按「还没做」来理解。

### 1. 车门状态：侧门与尾门改走车机属性通道（**待真机验证取值口径**），引擎盖是**彻底没有**

- **车窗**已经能读到真值：逐扇开度走 `AdaptAPI` 的
  `getCustomizeFunctionValue(BCM_FUNC_WINDOW_POS, 窗区)`，
  窗区号按名字解析（`WINDOW_ROW_1_LEFT` 等），读不到的那一扇显示 `--`，
  不会当成「关着」；
- **四个侧门 + 尾门**在车机端有独立的属性（`GeelyVehicleProperty` 里的
  `PA_CP_DoorDrvrSts` / `DoorDrvrReSts` / `DoorPassSts` / `DoorPassReSts` / `TrSts`），
  在车机的 `car_service` 清单里**标为只读、确实存在**。
  第七十二轮之前读不到，是因为**属性通道压根没建起来** ——
  我们调的 `Car.createCar(Context)` 这个重载在车机端那份 `android.car` 里
  **压根不存在**（它只有带 `ServiceConnection` 的重载），
  诊断包里那句「Car.createCar 返回 null」就是这么来的；
- 第七十四轮又查出**第二条根因**：属性通道建起来之后，我们那**两条读法同时是死路** ——
  字节那条找的是 `getProperty(int[], Class)`，而车机端那份 `CarPropertyManager`
  **只有** `getProperty(Class, int, int)` 与 `getProperty(int, int)`，
  方法句柄因此恒为 null；数值那条走 `as? Number`，
  而这几个属性的值是 `byte[]`，恒解不出来。
  现在改成**先问车机登记的类型**（`CarPropertyConfig.getPropertyType()`），
  再按类型走 `getProperty(Class, int, int)`；
- 但**这一档还没在真机上验证过**：属性到底是 `byte[]` 还是数值、
  值是 `0`（关）/ `1`（开）还是反过来，
  要等装到车上开一次门看日志里的原始值才能定
  （诊断日志会带出每个门属性的 `type=` 与原始值，一次实车日志就能定论）；
- 第七十九轮把「读不到」这件事的**证据链**补齐了：
  - 反射异常现在会**剥到最内层**再记 ——
    以前只记到 `InvocationTargetException: -`（它自己的 message 是 null，什么都没有），
    现在能记到车机抛的真异常，例如
    `IllegalArgumentException: Invalid property type. Expected: class java.lang.Integer, but was: class [B`
    —— **`but was: class X` 就是车机亲口说出值的真实类型**，不用再按属性 id 的类型位推断；
  - 新增**无类型快照**（走车机的 `getProperty(int, int)`，它不做类型校验）：
    一次拿到 `status`、回读区域号、值的真实类名、字节长度与前 16 字节的 hex / 十进制；
  - 试读候选里的向量类型从 `int[]` 改成 **`Integer[]`**
    （厂商自己的向量属性就是 `Integer[]`，`int[]` 永远匹配不上）；
- **第九十三轮找到最后一条根因：解析器只认「一个 varint」。**
  这几个门属性的值是 Geely `PublicAttrValue` 的**消息字节**，
  而 `core/ProtoLite.singleVarint()` 的规则是「顶层有且仅有一个 varint 才返回值」；
  `PublicAttrValue` 真机消息通常同时带 `availability`（field 1）、`status`（field 3）、
  `int32_data`（field 4，**sint32 / ZigZag**）三个 varint ⇒ `singleVarint` **恒返回 null**
  ⇒ 车门恒「读不到」，和通道/权限都无关。
  现在新增 `vehicle/PublicAttrValue.kt`（纯 Kotlin、可 JVM 单测）按完整线格式解析
  （field 1/2/3/4/8 varint、field 4 ZigZag、field 5 double、field 6/7 跳过、8192 上限），
  `McDoorSignals` 走 `CarProperties.readBytesWithStatus()` 拿到 HAL status 后解析，
  **`availability == 1` 才算有效**；
  取值口径按「合并口径」解释：`0 → 关`、`1 → 开`、`2 → 关`、其它**不猜**（null）。
  UNI 实车只在主驾门验证过 `1 = 开 / 2 = 关`，`0 = 关` 是 IBcm 口径 ⇒
  **这台车上的真实取值仍要一次实车日志定论**（诊断日志已把
  `raw / halStatus / availability / format / status / data / int32Wire / level / active` 全打出来）；
- **引擎盖**是**彻底没有**，不是「还没找到」：
  厂商 jar 的 931 个属性与 1,346 个功能号里，hood / frunk / bonnet **零命中**。
  不用再花时间查了，界面上按「不支持」处理；
- 老的 `IDoorAndWindow`（`getDoorAndWindowState`）通道在这台车机上
  根本不存在这个类；
- 所以在验证之前，**不要**把界面上的侧门图标当成真实车况 ——
  读不到时显示 `--`，不会当成「关着」。

### 2. 副驾座椅通风：左舵车上没有这条通路

厂商的座椅通风是**单座信号**，而且按左右舵决定哪个区域有效
（`zone == 4 && 右舵 || zone == 1 && 左舵`）。
dcy11 是左舵车 → 只有主驾座能读能写，副驾通风**读回来恒 0、写必失败**。
现在界面上点副驾通风会明确拒绝并留痕，不再假装成功。
座椅**加热**不受左右舵限制，副驾加热是通的。

### 3. 行程卡的总里程：改走了厂商传感器，**待真车验证**

- 原来总里程只读 `car_service` 属性通道（`PERF_ODOMETER` / `ODOMETER`），
  而那条路在这台车机上**从来没读到过值**，桌面一直是 `--`；
- 第七十五轮改成优先走厂商 `AdaptAPI` 的 `ISensor` 总里程传感器
  （`SENSOR_TYPE_ODOMETER = 1050368`）——
  厂商 SDK 里**取值 / 支持判定 / 监听注册 / 变化回调四样都有**，
  和已经读得到的续航、油量是同一族机制；单位**确定是公里**；
- **这一档还没在真机上验证过**：车机端到底推不推这个信号、拿到的公里数
  与仪表盘是否一致，要等装到车上开一次看日志
  （诊断日志会打「总里程探针 raw=… support=…」）；
- 顺带修了平均油耗的兜底口径：厂商的 `getTripFuelConsumption()` 是**升（L）**，
  而平均油耗是 **L/100km**，两者差 100 倍 —— 之前直接互相顶替，
  现在改成按厂商公式反解（`tripFuel × 100 / tripKm`）。

### 4. 红绿灯：两套协议的灯态**都从高德自己的代码里查实了**（第八十五轮）

以前官方那一套是「枚举没实测过，只按红灯倒计时判红」——
而那个倒计时在**绿灯期间照样可能是正数** ⇒ 绿灯被画成红灯。

第八十五轮把模拟器里那份**车机同款高德**（`com.autonavi.amapautp.yh`）
pull 下来用 `jadx` 反编译，结论有两处互相印证：

| | 灯态 | 方向 |
|---|---|---|
| **官方标准协议**（`KEY_TYPE = 60073`） | `trafficLightStatus`：**`1` = 红 / `4` = 绿** | `dir`：`1` 左 / `2` 右 / `4` 直 |
| **修改版高德巡航协议** | `status`：**`0` = 红 / `1` = 绿** | `1` 左 / `2` 直 / `3` 右 |

- 官方的 `1` = 红是这么来的：高德语音模块把 `trafficLightStatus == 1` 播成「即将绿灯」、
  `== 4` 播成「即将红灯」，而高德语音设置项写死了
  「只播报**红灯状态下的即将绿灯**」⇒ 「即将绿灯」发生在当前是红灯的时候。
- ⚠️ **两套协议的灯态正好相反**，取值不能互相套用。
- 现在是**灯态看枚举、读秒统一取 `redLightCountDownSeconds`**（第九十三轮修正，见 §4.1 末尾）。
  高德改枚举时**不猜**，灯态退回按 `greenLightLastSecond` 的伴随值（红=0/绿=3）判断，
  绝不退回「永远画红灯」。
- **这一档还没在真机上验证过**：官方协议到底发不发、真车上的 `greenLightLastSecond`
  带不带、巡航协议一次带几个方向、推送间隔多久、断网时还有没有 ——
  都要装到车上开一次导航看日志才能定
  （日志会打「收到红绿灯数据（官方协议）」/「（巡航协议）」并带原始值）；
- 目前的保守口径：**只显示最近路口的一个**信号 ——
  巡航协议一次带同一个路口的多个方向时，按当前转向方向挑；
  挑不出来就**整个不画**（宁可没有，也不要拼出一个看起来像真的信号）；
  超过 **5 秒**没收到新帧就当信号失效（TTL 保守取 5000ms）；
- 读秒按**本机单调时钟**往下减，减到 0 之后只抹掉数字、**不自动切换灯态**
  （「红灯读秒走完」不等于「变绿灯」，那是猜）。

### 4.1 红绿灯读秒：**不再在 3↔2 之间来回跳**（第八十六轮）

真车反馈「绿灯时倒计时一直在 3-2 秒之间，未能正确反应倒计时」。
根因是显示算法**每收到一帧就重新锚回原始值**，
而高德**不是每秒都刷**这个字段（真车帧间隔约 2 秒）——
于是**已经走掉的秒数被原样加回去**，读秒永远走不到 0。

现在改成独立的读秒显示状态机（`core/nav/TrafficCountdown.kt`，纯逻辑、可单测）：

- **同一个灯态里读秒只减不增**；
- 供应商的原始值只用来「往下压」，**不用来把秒数加回去**；
- 灯态变了才重新起一轮；归零后不再显示，等灯态变化（不猜灯态）；
- 断档超过 TTL（5 秒）按「新一轮」处理。

模拟器 A/B（原始读秒恒为 6、广播 2 秒一帧）：

| | 取值序列 | 回跳 |
|---|---|---|
| 旧包 4156 | `6 → 6 → … → 5 → 6 → … → 5` | **有（锯齿）** |
| 新包 4157 | `6 → 5 → 4 → 3 → 2 → 1 → 消失` | **无（单调）** |

⚠️ **绿灯读秒的字段语义仍未验证**：`greenLightLastSecond` 是**服务端透传**的，
高德**自己都没有消费端**（全仓只有序列化/反序列化），语义只存在于服务端。
所以本轮**不改取值、只装探针**：官方协议日志现在同时打
`rawRed`（`redLightCountDownSeconds`）与 `rawGreen`（`greenLightLastSecond`），
一次真车导航就能看出**哪个字段在动、往哪个方向动**。
显示层已经与这个选择解耦（换字段不用动状态机）。

#### 第九十三轮：绿灯读秒取错字段的**真凶找到了**

上一轮「只装探针」等来的不是新证据，而是一个更硬的结论 ——
**`greenLightLastSecond` 根本不是剩余秒数**：

- 第三方桌面「嘟嘟」的反编译生产代码（`com.dudu.autoui...C3691g`，处理同一个
  `KEY_TYPE = 60073`）里，**所有灯态的读秒都取 `redLightCountDownSeconds`**，
  `greenLightLastSecond` 只参与两个判断：
  **红灯 = `greenLast == 0 && status == 1`、绿灯 = `greenLast == 3 && status == 4`**
  —— 它是**灯态伴随标志**（红=0、绿=3），不是倒计时；
- UNI 的 `AmapTrafficLightProtocol.java` 同样恒取 `redLightCountDownSeconds`，
  注释明写「never turn it into a green countdown」；
- 所以 cardesk 以前绿灯读 `greenLightLastSecond`（恒为 3）⇒
  **绿灯倒计时一直从 3 开始、且每帧被压回 3** —— 这就是「绿灯读秒不正确」。

现在 `AmapTrafficLight.standard()` 的 `countdownSeconds` 对**所有灯态**
统一取 `redLightCountDownSeconds`（`red` 缺失时**不给读秒**，显示「—」，
绝不拿 `greenLightLastSecond` 顶上）。单测补了 4 条回归（绿 12→12、红 8→8、
`red=0`→「—」、缺 `red`→「—」）。

### 5. 氛围灯：链路是通的，**真车上的观感还没验**

- 第八十五轮把「颜色僵硬」查到底了：不是平滑没做，是**色相压根没动过** ——
  512 点 FFT 在 44.1kHz 下低频四段塌进同一个 bin，「取最响的那一段」永远取第 0 段。
  现在采集点数用 **1024**（设备范围自动夹取），并在「频谱分辨不了低频」时
  改用**强度→色相**兜底，保证颜色**一定会变**。
- **待真车验的三件事**：
  1. 车机 ROM 的 `Visualizer.getCaptureSizeRange()` 支持到多少
     （模拟器实测 128~1024；车机只支持 512 的话色相会走强度兜底）；
  2. `greenLightLastSecond` 真车上带不带、语义是不是「绿灯剩余秒数」；
  3. 亮度的**快起慢落**与**起拍脉冲**在真车灯珠上的观感
     （模拟器音频链路有归一化，目标亮度几乎是常量，这两项只在单测里验过）。
- 模拟器上已能验的部分：频谱状态「采集中 · 来源：输出混音」、当前颜色随音乐变、
  单帧颜色跳变降为 1/4.1（`emu-verify/REPORT-r85.md` §4）。
- **V2 的两层 ΔE00 限速，实测没有发散**（第八十九轮）：
  - 渲染层基准 = 上一**渲染帧**（60 Hz），预算 = `1.8 × 16/50 = 0.576`；
    写入层基准 = 上一**已写**颜色（20 Hz），预算 = 完整的 1.8。
    渲染层一个写入周期最多走 1.73 ≤ 1.8 ⇒ 写入层是**安全网**，正常不触发。
  - 10 秒端到端实测（`AmbientWritePipelineTest`，走真实类）：
    引擎原始步长中位 1.00 ΔE/帧（峰值 12.5）→ 限速后最大 0.67；
    写入层触发 8 次 / 145 次写入，但**写下去的颜色与渲染色最大差 0.0 ΔE**，
    且这个差**不随时间变大**（前 1/3 段 0.0 → 后 1/3 段 0.0）。
    渲染限速造成的滞后稳定在 28.6 / 32.6 / 29.8 ΔE（三个 1/3 段）——那是限速本身的效果，
    跟着音乐起伏，**不会一轮轮累积**。
  - 顺带查清：渲染层有约 3% 的帧越过 0.576，原因是**8 bit 网格比预算粗**
    （暗色上一个 RGB 步长就值 0.63 ΔE），属量化误差，不是发散。
- **「五彩」（第九十轮）**：V2 的色相带原先固定 170°~320°（青→蓝→紫→品红），
  真实音乐里谱质心只在 0.3~0.6 之间移动 ⇒ 目标色相被钉在 235°~285°，
  看着就是「一直是蓝色系在明暗」。加四个参数把这条路打开（V2 才有，旧版链路一行不动）：
  - **色相跨度** `HUE_BAND_SPAN`（150°~360°）：决定色相最多能走多远；
  - **质心展开窗口**（低 0.20 / 宽 0.45，即 `RAINBOW_CENTROID_WINDOW_*`）：
    把真实音乐那 0.20~0.65 的质心区间线性拉伸到 0~1 —— 只拉跨度不展开，
    质心仍在窄区间里动，色相只多走 100° 出头；
  - **彩度上限** `MAX_CHROMA`（0.22→0.32，硬上限 0.40）：不抬彩度红/绿出来是灰的；
  - **色相自转** `hueRotateDegPerSec`（0~30°/s）：副歌反复、质心不动时靠时间推着走，
    30°/s 是上限（ΔE 限速下 60Hz 预算 0.576/帧，转太快只会被限速器吃掉）。
  预设新增 **彩虹**（跨度 360°、窗口 0.20/0.45、彩度 0.32、自转 18°/s）；
  默认值仍是 150°/不展开/0.22/不自转 ⇒ **不选彩虹时观感与改动前逐位一致**（有单测钉住）。
  亮黄仍然刻意避开：跨度拉满时色相会经过黄区，这是「五彩」的代价；
  夜间觉得刺眼就选「夜驾柔和」或把彩度上限压回去。
- **「跳跃」（第九十一轮）**：要的不是「转得更快」，而是**跳**。
  平滑层（二阶色相 + attack/decay）存在的意义就是把跳变抹成滑动 ——
  只把 τ 调小、限速调高，结果只是「滑得更快」，仍是连续过渡。所以换一套机制：
  - **色相跳变** `hueJumpDeg`（0~180°，0 = 连续）：目标色相直接吸到最近档位，
    **不做插值**；60° = 红/黄/绿/青/蓝/品红六档，一帧内换色（单测钉住「不经过中间色」）；
  - **每个起拍再前进一档**：只靠质心挪过去，几十秒才换一次色；拍子一到就换下一档才是跳跃。
    「加一档」记在运动层的 `State.beatHueOffsetDeg` 里——色相每帧都从目标重算，
    直接把 +60° 写进 hue，下一帧就被覆盖回去了（单测钉住「拍子换色留得住、没拍子不乱挪」）；
  - **亮度** attack 10ms / decay 60ms + 底光 0：鼓点起、鼓点落，中间允许真的暗下去；
  - **起拍闪光** `pulseGain`（默认 0.35，跳跃 1.2×）+ **起拍最短间隔**（默认 240ms，跳跃 120ms）：
  - **换色间隔** `ambient_jump_hold_ms`（默认 0 = 只跟拍子，跳跃 380ms）：起拍检测是内容驱动的，
    没鼓点、低音被自适应阈值当常态时就判不出拍子，灯会定在一个颜色上。设了间隔之后，
    即使一直没检测到起拍，隔这么久也强制换下一档 —— 参考视频里那种「一直在闪」就是这么来的。
  - **跳跃模式下两层 ΔE 限速都关掉**：色差上限 1.8（渲染层）/ 写入层限速会把 120° 的硬切
    拉成 100~200ms 的滑坡，看起来还是「渐变」而不是「跳」。跳跃要的就是那一帧到底，
    所以渲染层与写入层对跳跃都放行（写频仍受 50ms 节流约束）。

    最多约 8 次/秒，是默认「4 次/秒」的两倍；
  - **色差上限** 顶格 6.0/写：限速器不再把一次跳变拖成十几帧的滑坡；
  - 叠加彩虹那套跨度 360° + 质心展开 + 彩度 0.32 ⇒ 跳到的六档都「有颜色」。
  预设新增 **跳跃**；三个新滑杆「色相跳变 / 起拍闪光 / 起拍间隔」，
  默认 0 / 0.35× / 240ms ⇒ **不选跳跃时观感与改动前逐位一致**。
  ⚠️ 跳跃是驻车/展示向：夜间跟车红黄档晃眼，行车建议换回「均衡」或「夜驾柔和」；
  每拍换色 + 闪光是高频闪烁，对闪烁敏感/光敏性癫痫者不要用。
- **页面拆成预设页 / 自定义页（第一百零一轮）**：预设是前门，参数是里屋。
  - **预设页（默认，`ambient_custom_mode=false`）**：引擎 chips + 预设 chips +
    **一句效果说明**（`AmbientPresets.summaryOf`：只描述灯怎么动，不写「适合什么时候用」）；
    所有滑杆（灵敏度、亮度包络、色相、彩度、跳变…）全部收起。
  - **自定义页**：把「自定义参数」开关打开，上面这些滑杆全部放出来，当前参数**原地不动**
    （不是重置成某个预设再改）；选任一预设 ⇒ 开关自动关掉、回到预设页。
    入口单独一行而不是塞进预设 chip 行：6 个预设 + 「自定义」在 1920 屏上会溢出成横向滚动，
    入口看不见就等于找不到。
  - **灵敏度 / 分区编排**也算「参数」：V2 下同样只在自定义页出现；
    旧版引擎没有预设，照旧直接显示自己的滑杆。
  - 选择被记住（`ambient_custom_mode`），重启不会把调过的参数悄悄折回某个预设。

### 6. 全局 Dock 能跨应用显示，但**「让系统真让出 112px」在 /data 装的 RRO 上做不到**（第八十七轮）

- **能用的部分**（模拟器实测）：开关打开后 `core/dock/GlobalDockService.kt` 起前台服务，
  在**其它应用**底部常驻一条 112px 的 Dock（首页 / 应用 / 分屏 / 360 / 媒体卡 / 空调 /
  快捷应用，和桌面自带那条同源，`DockBarView` 复用），点按键能回桌面指定页
  （`MainActivity.EXTRA_ACTION`）、能直接控媒体与空调；桌面自己回到前台时全局窗自动隐藏
  （不留双 Dock）；长按主页键 = **注入返回**（原车导航栏被摘掉后这是唯一返回入口）。
- **两条 policy 分支**：RRO 生效（`NavInsets.applied()`）→ 走 `SystemNavigationPolicy`，
  **只摘状态栏、永不写 `immersive.navigation/full`**，窗口用 `TYPE_NAVIGATION_BAR_PANEL`(2024)，
  由系统精确预留 112px；RRO 不生效 → 写 `immersive.navigation=*` 把原车导航栏整条摘掉，
  全局窗改用 `TYPE_APPLICATION_OVERLAY`(2038) 盖在底部 112px 上。
- **RRO 的实测结论（重要）**：`cardesk/insets/` 这套覆盖包（四条 `navigation_bar_height*` =
  112dp，四套 qualifier，platform key 签名，`--no-resource-deduping` 构建）在模拟器上
  `cmd overlay enable` 之后 `mState: STATE_ENABLED` 是成功的，**但 NavigationBar0 仍是
  1920×56，重启也不变**。原因不在包：framework-res 的 dimen 有配置限定符
  （默认 `48dp` / `sw900dp 56dp` / `car_mode 96dp`），而 **AOSP Android 10 不允许 `/data`
  安装的 overlay 改 framework-res 的资源**——`/system/overlay` 一类系统分区预装才生效。
  模拟器 `adb remount` 也不可用（overlayfs 依赖缺失），所以这一档**只能到真车上验**，
  真车 ROM 允不允许由 ROM 决定（参考工程 UNI Launcher 的车机允许）。
  拿不到 RRO 时功能照常，只是**其它应用底部 112px 会被 Dock 盖住**（相当于牺牲一条状态行）。
- **不留死角**：关开关 / 服务 `onDestroy` / 通知栏关闭都会**无条件**把 `policy_control`
  恢复成「没有全局 Dock 时该有的样子」，不会留下「既无原车 Dock 也无 CarDesk Dock」的空白底边。
- 取舍：全局模式下 `hideDock`（原车 Dock 名单）对其它应用不再单独生效——统一由全局 Dock 接管；
  `oemNavImmersive` 保持原语义不动。
- **第九十四轮起**：开关默认打开（默认就用桌面 Dock 顶替原车 Dock），并加了能力守卫
  （RRO 或悬浮窗权限至少有一项才接管，否则自动退回普通策略）；Dock 条从 1864 宽改为
  **铺满整屏宽**，详见下一节。

## Dock 铺满 + 全局 Dock 默认开（第九十四轮）

真车反馈两点：①其它应用里底部 Dock 栏「不铺满」，两侧各露出一小条硬色底；
②希望**默认**就把系统自带 Dock 藏掉、统一用桌面 Dock。

### 1. Dock 为什么「不铺满」

Dock 的设计板宽是 1864（1920 板上左右各留 28），`DockBarView.onMeasure` 又把宽度
**钉死成 1864**，而宿主给的宽度是 1872（桌面）/1920（全局 Dock 窗口）——
差出来的部分露出的是全局窗口的硬色底 `#162E2C`，在别的应用里看就是「Dock 没铺满」。

改法（三处一起才有效）：

- `ui/shell/DockBarView.kt`：`onMeasure` 按父布局给的宽度测量（EXACTLY/AT_MOST 取 size，
  UNSPECIFIED 退回设计宽 1920），高度仍 104；
- `ui/LauncherView.kt`：桌面内嵌 Dock 的 LayoutParams 改为整屏宽 1920、`Gravity.BOTTOM`；
- `core/dock/GlobalDockService.kt`：`DESIGN_DOCK_WIDTH = 1920`、`DOCK_LEFT_MARGIN = 0`。

Dock 内部布局本来就是左右两端锚定的（`DockLayout.sections(dockWidth, …)`：左侧 4 键、
右侧快捷应用贴右、媒体卡吃中间弹性空间），所以变宽只会让媒体卡/间隙变大，不会挤坏。
**增强导航栏（pill 1864×124）那套设计没动**——它本来就是按 1864 等比缩放进 pill 的。

### 2. 默认全局隐藏系统 Dock

- `Prefs.globalDockEnabled` 默认值改为 **true**：新装/未设置过的车机开机就起
  `GlobalDockService`，在其它应用底部显示桌面 Dock，同时 `policy_control` 把原车
  导航栏摘掉（RRO 生效走预留 112px；否则 `immersive.navigation=*`，第八十七轮那套）。
  老用户之前手动关过的话仍是关（prefs 已存值优先）。
- 加了**能力守卫**：`GlobalDockPolicyGate.apply()` 里先判断 `navInsets || canDrawOverlays`，
  两项都没有就退回「普通桌面策略」——否则会出现「原车 Dock 被摘掉、CarDesk 窗口起不来」
  的空白底边。用户在系统设置里补授权限后回桌面会自动重写 policy
  （`syncGlobalDockPolicy()` 的签名里带上了 overlay 状态）。

### 3. 验证

- `:app:testDebugUnitTest` 226 tests 全绿（新增 2 条：`globalDockDefaultsOn`、
  `dockMeasuresFullWidth`）；`:app:assembleSystem` 通过。
- `PreviewRenderTest` 渲染截图 `preview/01-home.png` 目视：Dock 贴满整屏宽，
  左右按键位置正常、无溢出。
- 真车验证点：盖到其它应用（如高德/音乐）后底部 Dock 是否铺满、原车 Dock 是否消失、
  设置页「全局 Dock 栏」开关是否默认开。

## UNI 能力移植（第九十五轮）：C4 原厂设置 → C5 应用菜单 → C3 最近任务 → C2 桌面卡片

按 `analysis/compare/uni-vs-cardesk-porting.md` 的评分顺序，在 cardesk 里用 Kotlin/Canvas 重写了 UNI 的四个能力。
参考的是 UNI 的交互与数据模型，**没有拷 UNI 的 Flutter 代码**。

### C4 原厂设置目录（`core/SystemSettingsCatalog.kt` + 设置 → 系统 → 原厂设置）

- 不再写死「系统设置」一个入口，而是扫描本机真实存在的设置 Activity：
  先试显式 component（`com.ecarx.settings.Settings`、`DriveModeActivity`），再按 `ACTION_SETTINGS` /
  `WIFI_SETTINGS` / `BLUETOOTH_SETTINGS` / `TETHER_SETTINGS` / `INTERNAL_STORAGE_SETTINGS` 等 action 查；
  只收 `exported && enabled` 的 Activity，按包名+类名去重。
- 页面逐条列出「原厂设置入口」（驾驶模式 / 系统设置 / WLAN …），点一下直接 `startActivity` 打开；
  另留「打开 Android 系统设置」「重新扫描原厂设置」两个兜底按钮。
- 扫描结果是 10 分钟 TTL 缓存 + 首次进页异步预扫，**车机系统版本不同、少了哪几项都不会崩**。
- 验证：`OemSettingsPageTest`（Robolectric 注入 Settings/WifiSettings/DriveMode 三个 Activity，断言 3 条入口、
  点击能发出 startActivity）＋ 效果图 `preview/y01-settings-oem.png`。

### C5 应用管理增强（抽屉格子右上角「⋮」）

- 抽屉里每个应用格子右上角多一个「⋮」，点开是三个动作：
  **重命名**（只改桌面显示名，落 `launcher_app_alias`，留空恢复原名）、
  **应用信息**（`ACTION_APPLICATION_DETAILS_SETTINGS`，失败退 `ACTION_MANAGE_APPLICATIONS_SETTINGS`）、
  **卸载**（`ACTION_DELETE` → `ACTION_UNINSTALL_PACKAGE` 兜底；系统应用不显示卸载，改为提示）。
- 长按拖动排序保持原样；重命名后排序/显示都按别名走。
- 验证：`DrawerAppMenuTest`（菜单四动作 / 重命名立刻生效 / 留空恢复）+ `DrawerMenuPreviewTest`
  效果图 `preview/y02-drawer-menu.png`、`preview/y03-drawer-rename.png`。

### C3 原车最近任务面板（`core/RecentTasks.kt` + `ui/RecentTasksPage.kt`）

- 替换了旧「回 HOME」的假最近任务：`ActivityManager.getRecentTasks`（平台签名 + `REAL_GET_TASKS`）取列表，
  去重、排除自己、最多 12 条；点卡片先 `moveTaskToFront`，失败回落启动 Intent；支持单条「关闭任务」、
  「清空」（保留锁定与桌面自己）、「锁定」。
- `ActivityManager.removeTask` 是 @SystemApi、公开 SDK 里没有 → 用反射调用（直接调用会编译不过）。
- 入口：Dock 主页键长按、增强导航栏「最近任务」动作、全局 Dock 主页键长按（发 `ACTION_RECENTS` 回桌面）。
- 权限不足/不支持时整页降级成一句提示，不会崩。
- 验证：`RecentTasksTest` 6 条（去重/清空保留锁定/权限降级/打开优先挪前台/回落启动/锁定持久化）
  ＋ `RecentTasksPageTest` 效果图 `preview/y04-recent-tasks.png`。

### C2 桌面卡片布局（`core/CardLayout.kt` + 设置 → 布局与显示 → 桌面卡片）

第九十五轮先做**布局模型的最小闭环**（卡片库/跨页拖动留到下一轮）：

- 布局从「只有顺序」升级为「顺序 + 尺寸档位 + 显隐」，落 `scene_card_layout_v2`：
  每张卡可选 自适应 / 小 / 中 / 大；车况卡自适应时吃掉行尾剩余宽度，小/中/大是固定宽度；
  隐藏的卡不参与排布与绘制，剩余宽度由自适应卡均摊（固定档位太宽时按比例收窄，整行不溢出）。
- 设置页「桌面卡片」：每张卡一个显隐开关 + 宽度档位，改完立刻重排；「恢复默认布局」一键回滚。
- 顺序拖拽仍是桌面上长按拖动（拖拽后写回完整布局，隐藏卡保持在末尾）。
- 验证：`CardLayoutTest` 10 条（编码/解码往返、非法输入、补缺、移动、尺寸、显隐下限、宽度分配/溢出收窄）
  ＋ `SceneCardLayoutRenderTest` 效果图 `preview/y05-scene-cards.png`（隐藏胎压卡 + 音乐卡放大）。

### C2 迭代 2：卡片库（行程 / 车门车窗两张新卡）

- 卡片库新增两张**默认隐藏**的卡：**行程**（总里程/平均油耗/行程里程/行程时长）与
  **车门车窗**（车门/车窗/后备箱/引擎盖汇总），都在 150 高的底部卡里画 2×2 单元格；
- 设置 → 桌面卡片顶部出现「卡片库」，点「添加」即上桌面；每张卡另有显隐开关与宽度档位；
- 七张卡同时显示时，宽度算法会先给车况卡保底宽度，再把其它自适应卡按比例收窄，**整行不溢出**；
  隐藏卡不参与拖拽排序，拖拽后写回完整布局（隐藏卡保持在末尾）；
- 效果图 `preview/y06-scene-cards-library.png`（6 张卡同时在场）。

### 底部卡片自适应（第九十五轮补丁）

- 卡片被压窄时（卡片库加到 6–7 张 / 选了「小」档）不再溢出：**快捷车控**按卡宽等比缩放
  三个控制位（座椅图标、文案、三段进度、最大风挡圈都跟着缩，超长文案自动截断加「…」）；
  **音乐卡**封面/标题/进度环/播放键按比例缩，标题按可用列宽裁剪；**行程/车门车窗**的
  单元格数值按格宽缩放并截断；**续航卡**的数值、油量条、燃料文案按卡宽缩放；
- 窄卡效果图：`preview/y07-scene-cards-seven.png`（7 张卡同时在场，控件全部留在卡内）。

### 全局 Dock 启用系统预留区（第 101 轮）

之前全局 Dock 只有两条路径：RRO 预留区已生效（系统给导航栏留 112px）或
`immersive.navigation=*` 把原车导航栏整体隐藏。第二条路径下，其它应用会变成
全屏，底部 112px 被悬浮 Dock 盖住——用户反馈的正是这个现象。

现在全局 Dock 服务启动时会自己争取预留区：

1. `NavInsets.prepare()` 静默安装包内的 `assets/cardesk-nav-insets.apk` 覆盖包；
2. `NavInsets.activate()` 把系统导航栏高度改成 112px（需要平台签名的
   `CHANGE_OVERLAY_PACKAGES` / `STATUS_BAR_SERVICE` 能力）；
3. 成功后把悬浮窗从 `TYPE_APPLICATION_OVERLAY` 换成
   `TYPE_NAVIGATION_BAR_PANEL`，Dock 正好占据系统预留带；
4. 同时 `policy_control` 只隐藏状态栏，不再取消导航 inset —— 其它应用
   自然只显示在 Dock 上方；
5. 预留区生效后桌面自己的 Dock 让位（否则会出现上下两条 Dock），全局 Dock
   在桌面页也保持可见（它就是系统导航栏的替代品）；
6. 关掉全局 Dock 开关时 `NavInsets.restore()` 把导航栏高度还给系统。

能力不足（普通签名 / 模拟器）时安静降级，并记日志
`系统预留区启用结果 ok=… supported=… applied=…`；设置页「全局 Dock」下会提示
「预留区未启用：其它应用底部可能被 Dock 遮挡（平台签名版会自动启用）」。

## 模拟器全面验证（第九十五轮补丁）

- 启动页/启动分段、Dock 铺满、全局 Dock 跨应用、C4 原厂设置目录、C5 应用菜单与重命名、
  C3 最近任务、C2 卡片库/尺寸档位/拖拽排序与持久化，全部在 AVD `cardesk-aosp` 上逐项验证；
  截图与清单见 `cardesk/emu-verify/REPORT-y08.md` 和 `cardesk/emu-verify/y08/`。
- 验证中修掉一个真 bug：最近任务面板按 BACK 不关闭（会一直叠在桌面/设置下方）。

### 状态栏驾驶模式胶囊（第九十五轮补丁）

- 修复错绑：点「驾驶模式 · xx」胶囊以前弹出的是**空调页**，现在优先打开车机原厂
  `com.ecarx.settings/.drivemode.DriveModeActivity`；这台车没有独立驾驶模式页时，
  兜底打开桌面设置 →「原厂设置」（并 toast 提示），不再误开空调。
- 空调页入口不受影响：仍可从 Dock 空调区 / 状态栏「助手」按钮进入。

### 驾驶模式快捷面板（第九十五轮补丁，已按文档对齐）

- 状态栏点「驾驶模式 · xx」在桌面弹快捷面板（不再跳原厂页）；
- **本车实际只有 3 档：经济 / 舒适 / 运动**（`570491137 / 570491138 / 570491139`）。
  文档 `IDriveMode` 里有 22 个取值常量，但 `16-hud-dim-adas.md` §4.3 与
  jimu 文档站 `api-drive-mode` 都注明：可用集合是 `PaDm*` 信号过滤后的结果，
  不是全部模式都有——所以面板只列这三项，不拿文档里存在、本车没有的档位让用户点；
- 写车严格按文档：`ICarFunction.setFunctionValue(0x22010100, 模式号)`（**两参、无 zone**），
  写完用 `getFunctionValue(0x22010100)` 回读确认（1.2 秒内没变就算失败）；
  切成功提示「已切换到 xx」并 0.7 秒后自动关面板；失败红字提示，面板留着可重试；
- 面板底部保留「打开原厂驾驶模式页」兜底（找不到原厂页时回落到桌面设置 → 原厂设置）；
- 真车限制：部分车型只允许 P 挡 / 静止时切换，写失败会如实提示，不会假装成功。

### 行程1 / 行程2 卡片 + 行程2 复位（第九十六轮）

按用户要求「一切车控相关功能要基于文档实际得到的数据开发」，先从文档把行程查清楚：

- `docs/ecarx-adapterapi/15-energy-trip-tpms.md`：`ITripData` 里**只有**
  `getLatestDrivingInfo()` 可用（`DrivingInfoManager` 用 TS 信号拼装 = 行程2）；
  `getLatestTripInfo / getTripInfo` 是空实现，**行程1 没有 API**；
- 行程1 只能直读 VHAL `TS_*` 属性（`byte[]` = `GeelyProto.PublicAttrValue`）：
  `TS_TripDistance(0x21701071)` 里程、`TS_TripTime(0x21701072)` 时间、
  `TS_TripAverageConsumption(0x21701070)` 平均油耗；
- 行程2 兜底同样来自 `TS_OdometerTripMeter2(0x2170106B, Double km)` 与
  `TS_EDT_time2(0x2170106A, String "HH:mm")`；
- 复位只有**常量**（`ITripData.TRIP_FUNC_RESET = 0x24800200` 等），jar 里没有消费方，
  所以桌面做「多路尝试 + 回读验证」，失败如实提示，不假装成功。

实现：

- 卡片库新增两张卡：**行程1（总）** 与 **行程2（自复位）**；
  旧的单张 `trip` 卡在 `CardLayout.normalize/decode` 里自动迁移到 `trip1`，老用户布局不丢；
- 行程2 卡右上角「复位」胶囊 → 确认弹窗 → `VehicleController.resetTrip2()`：
  依次尝试 `0x24800200=1`（文档功能号）、`Trip2(0x21603022)=1`、
  `TS_Callback_manualResetTrip(0x21402057)=1`，每次写完回读行程2，
  里程/时长归零才算成功；成功刷新桌面并 0.9 秒后关弹窗；
- `PublicAttrValue` 补上 field 6 string 解析（"HH:mm"），`CarProperties`
  新增 `writeInt/writeBytes`（反射 `setProperty`，invoke 不抛异常即提交）；
- 模拟通道新增行程模拟值 + 「复位行程2」；模拟器上可完整演示复位流程。

**默认不占桌面（用户口径「不要默认加上来，做到卡片库里就行」）**：
行程1/行程2 都在 `DEFAULT_VISIBLE` 之外，默认只出现在设置 → 桌面卡片 → 卡片库；
从旧版本升级时，原来的单张「行程」卡也**只迁移成卡片库里的行程1（默认隐藏）**，
不会自动出现在桌面底部。

验证：`TripSignalsTest`（int32 ZigZag / double / string 三支 + "HH:mm" 换算）、
`CardLayoutTest`（8 卡编码、旧 id 迁移后隐藏、窄卡不溢出）全绿；
模拟器实测：两张行程卡上桌 → 复位弹窗 → 确认 → 「已复位行程2」→ 行程2 归零、
行程1 不变；证据 `emu-verify/y14/`，效果图 `preview/y13-trip-cards.png`。

### 桌面布局与 Dock 调整（第九十六轮补丁）

用户口径：「1.把快捷应用卡片默认移除，把天气下面的行程卡挪过来；2.dock 栏的快捷应用改成只支持两个，图标放大」。

1. **底部卡片默认布局改为：行程1（总）· 音乐 · 空调 · 胎压 · 续航**
   - 快捷应用卡默认收进卡片库（需要时自己加），不再占默认位置；
   - 原来在天气下面那张「行程数据」卡**不再单独画**，行程数据由底部「行程1（总）」卡承担；
     右侧竖列只剩「车况」卡，并上移到原来行程卡的位置（255），避免留白；
   - 升级时做一次性迁移（`CardLayout.MIGRATE_QUICK_KEY`）：把旧布局里的快捷应用卡收进卡片库、
     行程1 挪到最左并显示；其它卡的顺序/尺寸/显隐原样保留，迁移只做一次。
2. **Dock 快捷应用槽位 3 → 2、图标放大**
   - `DockQuickApps.SLOTS = 2`，`DockLayout.QUICK_TILE` 56 → 76、`QUICK_GAP` 8 → 12；
   - 槽位变少 + 图标变大，整段宽度 184 → 164，省出的宽度给音乐卡；
   - 配置面板（设置 → Dock 快捷应用）自动变成 2 格。

验证：`CardLayoutTest`（新默认编码、快捷应用→行程1 迁移、旧行程卡仍隐藏）、
`DockQuickAppsTest`/`DockLayoutTest`（2 槽位、图标不越界）全绿；
渲染图 `preview/01-home.png` 目视确认：底部第一张是「行程1·总」、右侧只剩车况卡、Dock 右侧是两个更大的「+」。

### 卡片尺寸微调（第九十六轮补丁 3）

用户口径：「续航卡片调短点，行程卡片调大点，车况卡往下移与续航这一排卡片挨着」。

- **续航与行程1 位置互换**：默认顺序变成 **续航 / 音乐 / 空调 / 胎压 / 行程1**（续航在最左、行程1 收尾），
  尺寸仍是续航 **311**、行程1 **388（与右侧「车况」卡同宽）**；老布局升级时有一次性的位置互换迁移；
- 全新安装直接使用这套默认顺序（不再跑三次迁移，避免顺序被再挪一遍）；
  之前两张都挂「自适应」，用户加过别的卡时剩余宽度不够，续航会被挤小、看起来像没换；
  这次把两张卡的默认尺寸都固定成 MEDIUM（420 / 311），并做一次性迁移，宽度不再随其它卡漂移；
- 右侧「车况」卡下移到 **y=490**（250 高，底边 740），与底部卡片行顶边（764）留 **24** 的缝隙，
  不再完全贴住；右缘与「续航」卡对齐；
- 布局模型没动（还是顺序+尺寸+显隐），只是调整了自然宽度与车况卡上沿；`CardLayoutTest`
  的宽度断言同步更新（行程 311、续航 420、续航比行程宽）。

### 还没做的（下一轮）

- C2 的**跨页拖动**（场景页 ↔ 导航页互换卡片）与 UNI 的 16 类卡还差一层；
- 原厂设置目录里的图标/分组仍按扫描顺序，没有做语义排序；
- C4/C5/C3/C2 都还没上真车验证，最近任务与卸载界面依赖车机是否开放对应系统 Activity。

## 设置页文案精简（第九十八轮补丁）

设置里原来有很多「功能需求 / 实现原理 / 轮次说明」式的长段说明（RRO 怎么生效、环影走哪三条通道、
氛围灯的 OKLCH 色彩空间、日志上限怎么算……），对用户没有帮助，已整体删掉：

- 删除：实现原理、通道/权限依赖、轮次与 TODO 说明、限制解释、调试规则、示例教程、氛围灯算法长文；
- 保留（只留必要且短的一句）：当前状态、设备判定结果、导出/保存路径、安全提示（重启需 40 秒）、
  互斥提示（开启时会关掉哪两个开关）、开源许可与免责声明。
- 同一轮也压缩了对话框里的说明（重启、仪表歌词、热点、驾驶模式、行程复位、启动切换）。

预览：`preview/x02-settings-dock.png`、`x04-settings-diagnostics.png`、`x07-settings-update.png`、
`x10-settings-ambient.png`。

## 启动检测更新 + 云端应用界面（第九十八轮）

**在线更新**

- 桌面起来 3 秒后（首帧之后）自动查一次 R2 清单；查到新版本弹「发现新版本」询问框：
  「立即更新」直接进下载安装流程，「稍后」记下该 versionCode，**同一个版本不再问**。
- 原有「设置 → 在线更新 → 检查更新」手动入口保留；查询失败静默（不弹网络错误）。
- 实现：`core/update/UpdateStartupCheck.kt`（只查一次、失败静默、稍后去重）、
  `ui/dialogs/UpdateAvailableDialog.kt`、`LauncherView.startServices()` 末尾 `postDelayed(3s)`。

**云端应用**

- 列表一卡一人：序号块 + 名称 + 文件名/大小 + 上传日期/SHA 摘要 + 状态（可下载/已安装）+
  「下载 / 重新安装」按钮 —— 几个 `.apk` 不再长一个样。
- 点「下载」弹「下载安装」进度框：**百分比 + 下载速度 + 已下载/总大小 + 用时**；
  下载完成自动安装，结果（已提交静默安装 / 已转系统安装器 / 失败原因）留在框里并 Toast 提示一次；
  失败可「重试」，成功后列表标记「已安装」（`Prefs.cloudInstalled`）。
- 下载速度靠 `FileDownload` 新增的 `onDetail(已下载字节, 总字节, 已用毫秒)` 采样计算，
  格式化逻辑在 `core/net/TransferFormat.kt`（有单测）；行视图在 `ui/widget/CloudAppRowView.kt`。

验证：`TransferFormatTest`、`UpdateStartupCheckTest`、`CloudDialogsTest`（询问框按钮回调、
进度/速度/结果文案、失败重试、列表行区分度 + 预览图 `preview/y20-*.png`）全绿；
全量单测 + system/debug 构建通过。

## 导航卡底行字号与间距（第 106 轮）

用户反馈「导航模式下的剩余距离、到达时间看不清」。导航卡底行整体放大，并与车道块拉开间距：

- 弱标签（剩余 / 公里 / 到达）18 → **20**；数值（里程、剩余时间、到达时刻）21 → **25**；
- 卡片宽度 520 → **620**，给放大后的字留出水平空间；极端长文案（小时级剩余 + 长里程）放不下时按比例收到 `fit`（最低 0.86），不会和右侧 ETA 撞上；
- 底行与车道块之间新增 **18px** 间距（原来紧贴），基线再 +2；卡片高度随内容自动增加。
- 首页中央大字歌词：上一句 / 下一句 24 → **30**（当前句 52 不变）。

## 行程1 里程/时长读不到（第 108 轮修复）

4204 诊断包显示 `行程1 原始信号 distanceRaw=null format=-1 timeRaw=null`。
原因：`TS_TripDistance`/`TS_TripTime` 在车机上登记为 **Integer**，而旧实现只按
`byte[]` + `PublicAttrValue` 读，自然永远 null（平均油耗那条恰好是 byte[]，所以正常）。

修复：`TripSignals` 改为多通道读取（byte[] → `getProperty(int,int)` →
`getProperty(Integer,…)` → 全区域），诊断「车辆信号自检」新增「行程1 信号」一行，
列出登记类型与每条通道的读取结果。

## 巡航红绿灯：适配高德 9.5 灯态广播补丁（第 109 轮）

用户提供了打好补丁的高德（`gd95_新基础版_仅设置dex_路口图修复_红绿灯广播.apk`）和协议说明：
它在原生 `CameraLightInteract.notifyCameraLightInfosImpl()` 后广播
`NAVITOOL_CRUISE_TRAFFIC_LIGHTS`，extra `data` 是 JSON（`ts` + `lights[]`），
`count` / `ts` 另带；**巡航、导航都会发**，靠 `AUTONAVI_STANDARD_BROADCAST_SEND`
`KEY_TYPE=10001` 的 `TYPE` 区分模式（0 导航 / 1 模拟导航 / 2 巡航）。

桌面按文档把口径对齐（4207）：

- `status`：`1` 绿 / `4` 红（原生枚举）；老 YH 包 `0` 红 / `1` 绿 / `2` 绿 一并兼容；
- `countdown`：`>0` 秒数；`0` = 通行、`-1` = 无读秒 —— 后两者都不显示数字；
- `direction`：`0` 掉头 / `1` 左 / `2` 直 / `3` 右，巡航卡新增掉头箭头，按掉头→左→直→右排序；
- 模式：收到带 `TYPE` 的引导帧时直接切换「导航/巡航」，不再只靠路线剩余判断；
- 导航卡也吃这条广播：`trafficLight()` 优先用巡航那条，60073 缺失时不会空着。

模拟器注入验证（`emu-verify/y26/`）：注入 3 个方向 → 巡航卡显示「掉头 35 红 / 左 12 红 / 直 7 绿」，
日志 `收到巡航多方向红绿灯 count=3 dirs=0,2,3`。

## 日夜模式：与高德互相同步（第 110 轮）

「日夜模式 → 跟随高德」原来**根本同步不了**：「跟随光感」也**读不到光**。两处根因都不是高德的问题，
是高德车机版（`gd95_新基础版_v24t.apk`，9.5）反编译出来的协议：

| 方向 | action | `KEY_TYPE` | 字段 | 语义 |
|---|---|---|---|---|
| 高德 → 车机 | `AUTONAVI_STANDARD_BROADCAST_SEND` | `10019` | `EXTRA_STATE` | `37` 白天 / `38` 黑夜 |
| 车机 → 高德 | `AUTONAVI_STANDARD_BROADCAST_RECV` | `10048` | `EXTRA_DAY_NIGHT_MODE` | `0` 自动 / `1` 白天 / `2` 黑夜 |
| 车机 → 高德 | `AUTONAVI_STANDARD_BROADCAST_RECV` | `13030` | — | 查询高德当前日夜（高德回 `10019` 的 37/38） |

证据：`AndroidProtocolExe.sendAutoStatus` 的 `case 37 → onDayNightChange(1)`（白天）、
`case 38 → onDayNightChange(2)`（黑夜）；`defpackage/y70.java` `30→37`/`32→38`；
`defpackage/xh.java`（`EXTRA_DAY_NIGHT_MODE` → `nativeDayNightMode`）；
`defpackage/ga0.java`（`13030` → `nativeRequestDayNightMode`）。

**根因 1：「跟随高德」的信源取错了。**
高德的 `10019` 是**共用通道** —— 导航中(8)、巡航(9)、模拟(10)、在不在地图页(3/4) 与
**白天(37)/黑夜(38)** 挤在同一个 `EXTRA_STATE` 上。桌面原来把 `10019` 一律当「导航状态」，
`37`/`38` 被静默丢掉（诊断包里能看到 `高德导航状态 state=40/50/51/3/4` 就是这么记的）。
于是「跟随高德」退而读系统 uiMode —— 而这台车机上**没人写 uiMode**
（只有参考 App（君君桌面） `DayNightController.writeAndroidUiNightMode` 会写），读到的永远是死值。

**根因 2：「跟随光感」读的其实是设置值，不是光。**
车机日间模式功能值（`0x20150100`）在真车上**恒为 AUTO**（诊断包实测
`读取属性 func=0x20150100 value=538247427`），AUTO 时它没有明暗信息，只能回落到
`UiModeManager.nightMode` —— 那是**设置值**（固定 1/2，或 AUTO 时压根读不到），
不是「当前明暗」。真正的光感是 AdaptAPI 的 `Device.getDayNightMode().getDayNight()`，
跟随时取车机光照 `Twlibrists`（`OFF`→夜间、`ON`→白天）；参考 App（君君桌面）读明暗走的就是这条。

**修完之后的来源链**（与参考 App（君君桌面）`computeDesiredNightUi` 一致）：

```
跟随高德 = 高德广播(10019/37,38) → 车机光感 → 车机日间模式功能值 → 系统 uiMode → 上次应用值
跟随光感 =                        车机光感 → 车机日间模式功能值 → 系统 uiMode → 上次应用值
跟随时间 = 本地时段表（不依赖车机）
```

- 新增 `core/nav/AmapDayNight.kt`：高德日夜协议常量与解析；
- `AmapDesktopGuide` 先认 `37`/`38` 再当导航状态；新增回写 `10048` 与查询 `13030`；
- 新增 `vehicle/AdaptapiDayNight.kt`：反射开 `Device.getDayNightMode()` 读真·光感，
  `Proxy` 注册 `IDayNightChangeCallBack` 做**推送**（400ms 合并，避免明暗边界上配色来回闪）；
- 桌面切好日夜后**回推高德**（`10048`），地图跟桌面一致；
  **但「跟随高德」时绝不回写** —— 回写等于把高德的「自动」锁死成固定昼夜，
  高德以后自己就不切了，桌面也就永远停在旧值上。

单测：`AmapDayNightTest`（协议解析）、`AmapDesktopGuideDayNightTest`（`37`/`38` 必须走日夜通道、
导航状态不能被当成日夜）、`AdaptapiDayNightTest`（用同名同签名的假实现把反射路径真跑一遍：
类名 / 方法名 / 动态代理 / 回调派发 / 合并延时）、`DayNightTest`（来源优先级）。

## 桌面卡片：「车况」开关控制的其实是续航卡（第 111 轮）

设置 → 桌面布局 → 桌面卡片 → 打开「车况」开关，**关掉的是续航卡**；
而右侧那张真正的车况卡（车门 / 车窗 / 引擎盖 / 后备箱俯视车模）**压根没有开关**。

**根因：两张卡被同一个名字混起来了。**

| | 画的什么 | 在哪儿 | id | 以前的标题 |
|---|---|---|---|---|
| 行内卡 | **续航**（里程大字 + 油量条 + 「燃料剩余 · 62%」） | 底部卡片行，左起第一张 | `car` | 「车况」❌ |
| 车况卡 | **车况**（俯视车模 + 门/窗/盖状态图例） | **右侧固定**，388×250 | — | 压根没开关 ❌ |

- `core/CardLayout.kt`：`CAR -> "车况"`，而 `ScenePage.drawBottomCards()` 里
  `CardLayout.CAR -> drawCarCard(...)` —— 那个函数画的是 `canvas.drawText("续航", ...)`；
- 真正的车况卡是 `ScenePage.drawCarStatusCard()`（标题 `"车况"`），
  **固定画在右侧**（`CAR_STATUS_CARD_TOP=490` / `W=388` / `H=250` / 右对齐 `rightEnd`），
  `onDraw()` 里**无条件**调用，既不在底部卡片行、也不在 `CardLayout.ALL` 里；
- 参考 App（君君桌面）的底部卡 ids 是「快捷 / 媒体 / 空调 / 胎压 / **续航**」
  （`tools/ref_cites.json` 引用 `SceneDesktop.BOTTOM_CARD_IDS`），
  我们自己的注释也写着「参考 `SceneDesktop.disableShortClick(sceneCarCard)`（:703）：**续航卡**不可点」
  —— 说明 `car` 这个 id 在参考 App（君君桌面）那里本来就是**续航卡**，只是我们后来给它错配了「车况」标题；
- git 历史：第九十六轮 `8f4c2b6` 引入「续航 ↔ 行程1 位置互换」时，
  注释已经写着「让『续航』排到最左」，但操作的是 `CAR`/`TRIP1` 槽位 —— 语义漂移从那时就埋下了。

**修法：把两张卡彻底拆开。**

- `core/CardLayout.kt`：
  - `CAR` 常量改名 **`RANGE`**（**持久化 id 仍是 `"car"`**，只改 Kotlin 常量名与显示名）；
  - `title(RANGE) = "续航"`；新增 `CAR_STATUS = "carstatus"`，`title(CAR_STATUS) = "车况"`；
  - **`CAR_STATUS` 不进 `ALL`** —— 进了会被 `widths()` 当成行内卡参与分宽，把底部那一行挤变形；
  - 顺带修掉误导性注释（`Size.AUTO` 的 KDoc、`widths()` 里的「车况卡吃掉行尾剩余宽度」，
    实际管的是行内那张**续航**卡）。
- `core/Prefs.kt`：新增 `carStatusCardVisible`（键 `scene_car_status_card_visible`，**默认 true** = 保持升级前现状）。
- `ui/pages/ScenePage.kt`：新增 `showCarStatusCard`；
  `drawCarStatusCard()` 开头提前返回 ⇒ 卡不画，**车窗命中区 `carWin0..3` 也不注册**
  （点那个位置不会再误触「切换全部车窗」）。
  行内那张的 `drawCarCard` 改名 `drawRangeCard`、`CAR_PRESET_W` 改名 `RANGE_CARD_W`。
- `ui/SettingsOverlay.kt`：「桌面卡片」页**最上面**单独一节「车况」（右侧固定卡片），
  底下才是底部卡片行里的「续航」等。
- `ui/LauncherView.kt`：`prefs.carStatusCardVisible` → `scenePage.showCarStatusCard`
  （建页时设一次，`refresh()` 里每轮同步）。

**持久化 id 不动**：`scene_card_layout_v2` 里存的就是 id 字符串，改 id 会让老设备的布局
整份读不出来（`decode` 认不出 id 直接回退默认布局）。所以本轮只改常量名与显示名，
默认布局的编码串仍逐字节相同
（`car:MEDIUM:1,quick:AUTO:0,music:AUTO:1,climate:AUTO:1,tire:AUTO:1,trip1:MEDIUM:1,trip2:AUTO:0,status:AUTO:0`）。

单测：`CardLayoutTest`（标题映射、id 不变、`CAR_STATUS` 不进 `ALL`）、新增
`CarStatusCardVisibilityTest`（Robolectric：隐藏后车窗命中区不再注册、那块像素真的不画、
偏好默认 true）。

## 画中画黑屏 / 行程卡 / 桌面启动唤起高德（第 112 轮）

第 112 轮一次修三件事（依据 4209 的诊断包 `cardesk-logs-20261104-212457.zip`）。

### 1. 日夜切换后画中画高德一直黑屏（要「全屏一次」才恢复）

**根因：换肤重建时旧的画中画承载面没有释放。**

`applyTheme()` → `build()` 会把整棵 UI 树重建 —— 包括导航页里的 `PipSurface`。
但 `PipSurface` **没有 `onDetachedFromWindow` 释放**，`ActivityView` + `VirtualDisplay`
只 `clearContent()`（换应用 / 关闭）时才 release。于是：

1. 旧的 `VirtualDisplay` **泄漏**（反复切日夜会攒出一堆残留 task）；
2. **高德的 task 还挂在旧 VirtualDisplay 上**；
3. 新承载面用同一套 `FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_RESET_TASK_IF_NEEDED`
   旗标再启动一次高德时，系统会把**旧 task 拉到前台** ——
   而旧 task 在旧 display 上 → **新承载面里只剩一块黑**。

（诊断包里 `画中画已承载该包名，复用当前画面` / `画中画画面采样 maxLuma=255 black=false streak=0`
都来自切页时刻；切主题那一刻的日志已经被日志轮转冲掉了，所以根因是从代码链推出来的。）

**用户绕法为什么有效**：全屏 → 还原那条路会 `relaunchAfterResize()` **重新下发一次启动**，
把 task 搬到当前 display 上 —— 于是恢复了。

**修法**：

- `PipSurface.release(reason)`：彻底拆掉承载面（release `ActivityView` → 释放 VirtualDisplay
  → 连带销毁旧 task），并让占位回来（**绝不留一块黑屏**）；
- `LauncherView.build()`：**重建前**先 `releasePipSurfaces("重建前")`；
- `PipFrameProbe.shouldHealNeverSeenContent()`：**新增一条自愈路径** ——
  「嵌入之后一直没出画面」（`samples ≥ 4` 且连续全黑）。
  老路径要求「**先见过非黑画面**」，而新承载面从头到尾是黑的 → `sawContent` 永远是 false
  → 老路径**永远不自愈**。新路径只在**刚嵌入的 90 秒窗口内**判、且**一轮只自愈一次**
  （`PixelCopy` 恒为黑的设备最多多重启一次承载应用）；
- 顺带修好「重建时画中画在全屏态」的边界：重建后按普通态承载、再提到最层
  （`enterPipFullscreen()`），否则会掉回地图卡里且不承载。

> AOSP 9 `android.app.ActivityView` 的 `release()` 会在 `mVirtualDisplay == null` 时抛
> `IllegalStateException`（没就绪过就没有 display，本来也不泄漏）；
> `performRelease()` 会 `mVirtualDisplay.release()` —— 这就是旧 task 被清掉的那一步。

### 2. 卡片库去掉行程2，只留一张「行程卡」

- `CardLayout`：`TRIP1` 常量改名 **`TRIP_CARD`**（**持久化 id 仍是 `trip1`**），
  `title()` 改成「行程卡」；`TRIP2` 从 `ALL` / `DEFAULT` / `title()` 里**移除**，
  改留 `LEGACY_TRIP2 = "trip2"` 只用于迁移；
- `decode()` 遇到老布局里的 `trip2` **丢掉这一张**，而不是让整份布局读不出来 ——
  否则 `decode` 返回 null → 回退默认布局 → 用户攒下来的顺序 / 尺寸 / 显隐**全被冲掉**；
- `ScenePage`：`drawTrip1Card` → `drawTripCard`，卡上标题「行程1 · 总」→「行程」；
  **里程 / 时长改用 `ITripData.getLatestDrivingInfo()`**（原来行程2 卡用的那条路）——
  原来读的 VHAL `TS_TripDistance / TS_TripTime` 在真车上**读不到**
  （诊断包：`行程1 原始信号 distanceRaw=null distanceVia=- format=-1 timeRaw=null`），
  而 `ITripData` 是通的（诊断包：`行程里程/时长：8.5 km / 31.0 分钟`）；
  平均油耗与总里程不变；
- 行程2 卡上的「复位」胶囊、命中区、`TripResetDialog` 一并移除
  （入口没了）；车控层的 `VehicleController.resetTrip2()` **保留**（是车控能力，不是 UI）。

### 3. 桌面启动不再唤起高德

桌面启动时我们做两件**会唤起高德**的事：

1. **画中画承载**（`refreshPip()` → `ActivityEmbed.embed` → `startActivity`）
   —— 那就是**真的把高德这个 Activity 启动起来**；
2. **向高德索要区域信息**（`AmapCitySource.requestAreaInfo`）
   —— 那个广播带 `FLAG_INCLUDE_STOPPED_PACKAGES`，
   `AmapAreaInfo` 的注释里写得很直白：「**才能把高德顺带拉起来**」。

这两件事以前都在桌面**还没画完**的时候做（画中画在 `build()` 里、索要区域信息在
`startServices()` 里）→ 开机时**高德先跳出来**，跟桌面自己的首帧抢 CPU / 抢焦点。

**现在统一推到「桌面完全启动之后」**（启动页淡出、`desktopReady` 那一刻）：
新增 `LauncherView.startDeferredAmapActions()`，由 `MainActivity.onDesktopReady()` 调用。

## 首页中央大字歌词：改五行 + 四行放大（第 113 轮）

用户要求：首页那坨大字歌词**改成五行**，并且**当前行之外的四行整体放大一点**
（当前行只跟着抬一点点）。

### 版式

| 行 | 字号 | 颜色 | 说明 |
| --- | --- | --- | --- |
| 上 2 句 | 34px | `TEXT_DIM` | 最远、最淡 |
| 上 1 句 | 40px | `TEXT_SOFT` | 近一档 |
| **当前句** | **56px 粗体** | `TEXT` + 强调色擦除 | 视觉焦点 |
| 下 1 句 | 40px | `TEXT_SOFT` | 近一档 |
| 下 2 句 | 34px | `TEXT_DIM` | 最远、最淡 |

- 第 106 轮是**三行**（上一句 30 / 当前句 52 / 下一句 30），
  现在是**五行**，衬句从 2 行变 4 行；
- **为什么字号分三档**：五行同字号会糊成一片，扫一眼看不出哪句是「正在唱」。
  现在是「字号越大越靠近当前句 + 颜色越亮越靠近当前句」，
  两层线索叠在一起，视线自然落到中间那一句；
- **为什么当前句只从 52 抬到 56**：用户明确说「主要是除了当前行之外的四行要放大」，
  真正的大头在衬句上（30 → 40 / 34）。

### 基线为什么是算出来的

原来三行是写死基线（`LYRIC_PREV_Y` / `LYRIC_MAIN_Y` / `LYRIC_NEXT_Y`）。
五行放不下三行的高度，而行距是**像素**、一行文字的上下留白（ascent / descent）
取决于字号和字体 —— 写死基线一旦改字号就会「两行贴在一起」或「中间裂开一大段」。

现在只有**当前句的基线**是设计常量（`LYRIC_MAIN_Y = 562`），
其余四行按 **Paint 的真实字体度量 + 行距常量**（`LYRIC_GAP_MAIN` / `LYRIC_GAP_SIDE`）
往上/往下推。以后再调字号不用重新量坐标。

### 位置：整块必须落在「引导卡下沿」与「底部卡片区上沿」之间

桌面中间能用的是 `387`（左上引导卡最高形态的下沿）到 `764`（底部卡片区上沿）这一段。
三行时整块只占 410~590，五行 + 放大后实测墨迹 **396~686**：

- 上沿离引导卡 **9px**；
- 下沿离底部卡片区 **78px**；
- 当前句基线因此从 516 下移到 **562**。

这两个边界写成了常量（`LYRIC_GUIDE_CARD_BOTTOM` / `LYRIC_BOTTOM_CARD_TOP`），
单测直接拿像素差分出来的行带去卡边界 —— **不靠肉眼**。

### 取句：跟导航页共用一份实现

`LyricWindow.window(lines, index, total)`（**纯 Kotlin**，JVM 单测覆盖）
按 `split(total)` 取出「上 → 当前 → 下」的整窗，
首页（`ScenePage.setLyricWindow`）和导航页组合卡用的是**同一份**取句逻辑，
不会出现「首页比导航页少两句」的错位。

`LauncherView` 的歌词回调改成：
`navPage.setLyricWindow(lines, index)` + `scenePage.setLyricWindow(lines, index)`；
没有歌词（`timeline == null`）时**整窗清空**（`上2 / 下2` 也清，
否则会留下上一首歌的残句），只保留控制器状态文案当当前句。

### 验收

- 单测：`LyricWindowTest`（整窗取句 / 越界占位 / 空行占位）、新增
  `SceneLyricWindowTest`（五个属性的映射）；
- 渲染测试 `LyricsPreviewRenderTest`：**逐像素差分**「无歌词底图」与「有歌词图」，
  把行带数出来，断言正好 **5 条**、上下对称、字号三档关系、
  整块在引导卡与卡片区之间、**有引导卡时歌词不画到引导卡上**；
- 效果图：`preview/y27-lyrics-5lines.png`、`preview/y27-lyrics-5lines-guide.png`。

## 首页中央大字歌词：块居中 + 字号可调（第 114 轮）

第 113 轮把歌词改成五行之后，用户反馈两件事：**太偏下了**，以及**字号要能调**。

### 1. 歌词块整体居中

第 113 轮为了避开左上导航引导卡，把当前句基线从 516 压到了 562 ——
结果整块墨迹落在 396~686，**比原来那版三行（410~590）明显偏下**。

第 114 轮把锚点从「当前句基线」改成「**块中心**」：

- `LYRIC_BLOCK_CENTER_Y = 500` —— 桌面中间那段空区（天气下沿 223 到
  底部卡片区上沿 764）的中心，也正好是第 106 轮三行版式的中心 500；
- 实测 **100% 时整块墨迹 353~643**（中心 498）、
  **140% 时 294~702**（中心 498）—— 字号怎么调，块都在同一个中心上。

**为什么锚在块中心而不是某一行**：字号可调之后整块高度会变；锚在当前行基线上，
放大时整块往上顶、缩小时往下坠，位置跟着字号漂。锚在块中心上就没有这个问题。

### 2. 字号做成设置里可调

- 设置 → 桌面设置 → **仪表歌词** → 新增「**首页中央歌词**」一节 →
  「字号缩放」滑杆 **70% ~ 140%**（步长 5）；
- 落盘键 `scene_lyric_scale_pct`（**整数**百分比，默认 100）——
  存整数而不是浮点，避免不同机型上浮点精度带来的
  「同一台车重启后字号变了一点」；
- 缩放同时作用于**三个字号和两个行距**，当前句和上下四行一起变；
- 拖滑杆时桌面**立刻**跟着变（`SettingsOverlay.onSceneLyricChanged` →
  `scenePage.lyricScale`），不用等每秒 tick。

### 3. 引导卡避让改成「按行收窄」

块往上提之后，最上面那行的字顶（353）就进入了引导卡的竖直带（73~387）——
歌词太长就会伸到引导卡底下、被卡片压住半行字。

现在的规则：**只有「够高」的行**（字顶在引导卡下沿之上的）才把最大宽度
从 960 收到 **576**（左缘落在引导卡右缘 664 之外 8px），其余行照常 960。
正常字号下只有最上面一行够高；放大到 140% 时第二行也跟着够高 ——
**只有真正会撞上的行才被截断**，其余行不受牵连。

单测直接卡这条：带引导卡渲染一帧，逐像素差分**引导卡矩形内**不允许有任何变化。

### 验收

- 单测 **1572 全绿**（上一轮 1568 → 本轮 1572，+4：缩放默认值 / 越界夹取 / 场景页缩放夹取，
  以及渲染测试新增的「字号放大后仍然居中」用例）；
- 渲染测试 `LyricsPreviewRenderTest` 新增「字号放大后仍然居中」用例；
- 效果图：`preview/y27-lyrics-5lines.png`、`preview/y27-lyrics-5lines-guide.png`、
  `preview/y28-lyrics-scale-140.png`、`preview/y29-settings-lyric-scale.png`。

## 设置页里的歌词预览条（第 115 轮）

第 114 轮把歌词字号做成了可调，但留了个尾巴：**设置面板把桌面正中那块歌词整个挡住了**，
拖滑杆时用户在背后根本看不到变化 —— 只能关掉面板再回来看一次，
来回折腾好几轮才调得出来。

现在滑杆上面加了一条**实时预览**（`LyricScalePreviewView`）。

- 画的是**桌面那块歌词的同比例缩小版**：同样的字号常量（34 / 40 / 56）、
  同样的行距、同样的三档配色（`TEXT_DIM` → `TEXT_SOFT` → `TEXT`）、
  同样的居中规则；
- 走的是**共用排版** `LyricBlockLayout`（**纯 Kotlin**，JVM 单测覆盖）——
  桌面（`ScenePage`）和预览条用的是同一份基线/居中算法，
  两边各写一套的话，预览跟实际画出来的东西会对不上，用户就白调了；
- 宽度用**真实的**歌词最大宽度（960），所以「一句能显示多少字」也是真的；
- **优先用真实歌词**：桌面正在放歌时，预览里就是自己那首歌的上下五句
  （`LauncherView.previewLyricLines()` → `LyricWindow.lineAt` 取窗口）；
  没在放歌 / 歌词还没加载才退回自带示例文案。

### 单测

- `LyricBlockLayoutTest`（纯 Kotlin，6 例）：行序、相邻间距公式、
  上下近似对称的差值来源、**放大后平移量把块拉回同一个中心**；
- `LyricScalePreviewRenderTest`（Robolectric 渲染，3 例）：预览条正好 5 行、上下对称、
  140% 时每行更高且**块仍然居中**、没有歌词时不画字；
- 全量单测 **1582 全绿**（上一轮 1572 → 本轮 1582）。

效果图：`preview/y30-settings-lyric-preview.png`。

## 导航页画中画不显示应用（第 116 轮）

4213 反馈：**导航页的画中画不显示应用**（只有一块「请选择应用」占位图）。

### 根因：一条静默断掉的启动链

第 112 轮把画中画承载（= 真的启动高德这个 Activity）推迟到了「桌面完全就绪」之后，
链路是：

```
MainActivity.onDesktopReady()
  → LauncherView.startDeferredAmapActions()
    → pipHostingStarted = true
      → refreshPip() → PipSurface.show(pkg)
```

而触发 `onDesktopReady()` 的，是启动页淡出那一步 —— 当时写的是：

```kotlin
(current.parent as? ViewGroup)?.removeView(current)
current.post { onDesktopReady() }
```

`View.post(Runnable)` 在视图**没有 attach 到窗口**时，会把 Runnable 塞进这个视图自己的
`HandlerActionQueue`；而那个队列只在视图**重新 attach 到窗口**时才会被执行
（`View.dispatchAttachedToWindow` 里才 `executeActions`）。

启动页刚被 `removeView` 摘掉 → 已经脱离窗口 → 永远不会重新 attach →
**那个 Runnable 永远不会跑**。

于是：

- `onDesktopReady()` 永不执行；
- `StartupTrace` 里少了 `desktopReady` 这一个分段
  （4213 诊断包 `summary.txt` 的「本次启动」里**确实查不到它**，
  而 `launcherCreated` / `launcherReady` / `servicesStarted` / `launchServicesStarted` 都在
  —— 说明桌面自己跑完了，就差最后这一步）；
- `pipHostingStarted` 永远是 false → `refreshPip()` 直接 return →
  `PipSurface.show()` **一次都没被调用** → 画中画只剩占位图。

诊断包佐证：只有「画中画占位点击（选应用）」，
没有「画中画显示」，也没有「刷新画中画」。

它**一声不响**：没有异常、没有崩溃、没有日志。
第 112 轮到第 115 轮整整四轮都没发现。

### 修法：三道保险

1. **修根因** —— `MainActivity` 改用 Activity 自己的主线程 Handler
   （`mainHandler.post { onDesktopReady() }`）：跟视图的 attach 状态无关，一定跑得到；
   同时 `onDesktopReady()` 改成幂等（只做一次）。

2. **加兜底闸门** —— 新增纯 Kotlin 的 `core/DesktopReadyGate.kt`，
   把「承载该开始了」这件事的判定单独拆出来：
   正常路径是「桌面完全就绪」，兜底路径是「桌面首帧之后等满 3 秒」。
   任何一条先到，承载都会开始，且**只开始一次**。

3. **桌面侧看门狗** —— `LauncherView.notifyFirstFrame()` 记下首帧时刻，
   3 秒内没等到就绪信号就自己把承载启动起来
   （日志「画中画承载由兜底看门狗启动」）。

第 2、3 条的意义：光修那一行不够 —— 下次换个地方再断一次，还是得靠用户报障才发现。

### 单测

- `DesktopReadyGateTest`（纯 Kotlin，7 例）：闸门每条分支
  （首帧之前绝不开始、就绪即开始、兜底窗口、两条路径只生效一次、重建不刷新计时起点）。
- `DetachedViewPostTest`（Robolectric，3 例）：把平台的坑钉死 ——
  **脱离窗口的 `View.post` 永远不会执行**，而主线程 Handler 一定执行。
- `PipHostingStartupTest`（Robolectric，3 例）：端到端 ——
  桌面启动后承载一定开始、承载一定真的落到主画上、
  以及「桌面就绪这条正常路径自己能启动承载（不能只靠兜底看门狗）」。
  最后这条做过**反向验证**：把修复临时改回旧写法，它会变红。
- 顺带修了一处测试隔离：`AdaptapiDayNightTest` 的假实现回调列表是进程内静态的，
  别的测试类起完整桌面时会真的注册一次昼夜回调，把它顶红。
  现在它在自己的 `setUp` 里先清干净。
- 全量单测 **1595 全绿**（上一轮 1582 → 本轮 1595）。

### 模拟器验证（AOSP 29，1920×968，对齐车机分辨率）

装 4214 后冷启动，日志里能直接看到这条链走通了：

```
启动分段：desktopReady processMs=4031 onCreateMs=1968
桌面就绪后开始唤起高德相关动作
开始承载画中画 reason=桌面就绪
刷新画中画 pkg=com.autonavi.amapautp.yh
ActivityView 嵌入成功 pkg=com.autonavi.amapautp.yh
[EVT] 画中画显示 pkg=com.autonavi.amapautp.yh app=Amap Auto mode=embedded role=主画
ActivityView 启动承载应用 pkg=com.autonavi.amapautp.yh api=pendingIntent
```

**A/B 对照**：用同一台模拟器装 4213（有 bug 版）冷启动，
`desktopReady` 不出现、`画中画显示` 不出现，画中画停在占位图；
装 4214 后同一台机器、同一份 `pip_app`（高德），画中画里**高德在真实渲染**
（地图、搜索框、罗盘、比例尺都在）。

证据：`emu-verify/y29/`（报告 `emu-verify/y29/REPORT.md`）。

产物：`dist/CarDesk-platform-ecarx-cardesk-4214.apk`
（4214 / 1.0.1118-system，平台证书 SHA-256 `c8a2e9bc…92ab8`，
APK SHA-256 `1085fb4a7711df2217b7aaef5cea77a142f7e4ef3d5e88c1c0c575ac41a816e1`）。

## 首页中央歌词：三行 / KTV 两行 可切（第 117 轮）

用户要求三件事：

1. 首页大字歌词改成**三行**，**大小保持不变**；
2. 再加一种**上下两行**、像 KTV/MTV 的排法；
3. 两种**允许选**。

### 两种布局

```
三行（默认）                    KTV 式两行
  上一句（40px，SOFT）            当前句（40px）  ←
  当前句（56px 粗体）  ←          下一句（40px）
  下一句（40px，SOFT）
```

- **三行**：沿用第 113 轮「近 / 当前 / 近」那三档字号（40 / 56 / 40）——
  用户说「大小保持不变」，所以**只减行数、不动字号**。
- **KTV 式两行**：**当前句在上、下一句在下**，两行之间**明显分隔**
  （行距放大到 1.8 倍），而且**两句一样大**（第 118 轮按用户要求改的，
  详见下面那节）。唱过的那句直接往上走掉，
  屏幕上永远是「正在唱的 + 马上要唱的」这一对 —— 跟 KTV 字幕一样。

> 用户中途更正过一次语义：「两行歌词应该分隔上下各一行；当前句在上」。
> 之前按「上一句在上」做的那版已全部改成当前句在上。

### 怎么做的

- 新增纯 Kotlin 的 `core/media/LyricLayout.kt`：
  一个布局 = 「行数 + 当前行下标 + 行序 + 每行字号档 + 行距倍数」。
  这几样缺一不可 —— **当前行不总在中间**（三行在中间、KTV 在最上），
  差一个就画错位置。判定逻辑可以直接 JVM 单测覆盖。
- 字号档单独放在文件级的 `LyricSizeTier` 对象里 ——
  Kotlin 的**伴生对象在枚举项之后才初始化**，
  枚举构造参数引用伴生常量会直接编译失败
  （"Companion object of enum class is uninitialized here"）。
- `core/media/LyricBlockLayout.kt` 的基线算法从「写死 5 行」改成**按行数算**，
  行距也只剩一档（原来 5 行时有「衬句与衬句」的更紧行距，现在用不上）。
- `core/media/LyricWindow.kt` 加了按**显式行序**取句的重载 ——
  `split(n)` 是按「上下均分」算的，它假设当前句在中间；
  KTV 的「当前句在最上」得由布局自己给行序（`[0, 1]`）。
- `ScenePage.drawCenterLyric` 与设置页的预览条都按**同一份清单**画 ——
  预览里看到的必须是桌面上真会出现的。
- 首页中央原来的**五行**布局被三行取代，
  `ScenePage.lyricPrev2` / `lyricNext2` 一并删掉；
  **导航页组合卡仍是 5 句**（`NavPage` 自己的字段，没动）。
- 落盘键 `scene_lyric_layout`（`three` / `ktv`），默认 `three`；
  认不出来的键一律回三行（脏键不该让歌词整块不显示）。
  存的是**键**不是下标 —— 以后加布局下标会错位。
- 设置入口：设置 → 桌面设置 → 仪表歌词 → **首页中央歌词 → 歌词布局**，
  换布局时预览条立刻跟着变（预览条上方的提示文案也会说明当前布局长什么样）。

### 像素级断言（不靠肉眼）

`LyricsPreviewRenderTest` 拿「无歌词底图」跟「有歌词图」**逐像素差分**，
数出文字行带：

| 布局 | 实测行带 | 断言 |
|---|---|---|
| 三行 | `[405..442, 469..524, 554..591]` | 正好 3 条；上下衬句同高（37）；当前句最高（55）；块中心 498 ≈ 500 |
| KTV 两行 | `[440..477, 519..556]` | 正好 2 条；**当前句在上面**；**两行一样高**（37 / 37）；中间空档 42px；块中心 498 ≈ 500 |
| 三行 140% | `[367..420, 458..533, 575..628]` | 还是 3 条；块中心 497 ≈ 500；不压底部卡片区 |

另外还断言「有导航引导卡时，歌词一个字都不画到引导卡上」。

### 单测

- `LyricBlockLayoutTest`（10 例，含 KTV 两行与「当前行下标越界要报错」）、
  `SceneLyricWindowTest`（12 例）、`LyricScalePreviewRenderTest`（4 例）、
  `LyricsPreviewRenderTest`（3 例）、
  `SettingsLyricLayoutTest`（2 例，真点设置页里的「KTV 两行」，验偏好落盘 +
  预览条 + 桌面三处联动）。
- 全量单测 **1608 全绿**（上一轮 1595 → 本轮 1608）。

效果图：`preview/y31-lyrics-3lines.png`、`preview/y31-lyrics-3lines-guide.png`、
`preview/y31-lyrics-ktv2.png`、`preview/y32-lyrics-scale-140.png`。

产物：`dist/CarDesk-platform-ecarx-cardesk-4215.apk`
（4215 / 1.0.1119-system，平台证书 SHA-256 `c8a2e9bc…92ab8`，
APK SHA-256 `f7cae1a904187c31dc1b8f4413f1bf27b1f64c7d49255ef58ccf9115f43d314e`，2217642 字节）。

### 模拟器实机验证

`emulator-5556`（Android 10 / SDK 29 / arm64-v8a，`wm size 1920x968` 对齐车机），
探针 `com.cardesk.mediaprobe` 注入《怎么我睡不着你也不来救我》，
桌面在线抓到歌词（`歌词竞速命中 provider=qq lines=54`）：

| 截图 | 内容 |
|---|---|
| `emu-verify/y30/y30-lyric-three.png` | **三行**：上一句（40 灰）/ 当前句（56 粗 + 青色擦除）/ 下一句（40 灰） |
| `emu-verify/y30/y30-lyric-ktv.png` | **KTV 两行**：**当前句在上**（56 粗 + 青色擦除）/ 下一句在下（40 灰），中间明显分隔 |
| `emu-verify/y30/y30-settings-layout-three.png` | 设置页「歌词布局」：三行（选中）/ KTV 两行 + 说明 + 三行预览条 |
| `emu-verify/y30/y30-settings-layout-ktv.png` | 点「KTV 两行」后：chip、预览条、说明文案全部跟着换 |

两种布局是**同一首歌、同一帧歌词**截的，唯一变量就是布局。

#### 验证时抓到并修掉的一个 bug

第一版切到 KTV 两行时，预览条变了、桌面也变了，
但 **chip 下面那句说明还是三行的「上一句 / 当前句 / 下一句」**。

原因：那句说明是在**建页时**写死一次的（`note()` 只是往 body 里塞一个 TextView），
chip 回调只更新了预览条和桌面，没回头改那行字。

修法：`note()` 改成**返回它建的 TextView**，歌词布局那一节把返回值留在手里，
chip 回调里就地改文案。补的断言**做过反向验证** ——
临时去掉那行更新，用例立刻变红。

#### 取证脚本

`tools/emu_lyric_layout.sh`（三行 / KTV 两行各截一张）：

```bash
bash tools/emu_lyric_layout.sh three y30-lyric-three
bash tools/emu_lyric_layout.sh ktv   y30-lyric-ktv
```

**为什么必须 `kill -9`**：CarDesk 是系统 HOME 应用，
`am force-stop` 之后系统会立刻把它重新拉起来，旧实例带着内存里的 `page=1`；
`am start` 复用那个已有实例 → 桌面一启动就停在导航页，
还会把刚写好的 `last_page` 又写回 1、把偏好覆盖掉。

## KTV 两行改成「两句一样大」（第 118 轮）

用户看了 4215 的实机图之后提的：

> 对于两句歌词我的意思是这样布局，**前后句保持大小一样，不需要区分前后句大小**。

4215 那版 KTV 两行是「当前句 56px 粗体 + 下一句 40px 常规」—— 一粗一细。
这一版改成：

```
当前句（40px，青色逐字擦除）  ←   ← 靠颜色 + 位置认出来
下一句（40px，暗灰）
```

### 三处都要改，只改字号是不够的

1. **字号档**：`LyricLayout.KTV` 的 `sizeTiers` 从 `[MAIN, NEAR]` 改成 `[NEAR, NEAR]`。
2. **字重**：40px 的**粗体中文字视觉上比常规字大一号**（笔画更粗 → 看着更大）。
   光把字号调成一样还是「上下不一样大」，所以当前句**不加粗**了。
   这一项做成 `LyricLayout.boldCurrent` 字段 —— 绘制代码原来写死
   「当前行 = 粗体」，现在改成看布局。
3. **颜色**：当前句靠**青色逐字擦除**（已唱到的部分是青色）+
   它在**上面**那一行认出来；下面那行是暗灰。

**三行布局没动**（40 / 56 / 40，当前句加粗）。

### 落盘键没变

`scene_lyric_layout`  还是 `three` / `ktv` —— 升级后**不用重设设置**，
切到 KTV 两行就是新效果。

### 像素级断言

| 布局 | 实测行带 | 断言 |
|---|---|---|
| KTV 两行 | `[440..477, 519..556]` | 正好 2 条；当前句在上面；**两行一样高**（37 / 37，完全相等）；中间空档 42px；块中心 498 ≈ 500 |
| 三行 | `[405..442, 469..524, 554..591]` | 不受影响：3 条；当前句 55 / 衬句 37 |

### 单测

- `SceneLyricWindowTest` 新增 2 条：
  「KTV 两行不加粗、三行当前句加粗」、「KTV 两行用同一个字号档且都是近档」。
- 原「两种布局的当前句都是最大字号」那条**不变式改了**：
  从「必须**大于**其它行」改成「**不小于**其它行」——
  KTV 两行就是要一样大，原来那条断言跟用户要求是冲突的。
- `LyricsPreviewRenderTest` / `LyricScalePreviewRenderTest` 的 KTV 用例
  从「当前句比下一句大」改成「**两行一样高**」。
- 全量 **1610 个单测全绿**（上一轮 1608 → 本轮 1610）。

### 模拟器实机验证

`emulator-5556`（Android 10 / SDK 29 / arm64-v8a，`wm size 1920x968` 对齐车机），
探针 `com.cardesk.mediaprobe` 注入《怎么我睡不着你也不来救我》：

| 截图 | 内容 |
|---|---|
| `emu-verify/y30/y31-lyric-ktv-samesize.png` | **KTV 两行：两句完全一样大、一样字重**（上行青色逐字擦除 = 当前句，下行暗灰 = 下一句） |
| `emu-verify/y30/y31-lyric-three-4216.png` | **三行不受影响**：上一句 40 / 当前句 56 粗 / 下一句 40 |
| `emu-verify/y30/y31-settings-layout-ktv-samesize.png` | 设置页：chip 选 KTV 两行、说明「当前句在上、下一句在下，两句一样大」、预览条两行一样大 |

产物：`dist/CarDesk-platform-ecarx-cardesk-4216.apk`
（4216 / 1.0.1120-system，平台证书 SHA-256 `c8a2e9bc…92ab8`，
APK SHA-256 `719bebb2d182ebd51598aacc2f34adb86066e10676d8bfcb0b1d0304b27fa6f4`，2217858 字节）。

## KTV 两行改成「错落阶梯」（第 119 轮）

用户看了 4216 的实机图之后，画了一张示意图（红框两个），并说：

> 你看我发你那张图的红框啊，那是我要求的正确的布局方式！
> 具体位置以及字体大小，你按照实际情况修改，我那个图只是示意。

示意图的意思是：**当前句在左上、下一句在右下**，两行**错落成阶梯**，
两句一样大、每一行都从自己的**左边缘**起排（不是居中）。

### 坐标一律以**自己 App 的实测**为准，不以用户截的图为准

> 注：用户那张图是**截我给的图**的，**不是车机原图** ——
> 所以图上的像素**不能当坐标标准**（第120轮踩过一次这个坑，见下面）。
> 用户自己也明说了：**「具体位置以及字体大小，你按照实际情况修改，我那个图只是示意」**。
>
> 下面每个数都来自**自己 App 在设计分辨率 1920×1080 下的实测量**：
> 引导卡下沿 387、底排卡片上沿 764、胎压卡右缘 1453、车况卡左沿 1488。

### 落到实现上的坐标

| | 左边缘 x | 基线 y | 字号 |
|---|---|---|---|
| 当前句（行 0） | **44**（贴桌面左边距，跟导航引导卡同一条竖线） | **552** | 40px 不加粗 |
| 下一句（行 1） | **776**（往右下错开） | **656** | 40px 不加粗 |

- 当前句的字顶（≈512）在引导卡下沿 387 **之下**（不压卡片）；
- 下一句的字底（≈668）在底部卡片区上沿 764 **之上**（不压卡片）；
- 下一句的可占宽收到**车况卡左沿 1488**（`LYRIC_SAFE_RIGHT`）为止：
  `min(960, 1488 - 776 - 24) = 688` → 17 个汉字，长句不会钻到卡片底下。

### 新增了一个布局维度：**每行锚点**

原来的排版只有一条路：按字号算相对基线，再把整块平移到中心（`LYRIC_BLOCK_CENTER_Y`）。
但错落阶梯的每行位置是**定死的**，算不出来 —— 所以给布局加了「每行锚点」：

```kotlin
data class LyricAnchor(val leftX: Float, val baselineY: Float)

enum class LyricLayout(
    ...
    val anchors: Array<LyricAnchor>? = null,   // null = 整块居中；非 null = 每行定死
)
```

- `LyricLayout.KTV` 带两个锚点，`LyricLayout.THREE` 不带（`anchors = null`）；
- `LyricBlockLayout.anchoredBaselines(anchors)` 给锚定布局出基线，
  `baselines + centerShift` 给居中布局出基线 —— **两条路并存**；
- 锚定布局下 `gapScale` 不参与计算（行距已经被两个锚点之间的距离定死了）。

### 擦除高亮也得跟着改成左对齐

当前句的青色逐字擦除是从**文字左边缘**画到擦除位置的。
之前它是「居中绘制」，所以锚定布局下必须给 `LyricWipeText.draw()` 加一个 `leftX`：
给了就从那个 x 起排（否则整块居中画到屏幕中间去了）。

### 设置页的预览条走**同一份锚点**

预览条如果还按「整块居中」画，用户在设置里看到的就跟桌面对不上 —— 白调滑杆。

锚定布局下预览条的做法是：**把整块等比缩小**后放进预览条
（桌面上的横向跨度是 x=44~1464，预览条只有 960 宽，1:1 画不下）：

- 位置、字号、截断宽度都乘同一个系数 → 「错落」的比例关系在预览里是真的；
- 包围盒左右用**标称**最大宽度（不是实际文字宽度）——
  否则换一首歌歌词长短一变，预览的缩放就跟着变，字号滑杆会「看着忽大忽小」。

### 像素级断言

| 布局 | 实测 | 断言 |
|---|---|---|
| 桌面 KTV 两行 | 行带 `[518..555, 622..659]`，左边缘 **46 / 778** | 正好 2 条；当前句在上面；两行墨高都是 **37**（完全相等）；当前句左缘贴 44±6；下一句左缘 776±6；下一句右缘不越过 1488 |
| 预览条 KTV 两行 | 行带 `[167..192, 235..260]`，左边缘 **19 / 495** | 正好 2 条；两行一样高；当前句从左边起排（≤40）；下一句往右错开（>+200）；不超出条子右边缘 |
| 桌面三行 | 行带 `[405..442, 469..524, 554..591]` | 不受影响：3 条；当前句 55 / 衬句 37 |

### 单测

- `LyricBlockLayoutTest` 新增 4 条：
  「锚定基线就是锚点里写死的那个 y」、「锚定基线不依赖字号和行距」、
  「居中布局的一行用固定的最大宽度」、「锚定布局的一行到安全线为止」。
- `SceneLyricWindowTest` 新增 5 条：
  锚点值（44 / 776）、当前句在上面、下一句往右下错开、
  不压引导卡也不压底部卡片区、下一句不钻到车况卡底下；三行仍然不带锚点。
- `LyricsPreviewRenderTest` 的 KTV 用例：扫描范围从 `x 300..1440` 改成 `x 44..1488`
  （要覆盖整个错落块），「整块居中」那条断言换成「左边缘」断言。
- `LyricScalePreviewRenderTest` 新增「预览条里的 KTV 两行是错落阶梯」。
- 全量 **1620 个单测全绿**（上一轮 1610 → 本轮 1620）。

### 落盘键没变

`scene_lyric_layout` 还是 `three` / `ktv` —— 升级后不用重设设置，
切到 KTV 两行就是新效果。

### 模拟器实机验证

`emulator-5556`（Android 10 / SDK 29 / arm64-v8a），屏幕改成 **1920×1080**
（= 设计分辨率，设计坐标 = 屏幕坐标，量出来的数就是设计数），
探针 `com.cardesk.mediaprobe` 注入《怎么我睡不着你也不来救我》：

| 截图 | 内容 |
|---|---|
| `emu-verify/y32/y32-lyric-ktv-stagger.png` | **KTV 两行错落阶梯**：当前句（青色逐字擦除）贴左边在左上、下一句在右下 |
| `emu-verify/y32/y32-settings-lyric.png` | 设置页：chip 选 KTV 两行、说明「当前句在左上、下一句在右下错开，两句一样大」、**预览条也画成错落的** |
| `emu-verify/y32/y32-lyric-three-regression.png` | **三行不受影响**：上一句 40 / 当前句 56 粗 / 下一句 40，整块居中 |

实测量测（1920×1080 屏幕，设计坐标）：

| | 左缘 | 墨迹行带 | 锚点 |
|---|---|---|---|
| 当前句 | 44（左对齐，左边距） | `518..555` | 44 / 552 |
| 下一句 | **778**（右移 732px） | `622..659` | 776 / 656 |

下一句墨迹高 38、当前句 38 —— **两句一样大**（第 118 轮的要求没被破坏）。

产物：`dist/CarDesk-platform-ecarx-cardesk-4217.apk`
（4217 / 1.0.1121-system，平台证书 SHA-256 `c8a2e9bc…92ab8`，
APK SHA-256 `f2c922c75ae458be0e041a71c28a30e8b4a230f4bc993f4b0655fd21eef0e489`，2221426 字节）。

## KTV 两行整体左移 + 区域尾部对齐胎压卡（第 120 轮）

用户看完 4217 的实机图之后提的：

> 两行模式下，**整体左移一点**，**下面一句的区域尾部对齐胎压卡片的右缘**。

### 先量清楚「胎压卡片的右缘」在哪儿

从实机截图上量（1920×1080，设计坐标）：

| | x |
|---|---|
| 胎压卡（底排第 4 张）的右边框 | **1453** |
| 行程卡（底排最右那张）的左沿 | 1488 |
| 车况卡（右上那张）的左沿 | 1488 |

所以「对齐胎压卡右缘」= 把歌词区的右边界从 **1488 收到 1453**。
原来收到 1488 的话，歌词区跟最右那一列卡片**齐平**，看着像要撞上去。

### 「整体左移」挪到多少：这是用户直接定的值

当前句原来在 x=44（跟导航引导卡、底排卡片同一条竖线）。
「整体左移一点」这句话本身没有给数值，所以做了个问卷，
给了三个带具体坐标的选项让用户选 —— 用户选的是：

> **两条都挪：当前句 x=10、下一句 x=742，尾部收到 1453**

所以 x=10 / 742 这两个数是**用户指定**的，不是猜的。
当前句因此比引导卡（x=44）、底排卡片（x=44）更靠左一条竖线 ——
这是有意的：歌词是桌面里唯一贴着左缘走的元素。

> **这一轮踩过的坑（记下来别再犯）**：
> 当时把用户那张示意图当成了「车机原图」，反推出「车机屏是 1920×941、
> 桌面内容左右各内缩 123px」，还据此写了 README 和代码注释。
> 用户后来纠正：**那张图是他截的我给的图，只是示意，不能当坐标标准**。
> 车机屏的真实分辨率仍然未知 —— 但这不影响实现：
> 桌面整层是按 `Design.scaleFor` **等比缩放**的，
> 代码里存的永远是**设计坐标系（1920×1080）**的值，
> 屏幕多大都不影响这里的坐标。

### 改了什么

| | 4217（第119轮） | 4218（本轮） |
|---|---|---|
| 当前句左缘 | 44 | **10** |
| 下一句左缘 | 776 | **742** |
| 区域尾部（安全线 `LYRIC_SAFE_RIGHT`） | 1488 | **1453** |

两句一起左移 34px，行距不变，错落关系不变。
**三行（居中块）布局完全不受影响**（安全线只参与锚定布局的宽度计算）。

### 像素级断言

| 布局 | 实测 | 断言 |
|---|---|---|
| 桌面 KTV 两行 | 行带 `[518..555, 622..659]`，左缘 **12 / 744**（离屏渲染，单位 = 设计 px） | 正好 2 条；当前句在上；两行墨高都是 37；当前句贴左缘 10±6；下一句 742±6；区域尾部 ≤ 1453 |
| 预览条 KTV 两行 | 行带 `[167..192, 235..260]`，左缘 **19 / 496** | 正好 2 条；两行一样高；当前句从左边起排；下一句往右错开 |
| 桌面三行 | 行带 `[405..442, 469..524, 554..591]` | 不受影响：3 条；当前句 55 / 衬句 37 |

> 上一轮（第119轮）记的实测值是左缘 46 / 778 —— 那是锚点 44 / 776 时的数，
> 本轮按用户要求左移后变成了 12 / 744。

**注意**：`LyricsPreviewRenderTest` 里 KTV 用例的扫描窗口也从 `x 44..1488`
放宽到 `x 0..1453` —— 窗口从 44 开始的话，就看不到当前句贴到 x=10 的那半截了
（这条断言一开始就是这么红的：实测左边缘 44，窗口把它切掉了）。

全量 **1620 个单测全绿**（本轮没新增用例，改的是已有断言的期望值）。

### 关于「车机屏是多大」：仍然未知，别再猜

第 116~118 轮的 README 写过「`wm size 1920x968` 对齐车机分辨率」，
第 120 轮又误判成 1920×941 —— **两次都是猜的**。
真机屏到底多大，目前没有可靠来源。

**但这不影响实现**：桌面整层内容按 `Design.scaleFor` 等比缩放
（`min(屏宽/1920, 屏高/1080)`，居中），代码里存的坐标全是**设计坐标系**的值。
所以取证一律在 **1920×1080（= 设计分辨率）** 下做 ——
设计坐标 = 屏幕坐标，量出来的数就是设计数，不用任何换算，
也不会被「屏幕多大」带偏。

`tools/emu_lyric_layout.sh` 加了 `CARDESK_WM_SIZE` / `CARDESK_OUT_DIR` 两个开关，
换分辨率取证时用它，别手改。

### 模拟器实机验证（1920×1080 = 设计分辨率）

`emulator-5556`（Android 10 / SDK 29 / arm64-v8a）屏幕 `wm size reset` 成
**1920×1080**（设计坐标 = 屏幕坐标，量出来的数就是设计数），装 4218：

| 截图 | 内容 |
|---|---|
| `emu-verify/y33/y33-lyric-ktv-1080.png` | **KTV 两行左移后的实机图**：当前句（青色逐字擦除）贴着左缘在左上，下一句往右下错开 |

按颜色抠出两行文字的实际墨迹框：

| | 墨迹 x | 墨迹 y | 锚点 |
|---|---|---|---|
| 当前句（青色） | **13** .. 526 | `518..555` | 10 / 552 |
| 下一句（亮白） | **744** .. 978 | `622..659` | 742 / 656 |

左缘 13 / 744 = 锚点 10 / 742 + 2~3px 的字形左边距；墨迹高都是 37 ——
**两句一样大**（第 118 轮的要求没被破坏）。

产物：`dist/CarDesk-platform-ecarx-cardesk-4218.apk`
（4218 / 1.0.1122-system，平台证书 SHA-256 `c8a2e9bc…92ab8`，
APK SHA-256 `2eac45a4c6e36495541e3a422837f4631706d8628496ab9cfc02d689c386e339`，2221434 字节）。

## KTV 两行整体往右挪回来（第 121 轮）

用户看完 4218 的实机图之后提的：

> **太靠左了，移到右边去**。

4218 那版（第120轮）当前句贴到了 **x=10** —— 那是跟着「整体左移」一路贴到内容左缘的，
视觉上跟桌面其它元素脱开了（引导卡 x=44、底排卡片 x=44）。

这一轮用户先选「整组回到 4217 的位置」，随后又指定当前句落在 **x=100**，
所以最终值是：

| | 4217（第119轮） | 4218（第120轮） | 4219（本轮） |
|---|---|---|---|
| 当前句左缘 | 44 | 10 | **100** |
| 下一句左缘 | 776 | 742 | **776** |
| 区域尾部 `LYRIC_SAFE_RIGHT` | 1488 | 1453 | **1453**（不变） |

- 当前句 **x=100**（用户指定）：比内容左边距 44 往右一点，
  不再贴着屏幕边，也不跟下一句挤在一起；
- 下一句 **x=776**：错落距离回到第 119 轮的值；
- **区域尾部保持 1453**（底排胎压卡的右缘）——
  「下面一句的区域尾部对齐胎压卡片的右缘」这条要求继续生效，用户没说要动它；
- 三行（居中块）布局不受影响。

### 像素级断言

| 布局 | 实测 | 断言 |
|---|---|---|
| 桌面 KTV 两行 | 行带 `[518..555, 622..659]`，左缘 **102 / 778** | 正好 2 条；当前句在上；两行墨高都是 37；当前句 100±6；下一句 776±6；区域尾部 ≤ 1453 |
| 预览条 KTV 两行 | 行带 `[163..190, 236..263]`，左缘 **19 / 489** | 正好 2 条；两行一样高；当前句从左边起排；下一句往右错开；不超出条子右边缘 |
| 桌面三行 | 行带 `[405..442, 469..524, 554..591]` | 不受影响：3 条；当前句 55 / 衬句 37 |

全量 **1620 个单测全绿**（本轮没新增用例，改的是已有断言的期望值）。

### 模拟器实机验证（1920×1080 = 设计分辨率）

`emulator-5556` 屏幕 `wm size reset` 成 **1920×1080**（设计坐标 = 屏幕坐标），装 4220：

| 截图 | 内容 |
|---|---|
| `emu-verify/y34/y34-lyric-ktv-1080.png` | **KTV 两行最终位置**：当前句（青色）在左上、x=103 起；下一句在右下、x=778 起 |

按颜色抠出两行文字的实际墨迹框：

| | 墨迹 x | 墨迹 y | 锚点 |
|---|---|---|---|
| 当前句（青色） | **103** .. 616 | `518..555` | 100 / 552 |
| 下一句（亮白） | **778** .. 1012 | `622..659` | 776 / 656 |

> 版本号说明：这一轮中途取消过一次构建（常量又改了一次），
> 版本号被 bump 了两次，所以产物是 **4220 / 1.0.1124-system**（4219 被那次取消的构建占掉了）。
> 没有任何一版被发布出去，序号跳一位不影响升级。

产物：`dist/CarDesk-platform-ecarx-cardesk-4220.apk`
（4220 / 1.0.1124-system，平台证书 SHA-256 `c8a2e9bc…92ab8`，
APK SHA-256 `1e9c5d06a378c2ca15d3daadcdad3ef9db40b02eeffd7c986c6dc404c78ae967`，2221386 字节）。

## KTV 当前句再往右挪到 x=200（第 122 轮）

第 121 轮把当前句挪到 x=100 之后，用户又指定：

> 改成 x=200

所以这一轮只动当前句：

| | 4218（第120轮） | 4220（第121轮） | 4221（本轮） |
|---|---|---|---|
| 当前句左缘 | 10 | 100 | **200** |
| 下一句左缘 | 742 | 776 | **776**（不动） |
| 区域尾部 `LYRIC_SAFE_RIGHT` | 1453 | 1453 | **1453**（不动） |

当前句跟下一句错开 **576px**（≈14 个汉字）—— 阶梯关系一眼能看出来，
又不再贴着屏幕左边。

### 一条断言跟着改了

`SceneLyricWindowTest` 里「下一句应该错开足够远」那条，
阈值原本是 **600**（当初按 44 / 776 的 732 定的）；
现在 200 / 776 只有 576，断言红了 —— 阈值改成 **500**，
并在注释里写明 576 是**用户指定坐标**算出来的差值。

> 改阈值不是为了让用例变绿：576px 的错开仍然一眼看得出是阶梯，
> 600 那条本来就是我自己拍的数，不是从某个规格推出来的。

### 像素级断言

| 布局 | 实测 | 断言 |
|---|---|---|
| 桌面 KTV 两行 | 行带 `[518..555, 622..659]`，左缘 **202 / 778** | 正好 2 条；当前句在上；两行墨高都是 37；当前句 200±6；下一句 776±6；错开 ≥500；区域尾部 ≤ 1453 |
| 预览条 KTV 两行 | 行带 `[159..189, 239..268]`，左缘 **19 / 452** | 正好 2 条；两行一样高；当前句从左边起排；下一句往右错开 |
| 桌面三行 | 行带 `[405..442, 469..524, 554..591]` | 不受影响：3 条；当前句 55 / 衬句 37 |

全量 **1620 个单测全绿**（本轮没新增用例）。

### 模拟器实机验证（1920×1080 = 设计分辨率）

`emulator-5556` 屏幕 `wm size reset` 成 **1920×1080**（设计坐标 = 屏幕坐标），装 4221：

| 截图 | 内容 |
|---|---|
| `emu-verify/y35/y35-lyric-ktv-1080.png` | **KTV 两行最终位置**：当前句（青色）x=203 起、下一句 x=778 起，两句在 x 上**不重叠**（当前句右缘 699 < 下一句左缘 778） |

按颜色抠出两行文字的实际墨迹框：

| | 墨迹 x | 墨迹 y | 锚点 |
|---|---|---|---|
| 当前句（青色） | **203** .. 699 | `518..555` | 200 / 552 |
| 下一句（亮白） | **778** .. 1012 | `622..659` | 776 / 656 |

产物：`dist/CarDesk-platform-ecarx-cardesk-4221.apk`
（4221 / 1.0.1125-system，平台证书 SHA-256 `c8a2e9bc…92ab8`，
APK SHA-256 `9712c67a53947673f278199c248d4e11c1e7b8e123249dddbecb7b6ee2c8b3c6`，2221382 字节）。

## 左上角设置入口：字母「C」→ 齿轮图标（第 123 轮）

用户原话：

> 把左上角的C字设置入口改成一个设置图标

状态栏左上角那一格原来是 `canvas.drawText("C", ...)` 画的字母 **C**，
现在换成 **设置图标（齿轮）**。

### 改了什么、没改什么

| | 改前 | 改后 |
|---|---|---|
| 图形 | 字母「C」（字号 21px，粗体） | **齿轮**：8 齿 + 齿轮身 + 中心孔（Canvas 现画） |
| 齿尖半径 | — | **11.5px**（外径 23px） |
| 齿根半径 | — | **8px** |
| 中心孔半径 | — | **3.8px**（`Path.FillType.EVEN_ODD` 挖空） |
| 墨色 | — | `Skin.ACCENT_INK`（`0xFF0B1413`） |
| 强调色圆角方块底板 | 保留 | **保留（不变）** |
| 点击命中区 `avatarRect` | 40×40 | **40×40（不变）** |
| 点击行为（打开设置） | 打开设置 | **打开设置（不变）** |

> 只换「格子里的图形」，底板和命中区一个像素都没动 ——
> 用户说的是「把 C 改成设置图标」，不是「把这一格改掉」。

### 为什么用 Canvas 现画，不用 drawable 资源

状态栏这一行的其它图标（蓝牙 / WiFi / 信号 / 燃油 / 时钟）**全是 Canvas 画的**。
齿轮再单独引一套矢量资源，风格和缩放口径就多一个例外 —— 换个屏幕比例时两套口径容易对不上。
所以齿轮也走 `Path` + `Paint`，跟邻居保持一致。

### 中心孔为什么用 EVEN_ODD

齿轮中心的孔是**真的透空**，不是拿底色涂出来的。
这样无论这格落在什么背景上（强调色方块 / 壁纸 / 夜景），孔都是背景色，
不会出现「换个壁纸就多一个深色圆点」。

### 像素级断言

新文件 `app/src/test/java/com/cardesk/launcher/ui/StatusBarSettingsGearTest.kt`（2 条用例），
断言的是**形状**，不是「有没有东西」—— 只看「方块里有没有墨」跟旧的字母 C 分不开：

| 断言 | 采样 | 判据 |
|---|---|---|
| 齿轮身是一整圈 | 以中心为圆心、半径 **6.5** 的圆上 **36** 个点 | 命中 ≥ **32**（字母 C 的**右边是开口**，会缺一大片） |
| 8 个齿尖 | 半径 **10.5** 的圆上、8 个 **45°** 方向 | 命中 ≥ **6**（字母 C 根本没这么远的东西） |
| 中心孔是空的 | 正中心一点 | **不是**墨（透出方块底色） |
| 方块底板没变 | 方块内左上和右下各一点 | 都是**强调色** |
| 方块外没墨 | 方块右边缘外 3px | **不是**墨 |

**反向验证过**：把绘制临时改回字母 `C`，第一条断言立刻变红
（`齿轮身应该是一整圈：36 个采样点里只命中 27 个`），确认这断言真的在管形状，
不是恒真。随后源码已还原成齿轮实现。

> 踩过的坑：① 透明像素（alpha=0）会被「离墨色更近」误判成墨，所以 `isInk` / `isAccent`
> 都先判 alpha；② 圆角 13px 把方块四角切掉了，取样不能贴角，取角内侧 8px。

### 模拟器实机验证（1920×1080 = 设计分辨率）

`emulator-5556` 装 4222 后的真实截图 `emu-verify/y36/y36-desktop-gear-1080.png`，
把**同样那三条像素断言**在真机图上再跑一遍（而不是只在 Robolectric 里过）：

| 断言 | Robolectric | **真机实测** | 结论 |
|---|---|---|---|
| 齿轮身一整圈（r=6.5，36 点） | ≥32 | **36 / 36** | PASS |
| 8 个齿尖（r=10.5，8 方向） | ≥6 | **7 / 8** | PASS |
| 中心孔是空的 | 非墨 | **非墨** | PASS |
| 方块外没墨 | 非墨 | **非墨**（底板色 `(51,198,180)` 仍在） | PASS |

按半径扫一圈看**墨占比**（严格墨色阈值），把设计几何一条条对上：

| 半径 | 墨占比 | 对应设计 |
|---|---|---|
| 3.0 | **0 / 180** | 中心孔半径 3.8 → 孔里没有墨 ✓ |
| 4.0 | 65 / 180 | 孔边缘的抗锯齿 |
| 6.0 / 6.5 | **180 / 180** | 齿根 8 之前的实心带 → 整圈都是墨 ✓ |
| 8.0 | 108 / 180 | 齿间缺口开始出现 |
| 10.5 | 45 / 180 | 只有 8 个齿 |
| 11.5 | 10 / 180 | 齿尖边缘 |
| 12.0 | **0 / 180** | 齿尖半径 11.5 → 再往外没墨 ✓ |

墨迹包围盒 **x 38..59 / y 28..48**（22×21，严格阈值下不含抗锯齿边），
中心 **(48.5, 38.0)** —— 与设计的方块中心 (49, 38.5) 一致。
沿 8 个齿方向实测最远墨点 r = **10.5 ~ 11.5**，与齿尖半径 11.5 吻合。

**功能没丢**：点这个齿轮（49, 38）照旧打开「桌面设置」页
（`emu-verify/y36/y36-settings-open-after-gear.png`）—— 命中区 40×40 一个像素没动。

### 产物

全量 **1622 个单测全绿**（1620 + 本轮 2 条新用例）。
产物：`dist/CarDesk-platform-ecarx-cardesk-4222.apk`（4222 / 1.0.1126-system，
平台证书 SHA-256 `c8a2e9bc…92ab8`，
APK SHA-256 `2725cbf9f5a2f615b4d5705cb8a71b9973c91003eefee82c413546ef0a696f3a`，2222082 字节）。

## 设置页主体外的留白改成透出桌面（第 124 轮）

用户原话：

> 设置页主体外的留白不会透到桌面吗？把它改成透到桌面

### 原来为什么不透

第五十轮起，浮层背景板是 `.bgwash` + `.scrim` 两层**不透明**色块，
照设计板 `s05-settings` 做的「独立模态屏」—— 遮罩下面是干净的背景，
不是被压暗的桌面。代价就是**设置页面板之外的那圈留白是一整块死色**，
底下的壁纸、卡片、歌词全被挡死。

### 现在怎么透

`OverlayBackdrop` 加一个 `seeThrough` 开关，打开后把两层都改成半透明：

| 层 | 原来 | 透出模式 |
|---|---|---|
| `.bgwash` 底色 | `0xFF…`（不透明） | alpha **0x4D**（≈30%） |
| `.scrim` 遮罩 | 夜间 `0xA8` / 日间 `0x61` | **缩一半**（夜间 → `0x54`） |

桌面透出率 = `(1 - bgAlpha) × (1 - scrimAlpha)`：

| | 算出来的透出率 |
|---|---|
| 夜间 | **47%** |
| 日间 | **57%** |

> `.scrim` 必须一起减弱：背景板透明之后它的作用就变成了「压暗桌面」，
> 不减弱的话桌面透上来了也还是被压得死黑，等于没透。

**面板主体不受影响**：那张卡是 `Skin.flat(Shape.CARD)` → `palette.surface`，
**不透明**，里面的字照旧清楚。天然形成「主体内实、主体外透」。

> 改动只在背景板这两层的 alpha —— **不动布局、不动面板、不动动画**。

### 只改设置页

`seeThrough` 默认 `false`，其它浮层（空调 / 应用抽屉 / 最近任务 / 槽位 / 各类弹窗）
维持第五十轮那套不透明背景板，一个都没动。

### 怎么验「桌面透过来了」

断言「某个像素等于桌面上的某个色值」没意义 —— 桌面背景本身就有渐变。
这里换了个更硬的办法：**在背景板底下垫一块纯红**，再看红色有没有透上来。

| 用例 | 判据 |
|---|---|
| 透出模式下，桌面能透过来 | 底部纯红的**红色占比 > 0.7** |
| 不透明模式下，桌面完全看不见 | 红分量 < 40 且 ≤ 绿分量 |
| 面板主体是不透明的，桌面透不过去 | 面板区域红分量 < 60 且 ≤ 绿分量 |
| 设置页的背景板确实开了透出 | 反射读 `scrimView` → `seeThrough == true` |
| 透出率算得出来 | `OverlaySpec.throughRate()`（纯 Kotlin） |

**两轮反向验证**：

1. 把设置页的 `seeThrough` 改成 `false` → 「设置页的背景板确实开了透出」变红；
2. 把 `OverlayBackdrop` 里的 `seeThrough` 分支**反过来** → 「透出模式下桌面能透过来」
   和「不透明模式下桌面完全看不见」**两条都变红**
   （实测像素 `rgb=(12,13,18)` —— 跟设计值 `0x0F1116` 对得上）。

> 判据用「红色占比」而不是「某个绝对色值」，是因为桌面壁纸是渐变 + 素材，
> 绝对色值会随壁纸变；**红色占比**只依赖「底下那块红透过来多少」这一件事。

### 模拟器实机验证（1920×1080 = 设计分辨率）

装 4223 后在 `emulator-5556` 上实测，**日间 / 夜间各一套**：

| 截图 | 内容 |
|---|---|
| `emu-verify/y37/y37-settings-seethrough-night.png` | **夜间**：面板外透出山峦 / 续航卡 / 车况卡 / 天气 / 状态栏 |
| `emu-verify/y37/y37-settings-seethrough-day.png` | **日间**：同上，浅色壁纸与白卡片 |
| `emu-verify/y37/y37-desktop-before-settings.png` | 打开设置页之前的桌面（对比用） |

**留白区的像素对比**（4222 不透明 vs 4223 透出，同一批取样点）：

| 位置 | 4222（不透明） | 4223（透出） |
|---|---|---|
| 左侧留白·山峦 | `(10,12,16)` 死板背景色 | `(40,33,48)` 壁纸紫褐 |
| 左侧留白·续航卡 | `(10,12,16)` | `(20,23,29)` 卡片面 |
| 右侧留白·天气 | `(11,13,17)` | `(28,24,42)` 壁纸 |
| 右侧留白·车况卡 | `(10,12,16)` | `(20,23,29)` 卡片面 |
| 顶部留白·状态栏 | `(13,16,21)` | `(18,21,27)` 状态栏本体 |
| **面板主体内** | `(31,35,45)` | `(31,35,45)` **不变** |

> 面板主体内外两栏取值完全一样 —— 主体那张卡确实还是不透明的，
> 只有留白变透了。

日间同理：留白处 `(171,167,173)`（浅色壁纸）/ `(205,207,210)`（卡片面），
面板主体 `(255,255,255)` 纯白。

### 踩过的坑：模拟器上 `pkill -9` 杀不掉 CarDesk

CarDesk 在模拟器里是 **system 用户**的进程，`adb shell "pkill -9 com.ecarx.cardesk"`
**看着执行了、其实没杀掉**（`pidof` 的 pid 一直没变）。
害我把 `shared_prefs` 推回原文件后，进程还活着、用内存里的旧值又把文件覆盖回去了，
「还原环境」折腾了好几轮。

正确做法是**先拿 pid 再按 pid 杀**：

```bash
PID=$(adb -s emulator-5556 shell "pidof com.ecarx.cardesk" | tr -d '\r\n')
adb -s emulator-5556 shell "kill -9 $PID"
```

> 推 `shared_prefs` 前**必须**确认进程真的死了 ——
> `SharedPreferences` 有进程内缓存，进程活着的时候推文件等于白推。

### 产物

全量 **1627 个单测全绿**（1622 + 本轮 5 条新用例）。
产物：`dist/CarDesk-platform-ecarx-cardesk-4223.apk`（4223 / 1.0.1127-system，
平台证书 SHA-256 `c8a2e9bc…92ab8`，
APK SHA-256 `d64931670507465d606da1eb46f7a74e77d9d2e21613386c6df46741382480b7`，2222518 字节）。

## 默认媒体源可设 + 音乐卡片媒体源加图标（第 125 轮）

### 1. 默认媒体源改成可选

原来设置页「系统诊断」里有个**只读**的「媒体来源（固定 9 个）」列表 —— 只显示每个来源
装没装，不能选。现在在它上面加了**「默认媒体源」**一栏。

| | 原来 | 现在 |
|---|---|---|
| 设置页 | 只读列表 | **横排 chip，9 个来源可点选** |
| 桌面启动 | 不锁包，跟随系统当前会话 | **自动锁定设置里指定的来源** |
| 卡片上切换 | 点来源行能切 | 一样能切（**临时**的，下次启动回默认源） |
| 启动时会不会弹音乐 App | 不会 | **也不会**（只锁定，不拉起） |

#### 「默认」和「当前」是两个值

| | 存哪 | 谁改 | 含义 |
|---|---|---|---|
| `prefs.defaultMusicSource` | `default_music_source_v1` | 设置页 | **默认**来源，桌面启动时用 |
| `prefs.musicSource` | `design_music_source_v1` | 卡片上点来源 | **当前生效**的来源 |

> `defaultMusicSource` 的默认值是 **「通用音乐」** —— 也就是**原来的行为**
> （不锁包、跟随当前会话）。所以用户没在设置页选过时，
> 桌面启动一个字节都不变；只有显式选了才开始自动锁定。

#### 降级链

| 情况 | 结果 |
|---|---|
| 默认源 = 通用音乐 | 不锁包，跟随当前会话 |
| 默认源已安装 | 锁定对应包（`MusicSessionTracker.lockToPackage`） |
| 默认源**没装** | 降级：不锁包，跟随当前会话（不崩、不锁一个不存在的包） |
| 默认源 id 不认识 | 同上 |

纯逻辑在 [MediaSources.resolveDefaultPackage]（纯 Kotlin，可 JVM 单测）。

### 2. 音乐卡片上的媒体源显示优化 + 加图标

原来来源行是「**品牌色小圆点 + 文字 + 下拉箭头**」—— 看得出是哪家，认不出是哪个 App。
现在换成真正的图标：

| 来源 | 图标怎么来 |
|---|---|
| QQ音乐 / 网易云 / 酷狗 / 酷我 / 汽水 / 多媒体 | **对应 App 的真实图标**（`PackageManager.getApplicationIcon`） |
| 蓝牙音乐 | Canvas 自绘**蓝牙符号** |
| USB 音乐 | Canvas 自绘**USB 符号** |
| 通用音乐 | Canvas 自绘**音符** |

> 用户选的是「真实 App 图标优先，取不到时用 Canvas 自绘符号」——
> 蓝牙 / USB / 通用音乐**本来就没有对应的 App**，硬凑别人的图标会张冠李戴。

#### 符号形状是**调**出来的，不是拍出来的

音符这个符号改了两轮，每一轮都是**看着实机截图**才发现不对的：

| 版本 | 画法 | 实机观感 |
|---|---|---|
| 第一版 | 两个三次贝塞尔画符头 | 画出来是个**向下凸的「碗」**，根本认不出是音符 |
| 第二版 | 旋转 -20° 的椭圆（长轴 0.96r / 短轴 0.48r） | 旋转对了，但**太扁**，16px 下像根「」 |
| 第三版（现在） | 短轴加到 **0.64r**（接近正圆） | 一眼就是音符 ✓ |

USB 符号也调了一轮：方块比主干粗一点（不然整体「上细下粗」）、主干线宽不低于
0.2r（再细 16px 下就断了）。

> 这类细节**只能靠实机截图 + 放大看**——单元测试断言的是「特征部位有没有墨」，
> 断不出「像不像一个音符」。

#### 版式没漂

图标与原来那个圆点**同中心**（`x+22k` / `srcY+12k`），大小 16×16（原来圆点直径 8），
**文字起点仍是 `x+34k`** —— 只是把那个小圆点换成了图标，文字和箭头一个像素没动。

#### 性能

取 App 图标要读 APK 资源 → **后台线程**取，主线程只读缓存
（`MediaSourceIcons` 按「来源 + 尺寸」缓存，同一次运行只取一次）。
桌面每秒刷新不会卡。

> 缓存用的是 `ConcurrentHashMap` 而不是 `HashMap`：这张表是**后台线程写**
> （`load`）、**主线程读**（`cached`，桌面每秒刷新都调一次），
> 普通 `HashMap` 一边 resize 一边读，理论上会走进死循环 / 丢更新。

### 3. 验证

全量 **1643 个单测全绿**（1627 + 本轮 16 条）。

**像素级断言**（`MediaSourceIconRenderTest`）钉的是**每个符号自己的特征部位**，
不是「区域里有墨」：

| 符号 | 钉住的位置 |
|---|---|
| 音符 | 右边有符干、下面有符头、**左上方没有墨** |
| 蓝牙 | 右上折角有墨、左侧竖线有墨、**左边整片没墨** |
| USB | 顶部方块有墨、左下圆点有墨、支线有墨 |

> 踩过的坑：音乐卡的**卡片底色本身就不透明**，
> 用「alpha > 0」判墨的话整个卡片都算「画过的」—— 断言恒真，
> 退回圆点也能过。必须按**颜色**判（离品牌色足够近）才分得出
> 「品牌色的图标」和「深色的卡片底」。

**两轮反向验证**：

1. 把来源行退回原来的圆点 → 「音符符干的位置应该有墨」变红；
2. 把设置页的 `prefs.defaultMusicSource = src.id` 去掉 →
   「选了 QQ音乐，默认源就该是 qq」变红（`expected:<qq> but was:<generic>`）。

#### 模拟器实机验证（1920×1080 = 设计分辨率）

`emulator-5556` 装 4224 后的真实截图（`emu-verify/y38/`）：

| 证据图 | 看点 |
|---|---|
| `y38-desktop-icon-1080.png` | 来源行 = **自绘音符**（品牌色 `#EC4899` 实心），一眼能认出是「♪」 |
| `y38-desktop-appicon-1080.png` | 来源行 = **wecarflow 的真实 App 图标**（白底粉音符，16×16 居中 cover 裁剪） |
| `y38-restart-locked-local.png` | 重启桌面后默认源仍是「多媒体」，**没有弹起音乐 App** |
| `y38-home-lock-local.png` | 按 HOME 键启动 → 自动锁定默认源，卡片来源行已是「多媒体」 |

把来源行放大 5 倍逐个看（`emu-verify/y38/` 原图 1920×1080）：

- **自绘音符**：符头在左下、符干在右上 —— 是**完整的一个音符**，不是圆点；
- **真实 App 图标**：方形白底 + 粉色的车机音乐 logo，边缘是 App 图标自己的圆角。

> 真 App 图标统一 **居中 cover 裁剪成方形**：各家 App 图标长宽比不一样
> （有的是圆形、有的是竖条），不裁的话同一个来源在不同系统上大小不一，
> 来源行会跳。

设置页里「默认媒体源」那一行 chip **初始就滚到选中项**（`chipRowCore` 加了
`scroll.post { scroll.scrollTo(...) }`）—— 9 个来源一行排开，
选中「通用音乐」在**第 7 个**，不滚过去的话它正好在屏幕外看不见高亮。

#### 「桌面启动自动锁定默认源」这条链路验过了

这一项之前没做成 —— 模拟器上 HOME 归属是乱的（系统里同时装了 `com.ecarx.cardesk`
和 QQ 版 `com.ecarx.qq.tool`，默认 HOME 被设成了 `ResolverActivity` 选择器）。
把默认 HOME 改回 CarDesk 之后，按 **HOME 键**真启动一次，链路就通了：

```
I/CarDesk/Media: 默认媒体源：已锁定 sourceId=local pkg=com.tencent.wecarflow
D/CarDesk/Media: 会话选择 candidates= locked=com.tencent.wecarflow selected= ...
```

证据：图 `emu-verify/y38/y38-home-lock-local.png`、
日志 `emu-verify/y38/y38-home-lock-logcat.txt`。

启动前 prefs 是 `default_music_source_v1=local` + `design_music_source_v1=generic`，
启动后 **`design_music_source_v1` 自己变成了 `local`** —— 这就是
「当前生效的来源跟着默认源走」的那一步。

**没有拉起音乐 App**：`mResumedActivity` 仍是 `com.ecarx.cardesk/MainActivity`，
`pidof com.tencent.wecarflow` 为空 —— 桌面起来只锁定，不弹音乐。

> 踩过的坑：模拟器上 `com.ecarx.cardesk` 是 **uid 1000（system）** 的更新系统包，
> **不是 `u0_a99`**。改 `shared_prefs` 时 `chown u0_a99:u0_a99` 会**静默失败**，
> 文件变成 root:root 660 → App 读不到 → 启动后 prefs 被它自己重写成一堆空值，
> 看起来就像「功能没生效」。属主要用
> `pm list packages -U com.ecarx.cardesk` 查出来的 uid 来 chown。

### 产物

全量 **1643 个单测全绿**（1627 + 本轮 16 条新用例）。
产物：`dist/CarDesk-platform-ecarx-cardesk-4224.apk`（4224 / 1.0.1128-system，
平台证书 SHA-256 `c8a2e9bc…92ab8`，
APK SHA-256 `111731e1727fdfa86f76c4a6512040ecd87f7a72d2b1dd01715b34b53f778bdd`，2229706 字节）。

## 巡航红绿灯数据源排查（第 107 轮）

4202 的诊断包确认：巡航时高德只发 `12205/10001/10077` 三类广播，**没有任何灯态字段**
（10001 只有 `TRAFFIC_LIGHT_NUM` 数量，不带 `trafficLightStatus`），也没有
`NAVITOOL_CRUISE_TRAFFIC_LIGHTS` 帧。静态核对云端两个高德包（9.1 共存版 / 9.5 新基础版）：
都不含 `CruiseTrafficLightBroadcaster`，只有语音播报开关与标准 60073 协议。

因此：
- 导航态标准 60073 灯态**已支持**（经过路口时出现）；
- 巡航态当前高德**没有广播源**，卡片无从显示；需换装带
  `floatapp.CruiseTrafficLightBroadcaster` 的修改版（UNI 分析的
  `9.5高德-完美自动投屏3D彩虹.apk`，SHA-256 `d86d485b…`）。
- **4204 起接收端两种扩展都兼容**：①UNI 那份的 `NAVITOOL_CRUISE_TRAFFIC_LIGHTS`（`data` JSON）；
  ②社区修改版（`patch-amap-cruise-wrapper`）在 60073 帧里附带的 `lightsData`（CameraLightInfo 数组，
  带 `lightsCount`、`clearLights` 清空信号）。装了会发巡航灯的高德后，巡航卡即可显示多方向灯；
- 已加诊断段「---- 导航数据源 ----」：高德版本、引导帧/巡航帧/灯态帧计数、KEY_TYPE 统计，
  下次诊断包可直接区分「高德没发」还是「我们没解」。

## 导航卡：导航 / 巡航识别（第 100 轮）

- **根因**：以前用高德 `KEY_STATE` 的 8/10 判断「导航中」。本车高德导航时发的是 13–16 / 40 / 50 / 51 等，
  永远没有 8/10，导航帧被当成巡航帧；同时导航结束后的旧帧缓存会挡住巡航卡。
- **现在**：只看引导帧内容——带路线剩余（`ROUTE_REMAIN_DIS/TIME`）的算导航帧，
  否则算巡航帧；导航中 12 秒内夹进来的无剩余帧会被忽略，避免来回跳。
- 导航卡显示距离/道路/车道/剩余/ETA；巡航卡显示当前道路 + 车道 + 多方向红绿灯（最多 3 个方向）。
- 巡航无新帧 20 秒、导航无新帧 12 秒，卡片会自动收起；首页每秒重算，读秒不会冻住。
- 13011 是路况 TMC，不是引导帧；12205 在本车固定无效，直接丢弃。
- **60021 = 当前道路名**（extra `curRoadName`）：巡航时单独广播，用来补准巡航卡道路名；
  它也算一次「巡航心跳」。模拟器上实测高德每秒发一条（城市未识别时名字为空，忽略）。
- 未处理的高德广播（60021/13011 等）会按 10s 限频打一行 `未处理广播内容`（键 + 前 8 个值），
  方便真车继续对字段。

## 巡航红绿灯：从引导帧里提取（第 103 轮）

- **现象**：4195 车机诊断包里巡航时完全没有红绿灯；`CarDesk/NavUI` 只有 12205（无效）、
  10001（`icon=0 seg=0`）、60021（当前道路名），没有 60073、也没有 `NAVITOOL_CRUISE_TRAFFIC_LIGHTS`。
- **定位**：反编译同款高德（`gd95_新基础版_v24t.apk`）：
  * `floatapp.trafficlight.CruiseTrafficLightVoice` / `GuideTrafficLightVoice` 只做**语音播报**，
    数据来自内部的 `CameraLightInfo`，不对外广播；
  * `StandardProtocolKey` 里明确有 `greenLightLastSecond`、`redLightCountDownSeconds`、
    `trafficLightStatus` 三个无协议号的 extra —— 说明高德靠**普通广播的 extras** 传灯态；
  * 全 APK 搜不到 `NAVITOOL_CRUISE_TRAFFIC_LIGHTS` 字面量。
- **修法**：`AmapDesktopGuide.handleGuide()` 开头先调 `maybeStandardLight()` ——
  任何引导帧（10001/10003/12205）只要 extras 里同时有 `trafficLightStatus` 和
  一个读秒字段，就按官方 60073 的口径解析一次（把 KEY_TYPE 临时替换成 60073，
  其余字段原样交给 `AmapTrafficLight.standard()`）；巡航态按 `dir`（1 左 / 4 直 / 2 右）
  累积到 `standardByDir`，巡航卡并排显示多个方向。
- 无效引导帧的日志增加 `keys`（extras 键集合）；引导帧里提取到灯态会记
  `收到红绿灯数据（引导帧(12205)）`，方便真车确认这条链路到底通不通。
- 模拟器验证：注入带灯态的 12205（左红 20 / 右绿 12）→ 巡航卡出现两个方向灯，
  见 `preview/y22-cruise-card.png` 与 `emu-verify/y27/`。
- **风险**：如果车机高德确实不把灯态放进引导帧，则需要下一份诊断包再定位（日志已带 keys）；
  另一种可能是需要在 Amap 的 HUD 设置里打开「巡航红绿灯播报」。

## 音乐播放/暂停状态跳动（第九十七轮）

真机诊断包（4183 / 1.0.1087）里 `CarDesk/Media` 出现：

- `播放进度推送 … playing=false` 247 条 / `歌词当前行 … playing=true`（两个口径打架）；
- `清空媒体状态（回到暂无播放）` 164 次，集中在两段各约 4 分钟里、**每 2.5s 一次**；
- `通知监听服务已连接` 4 次 + `会话监听换用新组件重新注册` 4 次（两个监听组件交替连接）；
- `唤醒上次播放的音乐应用` 10 次（含 `wake-retry-1`），PLAY 键被反复重发。

三处根因与修法：

1. **卡顿被当成暂停**：wecarflow 缓冲时 `PlaybackState=PLAYING` 但 `position` 卡 2.5s，
   老逻辑直接改判「暂停」推给界面 → 播放键图标来回跳。
   现在 `PlaybackUiPolicy.uiPlaying()`：短暂卡顿不算暂停，`PLAYING` 且停滞 **6s 以上**才改判。
2. **会话短暂消失就清屏**：`NULL_SESSION_CLEAR_MS` 600ms → **3000ms**，覆盖 2.5s 周扫空窗；
   `clearToIdle()` 已经空过就不再重复通知（原来每次 reconcile 都推一次 null）。
   `applyPlayingUi()` 状态没变也不重复推。
3. **唤醒风暴**：两个通知监听组件交替连接时，每次连接都把 `wakeRetryCount` 归零、
   每次断开又把 `defaultWakeDone` 复位 → 「唤醒上次音乐应用」的 PLAY 键无限重发。
   现在唤醒整进程只做一轮；组件选择也改成「已注册组件还在线就不换」，不再来回重注册。

验证：新增 `PlaybackUiPolicyTest`（5 条，含「2.5s 卡顿仍算播放」「6s 停滞算暂停」
「宽限期覆盖一次周扫空窗」）；全量单测 + system/debug 两变体构建全绿。

## 冷启动：先出画面，再建桌面（第九十三轮）

真车反馈「车机开机时会黑屏一段时间才加载到桌面」。原因不是某个功能慢，而是
**整个桌面的构造都压在 `onCreate` 的主线程上**：

- `MainActivity.onCreate` 里直接 `LauncherView(this)`；
- `LauncherView.init` 同步做：`VehicleController.init`（车控 binder/反射）、`build()`（整棵 UI 树：
  场景页 / 导航页 / 抽屉 / 状态栏 / Dock / 浮窗层 / 桌面卡片（`WeatherDesk.start`，联网+定位）、
  `handler.post(ticker)`（**立刻**开始每秒一次的车控/媒体刷新）、`AmapDesktopGuide`
  （注册广播 + 向高德索要区域信息）；
- 这段时间屏幕上是系统 starting window —— 而旧 `AppTheme.windowBackground` 是 **`#05080F`（近黑）**
  ⇒ 用户看到的就是「一块黑屏，过一会儿桌面才出来」。

现在改成**三段式**，并给每一段打启动分段日志（`core/StartupTrace.kt`，对照 UNI 的 `StartupTrace.java`）：

| 段 | 做什么 |
|---|---|
| 1. `onCreate` | 只初始化日志 / 崩溃兜底 / 全屏，然后 `setContentView` **一个纯 Canvas 启动页**（`ui/LaunchSplashView.kt`：品牌渐变 + 转圈 + 「正在启动桌面…」）⇒ **毫秒级出第一帧** |
| 2. 启动页第一帧后 | 才构造 `LauncherView`（启动页盖在上面，用户看到的是「启动中」而不是黑屏） |
| 3. 桌面第一帧后 | `LauncherView.startServices()` 拉天气 / 每秒刷新 / 导航引导；再启动媒体、氛围灯、任务调度、权限延迟检查；最后启动页 **220ms 淡出** |

配套改动：

- theme 的 `windowBackground` 从纯 `#05080F` 换成同色系渐变 `res/drawable/launch_background.xml` ——
  **连系统 starting window 那段也不再是死黑**；
- `MainActivity` 里所有 `launcher` 调用改为可空 + `whenLauncherReady` 排队
  （`onResume` / `onNewIntent` / 返回键 / 权限回调都可能早于桌面就绪）；
- `LauncherView` 新增幂等 `startServices()`（`onDetachedFromWindow` 清掉的 ticker 会在
  `onAttachedToWindow` 里补挂）；
- 「向高德索要区域信息」从 `onAttachedToWindow` 挪到 `startServices()` ——
  那是跨进程广播，不该排在首帧前。

**怎么验证启动耗时**（车机连不上 adb 也能看）：

1. **不需要 adb**：车机上「设置 → 系统 → 诊断日志 → 导出诊断包」，
   打开 zip 里的 `summary.txt`，看「---- 冷启动 ----」段 ——
   里面同时有**本次启动**和**上次冷启动**（上次的数字持久化在
   `cardesk_startup.xml`，**日志被轮转掉也不会丢**）：

   ```
   本次启动: total=…ms boot=…ms 起点=process fromCreate onCreate=0 window=12 splashFirstFrame=40 …
   上次启动: 2026-10-31 23:40:12  total=…ms boot=…ms 起点=process fromCreate …
   ```

   - `total`：进程启动 → 导出时（冷启动完整走完时 = 进程启动 → 启动页 220ms 淡出完成）；
   - `fromCreate` 各段：`splashFirstFrame`（第一张图 = 黑屏结束）、`launcherFirstFrame`（桌面画出来）、
     `servicesDone`（车控/天气/媒体这些重活干完）；
   - `bootMs`：开机以来的毫秒数 —— 用来区分**系统开机慢**（`bootMs` 很大）
     还是**桌面启动慢**（`processMs` 大）；诊断包里「收到开机广播」那两行也带 `bootMs`，
     两者一减就是「广播到桌面首帧」的耗时。
2. 日志文件里也能搜「启动分段」/「启动完成」（`logs/` 目录）。
3. 有 adb 时同样可以 `adb logcat -s CarDesk-Startup`。

`total` 与 `am start -W` 的 `TotalTime` 同一时钟（都是 `uptimeMillis`），可直接对照。

> 取舍：桌面数据（天气、车况、导航卡）不再可能早于首帧 —— 它们本来就要依赖车控/网络，
> 现在只是**晚几百毫秒**补上；换来的是「开机立刻有画面」。
> 真机上 0/1/2 车门枚举、绿灯读秒仍需一次实车日志定论，见上面「已知限制」。

## 媒体播放/暂停（第八十七轮）

这一轮把播放/暂停按参考工程 UNI Launcher 的三条系统级逻辑补齐，**桌面自己的 Dock、
槽位条、全局 Dock、方控、仪表全都走同一条幂等入口**：

### 1. 幂等：`setPlaying(desired)` 而不是「切换」

- 连点播放键不再是「翻两次」：`core/media/TransportIntent.kt` 是纯逻辑决策器，
  `decide(观察到的播放态, 期望态, 可用 actions)` 给出 `send/reason`，
  **已经满足就不下发**（`already-satisfied`），日志里能直接看到
  `播放/暂停指令（幂等）：不下发` 以及 `desired/state/force/actions/buttons/transport`；
- 用户意图有 800ms 窗口（第八十七轮 `nextToggleTarget`，第八十八轮改由
  `toggleFromDisplayed` 承担）：连点两次 = 回到原状态，但**只发两条**，
  不会因为观察态滞后而互相抵消成「一次都不发」或多发一条；
- `force` 只越过 `already-satisfied`；**第八十八轮起 `no-action` 也不再被闸门吞掉**
  （见下面第 6 条：真车上这条闸门直接把用户按下的播放丢掉了）。

### 2. 车机自带音源「实现了回调但没 setActions()」的补位

参考工程里这条补丁是给网易云写的（它的 `PlaybackState` 没有 `setActions()`，
可 transport 回调其实都在）。这轮把它**推广到所有车机自带音源**：
`actions == 0` 且带 `FLAG_HANDLES_TRANSPORT_CONTROLS` 或 `FLAG_HANDLES_MEDIA_BUTTONS`
→ 只补 `ACTION_PLAY | ACTION_PAUSE`（不补上一首/下一首/进度，避免假装支持）。
不补的话这些音源在「幂等」这一层会被直接挡死（老实现是无条件下发，反而「能用」）。

### 3. 网易云的定向按键通道

`core/media/MediaButtonTransport.kt`：网易云（`com.netease.cloudmusic.iot`）的
播放/暂停/上一首/下一首改成 `controller.dispatchMediaButtonEvent(KeyEvent)`，
**DOWN + UP 两条**（它对 DOWN 不响应、只消费 UP），其它播放器仍是
`transportControls` 优先、全局按键兜底。返回的是「派发是否被接受」，
**不是**「播放态是否真的变了」——不据此重试，否则会二次翻转。

### 4. 方控 / 仪表媒体桥（`core/media/OfficialMediaBridge.kt`）

设置页「方控仪表 → 桥接开关」（`session_bridge_enabled`，默认关）打开后，
把当前选中的**第三方在线播放器**注册进原车 MediaCenter，于是方控、仪表、
原车媒体 UI 都能控制它（原车电台 / USB / 蓝牙仍归原车自己管）：

- 反射 `com.ecarx.sdk.mediacenter.MediaCenterAPI` + `IMusicClient` / `IMusicPlaybackInfo` /
  `IMediaInfo`，bind `ecarx.xsf.MEDIA_CENTER_SERVICE` → `ecarx.xsf.mediacenter.MediaCenterService`，
  `registerMusic` 后 `updateMediaSourceTypeList(ONLINE = 6)`；
- **所有 Binder 调用都在单独一条线程**，同步 getter 只读不可变缓存（绝不阻塞 UI 线程）；
  原车回调 `onPlay/onPause/onNext/onPrevious` 转成幂等的 `setPlaying` / `next` / `previous`；
- 源暂时消失时保留最后一份采样 2.5s（避免仪表闪烁）；进度推送 1s 节流；
- **无 SDK / 无权限时只记 reason 并降级**，不影响普通播放控制
  （模拟器实测日志：`原车媒体接入失败：NameNotFoundException`，没有崩溃）;
- 开关**即时生效**（第八十七轮补的那一下）：不用重启桌面，切开关就 start/stop 桥。

### 5. 这条链上「还没在真车验过」的部分

- `ecarx.oem.permission.OPENAPI_MEDIACENTER_PERMISSION` 权限名、SDK 包名、
  服务组件名、`ONLINE = 6`、`wirePlayback` 的 1/0 语义，**全部沿用参考工程**，
  真车上第一次跑要看日志（`OEM 媒体桥启动` / `原车媒体接入失败：…` /
  `原车媒体未接受播放信息`）；
- 网易云只认 `com.netease.cloudmusic.iot` 这一个包名；
- 「没有会话时 `setPlaying(true)`」会走定向唤醒 + 拉起音乐 App，返回 true 只代表
  「命令发出去了」，**不保证真的响**（这是媒体会话本身的边界，不是本项目的 bug）。

### 6. 第八十八轮：**「暂停后按播放恢复不了」的真车修复**

用户真车反馈（领克 dcy11 / Android 9 / 播放器 `com.tencent.wecarflow` 爱趣听）：
按下播放键「没反应」，只能重新点开音乐 App。日志与代码对照后是**两条叠加**的坏逻辑，
都是第八十七轮那次「幂等化」留下的：

- **坏逻辑 A：切换基准取了会话实测态，而会话状态滞后于界面。**
  我们暂停下发的是 `stop()`，界面（乐观 UI）立刻变暂停，但会话 `PlaybackState`
  要过几百毫秒才变、真车上甚至一直停在 `PLAYING`。于是「按播放」时
  以实测为基准算出的目标态**还是暂停**（`desired=false`）→ 又发一条暂停 →
  永远恢复不了。日志特征：两次 `播放/暂停指令` 的目标态都是 `to=false`。
- **坏逻辑 B：`no-action` 闸门把用户的播放整条吞掉。**
  爱趣听这类会话经常**不声明** `ACTION_PLAY`，甚至 `actions == 0`、flags 也没有 →
  `decide()` 判 `no-action` → 闸门返回「不下发」→ 一个字节都没发出去。
  日志特征：只有 `播放/暂停指令（幂等）：不下发`，没有任何后续下发记录。
  第八十七轮之前是无条件下发，所以老日志里反而「能恢复」。

修法（`core/media/TransportIntent.kt` + `MusicSessionTracker.kt`）：

- **切换基准改成「界面显示态」**（`toggleFromDisplayed`）：界面就是用户刚看到并
  据此按下的那个状态，取反即目标态；会话实测只用来判断会不会被 `already-satisfied`
  吞掉 —— 一致就 `force=true` 强推（`setPlaying` 允许越过 `already-satisfied`）。
  连点仲裁沿用 800ms 窗口。
- **`shouldDispatch` 不再拦 `no-action`**：action 位只作参考，用户明确按了键就必须发；
  我们下发的是**定向**通道（选中会话的 `transportControls` / 网易云定向按键），
  发错了顶多被对方忽略，不会像全局 `MEDIA_BUTTON` 广播那样误伤别的 App。
- **全局 Dock 的播放键改走 `playPause()`**：原来它自己算 `!playing` 再 `setPlaying`，
  `force` 恒 false，最容易被「已满足」吞掉；现在桌面和全局 Dock 是同一条路径。
- **DMT（爱趣听/车机多媒体）恢复改成阶梯升级**：t=0 只 `transportControls.play()`，
  +400ms 仍未恢复才补一次全局 `KEYCODE_MEDIA_PLAY`，+1000ms 再补一次
  （且只有爱趣听才加定向广播）。真车日志里「恢复瞬间 wecarflow 会话整体消失」
  就是一次性齐发 transport + 全局键 + 定向广播造成的，参考工程 UNI 只用 transport 一条通道。

模拟器可复现（`media-probe` 新增两个真车缺陷模式参数）：

```bash
# A：会话状态滞后 60s 仍报 PLAYING（暂停后按播放）
adb shell am start -n com.cardesk.mediaprobe/.MainActivity \
  --es title 海阔天空 --es artist Beyond --es play true --es lag 60000
# B：不声明 action 位（按播放被闸门吞掉）
adb shell am start -n com.cardesk.mediaprobe/.MainActivity \
  --es play false --es actions none
```

实测日志（验证包 `com.cardesk.r88verify`，2026-10-31）：

```
# A：暂停（会话仍报 PLAYING）→ 等窗口过期 → 按播放，目标态 force=true 强推
播放/暂停指令（切换） pkg=… state=3 displayed=false observed=true desired=true force=true
播放/暂停指令（幂等） pkg=… desired=true state=3 from=false reason=already-satisfied force=true
# B：actions=0 / flags 在时补位下发（不再「不下发」）
播放/暂停指令（幂等） pkg=… desired=true state=2 from=false reason=send-play force=false actions=0
```

## 在线更新（Cloudflare R2）


App 内「设置 → 在线更新」**手动**检查一次，源是本项目自己的 **Cloudflare R2 自定义域**：

```
GET https://file.chuxi.bid/cardesk/latest.json
```

**为什么从 GitHub Release 换到 R2**：车机在国内访问 `api.github.com` 又慢又不稳，
R2 走 Cloudflare 自己的 CDN，实测可达性更好。GitHub Release 继续作为源码与 tag 的
分发渠道，只是不再承担 App 的更新检查。

R2 只是对象存储、没有「release」这种东西，所以清单格式是本项目自己定的
（App 侧解析见 `app/src/main/java/com/cardesk/launcher/core/update/R2Release.kt`）：

```json
{
  "schema": 1,
  "version": "1.0.1053",
  "versionCode": 4149,
  "tag": "v1.0.1053",
  "name": "CarDesk 4149 / 1.0.1053-system",
  "notes": "……",
  "publishedAt": "2026-10-30T08:22:57Z",
  "apk": {
    "name": "CarDesk-ecarx-cardesk-4149.apk",
    "url": "cardesk/CarDesk-ecarx-cardesk-4149.apk",
    "size": 1941656,
    "sha256": "a09894ad776c37604a5188edfe3242df016f8b009d4a8c447380126d32c7423a"
  }
}
```

- `apk.url` 写的是 **R2 对象 key**（相对域名根），也就是上传时用的那个 key —— 填什么就传什么；
- 下载完会校验**文件长度**和 **SHA-256**，对不上就当这次更新失败，不装残包；
- App 端**不存任何密钥**：走自定义域匿名下载，凭据只在上传端用。

发布一个新版本的流程：

```bash
bash tools/release.sh --notes "这轮更新了什么"
#   → dist/CarDesk-ecarx-cardesk-<code>.apk
#   → dist/cardesk-latest.json（就是上面那份清单）

export R2_AK=<Access Key ID>
export R2_SK=<Secret Access Key>
python3 tools/publish_r2.py --bucket file \
    --apk dist/CarDesk-ecarx-cardesk-<code>.apk \
    --manifest dist/cardesk-latest.json
#   → 上传后自动回读校验（长度 + SHA-256）
```

> **密钥不入库**：R2 的 S3 密钥只在环境变量里用，仓库是公开的，
> 密钥一旦进代码或文档等于公开。创建密钥时只勾「指定 bucket + Object Read & Write」。

### ⚠️ 发布默认**不做**（用户 2026-10-31 定下的规矩）

出包（`tools/release.sh`）、跑全量单测、提交并推送 `origin/main` —— **照常做**。
但下面两件**默认不执行**，除非用户明确说「发布」：

| 动作 | 默认 | 说明 |
|---|---|---|
| GitHub Release 的 **Publish** | **不做** | 表单**可以先备好**（tag / 标题 / 正文 / APK 附件），但**不点 Publish** —— 点了这一版 Release 立刻在公网可见、任何人都能下 |
| R2 上传（`publish_r2.py` / `publish_apks.py`） | **不做** | 凭据只从环境变量读，环境里没有就不做，并如实写进报告 |

> 第八十六轮就是这样：4157 的 Release 表单**已经填好并传上了 APK**
> （tag `v1.0.1061`、Target `main`、标题正文齐、附件 1.87 MB），
> 用户在被问「确认发布吗」时选了**「先不发布」** ⇒ 没有点 Publish，
> 那一版 Release **至今不存在**（不会出现在 Releases 列表里），
> 表单原样留在浏览器标签页里，改主意时点一下即可。

**为什么这么定**：Release 一发布就是**公网可见、任何人可下载**的不可逆动作；
上传 R2 同样会把包推到公网 CDN。两者都属于「对外发布」而不是「本地构建」，
所以必须由用户单独点头，不能从「出包」「提交推送」顺延推断出来。

## 云端应用库（手机传 APK，车机直接装）

App 内「设置 → 网络与升级 → **云端应用**」能列出你自己上传到 R2 的 APK，
点一下就下载并安装（system 版静默装，普通安装包转系统安装器）。

**解决的痛点**：车机装别的 App，原来只有「带 U 盘到车上插」和
「在车上让电脑和车机连同一个局域网」两条路，两条都要求人在车旁边。
这一条是**出门前在手机上上传，到车上直接装** —— 不用带 U 盘，
也不用和车机连同一个局域网（车机走 4G 就行）。

```
手机浏览器 ──上传──▶ Cloudflare Worker ──▶ R2（cardesk/apks/xxx.apk）
                                            │
车机 CarDesk ──GET https://file.chuxi.bid/cardesk/apks.json ──▶ 清单
              └── 下载 APK ──校验长度+SHA-256──▶ 静默安装 / 系统安装器
```

**上传端是一个 Cloudflare Worker**（`worker/` 目录，手机浏览器打开就能用）：
R2 的写入必须带凭据，而凭据放进浏览器里的网页等于公开，
所以网页只发请求、**真正的写入在 Worker 里**（R2 binding，凭据由 Cloudflare 托管，
既不进代码、也不进仓库、也不进手机浏览器）。写操作要带口令，
没配口令时**一律拒绝**（fail closed）。

部署步骤见 [`worker/README.md`](worker/README.md)。
**车机端只读 R2 公开域，不依赖 Worker 地址** —— 换域名不用重装 App。

清单（`cardesk/apks.json`，App 侧解析见 `core/apkstore/ApkIndex.kt`）：

```json
{
  "schema": 1,
  "updatedAt": "2026-10-30T09:45:37Z",
  "items": [
    {
      "name": "酷狗音乐",
      "file": "kugou.apk",
      "key": "cardesk/apks/kugou.apk",
      "size": 12345678,
      "sha256": "…64 位十六进制…",
      "addedAt": "2026-10-30T09:45:25Z"
    }
  ]
}
```

- `key` 写的是 **R2 对象 key**（相对域名根），也就是上传时用的那个 key —— 改域名不用改清单；
- 下载完校验**长度**和 **SHA-256**，对不上就当没下成功，不装残包；
- **一条坏数据只跳掉那一条**，不会让整份列表变空；
- 电脑上不想部署 Worker 也能传：

```bash
export R2_AK=<Access Key ID>
export R2_SK=<Secret Access Key>
python3 tools/publish_apks.py --bucket file \
    --apk /path/to/app.apk --name "酷狗音乐"
#   → 上传包 + 重写 cardesk/apks.json，并回读校验
```

> **和「在线更新」的关系**：同一个 bucket、同一个公开域、同一套
> 「下载 → 校验 → 安装」链路（`core/net/FileDownload.kt` + `core/update/AppUpdate.kt`）。
> 区别只在清单语义 —— 一个只放 CarDesk 自己（要比 versionCode），
> 一个放任意 APK（列表全给你，不比版本）。

## 目录结构

```
cardesk/
├── app/                                    Android 应用模块
│   └── src/
│       ├── main/java/com/cardesk/launcher/
│       │   ├── MainActivity.kt             唯一 Activity，承载桌面
│       │   ├── BootReceiver.kt             开机兜底拉起
│       │   ├── core/                       基础层
│       │   │   ├── Design.kt               1920×1080 设计坐标系 + 等比缩放
│       │   │   ├── Palette.kt / Skin.kt    日夜配色、玻璃/胶囊等材质
│       │   │   ├── Prefs.kt                SharedPreferences 偏好
│       │   │   ├── NavSlots.kt             导航栏槽位模型
│       │   │   ├── SystemOps.kt            系统层操作（隐藏原车 Dock/状态栏等）
│       │   │   ├── ScheduledTasks.kt       计划任务模型与编解码
│       │   │   ├── media/                  媒体接管（会话监听、来源、歌词）
│       │   │   │   ├── MusicSessionTracker.kt 会话跟踪 / 幂等播放暂停 / 桥接线
│       │   │   │   ├── TransportIntent.kt  播放暂停决策（幂等，纯逻辑）
│       │   │   │   ├── MediaButtonTransport.kt 网易云定向按键通道（纯逻辑）
│       │   │   │   ├── MediaBridgePolicy.kt 方控桥接管名单 / 报文取值（纯逻辑）
│       │   │   │   ├── OfficialMediaBridge.kt 原车 MediaCenter 桥（反射，降级安全）
│       │   │   │   ├── KugouKrc.kt          酷狗 KRC 逐字歌词解密 + 解析
│       │   │   │   ├── LyricWordTiming.kt   逐字时间语义自校准 + 贴词
│       │   │   │   └── OnlineLyricCatalog.kt 在线歌词竞速（QQ / 网易 / 酷狗）
│       │   │   ├── dock/                   全局 Dock（第八十七轮）
│       │   │   │   ├── GlobalDockService.kt 前台服务 + 跨应用覆盖窗
│       │   │   │   ├── GlobalDockPolicyGate.kt 覆盖窗 / 原车导航栏 policy 闸口
│       │   │   │   ├── NavInsets.kt         导航区域 RRO（安装 / 启用 / 恢复）
│       │   │   │   └── SystemNavigationPolicy.kt 只摘状态栏的 policy 合并
│       │   │   ├── update/                 在线更新
│       │   │   │   ├── AppUpdate.kt         检查 / 下载 / 安装
│       │   │   │   └── R2Release.kt         R2 更新清单格式与解析
│       │   │   ├── apkstore/               云端应用库（R2 上的 APK 列表）
│       │   │   │   ├── ApkIndex.kt          云端应用清单格式与解析
│       │   │   │   └── ApkStore.kt          拉清单 / 下载 / 安装
│       │   │   ├── net/                    通用网络与文件工具
│       │   │   │   ├── FileDownload.kt      下载 + 长度 / SHA-256 校验
│       │   │   │   └── Digest.kt            SHA-256 完整性校验
│       │   │   ├── nav/                    导航页逻辑
│       │   │   └── log/                    日志
│       │   ├── vehicle/                    车控层
│       │   │   ├── VehicleGateway.kt       车辆通道抽象
│       │   │   ├── AdaptapiGateway.kt      真车通道（反射 ECARX AdaptAPI）
│       │   │   ├── AdaptapiSensors.kt      续航 / 油量 / 胎压等传感器读取
│       │   │   ├── CarLibGateway.kt        AOSP Car 属性通道
│       │   │   ├── HvacIds.kt              车控属性号表（换车机只改这里）
│       │   │   ├── HvacCodec.kt            属性值编解码
│       │   │   ├── HvacLogic.kt            乐观更新 + 写后屏蔽 + 联动规则
│       │   │   ├── MockVehicleGateway.kt   模拟车辆通道
│       │   │   └── CarSignalMath.kt        信号 → 展示值的纯函数换算
│       │   └── ui/                         界面层
│       │       ├── LauncherView.kt         桌面根视图（页面宿主 + 状态栏 + Dock + 浮层）
│       │       ├── ClimatePage.kt          气候整页
│       │       ├── DrawerPage.kt           应用抽屉
│       │       ├── SourceMenuLayer.kt      媒体来源切换菜单
│       │       ├── SettingsOverlay.kt      设置面板
│       │       ├── NavSlotDialog.kt        导航栏槽位管理
│       │       ├── pages/ shell/ widget/   场景页 / 状态栏与 Dock / 桌面组件
│       │       ├── dialogs/ floats/ embed/ pip/   对话框 / 浮窗 / 应用嵌入 / 画中画
│       ├── main/res/                       drawable、values 资源
│       └── test/java/com/cardesk/launcher/  JVM 单测（含离屏渲染）
├── design/                                 设计板：PNG 原图 + 生成脚本
├── dist/                                   交付产物目录（APK 不入库，走 Release）
├── emu-verify/                             模拟器验证记录与截图
├── insets/                                 导航区域 RRO 覆盖包源码（第八十七轮）
├── system-patch/                           system 变体所需补丁（hiddenapi 白名单等）
├── tools/                                  构建、比对、发布、日志采集脚本
│                                          （含 make_manifest.py / publish_r2.py /
│                                            publish_apks.py / build_insets.py /
│                                            r87_verify.py）
├── worker/                                 云端应用库的上传端（Cloudflare Worker）
├── gradle/                                 Gradle wrapper
├── build.gradle.kts                        全局构建脚本
├── settings.gradle.kts                     模块与仓库声明
├── gradle.properties                       Gradle 属性
└── version.properties                      versionCode / versionName
```

## 构建

需要 JDK 17 + Android SDK（platform 34、build-tools 34）。

```bash
echo "sdk.dir=/path/to/android-sdk" > local.properties
./gradlew assembleDebug        # 普通安装包
./gradlew testDebugUnitTest    # 纯 JVM 单测
```

> `platform-key/`（平台签名私钥）不在仓库里。有平台密钥时 `assembleSystem` 用平台签名，
> 没有时退化为 debug 签名 —— 也就是 clone 下来就能编译，只是装进 `/system/priv-app/`
> 需要你自己的平台密钥。

## 许可

本仓库**暂未附带 LICENSE 文件**：在作者补充许可条款之前，请勿将本项目视为已授予
再分发或商用授权；二次开发或分发前请先与作者确认。
（App 内「设置 → 开源与声明」页展示的是同一口径。）


## VIN 实读 + 车道箭头 + 巡航导航卡（第九十九轮）

### 1. VIN 不再写死

设置页左栏底部与「关于/设备」页的 VIN 都改成后台读取：

- **三级读法**（由近到远）：`PA_VIN_VinNum=560992300` 的 byte[]（`PublicAttrValue.stringData`）→ 同属性的 String 形态 →
  `DID_VIN_VinNum=554709014`（PA 消息 / String / 裸 UTF-8）→ 厂商 `CarSignalManager.getPaVinVinNum()` / `getDidVinVinNum()` 反射。
- **校验**：清洗空白/BOM/控制字符 → 全大写 → 17 位 `[A-HJ-NPR-Z0-9]`（不含 I/O/Q）；不合法按「读不到」处理并留 WARN。
- **缓存**：成功永久缓存、失败 60s 重试；左栏与关于页同时触发只读一次。
- 读不到时显示「VIN：读不到」，读取中显示「VIN：读取中」。

### 2. 车道箭头：转弯移到直行箭头下方

`drawLaneGlyph` 的三种组合形（直行+左 / 直行+右 / 直行+左右）改成**两枚独立箭头**：
直行箭头靠一侧，转弯箭头画成独立的小「L」形箭头放在左下/右下，两者之间不连线、留出明显空隙（38px 车道 chip 下也清晰可辨）。

### 3. 导航卡扩展到巡航态

- 判定：收到 `KEY_STATE` 且状态**不是** 8/10（导航/模拟导航）时，后续引导帧按「巡航帧」处理——
  只更新道路名/车道/图标，不进入导航模式。
- 巡航卡内容：**当前道路 + 车道指示 + 多方向红绿灯**（左/直/右各一枚，每枚带方向箭头、灯态、读秒）。
- 多方向红绿灯两个来源合并：
  * 官方协议 `KEY_TYPE=60073`（一帧一个 `dir`）：巡航时按 `dir` 累积（1 左 / 4 直 / 2 右），各自按 TTL 过期；
  * YH 巡航协议 `NAVITOOL_CRUISE_TRAFFIC_LIGHTS`（一帧多方向 JSON）：逐条解析、坏条跳过、同方向去重。
- 时间：引导帧 20s 内有效；红绿灯每条按既有 TTL；导航引导卡逻辑不变（8/10 + 有效帧）。

**已知限制**：真车 VIN 三个通道未上真机验证（需看日志 `读取到 VIN` 的 `source` 字段）；巡航卡依赖高德在巡航态推引导/红绿灯帧，
若某方向没有帧就不会显示那一枚灯。


## 应用抽屉「禁用 / 启用」（第104轮）

抽屉每个格子的 ⋮ 菜单新增一项状态相关的动作：

- **可用应用** → 「禁用应用」：先弹确认（禁用后停止运行、图标变灰，可随时恢复），
  确认后走 `PackageManager.setApplicationEnabledSetting(pkg, COMPONENT_ENABLED_STATE_DISABLED_USER, DONT_KILL_APP)`。
- **已禁用应用** → 「启用应用」：恢复应用级状态，并额外
  `setComponentEnabledSetting(启动入口, COMPONENT_ENABLED_STATE_ENABLED)`——
  像 SIM Toolkit 这种「入口组件默认被禁用」的应用，只改应用级是恢复不了的。
- 能力要求：`CHANGE_COMPONENT_ENABLED_STATE`（平台签名/系统应用才有）。普通安装调用会抛
  `SecurityException`，只提示「没有系统权限，无法禁用/启用应用」，界面不变、不崩溃。

抽屉列表本身改为 `queryIntentActivities(..., MATCH_DISABLED_COMPONENTS)`：

- 已禁用的应用**继续留在抽屉里**（否则禁用后就"消失"、再也找不到恢复入口）；
- 图标 alpha 0.35 压暗、名称用弱色，名称下方加一行「已禁用」；
- 点已禁用应用不会启动，而是打开它的 ⋮ 菜单（唯一的恢复入口）；
- 状态判定同时看清单 `enabled` 和 `getApplicationEnabledSetting` / `getComponentEnabledSetting`，
  保证系统默认禁用的应用也能正确显示与恢复。

**已知限制**：禁用系统关键应用（如 Settings）会导致对应能力不可用，属预期；禁用桌面本身被显式禁止（菜单里提示「桌面本身不能禁用」）。

## 自定义壁纸（第105轮）

设置 → 壁纸新增「自定义壁纸」一节：从系统相册（`ACTION_OPEN_DOCUMENT`，不需要存储权限）选一张照片即可当桌面壁纸。

实现口径（按「资源占用与性能对比」会话的结论落地）：

- **导入只在后台线程解一次**：`inJustDecodeBounds` 读尺寸 → `inSampleSize` 按 2 的幂降采样 → 等比缩放 + 居中裁切到 **1920×1080** → JPEG 88 落盘（实测一张 2000×1200 照片 53 KB）。原图不留在 App 里。
- **冷启动只解这张小图**，且**只保留一张全屏位图**（`CustomWallpaper` 单槽缓存），不参与 `WallpaperRenderer` 的 LRU；
  首帧之后异步解码，解码完成前先铺桌面底色 —— 三段式启动的时序不变。
- **夜间不额外存第二张图**：绘制时盖一层 40% 黑 + 顶部渐深的 scrim。
- **稳态绘制路径与预设壁纸相同**：每帧仍是一次 `drawBitmap`（居中裁切），不做解码/缩放。
- 顺手给壁纸成品缓存加了**按字节的上限（40 MB）**：原来只按 10 条，设置页 10 个缩略图就能把全屏那张挤掉。

交互：

- 「导入照片…」→ 系统相册 → 导入成功后自动切为当前壁纸，设置页与桌面立刻更新；
- 「使用自定义壁纸」切回照片，「删除自定义壁纸」清文件回到预设；
- 选任意预设缩略图 = 关掉自定义（照片文件保留，可随时切回）；
- 自定义壁纸时桌面左右拖拽换纸关闭（一张照片没有上一张/下一张），日志记录 `custom=true`。

性能（模拟器 API34，1920×1080，同一台机 force-stop 后稳定 15 s 采样）：

| 状态 | 稳态 PSS（两次） | 空闲 CPU | 冷启动（splash 首帧 / 桌面首帧） |
|---|---:|---:|---|
| 预设壁纸 | 50.1 / 51.6 MB | 0% | 125 ms / 379 ms |
| 自定义壁纸 | 51.4 / 51.3 MB | 0% | 125 ms / 379 ms |

即：自定义壁纸相比预设只多约 **0.5 MB（在噪声内）**，因为两者都只持有一张 1920×1080 全屏位图（8.3 MB）；多出来的是缩略图/测量抖动。导入那张 53 KB 的 JPEG 落盘，不占内存。

**已知限制**：导入按 16:9 裁切，竖图两侧会被裁掉；EXIF 方向只处理 JPEG 的 1/3/6/8；单张照片上限 64 MB。

## QQ 音乐车机版接入：方向盘方控 + 歌名/歌词（第 126 轮）

用户报的两个问题：

1. 设置里打开「方控」后，**方向盘按下去还是在控制爱趣听**（`com.tencent.wecarflow`），控不到 QQ 音乐；
2. **拿不到 QQ 音乐当前播放的歌**，也**没有歌词**。

这一轮把两件事都查到了底，而且**根因跟一开始猜的不一样** —— 不是「AOSP 会话可见性规则」，也不是「歌词格式认错了」。

### 诊断包给出的第一层线索

用户上传的真车诊断包（领克 dcy11 / Android 9 / 1920×968）里有两条关键信息：

- `default_music_source_v1 = qq`、`design_music_source_v1 = qq` —— 用户已经把默认媒体源设成 QQ 音乐了；
- 媒体段全是空：歌名空、歌手空、时长 0、歌词状态空。

而 `CarDesk/Media` 的日志（覆盖 18:25:43–18:27:19，约 24 分钟连续扫描，每 2.5s 一次）里：

```
周期重扫 count=569 sessions=2
会话选择 candidates=com.android.bluetooth#…,com.tencent.wecarflow#… locked=com.tencent.qqmusiccar selected= picked=无
没有选中任何会话（界面将清空） reason=reconcile candidates=2 locked=com.tencent.qqmusiccar
```

两件事同时成立：

- **QQ 音乐 24 分钟里一次都没进过会话枚举**；
- `locked=com.tencent.qqmusiccar` —— 这是第 125 轮「默认媒体源 → 桌面启动时锁包」加的**硬锁**，
  锁定分支里选不出可用会话就 `return null` → **每 2.5s 选一次都选不中 → 界面被清空** →
  OEM 媒体桥也拿不到 controller → 原车不把方控交给我们 → **方向盘还在爱趣听上**。

所以第 126 轮先修了这个**回归**：锁定包选不出可用会话时**不再返回 null**，而是继续走正常选择。
「默认媒体源」是**优先**，不是**一票否决**（`SessionChooser` / `MediaCoreTest` / `SessionChooserTest`）。

### 真正的第一因：QQ 音乐车机版**不发可用会话**，而且我们**从来没让它开口**

第 126 轮把 `com.tencent.qqmusiccar` 1.9.8.22 的真包下下来反编译，并在**模拟器上装真包实测**
（去掉 native 库重签后安装，Android 10 / arm64）。结论：

**① 它发布的 MediaSession 是个空壳。** `dumpsys media_session`：

```
mbr com.tencent.qqmusiccar/mbr
  state=PlaybackState {state=0, position=0, ..., actions=631}
  metadata: null
MediaSessionHelper-com.tencent.qqmusiccar …
  state=PlaybackState {state=0, position=0, ..., actions=1022}
  metadata: null
```

`metadata: null`（歌名只塞进了老式 `RemoteControlClient`）+ `state=0`（从不更新播放态）
+ 没有 `setCallback`（`transportControls` 发过去没人接）—— **标准 MediaSession 这条路对它是死的**。

**② 但它有一整套专门给第三方的广播协议**，而且我们**从来没发过最关键的那一条**。

`BroadcastSenderCenterForThird` 里每个 `updateXxx()` 的第一句都是 `if (this.allowSendOutside)` ——
**QQ 音乐默认不往外挂广播**，而这个开关只有第三方发 `action = 100` 才能打开。
我们以前**从来没发过** → QQ 音乐一个字都不往外广播 → **「没歌词」「没歌名」就是这么来的**
（不是歌词格式认错了，是**根本没收到**）。

模拟器上实测（模拟器上装了真包，`adb logcat` 原文）：

```
# 发 action=100（打开「允许对外发送」）
$ adb shell am broadcast -a com.tencent.qqmusiccar.action --ei action 100 \
      --el pull_from 45124 -p com.tencent.qqmusiccar
D BroadcastReceiverCenterForThird: dispacherAction and action is:100
I BroadcastSenderCenterForThird: setAllowSendOutside, allow=true
D BroadcastSenderCenterForThird: updatePlayState commandString :{"module":"play","command":
  {"method":"update_state","data":{"state":0,"curr_time":0,"total_time":0,"key_title":"",
  "key_artist":[{"singer":""}],"key_album":"","key_is_fav":0}}}
```

→ 开门后 QQ 音乐**立刻**回一条 `update_state`，JSON 结构和我们解析器完全对得上。
**关键细节**：`key_artist` 是**数组** `[{"singer":"…"}]` —— 以前按字符串解析，**永远取不到歌手**。

而我们的接收器其实**早就收到**了，只是被自己的一道门挡掉了（模拟器实测原文）：

```
CarDesk/Media: 忽略 QQ 歌词广播（当前会话不是 QQ 音乐） boundPkg=
```

那道门是「当前绑定的会话必须是 QQ 音乐」—— 而 QQ 音乐**根本不发可用会话** → 桌面永远绑不到它
→ **循环死锁**。现在改成两条通路：绑定的就是 QQ 音乐（老规矩），**或者**当前没绑定任何会话
且用户把默认媒体源选成了 QQ 音乐（真车的实际情况）。

### 方向控制：走 `qqmusiccar://?action=20&m0=…`，不走广播

反编译时最容易踩的坑是：**广播通道的 `action=0/2/3` 并不是播放/上一首/下一首**，
在 QQ 音乐那边是 `openQQMusic()` —— **只会把界面打开，不会切歌**。
真正下发到播放器的是 `controlPlay(m0)`，而它只从 URI 的 `action=20` 进来：

| m0 | 含义 | 出处 |
|---|---|---|
| 0 / 1 | 播放 / 暂停 | `controlPlay()` → `setPlay()` |
| 2 | 上一首 | `controlPlay()` → `prevPlay()` → `MusicPlayer.playPrev` |
| 3 | 下一首 | `controlPlay()` → `backPlay()` → `MusicPlayer.playNext` |

实测（`m0=3`）：

```
I BroadcastReceiverCenterForThird: onReceive： uri:qqmusiccar://?action=20&m0=3
D BroadcastReceiverCenterForThird: ACTION : 20
E MusicPlayerHelper: eventType = 2what = 10, subwhat = 0, ex = gotoNextSong
```

`gotoNextSong` 真的下发到播放器了（模拟器上没登录，所以后面报
`PLAY_ERR_CANNT_PLAY_OTHER` —— 那是没有播放列表，不是命令没到）。

URI 对应的是 QQ 音乐清单里 **`DispacherActivityForThird` 这个 exported Activity**，
它内部再转成广播给接收器。所以我们用 `startActivity` 走 Activity 这条路 ——
**不受 Android 8 起「隐式广播不再投递给清单接收器」的限制**，一定能送到。

### 这一轮改了什么

- **`core/media/QqBroadcast.kt`（重写）** —— QQ 三方协议的完整封装：
  解析（`key_title` / `key_artist` 数组 / `key_album` / `total_time` / `curr_time` / `state` / `lyric`）、
  开门（`action=100`）、传输控制（`qqmusiccar://?action=20&m0=…`）、采纳门。
- **`core/media/MusicController.kt`** —— 收广播 → 合成曲目（歌名/歌手/时长/进度/播放态）→ 解析歌词；
  默认源是 QQ 音乐时**主动开门**（收到广播 = 门开了的凭据，没收到就 30s 重试）；
  把合成的曲目**喂给原车媒体桥**。
- **`core/media/OfficialMediaBridge.kt`** —— 桥的采样来源从 1 个变成 2 个（**会话** + **广播**），
  合并规则是**会话优先**（`MediaBridgePolicy.pickSource`）。没有这一条，桥拿不到 QQ 音乐的样本
  → 不注册到原车 → **方控还在爱趣听上**。
- **`core/media/MusicSessionTracker.kt`** —— 原车把方控交给我们之后，按键要真的作用到 QQ 音乐上：
  没有会话时改走 QQ 自己的三方协议（`broadcastControl`）。
- **`core/media/SessionChooser.kt`** —— 锁定包选不出可用会话时**回落**，不再清空界面（第 125 轮回归）。
- **`core/media/MediaSessionDump.kt` / `PackageBroadcastProbe.kt`（新）** —— 诊断包新增
  `dumpsys media_session` 全量会话、各音乐 App **注册了哪些广播接收器**（不用拿 APK 就能问出 action）。
- **`core/log/Diagnostics.kt`** —— 诊断包新增「媒体诊断（第126轮）」「系统媒体会话」「音乐 App 广播接收器」三段，
  以及 `qqDoor(允许对外发送)` / `qqControl(已发方控命令)` 两个新字段。

### 顺带挖出来一个第 125 轮遗留的**致命崩溃**（桌面进程直接挂掉）

在模拟器上装新包、把默认媒体源设成 QQ 音乐再启动，桌面**起不来**：

```
E AndroidRuntime: FATAL EXCEPTION: Thread-3
E AndroidRuntime: Process: com.ecarx.cardesk, PID: 17384
E AndroidRuntime: java.lang.NullPointerException: Attempt to invoke virtual method
    'boolean android.os.Handler.post(java.lang.Runnable)' on a null object reference
E AndroidRuntime:     at com.cardesk.launcher.ui.LauncherView.updateMediaSourceIcon$lambda(LauncherView.kt:1765)
```

原因是第 125 轮加「媒体源图标」时写的两行：

```kotlin
private var iconLoadingFor: String? = null
private val iconHandler = Handler(Looper.getMainLooper())   // ← 声明在 init{} 之后
```

Kotlin 的初始化顺序 = **声明顺序**，而 `init{}` 块（**构造期**）里就会
`build()` → `refresh()` → `updateMediaSourceIcon()` → **起后台线程去取图标**。
于是构造期起的后台线程读到的 `iconHandler` 还是 **null** →
`iconHandler.post{}` 抛 NPE → **整个桌面进程挂掉**。

改成 `by lazy` **也救不了** —— Kotlin 的 `lazy` 委托本身也是一个字段，
一样按声明顺序初始化，构造期读到的会是「委托是 null」。
唯一的办法是：**把这两个字段挪到 `init{}` 之前**。

这个崩溃只在「默认媒体源 = QQ音乐 / 网易云 / 酷狗 / 酷我 / 汽水 / 多媒体」时出现
（这些来源要走「真实 App 图标」那条路；蓝牙 / USB / 通用音乐是自绘符号，不起线程），
所以第 125 轮没被发现 —— 而它恰好命中了用户这次的配置。

回归测试：`LauncherSmokeTest.qqDefaultMediaSourceDoesNotCrashTheLauncher`
（把字段挪回去，这条必红）。

### 怎么验证

设置 → 媒体来源 → 默认来源选「QQ音乐」。此后：

- 日志里出现 `请求 QQ 音乐打开「允许对外发送」`，QQ 音乐侧出现 `setAllowSendOutside, allow=true`；
- 我们这边出现 `QQ 广播合成曲目`（歌名/歌手），有歌词时出现 `收到 QQ 歌词广播`；
- 诊断包里 `qqDoor(允许对外发送): 已开`、`qqBroadcast: 收到=N 采纳=N`。

**已知限制**：

- 真车上的 QQ 音乐版本可能比 1.9.8.22 新，协议字段是否一致**尚未验证** ——
  所以诊断包里带了 `qqBroadcastRaw`（最近一条广播原文）和各 App 的接收器 action 清单，
  出问题一看就知道是哪一环对不上。
- 「允许对外发送」是 QQ 音乐自己的**全局**开关，打开后 QQ 音乐会向系统广播播放信息。
  我们**只在用户把 QQ 音乐选成了媒体源（默认源或当前源）时才开**它 ——
  第126轮原本只看「默认源」，真车诊断包证明那样**一次都发不出去**，
  第128轮已改成两个源都认（见下节）。
- QQ 音乐没在运行时，按方控会把它拉起来（它的 `DispacherActivityForThird` 的既定行为）。

## 网易云 / 汽水音乐车机版接入（第 127 轮）

第126轮把 QQ 音乐车机版接进来了（方控 + 歌名/歌词）。这一轮把**网易云音乐车机版**
与**汽水音乐车机版**按同样的流程做一遍：下真包 → 装模拟器 → 反编译 → 实测协议 → 接入。

### 用的真包（模拟器 emulator-5556 / Android 10 / arm64-v8a）

| App | 版本 | 包名 | MD5 | 来源 |
|---|---|---|---|---|
| 网易云音乐车机版 | 6.2.81（6002081） | `com.netease.cloudmusic.iot` | `750A098DB717CC43A8742E43D9165B50` | 网易官方 CDN `d3.music.126.net` |
| 汽水音乐车机版 | 1.1.27（101027） | `com.luna.music.car` | `11ba8c116d10e75b8df751e1daa15cb4` | 592 下载站 `app.592xiazai.com` |

两个包都**直接装得上**（网易云同时带 `arm64-v8a` 与 `armeabi-v7a`；汽水只有 `arm64-v8a`），
不用第126轮那套「去 `lib/` + 重签」的招。

### 网易云：歌词有**两条**通道，我们以前**一条都没接**

反编译 `com.netease.cloudmusic/` 得到（`vc/g`）：

**① metadata 通道**（`vc/g$S(String)`）

```java
builder.putString("android.media.metadata.LYRIC", lyric);
```

整份 LRC 挂在 MediaSession metadata 的 **`android.media.metadata.LYRIC`** 上。

**② 会话事件通道**（`vc/g$L(Integer, String)` → `I("wt_music_lyric", map)`）

```java
map = {
  MediaMetadata.METADATA_KEY_MEDIA_ID : audioId,
  "wt_music_lyric_obj"  : lyric,   // 整份 LRC
  "wt_music_lyric_state": state,   // 1 = 歌词就绪；2 = 提示消息；5 = 加载中
}
```

**走哪一条由车机配置决定**（`vc/g$e.a(...)`，网易云自己的 `notifyLyricManner`）：

```java
if (notifyLyricManner == 1)      S(lyric);        // 只写 metadata
else if (notifyLyricManner == 2) L(state, lyric); // 只发会话事件
else if (notifyLyricManner == 0) { S(lyric); L(state, lyric); } // 两条都发
```

用户那台车是几，我们**不知道** → **两条都得接**。

#### 「网易云没歌词」的真因

我们原来读歌词的候选 key 是
`lyric` / `lyrics` / `lrc` / `lyric_text` / `current_lyric` / `caption` / `subtitles`
——**全是短名**，而 `MediaMetadata.getText(key)` 是**精确匹配** →
网易云用的 `android.media.metadata.LYRIC` **永远取不到**。

不是歌词格式认错了，是**根本没取到那一项**。

#### 方控：网易云的「开始播放」必须走 `transportControls`

网易云的按键接收方（`com.netease.cloudmusic.iot.mediacenter.MediaButtonEventReceiver`）
在**没有音频焦点**时直接丢弃事件：

```java
if (ni.b.c()) {                       // hasNoAudioFocus
    if (!noBlockKeyCodeList.contains(keyEvent.getKeyCode())) return null;  // 忽略
}
```

而「从没在放到开始播」恰恰就是**没有焦点**的时候 —— 走定向按键 = **按了没反应**。

所以：**开始播放** → `transportControls.play()`（走 App 自己的 `onPlay` 回调，由它申请焦点）；
**暂停 / 切歌** → 定向按键（DOWN + UP，它的接收方只消费 `ACTION_UP`）。
（与 UNI `NeteaseMediaAdapter.command` 的实测结论一致。）

#### 网易云还认一条**车机原生**的方控广播

清单里 `MediaButtonEventReceiver`（exported）注册了：

```
android.intent.action.MEDIA_BUTTON
hozon.intent.action.SWC_MEDIA_KEY
```

`SWC_MEDIA_KEY` 那条读的是 **`KeyEvent.keyCode`**（int extra），模拟器实测：

```
$ adb shell am broadcast -a hozon.intent.action.SWC_MEDIA_KEY \
      -p com.netease.cloudmusic.iot --ei KeyEvent.keyCode 87
I/MediaButtonEventReceiver: [filterIntentEvent] action=hozon.intent.action.SWC_MEDIA_KEY,
  event=KeyEvent { action=ACTION_UP, keyCode=KEYCODE_MEDIA_NEXT, ... }
```

映射：`85`→toggle_pause、`87`→next、`88`→previous、`126`→play、`127`→pause。

我们**没有**走这条广播 —— 原车把方控交给我们时给的是**动作名**（play/pause/next/previous），
不是 KeyEvent，走会话的 `transportControls` / `dispatchMediaButtonEvent` 已经是同一条
`MediaButtonEventReceiver.c(intent)` 通路。多发一路广播只会带来**双份处理**的风险。

### 汽水音乐车机版：它是个**规矩**的 MediaSession App

反编译 `com/luna/`：

| 事实 | 证据 |
|---|---|
| 会话 `setFlags(3)`（MEDIA_BUTTONS + TRANSPORT_CONTROLS） | `RemoteControlSession.java` |
| **声明了 action 位**（950 / 694，含 PLAY/PAUSE/SKIP_TO_\*/SEEK_TO） | `CoreRemoteControl.java` |
| 回调实现了 `onPlay`/`onPause`/`onSkipToNext`/`onSkipToPrevious`/`onSeekTo` | `DefaultMediaSessionCallback.java` |
| metadata 是**标准布局**：TITLE=歌名、ARTIST=歌手、ALBUM=专辑、ART=封面位图 | `CoreRemoteControl.java` |
| TITLE **永远是歌名**（`blueToothLyricOpen` 硬编码 `false`） | `CompositeRemoteControl.buildRemoteControlContext()` |

→ 标准 `transportControls` 通道**直接可用**，歌名/歌手/专辑/封面走通用路径即可。

#### 汽水**不向第三方暴露歌词**（这一条决定了歌词只能联网查）

三条可能的三方歌词通道，逐条排除：

| 通道 | 汽水 | 证据 |
|---|---|---|
| metadata 自定义 key | **没有** | 全包 grep 不到歌词类 key；只写上面 6 个标准字段 |
| `onSessionEvent` | **没有** | 全包 grep 不到 `sendSessionEvent` |
| `MediaBrowserService` 的 browse 树 | **空的** | `onLoadChildren()` → `sendResultSafe(result, null)`；`onGetRoot()` → `null` |

→ 汽水的歌词只在自己界面里。我们只能**联网查**
（网易 → QQ → 酷狗；汽水曲库以抖音热歌为主，网易云覆盖最好）。

### 这一轮改了什么

- **新增** `core/media/NeteaseSessionLyric.kt` —— 网易云会话歌词协议的纯逻辑实现
  （`wt_music_lyric` / `get_lyric_action` / `lyric_loading_action` / `lyric_message_action`
  的解析、过期判定、长度上限），**可 JVM 单测**。
- **新增** `core/media/SodaMediaSource.kt` —— 汽水的协议事实（标准会话、不暴露歌词、TITLE 不是歌词行），
  写进代码是为了**以后有人改白名单 / 把它当「歌词型来源」时立刻有测试变红**。
- `PlayerLyric.METADATA_LYRIC_KEYS` 加上 **`android.media.metadata.LYRIC`**。
- `MusicSessionTracker`：实现 `MediaController.Callback.onSessionEvent`，
  把会话推来的歌词交给 [MusicController]（按 **token** 过滤 + **过期事件**丢弃）。
- `MusicController`：新增「会话事件歌词」这一级歌词来源（优先级：QQ 广播 → 会话事件 → 播放器自供 → 联网），
  并把 `get_lyric_action` 的**当前三行**当成「播放器自报的当前句」（没有时间戳，但比联网猜的准）。
- `MediaButtonTransport`：新增 `shouldUseTransportControl(pkg, action)` ——
  网易云的「开始播放」改走 `transportControls.play()`。
- `MediaBridgePolicy.ELIGIBLE_PACKAGES` **加上 `com.luna.music.car`** ——
  **这是「方向盘方控能控到汽水」的第一因**：不在白名单，原车就不会把方控交给我们。
- `MediaSources.DEFAULT_MUSIC_PACKAGES` 加上汽水（能被选成「默认音乐应用」）。
- 诊断包新增 `sessionLyric(会话歌词): 事件=N 行数=N 来源=…`。

### 怎么验证

模拟器（`emulator-5556`，Android 10）上：

```
# 默认媒体源选网易云 → 桌面启动即锁定网易云，音乐卡片显示网易云真实图标
I/CarDesk/Media: 媒体源图标已加载 sourceId=netease pkg=com.netease.cloudmusic.iot px=64
I/CarDesk/Media: 默认媒体源：已锁定 sourceId=netease pkg=com.netease.cloudmusic.iot

# 默认媒体源选汽水 → 同上
D/CarDesk/Media: 媒体源图标已加载 sourceId=soda pkg=com.luna.music.car px=64
I/CarDesk/Media: 默认媒体源：已锁定 sourceId=soda pkg=com.luna.music.car
```

歌词两条通道用一个「协议探针」（模拟网易云那两个通道）验过：

```
# metadata 通道
I/CarDesk/Media: 使用播放器自供歌词 key=id:150619 lines=3 title=春风吹 artist=方大同
# 会话事件通道
I/CarDesk/Media: 收到会话事件歌词 pkg=com.cardesk.ncprobe key=id:150619 lines=3
I/CarDesk/Media: 会话事件：更新当前歌词行 pkg=com.cardesk.ncprobe line=谁在夜里唱着歌
```

**新旧包 A/B 对照**：同一个会话，旧包拿不到 metadata 里的歌词（只能联网，54 行），
新包直接用播放器自己那份（3 行）—— 日志里分别是「歌词目录命中」与「使用播放器自供歌词」。

### 已知限制

- **模拟器上汽水自己播不了**：它的 `/service/settings/v3/` 接口在模拟器环境被服务端拒了，
  界面停在「加载异常」。所以汽水的结论来自**清单 + 反编译源码**（权威），
  没能在模拟器上跑到「真实播放」那一步；真车上登录后应当正常。
- **模拟器上网易云也没登录**，真实播放时的歌词没跑到；
  歌词两条通道是用**协议探针**（严格照网易云那两个通道实现）验的。
- **汽水的歌词只能联网查** —— 它不向第三方暴露自己的歌词，这是 App 侧的限制，
  我们改不了；抖音独家曲可能查不到。
- 网易云那台车的 `notifyLyricManner` 是几，诊断包里看
  `sessionLyric(会话歌词): 来源=netease-metadata` 还是 `netease-session-event` 就知道。

## QQ 音乐方控修好：诊断包把第126轮的一个假设证伪了（第 128 轮）

第127轮发布后，用户在真车（领克 dcy11 / Android 9 / `geely/dcy11_a1`）上装了 4226 包，
出了一份诊断包（`cardesk-logs-20261106-085332.zip`），结论是「**还是无法方控 QQ 音乐**」。
这一轮就照着这份诊断包把问题挖到底。

### 诊断包里那 6 行，是全篇的钥匙

```
SharedPreferences(cardesk) 全量键值：
    design_music_source_v1 = qq          ← 有
    （**没有** default_music_source_v1）  ← 真车上压根没写过

qqBroadcast: 收到=4 采纳=0 丢弃=4
忽略 QQ 广播（当前会话不是 QQ 音乐） boundPkg= defaultSrc=generic
qqControl(已发方控命令): 0
bridge.bound: true                      ← 原车服务连上了
bridge.registered: false                ← 但注册一次都没成功
bridge.callbackCount: 0                 ← 原车一次方控回调都没给过我们
bridge.reason: 正在连接原车媒体中心
```

### 根因一：采纳门**读错了键**（第126轮的注释是错的）

第126轮在 `QqBroadcast.shouldAccept` 的 KDoc 里写：

> 2. **当前没有绑定任何会话**，而且**用户把默认媒体源选成了 QQ 音乐**
>    —— 真车上的实际情况（`default_music_source_v1=qq`）。

**这个假设被诊断包证伪了。** 真车上用户是在**音乐卡片的来源行**里选了 QQ 音乐，
写的是 `design_music_source_v1`（`Prefs.musicSource`）；
而设置页里的「默认媒体源」是**另一个键** `default_music_source_v1`，
用户**从来没动过它** → 读到的永远是默认值 `generic`。

于是采纳门永远关着 → 4 条广播**全被丢弃** → 我们拿不到歌名。

**两个选择是同一个意思**（「我要用 QQ 音乐」），只是存在不同的键里。
现在这道门**两个都认**：

```kotlin
fun shouldAccept(boundPkg: String?, defaultSourceId: String?, currentSourceId: String?): Boolean {
    if (QqLyric.isQqMusicPackage(boundPkg)) return true
    if (!boundPkg.isNullOrEmpty()) return false      // 别的 App 在播 → 让位
    return isQqSelected(defaultSourceId, currentSourceId)
}
```

### 根因二：开门广播（`action=100`）**一次都没发过**

QQ 音乐默认不往外挂广播，只有第三方发 `action=100` 打开「允许对外发送」才会。
第126轮的开门广播用**同一个键**判条件 → `qqSourceCache != "qq"` → **直接 return**，
所以这条命令**一次都没发出去** → QQ 音乐不往外挂 `update_song` / `update_state` / `update_lyric`
→ 我们永远拿不到歌名。

现在开门广播与采纳门共用同一个判定（`QqBroadcast.isQqSelected`），
并且卡片来源行切换时**立刻**开门（`LauncherView.applySource` → `onCurrentMediaSourceChanged`）。

### 根因三：原车媒体桥把「注册」和「有歌名」**绑死了**

`OfficialMediaBridge.register()` 的门槛是：

```kotlin
if (!running || !sample.available || registered || api == null) return
```

`sample.available` = 「我们拿到了歌名」。而 QQ 音乐车机版**不发布可用的 MediaSession**
（诊断包：会话枚举里一次都没出现过它）→ 歌名只能靠它的三方广播 →
拿不到歌名 → **永远注册不上** → 原车永远不会把**方向盘方控**交给我们。

诊断包里 `bound=true` 但 `registered=false`、且 `reason` 停在「正在连接原车媒体中心」，
就是铁证：`registerMusic()` 这个原车 API **根本没被调用过**
（调用过的话 reason 会变成「原车媒体注册未返回令牌」或某个异常名）。

### 这一轮的核心：把「方控目标」和「歌名」**解耦**

|  | 以前 | 现在 |
|---|---|---|
| 注册原车在线音乐源 | **必须先有歌名** | **只要桥在跑就注册** |
| 方控发给谁 | 只能是「歌名的来源包」 | 歌名的来源包 **或** 用户选的媒体源 |
| 没有歌名时 | 方控回调被丢弃（`!sample.available`） | 方控照常派发给兜底目标 |

「注册」= 「我是一个在线音乐源」，原车因此把方向盘方控交给我们；
「歌名」= 「我在放什么」，只影响仪表显示与桌面卡片。
**这两件事本来就不该绑在一起** —— 绑在一起就等于：拿不到歌名就永远拿不到方控。

抢播放权（`requestPlay` + `updateCurrentSourceType`）**仍然要求样本 + 正在播放**，
原车的收音机 / USB / 蓝牙一律不碰（`MediaBridgePolicy` 顶部的红线不变）。

**一个有意为之的行为变化**：以前「没有样本就 `release()` 注销」，
注销了原车就把方控收回去 → 于是「注册上了但按键不来」，比没注册更难查。
现在**没有样本也保持注册**，只把播放态报成「没在放」。
代价是原车界面上会**一直挂着「在线音乐」这个源**（内容为空）——
用户可以随时在设置里关掉「原车媒体桥接」开关把它摘掉（`stop()` 会注销）。
我们**不会**在没有内容时去抢播放权，所以原车认为的「当前音源」不会变。

兜底目标由 `MusicSessionTracker.syncControlTarget()` 定期同步：
把「用户当前选的媒体源」翻译成包名交给桥（`MediaSources.PRIMARY_PACKAGES`）。
「通用音乐 / 蓝牙 / USB」拿不到固定包名 → 不设目标 → 桥不会去动原车音源。

### 模拟器实测（emulator-5556 / Android 10 / 真包 QQ 音乐车机版 1.9.8.22）

模拟器上的 prefs 特意做成**和真车一模一样**：`design_music_source_v1=qq`，
**没有** `default_music_source_v1`。

```
# ① 开门广播发出去了（注意 defaultSrc=generic 也照发）
I/CarDesk/Media: QQ 开门广播：已选 QQ 音乐，准备开门 defaultSrc=generic currentSrc=qq
I/CarDesk/Media: 请求 QQ 音乐打开「允许对外发送」 sent=true doorOpened=false defaultSrc=generic currentSrc=qq

# ② QQ 音乐侧真的开门了
I/BroadcastSenderCenterForThird: setAllowSendOutside, allow=true
D/BroadcastSenderCenterForThird: updatePlayState commandString :{"module":"play","command":{"method":"update_state",…

# ③ 我们采纳了（真车上这里曾是 采纳=0 丢弃=4）
D/CarDesk/Media: 采纳 QQ 广播 method=update_state module=play boundPkg=
D/CarDesk/Media: 采纳 QQ 广播 method=update_song module=play boundPkg=
I/CarDesk/Media: QQ 广播合成曲目 title=测试歌名 artist=测试歌手 durationMs=200000 method=update_song positionMs=5000 playing=true
I/CarDesk/Media: 收到 QQ 歌词广播 boundPkg= lines=3 title=测试歌名 artist=测试歌手
D/CarDesk/Media: 歌词当前行 index=0 total=3 positionMs=5000 … lineText=这是第一句歌词

# ④ 方控 URI 通道能真正下发到 QQ 音乐
I/BroadcastReceiverCenterForThird: onReceive： uri:qqmusiccar://?action=20&m0=3
D/BroadcastReceiverCenterForThird: gotoNextByHtmlScheme：dataUrl is:action=20&m0=3
D/BroadcastReceiverCenterForThird: ACTION : 20
```

**真车 vs 新包**（同一份诊断包格式，A/B 对照）：

| 字段 | 4226（旧，真车） | 4227（新，模拟器） |
|---|---|---|
| `qqBroadcast` | 收到=4 **采纳=0 丢弃=4** | 收到=2 **采纳=2 丢弃=0** |
| `qqDoor` | （只有「已开」，看不出发没发） | 已开 **开门命令已发=1** |
| `bridge.controlTarget` | （没有这个字段） | `com.tencent.qqmusiccar` |
| `bridge.sourceKind` | none | **broadcast** |

### 诊断包这一轮也补了几处

- **`qqBroadcastMethods: update_state×1, update_song×1`** ——
  原来只记**最后一条**广播的原文，真车上那一条是
  `update_app_foreground`（QQ 说「我在不在前台」，**本来就不含歌名**），
  看完只会得出「QQ 根本没发过曲目广播」的错误结论。计数表一眼就能看出 QQ 发过什么。
- **`qqDoor(允许对外发送): 已开 开门命令已发=N`** ——
  原来只分「已开/未开」，看不出**我们到底发没发过那条开门命令**。
- **`bridge.controlTarget: com.tencent.qqmusiccar`** ——
  新增的兜底方控目标，看得见方控该发给谁。
- **各音乐 App 的版本号**（`com.tencent.qqmusiccar 版本: 1.9.8.22 (28)`）——
  协议对不对得上全靠版本号对齐，不用每次都问用户「你车上是哪个版本」。

### 怎么验证

真车上装 4227 后出一份诊断包，看这几行：

```
qqBroadcast: 收到=N 采纳=N 丢弃=N      ← 采纳数应该 > 0
qqBroadcastMethods: update_song×N, update_state×N, update_lyric×N
qqDoor(允许对外发送): 已开 开门命令已发=N
bridge.registered: true                ← 原车认了
bridge.controlTarget: com.tencent.qqmusiccar
bridge.callbackCount: N                ← 原车把方控交给我们了
qqControl(已发方控命令): N             ← 我们真的把命令发给 QQ 音乐了
```

### 已知限制

- **模拟器上 QQ 音乐没登录**，所以「真实播放时的歌名/歌词」是用**协议探针**
  （`am broadcast` 按 QQ 的真实协议发 `update_song`）验的，
  歌名/歌手/时长/进度/播放态/歌词**全部实测到位**（见 `emu-verify/y128/`），
  但「QQ 音乐自己在播放时发什么」这一段仍需真车登录后复验。
- **原车媒体桥在模拟器上没有 ecarx SDK**（`原车媒体接入失败：NameNotFoundException`），
  所以「注册 → 原车交方控 → 我们派发」这一段**只能在真车上验**。
  模拟器能验的是它前面的一切：开门、采纳、歌名/歌词、以及方控 URI 通道本身。
- 诊断包里的 `bridge.bound` 在模拟器上是 `false`（没有 ecarx 服务），
  真车上是 `true`；这两者不要混着看。

## 导航页「画中画全屏按钮」改成悬浮叠在画中画上（第 129 轮）

用户反馈（截图红框指认）：**画中画左侧那条全屏按钮，不要单独占一条，
要悬浮到画中画界面上**。

### 旧实现：按钮挂在导航页本体上，等于独占一条留白

```kotlin
// NavPage.buildNavSplit() —— 旧代码
addView(split, LayoutParams(36, 110, Gravity.START or Gravity.CENTER_VERTICAL))
```

`split` 是画中画那个 `›` 玻璃按钮（点一下把画中画切全屏）。它挂在
**导航页根 FrameLayout** 的 `START` 位置 —— 也就是**压在 navRow 左边
那 44px 留白**（`NAV_SIDE_MARGIN`）上，而画中画卡片从 x=44 才开始。
结果是：

- 屏幕最左边长期空着一条 44px 的竖玻璃条，**这条留白只为这一个按钮而留**；
- 按钮和画中画画面**分属两块**，视觉上对不齐；
- 画中画**左右换位**时（`applySidesSwapped` 靠 `navRow` 切 RTL 实现），
  按钮纹丝不动留在屏幕左边 → 跑到画中画的**对面**去了。

### 新实现：挂进画中画承载面，叠在画面左边缘

```kotlin
// NavPage.buildNavSplit() —— 现在
mapCard.addView(split, navSplitParams())
mapCard.bringChildToFront(split)
```

`mapCard` 就是**画中画承载面**（地图卡）。按钮进了它里面，
`Gravity.START or Gravity.CENTER_VERTICAL` → 贴**画中画自己的左边缘**、垂直居中：

- **不再独占任何留白**（左侧那条 44px 变成正常的设计边距，
  和右侧对称）；
- **换位时跟着画面走** —— 承载面挪到右边，按钮就跟着挪到右边，
  永远贴在画面自己的左边缘；
- **位置与全屏态完全一致** —— 全屏时按钮被提到桌面根容器上，
  同样在左侧垂直居中，用户不用记两个位置。

> `PipDividerView`（12×144 的玻璃 chip，压在「地图卡 ↔ 右槽」缝上）
> 是**另一个**控件：单击 = 主画全屏、长按 = 拖比例。
> 本轮改的是 `navSplit`，两者别混。

### 两态参数收敛到一个纯逻辑对象

「悬浮在画中画上」和「全屏浮在画面上」这两态，按钮的宽 / 高 / gravity
**必须逐字相同** —— 两处各写一份字面量的话，改了一处忘了另一处，
用户就会看到「点全屏后按钮跑位了」。

所以抽了 `ui/pip/NavSplitPlacement.kt`（纯逻辑，不碰 View）：

```kotlin
object NavSplitPlacement {
    const val WIDTH = 36
    const val HEIGHT = 110
    const val GRAVITY = Gravity.START or Gravity.CENTER_VERTICAL

    const val HOST_OVERLAID = HOST_PIP_CARD      // 悬浮态宿主 = 画中画承载面
    const val HOST_FULLSCREEN = HOST_DESKTOP_ROOT // 全屏态宿主 = 桌面根容器

    fun host(fullscreen: Boolean): String = if (fullscreen) HOST_FULLSCREEN else HOST_OVERLAID
    fun gravity(fullscreen: Boolean): Int = GRAVITY   // 两态同一个值
    const val PIP_CARD_LAYOUT_DIRECTION = PipSides.DIR_LTR
}
```

`NavPage`（悬浮态）和 `LauncherView.enterPipFullscreen`（全屏态）
都从这里取参数 —— 两态跑位在编译期就不可能发生。

`PIP_CARD_LAYOUT_DIRECTION` 钉死 **LTR** 是另一条要害：
画中画左右换位是靠**外层** `navRow` 切 RTL 实现的，承载面自己保持 LTR
（只换位置、不镜像内容）。承载面一旦被镜像，按钮（`Gravity.START`）
就会跑到画中画的**右边**去 —— 跟画面错位。

### 怎么验证

`NavSplitOverlayTest`（Robolectric，真实跑一遍布局）直接断言：

```
按钮挂在画中画承载面上而不是导航页本体
按钮尺寸与定位参数沿用原值
按钮压在画中画画面左边缘而不是画外
全屏往返后按钮回到画中画承载面
全屏态按钮与悬浮态用同一套定位参数
画中画左右换位时按钮跟着画面走
```

**反向验证 6 项全部变红**（`tools/verify_y129_reverse.sh`，可复跑）：

| 反向 | 改法 | 红灯 |
|---|---|---|
| 1 | `buildNavSplit` 挂回导航页本体 | 3 |
| 2 | `attachNavSplit` 放回导航页本体 | 1 |
| 3 | `HOST_OVERLAID` 改回 `navPage` | 1 |
| 4 | 全屏态 gravity 与悬浮态不一致 | 2 |
| 5 | 承载面方向改成 RTL | 2 |
| 6 | `LauncherView` 全屏态硬编码 gravity | 1 |

还原后自检 0 红灯。模拟器端到端见 `emu-verify/y129/`。

### 已知限制

- 按钮会**遮住画中画画面左边缘 36×110 的一块**，并吃掉该区域的触摸。
  地图应用左侧竖直居中处通常是空白（实测高德 9.5 该位置是菜单区文字），
  风险可控，但**真车上没验过**。
- 高德车机版是**系统弹窗**（登录/升级提示），画中画里弹出来时
  **会溢出画中画卡**盖到右边的音乐卡上 —— 这是 `ActivityView` 的既有行为，
  本轮没碰。
- 取证时撞见「画中画里高德偶发全黑」，查过一轮**确认不是本轮改坏的**：
  本轮对画中画承载路径**一处没改**（`mapCard.layoutDirection` 的新旧值都是 0；
  全屏按钮的宽/高/gravity 新旧值分别是 36 / 110 / `START|CENTER_VERTICAL`；
  `navSplit` 是个纯 `FrameLayout`，不参与 `ActivityView`），
  而且**同一份 4228 代码**两次跑结果不同（高德进程是遗留的就黑，
  `am force-stop` 高德后重跑就正常）→ 非确定性。
  logcat 显示全屏往返时 `relaunchAfterResize()` 会**杀掉重启**高德的
  `UsbFillActivity` / `MainMapActivity`，黑屏就是这个重绘窗口。
  **既有问题，本轮未修**，在此记录以免下一轮再被它绊住。

---

## 第130轮：修复画中画黑屏

> 用户诉求：「修复黑屏问题」。
> 现象：画中画黑屏，要「全屏一次再还原画中画」才看得到。

### 根因：黑屏**从来没被桌面发现过**

第129轮排查撞见黑屏，查下来是**一整条链子断了**：

| # | 断点 | 说明 |
|---|---|---|
| 1 | **探针从来没工作过** | 取不到 surface 时旧代码是**静默 return**，一个字都不留 → 诊断包里一条采样日志都没有 |
| 2 | **黑屏因此永远不被发现** | 自愈逻辑从来没被触发过 |
| 3 | **自愈只有一次机会** | `blackHealDone: Boolean` → 自愈一次不奏效就**永久黑屏** |
| 4 | **自愈动作只有一档** | 只有「重发一次启动」—— task 真挂在旧 VirtualDisplay 上时救不回来 |
| 5 | **看门狗多进程判据缺陷** | 只认主进程名 → 高德明明在发导航状态却被判「承载进程不在」→ 反复重发启动打断正常应用 |
| 6 | **诊断文案共用** | resize-relaunch / 自愈都写「画中画看门狗：…」 → 排障时分不清来源 |

### 关键发现：**像素采样根本看不见 ActivityView 的画面**

采样源从「ActivityView 的 SurfaceView surface」改成「窗口 + 限定矩形」之后采样**成功**了，
但读到的是 **maxLuma=0 / avgLuma=0** —— 而同一时刻屏幕上高德明明在渲染地图。

| 采样方式 | 结果 |
|---|---|
| `PixelCopy.request(window, srcRect, …)` | 成功，但恒为黑（maxLuma=0 / avgLuma=0 / brightPct=0） |
| `PixelCopy.request(surface, …)` | **result=3** = `ERROR_SOURCE_NO_DATA`，一帧都采不到 |

**原因**：`ActivityView` 内部是 `SurfaceView`，内容画在**独立的 SurfaceFlinger 图层**上；
窗口自己的缓冲在那个位置是**透明的洞**（SurfaceView 会在窗口上打洞）。
`PixelCopy` 拷窗口 = 拷窗口缓冲，**拷不到那个图层**。

> 于是「我们看不见应用画面」和「应用画面是黑的」在数据上**完全一样** ——
> 拿它当判据，实测**把正常的高德判成了黑屏并重启了它**。

### 修复：判据换成**权威信号**

跟 AOSP 官方 `VirtualDisplay Launcher` 示例判活的方式一致：

```
task.displayId == virtualDisplay.getDisplayId()
```

> **承载应用的 Activity 还挂在我们的 VirtualDisplay 上吗？**
> 不在 → 我们的 VirtualDisplay 上什么都没有 → **这就是黑屏的成因本身**。

| 文件 | 改动 |
|---|---|
| `ui/pip/PipBlackHeal.kt`（新） | 纯逻辑：判据 + **分级**自愈决策（重发启动 → 重建承载面 → 放弃）+ 冷却 + `isTaskOffDisplay()` |
| `ui/pip/PipSurface.kt` | `blackHealDone:Boolean` → `blackHealStage:Int`；新增 `rebuildContent()`；`isTaskOnOurDisplay()`；`isProcessAlive()` 改多进程判据；`relaunch(reason)` 带来源；看门狗加**前台闸** |
| `ui/embed/ActivityEmbed.kt` | `virtualDisplayIdOf()`、`displayIdOfTask()`、`windowOf()` |
| `ui/pip/PipFrameProbe.kt` | pending 超时复位；采样失败留痕；采样矩形限定到承载面并内缩；判黑加「亮像素占比」「整体很暗」；日志限频 |
| `core/HomeForeground.kt`（新） | 桌面是否在前台（`MainActivity.onResume/onPause` 维护） |

### 两个必须说清的坑

1. **`ActivityView` 里有两块「display」**：
   `mHost.mDisplay` 是**承载面自己所在的显示器**（我们桌面的 display 0），
   `mVirtualDisplay` 才是虚拟显示器。拿错 → **把正常画面判成「任务不在我们的 display 上」**（实测踩过）。
2. **自愈只能在桌面在前台时做**：
   用户点「全屏打开」把应用拉到全屏时，它的 task 确实从我们的 VirtualDisplay 搬走了；
   那时自愈会把应用**抢回画中画**，打断用户正在用的全屏应用（比黑屏更烦人）。

### 分级自愈

| 阶段 | 动作 | 冷却 |
|---|---|---|
| 0 | 重发一次启动（轻） | 8s |
| 1 | **重建承载面**（重：释放旧 VirtualDisplay 再干净重建） | 20s |
| 2 | **放弃**（绝不无限重启 —— 反复重启比一直黑更烦人） | — |

### 怎么验证

**模拟器端到端**（`emu-verify/y130/`）：

- 健康态：高德在画中画，60 秒内**自愈动作 = 0 次**（旧像素判据在这段时间里会误重启高德）；
- 故障注入（把 `virtualDisplayIdOf()` 故意取错）：
  判黑 → 10:53:27 重发启动 → 10:53:48 重建承载面 → 之后 **67 秒不再自愈**（放弃）；
  **自愈动作总次数 = 2**。

**单测**：全量 **1736 项全绿**（第129轮 1720 → +16）。
新增 `PipBlackHealTest`（判据 / 分级 / 冷却 / 放弃 / display 判定 12 项）。

**反向验证 9 项全部变红**（`tools/verify_y130_reverse.sh`，可复跑）：

| 反向 | 改法 | 红灯 |
|---|---|---|
| 1 | 去掉「任务不在我们的 display 上」这条权威判据 | 1 |
| 2 | 像素判黑去掉「必须先见过应用画面」这道闸 | 1 |
| 3 | 自愈不升级（永远停在「重发启动」） | 2 |
| 4 | 重建过还黑时不放弃（会无限重启） | 2 |
| 5 | 去掉冷却 | 1 |
| 6 | 第二级自愈的冷却不递增 | 1 |
| 7 | display 判据不再「判不准就当没黑」 | 1 |
| 8 | 像素判黑去掉「亮像素占比」 | 2 |
| 9 | 采样矩形不往里缩 | 2 |

还原后自检 0 红灯。

### 产物

`dist/CarDesk-platform-ecarx-cardesk-4229.apk` · 4229 / 1.0.1133-system ·
2263290 字节 · SHA-256 `965606aa6615b4b5f383d5cd477ab2a434003f26e39032d898e4e79303b460b7`

### 已知限制

- **真车（Android 9 / API 28）上 `PixelCopy` 的行为未验证** ——
  但**判黑已经不依赖像素**了，真车只影响诊断日志，不影响修复。
- `TaskInfo.displayId`（隐藏字段）与 `ActivityView.mVirtualDisplay` 字段名在 API 28 上需实机确认
  —— 取不到时判据退化成「不知道」（**不误判**），并在日志里留痕。
- **没有发 GitHub Release、没有传 R2**（等明确指示）。

---

## 第131轮：没打开播放器时，方控改为控制桌面默认选定的播放器

> 用户诉求：
> 「没打开 QQ 音乐或是其他第三方音乐播放器时，按播放按钮，默认打开的还是爱趣听，
> 能不能让方控控制我们桌面默认选定的播放器，而不是打开爱趣听。」

### 根因：按键**落进了空里**

方控在真车上走**原车媒体中心**的私有 Binder：

```
方向盘按键 → 原车 MediaCenter → 我们（OEM 媒体桥）→ 目标播放器
```

「目标是谁」这一层是对的（第128轮做的：有歌名用歌名来源，没歌名用**桌面选定的媒体源**）。
断的是**下一层** —— `mediaBridgeCommands.send()`：

```kotlin
// 修复前
if (c != null && c.packageName == source) { ...正常下发... }
// 目标播放器没在运行 → 没有它的 MediaSession → controller == null
Logx.i(LogTags.MEDIA, "OEM 方控回调已过期，忽略", ...)
return false
```

目标播放器**没在运行** → 没有会话 → **按键被直接丢掉** →
原车只好自己把它的在线音源（**爱趣听**）拉起来 → **用户看到的就是「打开爱趣听」**。

同一处毛病在「音乐卡播放键」那条路上也在：目标是「**记住的上次播放器**」（历史），
而不是「**桌面现在选定的播放器**」（用户当下的选择）——
真车诊断包实测就是这种错位（用户选了 QQ 音乐，上次播的是车机自带音源）。

### 改了什么

| 文件 | 改动 |
|---|---|
| `core/media/PlayTarget.kt`（新） | 纯逻辑：车机自带音源判定 / **播放目标解析** / 走新路还是旧路 / QQ 音乐车机版判定 |
| `core/media/MusicSessionTracker.kt` | ① 方控回调：**没有会话时把目标播放器拉起来**（不再丢弃按键）② `playWithoutSession()`：目标改用 `PlayTarget.resolve` ③ 新增 `selectedSourcePkg()` / `isPackageInstalled()` / `launchMusicApp()` |

**目标解析的优先级**（`PlayTarget.resolve`）：

| 优先级 | 来源 | 说明 |
|---|---|---|
| 1 | **卡片上当前选定的来源**（`design_music_source_v1`） | 桌面上一眼能看见的那个 |
| 2 | **设置页「默认媒体源」**（`default_music_source_v1`） | 桌面启动时锁定的那个 |
| 3 | **记住的上次播放器**（`last_music_package`） | **跳过车机自带音源**（用户不要它被打开） |
| 4 | **默认音乐应用列表** | 兜底（QQ音乐 → 爱趣听 → 酷我 → 网易云 → 汽水） |

**两条不能碰的线**：车机自带音源（爱趣听一类）**不由我们接管**
（原车收音机 / USB / 蓝牙有它们自己的 owner，见 `MediaBridgePolicy` 顶部的红线）；
判据不可靠（目标没装 / 没有目标）时**宁可不动**，不乱拉 App。

拉起来之后怎么控制：**QQ 音乐车机版**走它自己的三方协议
`qqmusiccar://?action=20&m0=N`（`action=20` 才会**真的开始播**，
而且它没在运行时会把 App 自己拉起来）；**其它播放器**先发一路**定向**
`MEDIA_BUTTON(PLAY)`，再把 App 拉起来。

### 怎么验证

**模拟器端到端**（`emu-verify/y131/`）：

| | 日志 | 结果 |
|---|---|---|
| 修复前 | `播放指令（无会话）：打开默认音乐应用 pkg=com.cardesk.ncprobe` | 目标是**历史**（上次播过谁） |
| 修复后 | `播放指令（无会话）：走桌面选定的媒体源 pkg=com.tencent.qqmusiccar remembered=com.cardesk.ncprobe`<br>`QQ 音乐方控命令 action=play ok=true` | 目标是**桌面选定的** QQ音乐；进程起来了；**爱趣听没被拉起** |
| 正式产物 4230 复验 | 同上（`y131-log-release-4230.txt`） | 前台 `com.tencent.qqmusiccar/.app.activity.AppStarterActivity`；爱趣听/网易云/汽水**全都没被拉起** |

**单测**：全量 **1750 项全绿**（第130轮 1736 → +14），新增 `PlayTargetTest`（14 项）。

**反向验证 9 项全部变红**（`tools/verify_y131_reverse.sh`，可复跑）：

| 反向 | 改法 | 红灯 |
|---|---|---|
| 1 | 让「记住的上次播放器」优先于「桌面选定的来源」（旧行为） | 4 |
| 2 | 不跳过车机自带音源（爱趣听又会被选中） | 1 |
| 3 | 目标解析不检查安装状态（会拉起没装的 App） | 1 |
| 4 | 车机自带音源也算我们接管（会去抢原车音源） | 2 |
| 5 | 车机自带音源判定失效（认不出爱趣听） | 4 |
| 6 | 去掉「设置页默认源」这一级 | 2 |
| 7 | 去掉「默认音乐应用列表」兜底 | 2 |
| 8 | 没有目标时也走「桌面选定媒体源」那条路 | 1 |
| 9 | QQ 音乐车机版认不出来（不走三方协议） | 1 |

还原后自检 0 红灯。

### 已知限制

- **原车方控那条路没能在模拟器上端到端跑** —— 原车媒体中心 SDK 模拟器上没有，
  真车上必须复验（诊断包看 `bridge.callbackCount` 有没有涨）。
- **原车桥的白名单里没有酷我 / 酷狗**（只有 QQ音乐车机版/手机版、网易云、汽水）——
  桌面选的是这两家时原车桥**会忽略**按键，要支持得先在真机上验证它们发不发会话。
- **没有发 GitHub Release、没有传 R2**（等明确指示）。

## 第132轮：启停开关接上执行端（之前它只存了个开关）

### 1. 之前是什么状态

设置 → 车控 → **启停控制** 那一页只有一个开关「开机后自动关闭启停」。
它做的事只有一件：把 `iss_keep_enabled` 写进 SharedPreferences。

**全仓没有任何代码读这个键** —— `git log -S issKeepEnabled` 只命中首次提交
`22d5b75`，也就是说从项目第一天起就**没有过执行端**。

现象：开关能点、能持久化、能写日志，**车机上的启停一点没变**。
那一页原本还有一句说明「开机后自动关闭启停的状态会保存到 iss_keep_enabled 配置项。」，
在第 97~99 轮的「设置文案精简」里被删掉了——它本来就在如实告诉用户「这只是存个配置项」。

按 `analysis/compare/cardesk-features.md` 自己的判据，这叫「仅设置项」。

### 2. 文档口径：这个功能**能写**

| 东西 | 值 | 出处 |
| --- | --- | --- |
| 功能号 | `537002240`（`0x20020100`） | `IVehicle.SETTING_FUNC_ENGINE_STOP_START` |
| 读 | `getPaSsActivation` | `CarFunctionValueImpl.java:429` |
| 写 | `setCbSsActivation` | `CarFunctionValueImpl.java:1247` |
| 值域 | `{0, 1}` | `docs/ecarx-adapterapi/data/value-domain.md:32` |
| 支持探针 | `isFunctionSupported` → `PaSsActivation.availability` | `CarFunctionSupportedUtil.java:356` |

参考 App（君君桌面）（`com.claunchercard` 的 `DriveModeController`）用的就是这个功能号：

- `:21` `FUNCTION_ENGINE_STOP_START = 537002240`
- `:273` `isIssOn()` → `getFunctionValue(...) == 1`
- `:511` `setIssEnabled(z)` → `setFunctionValue(..., z ? 1 : 0)`

**语义**：`1` = 启停开着，`0` = 启停关闭。

另一条看着很像的路走不通：驾驶模式里的
`IDriveMode.DM_FUNC_DM_CUSTOM_EN_START_STOP`（`0x22030E00`）在厂商 jar 里
**没有实现**（`func-map.csv` 里 0 行）。

### 3. 读侧有个坑：厂商的默认值是 1

`CarFunctionValueUtil.getMappedFunctionValue(PaSsActivation)`：

```java
if (pa == null || pa.data == null) {
    Logger.w(TAG, "pa is null, returning defaultValue:1");
    return 1;                       // ← 信号缺失时返回 1，不是错误哨兵
}
```

所以「读到 1」是**二义的**：可能确实开着，也可能根本**没这条信号**、
厂商拿默认值 1 兜住了。

`IssControl.State.isOn` 因此按三档判：

| raw | supported | 结论 |
| --- | --- | --- |
| 0 | 任意 | **确凿关闭**（信号缺失只会返回 1，能读到 0 就是真关了） |
| 1 | `true` | **确凿开启** |
| 1 | `false` / `null` | **判不了** |

页面上的状态行也跟着这个口径走：**判不了就说「启停状态读不到」**，
颜色用弱文字——不能把「读不到」显示成一个看着像「有结论」的绿字。

### 4. 实现

| 文件 | 改了什么 |
| --- | --- |
| `vehicle/IssControl.kt`（新） | 功能号常量、读（支持探针 + 原始值）、写、状态判定与文案 |
| `vehicle/VehicleController.kt` | `setIssEnabled(on, onResult)`（写完回读确认，最多等 1.2s）、`readIssState()` |
| `vehicle/MockVehicleGateway.kt` | 模拟车也有启停状态（默认开着），支持探针返回 `true` |
| `vehicle/VehicleLog.kt` | 日志里 `0x20020100` 显示为「发动机启停」（原来「未知属性」） |
| `ui/SettingsOverlay.kt` | 开关打开→**立刻**关掉启停；关掉→**不动车机**；加状态行与「立即关闭/开启启停」「刷新状态」 |
| `BootReceiver.kt` | 开机广播里按 `iss_keep_enabled` 应用一次（等通道就绪，最多 30s） |

**「关闭则不改动车机逻辑」是照副标做的**：开关关掉时只改本地开关，
不往车机写任何东西；想改回来用页面上的「立即开启启停」。

**开机那条为什么要自己 init 车控**：`VehicleController.init` 平时是 `LauncherView` 建的，
而 LauncherView 依附于 MainActivity —— Android 10+ 禁止后台启动 Activity，
开机广播里那次 `startActivity` **可能失败**。桌面起不来 = 车控通道永远不初始化
= 启停永远关不掉。所以 `BootReceiver` 主动 init 一次
（`init` 本身幂等，`if (initialized) return`，后面 LauncherView 再调一次没有副作用）。

### 5. 模拟器实测（API 28，`emulator-5558`）

证据：`emu-verify/y132/r132-logcat-iss.txt`、`emu-verify/y132/r132-iss-closed.png`、
`emu-verify/y132/r132-iss-page.png`、`emu-verify/y132/r132-iss-off-confirmed.png`；
逐条验证报告见 `emu-verify/y132/REPORT-y132.md`。

| # | 操作 | 期望 | 实测 |
| --- | --- | --- | --- |
| 1 | 打开「开机后自动关闭启停」 | 立刻写 `0` 并回读确认 | ✅ `设置变更 key=iss_keep_enabled from=false to=true` → `写入启停状态 func=0x20020100 value=0 target=关闭 ok=true` → `confirmed=true` → 回读 `raw=0 isOn=false` |
| 2 | 点「立即开启启停」 | 写 `1` 并回读确认 | ✅ `value=1 target=开启 ok=true` → `confirmed=true` → 回读 `raw=1 isOn=true` |
| 3 | 点「立即关闭启停」 | 写 `0` 并回读确认 | ✅ `value=0 target=关闭 ok=true` → 回读 `raw=0 isOn=false` |
| 4 | 关掉开关 | **只改本地开关，不碰车机** | ✅ 只有 `设置变更 key=iss_keep_enabled from=true to=false`，**无任何写入** |
| 5 | 开机广播（开关开） | 开机自动关一次启停 | ✅ `收到开机广播` → `初始化车控` → `写入启停状态 value=0 ok=true` → `开机自动关闭启停 ok=true detail=已关闭启停` |
| 6 | 开机广播（开关关） | 开机不碰车机 | ✅ 只有 `收到开机广播` / `开机广播已拉起桌面`，**无启停写入** |

**单测**：全量 **1765 项全绿**（第131轮 1750 → +15），新增
`vehicle/IssControlTest.kt`（15 项，含「读到 1 但信号缺失 ⇒ 判不了」这条）。

### 6. 已知限制（**真车还没验**）

- **模拟器验的是通道与逻辑，不是真车的启停行为** —— 真车能不能真的关掉启停、
  写进去有没有被整车状态机接受，要在真车上看这几行日志：
  `写入启停状态` / `启停写入已下发`（`confirmed` 是不是 `true`）/ `开机自动关闭启停`。
- **只写一次可能不够**：启停功能通常在**每次上电/点火后**由整车状态机复位，
  只在开机广播里写一次 `0`，用户中途熄火再点火后可能又被打开。
  稳妥做法是盯 `PaSsActivationChangedCallback`（jar 里有），
  状态被改回 `1` 时再补写一次 —— 这一版**没做**。
- **`confirmed=false` 不等于失败**：厂商写接口返回 `true` 只代表信号收下了，
  回读确认不了时页面会明说「已下发启停指令，但这台车机回读没确认」，
  不谎报成功。
- **`ENGINE_START_STOP_STATE` 传感器事件（`0x00201700`）在 jar 里未实现**，
  所以「当前启停运行状态」只能靠 `getPaSsActivation` 这条设置侧的路。

## 第133轮：仪表盘歌词接上执行端（副屏投射，参考 UNI 桌面的实现逻辑）

### 1. 之前是什么状态

设置 → 仪表歌词 → **布局参数对话框**里那一片「仪表 Overlay 歌词 / 模块宽度 /
仪表背景透明度 / 主歌词字号 / 副歌词字号 / 歌词颜色」，**全是只写偏好、不执行的滑杆**：

- 勾上「仪表 Overlay 歌词」只往 SharedPreferences 写一个 `desk_cluster_overlay = true`；
- 全仓**没有第二个窗口**，也就没有任何东西能被加到副屏上；
- `desk_overlay_x` / `desk_overlay_y`（水平 / 垂直位置）这两个键
  **连设置 UI 都没有**，只有写入方没有读取方。

按 `analysis/compare/cardesk-features.md` 自己的判据，这叫「仅设置项」（条目 S03 / R04）。

### 2. 参考对象：UNI 桌面 0.7.10 的「仪表歌词投屏」

上游 `uni-launcher` 在 `ce11799 feat(media): 方控接管、仪表歌词投屏、词级歌词时间轴`
里带进了完整实现，本轮按它的口径移植：

| UNI 的东西 | cardesk 这边的落点 |
| --- | --- |
| `ClusterPanelGeometry.java`（纯排版数学） | `cluster/ClusterPanelGeometry.kt`（纯 Kotlin，可单测） |
| `DisplayPicker.java`（选屏，宁可判不了也不猜） | `cluster/DisplayPicker.kt` |
| `ClusterLyricsView.java`（Canvas 面板） | `cluster/ClusterLyricsView.kt` |
| `ClusterLyricsService.java`（`createDisplayContext` + `addView`） | `cluster/ClusterLyricsService.kt` |
| `LyricCast.java`（进程内广播牌喂数） | **不复用**：cardesk 的 `MusicController` 是同进程单例，服务直接读 |
| `CLUSTER_LYRICS_CAST.md`（方案与验证顺序） | 本节 + `EMULATOR-VERIFY.md` H11 |

### 3. 它到底是怎么做到的（两个坑）

不是「把歌词发给仪表」，也不需要车厂接口或投屏协议 —— 做法是
**把一个窗口加到那块屏自己的窗口管理器上**：

1. `createDisplayContext(display)` 给出**那块屏**的 Context，它的 `WINDOW_SERVICE`
   属于那块屏；
2. 往那个 WindowManager 里 `addView(TYPE_APPLICATION_OVERLAY)`，
   窗口就落在那块屏上。

两个坑别的方案基本都死在这两条上：

- **必须用 `createDisplayContext`** —— 用主 Context 加窗口，窗口永远落在主屏上，
  `params.x/y` 怎么调都只是在主屏上乱跑；
- **必须查那块屏自己的 density** —— 主屏 240 dpi、仪表 320 dpi 很常见，
  用错密度字号会差三成。

窗口 flags（`ClusterLyricsService.kt:565` 起）：
`FLAG_NOT_FOCUSABLE | NOT_TOUCHABLE | NOT_TOUCH_MODAL | LAYOUT_IN_SCREEN |
LAYOUT_NO_LIMITS | HARDWARE_ACCELERATED`，`PixelFormat.TRANSLUCENT`，
`gravity = TOP|START` 绝对定位。`FLAG_NOT_TOUCHABLE` 必须有，否则会**吞掉副屏上的点击**。

#### 为什么这个服务是**前台**服务

这个功能的存在意义恰恰是「用户去别的 App 听歌时，仪表上还有词」——
而用户一去音乐 App，桌面（HOME）就转入后台。

Android 8+ 的后台执行限制：前台应用起的**普通** Service，
在应用转入后台约一分钟后会被系统**停掉**。
也就是说：开着音乐听两首歌，仪表上的歌词自己就没了。

所以走 `startForegroundService` + `startForeground`（低优先级 channel，不出声、不弹横幅），
通知里带一个「停止投射」的动作（点了会把开关也关掉）。
实测 `dumpsys activity services` 里是
`isForeground=true foregroundId=754 foregroundNoti=Notification(channel=cardesk_cluster_lyrics pri=0 …)`。

### 4. 改了什么

#### 4.1 新增 5 个文件

| 文件 | 作用 |
| --- | --- |
| `cluster/ClusterPanelGeometry.kt` | 纯排版数学：宽 / 高 / 左上角（面板中心 = 屏幕百分比） |
| `cluster/DisplayPicker.kt` | 选屏：id 精确命中 → 名字唯一命中 → 否则**不投** |
| `cluster/ClusterLyricSnapshot.kt` | 不可变快照（三行文字 + 擦除比例），面板每帧只读它 |
| `cluster/ClusterLyricsView.kt` | Canvas 面板，胶囊 / 纯文字 / 发光三种样式 |
| `cluster/ClusterLyricsService.kt` | 加窗 / 断窗 / 重连 / 喂数 / 退避重试 |

#### 4.2 新增偏好与日志

- `Prefs.kt:803-836`：`clusterDisplayId` / `clusterDisplayName` / `clusterRows` / `clusterStyle`
  （`desk_cluster_display_id` / `desk_cluster_display_name` / `desk_cluster_rows` / `desk_cluster_style`）；
- `LogTags.kt:66` 新增 `CLUSTER = "CarDesk/Cluster"`，并加进 `ALL`；
- `AndroidManifest.xml:124` 注册 `.cluster.ClusterLyricsService`（`exported=false`）。

#### 4.3 设置页接上执行端（`ui/dialogs/ExtraDialogs.kt`）

`DesktopLayoutDialog` 原来只有几个只写偏好的滑杆，现在补齐了最后一公里：

| 新增 | 位置 | 说明 |
| --- | --- | --- |
| 投射目标 | `:508-519` | 列出这台车机**实际暴露给应用**的屏，点一块就选中；列表为空 ⇒ 直接把「没暴露副屏」说给用户听 |
| 投射状态 | `:520-527` | 服务最后一次结果：成功 / 失败原因 / 目标屏 / 当前曲目 / 歌词行数 |
| 水平 / 垂直位置 | `:530-541` | 这两个偏好键以前**根本没有 UI**，这里补上滑杆 |
| 显示行数 | `:542-548` | 1 / 2 / 3 行 |
| 面板样式 | `:549-573` | 胶囊 / 纯文字 / 发光 |

**改完立刻生效**：开关一变就 `ClusterLyricsService.apply()`（起 / 停服务），
滑杆拖动时 `refresh()`（**原地**改窗口，不重连）；不用等「应用」按钮。

#### 4.3.1 默认**不画底板**（第133轮末按反馈调整）

原先「仪表背景透明度」默认 **70%**，投到仪表上就是一条半透明深灰带子
横在仪表中间 —— 仪表底色本来就是深的，再压一块底反而把车速 / 档位衬得更脏。

现在默认 **0%（不画底板）**：歌词**直接落在仪表画面上**，
「胶囊 / 纯文字 / 发光」的差别只在字本身（发光 = 字带同色光晕）。

- 偏好默认值改在 `Prefs.kt:758-774`（`desk_overlay_bg_alpha`，70 → 0）；
- 滑杆**保留**：仪表底色太亮、字看不清时，把它拉上去加一层底（设置页里写了说明）；
- 服务侧 `backgroundOf` 逻辑不变（alpha = 0 ⇒ `plateColor` 的 alpha 也是 0
  ⇒ `ClusterLyricsView.onDraw` 直接不画底板）。

#### 4.4 启动兜底

- `MainActivity.kt:288`：桌面起来后拉一次投射服务（`startLaunchServices` 里，
  排在 `MusicController.start` 之后，第一帧就能拿到歌名而不是「等待播放」）；
- `BootReceiver.kt:82-87`：开机广播再独立拉一次 ——
  Android 10+ 禁止后台启动 Activity，桌面起不来时 `MainActivity` 那条路走不通。

#### 4.5 「两边歌词不同源」这个坑从根上避开了

UNI 那边踩过：桌面卡片读 LRC、面板读 KRC，同一首歌在两处显示成两回事。

cardesk 这边**不复用 UNI 的 `LyricCast` 广播牌**：面板与桌面卡片读的是**同一个**
`MusicController`，擦除高亮也**直接调用桌面卡片那支画笔**
（`LyricWipeText.draw`，`ClusterLyricsView.kt:260-281`），
比例来自同一个 `LyricTimeline.wipeFraction` —— 所以两边的擦除边界**逐像素一致**。

### 5. 三个「刻意保守」的决定

1. **主屏 id ≤ 0 永远不是目标**（`DisplayPicker.kt:66-70`）。
   单屏设备上主屏 id 是 `0`，名字一旦被存下来，按名字匹配就会把主屏当成仪表，
   于是歌词窗口盖在桌面自己身上：整条半透明底把桌面压暗，字全糊了。
2. **名字不唯一 ⇒ 判不了，不投**（`DisplayPicker.kt:60-73`）。
   车机上的副屏不止一块（仪表 / 副驾屏 / 后退像屏 / HUD）。
   猜错的后果不是「歌词没显示」，而是**把歌词糊到副驾屏或倒车影像上** —— 那比不做还糟。
3. **屏增删改都重新按存下来的身份解析**（不信旧 id）：id 命中 → 名字唯一命中 → 不投。

### 6. 模拟器实测

证据：`emu-verify/y133/r133-cluster-cast.png`（**副屏抓回来的真实画面**）、
`emu-verify/y133/r133-cluster-settings.png`、`emu-verify/y133/r133-cluster-settings-on.png`、
`emu-verify/y133/r133-logcat-cluster.txt`、`emu-verify/y133/r133-cluster-no-plate.png`；
逐条验证报告见 `emu-verify/y133/REPORT-y133.md`。
离屏渲染（`preview/` 不入库，本机产物）：
`preview/30-cluster-pill.png`、`preview/31-cluster-plain.png`、
`preview/32-cluster-glow.png`、`preview/33-cluster-empty.png`、`preview/34-cluster-settings.png`。

| # | 操作 | 期望 | 实测 |
| --- | --- | --- | --- |
| 1 | instrumented：造一块 1920×720@240dpi 的副屏，投过去 | 窗口挂上 + 画上东西 | ✅ 报告变 `已投射 · CarDesk 测试仪表 · ID 1`；抓回来的画面里有面板（`r133-cluster-cast.png`） |
| 2 | 设置页打开开关 | 服务立刻起来 | ✅ `CarDesk/Cluster: 没有可投的目标屏 reason=service-created displays=0 … cause=no-secondary-display` |
| 3 | 设置页「投射状态」 | 说清楚为什么没投出来 | ✅ 状态行显示「这台车机没有向应用暴露副屏（仪表可能由独立 SoC 驱动）」 |
| 4 | 单屏模拟器（只有主屏） | **不往主屏上投** | ✅ `displays=0`；主屏上没有任何歌词窗口 |

**instrumented 测试**：`app/src/androidTest/java/com/cardesk/launcher/cluster/ClusterLyricsCastTest.kt`。
这是本项目**第一个 instrumented 测试**（`app/src/androidTest/` 之前不存在），
原因是这个功能的要害全在 Android 框架侧（`createDisplayContext` / `addView` / 跨屏权限），
Robolectric 一样都模拟不出来。它用 `DisplayManager.createVirtualDisplay` **自己造一块副屏**
（自己拥有 ⇒ 窗口能挂上去），背后接 `ImageReader` 把那块屏的画面**抓回来** ——
抓到非全透明像素 ⇒ 歌词真的画上去了，不是「加了个空窗口」。

> 踩到的两个 API 坑：
> - `DisplayManager.createVirtualDisplay` 的**公开**签名是
>   `(name, w, h, dpi, surface, flags)` —— **surface 在 flags 前面**
>   （AOSP 内部那版是 flags 在前，公开 SDK 这版反过来）；
>   返回的 `android.hardware.display.VirtualDisplay` **不是** `android.view.Display` 的子类，
>   真正那块屏要从 `getDisplay()` 拿。
> - `VIRTUAL_DISPLAY_FLAG_PUBLIC` 会被系统当成「屏幕共享 / 投屏」，
>   要 `CAPTURE_VIDEO_OUTPUT` 权限（普通应用拿不到）→ 直接抛 `SecurityException`。
>   测试里用 flags = 0 的**私有**虚拟屏：一样出现在自己的 `getDisplays()` 里，一样能挂窗口。

**单测**：全量 **1814 项全绿**（第132轮 1765 → +49），新增
`cluster/ClusterPanelGeometryTest.kt`（19 项）、
`cluster/DisplayPickerTest.kt`（15 项）、
`cluster/ClusterLyricSnapshotTest.kt`（11 项）、
`cluster/ClusterLyricsRenderTest.kt`（3 项，渲染 PNG）。

### 7. 渲染时抓出来并修掉的两个 bug

离屏渲染（`preview/30-cluster-pill.png`）一上来就暴露了真问题：

1. **三行歌词重叠成一团**。
   原来基线算的是 `top = 行号 × (本行字号 × 行距)`：
   第 2 行（大字）基线 = `1 × 大字行高 + 大字行高 × 0.72 = 117`，
   第 3 行（小字）基线 = `2 × 小字行高 + 小字行高 × 0.72 = 119` —— 两行几乎重合。
   根因：第 3 行的起点用**小字**的行高去乘行号，但第 2 行实际占掉的是**大字**的高度，
   两套口径。现在改成**逐行累加**（`ClusterLyricsView.kt:172-188`、`:361`）。
2. **「发光」样式和「胶囊」一模一样** —— 原来根本没实现。
   现在用 `Paint.setShadowLayer` 给字加同色光晕（`ClusterLyricsView.kt:309-322`）。

另外空态（「等待播放」等）原来贴在底板**上沿**，看着像面板画歪了，改成垂直居中。

### 8. 已知限制（**真车还没验**）

- **能不能在真车上生效，取决于车机是否向应用暴露副屏**。
  真车日志包（`cardesk-logs-20261106-085332.zip`）里**没有 `dumpsys display`**，
  `ICarInfo.getPresentationDisplay(int)` 在 jar 里**返回 null（是桩）**，
  所以目前**无法证明**这台领克 05 把副屏开放给了第三方应用。
  仪表很可能由**独立 SoC / 总线**驱动 —— 那样第三方窗口画不上去，
  **这不是设置问题**。设置页会直接这么说，不会装作能投。
- **一旦能枚举到副屏，权限仍可能拦一道**：往**别人的**私有屏加窗口会被系统拒
  （`SecurityException`），这时状态行会显示「投射失败 · …」并按指数退避重试。
- **本版没有做「启停变更监听补写」那种持续跟随**（那是第132轮启停的遗留问题，
  与本轮无关）。
- **歌词来源仍是 `MusicController`**：面板只显示桌面卡片**正在显示的那一份**，
  桌面卡片没有歌词时面板也没有。
- **位置滑杆是百分比**，不是像素；目标屏分辨率变了，位置会按比例跟着变。

### 9. 产物

| 项 | 值 |
|---|---|
| 文件 | `dist/CarDesk-platform-ecarx-cardesk-4231.apk` |
| 包名 | `com.ecarx.cardesk`（system 变体，与真车同包名） |
| versionCode / versionName | 4231 / 1.0.1135-system |
| 大小 | 2 306 709 字节 |
| SHA-256 | `e228d78deae0c1709519b844a7040110372b92725900ec8e9bf01e827b340e7f` |
| 签名 | AOSP platform（SHA-256 `c8a2e9bc…92ab8`） |
| 单测 | 1814 项全绿（`tools/release.sh` 出包时一并跑的） |

---

## 第134轮：接管方控后仪表没有图片（桥没实现 `getArtwork`）

> 用户诉求：
> 「目前接管方控之后，仪表只有歌名和歌手，没有图片；
> 车机默认的爱趣听是有图片的，试着修复这个问题。」

### 根因：原车问我们要封面，我们没接

`carmedia/MultiMedia.apk` 里带着**完整的原车 `com.ecarx.sdk.mediacenter` SDK**，
反编译后接口长这样：

```java
// IMediaInfo / IMusicPlaybackInfo / 仪表侧 IMediaInteraction.IMedia 都有这一项
Uri getArtwork();      // ← 仪表渲染图片的依据
```

原车自己填这个 Uri 的口径（`MediaCenterHelper.D(MediaMetadataCompat, String)`）：

```
DISPLAY_ICON_URI → ART_URI → ALBUM_ART_URI
（ART_URI 是本地裸路径时文件存在才 Uri.fromFile；都没有时给 Uri.parse("")）
```

而我们的桥的代理**只实现了 `getTitle` / `getArtist` / `getAlbum` / `getDuration`**，
`getArtwork` 落到 `else -> defaultValue(...)` → **返回 null** → 仪表拿不到封面。

> **歌名歌手有、偏偏图没有，恰恰证明桥是通的** —— 缺的只有封面这一项。
> 参考实现 **UNI 桌面的桥也是同样漏的**，这是整条传承链上的共同缺口。

### 改了什么

| 文件 | 改动 |
|---|---|
| `core/media/BridgeArtwork.kt`（新，纯逻辑） | 封面 Uri 取值优先级（**与原车一致**）/ 合法性 / 本地路径判定 / `getArtwork` getter 映射 / 变化判定 / 歌的 key |
| `core/media/ArtworkProvider.kt`（新） | `ContentProvider`：把我们的封面位图暴露成仪表读得到的 `content://` Uri |
| `core/media/OfficialMediaBridge.kt` | ① `Sample` 加 `artwork`/`songKey` ② 按原车口径从会话 metadata 取封面 ③ **实现 `getArtwork`/`getNextArtwork`/`getPreviousArtwork`** ④ 推给原车的指纹**含封面** ⑤ 按歌 key 的封面缓存 + 限频 |
| `core/media/MusicController.kt` | 封面到手时推给桥（补齐「没有会话」的来源） |
| `core/media/MediaBridgePolicy.kt` | 诊断加 `artwork` / `artworkSource` |

**封面 Uri 的来源**：① 会话 metadata（与原车逐字一致的口径）→ ② **我们自己的封面**
（`CoverLoader` 搜到的网络图 / 会话自带的位图）。
第 ② 条是必须的 —— **QQ 音乐车机版不发布会话**，它的广播里也没有封面字段
（实测 apk：只有 `key_title`/`key_artist`/`key_album`/`key_is_fav`）。

**为什么用 `content://` 而不是 `file://`**：封面文件在我们的私有目录，
别的进程能不能读取决于 uid；`content://` 由我们的进程替别人读，与对方 uid 无关。

### 三个踩过并修掉的坑

1. **authority 写死** → 调试包与系统包抢同一个 authority，表现为「provider 在但永远读不到文件」。
   改成 `${applicationId}.artwork` + 代码里 `context.packageName` 现算。
2. **硬件位图不能直接压缩** —— `MediaMetadata.getBitmap()` 在 API 26+ 是 `Config.HARDWARE`，
   `compress` 直接抛异常被吞掉 → 这一路静默失败。先 `copy(ARGB_8888)` 再压。
3. **日志被刷爆** —— 会话每 200ms~2.5s 推一次 metadata。改成**只在变化时记**。

### 验证（`emu-verify/y134/`）

| | 证据 | 结果 |
|---|---|---|
| 封面链路 | `y134-log-artwork.txt` | 会话 → 封面搜索（500×500）→ 落盘 → `content://` Uri → 缓存命中 |
| provider 跨进程吐图 | `adb shell content read`（**另一个 uid**） | 读回 47 773 字节 JPEG，**SHA-256 与磁盘文件逐字节一致** |
| 桌面卡片 | `y134-01-desktop-cover.png` | 卡片与 Dock 都是这张封面 |

**单测**：全量 **1841 项全绿**（新增 `BridgeArtworkTest` 21 + `ArtworkStoreTest` 6）。

**反向验证 11 项全部变红**（`tools/verify_y134_reverse.sh`，可复跑）：

| 反向 | 改法 | 红灯 |
|---|---|---|
| 1 | 认不出 `getArtwork`（回到「压根没实现」） | 1 |
| 2 | 取值顺序颠倒（`ART_URI` 压过 `DISPLAY_ICON_URI`） | 2 |
| 3 | 不认 `content://` | 6 |
| 4 | 认 `android.resource://`（跨进程读不到） | 3 |
| 5 | 本地路径文件不存在也当有图 | 1 |
| 6 | 封面变了不重推 | 1 |
| 7 | `sanitize` 放过 `..`（目录穿越） | 1 |
| 8 | `sanitize` 放过 `/` | 3 |
| 9 | authority 写死 | 1 |
| 10 | 歌的 key 恒为空 | 2 |
| 11 | 代理里把封面应答改成 `null`（接线检查） | 1 |

### 已知限制

- **「仪表真的把图画出来了」这段模拟器上验不了** —— 模拟器没有原车
  `ecarx.xsf.mediacenter` 服务，也没有仪表（DIM）。**必须真车复验**：
  装包后放一首歌看仪表有没有图；诊断包看 `bridge.artwork` 与 `bridge.artworkSource`。
- `getNextArtwork` / `getPreviousArtwork` 仍返回 `null` —— 与原车 `MusicPlaybackInfo` 一致。
- **没有发 GitHub Release、没有传 R2**（等明确指示）。

### 产物

| 项 | 值 |
|---|---|
| 文件 | `dist/CarDesk-platform-ecarx-cardesk-4232.apk` |
| versionCode / versionName | 4232 / 1.0.1136-system |
| 大小 | 2 315 549 字节 |
| SHA-256 | `27681da7b5be95d10934ebd316bd11284ade1dbcd6291ad3649d4251aba8c118` |
| 签名 | AOSP platform（SHA-256 `c8a2e9bc…92ab8`） |
| 单测 | 1841 项全绿（`tools/release.sh` 出包时一并跑的） |

## 第135轮：自动切换驾驶模式接上执行端 + 没开播放器时方控不再唤起爱趣听

> 用户诉求（4231 版真车诊断包）：
> 1. 「自动切换驾驶模式功能无效」；
> 2. 「在没打开播放器的情况下，方控默认唤起的还是原车的爱趣听」。

### 根因 1：这个开关**只存不执行**

设置页「启动切换」页里的「自动切换驾驶模式」=
`Prefs.startSwitchEnabled`（`start_switch_enabled`）+
`Prefs.startSwitchMode`（`start_switch_mode`）。

`git log -S start_switch_enabled` **只命中首次提交 `22d5b75`** ——
全仓**只有 `SettingsOverlay` 在写，没有任何执行端**。
典型的「设置项存下来了，但没人读」。

> 别和第132轮接上执行端的**另一个**开关搞混：
> 那是 `iss_keep_enabled`（发动机启停，功能号 `537002240` / `0x20020100`），
> 和驾驶模式（`570491136` / `0x22010100`）是两条完全不同的功能。

顺带发现**写入路径也不对**：参考 App（君君桌面）写驾驶模式的降级顺序是
（反编译 `com/claunchercard/manager/DriveModeController.writeDriveModeAdaptApi`）：

```
setFunctionValue(FUNCTION_DRIVE_MODE_SELECT, modeId)   // 两参，成功就收工
  ↓ 失败
trySetDriveModeWithZone(f, 2, modeId)                  // 三参 zone=2
  ↓ 失败
trySetDriveModeWithZone(f, 1, modeId)                  // 三参 zone=1
```

我们的 `VehicleGateway.writeFunction` **只有两参一条路** ——
固件不提供两参重载时直接失败，参考 App（君君桌面）却能靠 zone 兜底写成功。

### 根因 2：桥注册上了，但从**没 claim** 过

诊断包（4231）实测：

```
bridge.registered: true          bridge.playClaimed: false
bridge.callbackCount: 0          bridge.sourceType: -1
bridge.controlTarget: com.tencent.qqmusiccar     <- 第131轮的目标修复已生效
bridge.reason: 已注册，等待原车接受播放信息
```

所有日志文件里「OEM 方控回调」**0 条**。

原因在 `MediaBridgePolicy.shouldAcquire`：
**只有真的开始播放、且是刚刚开始播 / 换了来源**时才抢播放权
（`playing && (stateChanged || sourceChanged)`）。
于是「没打开播放器」时：

1. 没有样本 → 不 claim → 原车认为当前音源还是爱趣听；
2. 原车不把方向盘方控交给我们 → **我们连回调都收不到**；
3. 按键落进空里 → 原车只好自己把爱趣听拉起来 → **用户看到「打开爱趣听」**。

第131轮补的「收到回调后拉起桌面选定播放器」那段代码**根本没机会执行**。

**把主体分清楚，根因就完全闭合了**：

| 主体 | 包名 / 版本 | 身份 | 与 MediaCenter 的关系 |
|---|---|---|---|
| **参考 App（君君桌面）** | `com.ecarx.qq.tool`（`app_name` = 君君桌面；反编译产物 `desktop.apk` / `1.0.20273116-session-desktop`、`1.0.01257-lynkco-tool-pro`） | **第三方**桌面（平台签名预装） | 自带 `SessionBridge` 桥 SDK |
| **UNI 工程** | `xyz.i4o.uni.launcher` | 第三方参考工程 | 我们的 `OfficialMediaBridge` 是从它的移植（第87轮） |

> **这里根本没有「官方桌面」**（第135轮末修正）：
> 反编译产物的 manifest `package` 是 `com.ecarx.qq.tool`，
> 而 `com.claunchercard.manager.*`（`DesignSettings`、`MusicManager`、`DriveModeController`…）
> 是**同一个 APK 内部**的 Java 包名 —— **不是另一个 App**。
> `inner_dec` / `outer_dec` 两个产物的 manifest package 都是 `com.ecarx.qq.tool`，
> `app_name` 都是「君君桌面」；`outer_dec` 只是被 `com.yqsh.protector.shell` 加固过的同款构建。

关键的一条：**君君桌面那个「桥接开关」默认是关的**
（`SessionBridge.isBridgeEnabled` 读 `bridge_control/enabled`，默认 **false**），
而它的设置页原文与我们一字不差：

```java
// DesignSettings.java:515（君君桌面内部包 com.claunchercard.manager）
toggle(linearLayout, "桥接开关", "连接车机仪表与方控通道", SessionBridge.isBridgeEnabled(host), ...);
// → lambda$buildPage$5 → SessionBridge.setBridgeEnabled(host, z3)
```

```kotlin
// SettingsOverlay.pageCluster()（我们）
switchRow("桥接开关", prefs.flag(KEY_BRIDGE_ENABLED), "连接车机仪表与方控通道") { ... }
```

**所以「方控唤起爱趣听」不是谁的 bug，是「都没 claim」**：
君君桌面那个开关默认关 → 它默认不 claim → MediaCenter 的当前音源一直是原车音源；
我们注册上了但也从不 claim → 原车同样不认我们 → 方控自然落回爱趣听。

对照那条被认可的路：`SessionBridge.setBridgeEnabled(true)` 会
`registerMediaCenterSource(); start(...); refresh(); pushToMediaCenter(true);`
—— `pushToMediaCenter(true)` 里的 `t.a()` 就是 claim
（`t.b()` 声明音源类型、`t.d()` 推 meta）。
反向更直白：`setBridgeEnabled(false)` 的日志原文是
「bridge disabled → MediaCenter unregistered, **OEM source can take over**」
—— **不 claim，音源就归原车**。
**那条路是「启用即 claim」**，我们是「有歌在放才 claim」。

> **出处要说准（第135轮末修正）**：上面这条 claim 口径的出处是
> **参考 App（君君桌面）**（`com.ecarx.qq.tool`，第三方，平台签名预装）——
> **不是车机厂商规定的口径，也不是什么「官方桌面」的做法**（根本不存在官方桌面）。
> 证据性质只是「**另一个第三方桌面在同款车机上就是这么干的**」：
> 跟它对齐是因为它跑通过，**能不能成，最终看真车复验**。
>
> 顺带记一条**君君桌面的做法**（`BootMediaCompetitorKiller`）：
> 它开机 1s/5s/15s 会 `am force-stop` 掉
> `com.ecarx.multimedia` 与 `com.tencent.wecarflow`（爱趣听），
> 除非用户锁定了 / 上次在放 / 显式保护了它，然后才 claim。
> **我们不做这件事** —— 强杀原车音源属于越界（红线），我们只 claim 自己的播放入口。

### 改了什么

| 文件 | 改动 |
|---|---|
| `vehicle/DriveModeSwitch.kt`（新，纯逻辑） | 目标合法性 / 「已经是目标」判定 / 冷却 / 跳过原因 / 与参考 App（君君桌面）同口径的写入入口 |
| `vehicle/DriveModeAutoStart.kt`（新） | 执行端：开关 → 目标 → 等车控通道就绪 → 读当前 → 决定 → 写 → 回读确认 |
| `vehicle/VehicleGateway.kt` | 新增 `writeSelectFunction(func, value)`：**选择型**功能的写入通道（默认只走两参） |
| `vehicle/AdaptapiGateway.kt` | 覆写 `writeSelectFunction`：**两参优先 → zone 2 → zone 1**（与参考 App（君君桌面） `DriveModeController.writeDriveModeAdaptApi` 逐字对齐） |
| `vehicle/VehicleController.kt` | 手动切驾驶模式也走同一条通道（原来只有两参一条路） |
| `BootReceiver.kt` / `ui/LauncherView.kt` | 两个触发点：开机广播 / 桌面启动 |
| `core/Prefs.kt` | 持久化「上次尝试时间 / 上次目标 / 上次结果」 |
| `core/media/MediaBridgePolicy.kt` | `shouldClaim` + `hasNativeSourcePlaying`（纯逻辑，可单测） |
| `core/media/OfficialMediaBridge.kt` | 注册成功后**主动 claim 一次**；`claimTarget` / `nativeSourcePlaying` 诊断字段 |
| `core/media/MusicSessionTracker.kt` | 把「车机自带音源正在放」同步给桥 |
| `core/log/Diagnostics.kt` | 诊断包新增「驾驶模式自动切换（第135轮）」段 |

### 四个关键取舍

1. **两个触发点 + 一套冷却**：开机广播（`BOOT_COMPLETED`）是主场景，
   但真车诊断包 4231 里**一条「收到开机广播」都没有**（那次是进程重启，不是设备开机）——
   有些车机根本不给这个广播。所以桌面启动（`LauncherView`）也补一刀。
   两个触发点共用 **10 分钟冷却**：桌面在一次开机里会被拉起很多次，
   没有冷却就会对着车机连写几十次（写车是同步 Binder）。

2. **写入按参考 App（君君桌面）的口径降级，不猜 zone**：两参（文档口径，也是它的第一选择）→
   zone 2 → zone 1，顺序照抄它。**读不到当前模式时照样写** ——
   「读不到」≠「已经是目标」，这个区分很重要，否则读失败的机器永远切不了。

3. **claim 的红线没松**：只在**目标是三方在线播放器**（白名单）时 claim；
   **车机自带音源（爱趣听一类）正在放时不 claim**（`pushToMediaCenter`
   遇到 `isNativeSystemMediaSource(pkg)` 也是直接 skip）；
   **同一个目标只 claim 一次**（claim 是抢播放权，每 tick 都抢会把原车的播放权来回甩）。
   目标口径与方控回调**完全一致**（有歌名用歌名来源，没歌名用用户选的媒体源）。

4. **`playClaimed` 的语义改成「曾经成功接管过」**：失败的重试不再把一次成功的接管抹掉 ——
   真车排障要看的正是「接管过没有」。

### 验证

**模拟器（`emu-verify/y135/`）**

| | 证据 | 结果 |
|---|---|---|
| 执行端真的跑起来了 | `y135-log-drive-mode.txt` | `写入驾驶模式 func=0x22010100 value=570491139 label=运动模式 ok=true` |
| 模式真的变了 | 同上 | `驾驶模式变化 from=null to=运动 mode=570491139` → 状态栏胶囊变成「驾驶模式 · 运动」 |
| 冷却生效 | 同上 | 第二次启动：`自动切换驾驶模式：冷却中，跳过 sinceLastMs=15732`，**写入 0 次** |
| 设置页读到的是持久化值 | `y135-02-settings-drive-mode.png` | 开关是绿的、目标停在「运动模式」 |
| 诊断包里有新字段 | `y135-summary.txt` | 新增「驾驶模式自动切换（第135轮）」段 + `bridge.claimTarget` / `bridge.nativeSourcePlaying` |

**单测**：全量 **1871 项全绿**（第134轮 1841 → +30），
新增 `DriveModeSwitchTest`（23 项）+ `MediaBridgePolicyTest` 补 7 项。

**反向验证 13 项全部变红**（`tools/verify_y135_reverse.sh`，可复跑）：

| 反向 | 改法 | 红灯 |
|---|---|---|
| 1 | 目标模式什么值都算合法（哨兵值会被当成模式写下去） | 4 |
| 2 | 读不到当前模式也判成「已经是目标」 | 3 |
| 3 | 冷却失效 | 3 |
| 4 | 写入退回「只有两参」的老路 | 2 |
| 5 | zone 降级顺序颠倒（1 先 2 后） | 1 |
| 6 | 真车通道不走两参（接线检查） | 1 |
| 7 | 执行端不接开机广播（接线检查） | 1 |
| 8 | claim 不看白名单 | 1 |
| 9 | 车机自带音源在放时也 claim | 1 |
| 10 | 同一个目标反复 claim | 1 |
| 11 | 注册成功后不主动 claim（接线检查） | 1 |
| 12 | claim 只声明音源类型、不真的抢播放权（接线检查） | 1 |
| 13 | 车机自带音源的播放态不传给桥（接线检查） | 1 |

### 已知限制

- **问题 2 的「方控真的被我们接管了」这段模拟器上验不了** ——
  模拟器没有原车 `ecarx.xsf.mediacenter` 服务，也没有仪表（DIM）。
  **必须真车复验**：装包后不打开任何播放器，按方向盘播放键，
  看是不是**桌面选定的那个播放器**起来了（而不是爱趣听）；
  诊断包看 `bridge.claimTarget`（空 = 从没接管过）与 `bridge.callbackCount`（>0 = 原车把按键交给我们了）。
- **如果真车上 `claimTarget` 有值、但 `callbackCount` 仍是 0** ——
  说明原车把当前音源判给了**别的 App**（它也 claim 了同一个 type 6）。
  君君桌面那个桥**默认是关的**，正常情况下我们能拿到；
  真遇到这种冲突，下一步再议（不要学它去 `force-stop` 别人）。
- **「已经是目标模式就不写」这条分支在模拟器上走不到** ——
  模拟通道的当前模式只存在内存里，进程一重启就回到「读不到」。
  该分支由 `DriveModeSwitchTest` 钉死。
- **写车成功 ≠ 模式真的变了**：真车通道写完会回读确认（`verifyDriveMode`），
  确认不了时如实报「已下发但没确认」。
- **没有发 GitHub Release、没有传 R2**（等明确指示）。

### 产物

| 项 | 值 |
|---|---|
| 文件 | `dist/CarDesk-platform-ecarx-cardesk-4233.apk` |
| versionCode / versionName | 4233 / 1.0.1137-system |
| 大小 | 2 323 345 字节 |
| SHA-256 | `a2911207f7471650fd1d739f5b2331b373a3bf2838f63f002e04699b3e7a35bd` |
| 签名 | AOSP platform（SHA-256 `c8a2e9bc…92ab8`） |
| 单测 | 1871 项全绿（`tools/release.sh` 出包时一并跑的） |
