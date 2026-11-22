# 第228轮交接

> 上一轮：第227轮（修掉第225/226轮验收查出的三个问题 + 打通画中画「切全屏 / 还原」）。
> 交付 **4304**（`1.0.1208-system`），**未上传 R2**（本机无凭据）。

## 一、开工先做的两件事

1. **实车复测**（第225 / 226 / 227 三轮都没做，这是最大的欠账）：
   ```bash
   adb connect 198.18.50.1:5555     # 车机，API 28
   adb devices
   ```
   重点看四件事：
   - **视频壁纸在车机上还卡不卡** —— 第227轮把默认值改回了 `SurfaceView`（HWC 直通），
     这正是用户报的「默认方案掉帧很严重」的正解，**必须在车上验**；
   - 开机后视频**还会不会跳回开头重播**（第227轮用「记位置 + `seekTo` 续播」修了模拟器上的现象）；
   - 高德配画中画：首页 ⇄ 导航页来回切、**画中画 → 全屏 → 还原**，是否跑回首页 / 残留黑卡 / 直角 / 黑屏；
   - `logcat | grep -E "画中画窗口内渲染|承载任务守护|视频壁纸"` 是否按预期出现。
   车机仍离线的话，做第三节的遗留项。
2. **R2 上传**（第227轮没做）：
   ```bash
   export R2_AK=<Access Key ID>
   export R2_SK=<Secret Access Key>
   python3 tools/publish_r2.py --bucket file \
       --apk dist/CarDesk-platform-ecarx-cardesk-4304.apk \
       --manifest dist/cardesk-latest.json
   ```
   `dist/cardesk-latest.json` **已经按最终更新说明生成好了**（SHA-256 `1458765c…c19ab`），只差上传。
   ⚠️ 4304 本轮出过两版，**以 `1458765c…c19ab` 这版为准**（带「渲染后端」设置项的那版已作废）。

## 二、第227轮改动速查

| 文件 | 作用 |
|---|---|
| `app/src/main/java/com/cardesk/launcher/core/Prefs.kt` | `videoWallpaperBackend` **默认值改回 `BACKEND_SURFACE_VIEW`**（上一版是 TextureView ⇒ 默认一直走掉帧路） |
| `app/src/main/java/com/cardesk/launcher/core/VideoWallpaper.kt` | 新增 `needsTranslucentWindow()` / `targetWindowFormat()` 两个纯函数 —— 「窗口透明性」与「画中画路线」两条判据**共用一个来源** |
| `app/src/main/java/com/cardesk/launcher/MainActivity.kt` | 新增 `applyWindowFormatBeforeCreate()`：窗口 format 在 `setContentView` **之前**定好（不透明则完全不动窗口） |
| `app/src/main/java/com/cardesk/launcher/ui/LauncherView.kt` | `surfaceDestroyed` 记 `videoResumePositionMs` → 新播放器 `seekTo` 续播；视频层与 `videoBackend` 都是**启动时定死**的 `val`（第227轮加过运行时切换，按要求撤掉了） |
| `app/src/main/java/com/cardesk/launcher/ui/pip/PipMirrorHost.kt` | `ensureDisplay` 虚拟屏已存在时**重新 `setSurface` + `resize`**（不再 early-return）；`onSurfaceTextureSizeChanged` 改调 `resize`；新增 `relaunch()` |
| `app/src/main/java/com/cardesk/launcher/ui/pip/PipSurface.kt` | 新增 `relaunch()`（mirror ⇒ 镜像宿主自己发；layer ⇒ `ActivityEmbed.startActivity`）与 `rebuildForRenderPlan()` |
| `app/src/test/java/com/cardesk/launcher/core/VideoWallpaperBackendDefaultTest.kt` | 新增 9 条单测（默认值、format 目标值、两条口径一致性） |两条口径一致性） |

### 关键机制（改动前必须懂，否则会重复踩坑）

- **两条画中画路线**（`core/PipRenderPlan.kt`）：
  `LAYER` = `ActivityView` 自带图层（窗口**不透明**时用）；`MIRROR` = **自建 `VirtualDisplay` + `TextureView` 镜像**（窗口**透明**时用）。
  窗口透明 ⇒ 必须走 MIRROR，否则圆角变直角、画面错位、切页残留黑卡。
- **视频壁纸走 SurfaceView ⇒ 窗口必须 `TRANSLUCENT`**（视频是压在窗口**之下**的硬件图层）⇒ 就必须走 MIRROR。
  ⇒ 「省电」和「画中画正确」是靠这条链绑在一起的，不能单独改一头。
