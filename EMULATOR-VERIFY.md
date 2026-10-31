# CarDesk 模拟器验证记录

环境：macOS arm64 + Android SDK（本仓库 `toolchain/sdk`），AVD `cardesk` = android-34 / google_apis / arm64-v8a，
1920×1080 @160dpi（正好命中参考 App 的 820A 判定），headless（`-no-window -gpu swiftshader_indirect`）。

判定标准：截图 + logcat（FATAL/Exception 计数）+ `dumpsys`/`pm`/`settings` 实际状态，任一失败即记为 ❌。

## 0. 流程要求（第八十轮起，**硬性**）

用户要求：**「以后新功能开发都需要在虚拟机跑一遍结果，并提供截图」**。

为什么是硬性的 —— 第八十轮就抓到了两个**设计板（Python 重绘）根本看不出来**的问题：

1. **红绿灯读秒和「秒」字重叠** —— 设计板里是分开排的，App 里两个绘制基准不一致；
2. **氛围灯一开就 native 闪退** —— 根因是 FFT 缓冲区少给一半，ROM 越界写坏 Java 堆，
   崩在 ART 的 GC 线程上（tombstone：`ConcurrentCopying::AddLiveBytesAndScanRef`）。

所以每个新功能都要走一遍：

```bash
# 装包（会顺手授 RECORD_AUDIO：pm grant + appops 两步）+ 启动
python3 tools/emu_regress.py install dist/CarDesk-ecarx-cardesk-<code>.apk

# 触发 + 截图 + 判定闪退
python3 tools/emu_regress.py ambient 40          # 氛围灯页（开页 / 开开关 / 观察 / 判定）
python3 tools/emu_regress.py traffic 0 12        # 造高德红绿灯广播并截图（0=红 1=绿）
python3 tools/emu_regress.py check 30            # 单独判定「这段时间没崩」

# 判定标准（任一失败即 ❌）
#   · 进程 pid 没变（变了 = 崩过并被系统重启）
#   · logcat 无 Fatal signal / FATAL EXCEPTION
#   · /data/tombstones 没有新增
#   · 截图能看出功能真的在渲染（不是黑屏 / 不是空白）
```

**出图要「看得清」才算数** —— 全屏 1920×1080 里一个 80×100 的 chip 肉眼很难判，
所以红绿灯这类小元素另配了 `tools/traffic_shot.py`：广播 → 截图 →
**自动定位灯圆色块 → 裁 3 倍放大图**，并顺带判定「这一帧到底是红是绿」：

```bash
python3 tools/traffic_shot.py red 12      # → r80-traffic-red.png + -zoom.png
python3 tools/traffic_shot.py green 8     # → r80-traffic-green.png + -zoom.png
```

> 抓图脚本自己踩过的坑（**都出过假图**）：
> ① 设置面板没关 → 截到的是设置页；
> ② 上一轮的广播脚本没杀掉 → 红绿互盖，「绿灯」截出来是红的；
> ③ `ps -A` 只显示进程名（脚本都叫 `sh`），清脚本必须用 `ps -ef` 按 PID 杀。
> 详见 `emu-verify/REPORT-r80.md` 第 1.3 节。

**逐字擦除这类「随时间变化」的效果**同理：设计板（静态重绘）根本看不出擦除边界落在哪，
所以第八十一轮加了 `tools/lyric_shot.py` —— 把播放位置**钉死在某个毫秒**再截图，
同一个位置可以反复截、也可以和旧包 A/B 对比：

```bash
# 钉到 103497ms（歌手已唱完这一句、但下一句还没开始的时刻）并截图 + 裁歌词区
python3 tools/lyric_shot.py at 103497 r81-new-103497
#   → emu-verify/r81-new-103497.png        全屏
#   → emu-verify/r81-new-103497-lyric.png  歌词区放大 2 倍
```

> 钉位置的办法：媒体探针（`com.cardesk.mediaprobe`）用 `--es position <ms> --es play false`
> 注入 MediaSession —— **暂停态位置是冻结的**，所以擦除进度也冻结，截图可复现。

**「手势 / 布局」这类改动**（第八十二轮）更不能只看设计板 ——
设计板是静态重绘，**看不出「手指落在哪一块会失效」**。那轮改成
**注入真实触摸事件 + 扫像素**：

```bash
# 注入一次横滑（换起点就能试不同区域）
adb shell input swipe 1400 300 800 300 300
# 判定：有没有「手势：单指横滑翻页」+「切页 from=… to=…」
adb logcat -d | grep -E "手势：单指横滑翻页|切页 "
# 判定布局：扫状态栏那一行，把每个元素的 x 区间打出来（python + PIL）
```

> 这轮靠它抓到一个**设计板和单测都看不见**的缺陷：
> 「没人消费 ACTION_DOWN 的区域收不到 MOVE」⇒ 整片区域滑动失效，
> 用户只会觉得「翻到头了，不能循环」。详见 `emu-verify/REPORT-r82.md` 第 2 节。

