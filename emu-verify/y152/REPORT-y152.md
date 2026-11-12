# 第152轮工作报告

> 项目：CarDesk（Android 车机桌面）· `/Users/roy/Documents/desktopapp/cardesk`
> 设备：emulator-5560（arm64，1920×1080，density 160）
> 涉及文件：`app/src/main/java/com/cardesk/launcher/ui/DrawerPage.kt`

---

## 一、本轮需求

> **「优化一下应用抽屉的UI，现在太混乱了，三个点离图标太远，
> 把三个点的菜单改成长按图标弹出，去掉长按排序的功能，以及现在的三个点」**

拆成四件可验证的事：

| # | 需求 | 状态 | 关键证据 |
|---|------|------|----------|
| ① | 去掉每格右上角的「⋮」 | ✅ | 真机截图：网格里一个「⋮」都不剩；单测从视图树断言全页无「⋮」文本 |
| ② | 三个点的菜单改成长按图标弹出 | ✅ | 真机：长按图标 → 弹出「重命名 / 应用信息 / 禁用应用 / 卸载 / 取消」 |
| ③ | 去掉长按排序（拖放） | ✅ | 源码里拖放整套（`setOnDragListener` / `startDragAndDrop` / 落点换算）全部删除；单测断言长按后 `appOrder` 一个字节都不变 |
| ④ | 优化 UI（太混乱） | ✅ | 图标在格子里居中（原来贴左沿）、应用名列 76 → 160 不再被截成「Amap A...」、底部提示文案精简 |

---

## 二、为什么之前「太混乱」

改前截图（`00_before.png`）把三个病因摆得很清楚：

1. **「⋮」离自己的图标 242px，却紧挨着下一个应用。**
   格子宽 300，图标贴在**左沿**（x = 84…160），「⋮」贴在**右沿**（x = 330…382），
   而下一列的图标正好从 x = 384 开始 ——
   视觉上「⋮」属于**右边那个应用**，不是左边这个。
   于是整屏读起来是「图标 ⋮ 图标 ⋮」，谁也分不清那个点是管谁的。

2. **一半应用名被截成省略号。**
   图标 + 名称那一列只有 **76 宽**（跟图标一样宽），只塞得下 4 个字：
   「Amap A...」「Android...」「Calcula...」「CarDes...」「Messag...」「WebVie...」。

3. **贴左沿的图标 + 空 224px 的右侧** —— 整排重心偏左。

这三件事叠在一起，就是用户说的「太混乱」。

---

## 三、实现

### 3.1 删掉「⋮」，菜单改成长按图标

```kotlin
// 菜单入口从「右上角 ⋮ 按钮」挪到「图标列」上
setOnClickListener {
    if (enabled) openApp(info, display)
    else { showItemMenu(info, display); toast("应用已禁用，可在菜单里启用") }
}
setOnLongClickListener {
    showItemMenu(info, display)
    true
}
```

- 长按监听挂在 **body（图标 + 名称整列）** 而不是只挂 ImageView：
  长按图标必然触发（图标在 body 内），长按名称也不会「没反应」。
- 菜单内容一字未改：重命名 / 应用信息 / 禁用·启用 / 卸载 / 取消。

### 3.2 去掉长按拖放排序

整套拖放代码一并删除（不是只把入口让给菜单，留着会打架）：

| 删掉的东西 | 作用 |
|------------|------|
| `grid.setOnDragListener { ... }` | 接收拖放、算落点 |
| `handleDrop()` | 落点换算 + 重写顺序 |
| `cellIndexAt()` | 坐标 → 格子下标 |
| `startDragAndDrop(...)` + `import android.content.ClipData` | 拖影 |
| `import android.content.DragEvent` | 拖放事件类型 |

**`prefs.appOrder` 改成只读不写**：老用户之前排过的顺序不该因为功能下线就丢，
所以读取保留（排过序的按保存顺序在前），写入路径彻底删掉。

### 3.3 布局不再挤

