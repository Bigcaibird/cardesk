#!/usr/bin/env bash
# 第136轮反向验证：把实现逐条改回「旧行为」，确认对应断言**必红**。
#
# 用法：bash tools/verify_y136_reverse.sh
#
# 背景（第136轮，用户需求）：
#   一、开关门检测：
#      1) 监测到有车门打开 → 桌面弹窗提示「有车未关」，**具体到对应车门**；
#      2) 所有车门关上后弹窗自动关闭；
#      3) 弹窗允许手动关闭；
#      PS) 弹窗内容做成类似车门车窗卡片那种风格。
#   二、续航卡：
#      1) 除油量百分比外新增**具体剩余油量**（升）；
#      2) 续航 ≤100 且 >50 用黄色，≤50 用红色。
#
#   下面每一条反向验证都对应一组断言：改坏 → 断言必红 → 还原 → 恢复全绿。
set -u
cd /Users/roy/Documents/desktopapp/cardesk

export JAVA_HOME=/Users/roy/Documents/desktopapp/toolchain/jdk-17.0.20.1+1/Contents/Home
export GRADLE_USER_HOME=/Users/roy/Documents/desktopapp/.gradle-home
GRADLE=/Users/roy/Documents/desktopapp/toolchain/gradle-8.7/bin/gradle

ALERT=app/src/main/java/com/cardesk/launcher/core/DoorAlert.kt
LEVEL=app/src/main/java/com/cardesk/launcher/core/RangeLevel.kt
MATH=app/src/main/java/com/cardesk/launcher/vehicle/CarSignalMath.kt
LAUNCHER=app/src/main/java/com/cardesk/launcher/ui/LauncherView.kt
SCENE=app/src/main/java/com/cardesk/launcher/ui/pages/ScenePage.kt
FILES="$ALERT $LEVEL $MATH $LAUNCHER $SCENE"

# 自建备份（可复跑；不用相对路径 rm -rf）
BK=$(mktemp -d /tmp/y136-bk-XXXXXX)
echo "$BK" > /tmp/y136-bk-path.txt
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
    --tests "com.cardesk.launcher.core.DoorAlertTest" \
    --tests "com.cardesk.launcher.core.RangeLevelTest" \
    --tests "com.cardesk.launcher.vehicle.CarSignalMathTest" \
    --console=plain > /tmp/y136-rev-run.log 2>&1
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

# 接线检查：Android 侧的代码 JVM 单测覆盖不到，用源码断言兜住。
#
# 注意：断言要**排除被注释掉的那一行** —— 否则「把调用改成注释」这种改法
# 骗不过检查（文本还在，但代码没了）。
check_wiring() {
  python3 - "$LAUNCHER" "$SCENE" "$ALERT" <<'PYEOF'
import io, sys
launcher, scene, alert = [io.open(p, encoding='utf-8').read() for p in sys.argv[1:4]]
checks = []

def has_call(src, text):
    """text 出现在某一行的**代码部分**（不是注释）里。"""
    for line in src.splitlines():
        idx = line.find(text)
        if idx >= 0 and '//' not in line[:idx]:
            return True
    return False

def func_body(src, header):
    """取某个顶层函数的函数体（到下一个顶层右花括号为止）。"""
    i = src.find(header)
    if i < 0:
        return ""
    j = src.find("\n    }\n", i)
    return src[i:j] if j > 0 else src[i:i + 6000]

# 1) 车门告警必须真的接进刷新链
checks.append((
    has_call(launcher, 'syncDoorAlert(info)'),
    "LauncherView 必须在刷新里同步车门告警（否则永远不弹）",
))
body = func_body(launcher, "private fun syncDoorAlert")
checks.append((
    'closeOverlayDialog(dialog)' in body,
    "车门全关上时必须自动关闭弹窗",
))
checks.append((
    'doorAlertDismissed = true' in body,
    "用户手动关闭后必须记住（否则关一次又弹回来）",
))
checks.append((
    'DoorAlert.openItems(' in body,
    "弹窗内容必须走 DoorAlert.openItems（读不到不算开着）",
))
# 2) 续航分级必须接进绘制
checks.append((
    has_call(scene, 'rangeValueColor(range)'),
    "续航卡数值必须按分级着色（≤100 黄 / ≤50 红）",
))
checks.append((
    'fitText(fuelSummaryText(info?.fuelLiters, fuel), w - 44f * k)' in scene,
    "续航卡必须显示具体剩余油量（升）",
))
# 3) 判定逻辑本身
#
# 注意：这里也用 has_call 而不是 `'?: continue' in alert` ——
# 后者只要文本还在文件里就算通过，把那行注释掉也能骗过去。
checks.append((
    has_call(alert, '?: continue'),
    "DoorAlert 必须把「读不到」当成「不提示」（不能误报）",
))

bad = [msg for ok, msg in checks if not ok]
print("RED=%d TOTAL=1 ERR=0" % (1 if bad else 0))
for m in bad:
    print("   红:", m)
PYEOF
}