- **MIRROR 路线的承载面是 `TextureView`，没有 `startActivity`** ⇒ 需要「重新拉起承载应用」时，
  必须由镜像宿主自己发（`PipMirrorHost.relaunch()`），不能指望 `ActivityEmbed`。

## 三、遗留项（按优先级）

1. **实车复测**（见第一节）：三轮欠账，车机掉帧结论只能靠它。
2. **R2 上传 4304**（见第一节）。
3. **承载应用启动兜底**（第225轮的老遗留）：实测遇到过一次 `PendingIntent` 启动**静默不启动**
   （logcat 有「已启动」但没有 `START`），当时没有回退。建议：启动后 300~500ms 校验「承载应用有没有 task」，
   没有就走下一档 API。
4. **还原时的多一次启动**：第227轮实测「还原」会让承载应用**重启一次**（`by=resize route=mirror`），
   这是设计内（否则应用不按新尺寸重排）。如果要求一次都不重启，得另找让应用响应
   `onConfigurationChanged` 自适应的路子。
5. **视频壁纸「显示混乱 / 右侧裁切」**：第225/226轮都推断它与窗口透明同源，**没做过不同宽高比视频的对照复测**。
6. 车上 UNI 桌面（`xyz.i4o.uni.launcher`）未处理，只在模拟器上卸载过。

## 四、环境备忘（踩过的坑）

- **出包必须先** `export ANDROID_USER_HOME="$TMPDIR/cardesk-android-home"`（内含 `debug.keystore`），
  否则 `validateSigningSystem` 会报 `Unable to create debug keystore … not writable`。
- `tools/release.sh` **失败的那次也会 bump 版本号**；失败后要手动改回 `version.properties` 再 `--no-bump` 重出。
- `gradle assembleSystem` 默认产出的是 **`com.ecarx.qq.tool`**（替换参考 App 变体），**不是交付包**；
  交付包只能走 `tools/release.sh`。
- 真实启动类是 **`com.ecarx.cardesk/com.cardesk.launcher.MainActivity`**（Kotlin 包名 ≠ applicationId），
  `am start -n com.ecarx.cardesk/.MainActivity` 会报「不存在」。
- **只改更新说明、不重出包时**，用 `tools/make_manifest.py` 直接重生成清单即可（不会动 APK 的 SHA）：
  ```bash
  python3 tools/make_manifest.py --apk dist/CarDesk-platform-ecarx-cardesk-4304.apk \
      --version 1.0.1208 --code 4304 --tag v1.0.1208 \
      --name "CarDesk 4304 / 1.0.1208-system" \
      --notes-file tools/notes-r227.md --out dist/cardesk-latest.json
  ```
- 模拟器 `emulator-5560` 可 `adb root`，能直接改 `/data/data/com.ecarx.cardesk/shared_prefs/cardesk.xml`
  （`chown 1000:1000` + `chmod 660`）；关键 key：
  `pip_app` / `pip2_app` / `last_page` / `design_video_wallpaper` / `design_video_wallpaper_enabled` / `design_video_wallpaper_backend`。
  改完**必须重启桌面**才生效。
- **`tools/set-pip-prefs.sh <0|1> [--drop-backend-key]`** 是复现脚本：
  `--drop-backend-key` = 删掉后端键 ⇒ 走代码默认值（用来复现第227轮修掉的问题①）。
- 模拟器上**同时装着调试包 `com.cardesk.launcher` 和被验包 `com.ecarx.cardesk`**，日志必须按 pid 过滤。
- 切页在模拟器上的可靠操作：`input tap 209 1043`（底部 Dock 分屏键，首页 ⇄ 导航页）。
- **画中画全屏键** = `navSplit`/`pipDivider` 单击，挂在画中画卡**左缘**
  （`NavSplitPlacement.WIDTH=36, HEIGHT=110`，`Gravity.START|CENTER_VERTICAL`）：
  导航页态 ≈ **(64,510)**，全屏态「还原」键 ≈ **(15,540)**。
- 用 `INVISIBLE` 藏承载层会**拆掉 `VirtualDisplay` 的 surface**，切回来永远黑 —— 别再用（改为「挪到屏外」）。
- 拿 `configuration.screenWidthDp` 当「task 在哪块屏」的判据**不可信**（搬移瞬间还是主屏尺寸）。
- **`MediaPlayer.setSurface()` 换输出面在模拟器上会全黑**（壁纸亮度 21.9 vs 正常 129~212）——
  第227轮试过并作废，改用「释放播放器 + `seekTo` 续播」。
- PIPY 探测到的采样矩形 `rect=237,203,1138,810` 是**内采样区**，不是画中画卡边界；卡实际约 x=50..1330、y=80..940。
- 模拟器是**软件解码**，帧率表现不代表车机。
