#!/usr/bin/env bash
# =============================================================================
# 第167轮 模拟器验证：Dock 栏「切屏」按钮在画中画全屏下失效
#
# 用法：
#   bash tools/verify_y167.sh                      # 用 dist 里的最新包（默认 4260）
#   bash tools/verify_y167.sh --apk dist/xxx.apk   # 指定包（做 A/B 对照用）
#   bash tools/verify_y167.sh --keep-pip-app        # 不改 pip_app（默认改成 embedtest）
#
# ## 这条脚本验的是什么
#
# 真机（诊断包 cardesk-logs-20261116-115454）上的现象：
#   画中画进入全屏后，用户点 Dock 的「分屏」键 → 日志里 `切页 from=1 to=0`
#   **逻辑切页成功**，但画面一个像素都没变 → 用户看到「切屏按钮点了没反应」。
#
# 根因：画中画全屏时承载面被 `bringChildToFront` 提到**根容器最上层**，
# 而 `showPage()` 不管 `pipFullscreen` → 全屏层把切页结果整个盖住。
#
# ## 为什么必须用 embedtest 当承载应用
#
# API 28（车机同版本）上，**不支持副屏的应用会被系统搬到主显示器**（全屏），
# 它会盖住整个桌面 → 点不到画中画上的 chevron、也测不了「全屏层盖住切页」。
# `embedtest`（项目自带）支持副屏，PiP 画面老实待在虚拟屏里，
# 才能稳定复现「画中画全屏」这个状态。
#
# ## 断言
#
#   A. 切屏键：全屏 → 点分屏键 → 必须出现 `动作前还原画中画全屏` + `画中画退出全屏`，
#      且**画面必须有明显变化**（旧包是 0.00%）。
#   B. 应用键：全屏 → 点应用键 → 必须先还原全屏，抽屉必须**看得见**。
#   C. 主页键：全屏 → 点主页键 → 必须先还原全屏。
#   D. 全局 Dock 页号：站在画中画页时，日志里的 `activePage` 必须是 1（旧包恒为 0）。
# =============================================================================
set -uo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO"

SDK="${CARDESK_TOOLCHAIN:-$(dirname "$REPO")/toolchain}/sdk"
ADB="$SDK/platform-tools/adb"
DEV="${DEVICE:-emulator-5560}"
OUT="emu-verify/y167"
PREF_XML=/data/data/com.ecarx.cardesk/shared_prefs/cardesk.xml
PIP_APP=com.cardesk.embedtest

# 全局 Dock 四个导航键的 x（DockLayout: KEY_LEFT=22, KEY_SLOT=60, KEY_GAP=14）
DOCK_HOME_X=52
DOCK_APPS_X=124
DOCK_SPLIT_X=200
DOCK_Y=1027
# 画中画上的「全屏 / 还原」chevron（NavSplitPlacement: 宽36, 贴画中画左边缘, 垂直居中）
CHEV_X=60
CHEV_Y=510

APK=""
KEEP_PIP_APP=0
while [ $# -gt 0 ]; do
    case "$1" in
        --apk) APK="$2"; shift ;;
        --keep-pip-app) KEEP_PIP_APP=1 ;;
        -h|--help) sed -n '2,40p' "${BASH_SOURCE[0]}"; exit 0 ;;
        *) echo "未知参数：$1" >&2; exit 2 ;;
    esac
    shift
done
[ -n "$APK" ] || APK="$(ls -t dist/CarDesk-platform-ecarx-cardesk-*.apk | head -1)"

mkdir -p "$OUT"
PASS=0
FAIL=0
ok()   { echo "  ✅ $1"; PASS=$((PASS+1)); }
bad()  { echo "  ❌ $1"; FAIL=$((FAIL+1)); }
step() { echo; echo "== $1"; }

adb_() { "$ADB" -s "$DEV" "$@"; }
tap()  { adb_ shell input tap "$1" "$2" >/dev/null 2>&1; sleep "${3:-3}"; }
lg()   { adb_ logcat -d 2>/dev/null | grep -E "$1"; }
shot() { adb_ exec-out screencap -p > "$OUT/$1" 2>/dev/null; }

