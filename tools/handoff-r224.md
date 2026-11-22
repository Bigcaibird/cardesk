# 第224轮交接文档 —— 画中画异常定位与修复

> **本文档用途**：把第224轮（进行中）的全部进展、根因证据、代码位置、修复方案、
> 环境命令一次性交接给接手的开发者（dsh），读完即可直接开工，**不需要重新取证**。
>
> **写于**：2026-11-19 20:41
> **状态**：根因已闭环，**代码尚未改动**，未出包。
> **优先级**：本轮只攻「画中画异常」；视频壁纸右侧裁切问题与按键调试均暂缓（见 §7）。

---

## 0. 一分钟速览

| 项 | 内容 |
|---|---|
| 用户报的现象 | ① 导入视频壁纸后「显示混乱」；② 「画中画的高德跑首页来了」 |
| 本轮目标 | 先修 ② 画中画异常（用户明确指示） |
| 根因 | **两条独立缺陷叠加**，都导致「承载应用被反复重启 → 高德回落到首页」 |
| 缺陷 1 | 像素判黑在**半透明窗口**下必然误判 → 触发承载面重建 → 重启高德 |
| 缺陷 2 | 承载面 surface 重建时**无条件 `startActivity`** 重启承载应用 → 高德回首页 |
| 修复方案 | 已设计完毕（§5），改动量小、边界清楚、可单测 |
| 剩余工作 | 改代码 → 补单测 → 全量单测 → 出包 4301 → 实车复测 → 补文档 |

---

## 1. 背景：这一轮是在什么状态下开始的

第222 / 第223 轮已交付（浮层墨色随壁纸明暗成套切换），基线为 **4300 / 1.0.1204**。
之后用户把**真实车机**通过无线 ADB 接上，要求「把前面需要做的调试都做一下，如按键 key 什么的」，
随即**方向变更**：优先修「车机导入视频壁纸后显示混乱」，按键调试暂缓。

第223 轮已完成且**已交付**的部分不必重做：

- `core/WallpaperContrast.kt`：`Ink` 扩了 `surface` / `surfaceStrong` / `stroke` 三个面 token
- `core/Skin.kt`：`overlayTop` / `overlayInkFor`
- `ui/shell/ShellStatusBar.kt`、`ui/shell/PageDotsView.kt`、`ui/LauncherView.kt` 接线
- 单测基线：**2602 条全绿**
- 交付包：`dist/CarDesk-platform-ecarx-cardesk-4300.apk`
  （SHA-256 `87198e8f9bd5c365b2b46e6d25fb65a4a82c863561687e9eecfd8b1946db56ee`，平台证书指纹校验通过）

---

## 2. 验证环境

| 环境 | 标识 | 说明 |
|---|---|---|
| **实车** | `198.18.50.1:5555` | 领克 01 `dcy11`，Android 9 / API 28 / arm64，1920×1080 @160dpi。**本轮问题只在这里出现** |
| cardesk AVD | `emulator-5560` | AVD `cardesk28`，**唯一验证基准**（日常开发与验收都 here） |
| 领克合并镜像 | `emulator-5570` | 项目基准，可复核 |

实车当前状态：已装 **4300**（`com.ecarx.cardesk`，uid **1000** = system 特权），且为**默认 HOME**。
车机设备端 `base.apk` 的 SHA-256 与 `dist/CarDesk-platform-ecarx-cardesk-4300.apk` **完全一致**。

> ⚠️ **实车不给 adb root** —— 读不到 app 私有目录（`/data/data/com.ecarx.cardesk/`），
> 取证只能靠 **logcat** 或应用内「桌面设置 → 诊断日志」页。

---

## 3. 根因（已闭环，有日志铁证）

### 3.1 缺陷 1 —— 像素判黑在「半透明窗口」下必然误判

**现象链**（实车日志 `/tmp/r223/car/logcat-raw.log`，cardesk 进程 pid 3703）：

