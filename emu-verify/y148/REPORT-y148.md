# 第148轮：歌曲图片作第二页音乐卡背景 —— 做成可开关（点封面图切换）

> 用户需求（第148轮）：
> 「歌曲图片作为第二页音乐卡片背景做成可开关的形式吧，通过点击音乐卡片的歌曲图片来切换」
>
> 上游：第146轮上线了「封面作背景」（真机用 QQ 音乐车机版 3.14.0.4 arm64 验证过），
> 第147轮做了 arm64 版的真机验证。本轮把它**变成可开关**。

---

## 一、交互设计

| 入口 | 位置 | 说明 |
|---|---|---|
| **主入口** | 音乐卡左上角那张 **96×96 封面图** | 点一下就切 |
| 辅助入口 | 设置页 →「桌面卡片」→ 新增「音乐卡 / 封面作背景」 | 和点封面图操作**同一份状态** |

- 状态落盘键：`nav_cover_backdrop_enabled`（`Prefs`），**默认 `true`**（保持第146轮上线的现状，升级不改变用户看到的东西）
- 两个入口操作的是同一个键，谁先改谁生效，不会有两份状态打架

### 关闭时卡片长什么样

音乐卡的封面图是**双重身份**的:既是那张 96×96 的小图,**又是整张卡的背景**。
关掉背景之后:

- **封面图本身不动** —— 还是原来那张封面,不加任何标记
  (用户第148轮后半段明确要求:「不要加对角斜杠和暗罩」);
- **整张卡退回纯色卡面** —— 封面纹理消失。

夜间模式下这个差别一眼就能看出来(有纹理 ↔ 纯色)。
日间模式下背景本来就接近纯白(第146轮定的「严格可读性优先」),
两个状态看起来差不多 —— 这时靠**点击时的 Toast** 和**设置页的开关**确认状态。

---

## 二、改动清单

| 文件 | 改动 |
|---|---|
| `core/Prefs.kt` | 新增 `navCoverBackdropEnabled`（键 `nav_cover_backdrop_enabled`，默认 true） |
| `ui/widget/CoverBackdropDrawable.kt` | 新增 `coverEnabled` 开关；`rebuild()` 里认它（关掉时**不生成**模糊图） |
| `ui/pages/NavPage.kt` | 新增 `coverBackdropEnabled` 属性 + `syncCoverBackdropState()` + `toggleCoverBackdrop()`；封面图挂点击监听；新增只读 `coverBackdropOn` |
| `ui/LauncherView.kt` | 接 `onCoverBackdropToggled` → 弹 Toast；每轮刷新把偏好灌进 `navPage` |
| `ui/SettingsOverlay.kt` | 「桌面卡片」里新增「音乐卡 → 封面作背景」开关 |
| `app/src/test/.../NavCoverBackdropToggleTest.kt` | **新增**（9 条） |
| `app/src/test/.../CoverBackdropDrawableTest.kt` | 新增 5 条 |
| `app/src/test/.../AlbumArtViewTest.kt` | 新增 2 条（不画标记 / 圆角裁剪） |
| `tools/verify_y148_reverse.sh` | **新增**（11 条反向验证） |

> `ui/widget/AlbumArtView.kt` 在这一轮**先加后撤**了「背景已关」标记
> （暗罩 + 对角斜杠），最后按用户要求**整段拿掉** —— 现在它和第146轮之前一样，
> 只负责把封面画成圆角小图。

### 关键实现细节

**① 关掉时不生成模糊图**

`rebuild()` 里加一行：

```kotlin
blurred = null
if (!coverEnabled) return          // ← 新增
```

模糊图要走十几次 `drawBitmap`（逐级减半缩到 64px 再拉回来）。关着的时候还留着它，
等于白占一张位图、白算一遍。源封面（`bitmap`）**不动** —— 再打开时能立刻重算，
不用等换歌。

**② 属性初始化不走 setter（Kotlin 的坑）**

```kotlin
var coverBackdropEnabled: Boolean = prefs.navCoverBackdropEnabled
```

这一行**只写 field，不调 setter** —— 所以 `coverBackdrop.coverEnabled` 还停在构造时的
默认值 `true`。用户上次把背景关掉了，这次冷启动 `prefs` 里是 `false`，
但背景层说「开」—— **背景照样会显示，开关等于没记住**。

