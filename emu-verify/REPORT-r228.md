# 第228轮验证报告 —— 真车逐条复核 + 模拟器复测

> 交付：`dist/CarDesk-platform-ecarx-cardesk-4304.apk`（`4304 / 1.0.1208-system`）。
> 上一版 4304 出过两版，**本报告对应的是 16:14 之后这一版**（SHA-256 `01cc94a5…6412`，
> 带第228轮的触摸转发 / `dontOverrideDisplayInfo` / 玻璃条 / 拖动调优）。

## 一、背景

第225 / 226 / 227 三轮的画中画改动，到第228轮才第一次在**领克真车**（`198.18.50.1:5555`，
Android 9 / API 28）上逐条复核。复核暴露出四个用户可见现象，根因**全部在 carrierdesk 自己**
（不在高德）：其中三个是「第226轮引入的镜像路线只做了一半」，一个是与路线无关的坐标换算错误。

## 二、四个现象 → 根因 → 修法

| # | 现象（真车） | 根因 | 修在哪 |
| --- | --- | --- | --- |
| 1 | 画中画里的高德**点不动** | 镜像路线从第一版起没有任何触摸转发；`ActivityView` 自带转发，我们自建的虚拟屏没有，API 28 也没有 `MotionEvent.setDisplayId` | `PipMirrorHost.forwardTouch`（`InputManager.createInputForwarder` + `IInputForwarder.forwardEvent`） |
| 2 | 切全屏后**右侧缺 108px** | 建虚拟屏后没调 `wm.dontOverrideDisplayInfo(displayId)`（AOSP `ActivityView.initVirtualDisplay` 紧随其后就有这一句）⇒ WM 覆盖了虚拟屏显示信息 ⇒ 承载应用按另一套尺寸排版，只画 1812/1920 | `PipMirrorHost.dontOverrideDisplayInfo`（反射类名是 `android.view.WindowManagerGlobal`） |
| 3 | 大小调整的**玻璃条看不见** | `NavPage.placePipDivider()` 把 row 局部坐标当成 `NavPage` 坐标（差 `NAV_SIDE_MARGIN = 44`）⇒ 画到 x≈1315，压在画中画滚动条上 | `NavPage.placePipDivider`（与路线无关） |
| 4 | 双画下「一画」面板**没有圆角** | 与单画同一个根因：半透明窗口下 `ActivityView` 的图层不受窗口轮廓裁剪 | 走镜像路线即成立 |

## 三、回归修复（本轮顺带）

- **关掉视频壁纸后画中画行为变了**（用户反馈「以前不用视频壁纸一切正常，现在有问题了」）：
  - 路线判据原来读 **prefs**，而窗口实际 format 会在运行中变（开关视频壁纸）⇒ 判据变了，
    承载面（同包名复用）还挂着旧路线的内容；
  - 修法：判据改成**问窗口自己**（`LauncherView.isWindowTranslucent()` 读 `window.attributes.format`），
    并新增 `PipRenderPlan.needsRebuild(current, want)` ⇒ 路线变了就重建承载面。
- **换肤重建时镜像宿主没跟着搬家** ⇒ 旧承载面 release 会销毁虚拟屏，承载应用 task 被推回主屏。
  现在镜像宿主与承载面整块搬家（`PipSurface.detachMirrorHost` / `adoptContent`）。

## 四、拖动卡顿（镜像路线专有）

真车实测拖分隔条约 10fps（帧间隔 34~177ms），四个成因一起修：

| 成因 | 证据 | 修法 |
| --- | --- | --- |
| 每帧一次反射 + IPC 到 WMS（触摸排除区） | 单帧多花 9~25ms | 节流 400ms |
| 每帧 `getDeclaredMethods()` 新建 Method 数组 | — | 解析一次即缓存（找不到也缓存 null） |
| 每帧 3~4 条 logcat | — | 逐帧日志节流 400ms，去掉重复一条 |
| 承载应用按每个中间尺寸重画整张地图 | 帧间隔与位移正相关 | 比例吸附 12px 一格（一次 200px 拖动 ≈ 16 次重画） |

## 五、判据（领克真车，Android 9 / API 28）

| 判据 | 结果 |
| --- | --- |
| 触摸转发器 | ✅ `画中画窗口内渲染：输入转发器就绪 display=8 ok=true` |
| 触摸送达高德 | ✅ `画中画触摸转发 display=8 x=355.75 y=338.24 ok=true`，画面随之变化 |
| `dontOverrideDisplayInfo` | ✅ 反射打中（`android.view.WindowManagerGlobal`） |
| 圆角 | ✅ 用户实车确认 |
| 全屏右侧缺口 | ✅ 右侧满宽 |
| 玻璃条 | ✅ 从 x≈1315 回到卡缝 x≈1359 |
| 壁纸开关换路线 | ✅ `画中画路线变了，重建承载面 from=LAYER to=MIRROR` |
| 崩溃 | ✅ `FATAL / Exception` 0 |
| 单测 | ✅ **2644 / 0 failures / 0 errors / 0 skipped** |

## 六、模拟器复测（cardesk28 / API 28 / `emulator-5560`，2026-11-20 16:30~16:36）

装的是同一份 4304（设备上 `base.apk` 的 SHA-256 与 `dist/` 一致）。

| 场景 | 结果 | 证据 |
| --- | --- | --- |
| 视频壁纸开（`SurfaceView`）+ 高德画中画 | ✅ 走 MIRROR，高德嵌在卡里、四角圆角 | `r228-01-emu-amap-mirror.png` |
| 首页 ⇄ 导航页来回切 | ✅ 首页干净无黑块、切回高德仍在；`START … autonavi` 新增 **0**、`FATAL` **0** | `r228-02-emu-home-clean.png`、`r228-03-emu-nav-back.png` |
| 单测 | ✅ 2644 / 0 failures | gradle `testSystemUnitTest` |

## 七、已知边界 / 未做

1. **实车复测只在第228轮做过一次**（车机随后再次离线）；视频壁纸在车机上的长期帧率数据仍缺。
2. **`LAYER` 路线（窗口不透明、视频壁纸关）+ 高德**在模拟器上有**黑屏**现象
   （`r228-04-emu-layer-opaque-black.png`，日志 `opaquePct=0`、`画中画显示 mode=embedded`）。
   同一配置下 `com.cardesk.embedtest` 正常 ⇒ 判断是模拟器上「虚拟屏内嵌 `SurfaceView` 应用」的合成问题；
   真车上第228轮实测该路线正常（用户确认「以前不用视频壁纸一切正常」）。
   **待车机恢复后按同一配置复测确认**。
3. 承载应用启动兜底（`PendingIntent` 静默不启动时回退下一档）仍未做。
4. 还原时承载应用会重启一次（`by=resize`）——设计内。
5. 视频壁纸「显示混乱 / 右侧裁切」仍未做不同宽高比视频对照复测。
6. 车上 UNI 桌面未处理。

## 八、发布

- 交付包：`dist/CarDesk-platform-ecarx-cardesk-4304.apk`（3672415 字节，
  SHA-256 `01cc94a5819b811446ece95325af4b1c1cf351be83bf9981082d9685b6412e14`，
  AOSP platform 签名 `c8a2e9bc…92ab8`）。
- 更新说明：`tools/notes-r228.md`；清单由 `tools/make_manifest.py` 生成。
- R2：`cardesk/CarDesk-platform-ecarx-cardesk-4304.apk` + `cardesk/latest.json`
  已上传并回读校验（公网 `https://file.chuxi.bid/cardesk/latest.json`）。