| 项 | 改前 | 改后 | 原因 |
|----|------|------|------|
| 图标列 gravity | `TOP or START`（贴左沿） | `TOP or CENTER_HORIZONTAL` | 贴左沿是**给右上角 ⋮ 让位**的（第五十一轮设计板 s03）；⋮ 没了之后那就是「整排挤左边、右边空 224px」 |
| 名称列宽 `LABEL_W` | 76 | **160** | 76 只塞得下 4 个字，一半应用名被截断；160 能放下「Android 设置」「Calculator」「高德地图」 |
| 图标边长 `ICON` | 76 | 76（不动） | 图标本身没问题，问题在它旁边的「⋯」和名字 |
| 底部提示 | 「点按图标启动，长按拖动排序；格子右上角 ⋮ 可重命名 / 应用信息 / 禁用·启用 / 卸载」 | 「点按图标启动，长按图标打开菜单」 | 与第150轮「删掉啰嗦说明」同一思路 |

图标列居中后左右各留 70，每列图标的中心间距正好等于格宽 300。

### 3.4 顺带修掉的一处矛盾文案

禁用确认弹窗里原本写着「随时可以在它的 **⋮ 菜单**里重新启用」——
⋮ 删了之后这句会把用户引向一个不存在的按钮，已改成「随时可以**长按它的图标**重新启用」。

---

## 四、真机发现的真 bug：抽屉浮层残留（本轮必修）

### 4.1 现象

第一版真机验证时发现两件不对的事：

1. **点按图标一点反应都没有**（logcat 里连「抽屉选中应用」都没有）；
2. 重开抽屉后，`uiautomator dump` 抓到的文本树里**还留着「卸载」「取消」**。

### 4.2 根因

抽屉页是 `LauncherView` **长期持有的同一个实例**：

```kotlin
private lateinit var drawerPage: DrawerPage   // init 时建一次，之后一直复用

// 打开
content.addView(drawerPage, index, ...)
// 关闭
content.removeView(drawerPage)                  // ← 只是把它从窗口摘掉
```

`removeView` **不会**清掉实例内部挂着的浮层（`itemMenu` / `renameDialog` / `confirmDialog`），
它们是 `DrawerPage` 自己的子 view，跟着实例一起活着。
下一次 `showDrawer()` 把同一个实例再 `addView` 回去，浮层就又盖上来了。

菜单的遮罩是 `MATCH_PARENT` + `isClickable` —— **它把整屏的点击都吃掉**，
所以菜单一旦残留，点图标就一点反应都没有（第一次点击只是把菜单关掉）。

这个毛病是**既有的**（以前那个「⋮」菜单同样会残留），本轮把长按变成菜单入口后更容易撞上；
而且它直接压掉了本轮的核心交互「点按图标启动」，所以必须收尾。

### 4.3 对照实验：旧包 vs 新包（同一台机器、同一条路径）

把**第151轮的旧包 4245** 装到 `emulator-5556`，走一模一样的路径：
**点 ⋮ 开菜单 → 点 Dock 关抽屉 → 重开抽屉**。

| | 旧包 4245 | 新包 4246 |
|---|---|---|
| 关抽屉后重开 | **菜单原封不动盖在上面**（`08_before_old_menu_open.png` / `09_before_old_residue.png`） | **干干净净**（`04_reopen_no_residue.png` / `06_reopen_after_dock_close.png`） |
| 菜单打开时按返回键 | 整个抽屉被关掉 | **只关菜单**，抽屉还在（`03_back_closes_menu.png`） |
| 点按图标 | 被残留菜单的遮罩吃掉，**无反应** | **成功启动计算器**（`mCurrentFocus=com.android.calculator2`） |

### 4.4 修法

| 位置 | 改法 | 为什么 |
|------|------|--------|
| `DrawerPage.clearOverlays()` | 新方法：把三个浮层全清掉 | 抽屉一关就清，不给它们跟着实例活到下一次 |
| `LauncherView.closeDrawer()` | 开头同步调 `clearOverlays()` | 放在动画回调**前面**：浮层不该跟着抽屉在屏幕上多待 240ms；也更好测 |
| `LauncherView.showDrawer()` | 开头再清一次 | 双保险，保证打开时一定是干净的 |
| `DrawerPage.closeTopOverlay()` | 新方法：关掉最上面那层，返回是否关掉了 | 返回键的优先级要用它 |
| `LauncherView.onBack()` | 抽屉开着时**先** `closeTopOverlay()`，没有浮层才关抽屉 | 「长按打开菜单 → 按返回」应该只退出菜单，不该把抽屉一起关掉 |