**推荐用 ecarx 平台镜像**（`emulator-5554`，android-10 / API 29）：预装 `com.ecarx.*` 与高德 YH，
`com.ecarx.cardesk` 是**平台签名 + uid 1000**，车控链路（`AdaptapiGateway`）是**真链路**。

**模拟器验不到、要真机的**（如实标注，不要写成 ✅）：车门 0/1 语义、
歌词与 QQ 音乐 App 的逐句一致性。

> 第八十五轮补上了一个缺口：**模拟器现在有能在后台持续播放的音源了**
> —— `media-probe` 的 `ToneActivity`（`AudioTrack` 播鼓 + 贝斯循环，
> 走 AudioFlinger ⇒ 输出混音的 Visualizer 抓得到）。
> 于是「频谱条在动」「灯随音乐变色」「颜色在流动」都能在模拟器上验了，
> 见 H8 段（H71~H77）。
>
> 仍然**只能真车验**的：亮度的快起慢落（模拟器音频链路有归一化，
> 目标亮度几乎是常量）、真实音乐的观感、真车的 `Visualizer` 采集点数范围。

## A. 桌面主体

| # | 项 | 操作 | 期望 | 结果 |
|---|---|---|---|---|
| A1 | 启动 / 设为桌面 | `cmd package set-home-activity` 后 HOME | 进入 CarDesk 场景页，状态栏/Dock 完整 | |
| A2 | 场景页卡片 | 截图 | 导航卡 / 天气 / 快捷 / 媒体 / 空调 / 胎压 / 续航卡 | |
| A3 | 车辆数据降级 | 无车机服务 | 通道自动回退「模拟车辆」，数据非空 | |
| A4 | 日/夜主题 | 设置切日间、夜间 | 两套配色都可读 | |
| A5 | 页面滑动 | 左右滑 | scene ↔ music ↔ drawer 切换 | |

## B. 车控（模拟车端）

| # | 项 | 操作 | 期望 | 结果 |
|---|---|---|---|---|
| B1 | 空调整页 | Dock 风扇 | 打开 climate 页（1864×700 底部面板） | |
| B2 | 电源/AC/AUTO | Dock 电源、A/C、AUTO | 状态互相联动（上电/关 AC 清 AUTO 等） | |
| B3 | 温度 ± | 主驾 +/− | 0.5℃ 步进、面板与 Dock 同步 | |
| B4 | 吹风模式 / 座椅档位 | 面板 chip | 模式切换、座椅档位循环 | |
| B5 | 360 浮层 | Dock 360 | 四路环视 + 视角切换 + 退出 | |
| B6 | 返回键 | 浮层打开时 BACK | 依次关闭 浮层→设置→回首页，不退出桌面 | |

## C. 音乐 / 应用

| # | 项 | 操作 | 期望 | 结果 |
|---|---|---|---|---|
| C1 | 音乐页 | Dock 媒体区 | 打开音乐页，无会话时显示兜底文案 | |
| C2 | 播放控制 | 播放/上一首/下一首 | 无会话时不崩溃、给出提示 | |
| C3 | 9 来源 | 设置 → 媒体来源 | 5 个固定包 + 4 个兜底来源 | |
| C4 | 应用抽屉 | Dock 应用 | 列出系统已装应用（设置/浏览器等） | |
| C5 | 浮窗 | 抽屉「小窗打开」选一个应用 | 生成降级浮窗卡片，可拖动/缩放/关闭，最多 2 个 | |

## D. 设置面板

| # | 项 | 操作 | 期望 | 结果 |
|---|---|---|---|---|
| D1 | 打开/关闭 | 状态栏齿轮 | 1320×860 双栏面板，关闭回到桌面 | |
| D2 | 全局 7 页 | 逐页点击 | 无崩溃、内容正确 | |
| D3 | 系统 11 页 | 切 tab 逐页 | 无崩溃、内容正确 | |
| D4 | 日夜 | 日间/夜间 chip | 主题立即切换 | |
| D5 | 主题色 | 取色器 | 选色后 Dock/按钮强调色改变 | |
| D6 | 桌面布局 | 对话框 | 隐藏 Dock/状态栏、Dock 下沉生效 | |
| D7 | 导航栏槽位 | 开启增强导航、切槽位模式 | 槽位条渲染、可编辑、重启后保留 | |

## E. 计划任务（AlarmManager）

| # | 项 | 操作 | 期望 | 结果 |
|---|---|---|---|---|
| E1 | 新建任务 | 条件=打开桌面后、延迟 5s、目标=系统设置 | 保存后写 `scheduled_tasks_json` | |
| E2 | 到点拉起 | 回桌面等待 | 5s 后 `mResumedActivity` 变成设置 | |

## F. 局域网传输