# 把画中画承载应用改成 embedtest（API 28 上高德会抢主屏，测不了）。
set_pip_app() {
    local tmp; tmp="$(mktemp /tmp/y167-prefs-XXXXXX.xml)"
    adb_ shell am force-stop com.ecarx.cardesk >/dev/null 2>&1
    sleep 2
    # 必须等进程真的没了再改文件，否则老进程退出时会用 apply() 把文件写回去。
    local i
    for i in $(seq 1 10); do
        [ -z "$(adb_ shell pidof com.ecarx.cardesk 2>/dev/null | tr -d '\r')" ] && break
        sleep 1
    done
    adb_ shell "cat $PREF_XML" > "$tmp" 2>/dev/null
    python3 - "$tmp" "$PIP_APP" <<'PY'
import io, re, sys
path, app = sys.argv[1], sys.argv[2]
s = io.open(path, encoding='utf-8', errors='replace').read()
s = re.sub(r'(<string name="pip_app">)[^<]*(</string>)', r'\1' + app + r'\2', s)
s = re.sub(r'(<int name="last_page" value=")[0-9]+( ?/>)', r'\g<1>0\g<2>', s)
io.open(path, 'w', encoding='utf-8').write(s)
PY
    adb_ push "$tmp" "$PREF_XML" >/dev/null 2>&1
    adb_ shell "chown system:system $PREF_XML; chmod 660 $PREF_XML" >/dev/null 2>&1
    rm -f "$tmp"
}

restart_app() {
    adb_ shell am force-stop com.ecarx.cardesk >/dev/null 2>&1
    sleep 2
    adb_ shell am start -n com.ecarx.cardesk/com.cardesk.launcher.MainActivity >/dev/null 2>&1
    sleep 10
}

# 画面变化率（>16 的灰度差算「明显变化」）。
change_ratio() {
    python3 - "$1" "$2" <<'PY'
import sys
from PIL import Image, ImageChops
a = Image.open(sys.argv[1]).convert('RGB')
b = Image.open(sys.argv[2]).convert('RGB')
d = ImageChops.difference(a, b).convert('L')
h = d.histogram()
print('%.4f' % (sum(h[16:]) / (a.size[0] * a.size[1])))
PY
}

# embedtest 承载面的绿区包围盒（xmin ymin xmax ymax），用来区分
# 「画中画铺满整屏」和「画中画缩回导航页地图卡」。
#
# 全屏态：  (0,24)-(1916,964)   → 盖住整屏
# 归位态：  (44,76)-(1328,944)  → 落在地图卡里（NAV_SIDE_MARGIN=44, navContentTop=73）
green_bbox() {
    python3 - "$1" <<'PY'
import sys
from PIL import Image
im = Image.open(sys.argv[1]).convert('RGB')
w, h = im.size
px = im.load()
xs, ys = [], []
for y in range(0, h, 4):
    for x in range(0, w, 4):
        r, g, b = px[x, y]
        if abs(r-15) < 14 and abs(g-108) < 14 and abs(b-74) < 14:
            xs.append(x); ys.append(y)
if not xs:
    print('none 0 0 0')
else:
    print('%d %d %d %d' % (min(xs), min(ys), max(xs), max(ys)))
PY
}

# 关掉可能开着的抽屉 —— 它盖在画中画上，chevron 点不到。
#
# 「打开」和「关闭」是成对的落盘事件，最后一条说了算。
close_drawer() {
    local i last
    for i in 1 2 3; do
        last="$(lg '打开应用抽屉|关闭应用抽屉' | tail -1)"
        case "$last" in
            *关闭应用抽屉*) return 0 ;;
            *打开应用抽屉*) ;;
            *) return 0 ;;
        esac
        adb_ logcat -c >/dev/null 2>&1
        tap "$DOCK_APPS_X" "$DOCK_Y" 3
    done
    return 0
}

# 切到画中画页；返回 0 = 成功。
goto_nav_page() {
    local i
    close_drawer
    for i in 1 2 3 4; do
        adb_ logcat -c >/dev/null 2>&1
        tap "$DOCK_SPLIT_X" "$DOCK_Y" 3
        if lg '切页 from=[01] to=1' >/dev/null; then return 0; fi
    done
    return 1
}

