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
**拿不到逐字数据时仍是行级线性**（与原厂 App 公式一致），不猜、不倒退。

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

### 2. 原厂音源「实现了回调但没 setActions()」的补位

参考工程里这条补丁是给网易云写的（它的 `PlaybackState` 没有 `setActions()`，
可 transport 回调其实都在）。这轮把它**推广到所有原厂音源**：
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
