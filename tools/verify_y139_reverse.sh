#!/usr/bin/env bash
# 第139轮反向验证：把实现逐条改回「旧行为」，确认对应断言**必红**。
#
# 用法：bash tools/verify_y139_reverse.sh
#
# 背景（第139轮，用户需求）：
#   「切换日夜模式时，高德会突然从画中画变成全屏」—— 通过模拟器复现并修复。
#
# 根因：换肤重建（applyTheme → build）前 `releasePipSurfaces("重建前")`
#   会**销毁旧的 VirtualDisplay**；挂在它上面的承载应用 task 会被系统
#   **搬到主显示器**（Android 9 的 ActivityStackManager 就是这么处理的），
#   → 高德从画中画变成全屏抢屏。
#
# 修法：换肤重建改成「摘走 → 释放外壳 → 交给新承载面」，
#   VirtualDisplay 全程不死 → task 一直在我们的 display 上。
#
#   每一条反向验证都对应一组断言：改坏 → 断言必红 → 还原 → 恢复全绿。
set -u
cd /Users/roy/Documents/desktopapp/cardesk

export JAVA_HOME=/Users/roy/Documents/desktopapp/toolchain/jdk-17.0.20.1+1/Contents/Home
export GRADLE_USER_HOME=/Users/roy/Documents/desktopapp/.gradle-home
GRADLE=/Users/roy/Documents/desktopapp/toolchain/gradle-8.7/bin/gradle

POLICY=app/src/main/java/com/cardesk/launcher/core/PipRebuildPolicy.kt
LAUNCHER=app/src/main/java/com/cardesk/launcher/ui/LauncherView.kt
PIP=app/src/main/java/com/cardesk/launcher/ui/pip/PipSurface.kt
EMBED=app/src/main/java/com/cardesk/launcher/ui/embed/ActivityEmbed.kt
FILES="$POLICY $LAUNCHER $PIP $EMBED"