| # | 项 | 操作 | 期望 | 结果 |
|---|---|---|---|---|
| F1 | 启动服务 | 设置 → 局域网传输 → 启动 | Toast 显示 `http://ip:18765/?t=…` | |
| F2 | 上传 | `curl -F file=@x` | 200，文件出现在面板列表 | |
| F3 | 下载 | `curl /file` | 内容一致 | |

## G. HUD 蓝牙（模拟器无蓝牙）

| # | 项 | 操作 | 期望 | 结果 |
|---|---|---|---|---|
| G1 | 扫描入口 | 设置 → HUD → 扫描 | 面板打开；蓝牙不可用时给出明确提示，不崩溃 | |

## H. system 版能力（平台签名 + uid 1000 + priv-app）

| # | 项 | 操作 | 期望 | 结果 |
|---|---|---|---|---|
| H1 | 安装 system APK | `adb install` 或推 priv-app | `dumpsys package` userId=1000 | |
| H2 | 权限授予 | dumpsys | INSTALL_PACKAGES / WRITE_SECURE_SETTINGS / REBOOT 等 granted | |
| H3 | 静默安装 | 设置 → 在线更新 → URL → 检查更新 | 无安装界面，`pm list packages` 出现 com.cardesk.probe | |
| H4 | ADB 开关 | 设置 → 启动切换 → 打开 ADB | `settings get global adb_enabled` = 1 | |
| H5 | 按键注入 | 按键映射 → 测试注入音量+ | 音量变化，无异常 | |
| H6 | 重启 | 设置 → 重启确认 | 设备重启并回到系统 | |
| H7 | 频谱录音权限（氛围灯） | `dumpsys package` + `appops get` | RECORD_AUDIO granted=true **且** `record_audio` = allow（白名单只对 privileged 生效，dangerous 权限要 `pm grant` + `appops set`） | |
| H8 | 隐藏 API 豁免 | 诊断包「车机定位能力探测」段 | `隐藏 API 豁免: 已放开`（旧版这里恒为「未放开(NoSuchMethodException)」） | |
| H9 | 氛围灯自检 | 诊断包「氛围灯」段 | 频谱状态=采集中、来源=输出混音；灯随音乐变色 | |
| H10 | 车门属性原始值 | 诊断包「车门属性原始值」行 | 出现 `类型=` / `值=`（据此标定 0 是关还是开） | |
| H11 | 歌词当前句 | logcat 过滤 `CarDesk/Media` | 「歌词当前行」行的 `live` 有值，且与 QQ 音乐 App 内当前句一致 | |

## H2. 第七十九轮新增（氛围灯黑屏 / 频谱 / 车门取证）

| # | 项 | 操作 | 期望 | 结果 |
|---|---|---|---|---|
| H12 | 氛围灯页不黑屏 | 设置 → 氛围灯 → 打开开关 | 页面**不黑屏**；主线程不卡（`dumpsys gfxinfo` 无长帧）；灯随音乐变色 | |
| H13 | 频谱真的有数据 | 氛围灯页的「频谱预览」条 | 条在动（不再是全 0）；诊断包「氛围灯」段 `频谱状态=采集中`、`来源=输出混音` | |
| H14 | 频谱口径 | 诊断包「氛围灯」段的「频谱口径」行 | `分段=24 采集点数=1024 轮询=100ms 回落=0.7`（第八十五轮起采集点数是 1024：512 点在 44.1kHz 下低频四段会塌进同一 bin ⇒ 色相恒为 0°） | ✅ 报的是**实际**用上的点数，不是期望值 |
| H15 | 车控写入不占主线程 | logcat 过滤 `CarDesk/Ambient` | 颜色/开关/亮度的下发日志不密集（写失败时**每 3 秒**才一条，不是每 100ms 一条） | |
| H16 | 车门属性快照 | 诊断包「车门属性原始值」行 | 出现 `快照 prop=0x21701414 … status=… valueClass=… bytesLen=… bytesHex=…` | |
| H17 | 异常内层原因 | logcat 过滤 `属性按类型读取失败` | `err=` 后面带 `cause[N]=java.lang.IllegalArgumentException: … but was: class …`（不是只有 `InvocationTargetException: -`） | |
| H18 | 崩溃栈落盘 | 故意触发一次崩溃后导出诊断包 | 日志文件里有一条 `未捕获异常（进程即将退出）`，带 `thread=` 与完整 `stack=` | |
| H19 | 设置项类型容错 | 把 `ambient_mode` 存成字符串后进氛围灯页 | 页面正常打开（不因 `ClassCastException` 白屏），诊断包「设置全量键值」段能看到 `[String]` | |

## H3. 第八十轮新增（氛围灯闪退根因 + 红绿灯渲染）

被测包：`dist/CarDesk-ecarx-cardesk-4144.apk`（修前）/ `-4145.apk`（修后）；
设备：`emulator-5554`（ecarx 平台镜像）。完整报告见 `emu-verify/REPORT-r80.md`。

