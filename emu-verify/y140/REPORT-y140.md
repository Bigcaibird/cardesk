# 第140轮 模拟器验证报告：API 28 上画中画承载链路 + 黑屏判据修复

> ⚠️ **第141轮更正（必读）**：下面 ① 里的
> 「**高德的画中画是正常的**」这个结论**是错的**。
>
> 用户当时指出：「你提供的截图里，**高德是全屏的**」—— **用户是对的**。
> 第141轮用**权威判据**（窗口的 `mDisplayId`）重新取证后确认：
>
> ```
> Window{… com.autonavi.amapautp.yh/com.autonavi.amapauto.MainMapActivity}:
>     mDisplayId=0 stackId=30          ← 主屏！不是虚拟屏
> ```
>
> 本报告当时用的 `dumpsys activity activities` 里的 `ResumedActivity`
> **不能**判定嵌入（它会出现在虚拟屏段，但窗口可能仍在主屏）；
> 权威判据是 `dumpsys window windows` 的 `mDisplayId` +
> `dumpsys window displays` 的 token 列表。
> 根因是 **AOSP 9 的 AMS 不肯把新建的 task 放到请求的 display 上**
> （车机 ROM 会）—— **ROM 差异，不是代码问题**（4237 在真机上是好的）。
> 详见 `emu-verify/y141/REPORT-y141.md`。
>
> 下面 ②③ 两条仍然成立。

> 结论先行：
> ① **车机就是 Android 9 / API 28**（与模拟器同版本）——
>    ~~实测在 API 28 模拟器上高德的画中画是正常的~~
>    **第141轮更正：不是正常的。** 当时那个判断用错了判据，
>    详见下面的更正框与 `emu-verify/y141/REPORT-y141.md`；
> ② 但顺着这条线查出一个**真 bug**：画中画黑屏的权威判据在 API 28 上**恒为「不知道」**，
>    也就是「黑屏自愈」那一整级在车机上等于没生效 —— 这一轮修好了；
> ③ 顺带修掉一个隐患：启动承载应用时我们自己造的那份 `ActivityOptions`
>    **没带 `launchDisplayId`** → 某些 ROM 上承载应用会落回主显示器（全屏抢屏）。

## 一、环境

| 项 | 值 |
|---|---|
| 模拟器 | `emulator-5560`，AVD `cardesk28`，**API 28 / Android 9**（AOSP default 镜像，平台签名） |
| 分辨率 | 1920×1080 @ 160dpi（承载面 1287×867，导航页留白后 1920×968） |
| 被测包 | 平台签名 system 包（`sharedUserId=android.uid.system` → **uid 1000**） |
| 承载应用 | `com.autonavi.amapautp.yh`（画中画主画）+ `com.cardesk.embedtest`（本轮新增的测试承载应用） |
| 对照模拟器 | `emulator-5556`，API 29 / Android 10（只作回归对照） |

> **车机同版本**：领克车机跑的就是 Android 9（API 28），
> 所以这一轮把模拟器口径**统一到 API 28**（`tools/emu28.sh` 起停，端口 5560）。

## 二、本轮修的真 bug：`virtualDisplayIdOf()` 在 API 28 上恒取不到 id

### 2.1 症状：判据「静默失效」

画中画黑屏的**权威判据**是 AOSP 官方 `VirtualDisplay Launcher` 示例那套：

```
task.displayId == virtualDisplay.getDisplayId()
```

前半句（承载应用的 task 在哪个 display 上）我们能读到；
后半句（**我们自己的** VirtualDisplay 是哪个 id）——
`ActivityEmbed.virtualDisplayIdOf()` 在 **API 28 上恒返回 `null`**。

后果：**「黑屏自愈」那一整级在 API 28（= 车机）上等于没有**。
更糟的是它**静默失效**：取不到就 `return null`，日志上一行都没有 ——
这种故障最难查。

### 2.2 根因（AOSP 9 源码 + 实测双重证据）

AOSP 9（`android-9.0.0_r1`）的 `core/java/android/app/ActivityView.java`：

```java
import android.hardware.display.VirtualDisplay;
...
private VirtualDisplay mVirtualDisplay;
...
private ActivityOptions prepareActivityOptions() {
    final ActivityOptions options = ActivityOptions.makeBasic();
    options.setLaunchDisplayId(mVirtualDisplay.getDisplay().getDisplayId());
    return options;
}
```

关键：`android.hardware.display.VirtualDisplay` **不是** `android.view.Display`
（它是个 final 类，里面包着一个 `Display`，只有 `getDisplay()` 能拿到）。
旧代码只认 `android.view.Display` → **API 28 上永远取不到 id**。

