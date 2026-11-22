# 第227轮报告 · 修掉验收查出的三个问题 + 画中画「切全屏 / 还原」打通

- 交付版本：**4304** / `1.0.1208-system`
- 交付物：`dist/CarDesk-platform-ecarx-cardesk-4304.apk`
  （3663679 字节，SHA-256 `1458765c6cfa40d5f7324e6672ef2a0b0a681cb323c7aa0b34c63a075f8c19ab`，平台证书 `c8a2e9bc…92ab8`）
  （⚠️ 本轮 4304 出过**两版**：先出的一版带「渲染后端」设置项，
  按要求撤掉那个入口后用 `--no-bump` 重出，**以上面这个 SHA 为准**，旧的那版作废）
- R2：**本轮未上传**（本机没有 `R2_AK` / `R2_SK` 环境变量），`dist/cardesk-latest.json` 已按最终更新说明重生成
- 验证环境：AVD `cardesk28`（Android 9 / API 28 / arm64 / 平台签名），`emulator-5560`
- 实车：`198.18.50.1:5555` **仍离线**（`ping` 100% 丢包），实车复测第 225/226/227 三轮**都还没做**
- 装机一致性：模拟器上 `com.ecarx.cardesk` 的 `base.apk` 与 `dist/` 里那份 **SHA-256 完全相同**
  （`adb pull` 后比对：`1458765c…c19ab`）⇒ 本报告所有截图/日志就是这一份包跑出来的

---

## 一、上一轮验收查出来的问题

第225/226轮的功能性改动**全部通过**（高德确实承载在虚拟屏、不抢主屏、圆角、切页无黑块、切回不重启、`FATAL` 0），
但验收指出 1 个必须修的 + 2 个次要问题：

| # | 问题 | 严重度 | 根因 |
|---|---|---|---|
| ① | **默认配置走的还是掉帧那条路** | **必修** | `core/Prefs.kt` 里 `videoWallpaperBackend` 默认值仍是 `BACKEND_TEXTURE_VIEW(1)`，与 4303 更新说明「后端保持 `0`」自相矛盾 ⇒ 上一轮声称的「回到省电方案」在默认配置下**根本没生效**。**修法：默认值改回 `SurfaceView`**（不加工期外的设置项） |
| ② | SurfaceView 路线冷启动视频起播两次 | 次要 | 画中画 MIRROR 路线自建虚拟屏时，系统给那块虚拟屏下发一份 display config override，连带把我们窗口的 `SurfaceView` surface 重建 ⇒ 播放器被换掉，新播放器从第一帧开始 ⇒ 用户看到「开机后视频跳回开头重播」 |
| ③ | REPORT-r226.md 写「逐字节相同」 | 次要 | 措辞过强：实测是**静止子区域逐字节相同**，整卡差 0.294%（来自高德自己的地图动画） |

另外，第226轮新加的「窗口内渲染（MIRROR）」路线在**画中画切全屏 / 还原**这条路上还有两个洞
（详见 `REPORT-r225-r226-acceptance.md`）：切一次全屏画面就黑；全屏 ↔ 还原之后画面不重排。

## 二、这一版怎么改的

### 2.1 问题①：默认值改回 SurfaceView

- `core/Prefs.kt`：`videoWallpaperBackend` 默认值改回 `VideoWallpaper.BACKEND_SURFACE_VIEW`，
  并重写注释，说明第227轮为什么改回（4303 的更新说明与代码口径不一致）。
- `core/VideoWallpaper.kt`：新增两个**纯函数**，把两条口径合成一处：
  - `needsTranslucentWindow(videoOn, videoEnabled, backend)` —— 窗口要不要半透明；
  - `targetWindowFormat(videoOn, videoEnabled, backend)` —— 窗口 format 的目标值。
  以前「窗口透明性」和「画中画路线」两处各写各的，口径一歪就是「直角 / 错位 / 切页残留黑卡」那三个老毛病。
- `MainActivity.kt`：新增 `applyWindowFormatBeforeCreate()`，在 `onCreate` 里 `setContentView` **之前**就把窗口 format 定好
  （不透明则完全不动窗口）。
- **没有加「渲染后端」设置项**（验收曾建议补一个 App 内开关，本轮按要求只改默认值）：
  后端是「装一次就不动」的设置，默认值改对之后，车机用户**不需要任何操作**。