| # | 项 | 操作 | 期望 | 结果 |
|---|---|---|---|---|
| H20 | 氛围灯页不黑屏 | 设置 → 氛围灯 → 打开开关 | 页面**不黑屏**（第七十九轮已修） | ✅ 4144/4145 均正常 |
| H21 | 氛围灯不闪退 | 同上，**观察 45s + 180s** | 进程 pid 不变；logcat 无 `Fatal signal`；tombstone 不新增 | ❌ 4144：11 秒后 native SIGSEGV（GC 线程）；✅ 4145：45s+180s 无崩溃 |
| H22 | 崩溃根因隔离 | 撤掉 `RECORD_AUDIO`（Visualizer 挂不上）再开 | 不崩 ⇒ 崩溃在 `Visualizer.getFft` 而非车控写入 | ✅ 25 秒无崩溃，2×2 隔离成立 |
| H23 | FFT 缓冲区口径 | logcat `频谱已挂上输出混音` | `captureSize=512` **`fftBytes=512`**（不是 256） | ✅ 4144 是 256（越界写坏堆），4145 是 512 |
| H24 | 频段数恢复 | 单测 + 诊断包 | 频点 `bins = N/2 = 256`（不是 128） | ✅ 与参照工程 `uni-launcher` 一致 |
| H25 | 写入节流 | logcat 过滤 `氛围灯颜色功能号按名解析不到` | 间隔 ≈ 1 秒（不是 120ms） | ✅ 4144 ≈ 8 次/秒，4145 ≈ 1.1 秒一条 |
| H26 | 红绿灯渲染 | 造高德广播 → 截图 | 大圆 + 圆下方读秒，**数字与「秒」不重叠** | ❌ 4144「秒」压在数字上；✅ 4145 整行以圆心居中（`r80-traffic-red-zoom.png` / `r80-traffic-green-zoom.png`） |
| H27 | 抓图前回到场景页 | 设置面板打开时截导航卡 | 先 `input keyevent BACK` 关掉面板，否则截到的是设置页 | ⚠️ 本轮曾误截成设置页（红绿两张 md5 相同） |
| H28 | 广播脚本互不打架 | `ps -ef \| grep broadcast` | 抓图前后**只有一个**广播脚本在跑 | ⚠️ 第七十九轮残留的红灯脚本活到 00:41，把绿灯盖成红 |
| H29 | 清脚本用 `ps -ef` | `ps -A` 的 NAME 列 | 脚本进程名都叫 `sh`，必须用 `ps -ef` / `ps -A -o PID,ARGS` 才看得到参数 | ✅ 已固化进 `tools/traffic_shot.py`（按 PID kill） |
| H30 | 灯态与图一致 | 脚本自动判定这一帧是红是绿 | 红灯帧红像素 > 0、绿灯帧红像素 = 0 | ✅ 红 1836 / 绿 0（`tools/traffic_shot.py` 会打印） |

## H4. 第八十一轮新增（歌词逐字擦除）

被测包：`dist/CarDesk-ecarx-cardesk-4145.apk`（修前）/ `-4146.apk`（修后）；
设备：`emulator-5554`（AVD `cardesk-aosp`，android-10 / API 29 / 1920×1080@160dpi）。
完整报告见 `emu-verify/REPORT-r81.md`。

| # | 项 | 操作 | 期望 | 结果 |
|---|---|---|---|---|
| H31 | 逐字歌词取到 | 媒体探针注入 `怎么我睡不着你也不来救我` / 焦迈奇 | logcat 出现 `酷狗 KRC 逐字歌词命中`，且 `wordLines` = 总行数 | ✅ `lines=54 wordLines=54`；`wordTiming=true` |
| H32 | 擦除随演唱推进 | 钉到 101000ms 截图 | 擦除边界落在**正在唱的那个字**上（不是全行/全暗） | ✅ 逐字 64.6%，边界卡在第 9 个字「不」中间（`r81-new-101000-lyric.png`） |
| H33 | **歌手唱完当场满格** | 钉到 103497ms（末字 103197ms 结束，下一句 120588ms 才开始） | 整行全亮（100%）；旧包只有 23% | ✅ 新包全亮（`r81-new-103497-lyric.png`）；❌ 旧包「怎么我」三个字亮（`r81-old-103497-lyric.png`） |
| H34 | 无逐字数据不倒退 | 无网络的歌 / 明文 LRC | 仍走行级线性（与原厂公式一致） | ✅ 单测 `lrcParser_plainLrcFallsBackToLineLevel` |
| H35 | 判不出来就丢 | 语义判不出的 `<n,n,n>` | **不挂词**、回退行级，绝不瞎挂 | ✅ 单测 `attach_keepsNothingWhenAmbiguous` |
| H36 | 不闪退 | 连续播放 + 反复换位置 | pid 不变；无 `Fatal signal`；tombstone 不新增 | ✅ pid 3108 → 3108，无崩溃 |
| H37 | 正式包复验 | 装 `dist/CarDesk-ecarx-cardesk-4146.apk` 重截 | 与开发包一致 | ✅ `r81-release-103497-lyric.png` 全亮 |
| H38 | 定位要能复现 | 同一毫秒反复截图 | 暂停态位置冻结 ⇒ 擦除进度冻结 | ✅ `tools/lyric_shot.py at <ms> <name>` |

