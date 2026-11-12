#!/usr/bin/env bash
# 第153轮真机验证脚本（emulator-5560）。
#
# 验证两件事：
#   ① 日间模式下：点封面图 → 开关「歌曲图片当卡片背景」→ **画面必须明显变化**；
#   ② 夜间模式下：同一路径 → 行为不变（封面依旧清晰可见，开关依旧有效）。
#
# 用法：
#   bash tools/verify_y153_device.sh <apk 路径>
#
# 产物：emu-verify/y153/*.png + y153-device-diff-20.txt
set -u

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="$REPO/emu-verify/y153"
mkdir -p "$OUT"

A=/Users/roy/Documents/desktopapp/toolchain/sdk/platform-tools/adb
D=emulator-5560
PKG=com.ecarx.cardesk
ACT=$PKG/com.cardesk.launcher.MainActivity
APK="${1:-$REPO/dist/CarDesk-platform-ecarx-cardesk-4247.apk}"

# 组合卡在屏幕上的位置（emulator 1920×1080 实测）：
#   整张卡 x=1350..1875, y=72..935
#   封面小图（96×96）在 x=1388..1456, y=138..210 → 点点 (1422, 186)
CROP="1350,72,1875,935"
COVER_TAP=1422,186

shot() { # shot <文件名>
  $A -s $D shell screencap -p /sdcard/_s.png >/dev/null 2>&1
  $A -s $D pull /sdcard/_s.png "$OUT/$1" >/dev/null 2>&1
  echo "  截图：$1"
}

set_pref() { # set_pref <key> <type> <value>
  local KEY=$1 TYPE=$2 VAL=$3
  $A -s $D shell "cat /data/data/$PKG/shared_prefs/cardesk.xml" > /tmp/y153p.xml 2>/dev/null
  python3 - "$KEY" "$TYPE" "$VAL" <<'PY'
import io, re, sys
key, typ, val = sys.argv[1], sys.argv[2], sys.argv[3]
s = io.open('/tmp/y153p.xml', encoding='utf-8').read()
line = '<%s name="%s" value="%s" />' % (typ, key, val)
if re.search(r'<%s name="%s"[^>]*/>' % (typ, key), s):
    s = re.sub(r'<%s name="%s"[^>]*/>' % (typ, key), line, s)
else:
    s = s.replace('</map>', '    %s\n</map>' % line)
io.open('/tmp/y153p2.xml', 'w', encoding='utf-8').write(s)
PY
  $A -s $D push /tmp/y153p2.xml /data/local/tmp/cardesk.xml >/dev/null 2>&1
  $A -s $D shell "cat /data/local/tmp/cardesk.xml > /data/data/$PKG/shared_prefs/cardesk.xml" >/dev/null 2>&1
}

restart() {
  $A -s $D shell am force-stop $PKG >/dev/null 2>&1
  sleep 1
  $A -s $D shell am start -n $ACT >/dev/null 2>&1
  sleep 3
}

echo "== 1) 装包 =="
if [ -f "$APK" ]; then
  $A -s $D install -r -d "$APK" 2>&1 | tail -2
else
  echo "!! 找不到 APK：$APK"
  exit 1
fi
$A -s $D shell dumpsys package $PKG | grep -E "versionCode|versionName" | head -2

echo
echo "== 2) 摆成日间（画中画停用 + 停到第 2 页）=="
restart
set_pref pip_app string com.no.such.pip
set_pref last_page int 1
set_pref daynight_enabled boolean true
set_pref daynight_mode int 1
set_pref daynight_applied_night_ui boolean false
restart
shot "20-day-01-nav.png"

