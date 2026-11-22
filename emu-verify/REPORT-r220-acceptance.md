# 第220轮验收 · 独立复测（cardesk28 / API 28 / arm64）

> 验收对象：`com.ecarx.cardesk` **4296 / 1.0.1200-system**（第220轮交付包）
> 验收环境：`cardesk28` AVD，`emulator-5560`，Android 9 / API 28 / arm64-v8a，1920×1080 @160dpi
> 验收时间：2026-11-19 16:07–16:20
> 验收方式：只读复测，不改代码、不重启车机、不点「清空日志」
> 包一致性：设备内 `base.apk` 3,865,267 B，sha256 `9a02c42847789a29e617b67302bb1ab2b4d926ab12fbc86c37bfaabced86c7e5`，与 `REPORT-r220.md` 记录一致。

---

## 0. 结论

**性能/卡顿主目标：通过。**

- 主线程不再做文件块写：10 秒采样内主线程 `write_bytes = 0`，日志块写全部落在 `cardesk-log-wri` 线程。
- 车控信号不再在主线程同步读：采样线程 `cardesk-vehicle` 在位，主线程只读 `@Volatile` 快照；导出包内「最近 0ms / 峰值 53ms」证明 53ms 花在后台，主线程读是 0ms。
- 落盘速率 713–721 B/s，只有 Logcat 的 **15.6%**；4MB 预算约 **97–98 分钟**，与报告 94.7 分钟同一量级。
- 心跳中位数 **1005.5ms**（1002–1016ms），未复现旧版 1089–1153ms。
- FATAL 0、ANR 0、Davey 0、Skipped frames 0。

**卡顿指标：基本通过，但报告的 3.23% 未能直接复现。**

- 单次翻页：41 帧、2 janky（4.88%）、p99 18ms、max 18ms、Missed Vsync 0 —— 与报告 p99 20ms / max 20ms / 0 missed 基本一致。
- 12 次连续翻页（0.35s 间隔）：321 帧、39 janky（12.15%）、p99 26ms、max 32ms、**Missed Vsync 仍为 0**。报告为 93 帧 / 3.23%；93 ≈ 260ms × 30fps × 12，疑似报告采样在 30fps 口径，本次是 60fps 且间隔更激进，**两者不能直接比较**，不能据此判回归。
- 最大帧 32ms，没有出现 >32ms 的帧，也没有 Missed Vsync；体感卡顿的两个主线程根因（文件 I/O、车控 binder）已被移除。

**诊断包导出有 P1 缺陷（历史遗留，非 220 引入），不通过。**

- 导出包**只含旧会话日志**（`cardesk-20261119-0.txt`，781KB），**当前会话日志**（`cardesk-20261119-1.txt`，2.7MB）被静默跳过；实车排障拿不到刚发生的问题。
- 截断文案写「丢弃了最老的 1 个文件」，实际丢的是**最新**文件，方向也反了。
- 单文件 512KB 轮换上限同样失效（当前文件已 2.8MB），但旧包 4295 写出的 `-0.txt` 也已 781KB，**说明该缺陷不是第 220 轮引入**，属历史遗留。

---

## 1. 环境与线程

| 项 | 值 |
|---|---|
| AVD | `cardesk28`，`emulator-5560` |
| 系统 | Android 9 / API 28 / arm64-v8a |
| 屏幕 | 1920×1080 @160dpi |
| 被测包 | `com.ecarx.cardesk` 4296 / 1.0.1200-system |
| 进程 / 主线程 | pid 29268 / TID 29268 |
| 日志写线程 | TID 29293，`cardesk-log-wri` |
| 车控采样线程 | TID 29321，`cardesk-vehicle`（`cardesk-vehicle-signal` 截断） |
| 氛围灯渲染线程 | TID 29348 / 29350，`cardesk-ambient` |
| 频谱线程 | TID 29353，`cardesk-spectru` |
| 系统状态采样 | TID 29320，`cardesk-system-` |

实测 `dumpsys` / `top`：设置页 idle 时 CPU **0.0%**，RES **159M**，Native Heap 47,532 KB，Java Heap 9,912 KB。

---

## 2. 主线程 I/O 与线程分离

### 2.1 按线程的 10 秒增量（`/proc/<pid>/task/<tid>/io`）

