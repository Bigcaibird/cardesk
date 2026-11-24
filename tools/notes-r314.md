# 第三百一十四轮：换更新通道后可直接切换（跨通道不比版本号）

## 背景

第三百零九轮起出包分**正式版 / 开发版**两条通道，各自一份清单、各自一套包。
但用户在设置里把「更新渠道」切过去之后，点「检查更新」得到的却是「已是最新」——
因为版本号是按 `versionCode` 比的：开发版已经 4326，正式版还停在 4315，
`4326 > 4315` 只说明开发版发得早，并不说明正式版旧。
两条分支的版本号各自递增，**跨分支的数字大小本来就没有意义**。

本轮改成：跨通道时不做版本比较，直接给「切换」入口；同通道内仍按 `versionCode` 判断。

## 改动

### `core/update/AppUpdate.kt`

- `channelOfVersionName(name)`：从 `versionName` 判断包属于哪条通道（含 `-preview` → 开发版，否则正式版）。
  打包脚本的版本号后缀是唯一依据，不猜别的。
- `installedChannel(context)`：当前装着的包属于哪条通道。
- `isBranchSwitch(target, installed)`：选中的通道和已装包的通道是否跨分支。
- `shouldOffer(release, localName, localCode, branchSwitch)`：跨分支 → 直接可更新；同分支 → 按 `isNewer` 比版本号。
- `check(context, channel, allowBranchSwitch = false)`：
  - 手动检查（更新对话框）传 `true`；
  - 启动自动检测保持 `false`（否则每次开机都提示「可切换到另一条通道」）。
- `CheckResult.Available` 增加 `branchSwitch` 字段，UI 据此换文案与按钮。
- `install(context, apk, allowDowngrade = false)`：降级安装开关。

### `core/SystemOps.kt`

- `installApk(context, apk, allowDowngrade)`：`allowDowngrade` 时反射
  `SessionParams.setAllowDowngrade(true)`（hidden 成员，compileSdk 29 编不到）。
  反射失败只记日志，继续按普通安装尝试，真实结果由安装回执报出。
- **顺带修一个老 bug**：安装回执的 `PendingIntent` 原来带了 `FLAG_IMMUTABLE`，
  系统把 `EXTRA_STATUS` 作为 fill-in 塞进来时会被丢掉 → 回执变成 `status=-1`，
  于是更新成功后也弹「安装失败」。现在去掉 `IMMUTABLE`（targetSdk 29 不强制），
  并且 `status < 0` 时只记「没有状态码」、不再误报失败。

### `ui/dialogs/ExtraDialogs.kt`（在线更新对话框）

- 手动检查传 `allowBranchSwitch = true`。
- 跨通道时：状态行「可切换到正式版（1.0.1219）」，详情里说明「当前安装的是开发版，
  选中的是正式版；两条通道版本号各自递增，跨通道不比较大小，这里直接把正式版的包换上来
  （装完版本号可能变小，属正常）」，按钮文案改成「下载并切换」，安装允许降级。
- 同通道时行为不变（「下载并更新」）。

### 测试

`app/src/test/java/com/cardesk/launcher/core/update/AppUpdateChannelTest.kt` 新增 4 组纯函数用例：

- `versionName` → 通道（`1.0.1230-preview` / `-PREVIEW` / `1.0.1219-system` / `?` / 空）；
- 跨通道判定（含大小写/空格脏值）；
- 跨通道时低 `versionCode` 也算可更新（4326-preview → 4315-system）；
- 同通道仍按版本号判断（更高才提示、相等/更低不打扰、清单没给 `versionCode` 时退回字符串比较）。

## 实测（emulator-5572，`com.ecarx.cardesk` 平台签名 system 变体）

设备原状态：`1.0.1230-preview（4326）`，偏好里的更新渠道已是「正式版」。

1. 设置 → 在线更新 → 检查更新：
   ```
   [EVT] 检查更新（手动触发） channel=正式版 url=https://file.chuxi.bid/cardesk/latest.json
   检查更新：跨通道，可直接切换 channel=正式版 installed=开发版
     current=1.0.1230-preview-system latest=1.0.1219 latestCode=4315
     apk=CarDesk-platform-ecarx-cardesk-4315.apk
   ```
