#!/usr/bin/env bash
# 第140轮反向验证：把实现逐条改回「旧行为」，确认对应断言**必红**。
#
# 用法：bash tools/verify_y140_reverse.sh
#
# 背景（第140轮）：
#   ① `ActivityEmbed.virtualDisplayIdOf()` 在 **API 28**（车机同版本）上**恒取不到** id
#      → 画中画黑屏的权威判据（「承载应用的 task 在不在我们的 VirtualDisplay 上」）整条失效；
#   ② 启动承载应用的「两参重载」传出去的 options **不带 launchDisplayId**
#      → 某些 ROM 上承载应用会落到默认显示器 = 全屏抢屏；
#   ③ 配套做一个「可被嵌入」的测试承载应用 `embedtest`，让 API 28 能独立验证画中画链路。
#
#   每一条反向验证都对应一组断言：改坏 → 断言必红 → 还原 → 恢复全绿。
set -u
cd /Users/roy/Documents/desktopapp/cardesk

export JAVA_HOME=/Users/roy/Documents/desktopapp/toolchain/jdk-17.0.20.1+1/Contents/Home
export GRADLE_USER_HOME=/Users/roy/Documents/desktopapp/.gradle-home
GRADLE=/Users/roy/Documents/desktopapp/toolchain/gradle-8.7/bin/gradle

CORE=app/src/main/java/com/cardesk/launcher/core/VirtualDisplayIdResolver.kt
EMBED=app/src/main/java/com/cardesk/launcher/ui/embed/ActivityEmbed.kt
PIP=app/src/main/java/com/cardesk/launcher/ui/pip/PipSurface.kt
SETTINGS=settings.gradle.kts
ET_BUILD=embedtest/build.gradle.kts
ET_MANIFEST=embedtest/src/main/AndroidManifest.xml
RELEASE=tools/release.sh
FILES="$CORE $EMBED $PIP $SETTINGS $ET_BUILD $ET_MANIFEST $RELEASE"

# 自建备份（可复跑；不用相对路径 rm -rf）
BK=$(mktemp -d /tmp/y140-bk-XXXXXX)
echo "$BK" > /tmp/y140-bk-path.txt
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
    --tests "com.cardesk.launcher.core.VirtualDisplayIdResolverTest" \
    --console=plain > /tmp/y140-rev-run.log 2>&1
  # 注意：gradle 在「**测试断言失败**」时也会返回非零（那是反向项**期望**的结果），
  # 所以不能只看退出码 —— 要先看有没有跑出测试结果文件：
  #   · 有结果文件 → 按断言失败数算红灯（这才是反向项要看的）；
  #   · 没有结果文件 → 多半是**编译不过**（改坏到编译失败）→ 同样算红，但要说明原因。
  python3 - <<'PY'
import glob, os, xml.etree.ElementTree as ET
files = glob.glob('app/build/test-results/testDebugUnitTest/*.xml')
if not files:
    print("RED=1 TOTAL=0 ERR=0")
    print("   红: 没跑出任何测试结果（多半是编译失败，见 /tmp/y140-rev-run.log）")
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
  python3 - "$CORE" "$EMBED" "$PIP" "$SETTINGS" "$ET_BUILD" "$ET_MANIFEST" "$RELEASE" <<'PYEOF'
import io, sys, re
core, embed, pip, settings, etb, etm, release = [io.open(p, encoding='utf-8').read() for p in sys.argv[1:8]]
checks = []

def has_exact(src, text):
    """精确子串存在（含缩进）—— 用来钉住「调用点」而不是「同名函数」。"""
    return text in src

def strip_comments(src):
    """去掉整行注释与行尾注释（XML 注释另算）。"""
    out = []
    for line in src.splitlines():
        i = line.find('//')
        out.append(line if i < 0 else line[:i])
    return '\n'.join(out)

def strip_xml_comments(src):
    return re.sub(r'<!--.*?-->', '', src, flags=re.S)

def has_code(src, text):
    """精确子串必须在**代码部分**里存在（整行注释 / 行尾注释都不算）。"""
    return text in strip_comments(src)

def no_code(src, text):
    """精确子串在**代码部分**里必须不存在（注释不算）。"""
    return text not in strip_comments(src)

def idx(src, text):
    return src.find(text)

