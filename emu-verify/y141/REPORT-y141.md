# 第141轮 模拟器验证报告：API 28 上画中画**真嵌入**（启动之后把 task 搬到虚拟屏）

> 结论先行：
> ① **第140轮的结论错了**——用户指出「你提供的截图里，**高德是全屏的**」，**用户是对的**。
>    重新取证（`dumpsys window windows` 的 `mDisplayId`）后确认：API 28 上承载应用
>    **一直落在主屏**（`mDisplayId=0`），画中画那块是空的。
> ② **但代码没问题**：**4237 在真机上画中画是好的** → 是 **AOSP 9 官方模拟器与车机 ROM 的差异**
>    （AOSP 9 的 AMS 不肯把新建的 task 放到请求的 display 上）。
> ③ 修法：**启动路径一个字不改**，启动成功之后**补一脚**把 task 搬到我们的虚拟屏
>    （AOSP 9 的 `IActivityManager.moveStackToDisplay`）→ **embedtest 实测真嵌入**。
> ④ 顺带修掉一个**真 bug**：第130轮的黑屏判据在 API 28 上**每轮看门狗都抛
>    `NoSuchFieldError`**，从来没生效过。

## 一、环境

| 项 | 值 |
|---|---|
| 模拟器 | `emulator-5560`，AVD `cardesk28`，**API 28 / Android 9**（AOSP default 镜像，平台签名） |
| 分辨率 | 1920×1080 @ 160dpi（虚拟屏 906×867 @160dpi） |
| 被测包 | 平台签名 system 包（`sharedUserId=android.uid.system` → **uid 1000**） |
| 承载应用 | `com.cardesk.embedtest`（第140轮新增的**干净**测试承载应用） |
| 对照 | `emulator-5556`，API 29 / Android 10（只作回归对照） |

> **车机同版本**：领克车机跑的就是 Android 9（API 28）。

## 二、更正：第140轮用的判据是错的

第140轮拿这个判「进了虚拟屏」：

```
dumpsys activity activities:
  Display #13 (activities from top to bottom):
    ResumedActivity: ActivityRecord{… com.autonavi.amapautp.yh/…MainMapActivity t30}
```

**这一行不能证明嵌入**：它会出现在虚拟屏那一段，而**窗口可能仍在主屏上**。

**权威判据**（第141轮确立）：

```bash
# ① 窗口到底在哪个屏
adb -s emulator-5560 shell dumpsys window windows \
  | grep -A2 "Window{.*embedtest" | grep -E "Window\{|mDisplayId"
# ② 虚拟屏上到底有没有窗口（token 列表为空 = 没嵌进去）
adb -s emulator-5560 shell dumpsys window displays \
  | grep -A6 "Display: mDisplayId=2[0-9]"
# ③ 栈归属
adb -s emulator-5560 shell am stack list | grep -B1 embedtest
```

**修复前实测**：

```
Window{… com.autonavi.amapautp.yh/com.autonavi.amapauto.MainMapActivity}:
    mDisplayId=0 stackId=30          ← 主屏！不是虚拟屏
Window{… com.cardesk.embedtest/com.cardesk.embedtest.EmbedTestActivity}:
    mDisplayId=0 stackId=10          ← 同样在主屏（还抢了焦点）
Display: mDisplayId=21
  Application tokens in top down Z order:
    （空）                          ← 虚拟屏上一个窗口都没有
```

> 第140轮那张截图之所以「看着像嵌进去了」：导航页的地图卡是**透明洞**，
> 透出的是**背后主屏上全屏运行的承载应用**。

## 三、根因：ROM 差异，不是代码问题

**车机 ROM 会把 task 放到请求的 display 上，AOSP 9 官方模拟器不会。**

AOSP 9（`android-9.0.0_r1`）`ActivityStackSupervisor.findTaskLocked(r, displayId)`：

```java
for (int displayNdx = mActivityDisplays.size() - 1; displayNdx >= 0; --displayNdx) {
    …
    if (mTmpFindTaskResult.r != null) {
        if (!mTmpFindTaskResult.matchedByRootAffinity) {
            return mTmpFindTaskResult.r;
        } else if (mTmpFindTaskResult.r.getDisplayId() == displayId) {
            affinityMatch = mTmpFindTaskResult.r;   // ← 只有这一支才「优先」目标 display
        }
    }
}
return affinityMatch;     // ← 跨屏的 affinity 匹配**照样**返回
```

