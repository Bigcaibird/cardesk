# 第226轮接手说明

## 先做的事

1. **车机复测第224、225两轮**（车机 `198.18.50.1:5555` 在第225轮期间一直不通：
   `ping` 100% 丢包、`adb connect` `Operation timed out`）。要重点看：
   - 第224轮：导入视频壁纸后高德会不会再跑回首页（alpha 闸门 + 承载面重建不再无条件 `startActivity`）；
   - 第225轮：**左右切首页/导航页**、画中画圆角、视频壁纸是否卡顿。
2. 车机上确认 `design_video_wallpaper_backend` 的取值：
   - 默认已改成 `1`（`TextureView`，窗口不透明 → 圆角正常、切页无残留）；
   - 若发烫/掉帧明显，改回 `0`（`SurfaceView`，HWC 直通），代价是圆角与切页残留问题会回来。
3. 第224/225轮的实车复测都缺，补完之后在 `CHANGELOG.md` 两轮段落里改「已验证」。

## 第225轮做了什么（交付 4302 / 1.0.1206-system，已传 R2）

| 内容 | 落点 |
|---|---|
| 搬移守护窗口（0.6/0.9/1.2/1.8/2.4s，约 7s），并**优先搬「跑到别的屏上」的 task**、最多 3 次 | `core/HostTaskMigrator.kt`、`ui/embed/HostTaskMigration.kt` |
| 离开导航页把承载层挪到屏外（`translationX=-8000`），不拆承载面 | `ui/pip/PipSurface.kt`、`ui/LauncherView.kt` |
| 视频壁纸默认后端改 `TextureView`；修掉抢帧回调的 bug | `core/Prefs.kt`、`ui/LauncherView.kt` |
| 移除程序化（自绘）动态壁纸 | 删 `core/LiveWallpapers.kt`、`ui/widget/LiveWallpaperRenderer.kt` + 测试 |

细节见 `emu-verify/REPORT-r225.md`、`tools/notes-r225.md`。

## 两个故意没做的坑（别当成遗漏）

1. **用 `INVISIBLE` 藏承载层**：实测会让 `ActivityView` 拆掉 `VirtualDisplay` 的 surface，
   切回导航页后画中画**一直黑**（`/tmp` 里那轮截图）。必须用「挪到屏外」，不能用 visibility。
2. **拿宽度（`configuration.screenWidthDp`）当「task 在哪块屏」的第二套证据**：surface 重建瞬间
   任务配置还停在主屏尺寸，会误判成「跑到主屏」→ 触发搬移 → 正是本轮要修的现象。
   API 28 的 `RunningTaskInfo` **没有 `displayId`**，应用进程也拿不到 `getAllStackInfos()`。

## 环境备忘

- 出包前必须 `export ANDROID_USER_HOME="$TMPDIR/cardesk-android-home"`（沙箱下 `~/.android` 不可写，
  AGP 建 debug keystore 会失败）；`release.sh` **失败也会 bump 版本**，失败重出要 `--no-bump`。
- 模拟器 `emulator-5560` 可 `adb root`；`adb reboot` 后 root 会掉。
- 改 prefs 的手法与关键 key 见 `emu-verify/REPORT-r225.md` 第五节。
- 切页动作：`input tap 209 1043`（底部 Dock 分屏键，首页 ⇄ 导航页）。
- 模拟器上高德是**软件解码**，1080p60 视频会卡帧；车机是硬解，不要拿模拟器的掉帧当 bug。
- UNI 桌面已卸载（用户要求），HOME 归 `com.ecarx.cardesk`；同套件的 `xyz.i4o.uni.launcher.insets`、
  `xyz.i4o.uni.fixture.player` 还在（不影响 HOME）。