API 29 的 `ActivityView`（AOSP 10）也是这个结构 —— 所以这不是 28 独有，
只是 28/29 上我们恰好还能从别的分支蒙对。

### 2.3 修法

新�� `core/VirtualDisplayIdResolver.kt`（纯逻辑，可 JVM 单测）：

- `displayIdOfVirtualDisplayLike(obj)` —— 两种形态都认：
  本身是 `Display` 就直接取；是个**壳**就先 `getDisplay()` 再取（**API 28 的形态**）；
- `looksLikeVirtualDisplay(obj)` —— 类名（**简单类名**）带 `VirtualDisplay`，
  或有 `getVirtualDisplayId() > 0`；
- `pick(candidates, ownDisplayId)` —— 取舍规则（这一轮的「为什么」都在这里）：
  - 只认**已确认是虚拟显示器**的候选（第130轮的坑：`mHost.mDisplay` 是**桌面那块屏**，
    认了它 → 跟任何 task 都对不上 → 把正常画面判成黑屏并误重启了承载应用）；
  - `displayId <= 0` 丢弃（`0` 是主显示器）；
  - 等于**我们自己窗口所在 display** 的丢弃（车机是多屏的）；
  - 一个都拿不准 → `null`（**宁可漏判也不能误判**，误判的代价是重启用户正在用的应用）。

Android 侧只负责「探」，取舍全在纯逻辑里。

### 2.4 实测（API 28）

```
21:25:02.796 D CarDesk/NavUI: 承载面首次就绪 pkg=com.cardesk.embedtest times=1 vid=15
21:25:02.802 I CarDesk/NavUI: 承载面就绪，启动承载应用 pkg=com.cardesk.embedtest by=surface w=1287 h=867
21:25:02.819 I CarDesk/NavUI: ActivityView 启动承载应用 pkg=com.cardesk.embedtest api=pendingIntent
```

`vid=15` —— 修复前这里是 `null`。系统侧对得上：

```
ActivityTaskManager: START u0 {… pkg=com.autonavi.amapautp.yh cmp=…/UsbFillActivity} from uid 1000
dumpsys activity activities:
  Display #15 (activities from top to bottom):
    ResumedActivity: ActivityRecord{… com.autonavi.amapautp.yh/com.autonavi.amapauto.MainMapActivity t30}
```

API 29 对照：`承载面首次就绪 … vid=77`，高德的 task 同样 Resumed 在 `Display #77`。

## 三、更正：之前「API 28 上高德进不了画中画」的结论是**错的**

第140轮开工时的判断是「AOSP 9 会拒绝把高德放进虚拟屏」，理由是
`ActivityStackSupervisor.canPlaceEntityOnDisplay()` 那条 `resizeable` 校验。
**实测推翻了它**：

| 观测 | 结果 |
|---|---|
| 我们 App 在模拟器上的身份 | `dumpsys package com.ecarx.cardesk` → **`userId=1000`**、`INTERNAL_SYSTEM_WINDOW: granted=true` |
| AOSP 9 的放行条件 | `isCallerAllowedToLaunchOnDisplay()` **第一步**就查 `INTERNAL_SYSTEM_WINDOW`，拿到就直接 `return true` |
| 高德有没有声明 `allowEmbedded` | **0 次**（但因为上面那条，根本没走到这一检查） |
| 实测 | 高德的 task **Resumed 在我们的 VirtualDisplay 上**，截图里高德就画在导航页里（`y140-01-amap28-embedded.png`） |

也就是说：**能进画中画，靠的是我们自己是 system 身份（uid 1000）**，
不是高德 manifest 里有什么属性。之前的实验之所以看到「落回 Display #0」，
是因为那次是**用 adb（shell 身份）**去启的 —— shell 没有 `INTERNAL_SYSTEM_WINDOW`，
而高德的 manifest 又没有 `allowEmbedded` → 被系统**静默拒绝** → 回落到主显示器。
**这是测试方法的差异，不是产品的差异。**

> 教训写进 `EMULATOR-VERIFY.md`：**验画中画必须从桌面 App 里发起**，
> 不能用 `adb shell am start` 代替 —— 后者是 shell 身份，权限路径完全不同。

## 四、新增：`embedtest`（可被嵌入的测试承载应用）

为什么要有它：高德带一堆首启引导、权限弹窗、导航协议广播，
**做不了「干净的承载目标」**；而且一旦高德自身状态不对，
测出来的现象到底是「我们的链路坏了」还是「高德没准备好」分不清。

`embedtest` 是一个**独立 APK**（`com.cardesk.embedtest`）：

- `android:allowEmbedded="true"` + `android:resizeableActivity="true"`（**activity 级**）；
- 画面是一屏**纯色 + 大号字 + 当前承载面尺寸** → 截图一眼能认出是它；
- **不进正式包**（`tools/release.sh` 只取 `app/build/outputs/apk/system/app-system.apk`，
  反向验证第 14 项专门盯这条）；
