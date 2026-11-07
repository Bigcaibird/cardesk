# 第130轮 验证报告：修复画中画黑屏

> 用户诉求：「修复黑屏问题」。
> 现象：画中画黑屏，要「全屏一次再还原画中画」才看得到。

## 1. 环境

| 项 | 值 |
|---|---|
| 模拟器 | `emulator-5556`（Android 10 / SDK 29 / arm64-v8a / 1920×1080） |
| 包名 | `com.ecarx.cardesk`（**system 变体**，与真车同包名，uid 1000） |
| 画中画承载应用 | `com.autonavi.amapautp.yh`（高德地图车机版 9.5） |
| 桌面版本 | 4229 / 1.0.1133-system |
| 平台证书 | SHA-256 `c8a2e9bccf597c2fb6dc66bee293fc13f2fc47ec77bc6b2b0d52c11f51192ab8` |

## 2. 根因：黑屏**从来没被桌面发现过**

第129轮排查时撞见「画中画黑屏」，查下来是**一整条链子断了**：

| # | 断点 | 证据 |
|---|---|---|
| 1 | **探针从来没工作过** | 诊断日志里「画中画画面采样」一条都没有，连「采样失败」也没有 —— 旧代码取不到 surface 时是**静默 return** |
| 2 | **黑屏因此永远不被发现** | 自愈逻辑从来没被触发过 |
| 3 | **自愈只有一次机会** | `blackHealDone: Boolean`，自愈一次不奏效就**永久黑屏** |
| 4 | **自愈动作只有一档** | 只有「重发一次启动」—— task 真挂在旧 VirtualDisplay 上时救不回来 |
| 5 | **看门狗多进程判据缺陷** | 只认主进程名 → 高德明明在发导航状态却被判「承载进程不在」→ 反复重发启动打断正常应用 |
| 6 | **诊断文案共用** | resize-relaunch / 自愈都写「画中画看门狗：…」 → 排障时根本分不清来源 |

## 3. 关键发现：**像素采样根本看不见 ActivityView 的画面**

第130轮把采样源从「ActivityView 的 SurfaceView surface」改成「窗口 + 限定矩形」之后，
采样**成功**了，但读到的却是 **maxLuma=0 / avgLuma=0** —— 而同一时刻屏幕上高德明明在渲染地图。

| 采样方式 | 结果 |
|---|---|
| `PixelCopy.request(window, srcRect, …)`（限定在承载面那块） | 成功，但 **maxLuma=0 / avgLuma=0 / brightPct=0** —— 恒为黑 |
| `PixelCopy.request(surface, …)`（ActivityView 的 SurfaceView） | **result=3** = `ERROR_SOURCE_NO_DATA`，一帧都采不到 |

**原因**：`ActivityView` 内部是 `SurfaceView`，它的内容画在**独立的 SurfaceFlinger 图层**上；
而窗口自己的缓冲在那个位置是**透明的洞**（SurfaceView 会在窗口上打洞）。
`PixelCopy` 拷窗口拷到的是**窗口缓冲**，**拷不到那个图层**。

证据文件：`y130-log-falseblack.txt`（含截图 `y130-02-pixel-falseblack-amap.png`）。

> **这一度把「正常的高德」判成了黑屏并重启了它** ——
> 像素采样在「我们看不见应用画面」和「应用画面是黑的」之间**无法区分**，
> 当判据用必然误伤。

## 4. 修复：判据换成**权威信号**

跟 AOSP 官方 `VirtualDisplay Launcher` 示例判活的方式一致：

```
task.displayId == virtualDisplay.getDisplayId()
```

> **承载应用的 Activity 还挂在我们的 VirtualDisplay 上吗？**
> 不在 → 我们的 VirtualDisplay 上什么都没有 → **这就是黑屏的成因本身**。

### 4.1 改了什么

| 文件 | 改动 |
|---|---|
| `ui/pip/PipBlackHeal.kt`（新） | 纯逻辑：判据 + **分级**自愈决策（重发启动 → 重建承载面 → 放弃）+ 冷却 + `isTaskOffDisplay()` |
| `ui/pip/PipSurface.kt` | `blackHealDone:Boolean` → `blackHealStage:Int`（分级）；新增 `rebuildContent()`；`isTaskOnOurDisplay()`；`isProcessAlive()` 改多进程判据；`relaunch(reason)` 带来源；`rebuildContent()`；看门狗加**前台闸** |
| `ui/embed/ActivityEmbed.kt` | `virtualDisplayIdOf()`（取 VirtualDisplay 的 displayId，**避开 `mHost.mDisplay` 那个坑**）、`displayIdOfTask()`（反射读隐藏字段 `TaskInfo.displayId`）、`windowOf()` |
| `ui/pip/PipFrameProbe.kt` | pending 超时复位；采样失败留痕；采样矩形限定到承载面并内缩；判黑加「亮像素占比」「整体很暗」两条；日志限频 |
| `core/HomeForeground.kt`（新） | 桌面是否在前台（`MainActivity.onResume/onPause` 维护） |
| `MainActivity.kt` | 维护 `HomeForeground` |