补法是 `build()` 末尾显式同步一次：

```kotlin
private fun syncCoverBackdropState() {
    coverBackdrop.coverEnabled = coverBackdropEnabled
    if (::albumArtView.isInitialized) albumArtView.backdropDisabled = !coverBackdropEnabled
}
```

**③ 为什么 `prefs` 挪到了属性区最前面**

`coverBackdropEnabled` 的初值要读 `prefs`，而 Kotlin 属性初始化按声明顺序走。
原来 `private val prefs = Prefs(context)` 在类的中部，声明在它后面的属性读不到 ——
所以把 `prefs` 提到了类开头。

**④ 状态只有一个源**

`LauncherView` 每轮刷新都做一句 `navPage.coverBackdropEnabled = prefs.navCoverBackdropEnabled`。
设置页改了偏好 → 下一轮自动生效；点封面图改了偏好 → 下一轮读到的是同一个值。
`setter` 里加了 `if (field == v) return`，所以这个每帧调用零开销。

---

## 三、真机验证（emulator-5560，QQ 音乐车机版 3.14.0.4 arm64）

### 3.1 点击生效

```
$ adb shell input tap 1421 184        # 封面图中心（卡内 x=24..120 / y=66..162 → 绝对 1373..1469 / 139..235）

I/CarDesk/NavUI: [EVT] 导航页音乐卡封面背景切换 enabled=false title=ARIA (咏叹调)

# 偏好同步落盘
<string name="pip_app">com.autonavi.amapautp.yh</string>
<boolean name="nav_cover_backdrop_enabled" value="false" />
```

### 3.2 背景的客观量化

判据不是「彩色像素占比」（关掉的深蓝灰卡面饱和度也 >0.05，这个指标不适用），
而是**背景区的相对亮度 min 是否等于 max**：

| 状态 | 背景最大灰度 | 相对亮度 max | 相对亮度 min | 结论 |
|---|---|---|---|---|
| 开 | 51 / 52 | 0.0341 | **0.0013** | 有明暗结构 = 封面纹理在 |
| 关 | 34 | 0.0168 | **0.0168** | min = max = **纯色卡面，零纹理** |
| 再点一次 | 51 / 52 | 0.0341 | **0.0013** | 与第 1 行数值**完全一致** |
| 冷启动后 | 34 | 0.0168 | **0.0168** | 关掉的状态**记住了** |

### 3.3 封面图本身有没有被改动（用户要求的核心）

把开关前后两张截图的**封面图圆角内部**（62×62 安全区）做逐像素 diff：

```
差异像素数 = 9 / 3844 (0.2%)
最大通道差 = 1
非零差异中位数 = 1
```

**9 个像素、最大差 1 个灰阶** —— 这是抗锯齿边缘的舍入噪声
（背景层重绘时圆角边缘和下面那层的混合舍入），不是标记。
**封面图实质上一个像素都没变** —— 没有暗罩，没有斜杠 ✓

对照：`开` vs `恢复` 两张图 → 差异 bbox = `None`（完全一致）。

### 3.4 冷启动记住状态（最关键的一条）

```
# 关掉 → 重启桌面
force-stop com.ecarx.cardesk
am start -n com.ecarx.cardesk/com.cardesk.launcher.MainActivity

# 冷启动后实测
相对亮度 max=0.0168 min=0.0168    ← 仍然是纯色，没自己回来
```

**这条是 `syncCoverBackdropState()` 存在的全部理由** ——
没有它，冷启动后背景会自己回来（反向验证第 4 项就是专门打这个的）。

### 3.5 Toast 反馈

桌面不是普通 Activity，点完必须立刻给一句话。实测点击后底部弹出「封面背景已关闭」。

---

## 四、测试

### 4.1 新增用例（16 条）

**`NavCoverBackdropToggleTest`（9 条，端到端）** —— 走真实的
`MainActivity` → `LauncherView` → `NavPage` 链路：

