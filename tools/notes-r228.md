# CarDesk 4304 / 1.0.1208-system

## 这一版修了什么

**第228轮：把「窗口内渲染」那条画中画路线在领克真车（Android 9 / API 28）上逐条补齐。**
你在车上遇到的四个现象，根因全部在我们的代码里（不是高德），已经逐条修掉。

### 1. 画中画里的高德点不动 → 已修

第226轮新加的那条路线（自建虚拟屏 + `TextureView` 镜像）**从第一版起就没有任何触摸转发**；
老的 `ActivityView` 路线是 AOSP 自带的（`ActivityView.onTouchEvent` → `InputManager.createInputForwarder`
→ `IInputForwarder.forwardEvent`），我们自建的虚拟屏没有这一步，API 28 又没有
`MotionEvent.setDisplayId` 可用 ⇒ 点哪儿都没反应。

这一版补上了同样的转发器（`PipMirrorHost.forwardTouch`）：画中画里的高德现在**可以直接点、可以拖地图**。

### 2. 画中画切全屏后右侧缺一条（108px）→ 已修

镜像路线建虚拟屏后**没有调** `WindowManagerService.dontOverrideDisplayInfo(displayId)`。
AOSP 的 `ActivityView.initVirtualDisplay` 建完屏紧接着就调这一句；少了它，窗口管理器会**覆盖**
虚拟屏的显示信息 ⇒ 承载应用按另一套尺寸排版 ⇒ 只画到 1812/1920，右侧一条是空的。

这一版补上了（注意：车机 framework 里这个类是 `android.view.WindowManagerGlobal`，
不是 `com.android.internal.view.…`，反射按前者打）。

### 3. 大小调整的玻璃条看不见 → 已修

`NavPage.placePipDivider()` 把 **row 的局部坐标**当成了 `NavPage` 的坐标去摆放玻璃条，
两者差一个 `NAV_SIDE_MARGIN`（44px）⇒ 玻璃条被画到 x≈1315，**正好压在画中画自己的滚动条上**，
看上去就是「没有那条玻璃条」。这一版把坐标换算补齐，玻璃条回到两卡之间的卡缝（x≈1359）。

> 这一条与走哪条路线无关，老的 `ActivityView` 路线同样看不见。

### 4. 双画下「一画」面板没有圆角 → 已修（与单画同一个根因）

半透明窗口下 `ActivityView` 的图层不再被窗口轮廓裁剪 ⇒ 直角；走窗口内渲染路线即成立。

## 顺带修掉的一个回归：关掉视频壁纸后画中画行为变了

第227轮把「要不要走窗口内渲染」的判据放在了 **prefs**（「视频壁纸开 + SurfaceView 后端」），
但窗口**实际的 format** 是另一回事 —— 用户在设置里开关视频壁纸会**在运行中**改窗口 format；
而承载面又是「同包名复用」的 ⇒ 判据变了，承载面还挂着**上一条路线**的内容。

于是你在车上看到：关掉视频壁纸 ⇒ 窗口变不透明，但画中画**仍是镜像路线**
⇒ 全屏右侧缺一块、拖动很卡 ⇒ 「以前不用视频壁纸一切正常，现在有问题了」。

这一版：

- 判据改成**问窗口自己**（`LauncherView.isWindowTranslucent()` 读 `window.attributes.format`）；
- **路线变了就重建承载面** ⇒ 开关视频壁纸不用重启桌面就即时换路线；
- 换肤重建时镜像宿主（连同它的虚拟屏）与承载面**整块搬家**，不再把承载应用的 task 推回主屏。

## 拖动不再卡（镜像路线专有）

真车实测拖分隔条只有 ~10fps（帧间隔 34~177ms）。四个成因一起修：

| 成因 | 修法 |
| --- | --- |
| 每帧一次「反射 + IPC 到 WMS」（触摸排除区），单帧多花 9~25ms | 节流到 400ms 一次 |
| 每帧 `Class.getDeclaredMethods()` 新建 `Method` 数组 | 反射方法解析一次就缓存（找不到也缓存） |
| 每帧 3~4 条 logcat | 逐帧日志节流 400ms，并去掉一条重复的 |
| 承载应用按每个中间尺寸重画整张地图 | 比例**吸附到 12px 一格** ⇒ 一次 200px 拖动只触发约 16 次重画 |

## 怎么用

- **什么都不用改**：视频壁纸开着就继续是省电的 `SurfaceView` 后端；
  关掉视频壁纸也正常（窗口不透明时自动走 `ActivityView` 路线）。
- 画中画里可以直接点高德；卡左缘的全屏键单击进全屏、再点「还原」回来。

## 细节

- 路线由 `core/PipRenderPlan.kt` 的纯函数判定，判据是**窗口实际 format**，不是 prefs；
  路线不一致时 `needsRebuild()` 触发承载面重建。
- 镜像路线补齐了 AOSP `ActivityView` 本来就有、我们漏掉的两件事：输入转发、`dontOverrideDisplayInfo`。
- 镜像虚拟屏的尺寸变化**只 `resize`、不重建**（重建会把承载应用推到主屏）。

## 验证

- 单测 **2644 / 0 失败 / 0 错误 / 0 跳过**。
- 领克真车（`198.18.50.1:5555`，API 28）实测：
  - 触摸转发器就绪、触摸真的送到高德（画面随之变化）；
  - `dontOverrideDisplayInfo` 反射打中，全屏右侧满宽；
  - 圆角成立（单画 / 双画都正常）；
  - 玻璃条回到卡缝；
  - 开关视频壁纸即时换路线（`画中画路线变了，重建承载面 from=LAYER to=MIRROR`）；
  - `FATAL / Exception` 0。
- 模拟器（cardesk28 / API 28）复测：视频壁纸开 + 高德画中画，首页 ⇄ 导航页来回切，
  首页无黑块、切回导航页 `START` 新增 0 次、`FATAL` 0。
- 详细过程见 `emu-verify/REPORT-r228.md`。