## H5. 第八十二轮新增（状态栏重排 + 翻页圆点搬家 + 单指滑动循环）

被测包：`com.ecarx.qq.tool`（平台签名替换包，versionCode 4354 / 1.0.1258-system）；
设备：`emulator-5554`（AVD `cardesk-aosp`，android-10 / API 29 / 1920×1080@160dpi）。
完整报告见 `emu-verify/REPORT-r82.md`。

| # | 项 | 操作 | 期望 | 结果 |
|---|---|---|---|---|
| H39 | 位置+天气挪到右侧 | 截状态栏右段 | 位置胶囊、天气胶囊在连接图标左边 | ✅ x1323~1545（`r82-statusbar-new.png`） |
| H40 | 正中让出来 | 扫状态栏正中 | 正中不再有位置/天气 | ✅ 正中是翻页圆点（x916~1004） |
| H41 | 圆点搬家 | 扫 Dock 上方老位置 | 内容像素 = 0 | ✅ 老位置已空（对比 `r82-dots-old-dock.png`） |
| H42 | 圆点居中 | 扫 y=38 行 | 圆点组中心 = 960 | ✅ 936~984，中心 **960** = 屏幕正中 |
| H43 | 圆点还能点 | 点未选中那个圆点 | 切到第 2 页 | ✅ `页面圆点点击切页 target=1 current=0` |
| H44 | **单指滑动全区域可用** | 换起点连滑 7 次 | 每次都切页 | ✅ 7 连全中；`抢事件=true/false` 两条路径都覆盖（修复前 `false` 那条整片失效） |
| H45 | **循环切换** | 第 2 页继续左滑 | 回到第 1 页 | ✅ `切页 from=1 to=0`，整串严格 `0→1→0→1…` |
| H46 | 驾驶模式胶囊可点 | 点驾驶模式胶囊 | 开空调面板 | ✅ `状态栏驾驶模式胶囊点击`（**修掉既有 bug**：以前点不开，点天气反而误开） |
| H47 | 不闪退 | 全程 | pid 不变、无 FATAL | ✅ pid 6151 → 6151 |

## H6. 第八十三轮新增（在线更新源换 Cloudflare R2）

被测包：`com.ecarx.cardesk`（平台签名，versionCode 4100 / 1.0.1004-system 的**临时构建**，
把更新地址临时指向本机模拟 R2；验证完已改回 `file.chuxi.bid`）；
设备：`emulator-5554`（AVD `cardesk-aosp`，android-10 / API 29 / 1920×1080@160dpi）。
完整报告见 `emu-verify/REPORT-r83.md`。

> **为什么用本地模拟 R2 而不是直接打真 R2**：改更新源这件事要验的是
> 「清单解析 → 比版本 → 下载 → 校验 → 安装」这条链路本身，
> 与清单从哪儿来无关。用本机 `http://10.0.2.2:8899/cardesk/latest.json`
> 配一份同格式的清单，能把链路完整跑通又不依赖外网；
> 真 R2 那次是交付前的终验。

| # | 项 | 操作 | 期望 | 结果 |
|---|---|---|---|---|
| H48 | 更新源改了 | 设置 → 在线更新 | 显示「Cloudflare R2」而不是 GitHub | ✅ `r83-update-page-r2.png` |
| H49 | **清单能解析** | 点「检查更新」 | 拉到清单、认出新版本 | ✅ `检查更新：有新版本 current=1.0.1004-system latest=1.0.1052` |
| H50 | **相对 key 拼地址** | 看对话框里的下载地址 | `cardesk/x.apk` 拼成域名根 + key | ✅ `http://10.0.2.2:8899/cardesk/CarDesk-ecarx-cardesk-4148.apk` |
| H51 | **下载 + SHA-256 校验** | 点「下载并更新」 | 下载完才报「下载完成」（校验不过就不算完成） | ✅ `更新下载完成 file=cardesk-update-1.0.1052.apk bytes=1941768 elapsedMs=49` |
| H52 | **静默安装真的生效** | 自动 | 设备上版本号变成 4148 | ✅ `dumpsys package` → versionCode=4148 / `1.0.1052-system` |
| H53 | 已是最新分支 | 再点一次「检查更新」 | 「已是最新版本」 | ✅ `检查更新：已是最新 current=1.0.1052-system latest=1.0.1052`（`r83-already-latest.png`） |
| H54 | 不闪退 | 全程 | 无 FATAL | ✅ `Fatal signal` / `FATAL EXCEPTION` 计数 0 |

### H6.2 第八十三轮续：**真 R2 终验**（公开自定义域，交付前）

