# 第139轮 验证报告

> 主题：切换日夜模式时**高德从画中画变成全屏** —— 在模拟器上复现并修复。
>
> 需求原文（用户）：这个版本，在切换日夜模式时，高德会突然从画中画变成全屏，
> 你通过虚拟机复现修复一下。

## 一、验证环境

| 项 | 值 |
|---|---|
| 模拟器 | `emulator-5556`（Android 10 / SDK 29 / 1920×1080，平台签名镜像） |
| 对照包 | 修复前 4236（第138轮产物） / 修复后 4237（本轮产物） |
| 桌面日夜设置 | `daynight_enabled=true`、`daynight_mode=0`（**跟随高德**） |
| 承载应用 | `com.autonavi.amapautp.yh`（画中画主画） |

> 模拟器是 **API 29**，真车是 **API 28** —— 两边在「display 消失时 task 去哪」这件事上
> 行为不完全一样（见「四、已知限制」），所以本报告里的对照口径是
> **「承载面有没有被重建 / 承载应用有没有被重启」**，这在两边是同一条因果链。

## 二、怎么触发的切日夜（踩过的坑记一笔）

```bash
adb -s emulator-5556 shell am broadcast \
  -a AUTONAVI_STANDARD_BROADCAST_SEND --ei KEY_TYPE 10019 --ei EXTRA_STATE 37
```

- `KEY_TYPE=10019` —— 高德「自动状态」通道的消息类型，**不能漏**；
  漏了的话桌面走的是「忽略高德广播 keyType=-1」分支，**桌面根本没换肤**
  （第一次复现就栽在这儿，一度以为复现的是另一件事）。
- `EXTRA_STATE=37` 白天 / `38` 黑夜 —— 与高德协议
  `AndroidProtocolExe.sendAutoStatus` 的 `case 37/38 → onDayNightChange(1/2)` 一致。

## 三、证据

### 3.1 修复前（4236）—— 承载面被整个重建、承载应用被重启

| 证据 | 结论 |
|---|---|
| `y139-01-before-fix-switch.png` | 切日夜前：高德在画中画里正常显示 |
| `y139-02-after-fix-switch.png` | 切日夜后：高德被**重启**了一遍（画面重来，不是连续的） |
| `y139-log-before-fix.txt` | 日志里出现 `尝试 ActivityView 嵌入` + `ActivityView 启动承载应用` —— **重建了承载面** |
| `y139-log-before-fix.txt`（系统侧） | `Display device removed: ActivityViewVirtualDisplay@…` → `finishAllActivitiesLocked: finishing … MainMapActivity t792` —— **旧 VirtualDisplay 一销毁，承载应用就被踢走** |
| `dumpsys activity activities` 对照 | VirtualDisplay **#67 → #68**；高德 task **t790 → t791** |

### 3.2 修复后（4237）—— 承载面「搬家」，高德一动不动

| 证据 | 结论 |
|---|---|
| `y139-03-fixed-before-switch.png` | 切日夜前：高德在画中画里 |
| `y139-04-fixed-after-switch.png` | 切日夜后：**画面连续**，高德的「50 米」导航提示一直在，没有黑一下、没有重来 |
| `y139-log-after-fix.txt` | `画中画承载面摘出（VirtualDisplay 保留）` → `画中画显示 … mode=transferred` → `承载面搬家（换肤重建），不重新拉起承载应用`；**没有** `尝试 ActivityView 嵌入`、**没有** `ActivityView 启动承载应用` |
| `dumpsys activity activities` 对照 | VirtualDisplay **#73 → #73**；高德 task **t801 → t801**（都没变） |

### 3.3 边界走查

| 场景 | 证据 | 结论 |
|---|---|---|
| 画中画**全屏态**切日夜 | `y139-05-fixed-fullscreen-switch.png` | 全屏态保留，高德画面连续（承载面此时挂在桌面根容器上，搬家逻辑一样成立） |
| **双画**切日夜 | `y139-06-fixed-dual-switch.png` | 主画、副画**都**走搬家；高德与 QQ音乐两个应用的 display id、task id 全部不变 |

## 四、根因（为什么以前是「黑屏」，现在是「全屏」）

- **第十二轮**为了修「切日夜后画中画一直黑屏」，在换肤重建**前** `release` 旧承载面 ——
  解决了「旧 VirtualDisplay 泄漏」+「新承载面黑屏」，
  但代价是**旧的 VirtualDisplay 一销毁，挂在它上面的 task 就离开那块虚拟屏**：
  - Android 9（真车 API 28）：task 被**搬到默认显示器** → **高德全屏抢屏**（用户报的现象）；
  - Android 10（模拟器 API 29）：task 被 finish 掉、随后在新 display 上重建 → 高德被**重启**一遍。

同一句 `releasePipSurfaces("重建前")`，在两个系统版本上表现不同，
**但根因是同一条**：承载面被拆了。

## 五、修法（一句話）

换肤重建改成「**摘走 → 释放外壳 → 交给新承载面**」：
`VirtualDisplay` 全程不死 → 高德的 task 根本不会离开我们的 display。

- `PipSurface.detachContent()`：把承载面摘出来（**不调** `ActivityEmbed.release`）；
- `PipSurface.adoptContent()`：新承载面接管（画面连续，`refreshPip()` 随后直接复用）；
- `ActivityEmbed.suppressNextRelaunch()`：搬家那一次 surface 重建**不重新拉起**承载应用；
- `core/PipRebuildPolicy.kt`（新，纯逻辑）：主画 / 副画搬不搬、搬完要不要重拉，
  三条分支各自对应一种画中画坏掉的用户可见故障。

## 六、怎么验的

| 项 | 结果 |
|---|---|
| 全量单测 | **1937 项全绿**（1928 → +9，新增 `PipRebuildPolicyTest`） |
| 反向验证 | `tools/verify_y139_reverse.sh` **13 项全红**，还原后全绿 |
| 反向脚本自身 | 断言用精确文本 + 剥行注释；**反向 12** 专门把调用注释掉，验证断言不会被注释骗过 |
| 模拟器走查 | 单画 / 画中画全屏态 / 双画，三档都过 |

## 七、已知限制

- **真车（领克，API 28）仍需复验**：模拟器是 API 29，
  系统在 display 消失时对 task 的处理与 Android 9 不完全一样
  （API 29 会把 task 顺手搬回新 display，API 28 不会）。
  本轮的修法让 **task 根本不离开那块 VirtualDisplay**，
  两边行为应当一致，但真车结论以实车为准。
- 本轮**只**修「切日夜导致高德从画中画变全屏」这一条；
  画中画其它历史问题（黑屏自愈分级、尺寸变化重载等）一律照旧。
- 模拟器上的偏好已还原（`daynight_enabled` / `daynight_mode` / `pip2_app` / 双画开关
  都恢复成走查之前的样子）。
