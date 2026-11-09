#!/usr/bin/env bash
# 第141轮反向验证：把实现逐条改回「旧行为」，确认对应断言**必红**。
#
# 用法：bash tools/verify_y141_reverse.sh
#
# 背景（第141轮）：
#   ① 第140轮截图里高德是**全屏**的 —— 取证后确认：**AOSP 9 官方模拟器**上
#      承载应用的 task 根本不会被 AMS 放到我们的 VirtualDisplay 上
#      （新建的 task 仍落在 display 0 = 全屏抢屏，画中画那块是空的）；
#      而**车机（真机 ROM）上 4237 是正常的** → 启动路径没问题，是 ROM 差异。
#      修法：**启动之后补一脚**，把承载应用的 task 搬到我们的虚拟屏
#      （AOSP 9 的 `IActivityManager.moveStackToDisplay`）。
#   ② 第130轮起用的黑屏判据在 API 28 上**从来没生效过**：
#      `RunningTaskInfo.baseIntent` 在 AOSP 9 里**不存在**（API 29 才移除）
#      → 每轮看门狗都抛 `NoSuchFieldError`；
#      而且 AOSP 9 的 `RunningTaskInfo` **连 displayId 都没有**。
#   ③ 所以「task 在不在我们的虚拟屏上」这条判据，必须有**平台无关**的证据。
#
#   每一条反向验证都对应一组断言：改坏 → 断言必红 → 还原 → 恢复全绿。
set -u
cd /Users/roy/Documents/desktopapp/cardesk

export JAVA_HOME=/Users/roy/Documents/desktopapp/toolchain/jdk-17.0.20.1+1/Contents/Home
export GRADLE_USER_HOME=/Users/roy/Documents/desktopapp/.gradle-home
GRADLE=/Users/roy/Documents/desktopapp/toolchain/gradle-8.7/bin/gradle

CORE=app/src/main/java/com/cardesk/launcher/core/HostTaskMigrator.kt
MIG=app/src/main/java/com/cardesk/launcher/ui/embed/HostTaskMigration.kt
EMBED=app/src/main/java/com/cardesk/launcher/ui/embed/ActivityEmbed.kt
PIP=app/src/main/java/com/cardesk/launcher/ui/pip/PipSurface.kt
FILES="$CORE $MIG $EMBED $PIP"

# 自建备份（可复跑；不用相对路径 rm -rf）
BK=$(mktemp -d /tmp/y141-bk-XXXXXX)
echo "$BK" > /tmp/y141-bk-path.txt
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
    --tests "com.cardesk.launcher.core.HostTaskMigratorTest" \
    --console=plain > /tmp/y141-rev-run.log 2>&1
  # 注意：gradle 在「**测试断言失败**」时也会返回非零（那是反向项**期望**的结果），
  # 所以不能只看退出码 —— 要先看有没有跑出测试结果文件：
  #   · 有结果文件 → 按断言失败数算红灯（这才是反向项要看的）；
  #   · 没有结果文件 → 多半是**编译不过**（改坏到编译失败）→ 同样算红，但要说明原因。
  python3 - <<'PY'
import glob, xml.etree.ElementTree as ET
files = glob.glob('app/build/test-results/testDebugUnitTest/*.xml')
if not files:
    print("RED=1 TOTAL=0 ERR=0")
    print("   红: 没跑出任何测试结果（多半是编译失败，见 /tmp/y141-rev-run.log）")
