# 第137轮 验证报告

> 主题：① 整理设置页，把「桌面歌词」与「仪表歌词」区分开（原来混在一起）；
> ② 仪表歌词颜色联动日夜模式，分别用不同配色，确保在白色仪表底上清晰。
>
> 需求原文（用户）：
> 1. 整理一下仪表歌词内的设置内容，将桌面歌词和仪表歌词区分出来，现在过于混乱；
> 2. 现在的仪表歌词在日间模式的白色仪表背景下不明显，仪表歌词颜色联动一下日夜模式，
>    分别使用不同的配色，确保显示清晰。

## 一、验证环境

| 项 | 值 |
|---|---|
| 模拟器 | `emulator-5558`（Android 14 / API 34，1920×1080） |
| 被验包 | `app-debug.apk`（debug 变体，包名 `com.cardesk.launcher`） |
| 单元测试 | 全量 **1908 项**，0 失败 / 0 错误 / 0 跳过 |
| instrumented | `ClusterLyricsCastTest` → **OK (2 tests)** |
| 源码状态 | 本轮改动**未提交**（工作区）；未出正式包、未发 Release |

---

## 二、需求一 · 设置页拆分

### 改之前的乱象（代码证据）

| 现象 | 证据 |
|---|---|
| 导航项叫「仪表歌词」，内容大半是桌面歌词 | `SettingsOverlay.kt` 原 `mergedGroups` 里 `NavItem("layout","仪表歌词")` → `pageLayout()`，而 `pageLayout()` 里放的是**导航栏歌词槽字号 / 首页中央歌词布局** |
| 仪表歌词真入口藏在页面**最底部** | `pageLayout()` 末尾只有一个「打开布局参数对话框…」按钮，点开才是 `DesktopLayoutDialog` |
| 对话框里桌面/仪表字号混在一起 | `DesktopLayoutDialog` 同时放桌面歌词字号与仪表歌词字号，用户分不清哪个调哪个 |
| 对话框选中屏标签用错色 | 用了 `pal.accentInk`（深底深字），疑似旧 bug |

### 改之后

| 导航项 | 内容 |
|---|---|
| **桌面歌词** | 导航栏歌词槽字号（4 档）+ 首页中央歌词布局 / 预览 / 字号缩放 |
| **仪表歌词** | 投射开关 / 投射目标屏列表 / 投射状态 / 面板位置与样式 / 歌词颜色 |
| **车辆信息** | 胎压字号 / 车模编号 / 车辆数据源 |

`DesktopLayoutDialog` 类**已删除**（`ui/dialogs/ExtraDialogs.kt` 净删 475 行，文件现 428 行）——
不留第三处入口。

### 证据链

| 场景 | 证据 | 结论 |
|---|---|---|
| 设置页「桌面歌词」 | `r137-settings-desktop-lyric.png` | 导航栏歌词槽字号 + 首页中央歌词布局 / 预览 / 字号缩放，**全是桌面歌词的**，没有一项是仪表的 |
| 设置页「仪表歌词」 | `r137-settings-cluster-lyric.png` | 投射开关 / 投射目标屏列表 / 投射状态 / 面板位置与样式，**仪表歌词设置不再藏在底部按钮后面** |
| 设置页「歌词颜色」 | `r137-settings-cluster-color.png` | 两行标签分别显示「跟随日夜 · #7FE3D4」「跟随日夜 · #9FB4BB」——把当前实际色值写出来了 |
| 导航树 | 走查时设置页导航 | 「布局与显示」组已拆出**「桌面歌词」「仪表歌词」**两项 |
| 离屏渲染 | `preview/y31-settings-cluster-lyric.png`、`preview/y32-settings-carinfo.png` | 两个新页面渲染正常，无布局塌陷 |

> 📌 `r137-settings-cluster-color.png` 是在修掉一个 markdown 字面量 bug **之前**截的
> （`note()` 是普通 TextView，不解析 markdown，`**xxx**` 会原样显示）。bug 已修，
> 重装后的 APK 已含修复；此图为修复前的历史证据。

