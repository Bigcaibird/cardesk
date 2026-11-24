# CarDesk 4321 / 1.0.1225-preview · 第三百一十轮

四项改动：**车模资源远端化**、**360 环影车机信号联动**、**海拔**、**仓库文案约束**（见 `AGENTS.md` §2）。

验证环境：AVD `Lynkco_820A_car15_arm64`（`emulator-5572`，API 28 / arm64 / 平台签名）。
构建：`assembleSystem -PcardeskPackage=com.ecarx.cardesk --offline`；单测 2751 条全绿。

---

## 1. 车模资源远端化

**做了什么**：内置车模资产（`assets/car3d/{lynk01,lynk03,lynk05}`）保持原样可用，
另加一条「远端资源包」通路 —— 远端更新时把图落到 `filesDir/cache3d/<dir>/`，
解码时**缓存优先、内置资产兜底**。

- 新增 `core/car3d/CarModelRemote.kt`：`openStream()`（缓存→assets）、`installedVersion(s)`、
  `sync()`、`refresh()`（自带后台线程 + 主线程回调），`.part` + 原子激活（旧→`.old`、
  新→正式目录、写 `versions.json`、删 `.old`，失败回滚），`versions.json` + 每目录 `version.json`
  双戳防止半包。
- `core/car3d/Car3dAssets.kt` 三处 `context.assets.open(...)` 改成 `CarModelRemote.openStream(...)`
  （bounds 解码、正式解码、`model.json`）；LRU（8 张 / 4MB）、缺图记 `missing` 不再重试、
  解析失败永久回退自绘车模这些既有策略**未动**。**首帧解码路径不发网络**。
- 远端布局：`https://file.chuxi.bid/cardesk/car-models/index.json`
  （`format: "cardesk.carmodels/1"`）+ `cardesk/car-models/<dir>-v<n>.zip`；
  zip 级 size + sha256、解压后逐文件校验；条目拒绝绝对路径与 `..`，单条目 ≤8MB、总解压 ≤32MB。
- 新增 `tools/build_car_models.py`（仅标准库）：确定性 zip（固定时间戳、条目名排序、无目录项）
  + 生成 `index.json` + `versions.json` 自增；`--dry-run` / `--upload`（复用 `tools/publish_r2.py`
  的 SigV4，凭据只从 `R2_AK` / `R2_SK` 环境变量读）。
- 设置页「车辆信息 → 车模资源包」：版本行 + 「检查车模资源更新…」。

**实测**：产物 `dist/car-models/`（3 个 zip 共 1.0M）已生成，**未上传 R2**（本机没有凭据）。
在 5572 上点「检查车模资源更新…」→ 日志
`车模清单下载失败：HTTP 404` → Toast 说明原因，桌面车模照常显示（内置资产兜底），无崩溃。

**诚实的缺口**：端到端（真的从 R2 拉到新图）**未验证**，需要 `R2_AK` / `R2_SK` 后跑
`python3 tools/build_car_models.py --upload`，再在车机上点一次；判据是
`logcat -s CarDesk/Car3d` 出现 `车模资源包结果 msg=…` 且设置页版本行变成 `N 个车型 · 当前 v1`。

## 2. 360 环影车机信号联动

**做了什么**：新增「按车机信号自动开关环影并切视角」，六条规则：

| 条件 | 动作 |
| --- | --- |
| 挂入 R 且「倒车视角」≠ 无 | 开环影 + 切倒车视角 |
| R→D 且「R→D 视角」≠ 无 | 开环影 + 切 R→D 视角 |
| P/N/未知 → D 且「D 挡视角」≠ 无 | 开环影 + 切 D 挡视角 |
| 挂入 N/P 且「N·P 挡关闭环影」开 | 关环影 |
| 雷达图形出现（上升沿）且「雷达图形触发」开、环影本来没开 | 只开环影（不切视角） |
| 其余 | 不动作 |

- 新增 `core/Camera360Policy.kt`（纯 Kotlin 决策层，JVM 可测）+ `vehicle/GearSource.kt`
  （挡位统一来源：模拟车辆通道 → `car_signal 29693` → `CarProperties CURRENT_GEAR` 兜底）
  + `vehicle/Camera360Link.kt`（订阅 / 去重 / 防抖 / 执行）。
- 挡位动作命中时**抑制**雷达动作（同一次变化不连写两次 360）；同一动作 signature
  1500ms 内不重复写；网关未就绪时 500ms 轮询最多 30s，之后每 3s 补绑。
- `vehicle/EcarxSignals.kt` 加 `SIG_GEAR = 29693`；`vehicle/HvacIds.kt` 加
  `FUNC_RADAR_GRAPHICS = 0x23010500`（值 != 0 出现 / 0 消失）。