```
20:24:10.748  CarDesk/NavUI: 画中画画面采样 maxLuma=0 avgLuma=0 brightPct=0 black=true streak=15
20:24:55.773  CarDesk/NavUI: 画中画画面采样：状态变化 black=false maxLuma=238 avgLuma=39 brightPct=8 streak=0
               note=窗口拷贝看不到 ActivityView 画面（SurfaceView 独立图层）
20:26:08.074  CarDesk/NavUI: 画中画画面采样：状态变化 black=true maxLuma=0 avgLuma=0 brightPct=0 streak=1
20:26:08.863  CarDesk/NavUI: 画中画黑屏自愈：重建承载面 pkg=com.autonavi.amapautp.yh role=主画
               reason=连续黑帧 3 帧        ← ★ 误判触发
20:26:08.864  CarDesk/NavUI: 画中画承载面重建 pkg=com.autonavi.amapautp.yh role=主画
```

**机制**：

1. `ActivityView` 内部是 `SurfaceView`，画面画在**独立的 SurfaceFlinger 图层**上，
   而 cardesk **窗口缓冲**在那个位置是一个**透明的洞**。
2. `PixelCopy.request(window, rect, …)` 拷的是**窗口缓冲**，拷不到那个图层
   → 拷出来的是**透明像素（alpha = 0、RGB = 0）**。
3. 现有 `PipFrameProbe.evaluate()` **只看 RGB 亮度、完全忽略 alpha**
   → 「我们根本看不见应用画面」与「应用画面是黑的」在数据上**完全一样** → **被当成黑屏**。
4. 视频壁纸会把 cardesk 窗口切成 `PixelFormat.TRANSLUCENT`
   （`LauncherView.applyVideoWindowFormat()`，L4037）→ 半透明窗口下这个「洞」更容易变成真透明
   → 采样**恒为 0** → 连续 3 帧 → 触发「重建承载面」→ **重启高德**。
5. 放大器是 `PipBlackHeal.decide()` L186：`blackByPixels = sawContent && consecutiveBlack >= limit`，
   而 `sawContent` 是**粘性**标志（见过一次非黑帧就永远为真，直到 `markHealed()` / `reset()`）
   → **采样环境变了也不会重新验证**。

> 💡 **重要**：`PipFrameProbe` 与 `PipBlackHeal` 的类注释**早就写明**
> 「窗口拷贝看不见 ActivityView 画面」「拿它当判据 = 把正常的高德判成黑屏（实测确实误判并重启了高德）」。
> **设计是对的，降级没落实** —— 修的时候要把注释里承诺的降级真正做出来。

### 3.2 缺陷 2 —— 承载面 surface 重建时无条件重启承载应用

**现象链**（同一份日志）：

```
20:25:10.328  CarDesk/App: onPause
20:25:10.561  DisplayManagerService: Display device changed state: "ActivityViewVirtualDisplay@57660782", OFF
20:25:10.577  CarDesk/NavUI: 承载面销毁 pkg=com.autonavi.amapautp.yh times=1
20:25:12.624  GKAI:GUI: com.estrongs.android.pop（ES文件浏览器）弹出 —— 用户在选视频壁纸文件
20:25:13.168  CarDesk/App: onStop
20:25:20.671  ActivityManager: START u0 {act=MAIN cat=[LAUNCHER] flg=0x10200000
               pkg=com.autonavi.amapautp.yh cmp=com.autonavi.amapautp.yh/com.autonavi.auto.remote.fill.UsbFillActivity}
               from uid 1000                     ← ★ cardesk 发起（铁证）
20:25:20.771  ActivityManager: START u0 {flg=0x20000 cmp=com.autonavi.amapautp.yh/com.autonavi.amapauto.MainMapActivity}
               from uid 10175                    ← 高德自己回落到 MainMapActivity（首页）
20:25:39.331  ActivityManager: START u0 {…UsbFillActivity} from uid 1000   ← 又一次
20:25:41.345  ActivityManager: START u0 {…UsbFillActivity} from uid 1000   ← 又一次
20:25:41.487  ActivityManager: START u0 {…UsbFillActivity} from uid 1000   ← 又一次
```

`from uid 1000` = cardesk 发起 ⇒ **cardesk 自己把高德重启了 4 次**，每次高德都回落到首页。

**机制**：

1. 用户点「导入视频壁纸」→ 系统文件选择器弹出 → **cardesk onPause / onStop**。
2. 桌面不在前台 → `SurfaceView` 的 surface 被销毁 → `ActivityView` 的 VirtualDisplay 变 OFF
   → `ReadyStarter.onSurfaceDestroyed()`。
3. 用户选完文件回到桌面 → surface 重建 → `ReadyStarter.onSurfaceCreated()`
   → 走「重建」分支 → **无条件 `startActivity(view, intent)`** → **高德被重启** → 回首页。