| 线程 | `wchar` 增量 | `write_bytes` 增量 | 说明 |
|---|---:|---:|---|
| 主线程 29268 | +12,112 | **0** | 只剩 Logcat 的字符写，无文件块写 |
| 写线程 29293 | +7,418 | **+24,576** | 日志文件块写全部在这里 |
| RenderThread 29296 | +2,229,821 | — | GPU 传输缓冲，不是日志 |
| 氛围灯 29348 | +32,971 | — | 逐帧日志的 Logcat 输出 |

**结论：主线程零文件块写，线程分离成立。** 这比「看代码里有没有 `write()`」更硬：内核计数里主线程的 `write_bytes` 就是 0。

### 2.2 60 秒文件增长（16:18–16:19）

| 项 | 值 |
|---|---|
| 文件 | `/data/data/com.ecarx.cardesk/files/logs/cardesk-20261119-1.txt` |
| 前 | 2,765,182 B |
| 后 | 2,807,933 B |
| 增量 | **+42,751 B = 713 B/s** |

16:08 同一口径为 **+43,288 B = 721 B/s**，两次一致。

---

## 3. 落盘速率与预算

| 项 | 16:08 窗口 | 16:18 窗口 |
|---|---:|---:|
| 文件落盘 | 721 B/s | 713 B/s |
| Logcat 中 CarDesk | 4,635 B/s（1,707 行/60s） | — |
| 落盘 / Logcat | **15.6%** | — |
| 4MB 预算 | ≈97 分钟 | ≈98 分钟 |

- 与报告 738 B/s、15.5%、94.7 分钟一致。
- 导出包 `summary.txt`：`落盘: 已写 17111 行，平均 44 µs/行`。17,111 × 44µs ≈ 0.75s，摊到 61 分钟可忽略；报告 A/B 的 3µs/行是微基准，44µs 是含队列/格式化/刷盘的真实均值，仍远低于旧版 2–5ms/行。

---

## 4. 心跳

16:18 的 32 秒 Logcat，`状态栏时钟刷新` 共 105 行：

| 指标 | 值 |
|---|---|
| 1s ticker 间隔中位数 | **1005.5ms** |
| 最小 / 最大 | 1002 / 1016ms |
| 0.9–1.2s 区间样本 | 44 个 |
| 旧版对照 | 1089–1153ms |

另有一个约 2.5s 一次的刷新源，造成 235/768ms 的交错小间隔；这是第二个刷新源，不是 1s ticker 抖动。**1s 心跳本身稳定，未退化。**

---

## 5. 帧统计

| 场景 | 帧数 | Janky | p50 | p90 | p99 | max | Missed Vsync | High input |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| 单次翻页（含少量 idle） | 41 | 2（4.88%） | 8 | 13 | 18 | 18 | **0** | 0 |
| 纯 idle 8s | 22 | 2（9.09%） | 7 | 12 | 17 | 17 | **0** | 0 |
| 12 次连续翻页（0.35s 间隔） | 321 | 39（12.15%） | 9 | 17 | 26 | 32 | **0** | 0 |
| 报告（12 次） | 93 | 3（3.23%） | — | — | 20 | 20 | **0** | 0 |
| 累计（reset 前，约 55 分钟） | 9,333 | 871（9.33%） | 9 | 16 | 32 | — | 30 | 7 |

**解读：**

- 单次翻页的 p99 18ms / max 18ms / 0 missed，与报告 p99 20ms / max 20ms / 0 missed 基本一致；翻页动画本身没有明显退化。
- 12 次连续翻页的 12.15% 高于报告 3.23%，但报告 93 帧 ≈ 260ms × 30fps × 12，本次 321 帧 ≈ 60fps 下每次约 26.75 帧（含 swipe 前后的 idle），**采样帧率和滑动间隔都不同**，百分比不可直接对比。
- 12 次连续翻页仍 **0 Missed Vsync、max 32ms**，没有出现 >32ms 的长帧；就「可感知卡顿」而言，不构成回归。
- 若要把 3.23% 作为验收硬指标，需要用同一脚本、同一帧率、同一间隔对 4295/4296 做 A/B；本次没有做 4295 对照，不能把 12.15% 归因于第 220 轮。

---

## 6. 诊断包（P1 缺陷，历史遗留）

### 6.1 包内容

`/sdcard/Download/CarDesk/cardesk-logs-20261119-161613.zip`，120,284 B：