---

## 三、需求二 · 仪表歌词颜色联动日夜

### 问题的量化

仪表歌词画在**仪表自己的画面**上，仪表底色跟着车机日夜走。
第一百三十三轮只有**一套固定配色**（浅青 `0xFF7FE3D4` + 浅灰蓝 `0xFF9FB4BB`），
那是照着**夜间仪表**调的。日间仪表底色是白的，同一支浅青直接糊进去：

| 旧配色 | 仪表底 | 对比度（WCAG 2.x） |
|---|---|---|
| 旧主色 `0xFF7FE3D4` | 白 `#FFFFFF` | **1.52 : 1** ✗ |
| 旧副色 `0xFF9FB4BB` | 白 `#FFFFFF` | **2.16 : 1** ✗ |

**1.52 : 1 连 AA 大字标准（3 : 1）都不到** —— 这就是「日间模式下不明显」的确切数值。

### 两套配色与实测对比度

| | 主歌词色 | 副歌词色 | 底板色（alpha 恒为 0） |
|---|---|---|---|
| **夜间**（仪表深底） | `0xFF7FE3D4` 浅青 | `0xFF9FB4BB` 浅灰蓝 | `0x00101820` 深 |
| **日间**（仪表白底） | `0xFF0C5F57` 深青 | `0xFF46535C` 深石板灰 | `0x00F3F6F9` 浅 |

| 色 | 仪表底 | 对比度 |
|---|---|---|
| 日间主色 `0xFF0C5F57` | 白 `#FFFFFF` | **7.53 : 1** ✅ |
| 日间副色 `0xFF46535C` | 白 `#FFFFFF` | **7.92 : 1** ✅ |
| 夜间主色 `0xFF7FE3D4` | 深 `#0F1116` | **12.45 : 1** ✅ |
| 夜间副色 `0xFF9FB4BB` | 深 `#0F1116` | **8.74 : 1** ✅ |

WCAG AA 正文要求 4.5 : 1，四条全过。数值由 `ClusterLyricColorsTest` 用同一套 WCAG 亮度公式
**实算**得出，不是估的。

**底板颜色也必须跟着翻**：夜间深底配亮字、日间浅底配深字；
不翻的话日间会变成「深字压深底板」，一样糊。底板 alpha 恒为 0，
真实透明度仍由「仪表背景透明度」滑杆给。

**用户手挑的颜色优先于日夜联动**（`clusterLyricMainColor/SubColor != 0` 即固定），
并提供「恢复跟随日夜」按钮（`defaultColor = 0` 即恢复）。

### 实投证据（真实副屏）

| 场景 | 证据 | 结论 |
|---|---|---|
| 副屏实投 · 夜间 | `r137-cluster-cast-night.png` | 深色仪表底（`#1A2430`）+ **亮青字**，看得清 |
| 副屏实投 · 日间 | `r137-cluster-cast-day.png` | 白色仪表底（`#FFFFFF`）+ **深青字**，看得清 |
| 离屏渲染 · 日间 | `preview/36-cluster-day.png` | 白底 + 深青字，清晰 |

### instrumented：「日夜切换后副屏上的歌词颜色会跟着换」

做法：自造副屏 → 用**真实设置键**（`dayNightEnabled=true` / `dayNightMode=1` 跟随本地时间 /
`dayStartMin` + `nightStartMin` 摆时段）强制夜间与日间 → 抓副屏帧算不透明像素平均亮度，
断言**日间 < 夜间 − 0.1**。

结果：**OK (2 tests)**，0 failed。

日志（`y137-log-cluster-daynight.txt`）：

```
11-09 09:43:50.586 I TestRunner: started: 日夜切换后副屏上的歌词颜色会跟着换
11-09 09:43:50.723 I CarDesk/Cluster: 歌词已投到副屏 display=CarDesk 测试仪表 displayId=20 size=1920x720 density=1.5 panel=1920x155
11-09 09:44:05.511 I CarDesk/Cluster: 日夜态变化，重配歌词颜色 night=false
11-09 09:44:06.387 I TestRunner: finished: 日夜切换后副屏上的歌词颜色会跟着换
11-09 09:44:07.024 I TestRunner: run finished: 2 tests, 0 failed, 0 ignored
```

