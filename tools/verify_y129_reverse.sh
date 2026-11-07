#!/usr/bin/env bash
# 第129轮反向验证：把实现逐条改回「旧行为」，确认对应断言**必红**。
#
# 用法：bash tools/verify_y129_reverse.sh
# 备份目录写在 /tmp/y129-bk-path.txt（由外层脚本准备）。
set -u
cd /Users/roy/Documents/desktopapp/cardesk

export JAVA_HOME=/Users/roy/Documents/desktopapp/toolchain/jdk-17.0.20.1+1/Contents/Home
export GRADLE_USER_HOME=/Users/roy/Documents/desktopapp/.gradle-home
GRADLE=/Users/roy/Documents/desktopapp/toolchain/gradle-8.7/bin/gradle

NAV=app/src/main/java/com/cardesk/launcher/ui/pages/NavPage.kt
LV=app/src/main/java/com/cardesk/launcher/ui/LauncherView.kt
PLC=app/src/main/java/com/cardesk/launcher/ui/pip/NavSplitPlacement.kt

FILES="$NAV $LV $PLC \
  app/src/test/java/com/cardesk/launcher/ui/pages/NavSplitOverlayTest.kt \
  app/src/test/java/com/cardesk/launcher/ui/pip/NavSplitPlacementTest.kt"

# 自建备份（可复跑；不用相对路径 rm -rf）
BK=$(mktemp -d /tmp/y129-bk-XXXXXX)
echo "$BK" > /tmp/y129-bk-path.txt
for f in $FILES; do
  mkdir -p "$BK/$(dirname "$f")"
  cp "$f" "$BK/$f"
done

restore() {
  for f in $FILES; do
    cp "$BK/$f" "$f"
  done
}

# 跑两个相关测试类，输出红灯数（失败用例数）
run_tests() {
  python3 -c "import shutil,glob;[shutil.rmtree(p,ignore_errors=True) for p in glob.glob('app/build/test-results/testDebugUnitTest')]"
  "$GRADLE" :app:testDebugUnitTest \
    --tests "com.cardesk.launcher.ui.pages.NavSplitOverlayTest" \
    --tests "com.cardesk.launcher.ui.pip.NavSplitPlacementTest" \
    --console=plain > /tmp/y129-rev-run.log 2>&1
  python3 - <<'PY'
import glob, xml.etree.ElementTree as ET
tot=fail=err=0
names=[]
for f in glob.glob('app/build/test-results/testDebugUnitTest/*.xml'):
    r=ET.parse(f).getroot()
    tot+=int(r.get('tests')); fail+=int(r.get('failures')); err+=int(r.get('errors'))
    for tc in r.iter('testcase'):
        if tc.find('failure') is not None or tc.find('error') is not None:
            names.append(tc.get('classname','').split('.')[-1]+'.'+tc.get('name'))
print("RED=%d TOTAL=%d ERR=%d" % (fail+err, tot, err))
for n in names:
    print("   红:", n)
PY
}

patch() { # patch <file> <python-replace-script>
  python3 - "$1" <<PYEOF
import io, sys
p = sys.argv[1]
s = io.open(p, encoding='utf-8').read()
old, new = $2
assert s.count(old) == 1, "patch 匹配 %d 次: %r" % (s.count(old), old[:60])
io.open(p, 'w', encoding='utf-8').write(s.replace(old, new))
PYEOF
}

echo "=== 基线（应当 0 红灯）==="
restore
run_tests

echo
echo "=== 反向1：buildNavSplit 把按钮挂回导航页本体（旧行为）==="
patch "$NAV" "('        mapCard.addView(split, navSplitParams())\n        mapCard.bringChildToFront(split)', '        addView(split, navSplitParams())')"
run_tests
restore

echo
echo "=== 反向2：attachNavSplit 把按钮放回导航页本体（旧行为）==="
patch "$NAV" "('        mapCard.addView(navSplit, navSplitParams())\n        mapCard.bringChildToFront(navSplit)', '        addView(navSplit, navSplitParams())')"
run_tests
restore

echo
echo "=== 反向3：HOST_OVERLAID 改回 navPage ==="
patch "$PLC" "('    const val HOST_OVERLAID = HOST_PIP_CARD', '    const val HOST_OVERLAID = \"navPage\"')"
run_tests
restore

echo
echo "=== 反向4：全屏态 gravity 与悬浮态不一致 ==="
# —— 注意：不能用 GRAVITY or 0x10（0x10 就是 CENTER_VERTICAL，位或后值没变，等于没改）
patch "$PLC" "('    fun gravity(fullscreen: Boolean): Int = GRAVITY', '    fun gravity(fullscreen: Boolean): Int = if (fullscreen) Gravity.TOP else GRAVITY')"
run_tests
restore

echo
echo "=== 反向5：画中画承载面方向改成 RTL ==="
patch "$PLC" "('    const val PIP_CARD_LAYOUT_DIRECTION = PipSides.DIR_LTR', '    const val PIP_CARD_LAYOUT_DIRECTION = PipSides.DIR_RTL')"
run_tests
restore

echo
echo "=== 反向6：LauncherView 全屏态硬编码 gravity ==="
patch "$LV" "('                    NavSplitPlacement.gravity(fullscreen = true),', '                    Gravity.START or Gravity.TOP,')"
run_tests
restore

echo
echo "=== 还原后自检（应当 0 红灯）==="
run_tests

echo
echo "备份目录：$BK"