echo
echo "== 3) 日间：封面背景开 → 点封面关 → 再点开 =="
shot "20-day-02-cover-on.png"
$A -s $D shell input tap ${COVER_TAP%,*} ${COVER_TAP#*,}
sleep 2
shot "20-day-03-cover-off.png"
$A -s $D shell input tap ${COVER_TAP%,*} ${COVER_TAP#*,}
sleep 2
shot "20-day-04-cover-on-again.png"

echo
echo "== 4) 摆成夜间（总开关关 → 回落到「上次应用的夜间态」= 夜间）=="
set_pref daynight_enabled boolean false
set_pref daynight_applied_night_ui boolean true
restart
shot "20-night-01-nav.png"
$A -s $D shell input tap ${COVER_TAP%,*} ${COVER_TAP#*,}
sleep 2
shot "20-night-02-cover-off.png"
$A -s $D shell input tap ${COVER_TAP%,*} ${COVER_TAP#*,}
sleep 2
shot "20-night-03-cover-on-again.png"

echo
echo "== 5) 裁出组合卡 + 算「开 / 关」的像素差 =="
python3 - "$OUT" "$CROP" <<'PY'
import io, os, sys
from PIL import Image, ImageChops
out, crop = sys.argv[1], sys.argv[2]
box = tuple(int(v) for v in crop.split(','))
pairs = [
    ("日间：开 vs 关", "20-day-02-cover-on.png", "20-day-03-cover-off.png"),
    ("夜间：开 vs 关", "20-night-01-nav.png", "20-night-02-cover-off.png"),
]
lines = []
for title, a, b in pairs:
    pa, pb = os.path.join(out, a), os.path.join(out, b)
    if not (os.path.exists(pa) and os.path.exists(pb)):
        lines.append("%s：截图缺失" % title); continue
    ia, ib = Image.open(pa).convert('RGB'), Image.open(pb).convert('RGB')
    ca, cb = ia.crop(box), ib.crop(box)
    ca.save(os.path.join(out, a.replace('.png', '-card.png')))
    cb.save(os.path.join(out, b.replace('.png', '-card.png')))
    diff = ImageChops.difference(ca, cb)
    hist = diff.convert('L').histogram()
    total = sum(hist)
    mean = sum(i * n for i, n in enumerate(hist)) / float(total)
    changed = sum(hist[8:])          # 差 ≥ 8 的像素占比
    # 并排图
    w, h = ca.size
    side = Image.new('RGB', (w * 2 + 12, h), (24, 24, 28))
    side.paste(ca, (0, 0)); side.paste(cb, (w + 12, 0))
    side.save(os.path.join(out, a.replace('.png', '-vs.png')))
    lines.append("%s：平均差 %.2f / 255，变化像素占比 %.1f%%" % (title, mean, 100.0 * changed / total))
io.open(os.path.join(out, 'y153-device-diff-20.txt'), 'w', encoding='utf-8').write("\n".join(lines) + "\n")
print("\n".join(lines))
PY

echo
echo "== 6) 夜间零退化：新包 vs 4247 那一版（同一采样点逐像素比）=="
python3 - "$OUT" <<'PY'
import io, os, sys
from PIL import Image
out = sys.argv[1]
# 组合卡里几个「应该是纯色」的采样点（避开封面、歌词、进度条）
PTS = {
    "卡片左上空白": (1370, 250),
    "卡片右上空白": (1860, 250),
    "卡片左下空白": (1370, 880),
    "卡片右下空白": (1860, 880),
}
pairs = [
    ("新包 4248 夜间（封面关）", "20-night-02-cover-off.png"),
    ("上一版 4247 夜间（封面关）", "06-night-02-cover-off.png"),
    ("上一版 4247 夜间（封面开）", "05-night-01-nav.png"),
]
imgs = []
for label, name in pairs:
    p = os.path.join(out, name)
    if os.path.exists(p):
        imgs.append((label, Image.open(p).convert("RGB")))
    else:
        print("  %s：截图缺失（%s）" % (label, name))
if len(imgs) >= 2:
    for name, (x, y) in PTS.items():
        row = "  %-14s" % name
        for label, im in imgs:
            row += " %s=%s" % (label.split()[0], im.getpixel((x, y)))
        print(row)
PY

echo
echo "== 产物 =="
ls -1 "$OUT"/*.png "$OUT"/*.txt 2>/dev/null
