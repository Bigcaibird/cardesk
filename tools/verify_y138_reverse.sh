#!/usr/bin/env bash
# 第138轮反向验证：把实现逐条改回「旧行为」，确认对应断言**必红**。
#
# 用法：bash tools/verify_y138_reverse.sh
#
# 背景（第138轮，用户需求）：
#   一、第二页音乐播放器的媒体来源没有显示对应 App 的图标 →
#      同步首页音乐卡片的图标显示方案；
#   二、桌面状态栏的图标（信号 / WiFi / 蓝牙 / 热点）显示不准确 →
#      参考 UNI 的逻辑优化。
#
#   每一条反向验证都对应一组断言：改坏 → 断言必红 → 还原 → 恢复全绿。
set -u
cd /Users/roy/Documents/desktopapp/cardesk

export JAVA_HOME=/Users/roy/Documents/desktopapp/toolchain/jdk-17.0.20.1+1/Contents/Home
export GRADLE_USER_HOME=/Users/roy/Documents/desktopapp/.gradle-home
GRADLE=/Users/roy/Documents/desktopapp/toolchain/gradle-8.7/bin/gradle

POLICY=app/src/main/java/com/cardesk/launcher/core/SystemStatusPolicy.kt
NAV=app/src/main/java/com/cardesk/launcher/ui/pages/NavPage.kt
LAUNCHER=app/src/main/java/com/cardesk/launcher/ui/LauncherView.kt
BAR=app/src/main/java/com/cardesk/launcher/ui/shell/ShellStatusBar.kt
FILES="$POLICY $NAV $LAUNCHER $BAR"

# 自建备份（可复跑；不用相对路径 rm -rf）
BK=$(mktemp -d /tmp/y138-bk-XXXXXX)
echo "$BK" > /tmp/y138-bk-path.txt
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
    --tests "com.cardesk.launcher.core.SystemStatusPolicyTest" \
    --console=plain > /tmp/y138-rev-run.log 2>&1
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
  python3 - "$NAV" "$LAUNCHER" "$BAR" "$POLICY" <<'PYEOF'
import io, sys
nav, launcher, bar, policy = [io.open(p, encoding='utf-8').read() for p in sys.argv[1:5]]
checks = []

def has_call(src, text):
    """text 出现在某一行的**代码部分**（不是注释）里。

    注意：这只排除「整行注释 / 行尾注释」，
    **挡不住**「函数定义处」和「被 if (false) 短路」的写法 ——
    所以下面几条关键断言一律用 has_exact（精确文本）。
    """
    for line in src.splitlines():
        idx = line.find(text)
        if idx >= 0 and '//' not in line[:idx]:
            return True
    return False

def has_exact(src, text):
    """精确子串存在（含缩进）—— 用来钉住「调用点」而不是「同名函数」。"""
    return text in src

def strip_comments(src):
    """去掉每行的 `//` 注释。

    为什么需要：源码里**注释掉的写法**也会被文本匹配命中 ——
    比如注释里写着「不能写 `val sourceIconView = …`」，
    纯文本检查就会把这段说明当成代码。
    """
    out = []
    for line in src.splitlines():
        i = line.find('//')
        out.append(line if i < 0 else line[:i])
    return '\n'.join(out)

def no_text(src, text):
    """精确子串在**代码部分**里必须不存在（注释不算）。"""
    return text not in strip_comments(src)

def has_code(src, text):
    """text 出现在**代码行**里（排除整行注释）。"""
    for line in src.splitlines():
        st = line.strip()
        if st.startswith('//'):
            continue
        if text in line:
            return True
    return False

