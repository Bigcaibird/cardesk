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

**推荐用 ecarx 平台镜像**（`emulator-5554`，android-10 / API 29）：预装 `com.ecarx.*` 与高德 YH，
`com.ecarx.cardesk` 是**平台签名 + uid 1000**，车控链路（`AdaptapiGateway`）是**真链路**。

**模拟器验不到、要真机的**（如实标注，不要写成 ✅）：车门 0/1 语义、
频谱条是否随音乐跳动（模拟器没有能在后台持续播放的音源）、歌词与 QQ 音乐 App 的逐句一致性。

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
| H14 | 频谱口径 | 诊断包「氛围灯」段的「频谱口径」行 | `分段=24 采集点数=512 轮询=100ms 回落=0.7` | |
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
