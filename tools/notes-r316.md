# 第三百一十六轮：合并「按键增强」的重复入口

## 一、用户反馈

> 「设置页里的按键增强设置页和里面的子项打开按键增强面板功能上看起来是重复的？」

确认属实。改动前有两套**编辑同一组 `Prefs`** 的界面：

| 入口 | 内容 | 实现 |
| --- | --- | --- |
| 设置 → 按键增强 | 总开关 + 语音键单击/双击/长按 + 媒体键上一曲/下一曲长按（含「切换驾驶模式」的目标模式） | `SettingsOverlay.pageKeys()` |
| 设置 → 按键增强 → **打开按键增强面板…**（弹窗） | **一模一样**的五项 + 同一组 `prefs.*Action/Mode` | `ui/dialogs/KeysDialog.kt` |

成因：设置页是后来按设计板 `s02-keys.png` 重做的（画板上就是完整编辑器），
旧的弹窗版当时没删，只在页面底部留了一个入口。

## 二、收口方式（已按用户选择执行）

**删掉弹窗，设置页成为唯一编辑入口**，并把弹窗里独有的两块东西搬进设置页：

1. 删除 `app/src/main/java/com/cardesk/launcher/ui/dialogs/KeysDialog.kt`（整文件）。
2. 删除设置页底部的「打开按键增强面板…」按钮、`SettingsOverlay.onShowKeys` 回调、
   `LauncherView.showKeys()` 与 `overlay.onShowKeys` 的接线、以及只给该按钮用的
   `KEY_PANEL_BTN_*` 三个常量。
3. 设置页「按键增强」底部新增 **「按键通路（车机广播）」** 自检区：
   - `最近按键`～ 显示最近一次收到的方控按键（读 `KeyActionExecutor.KEY_DIAG_LAST`，
     没收到过时提示「还没收到（在车上按一下语音键）」）；
   - `自测` 一组三枚 chip：`单击` / `长按` / `媒体长按`，
     点击直接合成一次方控事件喂进执行端（`OemKeyRuntime.dispatch(path = "selftest")`），
     用来在车上确认「配置 → 手势 → 动作」这条链是通的（真车没法用 adb 注入按键）。

## 三、为什么自测区留在设置页

第三百一十五轮把方控改成广播通路后，「按键有没有到」变成最需要现场确认的一件事。
自测区放在设置页而不是弹窗里，好处是**同屏能看到当前动作配置**：
点「长按」自测，看到的动作就是上面那张卡里配的「唤起语音助手」，不用来回切界面。

## 四、验证

- 单测 `testSystemUnitTest --offline`：**2798 例 / 0 失败 / 0 错误 / 0 跳过**。
  - `KeyChannelBroadcastTest` 里原来打弹窗的那条改成打设置页：
    `keysEnhanced = true` → 反射 `select("keys")` → 断言「按键通路（车机广播）」「最近按键」
    与三枚自测 chip 都在 → 点「媒体长按」→ 断言 360 被切换一次。
  - `PreviewRenderTest` / `DesignParityRenderTest` 原来 `showKeys()` 截的那两张图，
    改成「打开设置页 + 切到按键增强页」再截。
- 设备（emulator-5572，`com.ecarx.cardesk` 系统变体）：设置 → 按键增强，
  页面自上而下：总开关 / 语音助手·* 键（单击·双击·长按）/ 媒体按键（长按）（上一曲·下一曲）/
  按键通路（车机广播）（最近按键·自测三枚），底部无裁切、无重复入口。
  点「长按」自测 → `按键增强自测 press=LONG` → `收到方控广播 … action=selftest`
  → `执行动作 action=2` → `唤起车机语音 …ECARX_KEY_EVENT_RVOICE_EVENT`
  → `自测结果 press=LONG handled=true`；`logcat -b crash` 0 条。
  截图：`emu-verify/shots-r316/r316-01-keys-page-merged.png`（开发构建）、
  `r316-02-release-page.png`（发行包 `1.0.1235-preview` / 4331 复验，同样无重复入口）。

## 五、诚实缺口

- 自测 chip 沿用设置页 `chipRow` 的样式（未选中态是浅底 + 无描边），
  在这个页面上看起来偏「纯文字」，可点性不如选中的动作胶囊明显；
  真车上看是否需要加一枚图标或描边，等实车摸过再定。
- 点自测后整页会重建（为了刷新「最近按键」），滚动位置回到顶部；
  页面本身一屏放得下，暂不处理。

## 六、发布

- `tools/release.sh --preview`：`dist/CarDesk-platform-ecarx-cardesk-4331-preview.apk`
  （2,830,997 B，versionCode **4331** / versionName **1.0.1235-preview**，
  sha256 `e315eb17acb18a6ba2d8d460158d3e5dd0ccd54144185633ee493207a60dbbc1`），
  已上传 R2 `cardesk/preview/`（APK + `latest.json` 回读一致）。
- 发行包装机复验：`versionName=1.0.1235-preview`，按键增强页为合并后的单入口版本，
  `logcat -b crash` cardesk 0 条。