`closeTopOverlay()` 的关停顺序按「谁在上面谁先关」：重命名 → 禁用确认 → 应用菜单
（重命名 / 禁用确认都是在菜单之上弹出来的）。

### 4.5 踩过的坑：`uiautomator dump` 会报**假阳性**

新包上重开抽屉后，截图明明是干净的（`04_reopen_no_residue.png`），
`uiautomator dump` 却还报「重命名 / 应用信息 / 卸载 / 取消」。

原因是 **uiautomator 抓的是无障碍节点树**，
已经被 `removeView` 掉的 view，它的 accessibility 节点**不会立刻从树上摘掉** ——
dump 抓到的是**陈旧节点**。

所以判断「有没有残留」必须看**截图**（真实渲染），不能只看 dump。
这一条一开始把我带偏了，以为新包也没修好，实际是截图证明已经修好了。

> 对照组的价值也在这里：旧包用**同一台设备、同一套脚本**跑出来的**截图**确实有菜单，
> 那是真残留，不是 dump 的陈旧节点。两件事要分清。

---

## 七、真机验证（emulator-5560，1920×1080）

| # | 验证项 | 结果 | 证据 |
|---|--------|------|------|
| ① | 抽屉里没有「⋮」 | ✅ | `01_drawer.png`；`uiautomator` 文本树里没有任何「⋮」 |
| ② | 长按图标弹出应用菜单 | ✅ | `02_longpress_menu.png`：重命名 / 应用信息 / 禁用应用 / 卸载 / 取消 |
| ③ | 菜单打开时按返回键 → 只关菜单 | ✅ | `03_back_closes_menu.png`（画面仍是抽屉，不是桌面）；logcat 只有「返回键」，**没有**「关闭应用抽屉」 |
| ④ | 关抽屉后重开 → 无菜单残留 | ✅ | `04_reopen_no_residue.png`、`06_reopen_after_dock_close.png` |
| ⑤ | 点按图标 → 启动应用 | ✅ | `05_launch_app.png`：计算器完整启动（`mCurrentFocus=com.android.calculator2`） |
| ⑥ | 应用名不再被截断 | ✅ | 「Amap Auto」「Calculator」「CarDesk 车控桌面」「Messaging」「Settings」「媒体探针」「嵌入测试」全部完整；只剩「Android Keyboard (AOSP)」「WebView Browser Tester」两个超长名自然截断 |

> 第 ⑤ 条在**修复前**是失败的：残留菜单的遮罩把点击全吃掉了，
> logcat 里连「抽屉选中应用」都没有 —— 这就是「点按图标没反应」的真正原因。

### 截图清单

| 文件 | 内容 |
|------|------|
| `00_before.png` | 改前（4245 夜间）：满屏「图标 ⋮ 图标 ⋮」，应用名一半被截断 |
| `01_drawer.png` | 改后抽屉：无 ⋮、图标居中、应用名完整 |
| `02_longpress_menu.png` | 长按图标弹出的应用菜单 |
| `03_back_closes_menu.png` | 菜单打开时按返回 → 只关菜单，抽屉还在 |
| `04_reopen_no_residue.png` | 关抽屉再重开 → 无残留 |
| `05_launch_app.png` | 点按图标 → 计算器启动 |
| `06_reopen_after_dock_close.png` | 「菜单开着点 Dock 关抽屉」这条路径 → 重开也无残留 |
| `08_before_old_menu_open.png` | **旧包 4245 对照**：点 ⋮ 打开菜单（背后满屏 ⋮） |
| `09_before_old_residue.png` | **旧包 4245 对照**：关抽屉后重开，**菜单原封不动残留** |

---

## 八、回归

| 变体 | 用例数 | 失败 | 错误 | 跳过 |
|------|--------|------|------|------|
| `testSystemUnitTest` | **2090** | 0 | 0 | 0 |
| `testDebugUnitTest` | **2090** | 0 | 0 | 0 |

比上一轮（2076）多了 **14 条** = `DrawerUiTest` 8 条 + `DrawerOverlayTest` 6 条。

另外改了 4 个既有抽屉测试文件（菜单入口从「点 ⋮」改成「长按图标」），全部通过。

---

## 九、产物

