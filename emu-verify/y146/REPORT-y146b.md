# 第146轮·补：拿真实 QQ 音乐实测 —— 揪出一个合成图永远测不到的 bug

## 用户追加要求

> appstore目录下有个qq音乐车机版，你自己装上实际验证一下

（第一轮回答：appstore 里那个 2.8.0.5 是 **armeabi-v7a only**，本机模拟器全是
arm64 独占，装不上。）
> 你下个带arm64版本的qq音乐车机版验证一下

**→ 换成带 arm64 的 3.14.0.4，装上、跑起来、验证通过。**

## 零、先把 appstore 那个包为什么装不上说清楚

`cardesk/appstore/qq音乐2.8.0.5.apk`（`com.tencent.qqmusiccar` v2.8.0.5）
**只带 armeabi-v7a 原生库**（52 个 .so），没有任何 arm64-v8a。

```
$ adb -s emulator-5560 install -r -t qq音乐2.8.0.5.apk
adb: failed to install qq音乐2.8.0.5.apk: Failure [INSTALL_FAILED_NO_MATCHING_ABIS:
     Failed to extract native libraries, res=-113]
```

| 尝试 | 结果 |
|---|---|
| 本机 3 个模拟器 | 全是 `arm64-v8a` **独占**，`/system/lib32` 不存在，无 libhoudini 转译层 |
| API 26+ 的 32 位系统镜像 | Google **已下架**（32 位最高 API 25，桌面 minSdk=26 装不上）；清华/中科大/上交/南大/华为云/北外镜像逐个探测 **全 404** |
| 旧版 emulator | 新版 37.3.5 **硬拒**：`CPU Architecture 'arm' is not supported by the QEMU2 emulator`；Apple Silicon 宿主的旧包被下架，并发扫 **460 万个构建号 0 命中** |
| 剥原生库重签 | 不行：包**加过固**（乐固 `libshella` 就是 Application 本身），`libmarsxlog` 启动即 load |

> **这条对用户有实际价值**：appstore 里那个 2.8.0.5 是 2021 年的包，
> 只支持 32 位 ARM —— 装到 Android 12+ 的纯 arm64 车机上会直接失败。
> 3.14.0.4 同时带 `arm64-v8a` + `armeabi-v7a`，才能覆盖新旧车机。

## 一、换成 3.14.0.4：来源与可信度校验

```
文件：qqmusiccar_3.14.0.4.apk（86.0 MB）
包名：com.tencent.qqmusiccar   versionCode=3140004  versionName=3.14.0.4
minSdk 21 / targetSdk 33 / compileSdk 34
原生库：arm64-v8a × 70  +  armeabi-v7a × 65      ← 这次有 arm64
```

**三重校验（不靠"下载站说是官方"这句话）**：

| 校验 | 结果 |
|---|---|
| MD5 vs 页面公布值 | `F735C0A8187382073A050B1B9A7FF499` → **完全一致** |
| 签名证书 vs appstore 里那个官方 2.8.0.5 | SHA-256 `bf3db8c6cbbf11104eb62fc20e9dfb27b37445d84bdb529241ed498b0044fa1e` → **完全一致**（DN 同为 `CN=Tencent, OU=Tencent, O=Tencent, L=Beijing, ST=Beijing, C=86`） |
| ABI | `primaryCpuAbi=arm64-v8a` → 装进模拟器用的是 arm64 库 |

签名证书指纹一致 = **同一个腾讯密钥签的**，比 MD5 更硬。

安装：

```
$ adb -s emulator-5560 install -r -t qqmusiccar_3.14.0.4.apk
Success
```

## 二、跑起来了

```
$ adb shell monkey -p com.tencent.qqmusiccar -c android.intent.category.LAUNCHER 1
mCurrentFocus=Window{... com.tencent.qqmusiccar/com.tencent.qqmusiccar.app.activity.StartUpPrivacyPolicyActivity}
```

