# 第225 / 226 轮验收报告

- 验收对象：dsh 的**第225轮**（交付 4302 / `1.0.1206-system`）与**第226轮**（交付 4303 / `1.0.1207-system`）
- 验收环境：AVD `cardesk28`（Android 9 / API 28 / arm64 / 平台签名），`emulator-5560`，1920×1080@160dpi
- 验收日期：2026-11-20
- 被验包：`dist/CarDesk-platform-ecarx-cardesk-4303.apk`
  （3661047 字节，SHA-256 `745359013bb412324d59d91f6a62fc6397b43c5b277a701acd9fc171906ca2bb`，**与 r226 报告所写完全一致**）
- 实车 `198.18.50.1:5555`：**仍然离线**，与 r225/r226 报告一致 ⇒ 实车复测仍未做（见 §六）

---

## 一、结论

**两轮的功能性改动全部通过验收**，在基准模拟器上实测复现了「画中画 = 高德」这条路径：

- r226 的新路线（窗口透明 ⇒ 自建虚拟屏 + `TextureView` 镜像）**确实生效**，高德真的被承载在画中画里；
- 圆角、切页残留、切回不重启、`FATAL` 0 条 —— 全部达标；
- r225 的搬移守护窗口、动态壁纸移除 —— 全部生效；
- 全量单测实测 **2628 / 0 failures / 0 errors / 0 skipped**，与 r226 报告一致。

**但有 1 个必须修的问题（§四 问题 1）**：r226 的更新说明与代码默认值对不上，
导致**「默认方案」并没有被修**——用户报的「默认方案掉帧很严重」在默认配置下**依然存在**。
另有两个次要问题（§四 问题 2、3）。

---

## 二、代码验收

### 2.1 第225轮

| 改动 | 落点 | 验收 |
|---|---|---|
| 搬移**守护窗口**（0.6/0.9/1.2/1.8/2.4s ≈ 7s），复查时优先搬「跑到别的屏上」的 task，最多 3 次 | `core/HostTaskMigrator.kt`（`verifyDelayMsForAttempt`）、`ui/embed/HostTaskMigration.kt` | ✅ 代码在位；实测日志打出 `承载任务守护 round=0..4` 与 `守护窗口结束` |
| 离开导航页把承载层**挪到屏外**（`translationX=-8000`），不拆承载面 | `ui/pip/PipSurface.kt`、`ui/LauncherView.kt` | ✅ 切页后首页无黑块 |
| 视频壁纸默认后端 → `TextureView`；去掉抢 `TextureView` 帧回调的 bug | `core/Prefs.kt`、`ui/LauncherView.kt` | ✅ 见 §四 问题 1（默认值与 r226 说明不一致） |
| **移除程序化动态壁纸** | 删 `core/LiveWallpapers.kt`、`ui/widget/LiveWallpaperRenderer.kt` | ✅ 文件确已删除，且全仓 `grep -rE "LiveWallpaper"` **零命中**（无残留引用、无悬空调用） |

### 2.2 第226轮

| 改动 | 落点 | 验收 |
|---|---|---|
| 渲染路线判据（纯逻辑） | `core/PipRenderPlan.kt` | ✅ `modeFor(translucentWindow)` 单测覆盖 |
| 自建虚拟屏 + `TextureView` 镜像宿主 | `ui/pip/PipMirrorHost.kt` | ✅ 实测 `mode=mirror`、虚拟屏 1287×867 |
| 虚拟屏 displayId 登记进 `ActivityEmbed`，搬移守护 / 黑屏自愈照常工作 | `ui/embed/ActivityEmbed.kt`（`bindHostedDisplay` / `virtualDisplayIdOf` 先查表） | ✅ 实测 `画中画宿主屏已登记 display=19`，搬移守护随后正常跑满 5 轮 |
| 尺寸变化只 `resize` 不重建 | `PipMirrorHost.onSurfaceTextureSizeChanged` | ✅ 代码在位 |

**代码层面未发现缺陷。** 两条路线的选择、登记、释放顺序自洽，`release` 时先注销登记再释放虚拟屏（顺序正确）。

---

## 三、模拟器实测（画中画 = 高德）

