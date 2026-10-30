# 第八十二轮 · 状态栏重排 + 翻页圆点搬家 + 单指滑动循环

日期：2025-10-30
被测包：`com.ecarx.qq.tool`（平台签名替换包，versionCode 4354 / 1.0.1258-system）
设备：`emulator-5554`（AVD `cardesk-aosp`，android-10 / API 29 / 1920×1080@160dpi，`mAppBounds` 1920×1024）
基线包：`dist/CarDesk-ecarx-cardesk-4146.apk`（上一轮发布版，布局未改）

本轮三条用户诉求：

1. 状态栏的「地区（位置）+ 天气」挪到**和右侧图标一起**；
2. Dock 栏上方的**翻页指示圆点**移到**状态栏的中间**；
3. **单指滑动切画桌面实现循环切换**。

---

## 1. 结论

| # | 诉求 | 结果 |
|---|---|---|
| 1 | 位置 + 天气挪到右侧图标行 | ✅ 状态栏右段现在是「位置胶囊 · 天气胶囊 · 蓝牙 · WiFi · 信号 · 燃油 · 时钟」 |
| 2 | 翻页圆点移到状态栏正中 | ✅ 玻璃胶囊落在状态栏行正中（x 916~1004，中心 **960** = 屏幕正中） |
| 3 | 单指滑动循环切换 | ✅ 7 连滑严格 `0→1→0→1…`；**修掉了「某些区域滑动被吃掉」的根因** |

外加修掉一个**既有 bug**：驾驶模式胶囊**从来就点不开**（命中判定用的矩形被天气胶囊覆盖），
点天气胶囊反而会误开空调面板。

单测：全量 **1163** 个用例全绿（上一轮 1157，本轮 +6）。
无崩溃：pid 6151 全程不变，无 `Fatal signal` / `FATAL EXCEPTION`。

---

## 2. 第 3 条的根因（**不是取模问题，是事件分发问题**）

用户说「单指滑动切桌面要循环切换」。先查代码：**取模早就有了** ——
`PageGesture.wrapIndex` 是 `((from + step) % pageCount + pageCount) % pageCount`，
`LauncherView.nextPage/prevPage` 都走它。所以「循环」在**逻辑上一直是对的**。

### 2.1 现象（模拟器实测，基线包 4146）

在 `emulator-5554` 上用 `adb shell input swipe` 逐个起点试：

| 页面 | 起点 | 结果 |
|---|---|---|
| 导航页 | x=800（地图卡内） | ✅ 触发 |
| 导航页 | x=1400（音乐卡内） | ❌ **无任何日志** |
| 导航页 | x=1700 | ❌ **无任何日志** |
| 场景页 | x=1400 | ✅ 触发 |

「某些起点整片失灵」——用户很容易把它理解成「翻到头了，不能循环」。

### 2.2 定位

在 `LauncherView.onInterceptTouchEvent` 里加临时日志后，真相浮出来：

```
手势调试 DOWN rawX=1400.0 rawY=300.0 dx=1400.0 dy=300.0
手势调试 DOWN rawX=1700.0 rawY=300.0 dx=1700.0 dy=300.0     ← 只有 DOWN，没有 MOVE
```

**`onInterceptTouchEvent` 只收到了 ACTION_DOWN，一个 MOVE 都没有。**

原因是 Android `ViewGroup.dispatchTouchEvent` 的既定行为：

```java
if (actionMasked == ACTION_DOWN || mFirstTouchTarget != null) {
    ... intercepted = onInterceptTouchEvent(ev);
}
```

- **有子视图消费了 ACTION_DOWN** → `mFirstTouchTarget != null` → 后续 MOVE 继续回调 `onInterceptTouchEvent`；
- **没有任何子视图消费 ACTION_DOWN** → `mFirstTouchTarget == null` → 后续 MOVE **不再回调** `onInterceptTouchEvent`。

导航页右侧的音乐卡是**纯自绘的空白区**（没有子视图消费按下），
场景页 `ScenePage.onTouchEvent` 永远返回 true（消费按下）——
所以「场景页能滑、音乐卡区不能滑」。

而旧代码把翻页判定挂在 **`singleIntercepted`** 上，
它**只在 `onInterceptTouchEvent` 里才可能被置位** → 没人消费按下的那条路径上它永远是 false →
`singleFingerStep` 根本不会被调用 → 永远不翻页。

### 2.3 改法（`LauncherView.onTouchEvent`）

把**最终判定**从 `onInterceptTouchEvent` 挪到 `onTouchEvent`：

- `onInterceptTouchEvent` 只负责「**抢事件**」（横向位移超阈值就把事件夺回来，
  保证底部卡片拖拽 / 画中画点位 / Dock 媒体卡滑动照常响应）；
- `onTouchEvent` 负责「**判定翻页**」——它从 DOWN 一路收到 UP，
  两条路径（子视图消费 / 没人消费）都能拿到完整位移。

两条路径**不会重复翻页**：判定点只有 `onTouchEvent` 一个。

顺带把「多指一律作废单指判定」写进 `ACTION_POINTER_DOWN`
（原来 2 指横滑也会被当成单指横滑翻页）。

改完后的实测（同一台模拟器、同一串 `adb input swipe`）：

```
手势：单指横滑翻页 dx=-598.0  抢事件=true    切页 from=0 to=1
手势：单指横滑翻页 dx=-590.0  抢事件=false   切页 from=1 to=0   ← 修复前这一条是失效的
手势：单指横滑翻页 dx=-608.0  抢事件=true    切页 from=0 to=1
手势：单指横滑翻页 dx=-608.0  抢事件=false   切页 from=1 to=0   ← 修复前这一条是失效的
手势：单指横滑翻页 dx=597.7   抢事件=true    切页 from=0 to=1
手势：单指横滑翻页 dx=-608.0  抢事件=false   切页 from=1 to=0
手势：单指横滑翻页 dx=-608.1  抢事件=true    切页 from=0 to=1
```