| 用例 | 锁住什么 |
|---|---|
| 封面背景开关默认是开的 | 默认值不破坏第146轮的现状 |
| 封面背景开关能存能读 | 偏好读写 |
| 封面图上挂着的点击监听真的能切换开关 | 入口真的挂上了（`performClick()` 真点） |
| 点封面图会把状态落盘 | 不落盘等于没做（桌面是杀进程重开的） |
| 连续点两次会回到原来的状态 | 开关不是单向棘轮 |
| 切换会把新状态回调给宿主 | 用户点了要知道状态变了 |
| **冷启动会记住上次关掉的状态** | **查的是背景层真实状态（`coverBackdropOn`），不是那个永远对的属性** |
| 冷启动默认状态是开的 | 默认路径也要同步 |
| 切换后属性和背景层保持一致 | 两边不能一边动一边不动 |

**`CoverBackdropDrawableTest`（新增 5 条）**：
关掉退回纯色 / 关掉不生成模糊图 / 重新打开背景回来 / 关掉描边还在 /
关掉后换封面背景不会偷偷回来。

**`AlbumArtViewTest`（新增 2 条）**：
封面图上不画任何标记（用户要求）/ 封面按圆角裁剪。

### 4.2 反向验证（`tools/verify_y148_reverse.sh`，12 条改坏全部必红）

| # | 改坏什么 | 红了哪条 |
|---|---|---|
| 1 | 封面图不挂点击监听 | 封面图上挂着的点击监听真的能切换开关 |
| 2 | 切换不落盘 | 点封面图会把状态落盘 |
| 3 | 不回调给宿主 | 切换会把新状态回调给宿主 |
| 4 | `build()` 里不同步状态 | 冷启动会记住上次关掉的状态 |
| 5 | 同步时不给背景层灌状态 | 切换后属性和背景层保持一致 + 冷启动会记住上次关掉的状态 |
| 6 | `rebuild()` 不认开关 | 关掉封面开关_不生成模糊图 / _卡面退回纯色 / 关掉开关之后换封面_背景不会偷偷回来 |
| 7 | 开关恒为关 | 有封面时卡面中心带着封面的颜色（+6 条连带红） |
| 8 | `rebuild()` 里判反 | 开着的时候反而不生成模糊图（10 条红） |
| 9 | 封面不按圆角裁剪 | 封面按圆角裁剪_不会画到圆角外面 |
| 10 | 模糊图短边写死 | 模糊图的比例和卡片一致 |
| 11 | **把暗罩偷偷加回封面图** | **封面图上不画任何标记**（+3 条连带红）—— 专门守用户那条要求 |
| 12 | 默认改成关 | 封面背景开关默认是开的 / 冷启动默认状态是开的 |

基线 `RED=0 TOTAL=29` → 12 条改坏逐条必红 → 还原后 `RED=0 TOTAL=29`，
接线检查同样 `RED=0 TOTAL=1`。

---

## 五、踩过的坑（这一轮踩的）

### 5.1 反向验证第一版有两条**假通过**（没红）

第一次跑反向验证，反向 4 和反向 8 都是 `RED=0` —— **改坏了却没红**，说明
反向项本身有问题。查下来是两个不同的原因：

**反向 4（build 里不同步状态）**：我原来的断言查的是 `nav.coverBackdropEnabled`
这个**属性**。但属性的初值本来就读自 `prefs`，**永远是对的**；真正会错的是
**背景层**（`coverBackdrop.coverEnabled`）。

修法：给 `NavPage` 加一个只读属性把背景层的真实状态暴露出来 ——
```kotlin
val coverBackdropOn: Boolean get() = coverBackdrop.coverEnabled
```
然后断言改成查它。**只查属性的用例，在 bug 存在时照样绿**，这条坑值得记。

**反向 8（圆角裁剪）**：我改的是 `drawDisabledMark()` 里的
`canvas.clipPath(path)`。但 **`onDraw()` 里早就裁过一次了**，标记是在那次裁剪之内画的
—— 我改的那次是**冗余**的，去掉它渲染结果**一个像素都不变**。

修法：反向项改成破坏 **`onDraw()` 里真正管用的那次裁剪**。

> 教训：反向项要破坏**真正生效的那一行**。
> 改一处「看起来相关」的代码，如果那处其实是死的，反向项就永远绿 ——
> 比没有反向项更危险，因为它给人「已验证」的错觉。

### 5.2 Robolectric 里 NavPage 没被量过布局

写「渲染前后对比」类断言时踩的：

```
java.lang.IllegalArgumentException: width and height must be > 0
```

端到端测试里 `NavPage` 是通过 `LauncherView` 建起来的，但没人对它
`measure`/`layout`，所以它里面的 `AlbumArtView` 的 `width`/`height` 还是 **0**，
`onDraw` 直接 return。