配置：`pip_app=com.autonavi.amapautp.yh`、`last_page=1`、视频壁纸开、
`design_video_wallpaper_backend=0`（`SurfaceView`）⇒ 窗口 `TRANSLUCENT` ⇒ **MIRROR 路线**。

### 3.1 两条路线都验了

| 判据 | MIRROR 路线（backend=0，r226 新路线） | LAYER 路线（backend=1，r225 老路线） |
|---|---|---|
| 路线判据日志 | `画中画显示 … mode=mirror` | `画中画显示 … mode=embedded` |
| 窗口格式 | `winFmt=-3`（TRANSLUCENT） | `winFmt=-1`（OPAQUE） |
| 虚拟屏 | `CarDesk 画中画` 1287×867，owner uid 1000，`FLAG_OWN_CONTENT_ONLY`，state ON | （`ActivityView` 自带） |
| 高德嵌在卡里 | ✅ 截图可见高德 UI | ✅ 截图可见高德 UI |
| 四角**圆角** | ✅ 裁图确认（`acc-r225r226-08-pip-corner-tl.png`） | ✅ 截图确认 |
| 切到首页 | ✅ **无黑块**，视频壁纸继续播 | ✅ **无黑块** |
| 切回导航页 | ✅ 画面回来，高德 `START` 新增 **0** 次 | ✅ `START` 新增 **0** 次 |
| `FATAL` | ✅ **0** 条 | ✅ **0** 条 |
| 画面探针 | `black=false opaquePct=100 maxLuma=255` | — |

### 3.2 决定性证据：高德**没有**跑回首页

```
Display #22 (我们的虚拟屏)：
  Activities=[ActivityRecord{7f8bb64 u0 com.autonavi.amapautp.yh/…MainMapActivity t899}]
Display #0 (主屏)：
  mResumedActivity: ActivityRecord{b7a45ed u0 com.ecarx.cardesk/…MainActivity t904}
```

⇒ 高德的 `MainMapActivity` **在虚拟屏上**，主屏上只有桌面。
这是「高德跑回首页」被修好的**直接证据**（不只是看截图）。

### 3.3 搬移守护窗口确实在跑

```
09:26:07.311 承载任务跑到别的屏上，优先搬它 task=899 stack=60 size=1920x944
09:26:07.325 承载任务搬到我们的虚拟屏 api=STACK_TO_DISPLAY task=899 stack=60 to=19
09:26:07.627 承载任务跑到别的屏上，优先搬它 task=899 stack=62 size=1920x944
09:26:07.639 承载任务搬到我们的虚拟屏 api=STACK_TO_DISPLAY task=899 stack=62 to=19
09:26:08.446 承载任务守护 round=0 delay=600  reason=已在我们的虚拟屏上
09:26:09.049 承载任务守护 round=1 delay=900  reason=还在我们的虚拟屏上
09:26:09.963 承载任务守护 round=2 delay=1200 reason=还在我们的虚拟屏上
09:26:11.166 承载任务守护 round=3 delay=1800 reason=还在我们的虚拟屏上
09:26:12.967 承载任务守护 round=4 delay=2400 reason=还在我们的虚拟屏上
09:26:15.373 承载任务搬移完成 reason=还在我们的虚拟屏上，守护窗口结束
```

### 3.4 切页前后画面对比（比 r226 报告更严谨）

r226 报告写「回来画面与切走前**逐字节相同**」。实测：

- **静止子区域**（高德搜索框那块，70,100–470,245）：**逐字节相同** ✅
  （before `8d16a900284b731f` == after `8d16a900284b731f`）
- **整块画中画卡**（50,80–1330,940）：**不完全相同**，变化像素 3232 / 1 100 800 = **0.294%**

⇒ 差异来自高德自己的地图动画（罗盘/飘字在动），**不是**重绘或重启。
结论成立，但「逐字节相同」这句话只对静止区域成立 —— 措辞需要收紧。

### 3.5 单测

```
gradle testSystemUnitTest → BUILD SUCCESSFUL
261 个测试类 / tests=2628  failures=0  errors=0  skipped=0
```

与 r226 报告的 2628 / 0 **完全一致** ✅

---

## 四、问题清单

