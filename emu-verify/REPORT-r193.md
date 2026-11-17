# 第193轮 · 全局 Dock 让位实测报告

**日期**：2026-11-17
**设备**：模拟器 `emulator-5560`（API 28，1920×1080 @160dpi，已 root）
**对比包**：`CarDesk-platform-ecarx-cardesk-4268.apk`（第192轮）vs 第193轮修复版
**设置**：对齐真车 —— `dockbar_hide_dock=true`（「隐藏原车 Dock」打开）、RRO 覆盖包 `com.ecarx.cardesk.insets` 已 enable

---

## 一、为什么要在模拟器上做新旧包 A/B

第189轮把「隐藏原车 Dock」误接到**预留区路径**上，往 `policy_control` 写了
`immersive.navigation=*`。这条策略在真车上连锁出三件事：

1. 系统导航栏连同 112px 预留带被从**所有** App 的窗口布局里摘掉；
2. WMS 给**每个** App 打上 `HIDE_NAVIGATION`/`IMMERSIVE`，而
   `dumpsys window policy` 的 `mLastSystemUiFlags` 是**全局**值
   ⇒ 沉浸探测对任何前台 App 都判「沉浸」；
3. Dock 一让位，底下被它盖住的原车 `CarNavigationBar` 就露出来。

模拟器上没有厂商的 `CarNavigationBar`，**「导航栏露出」这一半无法直接复现**，
但「Dock 到处让位」这一半可以完整复现 —— 而且它就是导航栏露出的**原因**。
所以本轮在模拟器上做新旧包对照，重点看**让位判定**。

## 二、测试夹具

模拟器上**没有**网易云 / 汽水音乐，按包名造了 4 个桩 App
（`make_stub.sh`，用 `aapt2` + `d8` + `apksigner` 直接打的最小 APK）：

| 桩包名 | 自己 `hideNavigationBars()`？ | 在已知名单里？ | 用途 |
|---|---|---|---|
| `com.netease.cloudmusic.iot` | 是 | 是 | 名单命中路径 |
| `com.luna.music.car` | 是 | 是 | 名单命中路径 |
| `com.stub.immersive` | 是 | 否 | **探测命中**路径 |
| `com.stub.plain` | 否 | 否 | **不让位**路径 |

配套脚本：

- `matrix.sh` —— 跑让位矩阵（清日志 → 启动 App → 抓日志 + 窗口状态）
- `wins.py` —— 读 `dumpsys window windows`，报告三个关键窗口的可见性

## 三、判据

让位与否看 `CarDesk 全局 Dock` 窗口（`GlobalDockService.updateVisibility`）：

| 状态 | `mViewVisibility` | `isOnScreen` | 让位把手 |
|---|---|---|---|
| **不让位** | `0x0` | `true` | `isOnScreen=false` |
| **让位** | `0x4`（INVISIBLE） | `false` | `isOnScreen=true` |

## 四、策略侧对照

| | 旧包 4268 | 新包（第193轮） |
|---|---|---|
| `policy_control` | `immersive.navigation=*` | **null** |
| 策略日志 | `hideNavigation=true navInsets=true` | `hideNavigation=false navInsets=true` / `immersiveNavigation=false` |

预留带本身两边都在（`stable=[0,0][0,112]`），Dock 窗口都是
`mFrame=[0,968][1920,1080]` —— 变的是**策略**，不是几何。

## 五、让位矩阵结果

| 场景 | 期望 | 旧包 4268 | 新包（第193轮） |
|---|---|---|---|
| 桌面 · 左右滑动 | 不让位 | 不让位 ✓ | **不让位 ✓**（切页 page=0→1→0，全程无让位日志） |
| QQ音乐车机版 | 不让位 | **让位 ✗** | **不让位 ✓** |
| 高德地图 | 不让位 | **让位 ✗** | **不让位 ✓** |
| 网易云音乐（桩） | 让位 | 让位 ✓ | **让位 ✓** |
| 汽水音乐（桩） | 让位 | 让位 ✓ | **让位 ✓** |
| 沉浸型 App（非名单桩） | 让位 | 让位（连锁） | **让位 ✓**（探测命中） |
| 普通 App（非名单桩） | 不让位 | **让位 ✗** | **不让位 ✓** |

旧包从 QQ 音乐起就**一直**卡在让位状态 —— 连系统设置页
（`yield=true foreground=com.android.settings 名单命中=false 探测沉浸=true`）
和普通 App 都让位。这就是用户报告的「在几乎所有 app 都让位了」。

## 六、Fix B 那道闸单独验过

手动把 `policy_control` 写成 `immersive.navigation=*`
（模拟车机或别的 App 写了全局沉浸策略）：

```
W/CarDesk/Dock: 沉浸探测失效：policy_control 有全局沉浸策略，mLastSystemUiFlags 不代表单个 App policy=immersive.navigation=*
```

此时普通 App 前台**没有**触发让位，Dock 保持可见 ——
探测不可信时宁可多盖一点，也不要到处让位。

## 七、截图

| 文件 | 内容 |
|---|---|
| `r193-01-new-home.png` | 新包 · 桌面（全局 Dock 完整） |
| `r193-02-new-qqmusic-dock-visible.png` | 新包 · QQ音乐 —— **全局 Dock 完整显示，导航栏没露出** |
| `r193-03-new-netease-yield.png` | 新包 · 网易云（让位态，底部让出系统导航栏） |
| `r193-04-old4268-qqmusic-dock-missing.png` | 旧包 4268 · QQ音乐 —— **全局 Dock 整个消失** |

## 八、复现步骤

```bash
bash emu-verify/r193/make_stub.sh          # 造 4 个桩包
adb install -r -d /tmp/stub/out/com.netease.cloudmusic.iot.apk   # …逐个装
bash emu-verify/r193/matrix.sh             # 跑矩阵
python3 emu-verify/r193/wins.py            # 看窗口可见性
```

> 桩包为一次性夹具，**验证后已从模拟器卸载**（避免假图标混进应用列表）。
> 复现时用上面的脚本重打即可。