**这就是「画中画的高德跑首页来了」的直接触发点** ——
而且**光是「弹文件选择器」这一个动作就会触发**，与视频壁纸本身无关。

### 3.3 两条缺陷如何合成用户看到的现象

```
用户点「导入视频壁纸」
   │
   ├─► 文件选择器弹出 → cardesk onPause/onStop
   │      └─► 回桌面 → 承载面 surface 重建 → 无条件 startActivity
   │            └─► ★ 高德重启 4 次 → 每次回落 MainMapActivity（首页）  【缺陷 2】
   │
   └─► 视频壁纸生效 → 窗口切 TRANSLUCENT
          └─► PiP 采样恒为透明 → 连续 3 帧「黑」 → 误判黑屏
                └─► ★ 重建承载面 → 再重启高德                        【缺陷 1】
```

---

## 4. 代码位置速查表

| 文件 | 位置 | 问题 |
|---|---|---|
| `ui/pip/PipFrameProbe.kt` | `evaluate()` **L347** | 只看 RGB 不看 alpha；「透明」被当「黑」 |
| `ui/pip/PipFrameProbe.kt` | `sawContent` **L119 / L376** | 粘性标志，环境变了不复核 |
| `ui/pip/PipFrameProbe.kt` | `markHealed()` L293 / `reset()` L276 | 唯一会清 `sawContent` 的地方 |
| `ui/pip/PipBlackHeal.kt` | `decide()` **L171**，判据在 **L186** | `blackByPixels = sawContent && consecutiveBlack >= limit` |
| `ui/pip/PipBlackHeal.kt` | `isTaskOffDisplay()` L141 | 权威判据（纯逻辑，已可单测） |
| `ui/pip/PipSurface.kt` | `checkBlackScreen()` **L666** | 自愈调用点 |
| `ui/pip/PipSurface.kt` | `isTaskOnOurDisplay()` **L745**（private） | **已是正确的权威判据，但被关在 PipSurface 里** |
| `ui/pip/PipSurface.kt` | `checkProcess()` L593，L606 | 已有「桌面不在前台 → 不自愈」保护 |
| `ui/embed/ActivityEmbed.kt` | `ReadyStarter.onSurfaceCreated()` **L550**，重建分支 **L601** | ★ 无条件 `startActivity` |
| `ui/embed/ActivityEmbed.kt` | `startActivity()` **L358** | 三档启动 API 顺序（PendingIntent 优先） |
| `ui/embed/ActivityEmbed.kt` | `virtualDisplayIdOf()` / `displayIdOfTask()` | companion 里已有，可直接复用 |
| `ui/embed/ActivityEmbed.kt` | `suppressNextRelaunch()` L338 | 第139轮「换肤搬家」用的抑制开关，**只抑制一次** |
| `ui/LauncherView.kt` | `applyVideoWindowFormat()` **L4037** | 窗口 TRANSLUCENT / OPAQUE 切换点 |

---

## 5. 修复方案（已设计，改动量小）

### 修复 A —— alpha 闸门：把「看不见」和「黑」分开

**位置**：`ui/pip/PipFrameProbe.evaluate()`

做法：

1. 在统计 RGB 亮度的同时，**额外统计 alpha**：
   - `opaqueRatio` = 不透明像素（`alpha >= 阈值`）占比
2. 若 `opaqueRatio` 低于阈值（建议 0.02，即 64×36 采样图里不足 ~46 个不透明像素）
   ⇒ 判定为**「采样不可用」（blind）**：
   - **不累加 `consecutiveBlack`**（「看不见」不是「黑」）
   - **并清零 `consecutiveBlack`**（连续性被打断 —— 连续黑帧必须是**连续**的）
   - 计入 `consecutiveUnsampleable`，按现有限频规则记一条**独立**的日志
     （区别于现在的「状态变化 black=true」，避免再和真实黑屏混淆）
3. `sawContent` 保持粘性即可 —— 因为「不可用」时 `consecutiveBlack` 恒为 0，像素判黑自然不会成立；
   而窗口切回不透明后若画面真的黑了，连续黑帧能从 0 重新累积，自愈照常工作。

**效果**：半透明窗口（视频壁纸）下像素判黑**永不成立** → 不再误触发「重建承载面」→ 不再重启高德。
权威判据（task displayId）不受影响，真黑屏照样能自愈。

