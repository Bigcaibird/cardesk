#!/bin/bash
# 第193轮模拟器验证：全局 Dock 让位矩阵
#
# 用法：bash /tmp/stub/matrix.sh
# 每个用例：清日志 → 启动 App → 等 4s → 抓「让位」日志 + 三个窗口的可见性
export SDK=/Users/roy/Documents/desktopapp/toolchain/sdk
export PATH=$SDK/platform-tools:$PATH
A="adb -s emulator-5560"

run_case() {
  local label="$1" pkg="$2" expect="$3"
  echo ""
  echo "═══════════════════════════════════════════════════════════"
  echo "▶ $label   [$pkg]"
  echo "  期望：让位 = $expect"
  $A logcat -c
  $A shell am start -n "$pkg" >/dev/null 2>&1
  sleep 4
  local ylog
  ylog=$($A logcat -d -v time 2>&1 | grep -E "全局 Dock 让位" | tail -3)
  if [ -z "$ylog" ]; then
    echo "  让位日志：无（未发生让位状态变化）"
  else
    echo "$ylog" | sed 's/^/  /'
  fi
  echo "  ── 窗口状态 ──"
  python3 /tmp/stub/wins.py | sed 's/^/  /'
}

echo "###### 第193轮 · 全局 Dock 让位实测矩阵 ######"
echo "设备：emulator-5560 (API 28, 1920x1080@160)  包：com.ecarx.cardesk (第193轮修复版)"

# 1) 桌面：先回桌面并左右滑动，确认滑动不触发让位
echo ""
echo "═══════════════════════════════════════════════════════════"
echo "▶ 桌面 · 左右滑动"
echo "  期望：让位 = false（桌面永远不让位）"
$A logcat -c
$A shell am start -n com.ecarx.cardesk/com.cardesk.launcher.MainActivity >/dev/null 2>&1
sleep 3
$A shell input swipe 1100 600 400 600 350; sleep 1
$A shell input swipe 400 600 1100 600 350; sleep 1
$A shell input swipe 1400 600 500 600 350; sleep 2
ylog=$($A logcat -d -v time 2>&1 | grep -E "全局 Dock 让位" | tail -3)
if [ -z "$ylog" ]; then echo "  让位日志：无 ✓"; else echo "$ylog" | sed 's/^/  /'; fi
echo "  切页日志："; $A logcat -d -v time 2>&1 | grep -E "全局 Dock 页号同步" | tail -3 | sed 's/^/  /'
echo "  ── 窗口状态 ──"; python3 /tmp/stub/wins.py | sed 's/^/  /'

run_case "QQ音乐车机版"        com.tencent.qqmusiccar/.app.activity.AppStarterActivity  false
run_case "高德地图"            com.autonavi.amapautp.yh/com.autonavi.auto.remote.fill.UsbFillActivity false
run_case "网易云音乐(桩)"      com.netease.cloudmusic.iot/.StubActivity true
run_case "汽水音乐(桩)"        com.luna.music.car/.StubActivity         true
run_case "沉浸型App(非名单桩)" com.stub.immersive/.StubActivity          true
run_case "普通App(非名单桩)"   com.stub.plain/.StubActivity              false

echo ""
echo "###### 结束 ######"