### 🔴 问题 1（必须修）：`design_video_wallpaper_backend` 的**默认值与更新说明对不上**，
### ⇒ r226 想修的「默认方案掉帧」在默认配置下**并没有被修**

**代码事实**（`core/Prefs.kt:162-165`）：

```kotlin
var videoWallpaperBackend: Int
    get() = VideoWallpaper.normalizeBackend(
        sp.getInt("design_video_wallpaper_backend", VideoWallpaper.BACKEND_TEXTURE_VIEW),  // ← 默认 1
    )
```

`BACKEND_TEXTURE_VIEW = 1` 是第225轮改的默认值，**第226轮没有改回来**。

**更新说明的事实**（`tools/notes-r226.md`）：

> 什么都不用改：视频壁纸开（`design_video_wallpaper=1`）+ **后端保持 `0`（`SurfaceView`）**即可。

**实测复现**（把 `design_video_wallpaper_backend` 这个键**整个删掉**，
模拟「从没手动设过这项」的用户 —— 也就是所有从旧版本升级上来、从没碰过这项的用户）：

```
视频壁纸诊断 view=TextureView vis=0 size=1920x1080 winFmt=-1 passthrough=true
视频壁纸已开始播放 bytes=60878141 backend=TextureView
[EVT] 画中画显示 pkg=com.autonavi.amapautp.yh app=Amap Auto mode=embedded role=主画
```

⇒ 默认配置下走的是 **TextureView（掉帧那条路）** + **LAYER 老路线**，
**r226 的 MIRROR 路线一次都不会被走到**。

**为什么这条要紧**：
1. 用户在 r226 报的原始诉求是「**现在的默认方案**掉帧很严重」——
   r226 修好了 `backend=0` 这条路，但**默认仍然是掉帧那条**。
2. 4302（r225）把默认值从 0 翻成 1；`sp.getInt` 不会把默认值写回文件，
   所以**升级到 4303 的用户，这个键依然不存在** ⇒ 继续掉帧。
3. **App 里没有这个开关**：`grep` 全仓 `SettingsOverlay.kt` 找不到任何后端切换入口，
   唯一能改它的办法是用 adb 改 `shared_prefs`。
   r225 的更新说明写「可以把 `design_video_wallpaper_backend` 改成 0」——
   **车机用户根本没这个操作入口**（除非走 adb）。

**建议修法（择一）**：

- **A（推荐，一行）**：把 `Prefs.videoWallpaperBackend` 的默认值改回
  `VideoWallpaper.BACKEND_SURFACE_VIEW`，并同步更新那段「第225轮为什么把默认值翻过来」的注释；
  这样 r226 的 MIRROR 路线成为默认路径，用户诉求真正闭环。
- **B**：保持默认 1，但在 `SettingsOverlay` 里补一个「视频壁纸渲染后端」开关
  （`SurfaceView 省电` / `TextureView 圆角更稳`），让用户自己能选 —— 同时也解决 r225 那条
  「可以改成 0」的操作入口缺失问题。
- **C（最低限度）**：至少把 `notes-r226.md` / `latest.json` 的更新说明改成与代码一致
  （写明默认仍是 `TextureView`，要走省电路线需手动改 `shared_prefs`），
  否则车机上会按说明去「保持 0」而实际拿到的还是 1。

### 🟡 问题 2：`SurfaceView` 路线下视频壁纸**每次冷启动起播两次**

实测（`backend=0`）：

```
09:31:17.504 视频壁纸诊断 view=SurfaceView … winFmt=-3
09:31:17.504 视频壁纸已开始播放 backend=SurfaceView
09:31:17.946 E BufferQueueProducer: [SurfaceView - com.ecarx.cardesk/…MainActivity#0]
              detachBuffer: BufferQueue has no connected producer
09:31:18.156 视频壁纸诊断 view=SurfaceView … winFmt=-3
09:31:18.156 视频壁纸已开始播放 backend=SurfaceView
```

