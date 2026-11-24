# 第三百一十二轮：车模图不再随 APK 发布（纯远端资源包）

## 0. 背景

第三百一十一轮做完「启动后台自动补齐车模资源包」之后，安装包里仍留着一份内置车模图
（`assets/car3d/` 三个车型，打包后 1,010,863 B，占 APK 24.4%）。本轮按决定**删掉这份内置副本**：
写实车模只从远端资源包来，下载完成前桌面显示自绘俯视车模。

这一轮的目标只有一个：**瘦包**。可更新性/免重启换图在上一轮已经交付，本轮不重复。

## 1. 做了什么

### 1.1 资产出包（99 个文件 / 1,010,863 B 压缩体积）

- `git mv app/src/main/assets/car3d → assets-src/car3d`：源目录移出 `assets`，**不再进 APK**，
  只作为发布资源包的打包源（`tools/build_car_models.py` 读它）。
- 打包源路径同步改到 `assets-src/car3d`：`tools/build_car_models.py`、
  `tools/car3d/install_bake.py`、`tools/car3d/make_placeholders.py`（含文档串）。

### 1.2 读取链路（`core/car3d/`）

- `CarModelRemote.openStream()` 语义变成「**缓存优先，没装过就抛 IOException**」；
  末尾的 `assets.open` 兜底保留（将来若有人把某个车型塞回 assets 仍可用，现在必定抛）。
- 新增 `CarModelRemote.invalidateCache()`：丢掉进程内的 `versions.json` / 各目录
  `version.json` / 远端索引镜像。只给「绕过安装流程直接改缓存目录」的调用方用
  （单元测试夹具），运行时不调用。
- `Car3dAssets` / `CarModels` 的 KDoc 改成「资源包目录」，并写明「取不到图 → 回退
  `CarTopPainter` 自绘 → 资源包补齐后 `reset()` 换写实图」这条链路。

### 1.3 设置页文案（`ui/SettingsOverlay.kt`）

「车辆信息 → 车模资源包」在没装过时显示「未安装（启动时自动补齐）」，装好后
「N 个车型 · 当前 vX」；注释同步成「APK 不再内置车模图」。

### 1.4 单元测试夹具（新增 `app/src/test/.../core/car3d/TestCarAssets.kt`）

资产相关用例需要真实车模图（解码、绘制、截图对比）。做法是**不复制一份仓库副本**，而是：

```kotlin
@get:Rule val carAssets = TestCarAssets.Rule()
```

Rule 在用例开始时把仓库里的 `assets-src/car3d/<dir>/` 拷进 `CarModelRemote` 的缓存目录，
补上 `version.json` / `versions.json` 版本戳，然后 `CarModelRemote.invalidateCache()` +
`Car3dAssets.reset()` —— 被测代码走的是**生产路径（缓存优先）**。

`Car3dAssets` 是进程级单例，Robolectric 一个 JVM 跑完所有测试类，别的用例可能已经把它标成
「缺图/损坏」；所以夹具安装后必须 `reset()`，否则单例状态跨类残留（本轮踩过：一个用例
单独跑绿、全量跑红）。

挂上 Rule 的 8 个测试类：`Car3dAssetOrientationTest`、`Car3dAssetsTest`、`Car3dPaintTest`、
`Car3dPreviewTest`、`Car3dScenePageTest`、`CarStatusThemePreviewTest`、
`CarTopStatusViewAspectTest`、`SettingsCardViewRowTest`。

### 1.5 文档

- `README.md` 车模段重写：安装包不内置车模图、启动后台一次性补齐、失败 5 分钟重试、
  下载完成前显示自绘俯视车模、补齐后免重启换图。
- 本轮报告即本文件。

## 2. 实测

### 2.1 包体积（同一个干净构建）

| | 上一版 4323 | 本轮 | 变化 |
|---|---|---|---|
| APK | 4,168,917 B | **3,141,855 B** | **−1,027,062 B（−24.6%）** |
| `assets/car3d` 条目 | 99 个 / 1,010,863 B | **0** | 全删 |
| dex | 2,788,120 B / 14 个 | 2,788,562 B / 14 个 | 与上轮一致（未开 R8） |
| assets 顶层 | car3d / splash / nav-insets | splash / nav-insets | — |

### 2.2 单元测试

`gradle testSystemUnitTest --offline`：**2761 用例 / 0 失败 / 0 错误 / 0 跳过**（270 个结果文件）。

