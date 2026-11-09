#!/usr/bin/env bash
# =============================================================================
# 启动 / 停止 **API 28** 的 CarDesk 模拟器（车机同版本）
#
# 为什么单独一个脚本（用户要求，第140轮起）：
#   车机（领克 dcy11）跑的是 **Android 9 / API 28**，
#   而此前一直拿 API 29 的 AOSP 镜像当主力 —— 两边**系统行为不一样**：
#   最典型的就是「display 消失时 task 去哪」（API 28 搬到默认显示器，
#   API 29 直接 finish 掉），画中画 / 多屏相关的验证在 API 29 上会**看错现象**。
#
# 用法：
#   bash tools/emu28.sh start     # 启动（后台，headless）
#   bash tools/emu28.sh stop      # 停掉
#   bash tools/emu28.sh status    # 看状态（端口 / API / 是否开机完成）
#   bash tools/emu28.sh wait      # 等开机完成
# =============================================================================
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ROOT="$(dirname "$REPO")"
SDK="${CARDESK_TOOLCHAIN:-$ROOT/toolchain}/sdk"
AVD=cardesk28
# 端口固定 5560：5554/5556/5558 常被别的 AVD 占着。
PORT=5560

case "${1:-start}" in
  start)
    if "$SDK/emulator/emulator" -list-avds | grep -qx "$AVD"; then
      echo "AVD $AVD 存在，启动中（端口 $PORT）…"
    else
      echo "!!! AVD $AVD 不存在。先按 EMULATOR-VERIFY.md 第 0.0 节装镜像并建 AVD。" >&2
      exit 1
    fi
    nohup "$SDK/emulator/emulator" -avd "$AVD" \
      -port "$PORT" -no-window -gpu swiftshader_indirect -no-boot-anim -no-snapshot \
      > /tmp/emu28.log 2>&1 &
    echo "已后台启动，日志 /tmp/emu28.log"
    ;;
  wait)
    adb -s "emulator-$PORT" wait-for-device
    for _ in $(seq 1 80); do
      if [ "$(adb -s "emulator-$PORT" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; then
        echo "开机完成："
        adb -s "emulator-$PORT" shell getprop ro.build.version.sdk | sed 's/^/  API /'
        adb -s "emulator-$PORT" shell getprop ro.build.version.release | sed 's/^/  Android /'
        adb -s "emulator-$PORT" shell wm size | sed 's/^/  /'
        adb -s "emulator-$PORT" shell wm density | sed 's/^/  /'
        exit 0
      fi
      sleep 3
    done
    echo "!!! 等待开机完成超时" >&2
    exit 1
    ;;
  stop)
    adb -s "emulator-$PORT" emu kill 2>/dev/null || true
    echo "已请求停止 emulator-$PORT"
    ;;
  status)
    adb devices -l | grep -E "emulator-$PORT" || echo "emulator-$PORT 不在线"
    adb -s "emulator-$PORT" shell getprop ro.build.version.sdk 2>/dev/null | sed 's/^/  API /' || true
    ;;
  *)
    sed -n '15,25p' "$0"
    exit 1
    ;;
esac