# --- 1) 判据的取舍规则必须走纯逻辑
checks.append((
    has_code(embed, '        return VirtualDisplayIdResolver.pick(candidates, ownDisplayId)'),
    "virtualDisplayIdOf 必须走 VirtualDisplayIdResolver.pick（取舍规则写死就等于没有规则）",
))
checks.append((
    has_code(core, '    fun pick(candidates: List<Candidate>, ownDisplayId: Int? = null): Int? {'),
    "核心必须有 pick（纯逻辑，可 JVM 单测）",
))
checks.append((
    has_code(core, '            if (!c.confirmedVirtual) continue'),
    "只认「已确认是虚拟显示器」的候选（第130轮：mHost.mDisplay 是桌面那块屏，认了会误判）",
))
checks.append((
    has_code(core, '            if (id <= 0) continue'),
    "displayId <= 0 必须丢弃（0 是主显示器）",
))
checks.append((
    has_code(core, '            if (ownDisplayId != null && id == ownDisplayId) continue'),
    "等于我们自己的 displayId 的候选必须排除（车机是多屏的）",
))

# --- 2) API 28 的关键：mVirtualDisplay 是 VirtualDisplay（不是 Display），要剥一层
checks.append((
    has_code(embed, '            VirtualDisplayIdResolver.displayIdOfVirtualDisplayLike(own),'),
    "自持 mVirtualDisplay 必须走剥壳解析（API 28 上它不是 android.view.Display）",
))
checks.append((
    has_code(core, '        val inner = invokeNoArg(obj, "getDisplay")'),
    "核心必须支持「壳 → getDisplay() → Display」这一层（API 28 的形态）",
))
checks.append((
    has_code(core, '        if (obj.javaClass.simpleName.contains("VirtualDisplay", ignoreCase = true)) return true'),
    "判断虚拟显示器要看**简单类名**（全名会把外层类名也算进来 → 误判）",
))
checks.append((
    has_code(core, '        invokeInt(obj, "getDisplayId")?.let { return it }'),
    "核心必须能从 Display 上取到 displayId",
))

# --- 3) 启动承载应用：两参重载的 options 必须带 launchDisplayId
checks.append((
    has_code(embed, '                cls.getMethod("setLaunchDisplayId", Int::class.javaPrimitiveType).invoke(opts, vid)'),
    "两参重载的 options 必须带上我们这个 VirtualDisplay 的 id（否则承载应用落到主屏 = 全屏抢屏）",
))
checks.append((
    has_code(embed, '        val vid = virtualDisplayIdOf(view)'),
    "启动前必须先拿到我们这个 VirtualDisplay 的 id",
))
# 顺序：两参在前、单参在后（单参是 AOSP 自己的实现，也带 displayId）
i_two = idx(embed, '                    .getMethod("startActivity", PendingIntent::class.java, Class.forName("android.app.ActivityOptions"))')
i_one = idx(embed, '            view.javaClass.getMethod("startActivity", PendingIntent::class.java).invoke(view, pending)')
checks.append((
    i_two >= 0 and i_one >= 0 and i_two < i_one,
    "两参重载必须在单参重载**之前**（第139轮验过的顺序）",
))

# --- 4) 诊断：判据失效要留痕
checks.append((
    has_code(embed, '                    "vid" to virtualDisplayIdOf(view),'),
    "「承载面首次就绪」日志必须带 vid（判据失效要能一眼看出来）",
))
checks.append((
    has_code(pip, '        logOurDisplayId(ourId)'),
    "看门狗必须把解析出来的虚拟屏 id 记下来（限频：变化时才记）",
))
checks.append((
    has_code(pip, '    private fun logOurDisplayId(id: Int?) {'),
    "必须有 logOurDisplayId",
))
checks.append((
    has_code(pip, '        if (id == lastOurDisplayId) return'),
    "ourId 日志必须限频（变化时才记）",
))