4149 的 APK 与清单已上传到 R2（bucket `file`），下面这条是**从公网**跑的真实链路：
装一个同源的 4148 构建（源码与 4149 相同，只有版本号不同），让它去查真实的
`https://file.chuxi.bid/cardesk/latest.json`。

| # | 项 | 操作 | 期望 | 结果 |
|---|---|---|---|---|
| H55 | 公开域可读 | `curl` 两个对象 | 清单 200、APK 200 且字节一致 | ✅ 清单 590 B；APK 1,941,656 B，`sha256 a09894ad…c7423a`，与本地包逐字节一致 |
| H56 | **真域名查更新** | 4148 包点「检查更新」 | 走 `file.chuxi.bid` 认出新版本 | ✅ `url=https://file.chuxi.bid/cardesk/latest.json`；`检查更新：有新版本 current=1.0.1052-system latest=1.0.1053` |
| H57 | **公网下载 + 校验 + 安装** | 点「下载并更新」 | 下载、校验、静默安装全过 | ✅ `更新下载完成 … bytes=1941656 elapsedMs=8369`；设备变 4149 / `1.0.1053-system`；设备 `base.apk` 的 sha256 与线上、本地三方一致 |
| H58 | 装完确实是新包 | 打开设置 → 在线更新 | 「更新源」显示 R2 | ✅ `Cloudflare R2 · file.chuxi.bid`（`r83-r2-installed-4149.png`）；复检「已是最新」 |

证据：`emu-verify/r83-*.png`、`emu-verify/update-r2-log.txt`。

## H7. 第八十四轮新增（云端应用库：手机传 APK，车机直接装）

被测包：`com.ecarx.cardesk`（平台签名，versionCode 4148 / 1.0.1052-system 的**临时构建**，
源码与 4150 完全相同、只有版本号不同 —— 用来证明「装上去的确实是从云端下的那个包」）；
设备：`emulator-5554`（AVD `cardesk-aosp`，android-10 / API 29 / 1920×1080@160dpi）。
云端清单：`https://file.chuxi.bid/cardesk/apks.json`（公开只读，无需凭据）。
完整报告见 `emu-verify/REPORT-r84.md`。

| # | 项 | 操作 | 期望 | 结果 |
|---|---|---|---|---|
| H59 | 页面在 | 设置 → 网络与升级 → 云端应用 | 列出云端已有的包 | ✅ `r84-apkstore-list.png`：「CarDesk 4150（云端应用库自测）」/ `CarDesk-ecarx-cardesk-4150.apk` · 1.9 MB · 2026-10-30 |
| H60 | **清单真能解析** | 进页面自动拉一次 | 日志有「清单已拉取」 | ✅ `云端应用清单已拉取 count=1 updatedAt=2026-10-30T09:45:37Z` |
| H61 | **公网下载 + 校验** | 点「下载安装」 | 进度条 → 下完才报完成 | ✅ `云端应用下载完成 file=CarDesk-ecarx-cardesk-4150.apk bytes=1957140 elapsedMs=3644`（`r84-apkstore-downloading.png`） |
| H62 | **静默安装真的生效** | 自动 | 设备版本从 4148 变 4150 | ✅ `dumpsys package` → versionCode=4150 / `1.0.1054-system` |
| H63 | 装完确实是那个包 | 拉设备 `base.apk` 算 SHA256 | 与线上、本地三方一致 | ✅ `2851c53f8e398a9b0dfe5bbdba43e72a8e531de16c40904eaef4603bbdcaa982`（线上 = 本地 = 设备） |
| H64 | 中文展示名没乱码 | 看列表 | 清单里的中文名原样显示 | ✅ `CarDesk 4150（云端应用库自测）`（`r84-apkstore-4150.png`） |
| H65 | 刷新按钮 | 点「刷新列表」 | 重新拉一次清单 | ✅ 再次出现 `云端应用清单已拉取 count=1`（`r84-apkstore-refresh.png`） |
| H66 | 不闪退 | 全程 | 无 FATAL | ✅ `Fatal signal` / `FATAL EXCEPTION` 计数 **0** |

> **装完之后系统弹了「Select a Home app」**：CarDesk 是 HOME 应用，
> 被自己更新掉之后系统要重新确认主屏程序 —— 这是**重装 launcher 的正常副作用**，
> 不是崩溃（`Fatal signal` 计数 0，应用照常起来）。

## H8. 第八十五轮新增（红绿灯灯态 + 氛围灯动感）