→ **目标 display 只是「优先」，不是「限定」**；
新建 task 时 AMS 又会退回默认显示器 → 承载应用**全屏抢屏**。

**同一个 AMS 的交叉验证**（证明「AMS 有能力放，是怎么放的问题」）：

```bash
$ adb shell am stack start 21 -n com.cardesk.embedtest/.EmbedTestActivity
  → displayId=21 stacks=1                     ✓ 服务层显式指定 display → 落点正确
$ adb shell dumpsys window windows | grep -A2 "Window{.*embedtest"
    mDisplayId=0                               ✗ 我们自己的启动（带 launchDisplayId）→ 落回主屏
```

**API 28 上没有 `moveTaskToDisplay`**（设备 framework 里数过字符串，命中 **0** 次；
`moveStackToDisplay` 命中 2 次）—— `moveTaskToDisplay` 是 AOSP 10 才有的。

## 四、实现：启动**之后**补一脚

启动路径（顺序、旗标、Intent）**一个字没改**，保持 4237 真机验证过的顺序：

```
startActivity(PendingIntent) → startActivityForResult(Intent, int) → startActivity(Intent)
```

> 第140轮曾把 API 28 改成「先 `startActivity(Intent)`」—— **第141轮已回退**。
> `tools/verify_y141_reverse.sh` 的**反向 1 项**专门盯这条红线。

启动成功之后：

| 情况 | 动作 |
|---|---|
| task **已经在**我们的虚拟屏上 | **什么都不做**（车机上的常态 → 零行为变化） |
| task **不在** | `moveStackToDisplay(stackId, 我们的虚拟屏 id)` 搬过来 |
| **不知道** | **不搬**（宁可不动，也不能误搬一个本来就在对地方的 task） |

### 「在不在我们的屏上」这条判据（API 28 上不能靠 displayId）

第141轮把设备上 `RunningTaskInfo` 的**真实字段**打了出来：

```
baseActivity, configuration, description, id, lastActiveTime, numActivities,
numRunning, resizeMode, stackId, supportsSplitScreenMultiWindow, thumbnail, topActivity
```

**AOSP 9 的 `RunningTaskInfo` 连 `displayId` 都没有**（AOSP 10 才加）！
→ 第130轮那套 `task.displayId == 我们的 id` 的判据在 API 28 上**从来就读不到值**。

改用**第二套证据**：任务的**显示配置**（`RunningTaskInfo.configuration`）的**宽度**：

| 任务配置宽度 | 结论 |
|---|---|
| 跟我们的虚拟屏一样（906） | **在** → 不搬 |
| 跟主屏一样（1920） | **不在** → 搬 |
| 两边都不像 | **不知道** → 不搬 |

**为什么只比宽度**：三个高度口径互相对不上
（屏 1080 / app 区 968 / 任务配置 944 —— 差的是状态栏、导航栏）；
**宽度不受系统装饰影响**（1920 vs 906）。

## 五、顺带修掉的真 bug：`baseIntent` 让第130轮的判据**从来没生效过**

`PipSurface.isTaskOnOurDisplay()` 原来写着：

```kotlin
it.baseActivity?.packageName == pkg || it.baseIntent.getPackage() == pkg
```

`RunningTaskInfo.baseIntent` 在 **AOSP 9 里不存在**（API 29 才移除），
而我们的编译目标更高 → Kotlin 编译成**直接的字段访问** → 运行即：

```
java.lang.NoSuchFieldError: No field baseIntent of type Landroid/content/Intent;
    in class Landroid/app/ActivityManager$RunningTaskInfo
    at com.cardesk.launcher.ui.pip.PipSurface.isTaskOnOurDisplay(PipSurface.kt:748)
```

只要任务列表里有**别的包**（第一个条件不成立）就会走到第二个条件 → **抛异常**
→ **每轮看门狗都失败** → 「画中画黑屏自愈」在车机（API 28）上**等于没有**。

修复后（API 28 实测）：`看门狗异常` **0** 次、`baseIntent` 报错 **0** 次。

## 六、验证结果

### 6.1 embedtest：**真嵌入** ✓