> **纯逻辑可测**：把「不透明像素占比 → 是否判黑 / 是否计入连续黑帧」抽成纯函数放 `core/`，便于 JVM 单测。

### 修复 B —— 承载面重建时，先问「task 还在不在虚拟屏上」

**位置**：`ui/embed/ActivityEmbed.kt` 的 `ReadyStarter.onSurfaceCreated()`

做法：

1. 把 `PipSurface.isTaskOnOurDisplay()` 的能力**下沉到 `ActivityEmbed` 的 companion**
   （`virtualDisplayIdOf()` / `displayIdOfTask()` 本来就在那儿），
   `PipSurface` 改为调用它 —— 避免逻辑分叉。
2. 新增一个 4 态结果（纯逻辑枚举放 `core/`，便于单测）：

   | 状态 | 含义 | surface 重建时是否重启承载应用 |
   |---|---|---|
   | `ON_OUR_DISPLAY` | 承载应用的 task 还在我们的 VirtualDisplay 上 | **不重启**（画面自己回来；VirtualDisplay 复用、task 没死） |
   | `OFF_OUR_DISPLAY` | task 跑到别的屏上去了 | 重启 |
   | `NOT_FOUND` | 找不到承载应用的 task（被系统回收了） | 重启 |
   | `UNKNOWN` | 拿不到 displayId / 看不到别的任务，判不准 | **不重启** |

3. `UNKNOWN` 选择「不重启」的理由（写进注释）：
   > 「判不准就当没黑」—— 误判的代价是**把用户正在用的应用重启**（比一直黑更烦人）。
   > 另有**进程看门狗**兜底（进程没了 → `relaunch("watchdog")`）。

4. `ReadyStarter.onSurfaceCreated()` 的重建分支改为：

   ```
   val state = ActivityEmbed.hostTaskState(view, pkg)
   if (state == ON_OUR_DISPLAY || state == UNKNOWN) {
       Logx.i("承载面重建：承载应用的 task 仍在虚拟屏上，不重启承载应用")
       return          // ← 不再 startActivity
   }
   handler.post { startActivity(view, intent) }   // 保持原行为
   ```

> **注意**：`HostTaskMigration.isMigrating(pkg)` 期间须按 `UNKNOWN` 处理
> （搬完之前 task 确实不在我们的屏上，判成「黑」会越搬越乱）—— `PipSurface` 里已有这个判断，下沉时要一并带上。

### 修复 C（可选加固，非必需）

`PipSurface` 的**承载面重建路径**也可以补一条「桌面不在前台 → 不自愈」的保护，
与 `checkProcess()` L606 已有的那条对齐。修复 A+B 落地后此条可选。

---

## 6. 待办清单（接手后按此顺序做）

- [ ] **1.** 修复 A：`PipFrameProbe.evaluate()` 加 alpha 闸门；新增 `blind` / `opaqueRatio` 状态与独立日志
- [ ] **2.** 修复 B：task-display 判据下沉到 `ActivityEmbed`；新增 4 态枚举；`ReadyStarter` 重建分支按状态决定是否重启
- [ ] **3.** 抽纯逻辑模块（放 `core/`，不 import Android 类，便于 JVM 单测）
- [ ] **4.** 补单测：
  - 采样区全透明 ⇒ 不判黑、不累加连续黑帧、不触发自愈
  - 采样区不透明且很暗 ⇒ 连续 3 帧 ⇒ 触发像素判黑
  - `ON_OUR_DISPLAY` ⇒ 重建时不重启承载应用
  - `OFF_OUR_DISPLAY` / `NOT_FOUND` ⇒ 重建时重启
  - `UNKNOWN` ⇒ 重建时不重启
- [ ] **5.** 跑全量单测（基线 **2602 条全绿**，改动后不得减少）
- [ ] **6.** 出包 **4301**（`bash tools/release.sh --notes-file tools/notes-r224.md`）
- [ ] **7.** **实车复测**（关键，不能省）：
  - 装到 `198.18.50.1:5555`，设为默认 HOME
  - 复现路径：**导入视频壁纸 → 弹文件选择器 → 选文件 → 回桌面**，
    观察高德是否仍被重启回首页
  - 判定标准：logcat 里 **不应再出现** `from uid 1000` 的 `START … amapautp.yh`，
    也不应再出现 `画中画黑屏自愈：重建承载面`
  - 截图 + logcat 计数，留档 `emu-verify/REPORT-r224.md` 与 `emu-verify/shots-r224/`