修法：**渲染类断言放在 `AlbumArtViewTest` 里**（那边自己 `measure`/`layout`），
端到端测试只管接线。两边分工清楚。

### 5.3 断言「图上没画东西」怎么写才不脆弱

要锁住「封面图上不许有暗罩 / 斜杠」，不能断言「和基准图逐像素相同」
（换台设备色值就可能差 1）。

用**纯白封面**当基准：圆角之内必须**一个像素不差地是纯白** ——
任何暗罩（压暗）或斜杠（画白线）都会在某个像素上露出来。

### 5.4 uiautomator dump 拿不到坐标

想用 `contentDescription` 定位封面图，结果：

```
ERROR: could not get idle state.
```

桌面上歌词在滚动、进度条在动，uiautomator 等不到 idle 状态，dump 直接失败。
（上一轮测过 `uiautomator` 在静态页面上是好的，这里是**持续动画**的问题。）

改用**代码常量推算**：`COMBINED_PAD = 24`、`COMBINED_ART_TOP = 66`、封面 96×96、
卡片原点在 (1349, 73) → 封面绝对区域 x 1373–1469 / y 139–235，中心 (1421, 184)。
实测点击命中，日志确认。

### 5.5 「彩色像素占比」这个指标在关闭态失效

`measure_card.py` 里的「背景彩色像素(饱和度>0.05)」本意是判断封面有没有铺上来。
但关掉背景后的卡面是**深蓝灰**（`#1F232D` 附近），饱和度也 > 0.05，
测出来是 **100%** —— 看着比开着时的 99.46% 还高，纯属误导。

换成**相对亮度 min 是否等于 max**：
- 纯色卡面 → min = max（完全均匀）
- 封面纹理 → min < max（有明暗结构）

这个判据和「有没有纹理」直接对应，跨明暗模式都成立。

---

## 六、需求中途变更：去掉封面图上的标记

本轮后半段用户明确要求：**「不要加对角斜杠和暗罩」**。

于是把第一版加在 `AlbumArtView` 上的整套标记（`backdropDisabled` 属性 +
`drawDisabledMark()` + 两个专用画笔）**整段拿掉**，`AlbumArtView` 回到
「只负责把封面画成圆角小图」的状态。

连带要处理的有三处：

1. `NavPage.syncCoverBackdropState()` 里同步标记的那一行删掉；
2. `AlbumArtViewTest` 里 4 条标记用例换成 2 条
   （**封面图上不画任何标记** + **封面按圆角裁剪**）；
3. 反向验证脚本里原来靠「不画标记」红的那几条换成别的破坏点，
   并且**新增一条反向项专门守用户这条要求** ——
   把暗罩偷偷加回封面图，那条必须立刻红。

> 顺带一提：正因为加了这条反向项，以后谁「好心」给封面图加回禁用标记，
> CI 会直接拦下来。

改完之后，状态判断就落在**整张卡**上：夜间一眼能看出（有纹理 ↔ 纯色），
日间靠 Toast 和设置页的开关确认。

---

## 七、回归

```
testDebugUnitTest    XML=192 total=2034 failures=0 errors=0
testSystemUnitTest   XML=192 total=2034 failures=0 errors=0
assembleSystem -PcardeskPackage=com.ecarx.cardesk   BUILD SUCCESSFUL
```

（比第146轮多 16 条用例：2018 → 2034。）

---

## 八、产物

| 文件 | 内容 |
|---|---|
| `y148-01-night-backdrop-on.png` | 夜间 · 背景开（封面纹理铺满整张卡） |
| `y148-02-night-backdrop-off.png` | 夜间 · 背景关（纯色卡面，封面图**原样无标记**） |
| `y148-03-night-backdrop-restored.png` | 夜间 · 再点一次恢复 |
| `y148-04-coldstart-stays-off.png` | 关掉后冷启动，仍然是关的 |
| `y148-05-art-untouched.png` | 封面图圆角内部放大 —— 没有任何标记 |
| `y148-06-card-on.png` | 音乐卡对照 · 背景开 |
| `y148-07-card-off.png` | 音乐卡对照 · 背景关 |
| `../tools/verify_y148_reverse.sh` | 12 条反向验证脚本 |