# 进画中画全屏；返回 0 = 成功。
enter_fullscreen() {
    local i
    for i in 1 2 3; do
        adb_ logcat -c >/dev/null 2>&1
        tap "$CHEV_X" "$CHEV_Y" 3
        if lg '画中画进入全屏' >/dev/null; then return 0; fi
    done
    return 1
}

echo "设备：$DEV"
echo "被测包：$APK"
adb_ shell getprop ro.build.version.sdk 2>/dev/null | tr -d '\r' | sed 's/^/API /'

step "安装被测包"
adb_ install -r -d "$APK" 2>&1 | tail -1
adb_ shell dumpsys package com.ecarx.cardesk 2>/dev/null | grep -m1 versionCode | tr -d '\r' | sed 's/^/版本：/'
if [ "$KEEP_PIP_APP" -eq 0 ]; then
    step "把画中画承载应用改成 embedtest"
    set_pip_app
    echo "  pip_app = $(adb_ shell "cat $PREF_XML" 2>/dev/null | grep -o 'pip_app">[^<]*' | cut -d'"' -f2)"
fi
restart_app

# ---------------------------------------------------------------- A 切屏键
step "A. 画中画全屏 → 点 Dock「分屏」键"
restart_app
if goto_nav_page; then ok "已进入画中画页"; else bad "进不了画中画页"; fi
if enter_fullscreen; then ok "已进入画中画全屏"; else bad "进不了画中画全屏（chevron 点不到）"; fi
shot "a-before-split.png"
adb_ logcat -c >/dev/null 2>&1
tap "$DOCK_SPLIT_X" "$DOCK_Y" 4
shot "a-after-split.png"
RATIO=$(change_ratio "$OUT/a-before-split.png" "$OUT/a-after-split.png")
echo "  画面变化率：$RATIO"
if lg '动作前还原画中画全屏' >/dev/null; then ok "闸口命中：动作前还原画中画全屏"; else bad "没有「动作前还原画中画全屏」日志"; fi
if lg '画中画退出全屏' >/dev/null; then ok "画中画已退出全屏"; else bad "画中画没有退出全屏"; fi
if lg '切页 from=1 to=0' >/dev/null; then ok "页面切到桌面页"; else bad "页面没切到桌面页"; fi
if awk -v r="$RATIO" 'BEGIN{exit !(r>0.2)}'; then
    ok "画面有明显变化（$RATIO > 20%）—— 按钮真的生效了"
else
    bad "画面几乎没变（$RATIO）—— 按钮还是失效的"
fi
if lg 'Dock 按键点击 key=split .*activePage=1' >/dev/null; then
    ok "全局 Dock 页号正确（activePage=1）"
else
    bad "全局 Dock 页号不对（activePage 不是 1）"
fi
# 绿区几何佐证：全屏态盖住整屏 (0,24)-(1916,964)，切屏后不应该还是整屏。
BBOX_A=$(green_bbox "$OUT/a-after-split.png")
set -- $BBOX_A
echo "  全屏态绿区 bbox：$(green_bbox "$OUT/a-before-split.png")"
echo "  切屏后绿区 bbox：$BBOX_A"
if [ "$1" = "none" ] || [ "$1" -ge 40 ]; then
    ok "切屏后画中画不再全屏覆盖"
else
    bad "切屏后画中画仍全屏覆盖（绿区 bbox=$BBOX_A）"
fi

# ---------------------------------------------------------------- B 应用键
step "B. 画中画全屏 → 点 Dock「应用」键"
restart_app
if goto_nav_page; then ok "已进入画中画页"; else bad "进不了画中画页"; fi
if enter_fullscreen; then ok "已进入画中画全屏"; else bad "进不了画中画全屏"; fi
adb_ logcat -c >/dev/null 2>&1
tap "$DOCK_APPS_X" "$DOCK_Y" 4
shot "b-after-apps.png"
if lg '动作前还原画中画全屏' >/dev/null; then ok "开抽屉前先还原了全屏"; else bad "开抽屉前没有还原全屏（抽屉会被压住）"; fi
if lg '打开应用抽屉' >/dev/null; then ok "抽屉已打开"; else bad "抽屉没打开"; fi
# 抽屉背景是深色（#12151b 附近）；若还是全屏的 embedtest（绿色 15,108,74）说明被盖住了。
PY_=$(python3 - "$OUT/b-after-apps.png" <<'PY'
import sys
from PIL import Image
im = Image.open(sys.argv[1]).convert('RGB')
r, g, b = im.getpixel((5, 5))
print('1' if (r < 60 and g < 60 and b < 60) else '0')
PY
)
if [ "$PY_" = "1" ]; then ok "抽屉在画面上可见（不再是全屏画中画）"; else bad "画面上还是全屏画中画，抽屉看不见"; fi

