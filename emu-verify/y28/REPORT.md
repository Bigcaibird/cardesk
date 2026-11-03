# 4197 全局 Dock 预留区 模拟器验证

环境：AVD cardesk-aosp（API 34，1920×1080）
安装包：dist/CarDesk-platform-ecarx-cardesk-4197.apk（包名 com.ecarx.cardesk，platform 证书 + sharedUserId system）

## 1. 预留区自动启用（成功路径）

adb logcat -s CarDesk/Dock：
```
全局 Dock 服务启动 overlay=true
尝试启用系统预留区
系统预留区启用结果 ok=true supported=true applied=true
```

RRO 覆盖包状态（cmd overlay list）：
```
[x] com.ecarx.cardesk.insets
```

Dock 窗口（dumpsys window）：
```
mAttrs={(0,968)(1920x112) gr=TOP LEFT CENTER ... ty=NAVIGATION_BAR_PANEL fmt=TRANSLUCENT
```
=> 窗口类型已经从 APPLICATION_OVERLAY(2038) 换成 NAVIGATION_BAR_PANEL(2024)，
   正好占据系统导航栏预留的 112px。

## 2. 桌面：只有一条 Dock

y28-home-single-dock.png
- 底部只有全局 Dock；桌面自己的 Dock 已让位（LauncherView.applyDockVisibility 的 globalReserved=true）。
- 桌面内容整体在 Dock 上方。

## 3. 其它应用：内容不再被 Dock 遮挡

y28-settings-content-above-dock.png（系统设置）
- 设置列表最后一行在 Dock 上方结束，没有被 Dock 压住。

## 4. 对照：预留区不可用时的旧行为

y28-fallback-covered-bottom.png
- 强制撤掉 RRO 后（模拟「无平台权限/覆盖包不可用」环境），应用仍然全屏，
  底部内容被 Dock 盖住 —— 这就是修复前用户看到的现象。

## 5. 设置页

y28-dock-settings-page.png（桌面设置 → Dock 状态栏）