| # | 项 | 操作 | 期望 | 结果 |
|---|---|---|---|---|
| H67 | **官方协议红灯** | `python3 tools/traffic_shot.py standard red 12` | 画**红**灯 + 红灯读秒 | ✅ `lamp=RED countdown=12 rawStatus=1`；红块 x[475,519] y[117,162] 45×46（`r85-traffic-standard-red-zoom.png`） |
| H68 | **官方协议绿灯**（故意带 19 秒红灯倒计时） | `python3 tools/traffic_shot.py standard green 8` | 画**绿**灯 + **绿灯**读秒；**红像素 = 0** | ✅ `lamp=GREEN countdown=8 rawStatus=4`；绿块 46×46，红像素 **0**（`r85-traffic-standard-green-zoom.png`） |
| H69 | 巡航协议回归 | `python3 tools/traffic_shot.py cruise green 8` | 巡航 `status=1` 仍是**绿**（与官方正好相反） | ✅ `lamp=GREEN rawStatus=1`（`r85-traffic-cruise-green-zoom.png`） |
| H70 | 方向枚举 | 官方协议日志 | `dir=直行`（官方 1=左/2=右/4=直） | ✅ `收到红绿灯数据（官方协议） lamp=… rawStatus=… rawDir=4 dir=直行` |
| H71 | **合成音源能喂到频谱** | `adb shell am start -n com.cardesk.mediaprobe/.ToneActivity --es gain 0.05` | 输出混音的 Visualizer 抓得到 `AudioTrack` 的声音 | ✅ 氛围灯页「频谱状态：**采集中 · 来源：输出混音**」，24 根频谱条有形状（`r85-ambient-05.png`） |
| H72 | **色相不再恒为 0°** | `adb logcat -s CarDesk/Ambient:D` 看「氛围灯帧」 | `targetHue` **不再恒为 0**（随贝斯音高在 0/15/45 之间走） | ✅ 1024 点下 `targetHue=45/15/0/33.6…`；512 点时恒为 0.0 |
| H73 | **颜色在流动而不是在跳** | `python3 tools/ambient_shot.py 14 r85-ambient` | 平滑后的单步跳变明显小于目标跳变 | ✅ 目标最大跳 **51.0° / RGB 184.4** → 平滑后 **12.5° / RGB 45.2**（**4.1×**） |
| H74 | 当前颜色是活的 | 看氛围灯页「当前颜色」 | 不是恒定的死红 | ✅ `#F77025 · 亮度 97%`（251 行逐帧日志里 hex 一直在变） |
| H75 | **氛围灯重启后恢复** | 装包 → 重启 App → 看日志 | `ambient_enabled=true` 时启动要恢复采集与写灯 | ✅ 日志 `CarDesk/Ambient : 恢复上次开着的氛围灯`（以前这个开关**只写不读**，重启后灯再也不亮） |
| H76 | 采集点数 | logcat `频谱已挂上输出混音` | `captureSize=1024`（设备支持 128~1024） | ✅ `captureSize=1024 fftBytes=1024`；设备不支持时自动退回 512 |
| H77 | 不闪退 | 全程 | pid 不变、无 FATAL | ✅ 抓帧全程 pid 12468 未变；`Fatal signal` 计数 0 |

> **H71 是这一轮补上的缺口**：早前 `EMULATOR-VERIFY.md` 第 0 节写着
> 「模拟器没有能在后台持续播放的音源」，所以频谱与氛围灯一直只能标「要真机」。
> 现在用 `media-probe` 的 `ToneActivity`（`AudioTrack` ⇒ 走 AudioFlinger ⇒ 输出混音抓得到）
> 在模拟器里造了一个鼓 + 贝斯循环，频谱、色相、亮度第一次能在模拟器上验。
> `media-probe` 是**仓库外的测试工具**，不进 CarDesk 仓库、不进 APK。

> **上传端（Cloudflare Worker）本轮没上真机**：Worker 需要用户自己的 Cloudflare 账号
> 执行 `npx wrangler deploy` 才能拿到地址（见 `worker/README.md`）。
> 本轮验的是**车机侧**的完整链路（读真 R2 的清单 → 下载 → 校验 → 静默安装），
> 上传端用 `tools/publish_apks.py` 走 S3 API 产生同一份清单（格式与 Worker 完全一致）。

证据：`emu-verify/r84-apkstore-*.png`、`emu-verify/update-apkstore-log.txt`。

## H9. 第八十六轮新增（红绿灯读秒锯齿：3↔2 来回跳）

| # | 项 | 操作 | 期望 | 结果 |
|---|---|---|---|---|
| H78 | **旧包复现锯齿** | 装 4156 → `python3 tools/traffic_countdown_shot.py r86-before --lamp green --raw 6 --frames 14 --interval 0.45 --warmup 0.8` | 显示值在两个值之间**来回跳** | ✅ `distinct=2`、**回跳=有**；序列 `6→6→…→5→6→…→5`（`r86-before-frames.png`） |
| H79 | **新包单调递减** | 装 4157 → 同上（tag 换 `r86-after`） | 一路往下走，**不再回来** | ✅ `distinct=6`、**回跳=无**；序列 `6→5→4→3→2→1→消失`（`r86-after-frames.png`） |
| H80 | **显示值有日志可对** | logcat `CarDesk/NavUI` 过滤「红绿灯读秒显示」 | `raw`（供应商原值）与 `shown`（显示值）都打出来 | ✅ `raw=6 shown=6` → `shown=5` → `4` → `3` → `2` → `1` → `null` |
| H81 | **两个原始字段都落日志** | logcat 过滤「收到红绿灯数据（官方协议）」 | `rawRed` / `rawGreen` 都在 | ✅ `rawRed=… rawGreen=…`（真车取证的探针） |
| H82 | **红灯读秒同样受益** | `… --lamp red --raw 12` | 红灯也单调递减 | ✅ `12→11→10→9→8→7→6→5→4`，`distinct=7`、回跳=无（`r86-red-frames.png`） |
| H83 | 不闪退 | 全程 | pid 不变、无 FATAL、无 tombstone | ✅ pid `21836` 未变；`Fatal signal` / `FATAL EXCEPTION` 计数 **0** |

