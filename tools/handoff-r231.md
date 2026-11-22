# 第231轮交接

写于 2026-11-21，第230轮交付之后。

---

## 0. 开工先做这三件事

1. `adb devices`：车机 `198.18.50.1:5555` **仍未连上**（第224~230轮都离线）。
   连上后先做实车复测，清单见 §2。
2. 跑一次全量单测基线：应为 **2659 / 0 failures**。
   ```bash
   export JAVA_HOME="$PWD/../toolchain/jdk-17.0.20.1+1/Contents/Home"
   export GRADLE_USER_HOME="$PWD/../.gradle-home"
   ../toolchain/gradle-8.7/bin/gradle testSystemUnitTest --offline --console=plain -PcardeskPackage=com.ecarx.cardesk
   ```
3. 读 `CHANGELOG.md` 顶部的第230轮段，确认理解本轮改了什么。

---

## 1. 第230轮交付

| 项 | 值 |
|---|---|
| 版本 | `4307 / 1.0.1211-system` |
| APK | `dist/CarDesk-platform-ecarx-cardesk-4307.apk`（3684939 字节） |
| SHA-256 | `776dd3814194ff11091287104d8b59706f2e80f86f25e449d347ab42e8e3eecd` |
| R2 | 已上传（`cardesk/…4307.apk` + `cardesk/latest.json`，公网回读一致） |
| 单测 | 2659 / 0 failures / 0 errors / 0 skipped |

本轮改动：

- 新增 `core/ImagePicker.kt`（系统选择器降级链 + 取址三级兜底）
- 新增 `core/ImageLibrary.kt`（图片扫描纯逻辑）
- 新增 `ui/ImagePickerOverlay.kt`（应用内图片列表）
- 改 `MainActivity`（`startWallpaperImport` / `onActivityResult`）
- 改 `LauncherView`（`openImagePicker` / `importWallpaperFromLibrary`）
- 新增单测 `ImageLibraryTest`（8）、`ImagePickerTest`（7）

---

## 2. 待实车复测（按优先级）

1. **静态壁纸导入**：车上点「导入图片」→ 是否直接进应用内列表 → 选一张是否立刻生效。
2. **拖动调比例流畅度**（第229轮改动）：单画 + 双画各拖一次，看是否还卡。
3. **视频壁纸**：`SurfaceView` 后端是否正常、画中画是否仍是圆角。
4. **音乐卡三档**：把画中画拉宽 / 拉窄，看进度条、控制键是否跟着走。

---

## 3. 已知问题（沿用上一轮，未闭环）

1. 实车复测全部缺失（车机离线）。
2. LAYER 路线（关视频壁纸）在**模拟器**上冷启动可能整块黑；真车第228轮正常。
3. 视频壁纸在车机上的性能没有数据。
4. 承载应用启动兜底：遇到过 `PendingIntent` 静默不启动且不回退。
5. 车上 UNI 桌面没动（只在模拟器上卸载过）。

---

## 4. 环境备忘

- **出包**必须先 `export ANDROID_USER_HOME="$TMPDIR/cardesk-android-home"`（内含 `debug.keystore`），
  否则 `:embedtest:validateSigningSystem` 会因 `~/.android` 不可写而失败。
- `release.sh` **失败也会 bump 版本号**（第229轮踩过：4304→4305→4306）。
- R2 大文件上传走 `urllib` 会 broken pipe，`tools/publish_r2.py` 已自动回退 `curl --aws-sigv4`。
- 模拟器改 prefs：`/data/data/com.ecarx.cardesk/shared_prefs/cardesk.xml`，
  改完必须 `chown 1000:1000` + `chmod 660`，否则不生效。
- 常用 prefs 键：`pip_app`、`pip2_app`、`desk_dual_pip`、`desk_primary_ratio_normal_v1`、
  `desk_primary_ratio_dual_v1`、`design_video_wallpaper`、`design_video_wallpaper_enabled`、
  `design_video_wallpaper_backend`、`wallpaper_custom`。
- 模拟器手势注入用 `sendevent /dev/input/event0`（API 28 没有 `input motionevent`，
  `input draganddrop` 不产生 move）。
- 模拟器 UI 坐标（1920×1080）：设置齿轮 `(66,42)`；设置左栏「壁纸」`(300,528)`；
  壁纸页「导入图片…」在向下滑 `input swipe 1000 800 1000 300 400` 之后是 `(789,411)`。
- 验证「没有系统选择器」：`pm disable-user --user 0 com.android.documentsui` +
  `pm disable-user --user 0 com.android.gallery3d`，验完记得 `pm enable` 回来。