**7 连滑严格 `0→1→0→1→0→1→0→1`**，`抢事件=false` 的正是修复前失效的那条路径。

---

## 3. 布局改动

### 3.1 状态栏：位置 + 天气挪到右侧

- `ShellStatusBar.drawLocationWeather` 从「水平居中」改成「**贴着右边界往左画**」，
  返回占用宽度给右侧那一行继续排（顺序：时钟 → 燃油 → 信号 → WiFi → 热点 → 蓝牙 → 天气胶囊 → 位置胶囊）。
- 段内顺序也跟着右→左走：**天气胶囊在右、位置胶囊在左**。
- 状态栏行高 / 顶边距 / 边距**一个字没动**（`ROW_TOP=17`、`ROW_H=43`、`EDGE=29`）。

像素实测（`r82-new-layout-scene.png`，1920×1080）：

```
x  29- 68   头像（青色方块）
x  81- 270  驾驶模式胶囊
x 916-1004  翻页圆点玻璃条（中心 960 = 屏幕正中）
x1323-1432  位置胶囊（北京）
x1440-1545  天气胶囊（11°）
x1563-1585  蓝牙
x1603-1625  热点
x1643-1665  WiFi
x1683-1705  信号
x1723-1744  燃油图标
x1758-1788  燃油百分比
x1806-1891  时钟
```

### 3.2 翻页圆点：Dock 上方 → 状态栏正中

- `PageDotsView` 的几何**直接引用** `ShellStatusBar.ROW_TOP` / `ROW_H`
  （不抄一份数值 —— 状态栏行高以后再改，圆点自动跟着走）。
- 玻璃条上下 padding 由 8 收到 **3**（原来 44 高的条塞不进 43 高的状态栏行）。
- 选中点 30×10 / 未选中 10×10 / 350ms `OvershootInterpolator(1.4f)` 动画 / 点击切页，**全部保留**。
- 状态栏被隐藏（「隐藏君君状态栏」开关打开且在导航页）时，圆点**跟着一起隐藏**
  —— 否则它会孤零零浮在屏幕顶部。

对比图：

| 旧（`r82-statusbar-old.png`） | 新（`r82-statusbar-new.png`） |
|---|---|
| 位置/天气在**正中**，正中没有圆点 | 正中是**翻页圆点**，位置/天气在**右侧** |
| 圆点在 Dock 上方（`r82-dots-old-dock.png`） | 圆点在状态栏行内（`r82-dots-zoom.png`） |

### 3.3 顺带修掉的既有 bug：驾驶模式胶囊点不开

`ShellStatusBar` 里**头像、驾驶模式胶囊、位置胶囊、天气胶囊原本共用同一个 `RectF`**，
而绘制顺序是「驾驶模式胶囊 → 位置胶囊 → 天气胶囊」，所以那块矩形最后停在**天气胶囊**上：

- 点驾驶模式胶囊 → `contains` 判的是天气胶囊的位置 → **点不开**（一直被误判为「没点中」）；
- 点天气胶囊 → **误开**空调面板。

第八十二轮拆成 `avatarRect` / `assistRect` / `locRect` / `wxRect` 四个独立矩形，
命中判定与绘制一一对应。实测点驾驶模式胶囊 → 日志 `状态栏驾驶模式胶囊点击 assist=驾驶模式 · 待命` ✓，
新增单测 `点驾驶模式胶囊会触发回调` / `点右侧天气胶囊不会误触发驾驶模式回调` 锁住这个行为。

---

## 4. 单测

新增 `app/src/test/java/com/cardesk/launcher/ui/PageDotsPositionTest.kt`（4 个）：

| 用例 | 断言 |
|---|---|
| `圆点画在状态栏那一行的正中` | 状态栏行内正中 ±60 有内容像素 |
| `Dock 上方的老位置不再有圆点` | 老位置（y 928~960）内容像素 = 0 |
| `挪到状态栏后点击圆点仍然能切页` | 点未选中点 → `onDot(1)` |
| `点击空白处不切页` | 点状态栏空白 → 不触发 `onDot` |

改写 `StatusBarLocationWeatherTest`（9 个）：原来两条断言依赖「中置」位置，
改成「右侧有、正中空」；另加两条点击命中用例。

**全量 1163 个用例全绿**（上一轮 1157）。

> 说明：单指滑动这条修复**没法用 JVM 单测覆盖** ——
> 根因是 Android 的事件分发机制（`mFirstTouchTarget` 是否为空），
> 只有真机/模拟器上跑真实触摸才能验证，所以本轮以模拟器实测为准。

---

## 5. 稳定性

- 进程 pid 全程 **6151 → 6151**（没崩过）；
- logcat 无 `Fatal signal` / `FATAL EXCEPTION`；
- 圆点点击切页实测正常（`页面圆点点击切页 target=1 current=0`）。

---

## 6. 未能覆盖

| 项 | 原因 |
|---|---|
| 三指横滑 | `adb shell input` 不支持多指注入，只能靠代码审查（`ACTION_POINTER_DOWN` 的多指作废逻辑已核对） |
| 右缘竖滑 | 同上（未改逻辑） |
| 真车触控手感 | 模拟器是鼠标注入，没有真车的触摸采样率与惯性 |
