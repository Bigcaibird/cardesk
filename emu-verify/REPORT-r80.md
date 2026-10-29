# 第八十轮：模拟器回归报告（氛围灯黑屏闪退 + 红绿灯渲染）

日期：2026-10-30 · 被测包：`dist/CarDesk-ecarx-cardesk-4144.apk`（基线）与
`dist/CarDesk-ecarx-cardesk-4145.apk`（修复后）

## 0. 环境

| 项 | 值 |
| --- | --- |
| 设备 | `emulator-5554` = **ecarx 平台镜像**（android-10 / API 29 / `sdk_phone64_arm64-userdebug`） |
| 镜像内容 | 预装 `com.ecarx.*`、`com.autonavi.amapautp.yh`（高德 YH） |
| 分辨率 | 1920×1080 @160dpi |
| 被测包 | `com.ecarx.cardesk`，**平台签名 + uid 1000**（与真车同形态） |
| 权限 | `RECORD_AUDIO`：`pm grant` + `appops set RECORD_AUDIO allow`（两步都要） |
| 车控通道 | `ECARX AdaptAPI`（**车机不认氛围灯功能号** ⇒ 写不出去的降级路径，天然覆盖原 bug 触发条件） |

> 为什么用 ecarx 平台镜像而不是 AOSP：`com.ecarx.cardesk` 是平台签名 + `sharedUserId=android.uid.system`，
> 只有这个镜像能装。车控链路（`AdaptapiGateway` 反射进车机服务）在这里是**真链路**。

## 1. 红绿灯渲染（用户点名要看）

### 1.1 造数据

模拟器上没有高德，导航卡不会自己出现。用 `am broadcast` 发高德的两套协议：

```bash
# ① 导航状态（引导数据源启动）
am broadcast -a AUTONAVI_STANDARD_BROADCAST_SEND --ei KEY_TYPE 10019 --ei EXTRA_STATE 8
# ② 引导帧（KEY_TYPE=10001）
am broadcast -a AUTONAVI_STANDARD_BROADCAST_SEND --ei KEY_TYPE 10001 --ei ICON 1 \
  --ei SEG_REMAIN_DIS 320 --es CUR_ROAD_NAME 'CURRD' --es NEXT_ROAD_NAME 'NEXTRD' \
  --ei ROUTE_REMAIN_TIME 600 --ei ROUTE_REMAIN_DIS 3000
# ③ 红绿灯（YH 巡航协议，JSON 在 extra "data"；status 0=红 1=绿）
am broadcast -a NAVITOOL_CRUISE_TRAFFIC_LIGHTS \
  --es data '{"ts":0,"lights":[{"direction":2,"status":0,"countdown":12,"waitNum":1,"showType":0}]}'
```

**坑**：加 `-p com.ecarx.cardesk` 会把广播过滤掉（运行时注册的接收器不认包过滤），必须**全发**。

App 侧日志：`收到导航引导数据 keyType=10001 icon=1 seg=320` /
`收到红绿灯数据（巡航协议） lamp=RED countdown=12 rawStatus=0 rawDir=2`。

### 1.2 截图结论

- `r80-traffic-red.png` / `r80-traffic-green.png`：导航卡右上角，**大圆 + 圆下方读秒**，
  去掉「红灯/黄灯/绿灯」文字后颜色成为唯一灯态线索。
- **4144 的渲染有缺陷**：`r80-03b-traffic-zoom.png` 里「秒」字**压在读秒数字上**
  （「秒」的笔画直接压在第二个数字「1」上）。
  根因是 `drawTrafficLight` 里两个基准不一致：数字按 `Align.CENTER` 画在圆心，
  「秒」按 `Align.LEFT` 画在 `numLeft + numW + 4`，而 `numLeft` 又是按「整行左对齐」算的。
  **设计板（Python 重绘）完全看不出来** —— 这就是「必须跑模拟器出图」的直接价值。
- **4145 已修**：`r80-traffic-red-zoom.png`（红圆 + 「11 秒」）与
  `r80-traffic-green-zoom.png`（绿圆 + 「7 秒」）里，数字与「秒」**整行以圆心居中**，
  数字是灯色、秒字是弱化灰，**互不重叠**。

### 1.3 抓图过程中踩的三个坑（都出过假图，值得记下来）

> 这一节是**更正**：本轮第一次抓的 4 张 `r80-traffic-*.png` 其实**截的是氛围灯设置页**，
> 红绿两张**字节完全相同**（md5 一致）—— 当时只看了文件名就当成了绿灯渲染图。
> 下面是查清的过程，也是脚本里现在写死的约束。