# --- 5) 测试承载应用：独立模块，不进正式包
checks.append((
    has_code(settings, 'include(":embedtest")'),
    "必须引入 embedtest 模块",
))
checks.append((
    'android:allowEmbedded="${allowEmbedded}"' in strip_xml_comments(etm),
    "embedtest 的 Activity 必须声明 allowEmbedded",
))
checks.append((
    'android:resizeableActivity="true"' in strip_xml_comments(etm),
    "embedtest 的 Activity 必须声明 resizeableActivity",
))
checks.append((
    has_code(etb, '        manifestPlaceholders["allowEmbedded"] ='),
    "embedtest 的 allowEmbedded 要可切换（第140轮的 A/B 实验）",
))
checks.append((
    has_code(release, 'APK="$WORK/app/build/outputs/apk/system/app-system.apk"'),
    "release.sh 只能取正式包的 APK（embedtest 不能进 dist/）",
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
echo "=== 反向1：不再剥「VirtualDisplay → getDisplay()」这一层（API 28 回到取不到 id）==="
patch "$CORE" \
'        val inner = invokeNoArg(obj, "getDisplay")
        if (inner != null && inner !== obj) {
            return displayIdOfVirtualDisplayLike(inner, depth + 1)
        }' \
'        // 反向：不再剥壳'
run_tests
restore

echo
echo "=== 反向2：displayId <= 0 也认（把主显示器当成虚拟屏）==="
patch "$CORE" \
'            if (id <= 0) continue' \
'            if (id < -1) continue'
run_tests
restore

echo
echo "=== 反向3：不排除「我们自己那块屏」的 displayId（车机多屏 → 误判）==="
patch "$CORE" \
'            if (ownDisplayId != null && id == ownDisplayId) continue' \
'            if (false) continue'
run_tests
restore

echo
echo "=== 反向4：不再要求「已确认是虚拟显示器」（第130轮的坑回来了）==="
patch "$CORE" \
'            if (!c.confirmedVirtual) continue' \
'            if (false) continue'
run_tests
restore

echo
echo "=== 反向5：判断虚拟显示器改看全类名（外层类名带 VirtualDisplay 就误判）==="
patch "$CORE" \
'        if (obj.javaClass.simpleName.contains("VirtualDisplay", ignoreCase = true)) return true' \
'        if (obj.javaClass.name.contains("VirtualDisplay", ignoreCase = true)) return true'
run_tests
restore

echo
echo "=== 反向6：virtualDisplayIdOf 不走 pick（写死返回 null = 判据全废）==="
patch "$EMBED" \
'        return VirtualDisplayIdResolver.pick(candidates, ownDisplayId)' \
'        return null'
check_wiring
restore

echo
echo "=== 反向7：自持 mVirtualDisplay 退回只认 android.view.Display（旧写法）==="
patch "$EMBED" \
'            VirtualDisplayIdResolver.displayIdOfVirtualDisplayLike(own),' \
'            (own as? android.view.Display)?.displayId,'
check_wiring
restore

echo
echo "=== 反向8：两参重载的 options 不带 launchDisplayId（承载应用会落到主屏）==="
patch "$EMBED" \
'                cls.getMethod("setLaunchDisplayId", Int::class.javaPrimitiveType).invoke(opts, vid)' \
'                // 反向：不带 launchDisplayId'
check_wiring
restore

echo
echo "=== 反向9：把两参重载降级成单参（顺序反了）==="
patch "$EMBED" \
'                    .getMethod("startActivity", PendingIntent::class.java, Class.forName("android.app.ActivityOptions"))' \
'                    .getMethod("startActivity", PendingIntent::class.java)'
check_wiring
restore

echo
echo "=== 反向10：「承载面首次就绪」日志不记 vid（判据失效查不出来）==="
patch "$EMBED" \
'                    "vid" to virtualDisplayIdOf(view),' \
'                    // vid 不记了'
check_wiring
restore

echo
echo "=== 反向11：看门狗不记 ourId（限频日志也没了）==="
patch "$PIP" \
'        logOurDisplayId(ourId)' \
'        // 不记'
check_wiring
restore

echo
echo "=== 反向12：embedtest 不声明 allowEmbedded（测试应用自己进不了虚拟屏）==="
patch "$ET_MANIFEST" \
'            android:allowEmbedded="${allowEmbedded}"' \
'            <!-- android:allowEmbedded="${allowEmbedded}" -->'
check_wiring
restore

echo
echo "=== 反向13：把 embedtest 的模块声明注释掉（验证断言不会被注释骗过）==="
patch "$SETTINGS" \
'include(":embedtest")' \
'// include(":embedtest")'
check_wiring
restore

echo
echo "=== 反向14：release.sh 改成把 embedtest 的包也收进 dist/==="
patch "$RELEASE" \
'APK="$WORK/app/build/outputs/apk/system/app-system.apk"' \
'APK="$(ls -1 "$WORK"/*/build/outputs/apk/system/*.apk | head -1)"'
check_wiring
restore

echo
echo "=== 还原后自检（应当 0 红灯）==="
run_tests
check_wiring

echo
echo "备份目录：$BK"