else:
    tot=fail=err=0
    names=[]
    for f in files:
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
# 断言一律用**精确文本**（has_exact）—— 注释里写着同样的字样不算数；
# 需要比较先后顺序的用索引比较（idx）。
check_wiring() {
  python3 - "$CORE" "$MIG" "$EMBED" "$PIP" <<'PYEOF'
import io, sys, re
core, mig, embed, pip = [io.open(p, encoding='utf-8').read() for p in sys.argv[1:5]]
checks = []

def strip_comments(src):
    """去掉整行注释与行尾注释。"""
    out = []
    for line in src.splitlines():
        i = line.find('//')
        out.append(line if i < 0 else line[:i])
    return '\n'.join(out)

def has_code(src, text):
    """精确子串必须在**代码部分**里存在（整行注释 / 行尾注释都不算）。"""
    return text in strip_comments(src)

def no_code(src, text):
    """精确子串在**代码部分**里必须不存在（注释不算）。"""
    return text not in strip_comments(src)

def idx(src, text):
    return src.find(text)

def first_code_after(src, anchor):
    """锚点之后的第一条**代码**行（跳过空行与注释）。"""
    i = idx(src, anchor)
    if i < 0:
        return ""
    for line in src[i:].splitlines()[1:]:
        s = line.strip()
        if not s or s.startswith('//'):
            continue
        return s
    return ""

# --- 1) 【红线】启动顺序不许偏离车机验证过的路径
#
# 第141轮最大的教训：第140轮曾把 API 28 的启动顺序改成「先 startActivity(Intent)」，
# 那**偏离了 4237 在真机上验证过的路径** —— 车机上是好的，不能动。
i_pending = idx(embed, '        if (startViaPendingIntent(view, intent)) {')
i_forresult = idx(embed, '        if (startViaIntentForResult(view, intent)) {')
i_intent = idx(embed, '            view.javaClass.getMethod("startActivity", Intent::class.java).invoke(view, intent)')
checks.append((
    i_pending >= 0 and i_forresult >= 0 and i_intent >= 0
    and i_pending < i_forresult and i_forresult < i_intent,
    "启动顺序必须是 PendingIntent → startActivityForResult → startActivity(Intent)（4237 真机验证过的顺序，不许改）",
))

# --- 2) 搬移必须接在「启动成功」之后
checks.append((
    embed.count('HostTaskMigration.schedule(view, pkg)') >= 3,
    "三条启动路径（PendingIntent / startActivityForResult / Intent）成功后都要接上搬移",
))
checks.append((
    has_code(mig, '    fun schedule(view: View, pkg: String?) {'),
    "必须有 schedule（启动后补一脚搬移）",
))

# --- 3) 搬移要走 AOSP 9 真有的那个 AMS 方法
#
# AOSP 9 的 IActivityManager **没有** moveTaskToDisplay（那是 AOSP 10 才有的），
# 只有 moveStackToDisplay —— 设备 framework 里数过字符串，moveTaskToDisplay 命中 0 次。
checks.append((
    has_code(mig, '            "moveStackToDisplay",'),
    "搬移必须走 AOSP 9 的 moveStackToDisplay（moveTaskToDisplay 在 API 28 上不存在）",
))
checks.append((
    has_code(mig, '            "moveTaskToDisplay",'),
    "AOSP 10+ 的 moveTaskToDisplay 也要留着（按平台择优）",
))

# --- 4) 搬移的前提是「确证不在我们屏上」，不能盲搬
checks.append((
    has_code(mig, '        val onOurs = HostTaskMigrator.isOnOurDisplay(host, ourId, ourMetrics, mainMetrics)'),
    "搬移前必须先判「在不在我们的虚拟屏上」（盲搬会动本来就在对地方的 task）",
))
checks.append((
    has_code(core, '    fun isOnOurDisplay('),
    "判据必须是纯逻辑（可 JVM 单测）",
))
checks.append((
    has_code(core, '        if (ourMetrics != null && tw == ourMetrics.widthDp) return true'),
    "宽度跟我们的虚拟屏一样 → 在",
))
checks.append((
    has_code(core, '        if (mainMetrics != null && tw == mainMetrics.widthDp) return false'),
    "宽度跟主屏一样 → 不在 → 搬",
))
# 「两边都不像 → 判不知道」这条规则**只能断言代码**，不能断言注释 ——
# 注释会被剥掉（断言器本来就防「被注释骗过」），拿注释当断言 = 永远假红。
checks.append((
    first_code_after(core, '        if (mainMetrics != null && tw == mainMetrics.widthDp) return false') == 'return null',
    "两边都不像 → 必须判「不知道」（代码上必须是 return null，不能当成不在去搬）",
))
checks.append((
    no_code(core, '        if (ourMetrics != null && size == ourMetrics) return true'),
    "不能按「宽+高」整体比（高度三个口径互相对不上：1080/968/944）",
))

# --- 5) 核心纯逻辑：不得引用任何 android.* 类型
checks.append((
    not re.search(r'^import android\.', core, flags=re.M),
    "核心逻辑不得 import android.*（否则进不了 JVM 单测）",
))

# --- 6) 第130轮的判据在 API 28 上是「每轮都崩」——必须修掉
checks.append((
    no_code(pip, 'baseIntent'),
    "PipSurface 不得再碰 RunningTaskInfo.baseIntent（AOSP 9 里没这个字段 → NoSuchFieldError）",
))
checks.append((
    has_code(pip, '            it.baseActivity?.packageName == pkg || it.topActivity?.packageName == pkg'),
    "改用 baseActivity / topActivity 取包名（这两个字段 AOSP 9 有）",
))

# --- 7) 搬移期间不能判黑（否则看门狗会重启承载应用）
checks.append((
    has_code(pip, '        if (HostTaskMigration.isMigrating(pkg)) return null'),
    "搬移期间判据必须按「不知道」处理（否则会误判黑屏并重启承载应用）",
))
checks.append((
    has_code(mig, '    fun isMigrating(pkg: String): Boolean'),
    "必须有 isMigrating（看门狗要靠它暂缓判黑）",
))

# --- 8) 诊断：读到的证据必须留下痕迹
checks.append((
    has_code(mig, '            "承载任务快照",'),
    "必须把读到的 task 证据（id/stack/尺寸/字段名）记下来",
))
checks.append((
    has_code(mig, '            "fields" to taskFieldNames(),'),
    "必须把运行时真实字段名记下来（各版本字段集不一样，不能照源码猜）",
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
echo "=== 反向1：启动顺序改成「先 startActivity(Intent)」（偏离 4237 真机验证过的路径）==="
patch "$EMBED" \
'        if (startViaPendingIntent(view, intent)) {' \
'        if (startViaIntentFirstOnApi28(view, intent)) {'
check_wiring
restore

echo
echo "=== 反向2：启动后不再接搬移（承载应用留在主屏 = 全屏抢屏）==="
patch "$EMBED" \
'            Logx.i(LogTags.NAV_UI, "ActivityView 启动承载应用", "pkg" to pkg, "api" to "pendingIntent")
            HostTaskMigration.schedule(view, pkg)' \
'            Logx.i(LogTags.NAV_UI, "ActivityView 启动承载应用", "pkg" to pkg, "api" to "pendingIntent")'
check_wiring
restore

echo
echo "=== 反向3：搬移走 AOSP 10 才有的 moveTaskToDisplay（API 28 上没有这个方法）==="
patch "$MIG" \
'            "moveStackToDisplay",' \
'            "moveStackToDisplayXX",'
check_wiring
restore

echo
echo "=== 反向4：搬移前不再判「在不在我们屏上」（盲搬）==="
patch "$MIG" \
'        val onOurs = HostTaskMigrator.isOnOurDisplay(host, ourId, ourMetrics, mainMetrics)' \
'        val onOurs: Boolean? = false'
check_wiring
restore

echo
echo "=== 反向5：判据改成「按宽+高整体比」（高度口径对不上 → 永远判不出）==="
patch "$CORE" \
'        if (ourMetrics != null && tw == ourMetrics.widthDp) return true' \
'        if (ourMetrics != null && tw == ourMetrics.widthDp && task.screenHeightDp == ourMetrics.heightDp) return true'
run_tests
restore

echo
echo "=== 反向6：两边都不像时当成「不在」（会搬一个本来就在对地方的 task）==="
patch "$CORE" \
'        // 两边都不像 → 不知道 → 不搬
        return null' \
'        // 反向：当成不在
        return false'
run_tests
restore

echo
echo "=== 反向7：把 baseIntent 放回去（API 28 上每轮看门狗都崩）==="
patch "$PIP" \
'            it.baseActivity?.packageName == pkg || it.topActivity?.packageName == pkg' \
'            it.baseActivity?.packageName == pkg || it.baseIntent.getPackage() == pkg'
check_wiring
restore

echo
echo "=== 反向8：搬移期间不再暂缓判黑（看门狗会误判黑屏并重启承载应用）==="
patch "$PIP" \
'        if (HostTaskMigration.isMigrating(pkg)) return null' \
'        // 反向：不再暂缓'
check_wiring
restore

echo
echo "=== 反向9：核心逻辑引入 android.*（进不了 JVM 单测）==="
patch "$CORE" \
'package com.cardesk.launcher.core' \
'package com.cardesk.launcher.core

import android.view.Display'
check_wiring
restore

echo
echo "=== 反向10：不再记「读到的真实字段名」（读不到时排障瞎猜）==="
patch "$MIG" \
'            "fields" to taskFieldNames(),' \
'            // 不记了'
check_wiring
restore

echo
echo "=== 还原后自检（应当 0 红灯）==="
run_tests
check_wiring

echo
echo "备份目录：$BK"
