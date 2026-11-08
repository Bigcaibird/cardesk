# 第132轮 验证报告：启停（ISS）开关接上执行端

> 本轮解决的是一个**存了很久但从没有执行端**的开关：
> 设置 → 车控 → 启停控制 → 「开机后自动关闭启停」，
> 之前只往 SharedPreferences 写一个键，车机上的启停**一点没变**。

## 1. 环境

| 项 | 值 |
|---|---|
| 模拟器 | `emulator-5558`（API 28 / SDK 29 / arm64-v8a / 1920×1080） |
| 包名 | `com.cardesk.launcher`（debug 变体） |
| 车控通道 | **mock**（模拟器上没有厂商 `ICarFunction` 句柄，走 `MockVehicleGateway`） |
| 桌面版本 | 第131轮基线 `b9f99ad`（4230 / 1.0.1134） |

## 2. 根因：开关是「空壳」

```
git log -S issKeepEnabled --oneline
22d5b75   ← 首次提交，全仓只有这一处
```

设置页把开关写进 `iss_keep_enabled`，**全仓没有任何代码读这个键**。
按 `analysis/compare/cardesk-features.md` 的判据，这叫「仅设置项」。

## 3. 文档口径：这个功能**能写**

| 东西 | 值 | 出处 |
|---|---|---|
| 功能号 | `537002240`（`0x20020100`） | `IVehicle.SETTING_FUNC_ENGINE_STOP_START` |
| 读 | `getPaSsActivation` | `CarFunctionValueImpl.java:429` |
| 写 | `setCbSsActivation` | `CarFunctionValueImpl.java:1247` |
| 值域 | `{0, 1}` | `docs/ecarx-adapterapi/data/value-domain.md:32` |
| 支持探针 | `isFunctionSupported` → `PaSsActivation.availability` | `CarFunctionSupportedUtil.java:356` |

参考 App（君君桌面） `com.claunchercard` 的 `DriveModeController` 用的就是同一个功能号
（`:21` 常量、`:273` 读、`:511` 写）⇒ 语义一致：`1` = 开着，`0` = 关着。

另一条看着很像的路走不通：驾驶模式的
`IDriveMode.DM_FUNC_DM_CUSTOM_EN_START_STOP`（`0x22030E00`）在厂商 jar 里**没有实现**。

## 4. 读侧踩到的坑：厂商默认值是 `1`

`CarFunctionValueUtil.getMappedFunctionValue(PaSsActivation)`：

```java
if (pa == null || pa.data == null) {
    Logger.w(TAG, "pa is null, returning defaultValue:1");
    return 1;                       // ← 信号缺失时返回 1，不是错误哨兵
}
```

所以「读到 1」是**二义的**：真开着，或者压根没这条信号、厂商拿默认值 1 兜住了。
`IssControl.State.isOn` 因此判三档：

| raw | supported | 结论 |
| --- | --- | --- |
| 0 | 任意 | **确凿关闭**（信号缺失只会返回 1，能读到 0 就是真关了） |
| 1 | `true` | **确凿开启** |
| 1 | `false` / `null` | **判不了** |

页面状态行也跟着这个口径走：判不了就显示「启停状态读不到」，用弱文字颜色 ——
不能把「读不到」显示成一个看着像「有结论」的绿字。

## 5. 改了什么

| 文件 | 改了什么 |
|---|---|
| `vehicle/IssControl.kt`（新） | 功能号常量、读（支持探针 + 原始值）、写、状态三档判定与文案 |
| `vehicle/VehicleController.kt` | `setIssEnabled(on, onResult)`（写完回读确认，最多等 1.2s）、`readIssState()` |
| `vehicle/MockVehicleGateway.kt` | 模拟车也有启停状态（默认开着），支持探针返回 `true` |
| `vehicle/VehicleLog.kt` | 日志里 `0x20020100` 显示为「发动机启停」（原来「未知属性」） |
| `ui/SettingsOverlay.kt` | 开关打开→**立刻**关掉启停；关掉→**不动车机**；加状态行 + 「立即关闭/开启启停」+「刷新状态」 |
| `BootReceiver.kt` | 开机广播里按 `iss_keep_enabled` 应用一次（等通道就绪，最多 30s） |