### 2.2 问题②：surface 被换时**记位置、续播**

`ui/LauncherView.kt`：`surfaceDestroyed` 时把 `currentPosition` 记进 `videoResumePositionMs`，
新播放器起来后 `seekTo(resume, SEEK_CLOSEST)` 接着播 ⇒ **用户看不到「跳回开头」**。

> **试过并作废的一条路**：同一个 `MediaPlayer` 用 `setSurface()` 换输出面。
> 实测模拟器上**画面全黑**（壁纸亮度 21.9，正常是 129~212）⇒ 解码器没有真的换输出面，这条路不可用。

### 2.3 画中画切全屏 / 还原的两个洞

- `ui/pip/PipMirrorHost.kt`：`ensureDisplay` 在虚拟屏**已存在**时不再 early-return，
  而是**重新 `setSurface` + `resize`** 并重新登记 displayId；
  `onSurfaceTextureSizeChanged` 改调 `resize`；新增 `relaunch()`。
  ⇒ 进/出全屏时 `TextureView` 的 `SurfaceTexture` 销毁重建，虚拟屏会被**重新指回新 surface**
  （不重建虚拟屏 —— 重建会把承载应用推到主屏）。
- `ui/pip/PipSurface.kt`：新增 `relaunch()`（mirror ⇒ 镜像宿主自己发；layer ⇒ `ActivityEmbed.startActivity`）
  与 `rebuildForRenderPlan()`。
  ⇒ 上一版靠「重新发一次启动」让应用按新尺寸重排，但镜像路线的承载面是 `TextureView`，**没有 `startActivity`**，
  旧代码**根本没重新拉起**；现在由镜像宿主自己发（它知道自己的虚拟屏 id）。
- `ui/LauncherView.kt`：承载面重建时**先设 `translucentWindow` 再 `rebuildForRenderPlan()`**，
  否则会按老路线重建。（第227轮一度有个运行时切换后端的入口，连带改了这里；
  按要求撤掉那个入口后，这条顺序约束**依然成立**，保留。）

### 2.4 问题③：措辞

`emu-verify/REPORT-r226.md` 与 `tools/notes-r226.md` 的「逐字节相同」统一改成
**「静止子区域逐字节相同；整卡差异 0.294%（来自高德自己的地图动画）」**。

## 三、实测（模拟器 `cardesk28` / 5560，最终包 4304）

### 3.1 默认配置（`design_video_wallpaper_backend` 这个键**根本不存在**）

配置：`pip_app=com.autonavi.amapautp.yh`、视频壁纸开、`last_page=1`、**后端键已删除** ⇒ 走代码默认值。

```
I CarDesk/App: 窗口 format：TRANSLUCENT（建窗前定好） backend=0
I CarDesk/Setting: 视频壁纸已开始播放 bytes=60878141 size=1920x1080 backend=SurfaceView
I CarDesk/NavUI: [EVT] 画中画显示 pkg=com.autonavi.amapautp.yh app=Amap Auto mode=mirror role=主画
I DisplayManagerService: Display device added: DisplayDeviceInfo{"CarDesk 画中画": …, 1287 x 867, …, type VIRTUAL, owner com.ecarx.cardesk (uid 1000)}
I CarDesk/NavUI: 画中画窗口内渲染就绪 pkg=com.autonavi.amapautp.yh display=28 size=1287x867
I CarDesk/NavUI: 承载任务搬到我们的虚拟屏 pkg=com.autonavi.amapautp.yh api=STACK_TO_DISPLAY task=899 to=28
I CarDesk/Setting: 视频壁纸：surface 换新，从原位置接着播 positionMs=515
D CarDesk/NavUI: 画中画画面采样：状态变化 black=false opaquePct=100 maxLuma=255 avgLuma=235
```

