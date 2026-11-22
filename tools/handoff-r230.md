# 第230轮交接（写于第229轮收尾）

> 上一轮（第229轮）已交付 **4306 / 1.0.1210-system**，并上传 R2。
> 这一轮开工前先读第 3 节的「已知问题」——里面有一条需要实车确认的。

---

## 1. 第229轮做了什么

用户两条反馈：

1. **单画中画时，音乐卡的进度条等图标内容不跟随卡片大小变化**（贴边 / 遮挡）——
   已改成按卡片实际宽度分三档重排（见 `CHANGELOG.md` 第229轮）。
2. **拖动分隔条调比例卡顿** —— 改成「拖动期间不 resize 虚拟屏，松手 resize 一次」。

改动面（全部在 `app/src/main/java`）：

| 文件 | 改动 |
|---|---|
| `ui/pages/NavPage.kt` | `absIn()` 补右边距；`applyCombinedWidth()` 三档；`applyActionVisibility()` 加 `roomy` 门槛；控制键窄档收紧；`dragSteps` 计数与「画中画拖动结束」日志 |
| `ui/pip/PipSurface.kt` | `dragPreview` + `beginDragPreview()` / `endDragPreview()`；`onSizeChanged()` 预览态直接返回 |
| `ui/pip/PipMirrorHost.kt` | `dragPreview` + `pendingW/H`；`resize()` 预览态只记尺寸 |

新增测试：无（纯 View 版式，靠模拟器截图判据）。
全量单测：**2644 / 0 failures**。
证据：`emu-verify/REPORT-r229.md` + `emu-verify/r229-01…04-*.png`。

---

## 2. 交付

| 项 | 值 |
|---|---|
| APK | `dist/CarDesk-platform-ecarx-cardesk-4306.apk` |
| versionCode / Name | `4306` / `1.0.1210-system` |
| SHA-256 | `26784e77af99784124ee8d6a01aac25eb1aa6d4e9502e5091d7ae6a26a5c564a` |
| R2 | `cardesk/CarDesk-platform-ecarx-cardesk-4306.apk` + `cardesk/latest.json`（公网回读 `4306 / 1.0.1210 / 3674375 / 26784e77`） |

---

## 3. 已知问题（下一轮开工先看）

1. **拖动流畅度需要实车确认**：模拟器是软件渲染，且注入手势会被合并成一步，
   量化对比（gfxinfo）拿不到可信结论 —— 车机上到底还卡不卡，必须在车上拖一遍。
2. **LAYER 路线（关视频壁纸）在模拟器上冷启动可能整块黑**（第228轮就记录了）：
   `画中画显示 … mode=embedded` 但像素全空；同一配置 `com.cardesk.embedtest` 正常。
   真车第228轮该路线正常 ⇒ 记为「模拟器环境差异」，但下一轮若在车上复现，要按真问题查。
3. **承载应用启动兜底**：`ActivityView` 的 `PendingIntent` 路径出现过「静默没启动、也不回退」
   （模拟器 16584 那次）；建议做成「启动后 1.5s 校验有没有 task，没有就回退下一档」。
4. 音乐卡三档只调版式、没调字号；很长的歌名在 218 宽下按 `ellipsize` 截断。
5. 车上的 UNI 桌面没动（只在模拟器上卸载过）。

---

## 4. 环境备忘

- **出包**：先 `export ANDROID_USER_HOME="$TMPDIR/cardesk-android-home"`（内含 `debug.keystore`），
  否则 `validateSigningSystem` 会报 `Unable to create debug keystore … not writable`。
- **release.sh 失败也会 bump 版本号**（第229轮实测：第一次因 flaky 单测失败，
  版本从 4304 涨到 4305；重跑又涨到 4306）。失败后要么 `--no-bump` 重出，要么认这个号。
- **flaky 单测**：`PipRatioTest.quantize_snapsToPixelGrid` 在浮点边界偶发失败一次，
  同代码重跑即绿。见到它失败先重跑，别急着改代码。
- **R2 上传**：`tools/publish_r2.py` 的 urllib 路径传大 APK 会 `Broken pipe` / 写超时；
  脚本已自动回退到 `curl --aws-sigv4`（约 4 秒传完）。
- **模拟器改 prefs**：`/data/data/com.ecarx.cardesk/shared_prefs/cardesk.xml`，
  改完必须 `chown 1000:1000` + `chmod 660`，否则 App 读不到。
  - 比例：单画 `desk_primary_ratio_normal_v1`、双画 `desk_primary_ratio_dual_v1`；
  - 单/双画：`desk_dual_pip`；主/副画应用：`pip_app` / `pip2_app`。
  - ⚠️ 别把 `pip2_app` 设成 `com.cardesk.launcher`（画中画里套桌面，整机被拖慢、测量全失真）。
- **模拟器手势注入**：`input motionevent` 在 API 28 不存在；`input draganddrop` 的 move 不生效；
  要拖分隔条用 `sendevent /dev/input/event0`（`qwerty2`，值域 0..32767），
  坐标按 `x*32767/1920`、`y*32767/968` 换算，序列见 `REPORT-r229.md` 与第41块的笔记。
- **切页**：`input tap 209 1043`（底部 Dock）在首页 ⇄ 导航页之间切换。
- 车机 `198.18.50.1:5555` 第229轮自始至终**离线**（`ping` 100% 丢包、`adb connect` 超时）。

---

## 5. 建议的下一轮顺序

1. 车机连上后：**先做实车复测**（拖动流畅度、LAYER/MIRROR 两条路线、全屏右侧无空白）；
2. 按需要补「承载应用启动校验 + 回退下一档」；
3. 音乐卡若要支持更窄（<200），再考虑字号分档与两行歌名。
