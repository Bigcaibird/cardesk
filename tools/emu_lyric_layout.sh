#!/usr/bin/env bash
# 第117轮：模拟器定点取证 —— 首页中央歌词的两种布局
#
# 用法：
#   bash tools/emu_lyric_layout.sh three  out-name
#   bash tools/emu_lyric_layout.sh ktv    out-name
#
# 做的���：
#   1) **强杀** CarDesk 进程（`am force-stop` 对这个包不生效 —— 它是系统 HOME
#      应用，系统会立刻把它拉起来，旧实例还带着 page=1 的内存状态，
#      结果桌面一启动就停在导航页）；
#   2) 把 `last_page` 改成 0（首页=场景页），把 `scene_lyric_layout` 改成指定布局；
#   3) 起探针（带歌词的那首歌），再起 CarDesk；
#   4) 截图存到 emu-verify/y30/。
set -euo pipefail

LAYOUT="${1:-three}"
NAME="${2:-y30-lyric-$LAYOUT}"
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ADB="${CARDESK_ADB:-/Users/roy/Documents/desktopapp/toolchain/sdk/platform-tools/adb}"
DEV="${CARDESK_DEVICE:-emulator-5556}"
# 证据目录（默认 y30；第119轮用 y32）
OUT="${CARDESK_OUT_DIR:-$REPO/emu-verify/y30}"
# 屏幕尺寸：第119轮要把模拟器调成用户那张图的 1920x941 做**同尺寸对照**
WM_SIZE="${CARDESK_WM_SIZE:-}"
PREFS=/data/data/com.ecarx.cardesk/shared_prefs/cardesk.xml

mkdir -p "$OUT"

adb() { "$ADB" -s "$DEV" "$@"; }

if [ -n "$WM_SIZE" ]; then
  echo "== ⓪ 屏幕尺寸设为 $WM_SIZE =="
  adb shell wm size "$WM_SIZE"
  sleep 1
fi

echo "== ① 强杀 CarDesk 进程 =="
adb shell am force-stop com.ecarx.cardesk
for _ in 1 2 3 4 5; do
  P="$(adb shell pidof com.ecarx.cardesk | tr -d '\r' || true)"
  if [ -z "$P" ]; then
    break
  fi
  adb shell "kill -9 $P" >/dev/null 2>&1 || true
  sleep 1.5
done
echo "残留 pid=[$(adb shell pidof com.ecarx.cardesk | tr -d '\r')]"

echo "== ② 写偏好（last_page=0, scene_lyric_layout=$LAYOUT） =="
adb shell "sed -i 's|<int name=\"last_page\" value=\"[0-9]*\" />|<int name=\"last_page\" value=\"0\" />|; s|<string name=\"scene_lyric_layout\">[^<]*</string>|<string name=\"scene_lyric_layout\">$LAYOUT</string>|' $PREFS"
if ! adb shell "grep -q 'scene_lyric_layout' $PREFS"; then
  # 这个键第一次写，XML 里还没有 → 插到 </map> 前面
  adb shell "sed -i 's|</map>|<string name=\"scene_lyric_layout\">$LAYOUT</string>\n</map>|' $PREFS"
fi
adb shell "grep -E 'last_page|scene_lyric_layout' $PREFS"

echo "== ③ 起探针（带歌词） =="
adb shell am force-stop com.cardesk.mediaprobe
sleep 1
adb shell am start -n com.cardesk.mediaprobe/.MainActivity \
  --es title "怎么我睡不着你也不来救我" --es artist "焦迈奇" --es album "焦迈奇" \
  --es duration "277000" --es position "103000" --es play "true" >/dev/null
sleep 2

echo "== ④ 起 CarDesk =="
adb logcat -c
adb shell am start -W -n com.ecarx.cardesk/com.cardesk.launcher.MainActivity >/dev/null
sleep 10

echo "== ⑤ 截图 =="
adb exec-out screencap -p > "$OUT/$NAME.png"
ls -la "$OUT/$NAME.png"

echo "== ⑥ 日志 =="
adb logcat -d -v time CarDesk/App:I CarDesk/Media:I '*:S' 2>&1 \
  | grep -E "切页|歌词已应用|歌词竞速命中" | tail -8 > "$OUT/$NAME-logcat.txt" || true
cat "$OUT/$NAME-logcat.txt"