| 文件 | 大小 | 说明 |
|---|---:|---|
| `summary.txt` | 19,058 B | 设备/车控/日志统计 |
| `logs/cardesk-20261119-0.txt` | 781,480 B | 旧会话 14:53:10–15:14:30（4295 写的） |
| `logs/cardesk-20261119-1.txt` | **缺失** | 当前会话，2,727,624 B |

### 6.2 新增字段（已确认存在）

```text
车控信号采样: 采样 3561 轮 · 最近 0ms · 峰值 53ms · 快照年龄 912ms
落盘: 已写 17111 行，平均 44 µs/行
```

两行都真实反映了第 220 轮的改动：采样 3,561 轮 ≈ 1Hz × 59 分钟；最近 0ms 说明主线程读快照；峰值 53ms 是后台采样线程的耗时；平均 44µs/行是真实落盘均值。**字段本身验收通过。**

### 6.3 当前会话日志为什么没进包

`app/src/main/java/com/cardesk/launcher/core/log/Diagnostics.kt`：

```kotlin
// :67
private const val PACK_LOG_BYTES = 2L * 1024 * 1024

// :137-138
for (f in all.asReversed()) {              // 从最新往回装
    if (pickedBytes + f.length() > PACK_LOG_BYTES) continue
    ...
}
```

当前日志 2.7MB > 2MB，被 `continue` **跳过**；于是回退去装 781KB 的旧文件。`:609` 的文案却写「丢弃了最老的 1 个文件」，实际丢的是最新文件。

**影响：** 当前会话超过约 2MB（约 49 分钟后）导出的包一定不含当前会话，实车排障拿不到刚发生的问题。这是阻塞性的支持缺陷。`git blame` 显示该循环来自 2026-10-28 的 `22d5b75`，**不是第 220 轮引入**；第 220 轮新增的是两行性能字段，字段本身已通过。建议下一轮优先修：

1. 最新文件超过上限时，取**文件尾部** `PACK_LOG_BYTES`（保留最后 2MB），而不是跳过；
2. 或者把上限提高到能覆盖当前会话，并保留总包体上限；
3. 文案改成「当前日志过大，已保留最后 N MB」/「丢弃了最旧的 N 个文件」，与实际行为一致。

---

## 7. 单文件 512KB 轮换（历史缺陷）

`app/src/main/java/com/cardesk/launcher/core/log/FileLogSink.kt`：

```kotlin
// :85
const val MAX_FILE_BYTES = 512L * 1024

// :225
val out = stream ?: openTarget() ?: return
...
// :240
writtenBytes += bytes.size
```

`writeOne()` 在常驻句柄期间**从不检查** `writtenBytes >= MAX_FILE_BYTES`；`openTarget()` 的 `writtenBytes < MAX_FILE_BYTES`（`:292`）只在 `stream == null` 时执行。⇒ 一旦打开，文件可以一直长到 4MB 总上限。

实测：

| 文件 | 大小 | 写入者 | 是否 >512KB |
|---|---:|---|---|
| `cardesk-20261119-0.txt` | 781,480 B | 旧包 4295（14:53–15:14） | **是** |
| `cardesk-20261119-1.txt` | 2,807,933 B @16:19 | 当前 4296 | **是** |

`-0.txt` 是旧包写的，所以**这不是第 220 轮引入的回归**；但第 220 轮重写了 `FileLogSink`，没有顺手修掉。

到 4MB 时 `trim()` 会从最旧开始删；如果当前文件单独超过 4MB，会把正在写的文件也删掉（`FileLogSink.trim()` 检测到 current 被删后 `closeStream()`/`current=null`，下次重开）。表现为「到 4MB 一次性丢光」，而不是 8×512KB 轮转。

建议修法：在 `writeOne()` 写之前判断 `writtenBytes >= MAX_FILE_BYTES`，若成立则 `closeStream()` + `openTarget()`，再写；同时保留 `trim()` 的总量兜底。

---

## 8. 稳定性

| 项 | 结果 |
|---|---|
| `logcat -b crash` | 空 |
| FATAL | **0** |
| ANR in cardesk | **0** |
| Davey | **0** |
| Skipped frames | **0** |
| 已捕获 Exception | 68 条 |
| 其中主要 | `CarDesk/Theme 建设备失败 ClassNotFoundException: com.ecarx.xui.adaptapi.device.Device`，约每分钟 1 条；另 1 条 `CarPropertyManager ... ClassNotFoundException: android.car.Car` |

