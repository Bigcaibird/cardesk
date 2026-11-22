# 第229轮 · 单画音乐卡跟随卡片宽度 + 拖动调比例不再每帧重排

日期：2026-11-20
构建：`CarDesk 4306 / 1.0.1210-system`（平台签名）
验证环境：`cardesk28` AVD（API 28 / arm64 / 平台签名，`emulator-5560`）

---

## 一、问题

用户反馈两条：

1. **单画中画时，音乐卡片的进度条等图标内容不跟随卡片大小变化**，容易出现遮挡，
   比如进度条贴边。
2. 拖动分隔条调整画中画大小时**卡顿**。

---

## 二、根因

### 2.1 音乐卡是「固定 520 宽」的绝对坐标版式

`NavPage.buildCombinedCard()` 按第四十九轮设计板 `s02-nav` 摆位：

| 元素 | 原来 |
|---|---|
| 来源行 | `left 24` + `MATCH_PARENT`（**无右边距**） |
| 封面 | `left 24, 96×96` |
| 歌名 / 歌手 | `left 140` + `MATCH_PARENT`（无右边距） |
| 进度条 | `left 24` + `MATCH_PARENT`（无右边距） |
| 时间行 | `left 24` + `MATCH_PARENT`（无右边距） |
| 控制键行 | 整行 `MATCH_PARENT`、居中；三个按钮 `40+56+40` 加两个 `56` 间距 = **248 宽** |
| 收藏 / 播放模式 | 一个 `left 24`、一个 `right 24`（贴两边） |

卡片一变窄：

- 整行元素右边没有留白 ⇒ **进度条 / 时间贴边**；
- `封面 96 + 歌名 left 140` 挤在一起 ⇒ 文字被压 / 遮挡；
- 控制键行 248 宽 > 卡片宽 ⇒ **两侧箭头被裁**；
- 贴两边的收藏 / 播放模式与居中控制键**叠在一起**。

### 2.2 拖动卡顿：每帧 resize 虚拟屏

`PipSurface.onSizeChanged()` → `syncVirtualDisplay()` → `VirtualDisplay.resize()`；
镜像路线还有 `PipMirrorHost.onSurfaceTextureSizeChanged()` → `resize()`。

拖分隔条时卡宽每帧都在变 ⇒ **承载应用每帧按新尺寸重新排版重画**
（高德重画整张地图，最重的一笔），主线程被它拖住；模拟器实测拖动期间
手势 move 被合并（24 步只到 1 步）。

---

## 三、改法

### 3.1 音乐卡按实际宽度分三档

`app/src/main/java/com/cardesk/launcher/ui/pages/NavPage.kt`：

- 新增 `absIn(x, y, w, h, right)` —— 整行元素统一 `rightMargin = COMBINED_PAD(24)`；
- 新增 `applyCombinedWidth(width)`：在 `combinedCard` 的 `OnLayoutChangeListener` 里调用，
  按宽度套档（档位不变就直接返回，不重复摆）：

| 档 | 宽度阈值 | 版式 |
|---|---|---|
| 0 | ≥ `COMBINED_NARROW_W`(460) | 设计板版式（封面 96 / 歌名 left 140 / 收藏与播放模式都在） |
| 1 | ≥ `COMBINED_TIGHT_W`(320) | 封面 72、歌名 left 112、收起收藏与播放模式 |
| 2 | < 320 | 隐去封面、歌名从 24 起；控制键间距 12、按钮 32/48/32；隐藏状态文案 |

- `applyActionVisibility()` 增加 `roomy = combinedTier <= 0`：窄档不亮收藏 / 播放模式。

### 3.2 拖动期间不 resize

| 文件 | 改动 |
|---|---|
| `ui/pip/PipSurface.kt` | 新增 `dragPreview` / `beginDragPreview()` / `endDragPreview()`；`onSizeChanged()` 在预览态直接返回；结束时一次性 `syncVirtualDisplay()` |
| `ui/pip/PipMirrorHost.kt` | 新增 `dragPreview` / `beginDragPreview()` / `endDragPreview()`；`resize()` 在预览态只记 `pendingW/H` |
| `ui/pages/NavPage.kt` | `onRatioChanged` 先 `beginDragPreview()`（主/副画）；`onRatioCommitted` 里 `post { endDragPreview() }`，并记「画中画拖动结束」（steps / ratio / dual） |

---

## 四、实测

### 4.1 音乐卡三档（模拟器，prefs 直接改 `desk_primary_ratio_normal_v1`）

| 比例 | 卡片宽度 | 日志 | 画面 |
|---|---|---|---|
| 0.71（默认） | 525 | `tier=0` | 与改动前一致；进度条左右各 24 |
| 0.80 | 363 | `tier=1` | 封面 72、歌名 112 起、无贴边 |
| 0.88 | 218 | `tier=2` | 封面隐去、`‹ ▶ ›` 完整可见、进度条不贴边 |

截图：`emu-verify/r229-01-music-card-tier1.png`、
`emu-verify/r229-02-music-card-tier2.png`、`emu-verify/r229-03-music-card-default.png`。

### 4.2 全屏复核（第228轮那条）

点画中画左缘玻璃条：`画中画全屏切换 to=全屏`，地图铺满整幅 1920 宽，
右下「50 米 / 高德地图」贴到屏幕右边缘（**无 108px 空白**），还原正常。
截图：`emu-verify/r229-04-fullscreen-1920.png`。

### 4.3 单测

`testSystemUnitTest`：**2644 / 0 failures / 0 errors / 0 skipped**。

> 出包第一次运行时 `PipRatioTest.quantize_snapsToPixelGrid` 在浮点边界失败一次，
> 同一份代码重跑即绿 ⇒ 记为 flaky（已写进 `tools/handoff-r230.md`）。

---

## 五、已知边界

1. **车机拖动流畅度仍需实车确认**：模拟器是软件渲染，且注入手势会被合并，
   帧率不代表 820A。
2. 音乐卡三档只调了**版式**，没有重排字号；歌名在 218 宽下按 `ellipsize` 截断。
3. LAYER 路线（关视频壁纸）在模拟器上冷启动可能整块黑（第228轮已记录，
   真车正常），与本轮改动无关。

---

## 六、交付

| 项 | 值 |
|---|---|
| APK | `dist/CarDesk-platform-ecarx-cardesk-4306.apk` |
| versionCode / Name | `4306` / `1.0.1210-system` |
| SHA-256 | `26784e77af99784124ee8d6a01aac25eb1aa6d4e9502e5091d7ae6a26a5c564a` |
| 大小 | 3674375 字节 |
| 签名 | AOSP platform（`c8a2e9bc…92ab8`） |
| R2 | `cardesk/CarDesk-platform-ecarx-cardesk-4306.apk` + `cardesk/latest.json` |