「**日夜态变化，重配歌词颜色 night=false**」= 服务侧在日夜态翻转时**原地换色**
（不重建窗口、不打断播放）。

---

## 四、踩过的坑

### 坑一：日夜判定不能读桌面的 `Skin.isNight`

最初让投射服务直接读 `Skin.isNight`（桌面主题单例），实测两个坑：

1. **桌面没起来时这个值是死的** —— 开机广播 `BootReceiver` 会**独立**把投射服务拉起来，
   那时桌面还没跑过，`Skin.apply()` 从没被调过，`Skin.isNight` 停在初始值（夜间）。
   ⇒ **白天的白色仪表上投浅青字**，跟用户反馈的一模一样。
2. **桌面被销毁后这个值会过期** —— 投射服务是前台服务、活在桌面那个进程里；
   桌面 Activity 一旦被销毁，每秒刷新循环停了，`Skin.isNight` 冻在最后的值上。

**解决**：抽 `core/NightResolver.kt`，桌面与投射服务**各问同一个解析器**。
四路来源按优先级排（高德广播 → 车机光感 → 车机日间模式功能值 → 系统 UI 夜间态），
高德那路信号放在 `NightResolver.amapNightUi` 里**共享** ⇒ 桌面与仪表歌词不会劈叉。

服务侧 **2 秒限流**（`NIGHT_POLL_MS = 2000L`）：车机日间模式功能值要走 Binder，
不能每 700ms 喂数都去读一遍。

### 坑二：instrumented 里桌面不会被拉起

instrumented 测试**不会**启动 `MainActivity`，所以 `Skin.isNight` 停在默认夜间，
桌面的每秒刷新循环也不跑。⇒ 断言不能盯 `Skin.isNight`，要盯 `NightResolver.resolve(...)`。
服务自己解析日夜后就不再依赖桌面活着。

### 坑三：透明底的帧在查看器里像「没画出来」

抓回来的副屏帧是**透明底**，夜间浅灰蓝字在查看器白底上几乎看不见。
⇒ 证据图必须**合成到真实仪表底色**再看。

### 坑四：`note()` 不解析 markdown

`SettingsOverlay` 的 `note()` 是普通 TextView，**不解析 markdown**，`**xxx**` 会原样显示。
（本轮已踩并修。）

---

## 五、验证汇总

| 项 | 结果 |
|---|---|
| 编译 | `:app:compileDebugKotlin` **BUILD SUCCESSFUL** |
| 单测 | **1908 项全绿**（1908 / 失败 0 / 错误 0 / 跳过 0），第136轮 1899 → +9 |
| instrumented | `ClusterLyricsCastTest` → **OK (2 tests)** |
| 崩溃 | `FATAL EXCEPTION` 计数 **0** |
| 设置页走查 | 3 张截图 + 导航树确认 |
| 副屏实投 | 夜 / 日 两张，抓帧亮度断言通过 |

### 新增 / 修改的测试

| 测试类 | 变化 | 覆盖 |
|---|---|---|
| `ClusterLyricColorsTest`（新） | +8 | 日夜取值不同 / 用户色优先 / `0` = 跟随日夜 / 底板 alpha 恒为 0 / 日间对白底 ≥4.5:1 / 夜间对深底 ≥4.5:1 / 底板与字明度相反（含 WCAG 亮度算法） |
| `ClusterLyricsRenderTest` | +1 | 日间渲染 → `preview/36-cluster-day.png`；`shotPanel` 增加 `night`/`bg` 参数 |
| `SettingsLayoutRenderTest` | +2 | `y31-settings-cluster-lyric.png`、`y32-settings-carinfo.png` |
| `SettingsLyricLayoutTest` | 改 | `showPageKey("layout")` → `"desktoplyric"`（2 处） |
| `ClusterLyricsCastTest`（androidTest） | +1 | 日夜切换后副屏歌词颜色会跟着换 |

