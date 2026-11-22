# CarDesk 4308 / 1.0.1212-system

## 修了什么

**视频壁纸（「湖畔静思」）下，状态栏 / 天气 / 歌词颜色与背景太近、看不清。**

分两层：

1. **主因：视频壁纸的亮度采样在真车上一直是失败的。**
   第222轮起，视频壁纸的墨色靠「每 3 秒拷一块视频 Surface」来算（`PixelCopy`）。
   这招在模拟器上（GPU 合成）能读到，但在真车（820A）上视频是**硬件解码直通 HWC 图层**，
   `PixelCopy` 读那块 Surface 会一直失败（`ENODEV`）。
   采不到 ⇒ 墨色回落**主题墨色**；用户是**日间主题** ⇒ 深字 `#191C23`
   直接压在暗色视频上 ⇒ 状态栏 + 天气 + 歌词同时「颜色太近、看不清」。
2. **次因：只看平均值会「平均值够、局部发糊」。**
   视频有局部高光（湖畔静思歌词带平均 luma 88、最亮 10% 到 122），
   按平均值选的浅字压到最亮那撮像素上只有 ~3.3:1。

## 怎么用

**不用用户做什么。** 开着视频壁纸就自动是按帧亮度换墨色；
「采不到亮度就跟着主题走」这条兜底只在**视频文件本身读不到/解不开**时才会命中。

## 细节

### 一、视频亮度改成「直接解文件」

新增 `ui/widget/VideoFrameLuma.kt`：

```
MediaExtractor → MediaCodec（输出到 ImageReader 的 Surface）→ 读 Y 平面 → 256×144 灰度图
```

- **为什么不用 `MediaMetadataRetriever`**：第一版就是它，实测在 cardesk28（API 28 / AOSP 软解）
  上 `getFrameAtTime` / `getScaledFrameAtTime` **解出来全黑**
  （`视频壁纸解码诊断 w=256 h=144 px=-16777216`，即 `0xFF000000`），
  同一个文件用 `MediaPlayer` 播是正常的 ⇒ 它走的是「解码器 → 内存 buffer」那条路，
  模拟器软解只对**带 Surface 的输出**真的出图。
- 解到 Surface 是**标准播放路径**，模拟器 / 真车都成立；只要 Y 平面就够（要的就是亮度）。
- 解小图：按解码器原尺寸出帧，Y 平面等距抽样成 256×144 灰度图 ——
  比例坐标与整帧一致，区域含义不用改。
- `PixelCopy` / `TextureView.getBitmap()` 保留为**兜底**（文件那条失败才走）。
- 起播时**立刻采一次**（原来要等第一个 3 秒窗口，那 3 秒里日间深字就压在暗视频上）。

### 二、墨色按「最坏情况」算，不按平均值算

`WallpaperSampler` 新增：

```kotlin
data class Tone(val average: Int, val dark: Int, val bright: Int)
fun tone(bitmap, region, scrim): Tone?
```

一趟扫描同时算平均 + 16 桶 luma 直方图，暗端 = 最暗的一撮（10% 像素）平均色，
亮端 = 最亮的一撮平均色。

`WallpaperContrast.inkForTone(tone, themeNight)`：
- **方向只看平均值**（避免一团白高光把整块判成亮底、翻成深字）；
- 选完再按**最坏情况**拉对比度：浅字对 `tone.bright`、深字对 `tone.dark`，
  用 `ensureContrast` 往白/往黑混到活不下来为止。
- 中灰的物理上限 ≈4.3:1，达不到 4.5 时拉到纯白/纯黑为止（这就是能做的极限）。

### 三、视频壁纸单独一套采样区

`WallpaperSampler` 新增 `VIDEO_TOP_REGION`（`0.74..1.0 × 0.01..0.19`）与
`VIDEO_CENTER_REGION`（`0.28..0.72 × 0.36..0.60`）。

- 原来的 `TOP_REGION` 是 `0.55..1.0 × 0..0.20`：`0.55..0.74` 这段在「湖畔静思」上是
  **亮的暖色岩壁**（实测最亮 luma 191），把区域平均从「暗」（79）拉到「中间调」（114）；
