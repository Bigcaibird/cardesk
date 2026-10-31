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
- 现在是**灯态看枚举、读秒跟着灯态走**（红读 `redLightCountDownSeconds`，
  绿读 `greenLightLastSecond`）；万一高德改了枚举，**不猜**，
  改用**自带字段名**的读秒兜底（绿秒优先），绝不退回「永远画红灯」。
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