| 判据 | 结果 | 证据 |
|---|---|---|
| 默认后端 | ✅ `backend=0`（SurfaceView，HWC 直通），**与更新说明一字不差** | logcat |
| 窗口 format | ✅ `TRANSLUCENT`，**建窗前就定好**（不再中途改 format） | logcat |
| 画中画路线 | ✅ `mode=mirror`（自建虚拟屏 + `TextureView` 镜像），虚拟屏 1287×867 | logcat |
| 高德在虚拟屏 | ✅ `Task id #899`（`MainMapActivity`）挂在 **Display #28**；**Display #0 只有桌面** | `dumpsys activity activities` |
| 不抢主屏 | ✅ 主屏 `mResumedActivity` 仍是 `com.ecarx.cardesk/…MainActivity` | `dumpsys` |
| 画面不是黑的 | ✅ `black=false opaquePct=100` | logcat |
| 圆角 / 首页无黑块 | ✅ 首页截图干净、壁纸照常 | `r227-12-home.png` |
| 视频不跳回开头 | ✅ surface 换新后 `从原位置接着播 positionMs=515`（不再从 0 开始） | logcat |
| 视频确实在播 | ✅ 3 秒间隔天空区 **54.50%** 字节在变 | 两帧比对 |
| 切页不重启 | ✅ 首页 ⇄ 导航页来回切，高德 `START u0` **仍为 4 条**（无新增） | logcat |
| 崩溃 | ✅ `FATAL / Exception` **0** 条，pid `20852` 全程未重启 | logcat |

### 3.2 画中画 → 全屏 → 还原（用户追加要求的那项）

**进全屏**（点画中画卡左缘的全屏键 ≈ (64,510)）：

```
I CarDesk/NavUI: [EVT] 画中画全屏切换 to=全屏 pkg=com.autonavi.amapautp.yh
I CarDesk/Dock: 画中画全屏态同步 fullscreen=true
I CarDesk/NavUI: 画中画窗口内渲染：已重新接上新的 surface pkg=com.autonavi.amapautp.yh display=28 size=1920x968 ok=true
```

⇒ 虚拟屏从 1287×867 **resize** 到 1920×968（避让 Dock），**没有重建**
（重建会把承载应用推到主屏）；新的 `SurfaceTexture` 到位后虚拟屏**被重新指过去** ⇒ 画面不黑。
截图 `r227-10-fullscreen.png`：高德铺满，左缘出现「还原」键。

**还原**（点 ≈ (15,540)）：

```
I CarDesk/NavUI: [EVT] 画中画全屏切换 to=还原 pkg=com.autonavi.amapautp.yh
I CarDesk/NavUI: 画中画归位到地图卡 pkg=com.autonavi.amapautp.yh
I CarDesk/NavUI: 画中画窗口内渲染：已重新接上新的 surface pkg=com.autonavi.amapautp.yh display=28 size=1287x867 ok=true
W CarDesk/NavUI: 画中画重新拉起承载应用 pkg=com.autonavi.amapautp.yh role=主画 by=resize route=mirror
```

⇒ 虚拟屏 resize 回 1287×867、surface 重新接上、承载应用**按新尺寸重新拉起**（`route=mirror`，
证明第226轮「镜像路线没有 `startActivity` 所以根本没重新拉起」那个洞已补上）。
截图 `r227-11-restored.png`：高德回到地图卡、四角圆角、左缘按钮变回全屏键。

| 判据 | 结果 |
|---|---|
| 进全屏不黑屏 | ✅ `已重新接上新的 surface … ok=true`，截图可见高德铺满 |
| 全屏时壁纸仍在背后 | ✅ 截图上沿仍是视频壁纸 |
| 还原后画面回来 | ✅ `to=还原` → `画中画归位到地图卡` → `ok=true` |
| 还原后尺寸正确 | ✅ 虚拟屏 `size=1287x867` |
| 还原后应用重排 | ✅ `画中画重新拉起承载应用 … by=resize route=mirror` |
| 还原后仍在虚拟屏 | ✅ `Task #899`（`MainMapActivity`）仍在 **Display #28**，主屏只有桌面 |
| 还原带来的重启 | ⚠️ 高德 `START u0` 由 2 条变 4 条 —— **设计内**：resize 必须让应用重新布局，否则不重排；**不是崩溃、不是跑到主屏** |
| 崩溃 | ✅ `FATAL / Exception` **0** |

### 3.3 另一条后端（`TextureView`）的回归

设置项撤掉后，`design_video_wallpaper_backend` 这条 pref 仍然保留（**显式写过 `1` 的老用户不受影响**）。
用 adb 把 pref 置成 `1` 再重启桌面，走的还是老的 `LAYER` 路线（`ActivityView`）：