# ---------------------------------------------------------------- C 主页键
step "C. 画中画全屏 → 点 Dock「主页」键"
restart_app
if goto_nav_page; then ok "已进入画中画页"; else bad "进不了画中画页"; fi
if enter_fullscreen; then ok "已进入画中画全屏"; else bad "进不了画中画全屏"; fi
adb_ logcat -c >/dev/null 2>&1
tap "$DOCK_HOME_X" "$DOCK_Y" 4
shot "c-after-home.png"
if lg '外部 HOME' >/dev/null; then ok "HOME 已被桌面接住"; else bad "HOME 没被桌面接住"; fi
# 主页键走的是「先还原全屏、再留在画中画页」这条路，不经过 showPage 闸口，
# 所以判据是「画中画退出全屏」而不是 A/B 场景那个「动作前还原画中画全屏」。
if lg '画中画退出全屏' >/dev/null; then ok "主页键先还原了画中画全屏"; else bad "主页键没有还原画中画全屏"; fi
# 注意：切页日志里 animate 夹在 from/to 和 fullscreen 之间，
# 必须用正则匹配，不能用子串。
if lg '切页 from=1 to=1.*fullscreen=false' >/dev/null; then ok "仍停在画中画页且全屏态已清"; else bad "页号/全屏态不对"; fi
if lg '画中画全屏态同步 fullscreen=false' >/dev/null; then ok "全局 Dock 的全屏态已同步"; else bad "全局 Dock 的全屏态没同步"; fi
if lg '画中画归位到地图卡' >/dev/null; then ok "画中画已挂回导航页地图卡"; else bad "画中画没挂回地图卡"; fi

# API 28 模拟器上有个与本次改动无关的现象：embedtest 的 Activity 窗口会被系统
# 提升成 display 0 的 1920x1080 全屏窗（stackId 压在桌面栈之上），把 CarDesk
# 自己的画面整个盖住，连 CarDesk 画的 chevron 都点不到。
# 老版本（4250）在同一状态下点 chevron 是零日志 + 画面变化率 0.0000，
# 说明这是 embedtest/模拟器特有的窗口提升，不是 CarDesk 的状态没还原。
#
# 这里的验证办法：把 embedtest 关掉（CarDesk 的「黑屏自愈」会把它按 PiP 卡片
# 尺寸重拉），然后量绿区包围盒 —— 归位态应该是 (44,76)-(1328,944)，
# 而不是全屏态的 (0,24)-(1916,964)。
adb_ shell am force-stop com.cardesk.embedtest >/dev/null 2>&1
BBOX="none 0 0 0"
for i in 1 2 3 4 5 6; do
    sleep 3
    shot "c-after-home-nopip.png"
    BBOX=$(green_bbox "$OUT/c-after-home-nopip.png")
    set -- $BBOX
    if [ "$1" != "none" ] && [ "$1" -ge 40 ] && [ "$3" -le 1400 ]; then break; fi
done
echo "  绿区包围盒：$BBOX"
if [ "$1" != "none" ] && [ "$1" -ge 40 ] && [ "$3" -le 1400 ]; then
    ok "画中画已缩回地图卡（不再全屏覆盖）"
else
    bad "画中画仍是全屏覆盖（绿区 bbox=$BBOX）"
fi

echo
echo "==================== 结果 ===================="
echo "通过：$PASS"
echo "失败：$FAIL"
[ "$FAIL" -eq 0 ] && echo "全部通过" || echo "有失败项"
exit $([ "$FAIL" -eq 0 ] && echo 0 || echo 1)
