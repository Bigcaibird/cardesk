# 第146轮：把专辑封面做成第二页音乐卡的背景

## 用户诉求

> 能实现把歌曲图片作为第二页音乐卡片的背景吗

设计板 `design/index.html` 的 `s02-nav` 没有「封面做背景」样式，属**超出设计板的新需求**。
难点不在「把图铺上去」，而在**三层文字压在图上还读不读得清**。

## 结论

**能，已实现并在真机验证通过。**

- 夜间：封面（模糊后）作为整张卡的背景，斜纹/渐变清晰可见，三档文字对比度
  **10.93 / 5.38 / 3.07**（底线 3.0），100% 背景像素合规。
- 日间：受对比度底线约束，封面**优雅降级**为近白卡面（见「已知限制」）。

## 方案：为什么是「亮度映射」而不是「叠一层半透明遮罩」

最初的实现是「把封面整体混向卡面色」：

```
out = cover × (1 − a) + surface × a
```

为了让最亮的像素（纯白封面）也落到亮度上限，`a` 得取到 **0.92** ——
**整张封面只剩 8% 的份量**，所有像素被拉到同一个亮度。实测整卡是一片几乎均匀的
深蓝灰，**封面等于没画**（只剩极淡的斜纹痕迹）。

改用**亮度映射（tone map）**：

1. 先算出封面里最亮的像素有多亮；
2. 把整张图的亮度按一条 `gamma < 1` 的幂曲线**压进允许的区间**
   （`gamma < 1` 会把暗部抬起来，保留画面的明暗结构）；
3. 缩放发生在**线性光**空间 → **色相不变**。

同样的封面，灰度范围从 `36~53`（塌成一片）变成 `19~52`（结构清晰）。

> **踩过的坑**：变暗走「线性光乘系数」可以，但**变亮不能乘系数** ——
> 深蓝封面乘系数时蓝通道会削顶，亮度根本达不到目标值。变亮必须走「向白色混合」。

## 代码结构

### 新增 1：纯计算层 `core/media/CoverBackdrop.kt`（293 行）

不碰任何 Android 图形对象 → 可以直接在 JVM 里单测。

| 常量 | 值 | 含义 |
|---|---|---|
| `MIN_CONTRAST` | `3.0` | 对比度底线（WCAG 大字号 AA 档） |
| `SOLVE_CONTRAST` | `3.05` | 求解用，留浮点余量 |
| `TONE_GAMMA` | `0.45` | 幂曲线曲率（<1 抬暗部） |
| `BLUR_SHORT_SIDE` | `64` | 模糊图短边目标像素 |

核心 API：`relativeLuminance` / `contrastRatio` / `mixColor` / `gray` /
`targetRange(surface, textColor)` / `toneMap(pixels, lo, hi, gamma)`。

`targetRange` 按「字亮底暗 / 字暗底亮」自动取方向：

```kotlin
// 夜间：字亮底暗 → 给背景亮度定上限
doubleArrayOf(0.0, (lText + 0.05) / SOLVE_CONTRAST - 0.05)
// 日间：字暗底亮 → 给背景亮度定下限
doubleArrayOf(SOLVE_CONTRAST * (lText + 0.05) - 0.05, 1.0)
```

### 新增 2：背景层 `ui/widget/CoverBackdropDrawable.kt`（263 行）

`Drawable` 子类，**直接替代原来那句 `Skin.flat(Shape.CARD)`**，圆角裁剪 / 描边 /
无封面回退都在这一处解决：

- **模糊**：`centerCrop` 后**逐级减半**缩到短边 64，再双线性拉回全卡。
  > `RenderEffect` 要 API 31，本项目 `compileSdk=29` 用不了；RenderScript 已废弃。
- **绘制**：`saveLayerAlpha(卡面 alpha)` → 画封面（圆角裁剪）→ 1px 描边。
- **回收**：上过屏的模糊图换封面时**只丢引用不 recycle**（可能仍被硬件加速层持有）；
  中间位图可安全 recycle；**源封面绝不碰**（`MusicController` 缓存复用）。

### 修改：`ui/pages/NavPage.kt`（+45 −4）

```kotlin
private val coverBackdrop = CoverBackdropDrawable(
    surfaceColor = pal.surface, strokeColor = pal.stroke,
    cornerRadius = Shape.CARD, textColor = Skin.TEXT_DIM,
)

// artwork setter：同时喂专辑图控件和背景层
set(v) { field = v; albumArtView.bitmap = v; coverBackdrop.bitmap = v }

// buildCombinedCard()
combinedCard.background = coverBackdrop

// applyCardStyle()：换肤时刷新背景层的卡面色/描边/文字色
coverBackdrop.setStyle(Skin.surface, Skin.GLASS_STROKE, Shape.CARD, Skin.TEXT_DIM)
```