```
$ adb shell dumpsys window windows | grep -A2 "Window{.*embedtest"
  Window{… com.cardesk.embedtest/com.cardesk.embedtest.EmbedTestActivity}:
      mDisplayId=19 stackId=47          ← 虚拟屏！

$ adb shell am stack list | grep -B1 embedtest
  Stack id=47 bounds=[0,0][906,867] displayId=19
    taskId=…: com.cardesk.embedtest/…EmbedTestActivity bounds=[0,0][906,867] visible=true

$ adb shell dumpsys window displays | grep -A6 "Display: mDisplayId=19"
  Application tokens in top down Z order:
    taskId=… → appTokens=[… EmbedTestActivity …]      ← 虚拟屏上真的有窗口
```

桌面日志：

```
承载任务快照 pkg=com.cardesk.embedtest our=19 task=… stack=… display=null
    size=1920x944 ourSize=906x867 mainSize=1920x1080
承载任务搬到我们的虚拟屏 pkg=com.cardesk.embedtest api=STACK_TO_DISPLAY to=19
承载任务搬移完成 pkg=com.cardesk.embedtest reason=已搬到我们的虚拟屏
```

截图：`emu-verify/y141/y141-01-embedtest-embedded.png`
—— 导航页左边的画中画里显示的正是 embedtest 的画面（906×867 @160dpi）。

### 6.2 高德：模拟器上追不上（**不是我们的代码问题**）

高德进程会**自己**起 `MainMapActivity`：

```
ActivityManager: START u0 {flg=0x10000000 cmp=com.autonavi.amapautp.yh/…MainMapActivity} from uid 10073
```

这个启动**不带** displayId → AOSP 9 的 AMS 把它放回主屏 → 我们的搬移追不上它的节奏
（我们的搬移是「启动之后 5 次、最长 5 秒」的窗口）。
这是「**高德 + AOSP 9**」的组合行为；**车机 ROM 上没有这个现象**（用户实测高德画中画正常）。

→ **模拟器上验画中画请用 `embedtest`**（干净、无首启弹窗、不自己起活动）。

### 6.3 反向验证

`tools/verify_y141_reverse.sh`：**10 项全部变红**，还原后基线 **0 红灯**。

| # | 反向项 | 抓到的问题 |
|---|---|---|
| 1 | 启动顺序改成「先 `startActivity(Intent)`」 | **偏离 4237 真机验证过的路径** |
| 2 | 启动后不再接搬移 | 承载应用留在主屏 = 全屏抢屏 |
| 3 | 搬移走 `moveTaskToDisplay` | API 28 上**没有**这个方法 |
| 4 | 搬移前不判「在不在我们屏上」 | 盲搬会动本来就在对地方的 task |
| 5 | 判据改成「按宽+高整体比」 | 高度口径对不上 → 永远判不出 |
| 6 | 两边都不像时当成「不在」 | 会搬一个本来就在对地方的 task |
| 7 | 把 `baseIntent` 放回去 | API 28 上每轮看门狗都崩 |
| 8 | 搬移期间不再暂缓判黑 | 看门狗会误判黑屏并重启承载应用 |
| 9 | 核心逻辑引入 `android.*` | 进不了 JVM 单测 |
| 10 | 不再记「读到的真实字段名」 | 读不到时排障瞎猜 |

脚本自身也复核过：断言用**精确文本**并**剥掉行注释**，
「两边都不像 → 判不知道」这条**只能断言代码**（断言注释会被剥掉 → 永远假红，
第141轮第一次跑时踩过，已修）；**编译不过时也当红**处理。

## 七、这一轮**没有**做的

- **没有**改启动顺序/旗标/Intent；
- **没有**在 API 28 上启用「黑屏自愈」判据（`displayId` 读不到 → 仍按「不知道」处理，
  宁可漏判也不能误判**重启用户正在用的应用**）；
- **没有**改画中画其它历史问题（黑屏自愈分级、尺寸变化重载一律照旧）；
- **没有**发 GitHub Release、**没有**传 R2。

## 八、遗留 / 需要真车复验

- **真车（领克，API 28）必须复验**：这一轮改的是「启动之后补一脚」，
  车机上应当**一次都不触发**（task 本来就在我们的屏上）；
  但「判据用的是显示配置宽度」这条在真车上的取值需要确认
  （车机的虚拟屏尺寸与我们模拟器不同）。
- **高德在模拟器上**仍然是全屏抢屏（原因见 6.2）→ 模拟器上用 `embedtest` 验。

## 九、截图

| 文件 | 内容 |
|---|---|
| `emu-verify/y141/y141-01-embedtest-embedded.png` | API 28 上 **embedtest 真嵌在画中画里**（导航页左侧） |
