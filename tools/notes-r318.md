# 第三百一十八轮：视频壁纸 P0/P1（日夜双片 / 首帧闸门 / 解码校验）

验证环境：AVD `Lynkco_820A_car15_arm64`（`emulator-5572`，API 28 / arm64 / 平台签名），包名 `com.ecarx.cardesk`。
构建：`assembleSystem -PcardeskPackage=com.ecarx.cardesk --offline`。

## 一、背景

视频壁纸原来只有一段片、起播与换片的路子粗糙：

1. 起播瞬间图层还挂着、画面还没来 ⇒ **先露一段黑**；
2. 日/夜只有一段视频，切日夜桌面要重启才换（有时干脆不换）；
3. 导入视频不校验车机能不能解，**解不出帧的素材会被显示成永久黑背景**；
4. 前后台切换不暂停，桌面在后台还在解码；
5. 导入是「先覆盖目标文件、再校验、不合格再删」——一份坏素材会把用户原来能用的那段一起删掉。

## 二、改动

| 项 | 做法 |
| --- | --- |
| 日/夜双片 | `VideoWallpaper` 增加 `nightFile/videoFile(ctx, night)/hasNight/importFromUri(...,night)/importFromFile(...,night)/deleteNight`；设置页「日间视频 / 夜间视频」两行 + 导入/删除；`MainActivity` 两个请求码（日间 / 夜间） |
| 切日夜免重启 | `LauncherView.maybeSwitchVideoForNight()`：外观切到另一侧且夜间版存在时，先藏图层 → 释放旧播放器 → 重起播另一段；「立即切换」用 `videoNightOverride` 明确指定版本（不能再用时间推算，否则 19 点切夜间又选回日间片） |
| 首帧闸门 | 起播前藏图层；`MEDIA_INFO_VIDEO_RENDERING_START` 到达才 `revealVideoWallpaper()`；2 秒兜底（`VIDEO_REVEAL_FALLBACK_MS`） |
| 兜底前先探解码 | 兜底触发时先用 `VideoProbe.decodeCheck()` 真解一帧：解得出才显示，解不出就走 `handleUndecodableVideo()`（停播 + 切回静态壁纸 + 提示），**不再出现永久黑背景** |
| 静音 | `setVolume(0f, 0f)`，实测 `dumpsys media.player` 音量 `0.000000, 0.000000` |
| 裁剪铺满 | `VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING` |
| 生命周期 | `MainActivity.onStart/onStop` → `resumeVideoWallpaper()/pauseVideoWallpaper()`（只暂停不释放） |
| 导入校验 | 新增 `core/VideoSpec.kt`（规格常量 + 判定）与 `core/VideoProbe.kt`（`MediaMetadataRetriever` 读元数据 + `MediaExtractor`+`MediaCodec` 真解一帧，超时 2.5s）；**先校验源文件、通过后才覆盖**（Uri 路径先落缓存再校验），坏素材不动已导入的那份 |
| 导入失败只清临时文件 | `VideoWallpaper.copyToWallpaper` 的异常分支只删 `*.tmp`（目标文件靠原子 `rename` 替换） |
| 顺带修的两个真 bug | ①`SurfaceView` 设 `INVISIBLE` 会掐掉 surface ⇒ 首帧闸门永不通过（隐藏改为「壁纸盖住」）；②`prepareVideoWallpaper()` 里仍在用日间 `file()` ⇒ 夜间版永远不播 |

## 三、实测（`emulator-5572`）

| 场景 | 结果 |
| --- | --- |
| 冷启动（日间片，1920×1080） | `选文件 night=false bytes=60878141` → `已开始播放` → `图层已显示 reason=首帧就绪`，桌面视频正常、卡片叠在上面 |
| 切日夜（立即切换） | 同一进程（pid 不变）：`日夜切换，换一段视频 night=true` → `选文件 night=true file=wallpaper_night.mp4 bytes=46816024` → `图层已显示 reason=首帧就绪 night=true` |
| 静音 | `dumpsys media.player` → 音量 `0.000000, 0.000000` |
| 解不出帧的素材（2340×1080 / level 5.1） | 首帧 2 秒未到 → `视频解码探测结束 ok=false` → `这段视频解不出画面，切回静态壁纸` + Toast，背景恢复壁纸，无黑屏 |
| 导入解不出帧的素材 | 只在源文件上探测（`path=hupan2340.mp4 ok=false`）→ 拒绝，已导入的日间/夜间两份文件大小不变（60,878,141 / 46,816,024） |
| 崩溃 | `logcat -b crash` 0 条 |

## 四、判据

- `adb logcat -s CarDesk/Setting` 出现 `视频壁纸：选文件 … night=false|true` 且 `bytes` 与设备内文件一致；
- 出现 `视频壁纸图层已显示 reason=首帧就绪`（而不是只有 `首帧超时兜底`）；
- `dumpsys media.player` 里 `numFramesTotal` > 0（解不出帧的素材是 0）；
- 切日夜时进程 pid 不变、日志里出现 `日夜切换，换一段视频`；
- 导入坏素材时目标目录文件大小不变。

## 五、诚实缺口

1. **系统选择器这条路在本镜像上无法实测**（车机镜像里没有 `documentsui`，`ACTION_GET_CONTENT` 拉起即退），只验证了「应用内视频库」这条路；Uri 路径的代码（`copyUriToCache`）只能靠代码审查。
2. **前后台暂停/恢复没有实机触发**：这台车机上桌面是 HOME，`am start` 别的界面不会把 `MainActivity` 打到 `STOPPED`（`dumpsys activity` 里它一直 `RESUMED`），所以 `pauseVideoWallpaper/resumeVideoWallpaper` 只有代码路径、没有日志佐证。
3. **裁剪铺满没有可视对照**：本机软解只吃 1920×1080/16 对齐的素材，做不出「非 16:9 且能解码」的样本；缩放模式已按常量设置（`SCALE_TO_FIT_WITH_CROPPING`），但没有截图证明无黑边。
4. 测试用的两段视频是用户提供的素材，未随仓库发布；设备上的两段片保留在 `/sdcard/Android/data/com.ecarx.cardesk/files/video_wallpaper/`。

## 六、截图

| 文件 | 内容 |
| --- | --- |
| `emu-verify/shots-r318/r318-01-day-video.png` | 日间视频壁纸在桌面播放（发行包 4336，首帧就绪） |
| `emu-verify/shots-r318/r318-02-night-video.png` | 切到夜间后同一进程换成夜间视频（画面已换、未重启） |
| `emu-verify/shots-r318/r318-03-video-settings.png` | 设置 → 壁纸：启用视频壁纸 / 日间视频 58MB / 夜间视频 58MB / 导入与删除入口 |
| `emu-verify/shots-r318/r318-04-undecodable-fallback.png` | 解不出画面的素材被拦下后回到静态壁纸（不再黑屏） |
