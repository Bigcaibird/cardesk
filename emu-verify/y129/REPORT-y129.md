# 第129轮 验证报告：画中画全屏按钮悬浮叠在画中画上

> 目标：把导航页左侧那条 36×110 的玻璃 `›` 按钮，从「独占画中画左侧 44px 留白」
> 改成「悬浮叠在画中画画面上」（用户截图红框指认）。

## 1. 环境

| 项 | 值 |
|---|---|
| 模拟器 | `emulator-5556`（Android 10 / SDK 29 / arm64-v8a / 1920×1080） |
| 包名 | `com.ecarx.cardesk`（**system 变体**，与真车同包名） |
| 画中画承载应用 | `com.autonavi.amapautp.yh`（高德地图车机版 9.5，弹窗可见 `V 9.5.0 新功能介绍`） |
| 桌面版本 | 4228 / 1.0.1132 |

## 2. 改了什么

| 文件 | 改动 |
|---|---|
| `ui/pages/NavPage.kt` | `buildNavSplit()` / `attachNavSplit()`：按钮的父容器从**导航页本体**改成**画中画承载面 `mapCard`**；`applySidesSwapped()`：承载面方向改用 `NavSplitPlacement.PIP_CARD_LAYOUT_DIRECTION`；新增只读入口 `pipCard` |
| `ui/LauncherView.kt` | `enterPipFullscreen()`：全屏态按钮的宽 / 高 / gravity 改用 `NavSplitPlacement` |
| `ui/pip/NavSplitPlacement.kt`（新） | 纯逻辑：两态共用的宽 36 / 高 110 / `START\|CENTER_VERTICAL`、宿主标识、承载面方向 |

## 3. 模拟器端到端验证

### 3.1 悬浮位置 —— 按钮压在画中画画面左边缘

`y129-nav-split-overlay-pip.png`

- 画中画卡片左边界 **x = 44**，按钮玻璃条占 **x = 44…80**、**y = 455…565**（垂直居中）。
- 按钮**压在地图画面上**：截图里地图「更多常去地点」一行的红色文字被按钮盖掉一角，
  可确认它浮在画面**之上**（`mapCard.bringChildToFront`），而不是在画面外侧。
- 屏幕最左侧 0…44 不再有任何控件 —— 原来那里是这条按钮独占的留白。

### 3.2 全屏往返 —— 位置两态完全一致

`y129-pip-fullscreen.png`

- 点按钮 → 画中画铺满设计画布（`enterPipFullscreen`），按钮作为**还原入口**浮在左侧 x ≈ 18。
- 再点 → 回到导航页，**按钮回到画中画承载面左边缘**（`attachNavSplit()` → `mapCard`），
  与 3.1 位置一致。

### 3.3 左右换位 —— 按钮跟着画面走

`y129-sides-swapped-button-follows.png`

- 点画中画上方三个点换位后：地图卡挪到**右边**（x = 590…1875），
  按钮跟着挪到 **x ≈ 607**，仍然贴在**地图卡自己的左边缘**。
- 旧实现（挂在导航页本体）下按钮会纹丝不动留在屏幕左边 → 跑到画中画对面。

### 3.4 像素级坐标核对（不靠肉眼）

把三张图的 `›` 字形白色像素框出来，直接量横坐标：

| 状态 | chevron 像素 x | 相对「画中画卡左边缘 / 屏幕左边缘」的偏移 |
|---|---|---|
| 不换位 · 悬浮在画中画上 | **60…63**（中心 62） | 62 − 44 = **18** |
| 换位后 · 悬浮在画中画上 | **605…608**（中心 607） | 607 − 590 = **17** |
| 画中画全屏 | **16…19**（中心 18） | 18 − 0 = **18** |

三态的**相对偏移一致（17…18px）** → 按钮在画中画里和全屏时
**落在同一个位置**，用户不用记两个地方。

## 4. 单测

- 新增 `ui/pip/NavSplitPlacementTest.kt`（6 项）：尺寸沿用、gravity 是左+垂直居中、
  **两态 gravity 相同**、悬浮态宿主是画中画承载面（非 `navPage`）、全屏态宿主是桌面根容器、
  承载面方向必须 LTR。
- 新增 `ui/pages/NavSplitOverlayTest.kt`（6 项，Robolectric 真实布局）：
  按钮挂在画中画承载面上、尺寸与定位参数沿用原值、压在画面左边缘且不超出画面、
  全屏往返后回到承载面、**全屏态与悬浮态用同一套定位参数**、换位时跟着画面走。
- 全量单测：**见第 6 节**。

## 5. 反向验证（`tools/verify_y129_reverse.sh`，可复跑）