- 隐私政策页 → 点「同意」→ 进首页
- **不需要登录**就能用：首页「为你推荐」「大家都在听」是真数据（赤伶 / Six Days (Remix) / 数到五答应我 / 大雁…）
- 直接点歌就能播

### QQ 音乐车机版发的 MediaSession

```
package=com.tencent.qqmusiccar
state=PlaybackState {state=3, ...}
metadata:size=11, description=赤伶, HITA, 赤伶
```

**`metadata:size=11`** —— 比探针的 5 多得多。

## 三、桌面读到了什么（最关键的一段日志）

```
D/CarDesk/Media: 会话选择 candidates=com.tencent.qqmusiccar#... selected=... picked=com.tencent.qqmusiccar
D/CarDesk/Media: 来源 id 映射 pkg=com.tencent.qqmusiccar id=qq
D/CarDesk/Media: 元数据取值链 pkg=com.tencent.qqmusiccar
      title=Six Days (Remix) titleFrom=DESCRIPTION_TITLE
      artist=DJ Shadow artistFrom=SUBTITLE
      durationMs=232000 mediaId=49
      artFrom=ALBUM_ART artUri=
D/CarDesk/Media: 使用播放器自供歌词 key=id:49 lines=95
D/CarDesk/Media: QQ 广播（本次不含歌词）boundPkg=com.tencent.qqmusiccar
```

这几行把「真实 App 接入」的所有不确定点都答了：

| 关注点 | 真实答案 |
|---|---|
| 桌面会选中 QQ 音乐的会话吗 | ✅ `picked=com.tencent.qqmusiccar` |
| 桌面认得这是 QQ 音乐来源吗 | ✅ `来源 id 映射 id=qq`（桌面自带 QQ 音乐对接） |
| 封面是塞 bitmap 还是塞 URI | ✅ **`artFrom=ALBUM_ART`**、`artUri=` 为空 → 塞的是 **bitmap**，走兜底链第 ① 级 |
| 歌名/歌手从哪来 | `DESCRIPTION_TITLE` / `SUBTITLE`（不是标准的 TITLE/ARTIST）—— 桌面有兼容取值链 |
| 歌词 | QQ 音乐**自带**歌词（`使用播放器自供歌词 lines=95`） |

## 四、真实 App 下的实测数据

### 夜间（QQ 音乐《Six Days (Remix)》封面）

| 指标 | 实测 | 规格 | 结论 |
|---|---|---|---|
| 背景最大灰度 | **51** | ≤ 51 | ✅ |
| 背景最大相对亮度 | **0.0341** | ≤ 0.0341 | ✅ |
| 主档 / 次档 / 最弱文字（逐像素取最小） | **10.85 / 5.34 / 3.05** | ≥ 3.0 | ✅ |
| 合规率 | **100.00%**（75400/75400） | 100% | ✅ |
| 背景彩色像素占比 | 29.4%~78.4%（随封面变） | 封面确实铺上了 | ✅ |

### 日间（同一 App）

| 指标 | 实测 | 结论 |
|---|---|---|
| 背景 L 范围 | 0.9816 ~ 1.0000 | ✅ |
| 主档 / 次档 / 最弱（逐像素取最小） | 16.75 / 6.50 / **3.07** | ✅ |
| 合规率 | **100.00%** | ✅ |
| 背景彩色像素占比 | 0.00% | ⚠️ 封面基本不可见（规格必然，见下） |

### 换歌 → 背景跟着换

两张完全不同的封面，各自成了背景，来源标签是 **QQ 音乐自己的绿色图标**：

| | 曲目 A | 曲目 B |
|---|---|---|
| 歌名 / 歌手 | 大雁 / 马頔 | 数到五答应我 / 曹格 |
| 背景采样像素 | (6, 19, 29) | (51, 51, 51) |
| 最弱档文字最差对比度 | 3.05:1 | 3.05:1 |
| 合规率 | 100% | 100% |

## 五、实测揪出的真 bug（合成封面永远测不到）

### 现象

