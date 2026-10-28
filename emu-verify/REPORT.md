# CarDesk 模拟器实测报告（Android 14 / API 34）

- 环境：macOS arm64 + `toolchain/sdk/emulator`（qemu arm64，Hypervisor.Framework 加速）
- AVD：`cardesk`，系统镜像 `system-images/android-34/google_apis/arm64-v8a`，1920×1080 @160dpi（正好命中 820A 判定）
- 安装：debug APK → `cmd package set-home-activity com.cardesk.launcher/.MainActivity`
  → `cmd notification allow_listener com.cardesk.launcher/com.cardesk.launcher.core.media.MediaNotificationListener`
- 复现：`python3 tools/emu_verify.py boot|install|shot <name>|tap <x> <y>`
- 全部证据图在本目录 `emu-*.png`；清单见 `../EMULATOR-VERIFY.md`

## 通过项（可重复）

| 项 | 证据 | 说明 |
|---|---|---|
| 安装/启动/设为 HOME | emu-02-home.png、`dumpsys activity` topResumedActivity=com.cardesk.launcher | 桌面接管 HOME 键；按 HOME 从其他 App 返回桌面 |
| 桌面渲染（日间） | emu-02-home.png | 导航卡/天气/五张卡片/状态栏/Dock/气泡 |
| 空调整页 | emu-18-climate-actions.png | 电源开、AC 开、风量 7 档、左加热 1、前除雾开；Dock 同步（风扇 7 段、A/C 蓝、23°） |
| 空调控件覆盖 | 同上 + 状态条 | 电源/温度±/风量柱/吹风方向/AC/AUTO/内外循环/除霜/座椅/MAX/全吹 都可点选并乐观更新 |
| 应用抽屉 | emu-09-drawer.png | 真实 PackageManager 读到 18 个可启动应用，图标来自各自 APK |
| 启动第三方应用 | `logcat` START u0 pkg=com.google.android.calendar from uid 10192 | 抽屉点 Calendar 全屏启动；按 HOME 回桌面 |
| 音乐整页（无会话） | emu-19-music.png | 无媒体会话时显示「通用音乐/已暂停/暂无播放/歌词加载中」，不崩溃 |
| 设置面板（全局/系统分页） | emu-24-settings-opaque.png、emu-25-platform.png | 双栏 1320×860、左导航 7+11 项、VIN/开通入口 |
| 平台识别 | emu-25-platform.png | 820A（densityDpi=160、长边 1920）；签名非平台证书；uid 普通用户；默认桌面=是；通知使用权=已授权 |
| 车控通道优先级 | 同上 | AdaptAPI → AOSP Car 属性 → ECarXCar 信号 → 模拟车辆；当前=模拟车辆（模拟器无车） |
| 系统能力自检 | 同上 | 静默安装/系统设置写入=不可用（debug 签名），与预期一致 |
| 局域网传输 | emu-30-lan-started.png | 启动成功 `http://10.0.2.15:18765/?t=<token>`，文件列表 1 个；`adb forward` 后 host `curl` 带 token 返回 200（613B HTML），不带 token 返回 403 |
| 计划任务 | `dumpsys alarm` 出现 `*walarm*:com.cardesk.launcher.action.SCHEDULED_TASK`（ELAPSED_WAKEUP）并在 20s 后触发；`logcat` 有 START pkg=com.google.android.calendar from uid 10192 | AlarmManager 调度与 TaskReceiver 执行链路成立 |
| 返回键语义 | `adb shell input keyevent 4` | 依次关任务编辑器/任务列表/对话框/槽位面板/设置/360/空调/回首页；不 finish Activity |

## 实测中发现并修复的缺陷

1. **全屏覆盖层吞掉触摸**：`PageDotsView`、`ShellStatusBar` 的 `onTouchEvent` 无条件 `return true`，
   其下所有页面（气候页、抽屉、设置…）都点不到，只有 Dock 能点。
   修复：仅在命中热区时消费 DOWN，其余位置放行。
2. **面板空白处点击误关闭**：`ClimatePage`/`DrawerPage`/`DialogSheet`/`SettingsOverlay` 的卡片不消费触摸，
   点空白会穿透到遮罩关闭页面。修复：卡片 `isClickable=true` + 空点击监听。
3. **日间主题不一致**：`Skin` 全是 `const val`，日间只换壁纸；sheet 填充写死深色；
   `ClimatePage`/`CabinView`/`DrawerPage` 使用夜间色 → 白底白字 / 黑底黑字。
   修复：`Skin.apply(pal)` 动态 token（含不透明 `SHEET_FILL`/`SHEET_FILL_SOFT`），控件改读 token。
4. **模拟器副屏抢焦点**（环境问题）：`com.android.emulator.multidisplay` 的 secondary display
   Launcher 让 `mCurrentFocus=null`，`input tap` 全部无效。修复：
   `adb shell pm disable-user com.android.emulator.multidisplay` 后重启模拟器。
5. UI 细节：LAN 文件列表内联显示；按钮内边距（文字被裁）；抽屉分段控件主题色。