> **抓「随时间变化」的效果必须按帧连抓**（第八十六轮新增 `tools/traffic_countdown_shot.py`）：
> `tools/traffic_shot.py` 只截**一帧**，锯齿这种**时间维度**的缺陷截一帧看不出来。
> 新脚本按帧抓下读秒那一小块做 md5 —— 同一个数字逐像素相同 ⇒ 同一个哈希，
> 于是「到底在几个值之间跳」一眼可见。
>
> 抓图时踩的三个坑（**都出过假图**）：
> ① 设置面板/弹窗开着 → 截到的是「在线更新」弹窗，脚本把弹窗的绿色按钮当成了灯圆
>    （脚本现在会先按两次 BACK 回场景页，灯圆识别也限制在导航卡区域内）；
> ② **残留广播脚本没杀掉** → 两三个脚本一起发广播，帧率翻倍、读秒反而不跳了（**假象**）；
>    匹配条件已从「名字含 broadcast」放宽成「名字含 broadcast **或** countdown」；
> ③ **读秒是倒着走的** → `raw=3` 时等 3 秒再去抓，整段递减已经走完了 ⇒
>    要用 `--raw 6 --warmup 0.8` 这类参数把整段抓全。

## I. 稳定性

| # | 项 | 期望 | 结果 |
|---|---|---|---|
| I1 | logcat | 上述流程无 `FATAL EXCEPTION` / 未捕获 Exception | |
| I2 | 内存 | 无持续 OOM / 大量 GC | |

## 结论摘要

（执行后填写）

## 实测结果（Android 14 / API 34，1920×1080@160dpi）

见 `emu-verify/REPORT.md` 与 `emu-verify/emu-*.png`。结论摘要：
A 桌面/HOME ✅；B 车控（电源/AC/风量/温度/座椅/除霜）✅；C 抽屉 + 第三方启动 ✅；
D 音乐页无会话回退 ✅；E 设置双栏 + 平台识别（820A/非平台签名/普通 uid/默认桌面）✅；
F LAN（200 带 token / 403 无 token）✅；G 计划任务（AlarmManager 触发并启动应用）✅；
H 系统能力在 debug 签名下正确显示不可用 ✅（system 版待 priv-app 推送验证）；
I 媒体/HUD/360 视频/真车通道需真车或系统权限，模拟器不可验证。

实测中修复 5 类缺陷（触摸吞掉、面板穿透误关闭、日间主题 token、模拟器副屏焦点、UI 排版）。

## 在线更新实测（Android 10 / API 29，ecarx 平台镜像）

环境换成预装了 `com.ecarx.*` 系统的模拟器（`emulator-5554`，1920×1080@160dpi，`adb root`）。
被测包 `com.ecarx.cardesk` 走平台签名 + `sharedUserId=android.uid.system`。

| # | 项 | 操作 | 期望 | 结果 |
|---|---|---|---|---|
| J1 | 检查更新 | 设置 → 在线更新 → 检查更新（4135） | 检出 `v1.0.1042` | ✅ `检查更新：有新版本 current=1.0.1039-system latest=v1.0.1042` |
| J2 | 下载 | 点「下载并更新」 | 进度条 + APK 落地 | ✅ `cache/cardesk-update-1.0.1042.apk` 1,888,392 B |
| J3 | 静默安装 | 自动 | 无系统安装器弹窗，版本号变 | ✅ `installerPackageName=com.ecarx.cardesk`，变 4138 / `1.0.1042-system` |
| J4 | 包完整性 | 拉设备 `base.apk` 算 SHA256 | 与 `dist/` 出的包一致 | ✅ `f37ccf78…58a` 双向一致 |
| J5 | 复检 | 4138 再点检查更新 | 「已是最新」 | ✅ `检查更新：已是最新 current=1.0.1042-system latest=v1.0.1042` |

证据：`emu-verify/upd-*.png`、`emu-verify/update-4138-log.txt`、`emu-verify/REPORT-update-4138.md`。

注意：模拟器默认 HOME 是预装的旧包 `com.ecarx.qq.tool`（4354），测 `com.ecarx.cardesk`
必须显式 `am start -n com.ecarx.cardesk/com.cardesk.launcher.MainActivity`。