用真实 QQ 音乐封面实测**日间**模式：

```
背景最暗像素 L=0.8714（灰度 240）
合规率（L≥0.9745）只有 71.67%     ← 差了近三成
最弱档文字对最暗像素的对比度 2.89:1 ← 低于 3.05 底线
```

同一张卡在合成斜纹封面下是 **100% 合规** —— 合成图的像素分布太平，碰不到这个边界。
**真实封面里 12% 的像素是纯白（L≥0.95）**，才把这条边界顶出来。

### 根因

`CoverBackdrop.toneMap` 末尾「把取整误差越界的像素夹回区间」的循环，变亮方向用的是
**固定 5% 混白**。8bit 是离散的：

```
灰度 253 → linear(253)=0.9703
          mixWhite(p, 0.05) → 0.9703 + (1-0.9703)×0.05 = 0.97179
          toSrgb(0.97179) = 253.3 → 取整 **253**     ← 一模一样，像素没动
```

**8bit 的量化台阶比步长还大时，固定比例的步子永远跨不过去** —— 循环转 40 圈，
像素始终卡在规格线以下。

同样的坑在暗方向也有（代码注释里已记过一次：「灰度 52 缩放后还是 52」），
但暗方向是**上限**规格，卡住的像素本来就合格；变亮方向是**下限**规格，
卡住就直接违反「文字读得清」这条保证。

### 修法

变亮方向改成「**每通道至少 +1 个灰阶**」（`CoverBackdrop.brightenOneStep`）——
离散步进，必定跨得过量化台阶：

```kotlin
private fun brightenOneStep(color: Int): Int {
    val r = (red(color) + 1).coerceAtMost(255)
    val g = (green(color) + 1).coerceAtMost(255)
    val b = (blue(color) + 1).coerceAtMost(255)
    return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
}
```

循环上限从 40 提到 260（0 → 255 需要 255 步）。

### 新增用例（先红后绿）

`CoverBackdropTest.kt` 新增 2 条（15 → 17）：

| 用例 | 断言 |
|---|---|
| `日间_每个像素都要够亮_不能有像素卡在量化台阶上` | 输出里**每一个**像素都要 `L ≥ 下限`，且对最弱文字的对比度都 ≥ 3:1 |
| `夜间_每个像素都要够暗_不能有像素卡在量化台阶上` | 输出里每一个像素都要 `L ≤ 上限`，且对最弱文字的对比度都 ≥ 3:1 |

**反向验证**（改回旧写法 → 必红）：

```
=== 改回「固定 5% 混白」 ===
RED= 1 TOTAL= 17
  红 日间_每个像素都要够亮_不能有像素卡在量化台阶上
     java.lang.AssertionError: 第 0 个像素亮度只有 0.9215818562772946（下限 0.9743364468399305），
     卡在 8bit 量化台阶上没爬上来
=== 还原修复 ===
```

### 修完的实测对比（真实 QQ 音乐封面）

| | 修复前 | 修复后 |
|---|---|---|
| 日间背景 L 范围 | 0.8714 ~ 1.0000 | **0.9753 ~ 1.0000** |
| 日间合规率（`L ≥ 0.9745`） | 71.67% | **100.00%** |
| 日间最弱档文字最差对比度 | **2.89:1** ❌ | **3.07:1** ✅ |
| 夜间最差对比度 | 10.85 / 5.34 / 3.05 | 不变 |

夜间完全不受影响（暗方向本来就是上限规格）。

## 六、测量方法本身的修正

原来的测量脚本只拿「**最亮**的那个背景像素」去比对比度。这在夜间是对的
（字亮底暗 → 底越亮越难读），但在**日间是错的**（字暗底亮 → 底越暗越难读）。

改成「对每一个背景像素分别算对比度，再取最小值」：