- [ ] **8.** 补 `CHANGELOG.md`（第224轮章节）、`tools/notes-r224.md`、`emu-verify/REPORT-r224.md`
- [ ] **9.** 清理临时目录（必须用 `mavis-trash`：先 `cd` 进目录再 `mavis-trash --no-preserve -- .`）

---

## 7. 本轮**不做**的事（用户已明确暂缓）

| 项 | 说明 |
|---|---|
| **视频壁纸右侧裁切/压暗** | 现象：右上角状态栏时钟后半被压暗、大号天气「°」被切一半。证据：`/tmp/r223/car/vw5-scene.png`（20:33）、放大图 `vw-tr.png` / `now-tr.png`；而 `now.png`（20:35）**正常** ⇒ 偶发。**尚未定论**，疑与半透明窗口 + SurfaceView 挖孔/合成层序有关。修复 A/B 落地后再复测，可能顺带消失 |
| **按键调试** | 用户明确「先放一边」。取证已完成：实车物理键**不走标准 KeyEvent**，而是 OEM 有序广播 `ecarx.intent.action.ECARX_KEY_RVOICEASSIST_EVENT`（category `DEFAULT`，extras `ecarx.extra.ECARX_KEY_EVENT_TYPE`=200231、`ecarx.extra.ECARX_KEY_ACTION_TYPE`：**0=单击 / 1=长按**）。OEM **不提供双击** ⇒ 双击需桌面自己的 `KeyGesture.Detector` 判定。拟新增 `core/EcarxKeyAction.kt` + `ui/EcarxKeyReceiver.kt` |
| **R2 上传** | 本机无 `R2_AK` / `R2_SK`，不做 |
| **Git 提交** | 工作区自第197轮起有大量未提交改动，沿用「用户明确要求才提交」的惯例 |

---

## 8. 环境与命令（均已验证可用）

### 构建 / 测试 / 出包

```bash
cd /Users/roy/Documents/desktopapp/cardesk
export JAVA_HOME="$PWD/../toolchain/jdk-17.0.20.1+1/Contents/Home"
export GRADLE_USER_HOME="$PWD/../.gradle-home"
export PATH="$JAVA_HOME/bin:$PATH"

# 全量单测（基线 2602 条）
../toolchain/gradle-8.7/bin/gradle testSystemUnitTest --offline --console=plain -PcardeskPackage=com.ecarx.cardesk

# 快速装机实测（debug 包）
../toolchain/gradle-8.7/bin/gradle assembleDebug --offline --console=plain

# 干净构建 + 单测 + 平台证书 SHA-256 校验 + 写 dist/ 与 dist/cardesk-latest.json
bash tools/release.sh --notes-file tools/notes-r224.md
```

### 实车

```bash
export PATH="/Users/roy/Documents/desktopapp/toolchain/sdk/platform-tools:$PATH"
D=198.18.50.1:5555
adb connect $D

adb -s $D shell input keyevent KEYCODE_HOME
adb -s $D shell screencap -p /sdcard/x.png && adb -s $D pull /sdcard/x.png ./x.png

# 窗口层序（找「谁盖住了右上角」）
adb -s $D shell "dumpsys window windows" | grep -E "Window\{|mOwnerUid|Surface: shown|display=|isOnScreen"

# SurfaceFlinger 图层归属
adb -s $D shell "dumpsys SurfaceFlinger --list" | grep -iE "amap|cardesk|SurfaceView"

# 虚拟屏（判断高德有没有被承载）
adb -s $D shell dumpsys display | grep -iE "VirtualDisplay|mDisplayId=|ownerUid"

# cardesk 日志（一条命令筛出全部）
adb -s $D shell logcat -d | grep "CarDesk/"
```

### 模拟器场景脚本（`/tmp/r223`，可复用）

```bash
cd /tmp/r223
bash setup.sh emulator-5560 com.ecarx.cardesk        # 日间 + 暗色壁纸（已关视频壁纸）
bash setup_default.sh emulator-5560 com.ecarx.cardesk day|night
python3 scan_bar.py <screenshot.png>                  # 量胶囊底板 / 翻页圆点条
```

---