## 模拟器上无法验证（需真车或系统权限）

- 真车通道：ECARX AdaptAPI / AOSP Car 属性 / ECarXCar 私有信号（模拟器无对应服务，自动降级到模拟车辆）
- 360 真实视频：AVMManager 需车机服务，模拟器只验证叠加层与视角切换交互
- HUD：需要真实 BLE 串口设备与未公开帧协议
- 媒体会话与在线歌词：需安装车机版音乐 App 并授予通知使用权
- 系统级能力（静默安装 / ADB 开关 / 重启 / 按键注入）：需把 system 版推到 `/system/priv-app`
  并让系统信任平台证书（`-writable-system` + `adb root` + `remount`，或直接刷入镜像）

## 后续可做

1. 用 `-writable-system` 重启模拟器，`adb root && adb remount` 后把 `dist/CarDesk-replace-ecarx-4354.apk`
   推到 `/system/priv-app/CarDesk/CarDesk.apk`，重启验证 uid=1000、「平台与兼容」页系统能力全部变可用、
   重启/静默安装链路；
2. 安装一个车机版音乐 App（或写一个最小 MediaSession 测试 App）验证媒体接管与歌词回退；
3. 用两个 BLE 设备模拟 HUD 串口，补协议解析。

---

# Android 10（API 29）实测

- 新增 AVD `cardesk29`：`system-images/android-29/google_apis/arm64-v8a`（1.17GB 镜像），同样 1920×1080 @160dpi
- 同样的 debug APK、同样的 `set-home-activity` + 通知使用权授权
- 证据图：`emu29-*.png`

## 通过项（与 API 34 行为一致）

| 项 | 证据 |
|---|---|
| 安装/启动/设为 HOME | emu29-02-home.png；`mResumedActivity=com.cardesk.launcher/.MainActivity` |
| 空调页 + 全部控件 | emu29-04-climate-actions.png：电源开、风量 7 档、A/C 开、前除雾开、左加热 1、Dock 同步 |
| 抽屉（真实应用列表） | emu29-05-drawer.png |
| 启动第三方应用 | `ActivityTaskManager: START pkg=com.google.android.calendar from uid 10144` |
| HOME 键回桌面 | `mResumedActivity` 回到 CarDesk |
| 设置双栏 + 各页面 | emu29-06-settings.png、emu29-10-daynight.png（桌面布局滑杆/开关） |
| 平台识别 | emu29-07-platform.png：820A、Android 29 / arm64-v8a、非平台证书、普通 uid、默认桌面=是、通知使用权=已授权、系统能力不可用 |
| 局域网传输 | emu29-08-lan.png；host `curl` 带 token 200（613B），无 token 403 |
| 计划任务 | `dumpsys alarm` 出现 `*walarm*:com.cardesk.launcher.action.SCHEDULED_TASK`；到点后 `START pkg=com.google.android.calendar from uid 10144` |

## Android 10 与 14 的差异 / 已知问题

1. **日间主题底色偏暗（待修）**：`CarDeskTheme` 日志确认 `night=false hour=14 tz=Asia/Shanghai`（选中日间调色板），
   但桌面背景仍呈夜间的深蓝渐变，卡片/面板却是日间（白）——即背景层取到了错误调色板。
   同一 APK 在 API 34 上日间背景正常，故是 API 29 特有的绘制/时序问题，下一步在 `BackgroundView` 首帧
   （`onSizeChanged` → `build()` 顺序）取 `palette` 的时机上排查。
2. 沉浸式全屏确认弹窗（"Viewing full screen / Got it"）在两者都会出现，点掉即可；不影响功能。
3. `cmd package set-home-activity`、`cmd notification allow_listener` 在 API 29 上均可用，与 API 34 相同。

## 复现命令（Android 10）

```bash
cd toolchain/sdk
ANDROID_SDK_ROOT=$PWD ANDROID_HOME=$PWD emulator/emulator -avd cardesk29 \
  -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect -no-snapshot -memory 4096 -cores 4
adb wait-for-device
adb install -r ../cardesk/dist/CarDesk-debug.apk
adb shell cmd package set-home-activity com.cardesk.launcher/.MainActivity
adb shell cmd notification allow_listener com.cardesk.launcher/com.cardesk.launcher.core.media.MediaNotificationListener
adb shell am start -n com.cardesk.launcher/.MainActivity
```

## Android 10 修复：日间主题底色

- 根因：桌面窗口/根视图不带不透明底色，`BackgroundView` 的渐变在某些首帧/合成路径下没有盖住系统壁纸，
  Android 10 默认壁纸正好是深蓝弧形，于是出现「卡片/面板日间、底色夜间」的混搭；API 34 上壁纸为浅色所以看不出来。
