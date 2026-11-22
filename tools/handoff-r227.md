# 第227轮交接

> 上一轮：第226轮（视频壁纸回到省电的 `SurfaceView`，画中画改「窗口内渲染」）。
> 交付 **4303**（`1.0.1207-system`），已上传 R2。

## 一、开工先做的两件事

1. **实车复测**（第225 + 第226两轮都没做）：
   ```bash
   adb connect 198.18.50.1:5555     # 车机，API 28
   adb devices
   ```
   重点看三件事：
   - 视频壁纸在车机上**还卡不卡**（`design_video_wallpaper_backend=0`，第226轮的目标）；
   - 高德配画中画 + 左右切页，是否还会跑回首页 / 残留黑卡 / 直角；
   - `logcat | grep -E "画中画窗口内渲染|承载任务守护|承载任务跑到别的屏上"` 是否按预期出现。
2. 若车机仍离线，**不要空转**：可以直接做第三节的遗留项。

## 二、第226轮改动速查

| 文件 | 作用 |
|---|---|
| `app/src/main/java/com/cardesk/launcher/core/PipRenderPlan.kt` | 纯逻辑：窗口透明 ⇒ `MIRROR`，否则 `LAYER` |
| `app/src/main/java/com/cardesk/launcher/ui/pip/PipMirrorHost.kt` | 自建 `VirtualDisplay` + `TextureView`；`ActivityOptions.setLaunchDisplayId` 起应用；尺寸变化只 `resize` |
| `app/src/main/java/com/cardesk/launcher/ui/pip/PipSurface.kt` | `translucentWindow` / `embedMirror()` / 释放宿主 / 尺寸同步 |
| `app/src/main/java/com/cardesk/launcher/ui/embed/ActivityEmbed.kt` | `bindHostedDisplay` / `unbindHostedDisplay` / `hostedDisplayIds`（`virtualDisplayIdOf` 先查表） |
| `app/src/main/java/com/cardesk/launcher/ui/LauncherView.kt` | `refreshPip()` 按 **prefs** 判窗口是否透明并同步给主/副画 |

## 三、遗留项（按优先级）

1. **承载应用启动兜底**：第225轮实测遇到过一次 `PendingIntent` 启动**静默不启动**（logcat 有「已启动」但没有 `START`），
   当时没有回退。建议：启动后 300~500ms 校验「承载应用有没有 task」，没有就走下一档 API。
2. **「视频壁纸显示混乱/右侧裁切」独立复测**：第225/226轮都是推断它与窗口透明同源，没有做过不同宽高比视频的对照。
3. **视频壁纸在车机上的性能数据**：需要实车 `dumpsys gfxinfo` / 掉帧统计。
4. 车上 UNI 桌面（`xyz.i4o.uni.launcher`）未处理，只在模拟器上卸载过。

## 四、环境备忘（踩过的坑）

- **出包必须先** `export ANDROID_USER_HOME="$TMPDIR/cardesk-android-home"`（内含 `debug.keystore`），
  否则 `validateSigningSystem` 会报 `Unable to create debug keystore … not writable`。
- `tools/release.sh` **失败的那次也会 bump 版本号**；失败后要手动改回 `version.properties` 再 `--no-bump` 重出。
- 模拟器 `emulator-5560` 可 `adb root`，能直接改 `/data/data/com.ecarx.cardesk/shared_prefs/cardesk.xml`
  （`chown 1000:1000` + `chmod 660`）；关键 key：
  `pip_app` / `pip2_app` / `last_page` / `design_video_wallpaper` / `design_video_wallpaper_enabled` / `design_video_wallpaper_backend`。
- 切页在模拟器上的可靠操作：`input tap 209 1043`（底部 Dock 分屏键，首页 ⇄ 导航页）。
- 用 `INVISIBLE` 藏承载层会**拆掉 `VirtualDisplay` 的 surface**，切回来永远黑 —— 别再用（第225轮已改为「挪到屏外」）。
- 拿 `configuration.screenWidthDp` 当「task 在哪块屏」的判据**不可信**（搬移瞬间还是主屏尺寸）。