```
=== 三档文字的对比度（逐像素算，取最小值）===
  OK  TEXT      (主档 38px): 最小 16.75:1（取自最暗像素）/ 中位 16.91:1
  OK  TEXT_SOFT (次档 22px): 最小 6.50:1 / 中位 6.56:1
  OK  TEXT_DIM  (最弱 20px): 最小 3.07:1 / 中位 3.10:1
```

> 之前几轮报告里的「100% 合规 / 3.07:1」是按「最亮像素」算的，
> 对夜间成立、对日间不成立。日间结论以本轮修正后的口径为准。

## 七、性能抽查：换封面会不会掉帧

我的改动把「模糊 + 色调映射」放在 UI 线程上（`CoverBackdropDrawable.rebuild()`），
而合成封面只有 512×512，真封面最大能到 1500×1500 —— 值得实测。

`dumpsys gfxinfo com.ecarx.cardesk reset` 后分别测（软件渲染，无 GPU，数字整体偏高）：

| 场景 | 帧数 | 卡顿帧 | 中位帧时 | 99 分位 |
|---|---|---|---|---|
| 静默基线（5 秒不动） | 22 | 36.4% | 12ms | 34ms |
| 换封面（500×500 真封面） | 41 | 68.3% | 34ms | 125ms |
| 换封面（1500×1500 真封面） | 37 | 75.7% | 31ms | 150ms |

**结论：代价与源图尺寸无关**（500 和 1500 的中位帧时基本一样），
说明瓶颈是软件渲染下「换歌 → 整页重绘（歌名/歌词跑马灯/进度条）」本身，
不是模糊/色调映射管线。真机有 GPU 时这个数字会低得多。
同时无 OOM、无崩溃，Native Heap 30MB / Java Heap 8MB。

## 八、产物

| 文件 | 内容 |
|---|---|
| `y146-11-qqmusic-realapp-two-tracks.png` | **QQ 音乐真实 App**：两首不同歌曲，封面各自成背景（对照图） |
| `y146-12-qqmusic-realapp-full.png` | QQ 音乐真实 App，夜间整屏 |
| `y146-13-qqmusic-realapp-day.png` | QQ 音乐真实 App，日间整屏 |
| `y146-14-qqmusic-app-home.png` | QQ 音乐车机版 3.14.0.4 首页（真数据） |
| `y146-15-qqmusic-app-playing.png` | QQ 音乐车机版播放页 |
| `qqmusiccar-3.14.0.4-arm64.apk` | 验证用的安装包（MD5/签名已记录） |
| `y146-08/09/10-*.png` | 上一轮用 QQ 音乐曲库真封面（500×500 / 1500×1500）的实测 |
| `qqmusic-art-*.jpg` | 从 QQ 音乐接口拉的真封面原图 |

## 九、验证环境

- 模拟器 `emulator-5560`（API 28，arm64-v8a，1920×1080）
- 桌面包 `com.ecarx.cardesk` v4243（`-PcardeskPackage=com.ecarx.cardesk`）
- 音乐源：`com.tencent.qqmusiccar` **3.14.0.4**（arm64，腾讯同一签名密钥）
- 封面来源：QQ 音乐 `y.gtimg.cn` CDN + App 自己的 `METADATA_KEY_ALBUM_ART`

> **踩过的坑（补充）**
>
> 1. 画中画里的高德会自己弹全屏盖住桌面，截图会截到**地图页**——
>    地图里的白云和白色建筑会被误判成「背景越界」。
>    现在有 `work/verify_navpage.sh`：禁高德 → 重启桌面 → 校验焦点窗口 →
>    校验桌面日志 `page=1` → 校验歌名区有白字，四道关都过才允许测量。
> 2. 探针的 `readIntent` 只在 extra 存在时**赋值**、不会清空旧的 art 路径，
>    所以「不带封面重测」必须先 `force-stop` 探针，否则旧封面还在。
> 3. 测对比度**不能只看最亮像素** —— 日间最不利的是**最暗**像素。
> 4. 测日间前一定要先确认**设备时间**（跟随时间模式），
>    否则会在日间截到图却按夜间口径去算。