```
I CarDesk/App: 窗口 format：OPAQUE（默认，不动） backend=1
I CarDesk/NavUI: [EVT] 画中画显示 pkg=com.autonavi.amapautp.yh app=Amap Auto mode=embedded role=主画
```

⇒ 高德同样嵌在画中画里（`Display #30` 承载高德，`Display #0` 只有桌面）、`FATAL` 0。
**两条路线都没被删掉**，只是「默认走哪条」这件事被固定下来了（证据 `r227-17-backend1-layer.png`）。

### 3.4 设置页复核（确认没留下多余条目）

`emu-verify/r227-15-settings-wallpaper.png`、`r227-16-settings-video-section.png`：
「壁纸」页的「**视频壁纸**」段现在只有 —— 启用视频壁纸 / 已导入 58 MB 视频 / 导入视频… /
用系统选择器导入… / 删除视频壁纸，**没有「渲染后端」那一行**。

### 3.5 单测

| 项 | 结果 |
|---|---|
| 全量 JVM 单测 | **2637 / 0 failures / 0 errors / 0 skipped**（262 个测试类；基线 2628 + 新增 9） |
| 新增单测 | `app/src/test/java/com/cardesk/launcher/core/VideoWallpaperBackendDefaultTest.kt` 9 条（默认值、窗口 format 目标值、两条口径一致性） |

> ⚠️ 第三节的截图分两批：`r227-09`～`r227-12` 出自**带「渲染后端」设置项**的那版 4304，
> `r227-13`～`r227-17` 出自**撤掉设置项后的最终版**（SHA `1458765c…c19ab`）。
> 两版的画中画/全屏逻辑完全一致（撤的是设置项，不是渲染逻辑），
> 最终版的实测判据以 `r227-13`/`r227-14`/`r227-15`/`r227-16`/`r227-17` 为准。

## 四、影响面

| 场景 | 影响 |
|---|---|
| 默认配置（视频壁纸开） | **回到 SurfaceView** ⇒ 窗口半透明 + 画中画走 MIRROR ⇒ 省电方案**这次真的生效了** |
| 视频壁纸关闭 | 窗口不透明 ⇒ 画中画走 `LAYER`，行为与上一版一致 |
| 显式写过 `backend=1` 的老用户 | pref 里写着的值照旧生效（窗口不透明 ⇒ 画中画走 `LAYER`）；**默认值改动不影响已经明确选过的人** |
| 切页 / 切全屏 | 画中画承载面按需 resize，**不重建虚拟屏** ⇒ 不把应用推到主屏 |
| 静态壁纸（默认场景） | 不涉及（`needsTranslucentWindow` 为 false ⇒ 不动窗口 format） |
| 设置页 | **没有新增任何条目**，与上一版完全一样 |

## 五、已知边界

- **实车复测仍未做**（车机离线三轮了）：车机掉帧是否真的改善、`SurfaceView` 在领克 AAOS 上的实际表现，
  只能在车上验；模拟器是软件解码，**帧率表现不代表车机**。
- **还原时会多一次承载应用启动**（`by=resize`）：这是为了让应用按新尺寸重排，是设计内行为；
  如果要求「一次都不重启」，需要另找让应用响应 `onConfigurationChanged` 自适应的路子。
- 画中画仍不转发触摸（API 28 的 `ActivityView` 本身也不转发），与旧路线一致。
- 视频壁纸「右侧裁切」（另一条反馈）本轮没处理。

## 六、本轮未做

- **实车复测**：`198.18.50.1:5555` 离线（第 225/226/227 三轮欠账）。
- **R2 上传**：本机无 `R2_AK` / `R2_SK`；`dist/cardesk-latest.json` 已就绪，凭据到位后
  `python3 tools/publish_r2.py --bucket file --apk dist/CarDesk-platform-ecarx-cardesk-4304.apk --manifest dist/cardesk-latest.json` 即可。
- 视频壁纸「右侧裁切」独立复测。
- 车上 UNI 桌面（`xyz.i4o.uni.launcher`）未处理。
- 未提交 Git（HEAD 仍停在第200轮，201+ 全是未提交工作树改动）。
