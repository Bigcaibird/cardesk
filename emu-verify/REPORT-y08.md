# 第九十五轮补丁 · 模拟器全面验证（REPORT-y08）

环境：AVD `cardesk-aosp`（Android 9 / SDK 29，1920×1080 @160dpi），
`app-debug.apk`（com.cardesk.launcher）直装，`appops SYSTEM_ALERT_WINDOW allow`，
清掉模拟器上遗留的 UNI 桌面与旧 CarDesk 包避免干扰。

| # | 项目 | 操作 | 结果 | 截图 |
|---|---|---|---|---|
| 1 | 冷启动先出画面 | force-stop 后重新 `am start`，350ms 截屏 | ✅ 启动页（CarDesk + 正在启动桌面…）立即出现 | `y08/y08-01-splash.png` |
| 2 | 启动分段 | `adb logcat -s CarDesk-Startup` | ✅ onCreate 88ms · window 107ms · splashFirstFrame 136ms · uiBuilt 278ms · launcherFirstFrame 281ms · servicesDone 362ms · launchServicesStarted 372ms | 日志 |
| 3 | 桌面 Dock 铺满 | 首屏截屏 | ✅ Dock 左右贴边 1920，快捷车控等控件留在卡内 | `y08/y08-02-home-initial.png` |
| 4 | 全局 Dock（其他应用） | 打开时钟/相册应用 | ✅ 窗口 `mAttrs=(0,968)(1920x112) ty=APPLICATION_OVERLAY`，整条 Dock 覆盖在第三方应用底部、铺满全宽 | `y08/y08-03-global-dock-over-app.png` |
| 5 | 应用抽屉 | Dock「全部应用」 | ✅ 网格 + 每格右上「⋮」 | `y08/y08-04-drawer.png` |
| 6 | C5 应用菜单 | 点格子「⋮」 | ✅ 重命名 / 应用信息 / 卸载 / 取消；系统应用显示「系统应用不支持卸载」 | `y08/y08-05-app-menu.png` |
| 7 | C5 重命名 | 菜单 → 重命名 → 输入 → 保存 | ✅ 抽屉标签立即变为 `SearchX`，冷启动后仍保留 | `y08/y08-06-rename-dialog.png` `y08/y08-07-renamed-persist.png` |
| 8 | C5 应用信息 | 菜单 → 应用信息 | ✅ `topResumedActivity=com.android.settings/.applications.InstalledAppDetails` | `y08/y08-11-app-info.png` |
| 9 | C3 最近任务 | 长按 Dock 主页键 | ✅ 打开「最近任务」面板；模拟器无 REAL_GET_TASKS → 优雅显示「暂无最近任务」 | `y08/y08-08-recents.png` |
| 10 | C3 返回键 | 最近任务页按 BACK | ⚠️ 首次发现面板不关（叠在桌面/设置下面）→ 已修 `LauncherView.onBack()`；复测 ✅ 面板关闭、视图树无「最近任务」 | `y08/y08-14-back-closes-recents.png` |
| 11 | C2 卡片库 | 设置 → 桌面卡片 → 添加「行程」卡 | ✅ 「卡片库」列出未上桌的卡，一键添加 | `y08/y08-09-settings-cards.png` |
| 12 | C2 尺寸档位 | 空调卡选「小」 | ✅ 空调卡变窄，三个控制位等比缩小、无溢出；冷启动后保留 | `y08/y08-12-card-small-trip.png` |
| 13 | C2 拖拽排序 | 长按音乐卡拖到空调卡后面 | ✅ 顺序变为 快捷应用 → 快捷车控 → 音乐 → …，冷启动后顺序保留 | `y08/y08-13-drag-order-persist.png` |
| 14 | C4 原厂设置目录 | 设置 → 系统 → 原厂设置 | ✅ 从系统实时解析出 系统设置/无线和网络/WLAN/蓝牙/显示/声音/语言与输入法… 并标注来源 | `y08/y08-10-oem-settings.png` |
| 15 | C4 语义化打开 | 点「系统设置」 | ✅ `topResumedActivity=com.android.settings/.homepage.SettingsHomepageActivity` | — |
| 16 | 页面左右切换 | 首页横向滑动 | ✅ 场景页 ↔ 导航页切换正常 | — |
| 17 | 崩溃/ANR | `logcat` 全程 | ✅ `FATAL EXCEPTION` 0 条、无 `ANR in com.cardesk` | — |

## 已知 & 未覆盖
- debug 包无平台签名：`policy_control` 写入失败（`全局 Dock 策略已同步 applied=false`），
  系统导航栏仍显示 —— 真机 platform 包可写（第八十七轮已验），需真车确认「原车 Dock 消失」。
- 模拟器无 `REAL_GET_TASKS`：最近任务列表为空（代码已降级，不崩）。
- 未验证项：车控真实信号（模拟器走 Mock）、高德红绿灯广播（无高德实车数据）、
  氛围灯硬件、RRO 真预留。
- 冷启动第二次会打开「通知使用权」系统页（桌面首次引导逻辑，原有行为）。
