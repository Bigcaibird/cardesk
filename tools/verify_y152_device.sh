#!/usr/bin/env bash
# 第152轮真机验证：应用抽屉 UI 重做
#
# 验证五件事：
#   ① 抽屉里没有「⋮」了（以前贴着格子右沿，离自己的图标 242px）；
#   ② 长按图标弹出应用菜单（重命名 / 应用信息 / 禁用·启用 / 卸载）；
#   ③ 点按图标仍然启动应用（长按改菜单之后，单击没被误伤）；
#   ④ 应用名不再被截成省略号、图标在格子里居中；
#   ⑤ 菜单不会残留到下一次打开（真机发现的 bug）。
#
# 用法：bash tools/verify_y152_device.sh
set -u
cd "$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

D=emulator-5560
A=/Users/roy/Documents/desktopapp/toolchain/sdk/platform-tools/adb
OUT=emu-verify/y152
APK="dist/CarDesk-platform-ecarx-cardesk-4246.apk"

mkdir -p "$OUT"

shot() { # shot <文件名>
  $A -s $D shell screencap -p /sdcard/_y152.png
  $A -s $D pull /sdcard/_y152.png "$OUT/$1" >/dev/null
  echo "  截图 → $OUT/$1"
}

dump_texts() { # dump_texts <标记>
  $A -s $D shell uiautomator dump /sdcard/_y152.xml >/dev/null 2>&1
  $A -s $D shell cat /sdcard/_y152.xml 2>/dev/null > "/tmp/y152_dump_$1.xml"
  python3 - "/tmp/y152_dump_$1.xml" <<'PY'
import re, sys
s = open(sys.argv[1], encoding='utf-8', errors='replace').read()
t = [m.group(1) for m in re.finditer(r'text="([^"]*)"', s)]
menu = [x for x in t if x in ('重命名', '应用信息', '禁用应用', '启用应用', '卸载', '取消')]
print("   菜单项:", menu if menu else "无")
print("   有没有「⋮」:", "有（不该出现）" if any('⋮' in x for x in t) else "无")
PY
}

long_press() { # long_press <x> <y>
  $A -s $D shell input swipe "$1" "$2" "$1" "$2" 900
  sleep 1.2
}

echo "=== 安装 $APK ==="
$A -s $D install -r -d "$APK" 2>&1 | tail -2

echo "=== 拉起桌面 ==="
$A -s $D shell am force-stop com.ecarx.cardesk
$A -s $D shell am start -n com.ecarx.cardesk/com.cardesk.launcher.MainActivity >/dev/null
sleep 4

echo "=== 打开应用抽屉（Dock 里的「应用列表」）==="
$A -s $D shell input tap 124 1017
sleep 2.5
shot 01_drawer.png
dump_texts drawer

echo "=== 长按第 1 个应用图标 → 应弹出应用菜单 ==="
# 网格起点：面板 left 44 + pad 40 = 84，top 73 + 150 = 223；
# 格子 300 宽 → 第 1 列中心 84+150=234；图标 76 高 → 中心 223+38=261。
long_press 234 261
shot 02_longpress_menu.png
dump_texts menu

echo "=== 菜单打开时按返回键 → 应只关菜单、抽屉还在 ==="
$A -s $D shell input keyevent KEYCODE_BACK
sleep 1.5
shot 03_back_closes_menu.png
dump_texts after_back

echo "=== 再长按 → 菜单；然后关抽屉；再重开 → 菜单不能残留 ==="
long_press 234 261
dump_texts menu2
$A -s $D shell input keyevent KEYCODE_BACK   # 关菜单
sleep 1
$A -s $D shell input keyevent KEYCODE_BACK   # 关抽屉
sleep 1.5
$A -s $D shell input tap 124 1017           # 重开抽屉
sleep 2.5
shot 04_reopen_no_residue.png
dump_texts reopen

echo "=== 点按图标 → 应启动应用 ==="
# 第 3 列（Calculator）中心 x = 84 + 2*300 + 150 = 834
$A -s $D shell input tap 834 261
sleep 3
$A -s $D shell dumpsys window 2>/dev/null | grep -m1 "mCurrentFocus"
shot 05_launch_app.png

echo "=== 完成 ==="