# 自建备份（可复跑；不用相对路径 rm -rf）
BK=$(mktemp -d /tmp/y139-bk-XXXXXX)
echo "$BK" > /tmp/y139-bk-path.txt
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
    --tests "com.cardesk.launcher.core.PipRebuildPolicyTest" \
    --console=plain > /tmp/y139-rev-run.log 2>&1
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
# 断言一律用**精确文本**（has_exact）—— 注释里写着同样的字样不算数；
# 需要比较先后顺序的用索引比较（idx）。
check_wiring() {
  python3 - "$LAUNCHER" "$PIP" "$EMBED" "$POLICY" <<'PYEOF'
import io, sys
launcher, pip, embed, policy = [io.open(p, encoding='utf-8').read() for p in sys.argv[1:5]]
checks = []

def has_exact(src, text):
    """精确子串存在（含缩进）—— 用来钉住「调用点」而不是「同名函数」。"""
    return text in src

def strip_comments(src):
    out = []
    for line in src.splitlines():
        i = line.find('//')
        out.append(line if i < 0 else line[:i])
    return '\n'.join(out)

def no_code(src, text):
    """精确子串在**代码部分**里必须不存在（注释不算）。"""
    return text not in strip_comments(src)

def has_code(src, text):
    """精确子串必须在**代码部分**里存在（整行注释 / 行尾注释都不算）。

    为什么不能直接用 `text in src`：
    源码注释里经常把「正确的写法」原样抄一遍（比如「不能写成 xxx」），
    纯文本匹配会把这段说明当成代码 → 断言假绿。
    """
    return text in strip_comments(src)

def idx(src, text):
    return src.find(text)

# --- 1) 策略：主画 / 副画的判定必须走 PipRebuildPolicy
checks.append((
    has_code(launcher, 'com.cardesk.launcher.core.PipRebuildPolicy.mainAction(navPage.pip.hasContent)'),
    "主画处置必须走 PipRebuildPolicy.mainAction（写死就等于没有策略）",
))
checks.append((
    has_code(launcher, 'com.cardesk.launcher.core.PipRebuildPolicy.secondaryAction(\n                navPage.pipSecondary.hasContent,'),
    "副画处置必须走 PipRebuildPolicy.secondaryAction",
))
checks.append((
    has_exact(policy, 'fun mainAction(hasContent: Boolean): Action ='),
    "策略必须有 mainAction",
))
checks.append((
    has_exact(policy, 'fun secondaryAction(hasContent: Boolean, dualPip: Boolean, secondaryApp: String): Action ='),
    "策略必须有 secondaryAction",
))

# --- 2) 顺序：必须先「摘」再「释放」
i_carry = idx(launcher, '        val carried = carryOutPipSurfaces()')
i_release = idx(launcher, '        releasePipSurfaces("重建前")')
checks.append((
    i_carry >= 0 and i_release >= 0 and i_carry < i_release,
    "换肤重建必须**先摘承载面再释放**（先释放就摘不出来了，高德会被重启）",
))

# --- 3) 新承载面必须接住摘出来的承载内容
i_nav = idx(launcher, '        navPage = NavPage(context, pal).apply {')
i_adopt = idx(launcher, '            navPage.pip.adoptContent(carried.view, carried.pkg)')
checks.append((
    i_nav >= 0 and i_adopt >= 0 and i_adopt > i_nav,
    "新承载面必须在建好之后 adoptContent（否则等于没搬）",
))
checks.append((
    has_code(launcher, '        val carried = carryOutPipSurfaces()'),
    "换肤重建必须先摘承载面（这一步是本轮修复的核心）",
))
checks.append((
    has_code(launcher, '        releasePipSurfaces("重建前")'),
    "换肤重建仍要释放承载面外壳（否则旧 VirtualDisplay 泄漏）",
))
checks.append((
    has_code(launcher, '            navPage.pipSecondary.adoptContent(carried.view, carried.pkg)'),
    "副画也必须 adoptContent（双画时副画同样会被拆掉 VirtualDisplay）",
))

# --- 4) 摘的时候不能销毁 VirtualDisplay
checks.append((
    has_code(pip, '    fun detachContent(): View? {'),
    "PipSurface 必须提供 detachContent（把承载内容摘出来）",
))
checks.append((
    has_code(pip, '    fun adoptContent(view: View, pkg: String): Boolean {'),
    "PipSurface 必须提供 adoptContent（接管摘出来的承载内容）",
))
# 函数体单独切出来看，并且**去掉注释** ——
# 注释里写着「摘的时候不能调 ActivityEmbed.release」也会被文本匹配命中。
detach_body = pip[pip.index('    fun detachContent(): View? {'):pip.index('    fun adoptContent(view: View, pkg: String): Boolean {')]
detach_code = strip_comments(detach_body)
checks.append((
    'ActivityEmbed.release(' not in detach_code,
    "detachContent 里绝不能调用 ActivityEmbed.release（那一脚就是销毁 VirtualDisplay → 高德被搬到主屏）",
))
checks.append((
    'ActivityEmbed.suppressNextRelaunch(v)' in detach_code,
    "detachContent 必须抑制「surface 重建就重新拉起」（否则高德被重启一遍）",
))
adopt_body = pip[pip.index('    fun adoptContent(view: View, pkg: String): Boolean {'):]
adopt_body = adopt_body[:adopt_body.index('\n    }\n') + 6]
adopt_code = strip_comments(adopt_body)
checks.append((
    'content = view' in adopt_code and 'ready = true' in adopt_code,
    "adoptContent 必须真的把承载内容接到这块承载面上（content/ready 都要置位）",
))

# --- 5) 搬家时不能重新拉起承载应用
checks.append((
    has_code(embed, '    fun suppressNextRelaunch(view: View) {'),
    "ActivityEmbed 必须提供 suppressNextRelaunch",
))
checks.append((
    has_code(embed, '            if (suppressRelaunchOnce) {'),
    "onSurfaceCreated 必须消费 suppressRelaunchOnce（搬家那次重建不重新拉起）",
))
checks.append((
    has_code(embed, '                suppressRelaunchOnce = false'),
    "suppressRelaunchOnce 只生效一次（用掉就要清掉，否则会误伤真正的自愈）",
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
echo "=== 反向1：主画有内容也直接释放（旧行为：拆 VirtualDisplay）==="
patch "$POLICY" \
'    fun mainAction(hasContent: Boolean): Action =
        if (hasContent) Action.TRANSFER else Action.RELEASE' \
'    fun mainAction(hasContent: Boolean): Action = Action.RELEASE'
run_tests
restore

echo
echo "=== 反向2：副画不看双画开关（双画关了还留着 VirtualDisplay）==="
patch "$POLICY" \
'        if (hasContent && dualPip && secondaryApp.isNotBlank()) Action.TRANSFER else Action.RELEASE' \
'        if (hasContent && secondaryApp.isNotBlank()) Action.TRANSFER else Action.RELEASE'
run_tests
restore

echo
echo "=== 反向3：副画不看有没有选应用（空应用名也去搬）==="
patch "$POLICY" \
'        if (hasContent && dualPip && secondaryApp.isNotBlank()) Action.TRANSFER else Action.RELEASE' \
'        if (hasContent && dualPip) Action.TRANSFER else Action.RELEASE'
run_tests
restore

echo
echo "=== 反向4：搬家之后又重新拉起承载应用（高德被重启）==="
patch "$POLICY" \
'    fun shouldRelaunchOnRecreate(action: Action): Boolean = action != Action.TRANSFER' \
'    fun shouldRelaunchOnRecreate(action: Action): Boolean = true'
run_tests
restore

echo
echo "=== 反向5：主画处置写死（不再走策略）==="
patch "$LAUNCHER" \
'com.cardesk.launcher.core.PipRebuildPolicy.mainAction(navPage.pip.hasContent)' \
'com.cardesk.launcher.core.PipRebuildPolicy.mainAction(false)'
check_wiring
restore

echo
echo "=== 反向6：副画处置不看有没有承载内容==="
patch "$LAUNCHER" \
'                navPage.pipSecondary.hasContent,' \
'                false,'
check_wiring
restore

echo
echo "=== 反向7：先释放再摘（摘不出来，高德被重启）==="
patch "$LAUNCHER" \
'        val carried = carryOutPipSurfaces()
        releasePipSurfaces("重建前")' \
'        releasePipSurfaces("重建前")
        val carried = carryOutPipSurfaces()'
check_wiring
restore

echo
echo "=== 反向8：摘出来之后不交给新承载面（等于没搬）==="
patch "$LAUNCHER" \
'        carried.main?.let { carried ->
            navPage.pip.adoptContent(carried.view, carried.pkg)
        }' \
'        // 摘出来之后就丢掉了（老行为：重新建一块承载面）'
check_wiring
restore

echo
echo "=== 反向9：摘的时候顺手把 VirtualDisplay 也拆了==="
patch "$PIP" \
'        val v = content ?: return null
        content = null' \
'        val v = content ?: return null
        ActivityEmbed.release(v)
        content = null'
check_wiring
restore

echo
echo "=== 反向10：搬家时不抑制「surface 重建就重新拉起」==="
patch "$EMBED" \
'            if (suppressRelaunchOnce) {' \
'            if (false) {'
check_wiring
restore

echo
echo "=== 反向11：adoptContent 不真的把承载内容接上==="
patch "$PIP" \
'        content = view
        ready = true
        addView(view, insetParams())' \
'        content = null
        ready = true
        addView(view, insetParams())'
check_wiring
restore

echo
echo "=== 反向12：把 adoptContent 调用注释掉（验证断言不会被注释骗过）==="
patch "$LAUNCHER" \
'        carried.main?.let { carried ->
            navPage.pip.adoptContent(carried.view, carried.pkg)
        }' \
'        // carried.main?.let { carried ->
        //     navPage.pip.adoptContent(carried.view, carried.pkg)
        // }'
check_wiring
restore

echo
echo "=== 反向13：干脆不释放旧承载面外壳（旧 VirtualDisplay 泄漏）==="
patch "$LAUNCHER" \
'        releasePipSurfaces("重建前")' \
'        // releasePipSurfaces("重建前")'
check_wiring
restore

echo
echo "=== 还原后自检（应当 0 红灯）==="
run_tests
check_wiring

echo
echo "备份目录：$BK"
