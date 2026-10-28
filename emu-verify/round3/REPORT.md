# 模拟器实测 · 第三轮（车控链路修复）

- 环境：`emulator-5556`（Android 14 / API 34，google_apis arm64-v8a），1920×1080 @160dpi
- 安装：`cardesk/app/build/outputs/apk/debug/app-debug.apk`
- 启动：`cmd package set-home-activity com.cardesk.launcher/.MainActivity` → `am start`
- 副屏干扰已处理：`pm disable-user com.android.emulator.multidisplay`

## 目的

车控写入链路按参考实现对齐后，验证三件事：
1. 新增的「车控通道状态」区块能正常渲染；
2. 新增的「测试写入」按钮能跑通并给出可读结果；
3. 空调写入级联（电源 / A/C / MAX / 关机）仍然正确，且无 FATAL。

> ⚠️ 模拟器**没有** AdaptAPI / AOSP Car，真车写入路径**无法**在此验证。
> 本轮只验证「模拟通道降级 + 诊断 UI + 写入级联」，真车验证需按
> `cardesk/README.md` 的「真机自测步骤」在实车上进行。

## 通过项

| 项 | 证据 | 说明 |
|---|---|---|
| 桌面启动无崩溃 | `01-launch.png` | `logcat -b crash` 全空 |
| 设置页 → 系统 → 平台与兼容 | `03-system-tab.png`、`04-platform.png` | 新增「车控通道状态」区块正常渲染：当前生效通道 / 通道连接 / 车机服务 / 最近一次写入 |
| 测试写入按钮 | `06-selftest.png` | Toast「写入成功，回读失败（该通道不支持回读）」——模拟通道不实现属性回读，符合预期；真机上应显示「回读一致」 |
| 空调写入级联 | `07-hvac-taps.png` | 点 A/C → 点 MAX（风量 7）→ 点电源：风量条回到 0 档，级联清零正确 |
| 无 FATAL / 无 cardesk 异常 | `logcat -d -b crash` + `logcat -d \| grep cardesk` | 仅有 force-stop 时的 WindowManager 无关告警 |

## 未能验证（需真车）

- AdaptAPI `setFunctionValue` 的 3 参 / 2 参重载是否被固件接受；
- zone=1 的全局写入是否生效（zone=0 被拒的推断）；
- 除霜 `setDefrostVal` 的 `{2,1,0}` 回退是否需要；
- 360 两参 `setFunctionValue(FUNCTION_360, value)` 是否被接受；
- AOSP Car 通道的 `setProperty` 值类型是否被固件接受。

## 复现命令

```bash
export PATH="/Users/roy/Documents/desktopapp/toolchain/sdk/platform-tools:$PATH"
adb -s emulator-5556 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5556 shell pm disable-user com.android.emulator.multidisplay
adb -s emulator-5556 shell cmd package set-home-activity com.cardesk.launcher/.MainActivity
adb -s emulator-5556 logcat -c
adb -s emulator-5556 shell am start -n com.cardesk.launcher/.MainActivity
# 设置页：点左上角头像(70,52) → 系统 tab(543,221) → 左栏下滚 → 平台与兼容(397,623)
# 车控状态区块下滚后「测试写入」按钮在 (838,549)
adb -s emulator-5556 logcat -d -b crash
```