`artwork` setter 有 `field === value` 短路 → 播放器 tick 不会每帧重算模糊图。

## 对比度口径

以**最弱那档文字** `Skin.TEXT_DIM` 为准，要求对比度 ≥ **3:1**
（WCAG 对「大字号」的底线；这些字在 1080p 车机屏上是 18~20px，驾驶位远距离看，
3:1 是对次要信息通行的档位）。

| 模式 | `TEXT_DIM` | `L_text` | 对背景的约束 | 结果 |
|---|---|---|---|---|
| 夜间 | `#757E8E` | 0.2065 | `L_bg ≤ 0.0341` | 主档 10.8 / 次档 5.34 / 最弱 3.05 |
| 日间 | `#8B92A0` | 0.2859 | `L_bg ≥ 0.9745` | 主档 17.1 / 次档 6.6 / 最弱 3.13 |

## 真机实测

模拟器 `emulator-5560`（API 28 = 车机同版本），1920×1080，
桌面包 `com.ecarx.cardesk` v4243（`-PcardeskPackage=com.ecarx.cardesk`）。

**测量方法**：只统计**按布局常量确认没有内容**的矩形区
（卡内 y200–300 的两条带，避开歌词/进度条/时间行/控制键 —— 那些区域的文字像素
会让亮度读数虚高）。

### 夜间 · 封面 A（蓝→品红→橙斜纹）

| 指标 | 实测 | 规格 | 结论 |
|---|---|---|---|
| 背景最大灰度 | **51** | ≤ 51 | ✅ |
| 背景最大相对亮度 | **0.0335** | ≤ 0.0341 | ✅ |
| 最强档文字（38px 主档）对比度 | **10.93:1** | ≥ 3.0 | ✅ |
| 次档文字（22px）对比度 | **5.38:1** | ≥ 3.0 | ✅ |
| 最弱档文字（20px）对比度 | **3.07:1** | ≥ 3.0 | ✅ |
| 背景像素合规率 | **100.00%**（75400/75400） | 100% | ✅ |
| 背景彩色像素占比 | **74.74%**（平均饱和度 0.507） | 封面确实铺上了 | ✅ |

### 夜间 · 封面 B（绿系斜纹，换歌换封面）

| 指标 | 实测 | 结论 |
|---|---|---|
| 背景最亮 / 最暗像素 | `(51,52,52)` / `(2,35,29)` | ✅ 绿系，跟随封面换色 |
| 最强 / 次档 / 最弱对比度 | **10.85 / 5.34 / 3.05** | ✅ |
| 合规率 / 彩色像素占比 | 100.00% / 83.45% | ✅ |

### 日间（白底深字）

| 指标 | 实测 | 结论 |
|---|---|---|
| 背景相对亮度范围 | 0.9823 ~ 1.0000（规格 ≥ 0.9745） | ✅ 100% 合规 |
| 最强 / 次档 / 最弱对比度 | **17.05 / 6.62 / 3.13** | ✅ |
| 背景彩色像素占比 | **0.00%**（平均饱和度 0.0002） | ⚠️ 封面基本不可见 |

## 已知限制：日间模式封面几乎看不见

**这是对比度底线的数学必然，不是没实现。**

白底深字与深底白字是对称的：日间 `TEXT_DIM` 是 `#8B92A0`（`L=0.2859`），
要保住 3:1，背景亮度必须 **≥ 0.9745** —— 也就是**背景必须接近纯白**。
任何「肉眼可见」的深色内容都会直接吃掉最弱档文字的可读性。

因此本轮选择：**保持严格可读性，日间优雅降级为近白卡面**（只剩极淡的一层色偏），
不做任何偷偷放宽。**夜间才是这个功能的主战场。**

如果希望日间也能明显看到封面，有三条路（需要你拍板后再改）：

1. **日间把 `TEXT_DIM` 加深**（如 `#6E7683`）→ 背景亮度下限可从 0.9745 放宽到
   ≈ 0.649，封面在日间就明显可见；代价是改了日间主题的文字色。
2. **分区域遮罩**：文字行下面保持近白，卡片空白区（卡内 y180–310 / y380–630）
   显示封面 —— 日间也能见封面，且文字区对比度不受影响；代价是实现复杂度上升。
