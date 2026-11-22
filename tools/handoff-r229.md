# 第229轮交接

> 上一轮：第228轮（真车逐条复核：补齐镜像路线的触摸与显示信息 + 路线跟着窗口走 + 拖动不再卡）。
> 交付 **4304**（`1.0.1208-system`，SHA-256 `01cc94a5…6412`），**已上传 R2**。

## 一、开工先做的事

1. **实车复测**（第228轮只测了一次，车机随后又离线）：
   ```bash
   adb connect 198.18.50.1:5555
   adb devices
   ```
   重点看：
   - 视频壁纸开着（`SurfaceView` 后端 ⇒ 窗口半透明 ⇒ 画中画走 MIRROR）时，**还会不会掉帧**；
   - **关掉视频壁纸**（窗口不透明 ⇒ 画中画走 LAYER）时，画中画里**高德是否正常出图**
     —— 模拟器上这条路线有黑屏（见第二节），**真车上第228轮是正常的**，需要确认；
   - 画中画里点高德、拖地图、切全屏 / 还原；
   - `logcat | grep -E "画中画窗口内渲染|画中画触摸转发|承载任务守护|画中画路线变了"`。

2. **回归**：`design_video_wallpaper` 开关切换后，画中画路线是否即时切换（不用重启桌面）。

## 二、已知问题（按优先级）

1. **`LAYER` 路线 + 高德在模拟器（cardesk28）上黑屏**
   - 复现：视频壁纸关 + `pip_app=com.autonavi.amapautp.yh`，冷启动桌面，画中画整块黑；
   - 同一配置下 `pip_app=com.cardesk.embedtest` 正常（绿色页），视频壁纸开（MIRROR）时高德也正常；
   - 证据：`emu-verify/r228-04-emu-layer-opaque-black.png`，日志 `画中画显示 … mode=embedded`、
     `opaquePct=0`；
   - 推测：模拟器上「虚拟屏内嵌 `SurfaceView` 应用」的合成问题（真车正常）。
     **先在真车按同一配置复测**，确认是模拟器专属再决定要不要加固。

2. **承载应用启动兜底**（第225轮老遗留）：实测遇到过一次 `PendingIntent` 启动**静默不启动**
   （日志有「已启动」但没有 `START`）。建议启动后 300~500ms 校验「有没有 task」，没有就走下一档 API。

3. 还原时承载应用会重启一次（`by=resize`）—— 设计内；要求「一次都不重启」得让应用
   响应 `onConfigurationChanged` 自适应，成本高。

4. 视频壁纸「显示混乱 / 右侧裁切」：仍未做不同宽高比视频的对照复测。

5. 车上 UNI 桌面（`xyz.i4o.uni.launcher`）未处理。

## 三、第228轮改动速查

| 文件 | 作用 |
| --- | --- |
| `app/src/main/java/com/cardesk/launcher/ui/pip/PipMirrorHost.kt` | 新增 `forwardTouch`（`createInputForwarder` + `forwardEvent`）、`dontOverrideDisplayInfo`（反射 `android.view.WindowManagerGlobal`）、`relaunch()`；触摸排除区节流 400ms、反射方法缓存、逐帧日志节流 |
| `app/src/main/java/com/cardesk/launcher/ui/pages/NavPage.kt` | `placePipDivider` 坐标换算（补 `NAV_SIDE_MARGIN` 44px） |
| `app/src/main/java/com/cardesk/launcher/ui/pip/PipSurface.kt` | `detachMirrorHost` / `adoptContent`（换肤搬家带上镜像宿主）、`rebuildForRenderPlan()`、`relaunch()` |
| `app/src/main/java/com/cardesk/launcher/core/PipRenderPlan.kt` | 新增 `needsRebuild(current, want)`（路线变了重建承载面） |
| `app/src/main/java/com/cardesk/launcher/ui/LauncherView.kt` | 路线判据改为读**窗口实际 format**（`isWindowTranslucent()`），不再用 prefs |
| `app/src/main/java/com/cardesk/launcher/ui/pip/PipRatioStore.kt` / `PipDividerView.kt` | 比例吸附 12px、持久化口径统一 |
| `app/src/test/.../PipRenderPlanTest.kt` / `PipRatioTest.kt` | 新增/扩充单测（路线重建、比例吸附） |

### 关键机制

- **路线判据**：`window.attributes.format` 决定 LAYER / MIRROR；
  透明 ⇒ MIRROR（`TextureView` 镜像，圆角/裁剪成立），不透明 ⇒ LAYER（`ActivityView`，最省）。
- **镜像路线必须自己补** `ActivityView` 本来有的东西：触摸转发、`dontOverrideDisplayInfo`、
  自己发启动（`PipMirrorHost.relaunch()`）。
- **虚拟屏尺寸变化只 `resize`、不重建**（重建会把承载应用推到主屏）。
- **不能用 `INVISIBLE` 藏承载层**（会拆掉虚拟屏的 surface），用「挪到屏外」。

## 四、环境备忘

- 出包前 `export ANDROID_USER_HOME="$TMPDIR/cardesk-android-home"`（含 `debug.keystore`），
  否则 `validateSigningSystem` 报 keystore not writable。
- `tools/release.sh` 失败也会 bump 版本；失败后手改 `version.properties` 再 `--no-bump`。
- 启动类：`com.ecarx.cardesk/com.cardesk.launcher.MainActivity`。
- 只改更新说明不重出包：`python3 tools/make_manifest.py … --notes-file tools/notes-rXXX.md --out dist/cardesk-latest.json`。
- 模拟器 `emulator-5560` 可 `adb root`；改 prefs 后**必须重启桌面**：
  `pip_app` / `pip2_app` / `last_page` / `design_video_wallpaper` / `design_video_wallpaper_enabled` /
  `design_video_wallpaper_backend`（0 = SurfaceView，1 = TextureView）。
- 切页：`input tap 209 1043`（底部 Dock 分屏键，首页 ⇄ 导航页）。
- 画中画全屏键在卡**左缘**：导航页 ≈ (64,510)，全屏态「还原」≈ (15,540)。
- R2 上传：`set -a; . ./R2.txt; set +a; python3 tools/publish_r2.py --bucket file --apk … --manifest dist/cardesk-latest.json`。
