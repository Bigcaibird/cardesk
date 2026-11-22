# 第225轮模拟器验证报告（cardesk28 / API 28 / 端口 5560）

交付版本：**4302**（`1.0.1206-system`）
APK：`dist/CarDesk-platform-ecarx-cardesk-4302.apk`
SHA-256：`c51d858ef9186a8453b54a1a24112051c6d6b945119668650cfdcd0ae23d54b6`
平台证书 SHA-256：`c8a2e9bccf597c2fb6dc66bee293fc13f2fc47ec77bc6b2b0d52c11f51192ab8`

---

## 一、问题

用户（m00820）四件事：

1. 车机上**左右切首页 / 导航页**复现「画中画的高德跑回首页」；
2. 在 cardesk 上**装高德、画中画配高德**测试这条路径；
3. **移除自绘的动态壁纸**（车机上太卡），并指出「用自绘动态壁纸时不会有高德错乱显示」；
4. **画中画窗口的圆角没了**。

---

## 二、根因

| # | 根因 | 证据 |
|---|---|---|
| 1 | 高德进入后**自己再起一个活动**（`UsbFillActivity` → `MainMapActivity`），新活动**另建 task**、不带 `launchDisplayId` → AMS 放到默认显示器（全屏抢屏）；旧搬移逻辑「搬稳即收工」，且只挑还留在虚拟屏上的那个 task | 22:17:17 `搬到我们的虚拟屏 task=874 stack=13 to=3` → 22:17:18 `跑到别的屏上 … stack=14 size=1920x944 候选=1` |
| 2 | 切页时导航页被 `GONE`，但承载层 `SurfaceView` 是独立图层，AOSP 9 上摘不掉 → 首页原位残留黑卡 | 22:17 首页截图 `/tmp/k1-home.png`（pip 有内容才出现；pip 置空干净） |
| 3 | 视频壁纸 `SurfaceView` 后端要求窗口 `TRANSLUCENT`；窗口半透明后 `clipToOutline` 的「挖洞」失效 → 直角 + 承载层坐标/裁剪脱节 | 视频关（窗口不透明）圆角正常；视频开（半透明）直角。裁图 `/tmp/c2-corner.png` vs `/tmp/c3-corner.png` |
| 4 | 程序化动态壁纸每帧画满 1920×1080（p50=20ms），且让窗口不透明 —— 掩盖了第 3 条的病 | 第207/211轮记录 + 用户观察 |

---

## 三、改法

| # | 改法 | 文件 |
|---|---|---|
| 1 | 搬稳后继续守：`verifyDelayMsForAttempt` = 600/900/1200/1800/2400ms（约 7s）；复查时**优先搬「跑到别的屏上」的 task**；最多搬 3 次 | `core/HostTaskMigrator.kt`、`ui/embed/HostTaskMigration.kt` |
| 2 | 离开导航页把承载层挪到屏外（`translationX = -8000`），**不拆承载面**（`INVISIBLE` 会让 `ActivityView` 拆 surface，切回来一直黑 —— 实测过并放弃） | `ui/pip/PipSurface.kt`、`ui/LauncherView.kt` |
| 3 | 视频壁纸默认后端 `SurfaceView` → `TextureView`（窗口不透明）；去掉抢 `TextureView` 帧回调的诊断监听，改在 `onSurfaceTextureUpdated` 打一次「第一帧已上屏」 | `core/Prefs.kt`、`ui/LauncherView.kt` |
| 4 | 删除程序化动态壁纸全部实现与接线 | 删 `core/LiveWallpapers.kt`、`ui/widget/LiveWallpaperRenderer.kt` + 两份测试；改 `BackgroundView.kt`、`LauncherView.kt`、`SettingsOverlay.kt`、`core/Prefs.kt` |

---

## 四、实测（4302 装机后）

| 场景 | 判据 | 结果 |
|---|---|---|
| 高德配成画中画（导航页） | 启动 20s 后截图仍在卡里；日志有守护窗口 5 轮 | ✅ `r225-01-amap-pip.png` |
| 切到首页 | 原画中画位置**不得有黑卡** | ✅ `r225-02-home-clean.png` |
| 切回导航页 | 高德仍在、`START … autonavi` 新增 **0** 条、`FATAL` **0** 条 | ✅ `r225-03-nav-back-norestart.png` |
| 圆角 | 四角为圆角（与半透明窗口下的直角对照） | ✅ 见 `r225-01` 左上角 |
| 视频壁纸 | `TextureView` 后端下起播 + 第一帧上屏日志 | ✅ `视频壁纸：第一帧已上屏（TextureView）` |
| 单测 | `testSystemUnitTest` | ✅ **2625 / 0 failures / 0 errors / 0 skipped** |

---

## 五、环境备注（下一轮直接用）

- 模拟器 `emulator-5560` 可 `adb root`；`adb reboot` 后 root 会掉，要重新 `adb root`。
- 改 prefs：`am force-stop` → `adb pull /data/data/com.ecarx.cardesk/shared_prefs/cardesk.xml` →
  改 → `push` → `chown 1000:1000` + `chmod 660` → `am start`。
- 关键 key：`pip_app`、`pip2_app`、`last_page`、`design_video_wallpaper`、
  `design_video_wallpaper_enabled`、`design_video_wallpaper_backend`（0=SurfaceView、1=TextureView）。
- 已验证的切页动作：`input tap 209 1043`（底部 Dock 的分屏键，首页 ⇄ 导航页）。
- 高德包名 `com.autonavi.amapautp.yh`；启动器 `UsbFillActivity` 会自己拉 `MainMapActivity`。
- UNI 桌面已按要求卸载（`uninstall xyz.i4o.uni.launcher`），HOME 归 `com.ecarx.cardesk`。

---

## 六、已知边界

- 后端改回 `0`（`SurfaceView` / HWC 直通）时，圆角与残留会回来 —— 半透明窗口的硬约束。
- 模拟器软件解码（`c2.android.avc.decoder`）跟不上 1080p60，视频帧可能不前进；车机是硬解。
- 高德被系统重启时画中画会重新承载一次，属正常自愈。