Theme 的 `ClassNotFoundException` 是 AVD 底座没有 `com.ecarx.xui.adaptapi.device.Device` 导致的降级日志，已被捕获，不影响桌面；实车有该 API 时不会出现。**不构成验收失败。**

---

## 9. 验收判定

| 验收项 | 判定 | 依据 |
|---|---|---|
| 主线程零文件块写 | ✅ 通过 | 主线程 `write_bytes=0`；写线程 +24,576 B/10s |
| 车控采样搬后台 | ✅ 通过 | `cardesk-vehicle` 线程在位；主线程读 0ms；峰值 53ms 在后台 |
| 落盘降速 | ✅ 通过 | 713–721 B/s，Logcat 的 15.6% |
| 4MB 预算 | ✅ 通过 | ≈97–98 分钟 |
| 心跳恢复 1s | ✅ 通过 | 中位数 1005.5ms，1002–1016ms |
| 单次翻页帧指标 | ✅ 通过 | 4.88% / p99 18ms / max 18ms / 0 missed |
| 连续翻页帧指标 | ⚠️ 基本通过 | 12.15% / p99 26ms / max 32ms / **0 missed**；3.23% 口径未复现，需同口径 A/B |
| 诊断包两行新字段 | ✅ 通过 | 两行都在，数值自洽 |
| 诊断包含当前会话 | ❌ 不通过（历史） | 最新 2.7MB 日志被 `continue` 跳过；文案方向也反 |
| 512KB 单文件轮换 | ❌ 不通过（历史） | 当前 2.8MB；旧包 4295 的 `-0` 也 781KB，非 220 引入 |
| FATAL / ANR | ✅ 通过 | 均为 0 |

**总判定：性能/卡顿主目标通过；第 220 轮可以按性能优化交付。诊断包导出缺陷建议作为 P1 修复后再用于实车排障；512KB 轮换作为历史遗留一并修。**

---

## 10. 复现命令（节选）

```bash
# 环境
adb -s emulator-5560 shell getprop ro.build.version.sdk          # 28
adb -s emulator-5560 shell dumpsys package com.ecarx.cardesk | grep versionName

# 线程 I/O（10 秒前后各取一次）
adb -s emulator-5560 shell 'for t in 29268 29293; do cat /proc/29268/task/$t/io; done'

# 落盘速率（60 秒）
adb -s emulator-5560 shell 'stat -c %s /data/data/com.ecarx.cardesk/files/logs/cardesk-*.txt'

# 心跳
adb -s emulator-5560 logcat -d -v threadtime | grep 状态栏时钟刷新

# 帧统计（先 reset，再跑 /data/local/tmp/swipe12.sh）
adb -s emulator-5560 shell dumpsys gfxinfo com.ecarx.cardesk reset
adb -s emulator-5560 shell sh /data/local/tmp/swipe12.sh
adb -s emulator-5560 shell dumpsys gfxinfo com.ecarx.cardesk

# 诊断包
adb -s emulator-5560 shell ls -l /sdcard/Download/CarDesk/
adb -s emulator-5560 pull /sdcard/Download/CarDesk/cardesk-logs-20261119-161613.zip /tmp/r220.zip
```

---

## 附：与报告数字的差异

| 项 | 报告 | 本次 | 说明 |
|---|---|---|---|
| 落盘 | 738 B/s | 713–721 B/s | 一致 |
| 落盘/Logcat | 15.5% | 15.6% | 一致 |
| 4MB 预算 | 94.7 min | ≈97–98 min | 同一量级 |
| 心跳 | 1002–1010ms | 1002–1016ms（中位 1005.5） | 一致 |
| 单次翻页 | p99 20 / max 20 / 0 missed | p99 18 / max 18 / 0 missed | 一致 |
| 12 次翻页 | 93 帧 / 3.23% | 321 帧 / 12.15% / 0 missed | 帧率与间隔口径不同，不可直接比 |
| 导出包 | 68,922 B | 120,284 B | 本次日志更多；但当前会话缺失 |
| 采样 | 93 轮 / 峰值 6ms | 3,561 轮 / 峰值 53ms | 运行时长不同；峰值仍在后台 |
| 落盘均值 | 31 µs/行 | 44 µs/行 | 同一量级，远低于旧版 2–5ms |

---

*本报告为独立复测，未修改代码、未提交 Git。*
