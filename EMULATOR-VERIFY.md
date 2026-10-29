# CarDesk 模拟器验证记录

环境：macOS arm64 + Android SDK（本仓库 `toolchain/sdk`），AVD `cardesk` = android-34 / google_apis / arm64-v8a，
1920×1080 @160dpi（正好命中参考 App 的 820A 判定），headless（`-no-window -gpu swiftshader_indirect`）。

判定标准：截图 + logcat（FATAL/Exception 计数）+ `dumpsys`/`pm`/`settings` 实际状态，任一失败即记为 ❌。

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