- 设置页 `360 环影`：恢复「倒车视角 / R→D 视角 / D 挡视角 / N·P 挡关闭环影 / 雷达图形触发」，
  新增「模拟挡位（调试）」「模拟雷达（调试）」；改任意 360 配置后调 `Camera360Link.refresh()`，
  否则按 `(总开关|挡位|雷达)` 去重的决策层不会用上新配置。

**实测（5572，模拟车辆通道）**：
- `模拟挡位 R` → `联动决策 … actions=开环影并切视角 5（挂入倒挡）` → `联动执行：开环影 ok=true`
  → `切视角 view=5` → `360 视角状态同步 view=5`。
- `模拟挡位 N`（已开 N·P 关闭）→ `actions=关环影` → `联动执行：关环影 ok=true`。
- `模拟挡位 R` 再 `D`（R→D 视角=后视）→ `开环影并切视角 5（倒挡切前进挡）`。
- `模拟雷达` 打开 → `actions=开环影（不切视角）` → `联动执行：开环影（雷达图形） ok=true`。

**诚实的缺口**：
1. 真车的**转向灯信号**没有可验证通路 ⇒「转向灯视角」这一行**没有放出来**
   （`camera360LampView` 字段保留，等有信号再接）。设置页口径是「写进去必须有人读」，
   放了就是死项。
2. 车机厂商属性字节串（`0x21701500`，"08012002"=D / "08012006"=N / "08012004"=R）
   这条挡位通路**没接**：车控网关目前只有数字属性读写，没有字符串/字节通路。
   已记在 `GearSource` KDoc；真车若 29693 与 `CURRENT_GEAR` 都读不到，需要按这条补。
3. 实车置挡验证未做（模拟器上是用调试入口注入的）。

## 3. 海拔

- 新增 `core/AltitudeDesk.kt`：GPS（2s / 0m）+ 网络定位（10s / 10m）兜底，
  NMEA `GPGGA/GNGGA` 第 9 字段解析；新鲜期 30 分钟，30s 看门狗；每 60s 落盘；
  权限/服务异常全部安静降级（不影响桌面其它功能）。
- 车辆信息页「续航与油量」卡加第三行「海拔」；场景页天气块下方一行
  `海拔 42 m`（18px、弱文字、右对齐；无值/`--` 不画）。
- `AltitudeDesk.text()` 的更新回调接到两处视图（`LauncherView.startServices` /
  `onAttachedToWindow` 重启、`onDetachedFromWindow` 停）。

**实测（5572）**：`adb emu geo fix 121.473 31.230 42` →
`logcat -s CarDesk/Alt`：`海拔更新 m=42.0 source=gps`；截图两处均显示 `海拔 42 m`。

## 4. 仓库文案约束

`AGENTS.md` §2 新增硬性约定：本仓库任何文件、提交信息、release 说明里，
不得出现其它产品的名称或包名，也不得把功能来源描述成「取自外部产品」一类说法；
功能来源一律用中性表述（内置资产 / 远端资源包 / 车机通道 / 按本项目约定设计）。

配套 `tools/check_no_external_mentions.sh`：
- 默认只查**本次改动新增内容**（`git diff -U0 HEAD` 增行 + 暂存区 + 未跟踪新文件，跳过二进制）；
  存量历史文案只做 `--all` 清单（信息性，不判失败）。
- 打包身份文件（`app/build.gradle.kts`、`app/src/system/`、发布脚本）里允许出现发布用包名。
- 本轮已跑通：✓ 无新增命中。

---

## 改动文件

新增：`core/car3d/CarModelRemote.kt`、`core/Camera360Policy.kt`、`vehicle/GearSource.kt`、
`vehicle/Camera360Link.kt`、`core/AltitudeDesk.kt`、`tools/build_car_models.py`、
`tools/check_no_external_mentions.sh`、`tools/notes-r310.md`、三个单测文件。

修改：`ui/LauncherView.kt`（三处生命周期接线）、`ui/SettingsOverlay.kt`（360 页 + 车模资源包 +
`refreshCamera360Link`）、`core/car3d/Car3dAssets.kt`、`vehicle/EcarxSignals.kt`、
`vehicle/HvacIds.kt`、`core/log/LogTags.kt`、`ui/pages/VehiclePage.kt`、`ui/pages/ScenePage.kt`、
`app/src/test/.../SettingsDeadItemsTest.kt`（360 五项由「不该出现」翻成「必须有执行端」）、
`README.md`、`version.properties`、`.gitignore`。