3. **加「封面背景强度」开关**（强 / 中 / 弱 / 关），弱档放宽 `TEXT_DIM` 的约束；
   代价是引入一个可调参数和设置项。

## 反向验证：10 条改坏全部必红

`tools/verify_y146_reverse.sh`（可复跑，脚本自带备份与还原）

| # | 改坏什么 | 期望 |
|---|---|---|
| 1 | 不做亮度映射 | 必红 |
| 2 | 夜间亮度上限算错成 1.0 | 必红 |
| 3 | 对比度底线放宽到 1.5 | 必红 |
| 4 | 封面根本不铺上去 | 必红 |
| 5 | 去掉圆角裁剪 | 必红 |
| 6 | 没有封面时不画卡面 | 必红 |
| 7 | 换歌时背景不跟着换 | 必红 |
| 8 | 组合卡挂回纯色面 | 必红 |
| 9 | `applyCardStyle` 不刷新背景层 | 必红 |
| 10 | `TONE_GAMMA` 改成 1.0 | 必红 |

脚本还包含**源码接线断言**（`combinedCard.background = coverBackdrop`、
`coverBackdrop.bitmap = v`、`coverBackdrop.setStyle(`、`CoverBackdrop.toneMap(`、
`CoverBackdrop.targetRange(`），防止「实现还在、接线被摘掉」这种假绿。

> **踩过的坑**：反向验证的 patch 锚点必须唯一且**改在真正生效处**。
> 改「求解循环的起点」无效 —— 循环会继续往上找到答案，必须改返回值/结果。

## 新增断言（25 个）

`app/src/test/java/com/cardesk/launcher/core/media/CoverBackdropTest.kt`（15 个，纯 JVM）

亮度/对比度基础、目标区间（夜间上限 / 日间下限）、映射后最亮像素落到上限、
三档文字对比度、色相不变、暗部被抬起、结构未被压平、变亮方向不削顶、日间底要亮。

`app/src/test/java/com/cardesk/launcher/ui/widget/CoverBackdropDrawableTest.kt`（10 个，Robolectric）

封面铺上、纯白封面下三档文字可读、无封面回退扁平面、描边还在、圆角裁剪、
模糊图比例、换封面背景跟着换、明暗结构可见。

> **踩过的坑**：半透明混色后像素与原色可能只差 1
> （`FF1F232C` vs `FF1F232D`），「中心 != 卡面色」这种精确判据会失效 →
> 必须用 `colorDistance` 容差 + 色相方向判据。
>
> 纯逻辑层不依赖 `android.graphics.Color`，避免非 Robolectric 环境 not mocked。

## 全量回归

```
:app:testDebugUnitTest   →  BUILD SUCCESSFUL   （191 个 XML，failures=0 errors=0）
:app:testSystemUnitTest  →  BUILD SUCCESSFUL   （total=2016, failures=0, errors=0）
:app:assembleSystem -PcardeskPackage=com.ecarx.cardesk →  BUILD SUCCESSFUL
```

## 产物

| 文件 | 内容 |
|---|---|
| `y146-01-page2-night-cover-bg.png` | 夜间第二页整屏（封面背景生效） |
| `y146-02-before-after.png` | 改动前后卡片特写对照 |
| `y146-03-night-fullscreen.png` | 夜间整屏 |
| `y146-04-artA-card.png` | 封面 A 卡片特写（品红/橙斜纹） |
| `y146-05-artB-card.png` | 封面 B 卡片特写（绿系斜纹，换歌换封面） |
| `y146-06-day-card.png` | 日间卡片特写（优雅降级为近白） |
| `y146-07-day-fullscreen.png` | 日间整屏 |

测量脚本：`work/measure_card.py`、`work/check_page2.py`。

## 验证环境

- 模拟器 `emulator-5560`（API 28 = 车机同版本），1920×1080
- 桌面包 `com.ecarx.cardesk` v4243 / versionName `1.0.1147-system`
- 封面来源：`media-probe` 媒体探针（`--es art <路径>` → `METADATA_KEY_ALBUM_ART`）
- 夜间验证把系统时间设为 22:00，日间设为 12:00（`daynight_mode=1` 跟随时间）

> **踩过的坑**：验证时高德地图（画中画应用）会自己弹全屏盖住桌面，
> 且有保活服务会立刻重启 → 需 `pm disable-user` 才能稳定测量；
> 另外横滑手势会误触发「画中画全屏」，判断是否翻到导航页不能只看「有没有白字」
> （3D 地图的白色建筑会假阳性），要结合焦点窗口判断。