| 坑 | 现象 | 根因 | 现在怎么做 |
| --- | --- | --- | --- |
| 没回到场景页就截图 | 截到的是**还开着的氛围灯设置页** | `traffic` 只广播不切页面，设置面板还盖在场景页上 | 抓图前先 `input keyevent BACK` 关掉面板 |
| 广播脚本没杀掉 | 截「绿灯」拿到的是**红**灯 | `ps -A` 的 NAME 列只显示进程名（脚本都叫 `sh`），按脚本名 grep **匹配不到**；`pkill -f` 也没生效 | 用 `ps -ef` / `ps -A -o PID,ARGS` 拿全命令行，**按 PID kill** |
| 脚本名带后缀导致匹配落空 | 新脚本清不掉 | 匹配串写死 `"nav-broadcast.sh"`，而新脚本叫 `cardesk-nav-broadcast-g.sh` | 匹配 `broadcast` + `.sh`，脚本名带 `-r`/`-g` 后缀 |

还有一个更隐蔽的：第七十九轮留下的**红灯脚本（pid 8237）从 23:48 一直活到 00:41**，
和绿灯脚本同时广播 —— 最后一次收到谁，屏幕上就是谁的灯态。
所以「绿灯截成红色」曾经连续发生三次，**每次都是自己的锅**（脚本没清干净 / 页面没切 / 抓的不是这张图）。

为此新增 `tools/traffic_shot.py`：广播 → 截图 → **自动定位灯圆色块 → 裁 3 倍放大图**，
并把「这一帧到底是红是绿」判出来（灯圆是 46×46 的近似正方实心块，
按形状筛能避开顶栏头像和续航进度条上的绿色）。

```bash
python3 tools/traffic_shot.py red 12      # → r80-traffic-red.png + -zoom.png
python3 tools/traffic_shot.py green 8     # → r80-traffic-green.png + -zoom.png
```


## 2. 【P0】氛围灯黑屏闪退 —— 复现与根因

### 2.1 复现（4144）

1. 设置 →（系统 tab →）车控 → **氛围灯** → 打开「启用氛围灯」开关；
2. `23:52:52.940` 频谱挂上输出混音（`captureSize=512 fftBytes=256`）；
3. `23:53:03.814` **native SIGSEGV**（11 秒后）：

```
F libc : Fatal signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x44
        in tid 8096 (HeapTaskDaemon), pid 8080 (m.ecarx.cardesk)
I ActivityManager: Process com.ecarx.cardesk (pid 8080) has died: fore TOP
```

4. tombstone（`emu-verify/r80-tombstone-00.txt`）的栈：

```
#00 art::gc::collector::ConcurrentCopying::AddLiveBytesAndScanRef(art::mirror::Object*)+348
#01 art::gc::collector::ConcurrentCopying::ProcessMarkStackForMarkingAndComputeLiveBytes()+464
#02 art::gc::collector::ConcurrentCopying::MarkingPhase()+1176
...
Cause: null pointer dereference
```

**判读**：崩的是 **ART 的 GC 线程**，扫到一个**坏引用**时崩 ⇒ **Java 堆被 native 代码写坏了**，
不是 Java 异常、不是 watchdog、不是内存不足。
崩溃瞬间我们自己的两个工作线程（`cardesk-spectru` / `cardesk-ambient`）都**空闲在 epoll** ——
说明破坏发生在更早，GC 扫到时才爆。

### 2.2 隔离实验（2×2）

| 条件 | 4144（缓冲区 256B） | 4145（缓冲区 512B） |
| --- | --- | --- |
| 有 RECORD_AUDIO（**Visualizer 挂上**） | ❌ **11 秒后 SIGSEGV** | ✅ 45 秒 + 180 秒长测无崩溃 |
| 撤掉 RECORD_AUDIO（**Visualizer 挂不上**） | ✅ 25 秒无崩溃 | — |

⇒ 崩溃源头锁定在 **`Visualizer` 的 `getFft()`** 这条路径，**不是**车控写入路径。

### 2.3 根因：FFT 缓冲区少给了一半 ⇒ ROM 越界写坏 Java 堆

三方证据完全对上：

| | uni-launcher（**同款车机，已跑通**） | CarDesk 4144（崩溃） | CarDesk 4145（修复） |
| --- | --- | --- | --- |
| FFT 缓冲区 | `new byte[captureSize]` = **512 B** | `captureSize / 2` = **256 B** ❌ | `captureSize` = **512 B** ✓ |
| FFT 点数 N | `fft.length` = 512 | `fft.size × 2` = 512（凑的） | `fft.size` = 512 ✓ |
| 频点数 | `bins = fft.length / 2` = 256 | `maxBin = (size−2)/2` = 127 ❌ | 256 ✓ |