- 修复：`LauncherView.build()` 里 `setBackgroundColor(pal.bgTop)`（跟随主题的底色），`BackgroundView` 仍在其上画渐变与壁纸。
- 验证：`emu29-16-opaque.png`（日间，与 API 34 的 `emu34-day.png` 一致）、`emu-verify/emu29-16-opaque.png` 像素抽样底色为 #E8EEF8。
- 复测命令：`adb -s emulator-5554 install -r dist/CarDesk-debug.apk && adb -s emulator-5554 shell am force-stop com.cardesk.launcher && adb -s emulator-5554 shell am start -n com.cardesk.launcher/.MainActivity`

---

# 系统级验证（root + priv-app + 平台签名，AOSP API 29）

之前所有验证都是 debug 签名，系统能力显示「不可用」；这一轮验证「预装进系统」后能力是否真的打开。

## 关键结论：模拟器两种镜像的签名不同

| 镜像 | `ro.build.tags` | framework 证书 SHA-256 | 能否装 `sharedUserId="android.uid.system"` |
|---|---|---|---|
| `google_apis`（Google 版模拟器） | `dev-keys` | `301aa3cb…f866aa` | ❌ 与我们的 AOSP 测试平台证书不一致 |
| `default`（AOSP 版模拟器） | `test-keys` | `c8a2e9bc…2b8` | ✅ 与 `platform.pk8` 一致 |

所以「用 Google APIs 镜像 + 自己的 testkey 构建的 system APK」会因 `INSTALL_FAILED_SHARED_USER_INCOMPATIBLE`
装不进去；要验证系统能力必须用 **AOSP `default` 镜像**（`system-images;android-29;default;arm64-v8a`）或真机
（真机的 platform key 只在厂商手里）。

## 复现步骤（已验证）

```bash
# 1) AOSP test-keys 镜像 + 可写系统分区
emulator -avd cardesk-aosp -no-window -no-audio -gpu swiftshader_indirect \
         -no-snapshot -writable-system -memory 3072 -cores 4

# 2) root + remount
adb root && adb remount

# 3) 预安装（脚本已入库）
cardesk/tools/push_system_app.sh            # 推 APK + 两条白名单 + reboot
```

`push_system_app.sh` 会补两个白名单——缺了会 **system_server 启动崩溃**：

1. `/system/etc/permissions/privapp-permissions-com.ecarx.qq.tool.xml`
   （否则报 `Signature|privileged permissions not in privapp-permissions whitelist` 并无限重启）
2. `/system/etc/sysconfig/hiddenapi-package-whitelist.xml`
   （`ActivityView` 是 @SystemApi，需允许反射）

## 实测结果（截图 `emu-verify/aosp-*.png`）

| 项 | 结果 |
|---|---|
| 安装位置 | `pm path` = `/system/priv-app/CarDesk/CarDesk.apk` |
| uid | `userId=1000`、`sharedUser=android.uid.system/1000`、`pkgFlags=[SYSTEM …]` |
| 平台页 | 签名=平台证书、uid=1000 (system)、通知使用权可授权（`aosp-04/05`） |
| 系统能力 | **静默安装 / 系统设置写入 / 重启 / 按键注入 / 系统 uid 全部「可用」** |
| 静默安装 | 本地 HTTP 服务 + `PackageInstaller.Session` 安装 `com.cardesk.probe` 成功，界面显示「状态：已提交安装（静默）」，无系统安装器弹窗 |
| 系统设置写入 | logcat `SettingsProvider: Notifying … content://settings/global/adb_enabled`（应用写入了 secure settings） |
| ActivityView 真嵌入 | 浮窗标题变 **嵌入承载**，虚拟屏 `ActivityViewVirtualDisplay@… 920x618 owner com.ecarx.qq.tool (uid 1000)`，日历 UI 真实渲染进桌面（`aosp-17-embed3.png`） |

## 为让这两条路径可用而改的代码

1. `AndroidManifest.xml` 增加 `android:usesCleartextTraffic="true"`：
   默认 targetSdk 34 禁止明文 HTTP，导致「在线更新」下载失败（参考 App 的 LAN/OTA 也是 HTTP）。
2. `FloatWindowLayer.tryEmbedActivity` 改为：
   - 构造器三级回退 `(Context, AttributeSet, int)` → `(Context, AttributeSet)` → `(Context)`；
   - 回调类型从 `setCallback` 的方法签名里取（Q 上叫 `ActivityView$StateCallback` 且是**抽象类**，无法用 `Proxy`）；
   - 无法走回调时，`view.post { startActivity(intent) }` 直接拉起。
3. AOSP 上 `Class.forName("android.app.ActivityView$Callback")` 会 `ClassNotFoundException`（Q 只有 `StateCallback`），故不再写死类名。

## 仍然无法在模拟器上验证的

- **车控真通道**：`com.ecarx.xui.adaptapi` / `ecarx.car.ECarXCar` 只在领克车机上，模拟器上按设计回退到「模拟车辆」。
- **360 真实摄像头画面**：需要 `EcarxAVMManager` 与摄像头 HAL。
- **HUD 蓝牙帧协议**：参考 App 未公开，需实机抓包。
- **媒体会话**：可以验证（通知使用权+MediaSession 已通），但要装带 MediaSession 的音乐 App 才能看到真实封面/歌词。
