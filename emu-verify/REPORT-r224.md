# 第224轮 · 视频壁纸下画中画的高德跑回首页（「看不见」被当成「黑」+ 承载面重建无条件重启）

> 用户反馈：「**导入视频壁纸后画中画里的高德跑回首页了**」（导入视频壁纸后显示混乱另案）。
>
> 交付版本 **4301**（`1.0.1205-system`）。

### 一、问题

视频壁纸一开，画中画里的高德就会**自己跳回首页**，有时连着跳几次；
把视频壁纸换回静态壁纸，现象消失。

查下来是**两条互不相干的缺陷叠在一起**，任何一条都会让承载应用被重启：

| # | 缺陷 | 机制 |
|---|---|---|
| 1 | **「看不见」被当成「黑」** | 画中画画面是叠在桌面窗口上的一层**独立 Surface**，桌面窗口在那一块是**透明的洞**（alpha=0、RGB=0）。旧的黑屏检测只看 `maxLuma / avgLuma / brightRatio`，**不看 alpha** —— 透明与纯黑在像素数据上完全一样 ⇒ 「我们看不见它」被判成「它黑屏了」⇒ 连续 3 帧就触发自愈（重建承载面 → 重启承载应用）。视频壁纸把桌面窗口切成 `TRANSLUCENT`（`LauncherView.applyVideoWindowFormat()`），采样**恒为 0**，于是必误判。 |
| 2 | **承载面重建时无条件重启** | 导入视频壁纸要弹文件选择器 ⇒ 桌面 `onPause/onStop` ⇒ `ActivityView` 的 surface 销毁（AOSP 9 只 `VirtualDisplay.setSurface(null)`，不 release）⇒ 回桌面时 surface 重建。旧代码在重建分支里**无条件** `startActivity`，“不管承载应用是不是还在那儿”。 |

实车日志里的铁证（第224轮交接文档）：

```
START u0 {act=MAIN ... pkg=com.autonavi.amapautp.yh cmp=.../UsbFillActivity} from uid 1000   # cardesk
20:25:20 / 20:25:39 / 20:25:41 —— 四次
```

### 二、这一轮怎么解

两条各修各的，**都不改主路径**：

| # | 修法 | 落点 |
|---|---|---|
| A | **alpha 闸门**：每帧先算「不透明像素占比」`opaqueRatio`（`alpha >= 128` 算不透明）。占比 < **0.02**（64×36=2304 点，≈46 点）⇒ 判 **BLIND**：**不累加**黑帧、**清零**黑帧计数、单独记一条日志；`sawContent` 保持粘性 | `core/PipSampleGate.kt`（新）+ `ui/pip/PipFrameProbe.kt` |
| B | **承载面重建前先判 task 在哪块屏**：判据下沉成 `ActivityEmbed.hostTaskInfo()`（与黑屏检测共用一份，避免逻辑分叉），四态 `ON_OUR_DISPLAY / OFF_OUR_DISPLAY / NOT_FOUND / UNKNOWN`；重建分支只在「不在我们屏上 / 找不到」时才重新拉起 | `core/HostTaskDisplay.kt`（新）+ `ui/embed/ActivityEmbed.kt` + `ui/pip/PipSurface.kt` + `ui/pip/PipBlackHeal.kt` |

**A 的判据顺序**（`PipSampleGate.classify`）：

```
opaqueRatio < 0.02        → BLIND   （看不见，不判黑）
否则 黑像素判据成立        → BLACK   （真黑屏，照常自愈）
否则                      → CONTENT
```

**B 的判据顺序**（`HostTaskDisplay.shouldRelaunch`）：

| 状态 | 含义 | 重建时 |
|---|---|---|
| `ON_OUR_DISPLAY` | task 就在我们的虚拟屏上 | **不重启** |
| `OFF_OUR_DISPLAY` | task 被搬到主屏了（真该救） | 重启 |
| `NOT_FOUND` | 找不到承载应用的 task（应用没了） | 重启 |
| `UNKNOWN` | **判不准**（`HostTaskMigration` 正在搬栈、或读不到 displayId） | **不重启** |

> 原则：**判不准就当没黑**。误判成 OFF/NOT_FOUND 的代价是**重启用户正在用的应用**——
> 那正是本轮要修的现象；而「应用真的没了」还有**进程看门狗**兜底
> （连续 `WATCHDOG_MISS_LIMIT = 3` 次看不到进程才动手）。

### 三、API 28 上的一个硬限制（实测，写进代码注释）

`ActivityManager.RunningTaskInfo` 在 **API 28 没有 `displayId` 字段**（API 29 才有）。
在基准模拟器上用 `app_process` 把运行时字段打出来核对过：

```
baseActivity, configuration, description, id, lastActiveTime, numActivities,
numRunning, resizeMode, stackId, supportsSplitScreenMultiWindow, thumbnail, topActivity
```

两条补证据的路这一轮都**主动放弃**：

