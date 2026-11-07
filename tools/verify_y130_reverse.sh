#!/usr/bin/env bash
# 第130轮反向验证：把实现逐条改回「旧行为」，确认对应断言**必红**。
#
# 用法：bash tools/verify_y130_reverse.sh
#
# 背景（第130轮）：
#   画中画黑屏的判据换成了「承载应用的 Activity 还挂在我们的 VirtualDisplay 上吗」，
#   自愈动作改成**分级**（重发启动 → 重建承载面 → 放弃）。
#   下面每一条反向验证都对应一组断言：改坏 → 断言必红 → 还原 → 恢复全绿。
set -u
cd /Users/roy/Documents/desktopapp/cardesk

export JAVA_HOME=/Users/roy/Documents/desktopapp/toolchain/jdk-17.0.20.1+1/Contents/Home
export GRADLE_USER_HOME=/Users/roy/Documents/desktopapp/.gradle-home
GRADLE=/Users/roy/Documents/desktopapp/toolchain/gradle-8.7/bin/gradle

HEAL=app/src/main/java/com/cardesk/launcher/ui/pip/PipBlackHeal.kt
PROBE=app/src/main/java/com/cardesk/launcher/ui/pip/PipFrameProbe.kt
FILES="$HEAL $PROBE \
  app/src/test/java/com/cardesk/launcher/ui/pip/PipBlackHealTest.kt \
  app/src/test/java/com/cardesk/launcher/ui/pip/PipFrameProbeTest.kt"

# 自建备份（可复跑；不用相对路径 rm -rf）
BK=$(mktemp -d /tmp/y130-bk-XXXXXX)
echo "$BK" > /tmp/y130-bk-path.txt
for f in $FILES; do
  mkdir -p "$BK/$(dirname "$f")"
  cp "$f" "$BK/$f"
done

restore() {
  for f in $FILES; do
    cp "$BK/$f" "$f"
  done
}

run_tests() {
  python3 -c "import shutil,glob;[shutil.rmtree(p,ignore_errors=True) for p in glob.glob('app/build/test-results/testDebugUnitTest')]"
  "$GRADLE" :app:testDebugUnitTest \
    --tests "com.cardesk.launcher.ui.pip.PipBlackHealTest" \
    --tests "com.cardesk.launcher.ui.pip.PipFrameProbeTest" \
    --console=plain > /tmp/y130-rev-run.log 2>&1
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

patch() { # patch <file> <old> <new>
  python3 - "$1" "$2" "$3" <<'PYEOF'
import io, sys
p, old, new = sys.argv[1], sys.argv[2], sys.argv[3]
s = io.open(p, encoding='utf-8').read()
assert s.count(old) == 1, "patch 匹配 %d 次: %r" % (s.count(old), old[:70])
io.open(p, 'w', encoding='utf-8').write(s.replace(old, new))
PYEOF
}

echo "=== 基线（应当 0 红灯）==="
restore
run_tests

echo
echo "=== 反向1：判黑去掉「任务不在我们的 display 上」这条权威判据 ==="
patch "$HEAL" \
'        val blackByTask = taskOffDisplay' \
'        val blackByTask = false'
run_tests
restore

echo
echo "=== 反向2：像素判黑去掉「必须先见过应用画面」这道闸 ==="
patch "$HEAL" \
'        val blackByPixels = sawContent && consecutiveBlack >= blackStreakLimit' \
'        val blackByPixels = consecutiveBlack >= blackStreakLimit'
run_tests
restore

echo
echo "=== 反向3：自愈不升级（永远停在「重发启动」） ==="
patch "$HEAL" \
'            if (healStage >= STAGE_RELAUNCH) Action.REBUILD else Action.RELAUNCH,' \
'            Action.RELAUNCH,'
run_tests
restore

echo
echo "=== 反向4：重建过还黑时不放弃（会无限重启承载应用） ==="
patch "$HEAL" \
'        if (healStage >= STAGE_REBUILD) {' \
'        if (false) {'
run_tests
restore

echo
echo "=== 反向5：去掉冷却（黑屏一判就立刻反复重启） ==="
patch "$HEAL" \
'        if (sinceLastHealMs < cooldown) {' \
'        if (false) {'
run_tests
restore

echo
echo "=== 反向6：第二级自愈的冷却不递增 ==="
patch "$HEAL" \
'        STAGE_RELAUNCH -> COOLDOWN_SECOND_MS' \
'        STAGE_RELAUNCH -> COOLDOWN_FIRST_MS'
run_tests
restore

echo
echo "=== 反向7：display 判据不再「判不准就当没黑」 ==="
patch "$HEAL" \
'        if (ourDisplayId == null) return false' \
'        if (ourDisplayId == null) return true'
run_tests
restore

echo
echo "=== 反向8：像素判黑去掉「亮像素占比」这条 ==="
patch "$PROBE" \
'            maxLuma < BLACK_LUMA ||
                brightRatio < BRIGHT_RATIO_MIN ||' \
'            maxLuma < BLACK_LUMA ||'
run_tests
restore

echo
echo "=== 反向9：采样矩形不往里缩（会把自己叠在画面上的控件采进去） ==="
patch "$PROBE" \
'            val dx = (w * ratio).toInt()
            val dy = (h * ratio).toInt()' \
'            val dx = 0
            val dy = 0'
run_tests
restore

echo
echo "=== 还原后自检（应当 0 红灯）==="
run_tests

echo
echo "备份目录：$BK"
