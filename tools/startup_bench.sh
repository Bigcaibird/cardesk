#!/usr/bin/env bash
# =============================================================================
# 冷启动耗时基准（CarDesk）
#
# 反复「force-stop → 启动」，把 `am start -W` 的 TotalTime 和
# StartupTrace 的分段一起打出来，用于对比优化前/后。
#
# 用法：
#   bash tools/startup_bench.sh [轮数] [包名]
# 默认 5 轮、com.ecarx.cardesk。
# =============================================================================
set -uo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ROOT="$(dirname "$REPO")"
SDK="${CARDESK_TOOLCHAIN:-$ROOT/toolchain}/sdk"
ADB="$SDK/platform-tools/adb"
SERIAL="${CARDESK_SERIAL:-emulator-5560}"
PKG="${2:-com.ecarx.cardesk}"
ROUNDS="${1:-5}"

echo "== CarDesk 冷启动基准 =="
echo "   设备: $SERIAL   包名: $PKG   轮数: $ROUNDS"
echo

for i in $(seq 1 "$ROUNDS"); do
  "$ADB" -s "$SERIAL" shell am force-stop "$PKG" >/dev/null 2>&1
  # 等进程彻底死透，避免 force-stop 与下一次启动之间还有残留
  sleep 1
  # 清 logcat 只留本次
  "$ADB" -s "$SERIAL" logcat -c >/dev/null 2>&1
  OUT=$("$ADB" -s "$SERIAL" shell am start -W -n "$PKG/com.cardesk.launcher.MainActivity" 2>&1)
  TOTAL=$(echo "$OUT" | grep -E "TotalTime" | awk '{print $2}')
  WAIT=$(echo "$OUT" | grep -E "WaitTime" | awk '{print $2}')
  sleep 3
  SEG=$("$ADB" -s "$SERIAL" logcat -d -s CarDesk-Startup 2>/dev/null | grep -oE "^[0-9-]+ [0-9:.]+ +[0-9]+ +[0-9]+ [A-Z] [A-Za-z-]+: done total=[0-9]+ms.*" | tail -1)
  echo "第 $i 轮: TotalTime=${TOTAL:-?}ms  WaitTime=${WAIT:-?}ms"
  if [ -n "$SEG" ]; then
    echo "        $SEG"
  fi
done