**「关闭则不改动车机逻辑」是照副标做的**：开关关掉时只改本地开关，不往车机写任何东西；
想改回来用页面上的「立即开启启停」。

**开机那条为什么要自己 init 车控**：`VehicleController.init` 平时是 `LauncherView` 建的，
而 LauncherView 依附于 MainActivity —— Android 10+ 禁止后台启动 Activity，
开机广播里那次 `startActivity` **可能失败**。桌面起不来 = 车控通道永远不初始化
= 启停永远关不掉。所以 `BootReceiver` 主动 init 一次
（`init` 本身幂等，`if (initialized) return`，后面 LauncherView 再调一次没有副作用）。

## 6. 模拟器端到端验证

日志：`r132-logcat-iss.txt`；截图：`r132-iss-page.png`（页面）、
`r132-iss-closed.png`（已关闭状态）、`r132-iss-off-confirmed.png`（关闭后回读确认）。

| # | 操作 | 期望 | 实测 |
| --- | --- | --- | --- |
| 1 | 打开「开机后自动关闭启停」 | 立刻写 `0` 并回读确认 | ✅ `设置变更 key=iss_keep_enabled from=false to=true` → `写入启停状态 func=0x20020100 value=0 target=关闭 ok=true` → `confirmed=true` → 回读 `raw=0 isOn=false` |
| 2 | 点「立即开启启停」 | 写 `1` 并回读确认 | ✅ `value=1 target=开启 ok=true` → `confirmed=true` → 回读 `raw=1 isOn=true` |
| 3 | 点「立即关闭启停」 | 写 `0` 并回读确认 | ✅ `value=0 target=关闭 ok=true` → 回读 `raw=0 isOn=false` |
| 4 | 关掉开关 | **只改本地开关，不碰车机** | ✅ 只有 `设置变更 key=iss_keep_enabled from=true to=false`，**无任何写入** |
| 5 | 开机广播（开关开） | 开机自动关一次启停 | ✅ `收到开机广播` → `写入启停状态 value=0 ok=true` → `开机自动关闭启停 ok=true detail=已关闭启停` |
| 6 | 开机广播（开关关） | 开机不碰车机 | ✅ 只有 `收到开机广播` / `开机广播已拉起桌面`，**无启停写入** |

关键日志原文（第 4 行与第 5 行是「写 + 回读确认」这一对，能看出 `confirmed=true`）：

```
11-07 11:59:42.737 I CarDesk/Vehicle: 写入启停状态 func=0x20020100 value=0 target=关闭 ok=true reason=null
11-07 11:59:42.737 I CarDesk/Vehicle: 启停写入已下发 target=关闭 written=true confirmed=true channel=mock reason=null
11-07 11:59:42.762 D CarDesk/Vehicle: 读取启停状态 func=0x20020100 supported=true raw=0 isOn=false
```

## 7. 单测

| 项 | 值 |
|---|---|
| 全量单测 | **1765 项全绿**（第131轮 1750 → +15） |
| 新增单测 | `vehicle/IssControlTest.kt`（15 项，含「读到 1 但信号缺失 ⇒ 判不了」这条） |

## 8. 已知限制（**真车还没验**）

1. **模拟器验的是通道与逻辑，不是真车的启停行为** ——
   真车能不能真的关掉启停、写进去有没有被整车状态机接受，
   要在真车上看这几行日志：`写入启停状态` / `启停写入已下发`（`confirmed` 是不是 `true`）/ `开机自动关闭启停`。
2. **只写一次可能不够** —— 启停功能通常在**每次上电/点火后**由整车状态机复位，
   只在开机广播里写一次 `0`，用户中途熄火再点火后可能又被打开。
   稳妥做法是盯 `PaSsActivationChangedCallback`（jar 里有），状态被改回 `1` 时再补写一次 ——
   这一版**没做**。
3. **`confirmed=false` 不等于失败** —— 厂商写接口返回 `true` 只代表信号收下了，
   回读确认不了时页面会明说「已下发启停指令，但这台车机回读没确认」，不谎报成功。
4. **`ENGINE_START_STOP_STATE` 传感器事件（`0x00201700`）在 jar 里未实现**，
   所以「当前启停运行状态」只能靠 `getPaSsActivation` 这条设置侧的路。
5. 本轮**没做 GitHub Release、没传 R2**（等明确指示）。
