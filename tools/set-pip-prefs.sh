#!/usr/bin/env bash
# 验收脚本：改 cardesk 的 shared_prefs（画中画=高德 / 视频壁纸后端），改完重启桌面。
#
# 用法： bash tools/set-pip-prefs.sh <backend> [--drop-backend-key]
#   backend = 0 (SurfaceView) | 1 (TextureView)
#   --drop-backend-key  删掉 design_video_wallpaper_backend 这个键
#                       （模拟「从没手动设过这项」的用户 ⇒ 走代码里的默认值）
set -euo pipefail

A="${CARDESK_AVD:-emulator-5560}"
PKG=com.ecarx.cardesk
PREFS=/data/data/$PKG/shared_prefs/cardesk.xml
LOCAL="${TMPDIR:-/tmp}/cardesk-prefs-edit.xml"

BACKEND="${1:?用法: set-pip-prefs.sh <0|1> [--drop-backend-key]}"
DROP=0
[ "${2:-}" = "--drop-backend-key" ] && DROP=1

adb -s "$A" shell "am force-stop $PKG" >/dev/null 2>&1 || true
adb -s "$A" pull "$PREFS" "$LOCAL" >/dev/null

python3 - "$LOCAL" "$BACKEND" "$DROP" <<'PY'
import re, sys
path, backend, drop = sys.argv[1], sys.argv[2], sys.argv[3] == '1'
s = open(path, encoding='utf-8').read()

def set_bool(key, val):
    global s
    tag = f'<boolean name="{key}" value="{val}" />'
    if f'name="{key}"' in s:
        s = re.sub(rf'<boolean name="{re.escape(key)}" value="[^"]*" />', tag, s)
    else:
        s = s.replace('</map>', '    ' + tag + '\n</map>')

def set_int(key, val):
    global s
    tag = f'<int name="{key}" value="{val}" />'
    if f'name="{key}"' in s:
        s = re.sub(rf'<int name="{re.escape(key)}" value="[^"]*" />', tag, s)
    else:
        s = s.replace('</map>', '    ' + tag + '\n</map>')

def drop_key(key):
    global s
    s = re.sub(rf'\s*<(?:boolean|int|string|long|float) name="{re.escape(key)}" value="[^"]*" />', '', s)

# 画中画 = 高德
if 'name="pip_app"' in s:
    s = re.sub(r'<string name="pip_app">[^<]*</string>', '<string name="pip_app">com.autonavi.amapautp.yh</string>', s)
else:
    s = s.replace('</map>', '    <string name="pip_app">com.autonavi.amapautp.yh</string>\n</map>')
set_int('last_page', 1)
set_bool('design_video_wallpaper', 'true')
set_bool('design_video_wallpaper_enabled', 'true')
if drop:
    drop_key('design_video_wallpaper_backend')
else:
    set_int('design_video_wallpaper_backend', backend)

open(path, 'w', encoding='utf-8').write(s)
print('prefs written: backend=' + ('<已删除，用代码默认值>' if drop else backend))
PY

adb -s "$A" push "$LOCAL" "$PREFS" >/dev/null
adb -s "$A" shell "chown 1000:1000 $PREFS && chmod 660 $PREFS"
adb -s "$A" shell "am start -n $PKG/com.cardesk.launcher.MainActivity" >/dev/null
echo "已重启桌面：$A"
