# 第三百一十五轮：按键增强（方控广播通路 + 媒体键放行 + 自测）

## 一、背景：为什么「按键增强」原来是坏的

不是配置读错，是**两条结构性缺陷**：

1. **收不到按键**。旧实现只在 `MainActivity.dispatchKeyEvent()` 里接按键 —— 也就是
   只有桌面在前台、且拿到焦点时才看得见。用户在导航 / 音乐里按方向盘语音键，
   事件根本到不了桌面。而车机上的方控物理键**不走标准 KeyEvent**，走的是 OEM 有序广播
   （`ecarx.intent.action.ECARX_KEY_RVOICEASSIST_EVENT` 等），extras 里是
   `ECARX_KEY_EVENT_TYPE`（键码，实车语音键是 `200231`）与
   `ECARX_KEY_ACTION_TYPE`（`0`=单击 / `1`=长按）。OEM 只提供「单击 / 长按」两种事件，
   **不提供双击** —— 双击只能由桌面自己按时序合成。
2. **媒体键被无条件吞掉**。旧 `onKeyEvent` 对媒体键一路 `return true`，即使「长按动作」配的是「无」，
   结果方控切歌失灵（短按被吃、又没执行任何动作）。

## 二、改动

### 新增（4 个文件）

| 文件 | 作用 |
| --- | --- |
| `app/src/main/java/com/cardesk/launcher/core/OemKeyProtocol.kt` | 广播协议纯逻辑：action / extra 名、键码（含 `200231` 语音键、`200087/200088` 方控媒体键）、`parse()`、`keyName()`、`describe()` |
| `app/src/main/java/com/cardesk/launcher/core/OemKeyGesture.kt` | 事件式手势机：`onClick` / `onLong` / `expire`，双击窗口 **380ms**（与参考实现的时序对齐），纯逻辑可 JVM 测 |
| `app/src/main/java/com/cardesk/launcher/ui/OemKeyRuntime.kt` | 广播总机：`attach/detach/dispatch/headless/reset`；**按通路去重**（同一事件走不同通路才去重） |
| `app/src/main/java/com/cardesk/launcher/ui/OemKeyReceiver.kt` + `OemKeyRegistry.kt` | 广播接收器（清单注册 + 动态注册两条通路）；`ACTION_MEDIA_BUTTON` 只取语音键、媒体键一律放行 |

### 修改

- `core/KeyGesture.kt`：双击窗口 `280ms → 380ms`，与事件式手势机对齐。
- `core/KeyActionResolver.kt`：`VOICE_KEY_CODES` / `isVoiceKey` / `isMediaPrev` / `isMediaNext` 统一委托
  `OemKeyProtocol`（单一来源）。**这是实测抓到的 bug**：只往协议里加 `200231` 时，
  `isHandled(200231)` 仍是 false，广播收到了却被当成「不认识的按键」。
- `ui/KeyActionExecutor.kt`（约 480 行）：
  - 新增 `onOemKey(event)`：语音键单击走 380ms 双击窗口（窗口内第二次 → 双击动作；双击没配 → 退化成一次单击动作；
    三个槽位都没配 → **不吞**，交给车机语音）、长按立即执行；
  - 媒体键：**没配长按动作 ⇒ 整颗键放行**；配了则短按用 `AudioManager.dispatchMediaKeyEvent()` 补发回系统；
  - `ACTION_VOICE_ASSIST` 改为发 OEM 有序广播 `ecarx.intent.action.ECARX_KEY_EVENT_RVOICE_EVENT`
    （permission `com.android.systemui.permission.PLUGIN`），失败再退回原来的 intent 探测；
  - `stop()` / `voiceConfigured()` / `lastOemKey`（诊断用）。
- `MainActivity.kt`：创建执行端时 `OemKeyRuntime.attach(...)`，`onDestroy` 里 detach。
- `ui/LauncherView.kt`：启动链加 `OemKeyRegistry.start()`（`svc:oemkey` 分段），销毁时 stop。
- `AndroidManifest.xml`：新增 `.ui.OemKeyReceiver`（`exported=true`、priority 2147483647、6 个 action）。
- `ui/dialogs/KeysDialog.kt`：新增「按键通路（车机广播）」分组：`最近按键：…` 诊断行 + 三个自测按钮
  （自测：单击 / 自测：长按 / 自测：媒体长按），自测直接把合成事件喂进执行端。

## 三、为什么还要「动态注册」

清单接收器实测**收不到**：`am broadcast` 发出去以后，logcat 只有

```
W BroadcastQueue: Background execution not allowed: receiving Intent { act=ecarx.intent.action.ECARX_KEY_RVOICEASSIST_EVENT … }
    to com.ecarx.cardesk/com.cardesk.launcher.ui.OemKeyReceiver
```