| # | 改回旧行为 | 红灯 | 变红的断言 |
|---|---|---|---|
| 1 | `buildNavSplit` 挂回导航页本体 | 3 | 挂在承载面 / 压在画面左边缘 / 换位跟随 |
| 2 | `attachNavSplit` 放回导航页本体 | 1 | 全屏往返后回到承载面 |
| 3 | `HOST_OVERLAID` 改回 `navPage` | 1 | `host_overlaid_isPipCard_notNavPage` |
| 4 | 全屏态 gravity 与悬浮态不一致 | 2 | 两态一致 ×2 |
| 5 | 承载面方向改成 RTL | 2 | 承载面方向 / 压在画面左边缘 |
| 6 | `LauncherView` 全屏态硬编码 gravity | 1 | 全屏态与悬浮态同一套参数 |

基线 0 红灯 → 逐项改回必红 → **还原后自检 0 红灯**。

> 反向 4 第一版踩了个坑：原来写的是 `GRAVITY or 0x10`，
> 而 `0x10` 就是 `CENTER_VERTICAL` —— 位或完**值根本没变**，等于没改，断言自然不会红。
> 换成 `Gravity.TOP` 后如期变红。**「改坏」不等于「改到了」**，
> 反向验证脚本本身也要复核。

## 6. 产物与全量回归

| 项 | 值 |
|---|---|
| 产物 | `dist/CarDesk-platform-ecarx-cardesk-4228.apk` |
| 包名 | `com.ecarx.cardesk` |
| versionCode / versionName | **4228** / **1.0.1132-system** |
| 签名 | AOSP platform（证书 SHA-256 `c8a2e9bccf597c2fb6dc66bee293fc13f2fc47ec77bc6b2b0d52c11f51192ab8`） |
| SHA-256 | `b54c74fe90198c9af693d5e7cfe5ab40d3280914b5c5ba021a09edd3f6e32b2a` |
| 大小 | 2254826 字节 |
| 全量单测 | **1720 项全绿**（1708 → 1720，新增 12） |

模拟器上装的也是这份正式包（`pm path` + `dumpsys package` 核对
`versionCode=4228 / versionName=1.0.1132-system`），截图即真机效果。

## 7. 已知限制

- 按钮会**遮住画中画画面左边缘 36×110 的一块**，并吃掉该区域的触摸。
  地图应用左侧竖直居中处通常是空白（实测高德 9.5 该位置是菜单区文字），风险可控，
  但**真车上没验过**。
- 高德车机版的登录/升级提示是**系统弹窗**，在画中画里弹出来时会
  **溢出画中画卡**盖到右边的音乐卡上 —— `ActivityView` 的既有行为，本轮没碰。
- 模拟器上**点不进画中画里的高德**（触摸没被注入到 VirtualDisplay），
  所以「画中画内部交互」这一段仍需真车复验；本轮验的是**桌面自己这一层**的按钮归属与位置。

## 8. 排查过的一个疑似回归：画中画内容偶发黑屏

取证过程中出现过「画中画里高德变全黑」的现象（多张截图里出现），
必须确认**不是本轮改坏的**。排查过程与结论：

1. **代码层面逐行核对** —— 本轮对「画中画承载 / ActivityView / 换位」这条路径
   **一处都没改**：
   - `mapCard.layoutDirection`：`View.LAYOUT_DIRECTION_LTR` →
     `NavSplitPlacement.PIP_CARD_LAYOUT_DIRECTION`，而后者 = `PipSides.DIR_LTR` = **0**，
     **值完全相同**；
   - `LauncherView.enterPipFullscreen` 的全屏按钮参数：
     `Ui.frame(36, 110, Gravity.START or Gravity.CENTER_VERTICAL)` →
     `Ui.frame(NavSplitPlacement.WIDTH, NavSplitPlacement.HEIGHT, NavSplitPlacement.gravity(true))`，
     三个值分别还是 **36 / 110 / (START|CENTER_VERTICAL)**，**值完全相同**；
   - `navSplit` 本身是一个纯 `FrameLayout`（玻璃底 + 一个文字 `›`），
     **不参与 ActivityView**，挂到 `mapCard` 里不影响承载应用的渲染。
2. **对照实验** —— 用 `git stash` 回到第128轮代码出基线包（核对过 APK 里
   **不含** `NavSplitPlacement` 这个类），装到同一台模拟器：
   反复换位 3 轮、全屏往返各 1 轮，画面都正常。
3. **同代码不同结果** —— **同一份 4228 代码**，第一次跑（高德进程是上次遗留的）
   出现黑屏，`am force-stop` 高德后重跑一切正常。
   同一份代码两次结果不同 → **非确定性**，不是确定性回归。
4. **logcat 定位** —— 全屏往返时 `exitPipFullscreen` 会调
   `navPage.pip.relaunchAfterResize()`，日志里能看到高德的
   `UsbFillActivity` / `MainMapActivity` 被**杀掉重启**
   （`ActivityTaskManager: START ... com.autonavi.amapautp.yh`）。
   黑屏就是**高德 Activity 重启后的重绘窗口**。

**结论**：黑屏是「高德车机版 Activity 被 relaunch 后未及时重绘」的**既有问题**，
与本轮按钮改动无关；重启高德即恢复。本轮**未修**它（不在本轮范围），
但在此记录，避免下一轮再被它绊住。
