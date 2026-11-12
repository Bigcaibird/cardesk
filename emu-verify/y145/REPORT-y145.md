# 第145轮：第二页音乐卡封面「蒙了一层灰纱」—— 占位底的 alpha 漏进了封面画笔

## 用户诉求

> cardesk 第二页的音乐播放卡片的歌曲图片上面有一层折叠，把这一层遮挡去掉

现象：第二页（导航页）音乐卡上，专辑封面像**蒙了一层半透明的灰纱**，底下隐约透出卡片底色，
看着就像「图上盖了一层东西」。

## 根因

`app/src/main/java/com/cardesk/launcher/ui/widget/AlbumArtView.kt`

专辑图控件里，**占位底**与**封面位图**共用同一个 `Paint`：

```kotlin
private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

if (bmp == null) {
    paint.color = placeholderColor   // ← setColor 连 alpha 一起改
    canvas.drawRect(r, paint)
} else {
    canvas.drawBitmap(bmp, src, dst, paint)   // ← 用的还是那个 alpha 被改过的画笔
}
```

`Paint.setColor()` **连 alpha 一起改**。而导航页的占位底色是**半透明**的
（`NavPage.kt:794` → `pal.withAlpha(Skin.TEXT, 0x14)`，alpha 只有 **20/255**）。

于是真机上必然踩到这条顺序：

1. 桌面上电、还没有封面 → 走占位分支 → 画笔 alpha 被改成 **20**；
2. 歌曲起来、封面到位 → 走封面分支 → `drawBitmap(..., paint)` **用的还是 alpha=20 的画笔**
   → 封面只画了 **8% 的不透明度**。

**为什么只有第二页中招**：首页音乐卡（`ScenePage.drawMusicCard`）与 Dock
（`DockBarView`）都是 `new Paint(...)` 现画现用，alpha 恒为 255。
所以同一首歌 —— **Dock 里是亮的、第二页里是暗的**。

## 修法

把占位底与封面**拆成两个画笔**：

- `paint` —— 只画封面位图，并显式 `paint.alpha = 255` 钉死不透明度；
- `placeholderPaint` —— 只画占位底。

占位底色怎么改都碰不到封面的不透明度。

## 证据

### 1. 旧包（v4240）真机实测：第二页封面 246.7 / Dock 151.6

同一张封面，同一张卡，差了近 4 倍 —— 正是「蒙灰纱」。

### 2. 亮度反推 alpha=20

```
卡片底色实测 38.2
按 alpha=20/255 预测：20/255*170 + 235/255*38.2 = 48.5
导航页封面实测：41.6     ← 吻合
```

### 3. 反射探针直接读画笔状态

```
PROBE alpha before any draw     = 255
PROBE alpha after placeholder draw = 20   ← 占位分支把 alpha 带坏了
PROBE placeholderColor alpha      = 20
```

### 4. 修好后真机（v4243）实测：第二页 151.5 / Dock 151.6 —— 对齐了

| | 修复前 | 修复后 | Dock 参照 |
|---|---|---|---|
| 第二页封面亮度 | **246.7**（发白） | **151.5** | 151.6 |

### 5. 反向验证：改回旧行为，断言必红

把 `AlbumArtView.kt` 改回「共用一个画笔」，`AlbumArtViewTest` 立刻失败：

```
占位底之后封面来了_封面必须是满不透明度 FAILED
AssertionError: 封面被画成了半透明（卡片上的亮度只有 48.0，应接近 255）——
占位底的 alpha 漏进了封面画笔
```

（48.0 与上面反推的 48.5 再次吻合。）

## 新增断言

`app/src/test/java/com/cardesk/launcher/ui/widget/AlbumArtViewTest.kt`

| 用例 | 断言 |
|---|---|
| 占位底之后封面来了_封面必须是满不透明度 | 走完「占位 → 封面」后，封面在卡片上仍是满不透明度 |
| 封面一直都在时也是满不透明度 | 直接给封面也是满不透明度 |
| 没有封面时画的是半透明的占位底 | 占位底保留自己的 alpha（0x14），没被改成不透明 |

> 断言的关键：**先铺一张不透明的卡片底色再画专辑图**。
> 半透明封面画在**透明**画布上时，取到的像素 RGB 仍是纯白（只有 alpha 变），
> 亮度断言会误判为「通过」—— 必须像真机那样压在卡片底色上，alpha 漏了才会立刻掉亮度。

## 全量回归

```
:app:testDebugUnitTest  →  BUILD SUCCESSFUL
total=1991  failures=0  errors=0
```

## 产物

| 文件 | 内容 |
|---|---|
| `y145-01-nav-cover-before-after.png` | 封面特写对比（上=修复前灰纱，下=修复后满不透明） |
| `y145-02-navpage-fixed.png` | 修复后第二页整屏 |
| `y145-03-navpage-before.png` | 修复前第二页整屏 |

## 验证环境

- 模拟器 `emulator-5560`（API 28 = 车机同版本），1920×1080
- 桌面包 `com.ecarx.cardesk` v4243（`-PcardeskPackage=com.ecarx.cardesk`）
- 封面来源：`media-probe` 媒体探针（本次给它加了 `--es art <路径>`，
  往会话里塞真实封面位图；走 `METADATA_KEY_ALBUM_ART`）
- 验证完已把模拟器还原（双画中画复原、探针停掉、推上去的图删掉）