| 类型 | 文件 | 版本 | SHA-256 |
|------|------|------|---------|
| 交付包 | `dist/CarDesk-platform-ecarx-cardesk-4246.apk` | 4246 / `1.0.1150-system` | `18cd75990427b66eefe45fde2ec2569d49061016ae44a1a55842d796951412ee` |
| 调试包 | `dist-dev/CarDesk-debug-1.0.1150-com.cardesk.launcher.apk` | 4246 / `1.0.1150` | — |

- 交付包：2 373 705 字节，**AOSP platform 签名**（指纹 `c8a2e9bc…`），包名 `com.ecarx.cardesk`
- 出包走 `tools/release.sh`（自动版本号 + 干净全量构建 + 平台证书指纹校验 + 自动写 `dist/README.md` 与 `dist/cardesk-latest.json`）

---

## 十、设备状态

| 设备 | 装的包 | 说明 |
|------|--------|------|
| emulator-5560 | `com.ecarx.cardesk` 4246 system | 本轮验证机，prefs 与验证前一致 |
| emulator-5556 | `com.cardesk.launcher` 4246 debug | 对照实验用的旧包 4245 **已卸载**，恢复原状 |

`emulator-5560` 的 prefs 跟验证前一致：`pip_app=com.autonavi.amapautp.yh`、
`nav_cover_backdrop_enabled=true` 保留，`default_music_source_v1` / `auto_launch_media_app` /
`desk_overlay_*` 均未写入。

只多了一个 `last_app_pkg=com.android.calculator2` —— 那是「点按图标启动」的**正常产物**
（记录最后打开的应用），不是测试残留，下一次点别的应用就会覆盖。

---

## 十一、本轮改动的文件

| 文件 | 改动 |
|------|------|
| `app/src/main/java/com/cardesk/launcher/ui/DrawerPage.kt` | 删 ⋮ / 长按弹菜单 / 去拖放排序 / 图标居中 / 名称列 160 / 新增 `APP_CELL_MARK` / 新增 `clearOverlays()` `closeTopOverlay()` |
| `app/src/main/java/com/cardesk/launcher/ui/LauncherView.kt` | `closeDrawer` 清浮层 / `showDrawer` 清浮层 / `onBack` 先关浮层再关抽屉 |
| `app/src/main/java/com/cardesk/launcher/core/Prefs.kt` | `appOrder` 改为只读（不再写入） |
| `app/src/test/java/com/cardesk/launcher/ui/DrawerUiTest.kt` | **新增** 8 条 |
| `app/src/test/java/com/cardesk/launcher/ui/DrawerOverlayTest.kt` | **新增** 6 条 |
| `app/src/test/java/com/cardesk/launcher/ui/DrawerAppMenuTest.kt` | 菜单入口改成长按 |
| `app/src/test/java/com/cardesk/launcher/ui/DrawerAppDisableTest.kt` | 菜单入口改成长按 |
| `app/src/test/java/com/cardesk/launcher/ui/DrawerDisablePreviewTest.kt` | 菜单入口改成长按 |
| `app/src/test/java/com/cardesk/launcher/ui/DrawerMenuPreviewTest.kt` | 菜单入口改成长按 |
| `tools/verify_y152_reverse.sh` | 反向验证脚本（9 项） |
| `tools/verify_y152_device.sh` | 真机验证脚本 |

---

## 五、怎么保证不退回

### 5.1 新增 1 个标记，测试从**视图树**里挑东西

| 标记 | 标在谁身上 | 用来断言 |
|------|-----------|----------|
| `DrawerPage.APP_CELL_MARK` | 应用格子（图标列） | 能从视图树里**精确定位**某个应用的那个格子 |

> 为什么不能只靠源码断言：源码能查「某句话在不在」，查不出「还有没有 ⋮」、
> 「图标有没有居中」、「名称列多宽」。视图树这层能真查。
>
> 标记打在 **body（图标 + 名称整列）** 上而不是 ImageView 上 ——
> 长按监听就挂在 body 上，测试找的也是同一个宿主。

### 5.2 新增测试（14 条）

**`DrawerUiTest`（8 条）** —— 锁住 UI 重做本身：