patch() { # patch <file> <old> <new>
  # 锚点必须**唯一**（count == 1）：匹配不到或匹配多处都直接失败退出，
  # 否则会出现「patch 没生效 → 断言照样绿 → 反向项假通过」。
  python3 - "$1" "$2" "$3" <<'PYEOF' || { echo "!!! patch 失败（锚点不唯一或不存在）: $1"; exit 1; }
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
check_wiring

echo
echo "=== 反向1：读不到也当成「开着」（开机瞬间误报「有车门未关」）==="
patch "$ALERT" \
'            val closed = doorsClosed?.getOrNull(i) ?: continue' \
'            val closed = doorsClosed?.getOrNull(i) ?: false'
run_tests
restore

echo
echo "=== 反向2：只提示车门，引擎盖 / 后备箱不提示（卡片说开着、弹窗说没开）==="
patch "$ALERT" \
'        if (hoodClosed == false) out.add(Item(Kind.HOOD, 0, HOOD_LABEL))' \
'        if (false) out.add(Item(Kind.HOOD, 0, HOOD_LABEL))'
run_tests
restore

echo
echo "=== 反向3：不具体到是哪扇门（全都叫「车门」）==="
patch "$ALERT" \
'            if (!closed) out.add(Item(Kind.DOOR, i, CarStatus.DOOR_LABELS[i]))' \
'            if (!closed) out.add(Item(Kind.DOOR, i, "车门"))'
run_tests
restore

echo
echo "=== 反向4：50 公里算黄色（阈值边界写反）==="
patch "$LEVEL" \
'        rangeKm <= DANGER_KM -> Level.DANGER' \
'        rangeKm < DANGER_KM -> Level.DANGER'
run_tests
restore

echo
echo "=== 反向5：剩余油量不乘容量（把百分比当成升数）==="
patch "$MATH" \
'        return cap * percent / 100f' \
'        return percent.toFloat()'
run_tests
restore

echo
echo "=== 反向6：容量读不到也返回 0 L（显示假的「油箱空了」）==="
patch "$MATH" \
'        val cap = capacityLiters(capacityRaw) ?: return null' \
'        val cap = capacityLiters(capacityRaw) ?: 0f'
run_tests
restore

echo
echo "=== 反向7：执行端不接刷新链（车门开了也不弹）==="
patch "$LAUNCHER" \
'        syncDoorAlert(info)' \
'        Unit'
check_wiring
restore

echo
echo "=== 反向8：全关时不自动关闭（噪音弹窗赖着不走）==="
patch "$LAUNCHER" \
'                Logx.event(LogTags.SIGNAL, "车门未关弹窗自动关闭")
                closeOverlayDialog(dialog)' \
'                Logx.event(LogTags.SIGNAL, "车门未关弹窗自动关闭")
                Unit'
check_wiring
restore

echo
echo "=== 反向9：手动关闭后不记住（关一次又弹回来）==="
patch "$LAUNCHER" \
'            doorAlertDismissed = true' \
'            doorAlertDismissed = false'
check_wiring
restore

echo
echo "=== 反向10：续航卡不按分级着色（永远常态色）==="
patch "$SCENE" \
'        text.color = rangeValueColor(range)' \
'        text.color = Skin.TEXT'
check_wiring
restore

echo
echo "=== 反向11：续航卡不显示剩余油量（只有百分比）==="
patch "$SCENE" \
'            fitText(fuelSummaryText(info?.fuelLiters, fuel), w - 44f * k),' \
'            fitText("燃料剩余 · ${VehicleStats.formatFuelPercent(fuel)}", w - 44f * k),'
check_wiring
restore

echo
echo "=== 还原后自检（应当 0 红灯）==="
run_tests
check_wiring

echo
echo "备份目录：$BK"
