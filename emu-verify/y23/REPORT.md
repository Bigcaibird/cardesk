# 4199 应用抽屉「禁用 / 启用」模拟器验证

环境：AVD cardesk-aosp（API 34，1920×1080）
安装包：dist/CarDesk-platform-ecarx-cardesk-4199.apk（com.ecarx.cardesk，platform 证书）
权限：`CHANGE_COMPONENT_ENABLED_STATE: granted=true`

## 1. 已禁用应用在抽屉里的样子

`y23-emu-disabled-system-apps.png`（SIM Toolkit / Camera 等在系统里就是禁用态）

- 图标 alpha 0.35 压暗；
- 名称用弱色，并在下方多一行「已禁用」；
- 仍占格子、可长按排序，点它不会去启动，而是打开 ⋮ 菜单（唯一恢复入口）。

## 2. 禁用流程

`y23-emu-menu-disable.png` → `y23-emu-confirm.png` → `y23-emu-disabled.png`

- Email 的 ⋮ 菜单里有「禁用应用」；
- 点它弹确认：「禁用后「Email」会停止运行、图标变灰；随时可以在它的 ⋮ 菜单里重新启用。」；
- 确认后日志：
```
[EVT] 应用状态变更 pkg=com.android.email enabled=false
抽屉应用列表加载 count=17 costMs=5
```
`adb shell pm list packages -d | grep email` → `package:com.android.email`
抽屉里 Email 立刻变成灰图标 + 「已禁用」。

## 3. 启用流程

`y23-emu-re-enabled.png`

- 点 Email 的 ⋮ → 「启用应用」→ 图标恢复彩色、「已禁用」消失；
- 日志：`[EVT] 应用状态变更 pkg=com.android.email enabled=true`；
- `pm list packages -d` 里不再有 email。

## 4. 系统默认禁用的应用（组件级）

`y23-emu-sim-enabled.png`

- SIM Toolkit 这类应用在系统里是「入口组件被禁用」，清单 enabled 仍是 true；
  实现里另外查 `getApplicationEnabledSetting` / `getComponentEnabledSetting` 才判断正确。
- 菜单给的是「启用应用」；启用后应用级 + 入口组件一起打开，图标恢复正常，可正常启动。

## 5. 无系统权限时

普通签名包（如 debug 包）调用 `setApplicationEnabledSetting` 会抛 SecurityException：
只弹「没有系统权限，无法禁用/启用应用」，不改界面、不崩溃。
