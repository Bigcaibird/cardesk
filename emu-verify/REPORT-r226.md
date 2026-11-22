# 第226轮验收报告 · 视频壁纸回到 SurfaceView + 画中画「窗口内渲染」

- 交付版本：**4303** / `1.0.1207-system`
- 交付物：`dist/CarDesk-platform-ecarx-cardesk-4303.apk`
  （3661047 字节，SHA-256 `745359013bb412324d59d91f6a62fc6397b43c5b277a701acd9fc171906ca2bb`，平台证书 `c8a2e9bc…92ab8`）
- R2：`cardesk/CarDesk-platform-ecarx-cardesk-4303.apk` + `cardesk/latest.json`（已回读校验）
- 验证环境：AVD `cardesk28`（Android 9 / API 28 / arm64），`emulator-5560`
- 实车：`198.18.50.1:5555` 本轮**仍离线**（`ping` 100% 丢包），实车复测未做

---

## 一、问题

用户（车机实测）：「现在的默认方案掉帧很严重，有种卡顿的感觉，麻烦在基于渲染路径 0 的方案上，解决已有的问题」。

- 第225轮为保画中画圆角，把视频壁纸默认后端改成了 `TextureView`：视频每帧进窗口缓冲、GPU 合成全屏 1920×968@60 → 车机掉帧。
- 换回 `SurfaceView`（渲染路径 0）能省电，但要求窗口 `TRANSLUCENT`；窗口一透明，画中画承载层（`ActivityView` 内的独立 `SurfaceView` 图层）不再被窗口轮廓裁剪：
  - 圆角变直角（API 28 无 `SurfaceView.setCornerRadius`，图层侧补不了）；
  - 承载画面坐标/裁剪与窗口脱节 → 「高德错乱 / 右侧裁切」；
  - 切到首页后承载层仍在合成 → 残留黑卡。

⇒ 「省电」与「画中画正确性」必须同时成立，不能靠换后端取舍。

## 二、改法（混合渲染）

1. 视频保持 `SurfaceView` 硬件图层直通（省电），窗口保持半透明。
2. 画中画改走 `ui/pip/PipMirrorHost.kt`：**自己建 `VirtualDisplay`**，输出 Surface 接到 `TextureView`（画进窗口自己的缓冲）。
   - `TextureView` 是普通 View ⇒ `clipToOutline` 圆角成立、随页面隐藏、无残留；
   - 合成代价只有画中画那块（≈1287×867）；
   - 承载应用用 `ActivityOptions.setLaunchDisplayId` 起在这块虚拟屏上。
3. 虚拟屏 displayId 通过 `ActivityEmbed.bindHostedDisplay(view, id)` 登记，
   `virtualDisplayIdOf()` 先查这张表 ⇒ 第225轮的**搬移守护窗口**与黑屏自愈照常工作。
4. 尺寸变化只 `resize` 虚拟屏，**不重建**（重建会把 task 推到主屏）。

路线选择是纯逻辑（`core/PipRenderPlan.kt`）：窗口透明 ⇒ `MIRROR`，否则 `LAYER`（旧的 `ActivityView` 路线一行没改）。

## 三、实测（模拟器，`design_video_wallpaper_backend=0`、视频壁纸开）

| 判据 | 结果 | 证据 |
|---|---|---|
| 承载路线 | ✅ `画中画显示 … mode=mirror`、`画中画窗口内渲染就绪 display=17 size=1287x867` | logcat |
| 高德嵌在画中画里 | ✅ 截图可见地图 UI，右侧媒体卡、底部 Dock 都在 | `r226-01-amap-mirror-rounded.png` |
| 四角圆角 | ✅ 裁图确认为圆角 | `r226-01`（左上角） |
| 首页无残留 | ✅ 首页干净、视频壁纸继续播放 | `r226-02-home-clean-video.png` |
| 切回导航页不重启 | ✅ 切回后画面回来（**静止子区域逐字节相同**；整卡差异 0.294%，来自高德自己的地图动画）、`START u0 … autonavi` 新增 0 | `r226-03-nav-back-norestart.png` |
| 崩溃 | ✅ `FATAL` 0 条 | logcat |
| 简单承载应用（回归） | ✅ `com.cardesk.embedtest` 同样圆角、切页正常 | `r226-04-embedtest-mirror.png` |
| 单测 | ✅ 2628 / 0 failures / 0 errors / 0 skipped | `gradle testSystemUnitTest` |

## 四、影响面

- `design_video_wallpaper_backend=1`（`TextureView`）或视频壁纸关闭时，窗口不透明 ⇒ 仍走旧路线，**行为不变**。
- 旧的 `ActivityView` 路线、搬移守护窗口、黑屏自愈（alpha 闸门 + 分级自愈）全部保留。

## 五、已知边界

- 车机掉帧是否真的改善，需要实车复测（车机离线，本轮未做）。
- 画中画不转发触摸（API 28 `ActivityView` 本身也不转发），与旧路线一致。
- 模拟器是软件解码，视频本身的帧率表现不代表车机。

## 六、本轮未做

- 车上 UNI 桌面未处理（只按要求在模拟器上卸载过）。
- 承载应用启动的兜底（`PendingIntent` 静默失败时回退下一档）仍是遗留项。
