#!/usr/bin/env bash
# =============================================================================
# 抓「切页过渡动画」的连续帧（CarDesk）
#
# 用系统的 `animator_duration_scale` 把动画放慢 10 倍（260ms → 2.6s），
# 这样 `screencap` 才来得及抓到中间帧 —— 不用改代码。
#
# 用法：
#   bash tools/capture_page_transition.sh [输出目录] [切页按钮坐标x] [坐标y]
# =============================================================================
set -uo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ROOT="$(dirname "$REPO")"
SDK="${CARDESK_TOOLCHAIN:-$ROOT/toolchain}/sdk"
ADB="$SDK/platform-tools/adb"
SERIAL="${CARDESK_SERIAL:-emulator-5560}"
PKG="${CARDESK_PKG:-com.ecarx.cardesk}"
OUT="${1:-/tmp/cardesk-page-frames}"
X="${2:-197}"
Y="${3:-1020}"

mkdir -p "$OUT"

restore_scale() {
  "$ADB" -s "$SERIAL" shell settings put global animator_duration_scale 1 >/dev/null 2>&1
}
trap restore_scale EXIT

echo "== 抓切页过渡帧 =="
echo "   设备: $SERIAL   输出: $OUT   按钮: ($X, $Y)"

# 回到桌面首页（先确保在场景页）
"$ADB" -s "$SERIAL" shell am force-stop "$PKG" >/dev/null 2>&1
sleep 1
"$ADB" -s "$SERIAL" shell am start -n "$PKG/com.cardesk.launcher.MainActivity" >/dev/null 2>&1
sleep 4

# 动画放慢 10 倍：260ms → 2.6s
"$ADB" -s "$SERIAL" shell settings put global animator_duration_scale 10 >/dev/null 2>&1
sleep 1

# 点「切页」键，同时开抓
"$ADB" -s "$SERIAL" shell input tap "$X" "$Y" >/dev/null 2>&1 &
for i in $(seq -w 0 11); do
  "$ADB" -s "$SERIAL" exec-out screencap -p > "$OUT/frame-$i.png" 2>/dev/null
done
wait

restore_scale
trap - EXIT

echo "已抓 $((12)) 帧："
ls -la "$OUT" | tail -n +4
