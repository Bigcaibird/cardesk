# CarDesk 在线更新实测（GitHub Release 通道）

第七十三轮：在模拟器上验证「设置 → 在线更新 → 检查更新 → 下载并更新」全链路。

- 环境：macOS arm64 + `toolchain/sdk/emulator`，`emulator-5554`
  - Android 10 / API 29 / 1920×1080 @160dpi / `adb root` 可用（uid=0）
- 被测包：`com.ecarx.cardesk`
  - 平台签名 + `sharedUserId=android.uid.system` → 装在 `/data/app`，uid=1000
  - 权限含 `INSTALL_PACKAGES` / `INTERNET` / `QUERY_ALL_PACKAGES`
- 基线：4135 / `1.0.1039-system`（第六十九轮第一个带 GitHub Release UI 的版本）
- 远端：Release `v1.0.1042`，asset `CarDesk-ecarx-cardesk-4138.apk`（1,888,392 B）

> ⚠️ 模拟器上同时存在另一个包 `com.ecarx.qq.tool`（4354 / `1.0.1027-system`，预装的旧版），
> 它是系统的默认 HOME。测 `com.ecarx.cardesk` 必须显式
> `am start -n com.ecarx.cardesk/com.cardesk.launcher.MainActivity`，
> 否则会看到旧包的界面，误判成「新版本没有新 UI」。

## 通过项

| 步骤 | 结果 | 证据 |
|---|---|---|
| 检查更新（4135） | 检出新版本 `1.0.1039-system → v1.0.1042`，带出 asset 名与大小 | upd-01、upd-02；日志 `检查更新：有新版本` |
| 下载 | 状态「下载中…」+ 进度条；落到 `cache/cardesk-update-1.0.1042.apk`，1,888,392 B | upd-03 |
| 安装 | **静默安装**（`PackageInstaller.Session`），无系统安装器弹窗；`installerPackageName=com.ecarx.cardesk` | `dumpsys package installer` |
| 安装结果 | `versionCode=4138` / `versionName=1.0.1042-system`；设备 `base.apk` 的 SHA256 与本地 `dist/CarDesk-ecarx-cardesk-4138.apk` **完全一致** | upd-04；`shasum` |
| 新版启动 | 4138 新桌面（浮屿新版 UI）正常渲染 | upd-04 |
| 复检（4138） | 再点「检查更新」→ **已是最新版本（1.0.1042-system）**，仓库最新 1.0.1042 | upd-06；日志 `检查更新：已是最新` |

日志摘录见同目录 `update-4138-log.txt`。

## 交互链路（两级按钮，容易误判）

设置左栏「在线更新」→ **内嵌页**（更新源 / 当前版本 / 说明）→ 页内「检查更新」
→ 弹出 `UpdateDialog` → 弹窗内再点一次「检查更新」才真正联网。

弹窗内按钮在 (652, 832)，页内按钮在 (735, 604)。只点页内那个会「看起来没反应」。

## 实测中发现的问题

1. **Release 说明里的 Markdown 表格不渲染**：`upd-02` 里「包信息」段落显示成裸的
   `| 项 | 值 |` / `|---|---|` / `| 包名 | 'com.ecarx.cardesk' |`。
   弹窗用的是纯文本渲染，没有 Markdown 解析。**不影响功能**，但观感是坏的。
2. **进度条长时间停在 0**：1.8 MB 的包，下载完成前进度条几乎不动（截图 18 秒仍是 0%）。
   实际下载是成功的，属于进度上报粒度问题。
3. **下载完成后无「安装中」提示**：状态直接由「下载中…」跳到安装，中间没有过渡文案。

## 未覆盖

- 模拟器 HOME 是预装的旧包 `com.ecarx.qq.tool`，本轮只验证了 `com.ecarx.cardesk`
  自更新；「旧版 HOME 被新包顶替」这条路径未测。
- 真车（领克 dcy11）上的在线更新未测：真车需车机系统账号可写 `/data`、以及
  平台签名包在 `/system/priv-app` 下的表现可能与 `/data/app` 不同。