API 28 起，隐式广播不投递给「后台应用」的清单接收器。OEM 发的是普通隐式广播
（不是显式 `setPackage`），所以只靠清单注册在真机上同样不可靠。
⇒ 保留清单注册（前台 / 显式场景），再加 `OemKeyRegistry` 动态注册（进程活着就一定能收）。
两条通路会重复收到同一事件，所以 `OemKeyRuntime` 里做了**按通路去重**：

> 只有「同一键、同一手势、来自**不同通路**、且在 200ms 内」才忽略。
> 首版按「同键同手势」去重是错的 —— 双击的两次单击实测间隔只有 25ms，会被去重吃掉一次。

## 四、实测（emulator-5572，`com.ecarx.cardesk` 平台签名系统变体）

| 场景 | 结果 |
| --- | --- |
| 启动 | `svc:oemkey processMs=634`；`按键增强：方控广播已动态注册` |
| 广播接语音键单击（桌面在后台，前台是系统设置） | `收到方控广播 keyCode=200231 press=CLICK` → 380ms 后 `执行动作 source=方控语音键单击 action=1` → `切换 360 current=false` |
| 广播接语音键长按 | `press=LONG` → `执行动作 action=2` → `唤起车机语音 action=ecarx.intent.action.ECARX_KEY_EVENT_RVOICE_EVENT` |
| 媒体键短按（`input keyevent 88`，长按动作已配） | `按下 keyCode=88` → `短按转交系统 keyCode=88` → `抬起 gesture=NONE`（**不再被吞**） |
| 面板自测（单击） | `按键增强自测 press=CLICK` → `收到方控广播 … action=selftest` → `执行动作 action=1` → `自测结果 press=CLICK handled=true` |
| 面板自测（长按） | `press=LONG` → `执行动作 action=2` → `唤起车机语音` → `自测结果 press=LONG handled=true` |
| 崩溃 | `logcat -b crash` cardesk 0 条 |

截图：`emu-verify/shots-r315/r315-01-keys-dialog-channel.png`（面板下半部：媒体键配置、
「按键通路（车机广播）」分组、`最近按键：长按 · 语音键(200231)`、三个自测按钮）。

单测：`testSystemUnitTest --offline` → **2798 例 / 0 失败 / 0 错误 / 0 跳过**
（基线 2768，本轮新增 30：`OemKeyProtocolTest` 9、`OemKeyGestureTest` 10、`KeyChannelBroadcastTest` 11）。

## 五、判据

1. 桌面在后台时，发一次方控语音键广播（`am broadcast -a ecarx.intent.action.ECARX_KEY_RVOICEASSIST_EVENT
   -c android.intent.category.DEFAULT --ei ecarx.extra.ECARX_KEY_EVENT_TYPE 200231
   --ei ecarx.extra.ECARX_KEY_ACTION_TYPE 0`）⇒ `CarDesk/Keys` 出现「收到方控广播」与「执行动作」。
2. 媒体键短按且长按动作=无 ⇒ `onKeyEvent` 返回 false，系统照常切歌。
3. 面板自测三个按钮都能把事件喂进执行端（`自测结果 … handled=true`）。
4. `logcat -b crash` 无 cardesk 记录。

## 六、诚实缺口

- **真车未验证**：方控广播的发送方是否带 `FLAG_RECEIVER_INCLUDE_BACKGROUND`、`200231` 是否就是实车语音键、
  唤起语音的 ordered broadcast 是否还需要额外权限，都要等实车。模拟器上是用 root `am broadcast` 模拟的。
- **双击**：OEM 只给单击/长按，双击是桌面按 380ms 窗口合成的。车载场景下双击手感与手机不同，
  真车上要不要保留双击槽位待实车确认。
- **媒体长按**：模拟器造不出「按住 600ms」（`input keyevent --longpress` 的 down/up 同一毫秒），
  这条只在 Robolectric 虚拟时钟下验过。
- **`Input.requestKeysInterception` 兜底通路未实现**：`com.ecarx.xui.adaptapi.input.Input` 可以拦截
  `200231 / 200087 / 200088` 等键（白名单见下），但它会**真正吃掉**系统键事件，风险比广播高，
  等广播通路在实车确认可用后再决定要不要加。可拦截键白名单来自 `CarInputHandlingService.InputFilter`：
  `3, 85, NAVI, RADIO, MEDIA, SRC, SEEK_*, KNOB_*, R_NAVI/RADIO/MEDIA/SRC/SEEK/KNOB/HOME/BACK/CALL/ENDCALL/VOLUME_*,
  MENU, MEDIA_PLAY_PAUSE, R_MEDIA_NEXT(200087), R_MEDIA_PREVIOUS(200088), R_VOICE_ASSIST(200231), SETTINGS, DMSM_*, FINGER_TOUCH`。