| 备选 | 为什么不用 |
|---|---|
| `stackId` → `IActivityManager.getAllStackInfos()` 的 `StackInfo.displayId` | 探针里成立（实测 stack 31→0、32→1），但**应用进程里拿不到入口** —— `ActivityManager.getService()` 被隐藏 API 限制拦住（`getDeclaredMethod` 直接抛/返回 null） |
| 比 `configuration.screenWidthDp`（`HostTaskMigrator` 第141轮起用的判据） | 在 **surface 重建瞬间不可信**：正在被搬回虚拟屏的 task，那一刻配置还停在主屏尺寸 ⇒ 会被误判成「被搬到主屏」⇒ 触发重启 ⇒ 正是用户报的现象。模拟器实测也能看到 `size=1920x848` 与屏幕实际归属不一致 |

⇒ 结论：**车机与基准模拟器（都是 API 28）上 `displayIdOfTask()` 恒为 null**，
四态判据**一律 UNKNOWN** ⇒ 承载面重建时**一律不重启**。
这正是本轮想要的安全侧；要不要补第二套证据，留给拿到实车日志后的下一轮。

### 四、实测（`cardesk28` AVD / 5560，装的是 4301 同一份包）

场景：**prefs 里 `design_video_wallpaper=true`**（视频壁纸）+ 画中画承载 `com.cardesk.embedtest`。

**修复 A —— 看不见就不再判黑**：

```
D CarDesk/NavUI: 画中画虚拟显示器 id our=7 pkg=com.cardesk.embedtest role=主画
W CarDesk/NavUI: 画中画画面采样：看不见承载面（半透明窗口/透明洞），不判黑
    opaquePct=0 maxLuma=0 avgLuma=0 blind=1 rect=237,203,1138,810
    note=窗口缓冲在该位置是透明洞，像素与纯黑同数据，不代表应用画面是黑的
```

⇒ 改前这条会走「黑帧 +1」，连 3 帧就重建承载面；**改后 opaquePct=0 直接 BLIND，黑帧计数被清零**。

**修复 B —— 承载面重建不再重启**：

```
D CarDesk/NavUI: 承载面销毁 pkg=com.cardesk.embedtest times=1
（HOME 键回桌面 → ResumedActivity 变成桌面 → 再拉起 cardesk）
D CarDesk/NavUI: 承载面重建，判不准 task 在哪个屏，按「没黑」处理，不重新拉起
    pkg=com.cardesk.embedtest times=2 state=UNKNOWN vid=1
```

⇒ **没有** 任何 `START ... com.cardesk.embedtest`（旧代码在这里一定会 `startActivity`）。

| 时点 | 截图 | 结果 |
|---|---|---|
| 视频壁纸 + 画中画 | `emu-verify/r224-01-pip-video-wallpaper.png` | 画中画里 EMBED TEST 正常显示、**不是黑块**；Dock / 媒体卡 / 壁纸都完整 |
| HOME 后再回桌面 | `emu-verify/r224-02-after-home-return.png` | 画中画仍是原来那页，**没有跳回首页** |

**logcat `FATAL / Exception` 计数**：`0 / 0`（4301 进程存活，未重启）。

### 五、单测

| 文件 | 条数 | 覆盖 |
|---|---|---|
| `app/src/test/java/com/cardesk/launcher/core/PipSampleGateTest.kt` | 17 | 全透明⇒BLIND、不透明且暗⇒BLACK、黑盲交替**永远到不了**阈值、streak 累加/清零、sawContent 粘性、阈值边界 |
| `app/src/test/java/com/cardesk/launcher/core/HostTaskDisplayTest.kt` | 14 | 四态映射、多 task 任一在、displayId 全 null / 部分 null、搬栈中按 UNKNOWN、`shouldRelaunch` 只认 OFF/NOT_FOUND |
| `app/src/test/java/com/cardesk/launcher/ui/pip/PipFrameProbeTest.kt` | +1 | alpha 闸门常量与 `PipSampleGate` 同源（128 / 0.02） |

全量：**2634 / 0 failures / 0 errors / 0 skipped**（基线 2602 + 新增 32）。

### 六、影响面

| 场景 | 影响 |
|---|---|
| 静态壁纸（默认场景） | **一个像素都不变** —— alpha 闸门只对「采样区不透明像素不足」的情况生效，普通窗口恒为不透明 |
| 真黑屏（承载应用自己渲染黑） | **照常自愈** —— 不透明像素占比足够 ⇒ 仍走 BLACK 判定 |
| 视频壁纸 | 不再误判黑 ⇒ 不再反复重建承载面 |
| 弹文件选择器 / 回桌面 | 不再无条件重启承载应用 |
| 第223轮的胶囊配色 | 未动 |

### 七、发布

| 项 | 结果 |
|---|---|
| R2 清单 | `https://file.chuxi.bid/cardesk/latest.json` → HTTP 200，2929 字节，`versionCode=4301` / `1.0.1205` |
| R2 安装包 | `https://file.chuxi.bid/cardesk/CarDesk-platform-ecarx-cardesk-4301.apk` → HTTP 200，3664131 字节，`application/vnd.android.package-archive` |
| 回读校验 | 上传脚本自带（长度 + SHA-256），两份都一致 |

### 八、本轮未做

- **没做实车复测**：车机 `198.18.50.1:5555` 在 20:58 起 **ping 不通 / `adb` offline**
  （头单元睡了或熄火），只能以基准模拟器 + 单测交差。
- **视频壁纸右侧裁切**（另一个反馈）未在本轮处理。
- **API 28 上补 displayId 的证据**（栈表 / 配置宽度）主动放弃，理由见第三节。
- **未提交 Git**。