2. 对话框显示「状态：可切换到正式版（1.0.1219）」+ 跨通道说明 + 按钮「下载并切换」。
3. 点「下载并切换」→（下行较慢，4,101,453 B 用了 257470 ms）：
   ```
   更新下载完成 file=cardesk-update-1.0.1219.apk bytes=4101453 elapsedMs=257470
   静默安装已提交 path=…/cache/cardesk-update-1.0.1219.apk bytes=4101453
   安装回执：成功 status=0
   ```
4. `dumpsys package com.ecarx.cardesk` → `versionCode=4315` / `versionName=1.0.1219-system`
   —— **降级安装成功**（4326 → 4315），且回执不再误报失败。

同通道行为未变：装回开发版后（渠道=开发版）点检查 → `检查更新：已是最新`
（同版本不提示）；启动自动检测在跨通道状态下也照常静默（`启动检查更新：已是最新`），
不会每次开机弹「可切换到另一条通道」。

## 发布包复验（1.0.1231-preview / 4327）

把 R2 上下载下来的发行包（`sha256 778a6635…`）装到 5572 上复验（不是本地构建）：

- **同通道**：设置 → 在线更新（渠道=开发版）→ 检查更新 → 状态「已是最新版本（1.0.1231-preview）」，
  日志 `检查更新：已是最新 channel=开发版 installed=开发版 current=1.0.1231-preview
  latest=1.0.1231-preview latestCode=4327` ⇒ 同通道不打扰。
- **跨通道**：渠道切到正式版 → 检查更新 → 日志
  `检查更新：跨通道，可直接切换 channel=正式版 installed=开发版
  current=1.0.1231-preview latest=1.0.1219 latestCode=4315`；
  对话框状态「可切换到正式版（1.0.1219）」，下面是跨通道说明与
  「安装包：CarDesk-platform-ecarx-cardesk-4315.apk（3.9111328 MB）」，
  按钮变成「检查更新」+「**下载并切换**」。
- 截图（`emu-verify/shots-r314/`）：

  | 文件 | 内容 |
  | --- | --- |
  | `r314-01-update-page.png` | 在线更新页：渠道=开发版、当前版本 1.0.1231-preview（4327）、检查更新/系统能力自检 |
  | `r314-02-dialog-uptodate.png` | 同通道检查后的对话框：状态「已是最新版本」、线上最新 1.0.1231-preview |
  | `r314-03-branch-switch-dialog.png` | 跨通道对话框：状态「可切换到正式版（1.0.1219）」+ 说明 + 「下载并切换」 |

## 判据

| 项 | 判据 |
| --- | --- |
| 跨通道不再比版本号 | 日志出现 `检查更新：跨通道，可直接切换`，状态行显示「可切换到…」 |
| 可点即换 | 点「下载并切换」后下载 → 静默安装 → `安装回执：成功 status=0` |
| 降级能装 | 设备 versionCode 由 4326 变成 4315 |
| 回执不再误报 | 日志不再出现 `安装回执：失败 status=-1` |
| 同通道不退化 | 同版本点检查仍显示「已是最新」；启动检测不弹跨通道提示 |
| 单测 | `testSystemUnitTest`：2768 例 / 0 失败 |

## 诚实缺口

- **只实测了 开发版 → 正式版 方向**（降级，最容易被系统拒的那种）。反向（正式版 → 开发版）
  走的是同一条下载/安装路径，只少了 `allowDowngrade` 这一项，机制上更宽松；本轮未单独跑。
- 正式版通道当前发布的 `1.0.1219-system（4315）` **没有「更新渠道」下拉**（该功能第三百零九轮才加），
  所以切到正式版后，要等正式版出一条带渠道选择的新包，才能在 UI 里切回来
  —— 这是「切分支就换成那一支的包」的必然结果，不是本轮引入的缺陷。
- `setAllowDowngrade` 是反射调的；如果某台车机的 `PackageInstaller.SessionParams`
  没有这个成员，降级会被系统拒（回执会报 `INSTALL_FAILED_VERSION_DOWNGRADE`），
  此时升级方向仍然可用。