- `allowEmbedded` 做成**可切换**（`-PembedtestAllowEmbedded=false`），
  用来做 A/B 实验。

构建：

```bash
gradle :embedtest:assembleDebug                              # 带 allowEmbedded
gradle :embedtest:assembleDebug -PembedtestAllowEmbedded=false  # 不带
```

实测（API 28）：带 `allowEmbedded` 的版本**能**进我们的虚拟屏（`vid=15`），
画面是它自己（`y140-04-embedtest28.png`）。

## 五、第139轮的修复在 API 28 上复验（切日夜 → 承载面搬家）

用高德自己的日夜广播触发（与第139轮同一口径）：

```bash
adb -s emulator-5560 shell am broadcast \
  -a AUTONAVI_STANDARD_BROADCAST_SEND --ei KEY_TYPE 10019 --ei EXTRA_STATE 38
```

桌面侧日志：

```
21:26:02.450 I CarDesk/NavUI: 画中画承载面摘出（VirtualDisplay 保留） pkg=com.cardesk.embedtest role=主画
21:26:02.450 I CarDesk/NavUI: 画中画承载面释放 pkg=com.cardesk.embedtest role=主画 reason=重建前
```

对照系统侧：**task id 与 display id 都没变**（切前切后都是 `TaskRecord b1f5f60 #25` / `Display #15`）
→ **VirtualDisplay 没死 → 承载应用的 task 没被搬走 → 不会被全屏抢屏**。
用 embedtest 走了一遍（截图 `y140-05`），用高德走了一遍（`y140-02/03`），结论一致。

## 六、顺带修的隐患：两参重载的 `ActivityOptions` 没带 `launchDisplayId`

`ActivityView.startActivity(PendingIntent, ActivityOptions)` 这个重载
**用的是调用方传进去的 options**。我们以前传的是
`ActivityOptions.makeCustomAnimation(ctx, 0, 0)` —— **里面没有 `launchDisplayId`**：

- 一旦某个 ROM 上这个重载真的按我们给的 options 来，
  承载应用就会落到**默认显示器** = **全屏抢屏**（第139轮那个症状的同款）；
- 现在改成 `makeBasic()` + `setLaunchDisplayId(我们的 VirtualDisplay id)`；
- 拿不到 id 就不用这条路（宁可换一条，也别把应用丢到主屏）。

单参重载（`startActivity(PendingIntent)`）保持兜底 —— AOSP 的实现内部用
`prepareActivityOptions()`，**一定带 displayId**。

## 七、验证口径汇总

| 项 | 结果 |
|---|---|
| 全量单测 | **1951 项全绿**（1937 → +14，新增 `VirtualDisplayIdResolverTest`） |
| 反向验证 | **14 项全红**（`tools/verify_y140_reverse.sh`），还原后恢复全绿 |
| 脚本自身复核 | 断言用**精确文本**并**剥掉行注释**（XML 另剥 `<!-- -->`）；第 13 项专门把模块声明**注释掉**验证断言不会被注释骗过；编译不过时也**当红**处理（不会假绿） |
| API 28 实测 | **第141轮更正**：`vid` 取到 ✓；切日夜 task 与 display 均不变 ✓；但「高德 / embedtest 均进虚拟屏」**是错的** —— 当时用 `ResumedActivity` 判定，窗口其实在 display 0 |
| API 29 回归 | 高德仍进虚拟屏（`vid=77`），第139轮行为未回归 |

## 八、还没做的 / 待复验

- **真车（领克，API 28）仍需复验**：本轮修的是判据与启动参数，
  真车上是 OEM ROM，`ActivityView` 的内部结构可能与 AOSP 不同
  （所以 `virtualDisplayIdOf` 保留了「公开方法 → 自持 mVirtualDisplay → mHost」多路兜底）；
- **没有**动画中画其它历史问题（黑屏自愈分级、尺寸变化重载一律照旧）；
- **没有**发 GitHub Release、**没有**传 R2（等明确指示）。

## 附：证据文件

| 文件 | 内容 |
|---|---|
| `y140-01-amap28-embedded.png` | API 28 上高德嵌在画中画里（导航页） |
| `y140-02-amap28-before-night.png` | 切日夜前 |
| `y140-03-amap28-after-night.png` | 切到黑夜后（画面连续） |
| `y140-04-embedtest28.png` | 测试承载应用 embedtest 在画中画里 |
| `y140-05-embedtest28-after-night.png` | embedtest 在切日夜后（task/display 不变） |
| `y140-log-api28.txt` | API 28 上的 CarDesk/NavUI 与系统侧日志摘录 |