AOSP 公开文档口径：`getFft()` 往缓冲区里写的是 **N 个字节**
（1 字节 DC + 1 字节 Nyquist + (N/2−1) 组实虚对，每组 2 字节）。
我们只给 256 字节 ⇒ **ROM 往数组里写 511 字节 ⇒ 越界 255 字节踩坏 Java 堆** ⇒
GC 扫到坏引用 ⇒ native SIGSEGV ⇒ 进程直接死（**文件日志里一条都没有**，所以第七十九轮诊断包里看不到栈）。

### 2.4 顺带修掉的第二个缺陷：写入节流形同虚设

4144 的日志里「氛围灯颜色功能号按名解析不到」**≈ 8 次/秒**（120ms 一条）。
根因：`AdaptapiAmbientLightApi.apply()` 里**节流写在功能号解析之后** ——
车机不认这个功能号时每次都提前 `return`，`lastWriteAt` 永远不推进 ⇒ 节流与 backoff 都够不着。

4145：把节流与 backoff 提到最前面 ⇒ 实测 **≈ 1.1 秒一条**（1000ms 一次）。

## 3. 频谱

- 状态：**采集中 · 来源：输出混音 · 通道：ECARX AdaptAPI**
- 参数：`captureSize=512` **`fftBytes=512`**（修复前是 256）、`sampleRate=44100`
- 当前颜色：`#330808 · 亮度 20%`（**由频谱算出来**，不是写死值）
- 频段数从 **128 恢复到 256**（`bins = N/2`），与参照工程一致

**模拟器限制（如实记录）**：预览条在模拟器上是**平的基线**。原因是模拟器上没有能在
**后台**持续播放的音源 —— `com.android.music` 只有 `AudioPreview` 活动，一旦切到后台就停止播放
（实测：HOME 后 player 从列表里消失）；`tinyplay` 直写 ALSA、绕开 AudioFlinger，输出混音抓不到。
⇒ **「条在动」这一条要真机（或有后台播放器的环境）才能验**，模拟器只能验到
「采集链路通、参数对、颜色由频谱算出、且不崩」。

## 4. 结论

| 项 | 结果 |
| --- | --- |
| 红绿灯渲染（大圆 + 圆下方读秒） | ✅ 4144 有压字缺陷，**4145 已修**，截图见上 |
| 氛围灯页黑屏 | ✅ 4144 无黑屏（第七十九轮的「写车控挪出主线程」生效） |
| 氛围灯页闪退 | ✅ 4144 **闪退**（native SIGSEGV），**4145 已修**（45s + 180s 无崩溃） |
| 车控写入不占主线程 | ✅ 写入在 `cardesk-ambient` 线程 |
| 写入节流 | ✅ 4145 ≈ 1.1s/条（4144 ≈ 0.12s/条） |
| 频谱采集链路 | ✅ 采集中 / 输出混音 / 256 频段 |
| 频谱条跳动 | ⚠️ 模拟器无后台音源，**未验证**（需真机） |
| 车门 0/1 语义 | ⚠️ 仍未验证（`analysis/findings/19-…` 已记） |

## 5. 证据文件

| 文件 | 内容 |
| --- | --- |
| `emu-verify/r80-tombstone-00.txt` | 4144 的 native 崩溃 tombstone（GC SIGSEGV 栈） |
| `emu-verify/r80-logcat-ambient.txt` | 4144 崩溃前完整 logcat |
| `emu-verify/r80-03-traffic-red.png` / `r80-03b-traffic-zoom.png` | 4144 红绿灯渲染 + 放大（**可见压字缺陷**） |
| `emu-verify/r80-traffic-red.png` / `r80-traffic-red-zoom.png` | 4145 红灯渲染 + 放大（已修） |
| `emu-verify/r80-traffic-green.png` / `r80-traffic-green-zoom.png` | 4145 绿灯渲染 + 放大（已修） |
| `emu-verify/r80-ambient-page.png` / `r80-ambient-on-{1,2,3}.png` | 4145 氛围灯页与开启后连续帧 |
| `emu-verify/r80-ambient-spectrum.png` | 4145 频谱状态（采集中 / 输出混音 / 当前颜色） |
| `emu-verify/r80-soak-end.png` | 4145 长测结束时的画面 |
| `tools/emu_regress.py` | 本轮新增的回归脚本（装包 / 触发 / 截图 / 判定闪退） |
| `tools/traffic_shot.py` | 本轮新增的红绿灯取证脚本（广播 / 定位灯圆 / 裁放大图 / 清脚本） |