- **静态壁纸那条路一个字节没动** —— 第222轮在 cardesk28 上逐项验收过
  （时钟 14.69 / 天气 14.38 / 明细 6.85），换区域会让那批数字全部失效。

### 四、顺手修掉「换主题把视频墨色冲掉」

`BackgroundView` 在 `videoPassthrough` / `palette` 变化时会重新 publish 一次亮度
（视频壁纸下是 `null, null`）；`LauncherView.applyWallpaperBackdrop()` 以前照单全收
⇒ 可能把视频算出来的墨色冲成主题墨色。
现在：**视频在播且已有视频亮度分布 ⇒ 保持视频墨色**，并打一条
`视频壁纸墨色保持（忽略静态壁纸的兜底亮度）` 便于实车复测。

## 验证

模拟器（cardesk28，API 28，平台签名，5560）实测，视频用 **720p 转码版**
（原始 2340×1080 / AVC High level 5.1 在模拟器软解上解不出来，见第222轮遗留）：

```
I CarDesk/Scene: [EVT] 视频壁纸亮度采样 src=file pos=0     top=74 topBright=88 center=84 centerBright=110
I CarDesk/Scene: [EVT] 视频壁纸亮度采样 src=file pos=3047  top=73 topBright=88 center=86 centerBright=136
I CarDesk/Scene: [EVT] 视频壁纸亮度采样 src=file pos=6054  top=74 topBright=88 center=86 centerBright=139
```

- `src=file` ⇒ 走的是「解文件」那条路（不是拷图层）；数值与 Swift 独立探针一致
  （状态栏/天气区 luma 均值 70~84、p90 94~106；歌词带均值 84~90、p90 121~123）。
- 日间主题 + 视频：状态栏 / 天气 / 歌词都是**浅色**、压在暗色画面上清楚可读。
- 夜间主题 + 视频：同样浅色，正常。
- 日间 → 夜间主题切换（时钟拨到 20:00 触发「跟随时间」）：卡片变深、文字保持浅色，
  视频没有被冲掉、没有黑屏、没有重启进程。

| 项 | 结果 |
|---|---|
| 全量 JVM 单测 | 2666 条（基线 2662 + 新增 7）：两遍各有 **1 条与本轮无关的时序型 flaky**（`FileLogSinkRotationTest` / `AmbientWritePipelineTest`），两条**单独重跑都通过**；其余全绿 |
| 新增单测 | `WallpaperContrastTest` +4（分布未知继承主题 / 暗底浅字按亮端拉够 / 高光不翻方向 / 亮底深字按暗端拉够）、`WallpaperSamplerTest` +3（纯色三档一致 / 一半暗一半亮分得开 / 空位图返回空） |
| 编译 | `assembleSystem` 通过 |
| 装机冒烟 | cardesk AVD（5560）`FATAL / Exception` **0**，进程存活 |
| 交付包 | `dist/CarDesk-platform-ecarx-cardesk-4308.apk`（3,695,739 B，sha256 `790c100a…d188db`，装机验证过的同一份） |
| R2 上传 | **已上传**：`https://file.chuxi.bid/cardesk/latest.json` + `cardesk/CarDesk-platform-ecarx-cardesk-4308.apk`，下载回读 SHA-256 一致 |

截图：`emu-verify/r231-01-day-video-wallpaper.png`、
`r231-02-night-video-wallpaper.png`。

## 本轮未做

- **没在实车上复测**（车机 `198.18.50.1:5555` 仍 ping 不通）。
  实车要确认的两点：① 亮度采样日志是 `src=file`；② 日间主题下状态栏 / 天气是浅色。
- **没做「采样也失败」的兜底**：视频文件读不到时仍回落到主题墨色（与旧行为一致）。
  真要再兜一层，得在日间主题 + 视频壁纸时强制用夜间那套浅字 —— 但亮色视频会反过来糊，
  这版先不做。
- **未提交 Git**（R2 已上传）。