# 需求一：第二页来源行要用图标 View
checks.append((
    has_exact(nav, 'sourceRow.addView(\n            sourceIconView,'),
    "导航页来源行必须把来源图标 View 加进行内（而不是原来的小圆点）",
))
checks.append((
    no_text(nav, 'Skin.oval(Skin.ACCENT)'),
    "导航页来源行不能还留着原来那个品牌色小圆点",
))
checks.append((
    has_exact(nav, '\n        sourceIconView = com.cardesk.launcher.ui.widget.MediaSourceIconView(context).apply {'),
    "来源图标 View 必须**赋给字段**（局部变量会遮蔽字段，赋值就送不到）",
))
checks.append((
    no_text(nav, 'val sourceIconView ='),
    "不能有局部变量遮蔽来源图标 View 的字段",
))
checks.append((
    has_exact(launcher, '            if (::navPage.isInitialized) navPage.sourceIcon = cached'),
    "LauncherView 必须把真实 App 图标喂给导航页",
))
checks.append((
    has_exact(launcher, '        if (::navPage.isInitialized) navPage.sourceId = sourceId'),
    "LauncherView 必须把来源 id 喂给导航页（否则图标永远是通用音乐）",
))
# 需求二：状态栏按策略画
checks.append((
    has_exact(bar, 'for (slot in statusSlots) {'),
    "状态栏必须按策略槽位画图标（不能是四个固定图标）",
))
checks.append((
    has_exact(bar, 'statusSlots = com.cardesk.launcher.core.SystemStatusPolicy.slots(v)'),
    "状态栏必须用 SystemStatusPolicy 算图标槽",
))
checks.append((
    has_exact(launcher, '\n        startSystemStatusSampling()\n'),
    "LauncherView 必须在窗口挂上时启动状态采样（不启动就永远是旧图标）",
))
checks.append((
    has_exact(launcher, 'statusBar.systemStatus = snapshot'),
    "采样结果必须喂给状态栏",
))
checks.append((
    has_exact(policy, 'if (s.mobileKnown && !s.wifiConnected) {'),
    "蜂窝信号必须在「没连 WiFi」时才显示",
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
echo "=== 反向1：连了 WiFi 也显示蜂窝信号（两条链路同时亮）==="
patch "$POLICY" \
'        if (s.mobileKnown && !s.wifiConnected) {' \
'        if (s.mobileKnown) {'
run_tests
restore

echo
echo "=== 反向2：热点与 WiFi 同时显示（不互斥）==="
patch "$POLICY" \
'        } else if (s.wifiConnected) {' \
'        }
        if (s.wifiConnected) {'
run_tests
restore

echo
echo "=== 反向3：蓝牙没开也显示（读不到也亮）==="
patch "$POLICY" \
'        if (s.bluetoothKnown && s.bluetoothEnabled) {' \
'        if (s.bluetoothKnown || s.bluetoothEnabled) {'
run_tests
restore

echo
echo "=== 反向4：蓝牙已连接不强调（看不出连没连）==="
patch "$POLICY" \
'            out.add(Slot(Kind.BLUETOOTH, emphasized = s.bluetoothConnected))' \
'            out.add(Slot(Kind.BLUETOOTH, emphasized = false))'
run_tests
restore

echo
echo "=== 反向5：WiFi 等级读不到按满格（假装信号好）==="
patch "$POLICY" \
'            out.add(Slot(Kind.WIFI, level = s.wifiLevel, emphasized = false))' \
'            out.add(Slot(Kind.WIFI, level = if (s.wifiLevel >= 0) s.wifiLevel else LEVEL_MAX, emphasized = false))'
run_tests
restore

echo
echo "=== 反向6：移动数据关掉时蜂窝仍显示满格==="
# 锚点带上注释行 —— `!s.mobileEnabled -> 0` 在文件里出现两次
# （slots() 与 cellularLevel()），只给一行不唯一，patch 会直接失败退出。
patch "$POLICY" \
'                // 移动数据关着 → 没有信号（0 格）。
                !s.mobileEnabled -> 0' \
'                // 移动数据关着 → 没有信号（0 格）。
                !s.mobileEnabled -> LEVEL_MAX'
run_tests
restore

echo
echo "=== 反向7：导航页来源行又用回小圆点（没有图标）==="
patch "$NAV" \
'        sourceRow.addView(
            sourceIconView,
            LinearLayout.LayoutParams(SOURCE_ICON_SIZE, SOURCE_ICON_SIZE).apply { rightMargin = 8 },
        )' \
'        sourceRow.addView(
            View(context).apply { background = Skin.oval(Skin.ACCENT) },
            LinearLayout.LayoutParams(10, 10).apply { rightMargin = 10 },
        )'
check_wiring
restore

echo
echo "=== 反向8：来源图标 View 又被局部变量遮蔽（赋值送不到）==="
patch "$NAV" \
'        sourceIconView = com.cardesk.launcher.ui.widget.MediaSourceIconView(context).apply {' \
'        val shadow = com.cardesk.launcher.ui.widget.MediaSourceIconView(context).apply {'
check_wiring
restore

echo
echo "=== 反向9：LauncherView 不把图标喂给导航页==="
patch "$LAUNCHER" \
'            if (::navPage.isInitialized) navPage.sourceIcon = cached' \
'            if (false) navPage.sourceIcon = cached'
check_wiring
restore

echo
echo "=== 反向10：状态栏又画四个固定图标（不看真实状态）==="
patch "$BAR" \
'        for (slot in statusSlots) {
            drawIcon(canvas, x - ICON, cy - ICON / 2f, ICON) { drawStatusSlot(it, slot) }
            x -= ICON + ICON_GAP
        }' \
'        drawIcon(canvas, x - ICON, cy - ICON / 2f, ICON) { drawCellular(it, 0f, 0) }
        x -= ICON + ICON_GAP
        drawIcon(canvas, x - ICON, cy - ICON / 2f, ICON) { drawWifi(it, 0f, 0, 4) }
        x -= ICON + ICON_GAP
        drawIcon(canvas, x - ICON, cy - ICON / 2f, ICON) { drawHotspot(it, 0f, 0, true) }
        x -= ICON + ICON_GAP
        drawIcon(canvas, x - ICON, cy - ICON / 2f, ICON) { drawBluetooth(it, 0f, 0, true) }
        x -= ICON + ICON_GAP'
check_wiring
restore

echo
echo "=== 反向11：不启动状态采样（图标永远停在默认值）==="
patch "$LAUNCHER" \
'        startSystemStatusSampling()' \
'        // startSystemStatusSampling()'
check_wiring
restore

echo
echo "=== 还原后自检（应当 0 红灯）==="
run_tests
check_wiring

echo
echo "备份目录：$BK"
