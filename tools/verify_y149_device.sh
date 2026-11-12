#!/usr/bin/env bash
# 第149轮真机验证脚本（emulator-5560）。
#
# 覆盖四条需求：
#   ① 桌面启动后唤起默认媒体源 App 并播放（设置开关）
#   ② 设置页合并后的导航树与页面
#   ③ 设置页滑杆滑动不再触发桌面翻页
#   ④ 仪表歌词最小宽度 10%
#
# 用法：bash tools/verify_y149_device.sh
set -u
cd /Users/roy/Documents/desktopapp/cardesk

D=${DEVICE:-emulator-5560}
A=/Users/roy/Documents/desktopapp/toolchain/sdk/platform-tools/adb
PKG=com.ecarx.cardesk
ACT=com.cardesk.launcher.MainActivity
PREFS=/data/data/$PKG/shared_prefs/cardesk.xml
SHOT=emu-verify/y149
mkdir -p "$SHOT"

shot() { # shot <名字>
  $A -s $D shell screencap -p /sdcard/_y149.png
  $A -s $D pull /sdcard/_y149.png "$SHOT/$1.png" >/dev/null 2>&1
  echo "  截图：$SHOT/$1.png"
}

launcher() {
  $A -s $D shell am start -n $PKG/$ACT >/dev/null 2>&1
  sleep 3
}

echo "=== 0. 环境 ==="
$A -s $D shell pm list packages | grep -i qqmusic || echo "  (QQ音乐车机版没装)"

echo
echo "=== 1. 需求①：启动后唤起默认媒体源 App 并播放 ==="
echo "--- 1.1 开关默认关：桌面起来时**不该**弹 QQ音乐 ---"
# 先把开关写成关
$A -s $D shell am force-stop $PKG
$A -s $D shell am force-stop com.tencent.qqmusiccar
$A -s $D shell "awk '/<\/map>/{print \"    <boolean name=\\\"auto_launch_media_app\\\" value=\\\"false\\\" />\"} {print}' $PREFS > /tmp/p.xml"
$A -s $D shell "cat /tmp/p.xml > $PREFS"
$A -s $D logcat -c
launcher
sleep 3
echo "  前台：$($A -s $D shell dumpsys activity activities | grep -m1 'mResumedActivity\|topResumedActivity' | tr -s ' ')"
echo "  唤起日志："
$A -s $D logcat -d -s CarDesk/Media:V 2>&1 | grep -i "启动后唤起" | head -3 | sed 's/^/    /'
shot 10_auto_launch_off

echo
echo "--- 1.2 打开开关：桌面起来应该把 QQ音乐拉起来并让它播放 ---"
$A -s $D shell am force-stop $PKG
$A -s $D shell am force-stop com.tencent.qqmusiccar
$A -s $D shell "awk '/<\/map>/{print \"    <boolean name=\\\"auto_launch_media_app\\\" value=\\\"true\\\" />\"; print \"    <string name=\\\"default_music_source_v1\\\">qq</string>\"} {print}' $PREFS > /tmp/p.xml"
$A -s $D shell "cat /tmp/p.xml > $PREFS"
$A -s $D logcat -c
launcher
sleep 8
echo "  前台：$($A -s $D shell dumpsys activity activities | grep -m1 'mResumedActivity\|topResumedActivity' | tr -s ' ')"
echo "  唤起日志："
$A -s $D logcat -d -s CarDesk/Media:V 2>&1 | grep -i "启动后唤起" | head -5 | sed 's/^/    /'
echo "  会话状态："
$A -s $D shell dumpsys media_session 2>&1 | grep -A4 "com.tencent.qqmusiccar/QQMusicCar" | grep -E "state|active" | head -3 | sed 's/^/    /'
shot 11_auto_launch_on

echo
echo "=== 2. 需求②：设置页合并后的导航树 ==="
launcher
$A -s $D shell input tap 48 38   # 齿轮
sleep 2
shot 20_settings_navtree
echo "  （导航项应从 28 项变成 25 项，5 个分组）"

echo
echo "=== 3. 需求④：仪表歌词最小宽度 10% ==="
echo "  （在「歌词」页里，把「模块宽度」滑杆拖到最左，应停在 10% 而不是 30%）"

echo
echo "=== 4. 需求③：滑杆滑动不再触发桌面翻页 ==="
echo "  （用 tools/raw_touch.sh 注入单 MOVE 大位移的甩动，检查有没有「切页」日志）"

echo
echo "=== 还原设备状态 ==="
$A -s $D shell am force-stop $PKG
$A -s $D shell "awk '/<\/map>/{print \"    <boolean name=\\\"auto_launch_media_app\\\" value=\\\"false\\\" />\"} {print}' $PREFS > /tmp/p.xml"
$A -s $D shell "cat /tmp/p.xml > $PREFS"
echo "  已把「启动后自动播放」恢复为关"
