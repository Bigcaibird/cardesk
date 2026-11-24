# CarDesk 4322 / 1.0.1226-preview · 第三百一十一轮

一件事：**车模资源包改成自动补齐 + 更新后立即生效**。

验证环境：AVD `Lynkco_820A_car15_arm64`（`emulator-5572`，API 28 / arm64 / 平台签名）。
构建：`assembleSystem -PcardeskPackage=com.ecarx.cardesk --offline`；单测 **2761 条全绿**。

---

## 1. 自动补齐（`core/car3d/CarModelAutoSync.kt`）

**需求**：启动时先看本地有没有最新车模；有就什么都不做（不联网），没有就一次把所有车型都下下来；
失败静默重试（5 分钟一次）；换车模不用重启。

**判定口径**：本地「齐全」= `versions.json` 覆盖全部期望目录 `{lynk01, lynk03, lynk05}`
（`CarModels.MODEL_IDS` 映射而来）。齐全 ⇒ **一条 INFO 直接结束，零网络请求**；
不齐 ⇒ 后台同步远端清单，`CarModelRemote.sync()` 内部遍历**清单里所有车型**逐个比较版本并安装
（因此「一次性全量」是既有能力，本轮只是把它接到启动路径）。

**线程模型**：

- 单条 daemon 线程 `car-model-sync`（`THREAD_PRIORITY_BACKGROUND`）做全部磁盘 + 网络 IO；
  `start()` 在主线程只登记不做事，幂等。
- 每轮：读本地 → 齐全就结束 → 不齐就同步 → **每次尝试后重新读本地**：
  齐了回调一次 `onUpdated(...)` 并退出；仍不齐记 `W` 日志后 `Condition.awaitNanos(300000)` 等下一轮。
- `stop()` 递增世代号 + `signalAll()`：正在等的重试立刻醒、过期轮次的结果一律丢弃；
  另用一把 `syncLock` 串行化「自动补齐」与「设置页手动检查」，防两个线程写同名 `.part`。

**失败判定**：只看 `SyncResult.error != null || failedDirs.isNotEmpty()`。
`skipped=true` 只表示「这次没下载」，不能当结果用（索引缓存命中/已是最新都会是 true）。

## 2. 更新后立即生效

- 下载成功后主线程 `Car3dAssets.reset()`（回收位图 + 清 `model.json` / 缺图记录 / 失败计数）
  再 `refresh()`，下一帧就从新目录解码 —— 以前要杀进程重启才换图。
- 自动补齐走 `CarModelAutoSync.onUpdated`；手动检查走设置页回调，两条路径都做了 reset + `onApply`。
- 设置页「检查车模资源更新…」改成 `CarModelAutoSync.syncNow()`（`force = true`）：
  原来的 `CarModelRemote.check()` 复用 10 分钟索引缓存，刚发布的资源包点了也只会显示「全部最新」。
- 「车模编号」chip 改完立刻 `onApply`，不再等车辆数据每秒刷新那一拍。

## 3. 实测

| 场景 | 结果 |
| --- | --- |
| 本地已齐全，冷启动 | `本地车模齐全，不发起网络请求 dirs=lynk01,lynk03,lynk05 attempt=0` —— 零网络 |
| 清数据后首次启动（联网） | `本地车模不齐全，开始后台补齐 missing=…` → 三个车型全部装好 → `车模补齐完成，本地已齐全 … clean=true` → `车模资源包已补齐，解码缓存失效 dirs=[…]` |
| 清数据 + 断网启动 | `车模清单下载失败：Unable to resolve host …` → `车模仍未补齐，5 分钟后重试 attempt=1 …`，桌面照常显示内置车模、无崩溃 |
| 断网启动后恢复网络（不重启 App） | `13:04:32` 首轮失败 `attempt=1` → 网络上来的情况下等到 `13:09:34`（5 分 02 秒后）同一个 pid 自动重试：`车模已安装 dir=lynk01/03/05 version=5` → `车模补齐完成，本地已齐全 … clean=true` → `车模资源包已补齐，解码缓存失效`；之后冷启动日志为 `本地车模齐全，不发起网络请求 attempt=0` |
| 手动更新到 v4（临时改 `model.json` 名字 + 换 `closed.webp`） | 同进程内（pid 不变）设置页从「领克 03 · 更新验证」变成「领克 03 · 立即生效验证」、`本地资源包 3 个车型 · 当前 v4` |
| 再发 v5（= 还原后的正式内容）后手动更新 | 同进程内回到「领克 03」、`当前 v5`，车形恢复 |
| 全量单测 | `testSystemUnitTest` **2761 / 0 / 0**（新增 `CarModelAutoSyncTest` 10 例） |

远端现在发布的是 **v5 = 与仓库内置资产一致**的正式内容（验证用的 v2 / v4 是临时构造的差异包，
只用于证明「更新可见」与「立即生效」，不是产品内容）。

## 4. 改动文件

- 新增 `app/src/main/java/com/cardesk/launcher/core/car3d/CarModelAutoSync.kt`
- 新增 `app/src/test/java/com/cardesk/launcher/core/CarModelAutoSyncTest.kt`
- `ui/LauncherView.kt`：启动/attach/detach 三处接线 + `onUpdated` 回调
- `ui/SettingsOverlay.kt`：手动检查改 `syncNow()`（force）+ 结果文案；车模编号 chip 立即下发
- `core/car3d/CarModelAutoSync.kt` 的 `syncNow()` 供设置页复用同一把锁
- `README.md`：车模段落改写（自动校验 / 一次补齐 / 5 分钟重试 / 免重启生效）
- `version.properties`：4322 / 1.0.1226-preview
- `app/build.gradle.kts`：历史注释里的第三方指代改成中性表述（新约束）

## 5. 边界与缺口

- 「本地齐全」只按目录与版本号判断，不比 sha256：本地文件被外部改坏时不会自愈
  （`Car3dAssets` 有 `FAILS_BEFORE_BROKEN` 兜底，会回退自绘车模）。
- `stop()` 不能中断已在进行的 HTTP 调用，只能等它返回后丢弃结果。
- 自动补齐只在「缺」的时候联网：远端发了新版本而本地已齐全时，启动不会主动拉新版
  （按需求「有就什么都不做」）；要拿新版走设置页手动检查，或清掉本地版本表。
- 真车验证仍待：轮换/弱网下的重试节奏只在本机 AVD 上验过。

## 6. 截图（`emu-verify/shots-r311/`）

| 文件 | 内容 |
| --- | --- |
| `r311-01-carmodel-before.png` | 自动补齐装好 v2 后：`写实车模（领克 03 · 更新验证）`、`本地资源包 3 个车型 · 当前 v2` |
| `r311-02-carmodel-after-manual.png` | 手动检查（force）装 v4 后、**同进程**：标题变 `领克 03 · 立即生效验证`、`当前 v4` |
| `r311-03-carmodel-restored.png` | 再发 v5（正式内容）手动更新后、**同进程**：回到 `领克 03`、`当前 v5` |
| `r311-04-desktop-after-retry.png` | 断网启动 → 恢复网络 → 5 分钟重试自动装齐后的桌面：车模已是 v5 车形，**进程未重启** |
| `r311-05-settings-after-retry.png` | 同一时刻的设置页：`写实车模（领克 03）`、`本地资源包 3 个车型 · 当前 v5` |