### 2.3 设备端（`emulator-5572`，`com.ecarx.cardesk` 平台签名 system 变体）

安装新包 → `pm clear` → 飞行模式 + `svc wifi/data disable`（`ping` 报 `unknown host`）→ 启动：

```
13:51:04.345 I CarDesk/Car3d: 车模自动补齐已启动
13:51:04.346 I CarDesk/Car3d: 本地车模不齐全，开始后台补齐 missing=lynk01,lynk03,lynk05
13:51:04.356 W CarDesk/Car3d: 车模清单下载失败 url=https://file.chuxi.bid/cardesk/car-models/index.json
13:51:04.356 W CarDesk/Car3d: 车模仍未补齐，5 分钟后重试 attempt=1 missing=lynk01,lynk03,lynk05 clean=false
                          reason=车模清单下载失败：Unable to resolve host "file.chuxi.bid"
```

- 冷启动 `total=1901ms`，`svc:carmodel` 已记录，`logcat -b crash` **0 条**。
- **断网首启的车况卡显示自绘俯视车模**（`emu-verify/shots-r312/r312-01-offline-selfdrawn.png`），
  不空窗、无崩溃。

13:51:43 恢复网络（**不重启进程、不重启 App**），5 分钟重试到期后：

```
13:56:08.538 I CarDesk/Car3d: 车模已安装 dir=lynk01 version=5 size=305345
13:56:10.473 I CarDesk/Car3d: 车模已安装 dir=lynk03 version=5 size=394324
13:56:11.457 I CarDesk/Car3d: 车模已安装 dir=lynk05 version=5 size=323806
13:56:11.457 I CarDesk/Car3d: 车模补齐完成，本地已齐全 updated=lynk01,lynk03,lynk05 remoteTotal=3 clean=true
13:56:11.458 I CarDesk/Car3d: 车模资源包已补齐，解码缓存失效 dirs=[lynk01, lynk03, lynk05]
```

- 同一 pid（26796），车况卡换成写实车模（`r312-02-online-real.png`）。
- 设置 → 车辆信息：写实车模「领克 03」，本地资源包「3 个车型 · 当前 v5」
  （`r312-03-settings-v5.png`）。
- 设备端缓存：`/data/data/com.ecarx.cardesk/files/car3d-models/` 下
  `lynk01 / lynk03 / lynk05 + versions.json = {"lynk01":5,"lynk03":5,"lynk05":5}`。

## 3. 判据（复现用）

```bash
adb -s emulator-5572 install -r app/build/outputs/apk/system/app-system.apk
adb -s emulator-5572 shell pm clear com.ecarx.cardesk
# 断网（要真断：飞行模式 + wifi/data 双关，单关 wifi 会被蜂窝数据绕过去）
adb -s emulator-5572 shell "settings put global airplane_mode_on 1; am broadcast -a android.intent.action.AIRPLANE_MODE --ez state true; svc wifi disable; svc data disable"
adb -s emulator-5572 shell am start -n com.ecarx.cardesk/com.cardesk.launcher.MainActivity
adb -s emulator-5572 logcat -d -s CarDesk/Car3d     # 期望「本地车模不齐全…」+「5 分钟后重试」
# 恢复网络后（不重启 App）等 ≤5 分钟
adb -s emulator-5572 logcat -d -s CarDesk/Car3d     # 期望「车模补齐完成，本地已齐全」+「解码缓存失效」
adb -s emulator-5572 shell cat /data/data/com.ecarx.cardesk/files/car3d-models/versions.json
```

## 4. 诚实的缺口

- **splash 两张 PNG 仍 326,310 B**（7.9% → 现在占 10.4%），转 webp/降采样能再省 ~250K，本轮未做。
- **dex 仍 14 个 / 2,788,562 B**（现在占 APK 88.7%）：`isMinifyEnabled=false`、keep 规则 0 条，
  没跑 R8。要进 2MB 级必须动它，但本项目大量反射调车机私有 API，需要 keep 规则 + 全量回归。
- **下载完成前是自绘俯视车模**（设计如此）：首装 + 无网时用户会先看到 2D 车模，
  有网后自动换成写实图。若希望「首装即写实」，得把车模预置进 `/system` 或换个分发方式。
- 车模资源包依赖远端可用性；离线时功能不受影响（只是没写实图），但 R2 挂掉就等于永久 2D。