## 9. 取证文件清单

| 路径 | 内容 |
|---|---|
| `/tmp/r223/car/logcat-raw.log` | **36MB 实车全量日志**，含案发时段（20:24～20:28），**最重要的一份** |
| `/tmp/r223/car/sf.txt` | SurfaceFlinger dump |
| `/tmp/r223/car/vw5-scene.png` | 视频壁纸下场景页（20:33），右上角可见压暗 |
| `/tmp/r223/car/vw-tr.png` | 上述右上角 1650..1920×0..200 放大 |
| `/tmp/r223/car/now.png` | 20:35 的画面，**当时是正常的** |
| `/tmp/r223/car/now-tr.png` | 上述右上角放大 |
| `/tmp/r223/car/vw1..vw4*.png` | 视频壁纸排查过程中的中间截图 |
| `/tmp/r223/car/apk/xcin2` | `ecarx.xsf.inputservice`（OEM 按键链路）解包 |
| `/tmp/r223/car/apk/gest` | `ecarx.xsf.gestureservice` 解包 |
| `/tmp/r223/car/xcin.dexdump` | XCInputService dex 反汇编（按键广播来源） |

---

## 10. 必须遵守的约定（工作区根规则）

1. **模拟器验证基准是 cardesk**（`emulator-5560`，AVD `cardesk28`，API 28）。
   领克 820A 合并镜像（`emulator-5570`）**不再是强制验证环境**。
2. **只跑单测 / 只编译过 = 不算验证通过**；交互类改动必须在模拟器上看到真实画面。
3. **第215/216 轮的「描边 + 底衬」路线已被用户否决并回退**（第217轮全部回退）——
   **不得再用**描边/底衬装饰去做「可读性」类改动；本轮的两条修复都是**行为修复**，不涉及视觉装饰。
4. **默认场景必须一个像素都不变**（夜间+深壁纸 / 日间+浅壁纸）。改动后要做 A/B 截图逐像素比对。
5. 代码风格：**中文注释 + 标注「第XXX轮」**；纯逻辑模块**不 import Android 类**以便 JVM 单测；
   Robolectric 需 `@GraphicsMode(NATIVE)`；`Skin` 全局状态必须在 `@After` 还原。
6. 交付包必须是**装机验证过的那一份**；出包走 `tools/release.sh`（自动 +1 版本、干净构建、跑单测、
   校验平台证书 SHA-256 `c8a2e9bc…92ab8`）。
7. 临时目录清理**必须**用 `mavis-trash`（先 `cd` 进目标目录，再 `mavis-trash --no-preserve -- .`）。
8. **不暴露凭据**；R2 上传需 `R2_AK`/`R2_SK`（本机没有，不做）。
9. 用户纠正过的方法论：**真实 keyCode 必须由用户实际按键 + 我们监听取得**，
   注入式 `adb shell input keyevent` 不能替代。

---

## 11. 已知的环境事实（免得再查一遍）

- 车机 display 0：`mBaseDisplayInfo` app 1920×1080，
  `mOverrideDisplayInfo` app **1920×968**（real 1920×1080）
  → 底部 112px 是 systemui 导航栏 + cardesk 自己的「CarDesk 全局 Dock」窗口
  （`mFrame=[0,968][1920,1080]`）→ **正常，不是 bug**。
- 车机还有 **display 1「HDMI 屏幕」1920×720**（density 213）；
  高德另有一路独立窗口 `com.autonavi.amapautp.yh` 跑在它上面（shown=true）→ 与主屏 PiP 无关，
  但用户可能看到，解释现象时别搞混。
- cardesk 在车机上 **uid = 1000（system 特权）** → `getRunningTasks()` 能拿到全部任务，
  `isTaskOnOurDisplay()` 的判据在实车上**可用**。
- 实车 `getevent -lp`：`uinput_nav` 只有 `KEY_UP/LEFT/RIGHT/DOWN`、`KEY_HOMEPAGE`、`KEY_SEARCH`，
  KeyLayoutFile 为空（不重映射）→ `KEY_SEARCH` 直通 Android `KEYCODE_SEARCH=84`。
- 实车**不给 adb root**；`am broadcast` 手工发 OEM 按键 action 会被系统拒（uid=shell 无权限）。

---

**交接完毕。以上全部经实车日志验证，接手后无需重新取证，可直接按 §6 开工。**
