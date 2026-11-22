# 第225轮交接（第224轮收尾状态）

写于 2026-11-19 晚。第224轮（视频壁纸下画中画的高德跑回首页）**代码已交付、模拟器已取证**，
但**实车复测没做成** —— 车机从 20:58 起 `ping 198.18.50.1` 100% 丢包、`adb devices` 显示 `offline`。

## 一、第224轮交付物（都已落地）

| 项 | 位置 |
|---|---|
| 交付包 | `dist/CarDesk-platform-ecarx-cardesk-4301.apk`（`versionCode 4301` / `1.0.1205-system`；平台证书 SHA-256 `c8a2e9bc…92ab8`；APK SHA-256 `84d12ff4…4a78`，3664131 字节） |
| 更新清单 | `dist/cardesk-latest.json`（已由 release.sh 写成 4301） |
| R2 | **已上传**：`https://file.chuxi.bid/cardesk/latest.json` + `cardesk/CarDesk-platform-ecarx-cardesk-4301.apk`（回读校验过，HTTP 200 实测） |
| 应用内更新说明 | `tools/notes-r224.md` |
| 本轮报告 | `emu-verify/REPORT-r224.md`（含两条根因、修法、实测日志、截图） |
| 逐轮记录 | `CHANGELOG.md` 末尾「第224轮」段 |
| 截图 | `emu-verify/r224-01-pip-video-wallpaper.png`、`emu-verify/r224-02-after-home-return.png` |

代码改动：

- 新增 `app/src/main/java/com/cardesk/launcher/core/PipSampleGate.kt`（alpha 闸门纯逻辑）
- 新增 `app/src/main/java/com/cardesk/launcher/core/HostTaskDisplay.kt`（四态判据纯逻辑）
- 改 `app/src/main/java/com/cardesk/launcher/ui/pip/PipFrameProbe.kt`（`blind` / `consecutiveBlind` / `opaquePct`）
- 改 `app/src/main/java/com/cardesk/launcher/ui/embed/ActivityEmbed.kt`（`hostTaskInfo` / `hostTaskState`，`ReadyStarter.onSurfaceCreated()` 重建前先判归属）
- 改 `app/src/main/java/com/cardesk/launcher/ui/pip/PipSurface.kt`（判据下沉，四态→三态映射）
- 改 `app/src/main/java/com/cardesk/launcher/ui/pip/PipBlackHeal.kt`（`isTaskOffDisplay` 委托 core）
- 新增测试 `app/src/test/java/com/cardesk/launcher/core/PipSampleGateTest.kt`（17）、`HostTaskDisplayTest.kt`（14）

全量单测 **2634 / 0 failures / 0 errors / 0 skipped**（基线 2602 + 32）。

## 二、下一轮第一件事：把 4301 的实车复测补上

车机回来（`adb connect 198.18.50.1:5555`）后按这个顺序做：

1. 装 4301：`adb -s 198.18.50.1:5555 install -r dist/CarDesk-platform-ecarx-cardesk-4301.apk`
2. 导入视频壁纸 → 开画中画（高德）→ 看是否还跳回首页。
3. 抓这几条日志（`CarDesk/NavUI`）判断修复是否在车上生效：
   - `画中画画面采样：看不见承载面（半透明窗口/透明洞），不判黑 … opaquePct=…`
     ⇒ 修复 A 生效（**车上是关键**：车机才真的会有视频壁纸 + 高德这套组合）。
   - `承载面重建，判不准 task 在哪个屏，按「没黑」处理，不重新拉起 … state=UNKNOWN`
     ⇒ 修复 B 生效。
   - 反例：任何 `START u0 … pkg=com.autonavi.amapautp.yh` 且 `from uid 1000` ⇒ 还有别的重启路径没堵住，
     把**那一刻往前后 30 秒**的 `CarDesk/NavUI` + `ActivityManager` 日志一起带回来。
4. 顺手记一下车上 `opaquePct` 的实际数值（0 / 个位数 / 几十）——这决定 0.02 这个阈值要不要调。

## 三、第224轮留下的两个坑（都是**故意不做**，不是遗漏）

### 1. API 28 上「task 在哪块屏」判不出来（已写成代码注释）

`ActivityManager.RunningTaskInfo` 在 API 28 **没有 `displayId` 字段**（API 29 才有）。
在 5560 上用 `app_process` 探针核对过运行时字段清单。
两条补证据的路都实测不成立：

- `stackId` → `IActivityManager.getAllStackInfos()` 的 `StackInfo.displayId`：
  探针里成立（实测 stack 31→0、32→1），但**应用进程里拿不到入口** ——
  `ActivityManager.getService()` 被隐藏 API 限制拦住。
- 比 `configuration.screenWidthDp`（`HostTaskMigrator` 第141轮就用的判据）：
  **surface 重建那一瞬间不可信** —— 正在被搬回虚拟屏的 task，配置还停在主屏尺寸
  （实测同一时刻 `size=1920x848`，而画面明明在虚拟屏里），会被误判成「被搬到主屏」
  ⇒ 触发重启 ⇒ 正是本轮要修的现象。

⇒ 现状：车机与基准模拟器（都是 API 28）上四态**一律 UNKNOWN**，重建时**一律不重启**。
这是安全侧。要补证据的话，得先找到一条**不被隐藏 API 拦**的入口
（比如车机是平台签名系统应用，试试 `ServiceManager.getService("activity")` + `IActivityManager.Stub.asInterface` 在车上是否可用），
**必须先在车上验证入口可用**，再动判据。

### 2. 视频壁纸右侧裁切（用户另一条反馈）

`LauncherView.applyVideoWindowFormat()` 那条路（第224轮起点文档里有）—— 本轮完全没碰。

## 四、环境备忘（下一轮省时间）

- **出包必须先设 `ANDROID_USER_HOME`**（DSH 沙箱下 `~/.android` 不可写，
  AGP 会因为建不了 debug keystore 直接失败）：
  ```bash
  export ANDROID_USER_HOME="$TMPDIR/cardesk-android-home"   # 里面要有一份 debug.keystore
  bash tools/release.sh --notes-file tools/notes-rNNN.md
  ```
- `release.sh` 失败也会**先 bump version.properties**（本轮因此多出一个 4302），
  重出时用 `--no-bump` 并手动把 `version.properties` 改回目标版本。
- 基准模拟器：`bash tools/emu28.sh start|wait|status|stop`（端口 5560，可 `adb root`）。
  模拟器上同时装着调试包 `com.cardesk.launcher`（会一起刷 `CarDesk/NavUI` 日志，按 pid 过滤）。
- 模拟器里把画中画跑起来的土办法：`adb root` 后改
  `/data/data/com.ecarx.cardesk/shared_prefs/cardesk.xml`
  （`pip_app` = 承载包名、`last_page` = 1；改完 `chown 1000:1000`、`chmod 660`）。
- **模拟器的 task 归属本身不稳**：`承载任务搬移重试` 反复出现、偶发
  `承载任务没能搬到我们的虚拟屏 … 已达最大尝试次数`。这是第141轮起就有的既有现象，
  不是第224轮引入的；出现它时画中画可能显示成全屏或黑块，**重开一次进程通常就好了**。