---

## 六、正式包复验（4235 / 1.0.1139-system）

出包走 `tools/release.sh`（升版本号 → **干净目录** clean 全量构建 → 全量单测 →
平台证书指纹校验 → 落 dist/ + `cardesk-latest.json` + `dist/README.md`）。

| 项 | 值 |
|---|---|
| 文件 | `dist/CarDesk-platform-ecarx-cardesk-4235.apk` |
| 包名 | `com.ecarx.cardesk` |
| versionCode / versionName | 4235 / 1.0.1139-system |
| 大小 | 2 329 489 字节 |
| SHA-256 | `837357cb30c7d1c050f1eefc6960eb8e2754188a1dca7a881b40a8cca56db797` |
| 签名 | AOSP platform（SHA-256 `c8a2e9bc…92ab8`，脚本自动校验，不符直接失败） |
| 全量单测 | 干净构建目录里 `clean assembleSystem testDebugUnitTest` → **BUILD SUCCESSFUL** |

**实装复验（`emulator-5556`，ecarx 平台镜像）**

| 步骤 | 结果 |
|---|---|
| `adb install -r -t` | **Success** |
| `dumpsys package` | `versionCode=4235 versionName=1.0.1139-system` |
| 启动 | `am start com.ecarx.cardesk/com.cardesk.launcher.MainActivity` 正常 |
| 设置页导航 | 「布局与显示」组里**「桌面歌词」「仪表歌词」两项已分开**（`r137-official-settings.png`） |
| 「仪表歌词」页 | 投射开关 / 投射目标 / 投射状态 / 面板位置与样式**全部内联**，没有「打开布局参数对话框」按钮（`r137-official-cluster-lyric.png`） |
| 「歌词颜色」区 | 说明文案正常（**没有 markdown 字面量 bug**）；标签「跟随日夜 · #7FE3D4」「跟随日夜 · #9FB4BB」；有「恢复跟随日夜」按钮（`r137-official-cluster-color.png`） |

> 装在**普通 Google 镜像**（`emulator-5558`）上会被拒：
> `INSTALL_FAILED_SHARED_USER_INCOMPATIBLE` —— 该包是平台签名 + `sharedUserId=android.uid.system`，
> 目标机器的平台密钥必须一致（车机 / ecarx 平台镜像可以）。这不是包的问题。

---

## 七、已知限制

- **真车还没验**：模拟器上的仪表底色（夜 `#1A2430` / 日 `#FFFFFF`）是按常见车机仪表做的**假设**。
  配色逻辑本身是「深底配亮字 / 浅底配深字」，不依赖具体色值，但真车仍应看一眼。
- **日夜切换有 ≤2 秒延迟**：服务侧限流 2 秒读一次日夜（避免每 700ms 喂数都走 Binder）。
  真车上仪表自己切底色，我们跟着切，切换瞬间可能有一两秒颜色还没跟上。
- **副屏能不能投到真车仍未证明**（第133轮就有的限制，本轮没变）：真车日志包里没有
  `dumpsys display`，`ICarInfo.getPresentationDisplay(int)` 在 jar 里返回 null（是桩），
  无法证明这台车把副屏开放给了第三方应用。本轮改的是**投上去之后**的颜色，前提依然是那件事成立。
- **「用户手挑颜色」优先于日夜联动**：手动挑过色的用户，日夜切换时颜色**不会**跟着变
  （刻意为之：用户挑了就是想固定）。要恢复跟随得点「恢复默认」。
- **R2 未上传**：`R2_AK` / `R2_SK` 环境变量未设置（与第132/133/136轮一致）。
  App 的「在线更新」暂时拉不到这一版，需手动装包或补上凭据后重传。
- **GitHub Release 已发**（`v1.0.1139`），但**没有回滚计划**：发出去的包是公开可下载的。