**根因**（代码里已写明，见 `LauncherView.applyVideoWindowFormat` 的注释「⚠️ 切 format 会重建窗口 Surface」）：
桌面窗口从 OPAQUE 切成 TRANSLUCENT 时，`SurfaceView` 的 surface 被销毁重建
⇒ 一轮 `surfaceDestroyed` → `surfaceCreated` ⇒ 播放器被放掉再重建。
旧播放器**有被正确释放**（不泄漏），但**画面会从第一帧重新开始**，
用户能看到一次约 650ms 的「视频跳回开头」。

- 这**不是** r225/r226 引入的（是 r214 那条 `SurfaceView` 路径的老行为），但 r226 的说明要把它推成默认，
  于是它会变成默认可见的现象。
- 另外这也解释了 r225 报告里那句「真因是诊断代码抢了 `TextureView` 内部的帧回调」——
  那条 `setOnFrameAvailableListener` 确实已被去掉（代码在位，`onSurfaceTextureUpdated` 还在），
  但 r226 实测走 `SurfaceView` 时**没有**「第一帧已上屏（TextureView）」那条日志 ——
  因为那条日志只在 TextureView 分支里打（`LauncherView.kt:330`）。

### 🟡 问题 3：r226 报告「画面逐字节相同」措辞过强

见 §3.4：静止子区域逐字节相同 ✅，但整块卡差 0.294%（高德自己的动画）。
不影响功能，但建议在报告里改成「静止区域逐字节相同，整卡差异 0.294% 来自高德地图动画」。

---

## 五、遗留（两轮交接里已声明，不是本轮引入）

- **实车复测仍未做**（车机 `198.18.50.1:5555` 离线）。
  本次验收的模拟器结论**不能替代**实车验证 —— 尤其是「掉帧」这条，
  模拟器是软件解码（`c2.android.avc.decoder`），帧率表现不代表车机。
- r227 列的遗留项仍然成立：
  1. 承载应用启动兜底（`PendingIntent` 静默不启动时回退下一档）；
  2. 「视频壁纸显示混乱 / 右侧裁切」的独立复测（不同宽高比视频对照）；
  3. 车机上的 `dumpsys gfxinfo` / 掉帧统计；
  4. 车上 UNI 桌面（`xyz.i4o.uni.launcher`）未处理。
- r226 报告里「画中画不转发触摸」仍是已知边界，与旧路线一致。

---

## 六、证据清单

| 文件 | 内容 |
|---|---|
| `acc-r225r226-01-nav-pip.png` | MIRROR 路线：高德嵌在画中画里（导航页） |
| `acc-r225r226-02-home.png` | 切到首页：**无黑块**，视频壁纸继续播 |
| `acc-r225r226-03-nav-back.png` | 切回导航页：画面回来，高德未重启 |
| `acc-r225r226-04-layer-route.png` | LAYER 路线（backend=1）：高德嵌在画中画里，圆角 |
| `acc-r225r226-05-layer-home.png` | LAYER 路线切到首页：无黑块 |
| `acc-r225r226-06-layer-back.png` | LAYER 路线切回：`START` 新增 0 |
| `acc-r225r226-07-final-mirror.png` | 最终状态截图（MIRROR 路线） |
| `acc-r225r226-08-pip-corner-tl.png` | 画中画卡左上角裁图（**圆角**证据） |
| `tools/set-pip-prefs.sh` | 本次验收用的 pref 改写脚本（可复现） |

复现步骤：

```bash
cd cardesk
bash tools/set-pip-prefs.sh 0          # 画中画=高德 + 视频壁纸 + SurfaceView 后端
# 或
bash tools/set-pip-prefs.sh 0 --drop-backend-key   # 模拟「从没设过后端」的用户
adb -s emulator-5560 shell input tap 209 1043        # 底部 Dock 分屏键：首页 ⇄ 导航页
```

---

## 七、验收意见

**代码与功能：通过。** 两轮改动在基准模拟器上全部实测成立，
「画中画 = 高德」这条路径端到端跑通，高德确实被承载在虚拟屏里、不再抢主屏。

**放行前建议处理问题 1**（一行改动，或补一个 App 内开关），
否则 r226 想解决的「默认方案掉帧」在用户默认配置下并未解决，
而更新说明还会把用户引向一个 App 里并不存在的操作。

问题 2、3 可作为已知边界记录，不阻塞。
**实车复测仍然欠着**，建议在问题 1 修完后一并带上车机验证。
