#!/usr/bin/env bash
# 真机手势注入：用 sendevent 往模拟器的虚拟触摸屏（/dev/input/event0）写**原始** evdev 事件。
#
# ## 为什么不用 `input swipe`
#
# `input swipe` 会把位移**均摊成很多小步**（几十个 MOVE，每个几像素），
# 桌面翻页判定要求「**单个** MOVE 的横移超过 24px」，
# 所以均摊之后永远触发不了 —— 模拟出来的是「慢拖」，不是用户说的「甩」。
#
# 这里直接写原始事件，可以精确构造：
#   DOWN → MOVE(位移 300px) → UP
# 一个 MOVE 就跨过阈值，这才有资格复现「滑杆滑动触发桌面翻页」。
#
# ## 坐标换算
#
# 虚拟触摸屏报的是 0..32767 的绝对坐标（getevent -pl 里的 max），
# 要按屏幕 1920x1080 换算。
set -u
D=${DEVICE:-emulator-5560}
A=/Users/roy/Documents/desktopapp/toolchain/sdk/platform-tools/adb
DEV=/dev/input/event0
SW=32767
SCREEN_W=1920
SCREEN_H=1080

sx() { python3 -c "print(int($1*$SW/$SCREEN_W))"; }
sy() { python3 -c "print(int($1*$SW/$SCREEN_H))"; }

ev_abs() { $A -s $D shell "sendevent $DEV 3 $1 $2" ; }
ev_syn()  { $A -s $D shell "sendevent $DEV 0 0 0" ; }

# 按下
raw_down() { # raw_down <x> <y>
  ev_abs 47 0            # ABS_MT_SLOT = 0
  ev_abs 57 1            # ABS_MT_TRACKING_ID = 1（协议 B：非负即有手指）
  ev_abs 53 "$(sx $1)"   # ABS_MT_POSITION_X
  ev_abs 54 "$(sy $2)"   # ABS_MT_POSITION_Y
  ev_abs 58 100          # ABS_MT_PRESSURE
  ev_syn
}

# 移动
raw_move() { # raw_move <x> <y>
  ev_abs 53 "$(sx $1)"
  ev_abs 54 "$(sy $2)"
  ev_abs 58 100
  ev_syn
}

# 抬起
raw_up() {
  ev_abs 57 -1           # ABS_MT_TRACKING_ID = -1（手指离开）
  ev_abs 58 0
  ev_syn
}

# 一次「甩」：按下 → 一个大位移的 MOVE → 抬起
raw_flick() { # raw_flick <x1> <y1> <x2> <y2>
  raw_down "$1" "$2"
  sleep 0.05
  raw_move "$3" "$4"
  sleep 0.05
  raw_up
}

# 一次「拖」：按下 → N 步匀速 MOVE → 抬起（更接近真实手指）
raw_drag() { # raw_drag <x1> <y1> <x2> <y2> [步数]
  local n=${5:-10}
  raw_down "$1" "$2"
  local i dx dy
  dx=$(python3 -c "print(($3-$1)/$n)")
  dy=$(python3 -c "print(($4-$2)/$n)")
  for ((i=1; i<=n; i++)); do
    raw_move "$(python3 -c "print(int($1+$dx*$i))")" "$(python3 -c "print(int($2+$dy*$i))")"
    sleep 0.012
  done
  raw_up
}

case "${1:-}" in
  down) raw_down "$2" "$3" ;;
  move) raw_move "$2" "$3" ;;
  up)   raw_up ;;
  flick) raw_flick "$2" "$3" "$4" "$5" ;;
  drag) raw_drag "$2" "$3" "$4" "$5" "${6:-10}" ;;
  *) echo "用法: $0 {down|move|up|flick|drag} ..." >&2; exit 1 ;;
esac