### 4.2 两个必须说清的坑

1. **`ActivityView` 里有两块「display」**：
   `mHost.mDisplay` 是**承载面自己所在的那个显示器**（我们桌面的 display 0），
   `mVirtualDisplay` 才是虚拟显示器。
   拿错 → 跟任何 task 都对不上 → **把正常画面判成「任务不在我们的 display 上」**（实测踩过）。
2. **自愈只能在桌面在前台时做**：
   用户点「全屏打开」把应用拉到全屏时，它的 task 确实从我们的 VirtualDisplay 搬走了；
   那时自愈会把应用**抢回画中画**，打断用户正在用的全屏应用（比黑屏更烦人）。
   → 加 `HomeForeground` 闸。

### 4.3 分级自愈

| 阶段 | 动作 | 冷却 |
|---|---|---|
| 0 | 重发一次启动（轻） | 8s |
| 1 | **重建承载面**（重：释放旧 VirtualDisplay 再干净重建） | 20s |
| 2 | **放弃**（绝不无限重启 —— 反复重启比一直黑更烦人） | — |

## 5. 模拟器端到端验证

### 5.1 健康态 —— 高德在画中画，**不再误判**

`y130-01-healthy-pip-amap.png`、`y130-log-healthy.txt`

```
画中画显示 pkg=com.autonavi.amapautp.yh app=Amap Auto mode=embedded role=主画
画中画承载任务所在 display our=37 tasks=[37] onOurs=true
```

观察 60 秒：**画中画黑屏自愈动作 = 0 次**（旧像素判据在这段时间里会误重启高德）。

### 5.2 故障注入 —— 判黑 → 重发启动 → 重建承载面 → 放弃

`y130-log-heal-chain.txt`

注入方式：把 `virtualDisplayIdOf()` 的返回值故意 +1（等价于「task 不在我们的 display 上」）。

```
10:53:27.790 W 画中画承载任务不在我们的 VirtualDisplay 上 our=36 tasks=[35] onOurs=false
10:53:27.790 W 画中画黑屏自愈：重新拉起承载应用 … by=black-heal        ← 第 1 级
10:53:48.783 W 画中画黑屏自愈：重建承载面 …                            ← 第 2 级（21s 后）
10:53:48.793 I 画中画承载面重建 pkg=com.autonavi.amapautp.yh role=主画
10:54:55.649 W 画中画承载任务不在我们的 VirtualDisplay 上 our=37 tasks=[36]
                 ↑ 重建之后 67 秒内没有再自愈 → 第 3 级：放弃
```

**自愈动作总次数 = 2**，之后停手 —— 绝不无限重启承载应用。

### 5.3 task 迁到别的 display 上（用户场景）

`adb shell am start --display 0 …` 把高德的 task 从我们的 VirtualDisplay 挪到 display 0：

```
Display #0  (activities from top to bottom):
    * TaskRecord{fc3eb5 #709 A=com.autonavi.amapautp.yh …}
Display #31 (activities from top to bottom):   ← 我们的承载面，空的
```

回到桌面后：桌面**重建承载面**（新的 VirtualDisplay #32），高德的 task 随之迁移过去，
`our=32 tasks=[32] onOurs=true` → 不判黑、画面正常。

## 6. 单测与反向验证

| 项 | 结果 |
|---|---|
| 全量单测 | **1736 项全绿**（第129轮 1720 → +16） |
| 新增单测 | `PipBlackHealTest`（判据/分级/冷却/放弃/display 判定 12 项）、`PipFrameProbeTest` 第130轮新增 10 项 |
| 反向验证 | `tools/verify_y130_reverse.sh`：**9 条全部变红**，还原后 0 红灯 |

反向验证逐条结果（`RED` = 失败用例数）：

| # | 改坏的地方 | RED |
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

## 7. 产物

| 项 | 值 |
|---|---|
| 文件 | `dist/CarDesk-platform-ecarx-cardesk-4229.apk` |
| versionCode / versionName | 4229 / 1.0.1133-system |
| 大小 | 2263290 字节 |
| SHA-256 | `965606aa6615b4b5f383d5cd477ab2a434003f26e39032d898e4e79303b460b7` |
| 签名 | AOSP platform（SHA-256 `c8a2e9bccf597c2fb6dc66bee293fc13f2fc47ec77bc6b2b0d52c11f51192ab8`） |

## 8. 遗留 / 待真车复验

1. **真车（Android 9 / API 28）上 `PixelCopy` 的行为可能不同** ——
   本轮结论（窗口拷贝看不见 SurfaceView 图层）在模拟器 API 29 上成立，
   真车 API 28 未验证；不过**判黑已经不依赖像素**了，所以真车只影响诊断日志，不影响修复。
2. `TaskInfo.displayId`（隐藏字段）与 `ActivityView` 的 `mVirtualDisplay` 字段名在 API 28 上需实机确认
   —— 取不到时判据会退化成「不知道」（**不误判**），并在日志里留痕。
3. **没有发 GitHub Release、没有传 R2**（等明确指示）。
4. 用户车上 QQ 音乐 / 网易云 / 汽水的实际版本仍未验证。