| # | 用例 | 作用 |
|---|------|------|
| 1 | `抽屉里没有三个点了` | 全页文本里搜不到「⋮」 |
| 2 | `每个格子都只有一个图标和一个名称_没有多余的按钮` | 查**格子容器**（body 的父级）下的可点 view 只有 1 个 |
| 3 | `长按图标弹出应用菜单` | 长按 → 菜单里有重命名 / 应用信息 / 取消 |
| 4 | `长按不会改写应用排序` | 长按前后 `appOrder` 一个字节都不变 |
| 5 | `底部提示不再说拖动排序` | 提示里没有「拖动排序」也没有「⋮」，但要说清「长按」 |
| 6 | `应用名列加宽了_不再截成一串省略号` | 量真实宽度 ≥ 140（改前 76） |
| 7 | `图标在格子里居中_不是贴左沿` | 量图标列左边距在 40..110 + gravity 断言 |
| 8 | `图标本身还是76_没被改小` | 图标边长没被动过 |

**`DrawerOverlayTest`（6 条）** —— 锁住真机发现的浮层残留 bug：

| # | 用例 | 作用 |
|---|------|------|
| 1 | `关抽屉后应用菜单不能残留` | 关抽屉 → 浮层清空 |
| 2 | `重开抽屉不能带着上次的菜单` | 关→开之后抽屉是干净的 |
| 3 | `返回键先关菜单_不关抽屉` | 返回键只退出菜单，抽屉还在 |
| 4 | `没有浮层时返回键才关抽屉` | 没有浮层时返回键才关抽屉 |
| 5 | `没有浮层时closeTopOverlay返回false` | 方法语义 |
| 6 | `clearOverlays把菜单和弹窗都清掉` | 菜单 + 重命名弹窗一起清 |

### 5.3 改了 4 个既有测试文件

菜单入口从「点 ⋮」变成「长按图标」，原来依赖「点 ⋮」的用例全部改走 `performLongClick()`：

| 文件 | 改法 |
|------|------|
| `DrawerAppMenuTest.kt` | `menuButtonOfDemo` 改成按 `APP_CELL_MARK` 找祖先 |
| `DrawerAppDisableTest.kt` | `menuButtonOf` 同上 |
| `DrawerDisablePreviewTest.kt` | `menuButton` 同上 |
| `DrawerMenuPreviewTest.kt` | `menuButton` 同上 |

---

## 六、反向验证（9 项，全部必红）

脚本：`tools/verify_y152_reverse.sh`

| # | 改坏方式 | 预期 | 实测 |
|---|----------|------|------|
| 基线 | — | 0 红灯 | `RED=0 TOTAL=14` ✅ |
| 1 | 把「⋮」按钮加回来 | 必红 | `RED=2` ✅ |
| 2 | 长按又被拖动排序抢走（菜单弹不出来） | 必红 | `RED=4` ✅ |
| 3 | 长按偷偷改写排序偏好 | 必红 | `RED=1` ✅ |
| 4 | 名称列宽改回 76 | 必红 | `RED=2` ✅ |
| 5 | 图标列退回贴左沿 | 必红 | `RED=1` ✅ |
| 6 | 底部提示改回旧文案 | 必红 | `RED=1` ✅ |
| 7 | 关抽屉时不清理浮层 | 必红 | `RED=1` ✅ |
| 8 | 返回键不先关菜单 | 必红 | `RED=1` ✅ |
| 9 | 两处清理全拆掉（双保险都没了） | 必红 | `RED=2` ✅ |
| 还原 | — | 0 红灯 | `RED=0 TOTAL=14` ✅ |

### 踩过的坑：反向 7 一开始只红了 1 条

`clearOverlays()` 修在**两个地方**：`closeDrawer()` 开头（主）+ `showDrawer()` 开头（兜底）。
只拆 `closeDrawer()` 那处时，兜底还顶着，「重开抽屉不能带着上次的菜单」那条断言就**不会红** ——
说明那条断言当时并没有在把关。

补了反向 9（**两处一起拆**）才暴露出来，拆完两条断言一起红，才算真的验住了。

### 踩过的坑：测试 2 一开始查错了层级

「每个格子都只有一个图标和一个名称」原本查的是 `body` 的子树，
而「⋮」按钮当初挂在**格子容器**上（body 的兄弟节点）—— 只查 body 根本看不见它，
这条断言等于摆设。改成查 `body.parent`（格子容器）之后，反向 1 才正确命中 2 条。
