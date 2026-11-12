#!/usr/bin/env bash
# 第149轮反向验证：把本轮四条需求逐条改坏，确认对应断言**必红** ——
# 改坏 → 断言必红 → 还原 → 恢复全绿。
#
# 用法：bash tools/verify_y149_reverse.sh
#
# 背景（第149轮，用户需求）：
#   ① 桌面启动完成后唤起默认媒体来源 App 播放，并在设置里做成可选项；
#   ② 整理设置页各页设置项，把相同功能模块的整合到一个大项，减少重复，并优化 UI；
#   ③ 修设置页滑杆滑动时触发桌面左右滑动的问题；
#   ④ 仪表歌词最小宽度限制 30% → 10%。
#
# 这四条里有好几处「看着对、其实不生效」的地方，所以断言必须查**真实状态**：
#   - 滑杆那条的旧写法（挂上去时置位）实测**无效** —— AOSP 的
#     `ViewGroup.resetTouchState()` 会在每次 ACTION_DOWN 时清掉这个标志；
#   - 启动后播放那条的开关判断必须放在**函数内部**，否则调用方漏判就形同虚设；
#   - 播放指令必须是 `setPlaying(true)`（幂等），不能是 `playPause()`（翻转语义）。
set -u
cd /Users/roy/Documents/desktopapp/cardesk

export JAVA_HOME=/Users/roy/Documents/desktopapp/toolchain/jdk-17.0.20.1+1/Contents/Home
export ANDROID_HOME=/Users/roy/Documents/desktopapp/toolchain/sdk
export ANDROID_SDK_ROOT=/Users/roy/Documents/desktopapp/toolchain/sdk
export GRADLE_USER_HOME=/Users/roy/Documents/desktopapp/.gradle-home
GRADLE=/Users/roy/Documents/desktopapp/toolchain/gradle-8.7/bin/gradle

PREFS=app/src/main/java/com/cardesk/launcher/core/Prefs.kt
SETTINGS=app/src/main/java/com/cardesk/launcher/ui/SettingsOverlay.kt
LAUNCHER=app/src/main/java/com/cardesk/launcher/core/media/MediaAppLauncher.kt
MAIN=app/src/main/java/com/cardesk/launcher/MainActivity.kt
GEOM=app/src/main/java/com/cardesk/launcher/cluster/ClusterPanelGeometry.kt
FILES="$PREFS $SETTINGS $LAUNCHER $MAIN $GEOM"

# 自建备份（可复跑）
BK=$(mktemp -d /tmp/y149-bk-XXXXXX)
echo "$BK" > /tmp/y149-bk-path.txt
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
  python3 -c "import shutil,glob;[shutil.rmtree(p,ignore_errors=True) for p in glob.glob('app/build/test-results/testSystemUnitTest')]"
  "$GRADLE" :app:testSystemUnitTest \
    --tests "com.cardesk.launcher.ui.SettingsTouchInterceptTest" \
    --tests "com.cardesk.launcher.core.media.MediaAppLauncherTest" \
    --tests "com.cardesk.launcher.core.media.MediaAppLauncherLaunchTest" \
    --tests "com.cardesk.launcher.ui.SettingsLayoutTest" \
    --tests "com.cardesk.launcher.cluster.ClusterPanelGeometryTest" \
    --tests "com.cardesk.launcher.ui.MediaSourceIconRenderTest" \
    --console=plain > /tmp/y149-rev-run.log 2>&1
  python3 - <<'PY'
import glob, xml.etree.ElementTree as ET
tot=fail=err=0
names=[]
for f in glob.glob('app/build/test-results/testSystemUnitTest/*.xml'):
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

# 接线检查：Android 侧的接线 JVM 单测覆盖不到，用源码断言兜住。
check_wiring() {
  python3 - "$MAIN" "$LAUNCHER" "$SETTINGS" "$PREFS" "$GEOM" <<'PYEOF'
import io, sys
main, launcher, settings, prefs, geom = [io.open(p, encoding='utf-8').read() for p in sys.argv[1:6]]
checks = []

def strip_comments(src):
    # 注释有两种：`//` 行注释和 `/** */` 块注释。
    # 只剥 `//` 的话，KDoc 里提到「globalGroups」这种**名字**也会被当成代码 ——
    # 那样基线就会恒红（反向项就白做了）。
    import re
    src = re.sub(r'/\*.*?\*/', '', src, flags=re.S)
    out = []
    for line in src.splitlines():
        i = line.find('//')
        out.append(line if i < 0 else line[:i])
    return '\n'.join(out)

def no_text(src, text):
    return text not in strip_comments(src)

def has_code(src, text):
    for line in src.splitlines():
        st = line.strip()
        if st.startswith('//'):
            continue
        if text in line:
            return True
    return False

# —— 需求①：启动后唤起默认媒体源 App ——
checks.append((
    has_code(main, 'MediaAppLauncher.launchAndPlay('),
    "MainActivity 必须在启动流程里调用 MediaAppLauncher.launchAndPlay",
))
checks.append((
    has_code(main, 'autoLaunchEnabled = prefs.autoLaunchMediaApp'),
    "MainActivity 必须把设置页的开关传进去",
))
checks.append((
    has_code(launcher, 'if (!shouldLaunch(autoLaunchEnabled, sourceId))'),
    "开关判断必须在 launchAndPlay **内部**（放外面调用方一漏判就形同虚设）",
))
checks.append((
    has_code(launcher, 'tracker.setPlaying(true)'),
    "播放指令必须是 setPlaying(true)（幂等）",
))
checks.append((
    no_text(launcher, 'tracker.playPause()'),
    "不能用 playPause —— 那是「翻转」语义，启动场景下很可能翻转成「暂停」",
))
checks.append((
    has_code(launcher, 'if (sessionMatches(track?.pkg, pkg))'),
    "必须用 sessionMatches 比对包名，不能给别的 App 的会话下播放",
))
checks.append((
    has_code(prefs, 'sp.getBoolean("auto_launch_media_app", false)'),
    "自动播放开关默认必须是关（开机弹音乐 App 挺打扰）",
))
checks.append((
    has_code(settings, 'switchRow(') and '启动后自动播放' in settings,
    "设置页必须有「启动后自动播放」这个开关",
))
checks.append((
    has_code(settings, 'prefs.autoLaunchMediaApp = on'),
    "设置页的开关必须落盘",
))
# —— 需求②：设置页合并 ——
checks.append((
    has_code(settings, 'NavItem("media", "媒体源")'),
    "导航树里必须有独立的「媒体源」页",
))
checks.append((
    has_code(settings, 'NavItem("lyric", "歌词")'),
    "导航树里必须有合并后的「歌词」页",
))
checks.append((
    no_text(settings, 'globalGroups') and no_text(settings, 'systemGroups'),
    "死代码 globalGroups / systemGroups 必须删掉",
))
checks.append((
    has_code(settings, '"desktoplyric", "clusterlyric" -> "歌词"'),
    "旧 key 的标题映射必须保留（否则外部深链进来标题空白）",
))
checks.append((
    has_code(settings, '"autostart" -> pageBoot()'),
    "旧 key 必须仍然能路由到合并后的页面",
))
# —— 需求③：滑杆别触发翻页 ——
checks.append((
    has_code(settings, 'requestDisallowInterceptTouchEvent(true)'),
    "设置页必须置位「禁止祖先拦截」",
))
checks.append((
    no_text(settings, 'override fun onAttachedToWindow()'),
    "不能只在 onAttachedToWindow 置位 —— AOSP 的 resetTouchState() 会在每次 ACTION_DOWN 清掉它（实测无效）",
))
checks.append((
    has_code(settings, 'override fun dispatchTouchEvent(ev: MotionEvent): Boolean'),
    "必须在 dispatchTouchEvent 里置位（DOWN 派发之后），第一个 MOVE 才受保护",
))
checks.append((
    has_code(settings, 'requestDisallowInterceptTouchEvent(false)'),
    "关掉设置页时必须把标志撤掉（否则桌面翻不了页）",
))
# —— 需求④：仪表歌词最小宽度 10% ——
checks.append((
    has_code(geom, 'const val MIN_WIDTH_RATIO = 0.1f'),
    "几何层下限必须是 0.1",
))
checks.append((
    has_code(prefs, 'intValue("desk_overlay_w", 100).coerceIn(10, 100)'),
    "存储层下限必须是 10",
))
checks.append((
    has_code(settings, 'sliderRow("模块宽度", prefs.overlayWidthPct, 10, 100, "%")'),
    "UI 层滑杆下限必须是 10（三处都要改，只改一处会错位）",
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
echo "=== 反向1：滑杆保护整个去掉（滑动又会触发桌面翻页）==="
patch "$SETTINGS" \
'    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val handled = super.dispatchTouchEvent(ev)
        requestDisallowInterceptTouchEvent(true)
        return handled
    }' \
'    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        return super.dispatchTouchEvent(ev)
    }'
run_tests
check_wiring
restore

echo
echo "=== 反向2：置位挪到 super 之前（requestDisallowInterceptTouchEvent 的短路会让标志传不上去）==="
patch "$SETTINGS" \
'        val handled = super.dispatchTouchEvent(ev)
        requestDisallowInterceptTouchEvent(true)
        return handled' \
'        requestDisallowInterceptTouchEvent(true)
        return super.dispatchTouchEvent(ev)'
run_tests
check_wiring
restore

echo
echo "=== 反向3：改回只在 onAttachedToWindow 置位（实测无效的那个写法）==="
patch "$SETTINGS" \
'    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val handled = super.dispatchTouchEvent(ev)
        requestDisallowInterceptTouchEvent(true)
        return handled
    }' \
'    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        requestDisallowInterceptTouchEvent(true)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        return super.dispatchTouchEvent(ev)
    }'
run_tests
check_wiring
restore

echo
echo "=== 反向4：关设置页时不撤标志（桌面再也翻不了页）==="
patch "$SETTINGS" \
'        requestDisallowInterceptTouchEvent(false)
        super.onDetachedFromWindow()' \
'        super.onDetachedFromWindow()'
run_tests
check_wiring
restore

echo
echo "=== 反向5：启动后播放的开关判断挪到函数外面（调用方一漏判就弹 App）==="
patch "$LAUNCHER" \
'        if (!shouldLaunch(autoLaunchEnabled, sourceId)) {' \
'        if (false) {'
run_tests
check_wiring
restore

echo
echo "=== 反向6：shouldLaunch 不看开关（开关关了也弹）==="
patch "$LAUNCHER" \
'        if (!autoLaunchEnabled) return false' \
'        if (false) return false'
run_tests
check_wiring
restore

echo
echo "=== 反向7：通用音乐也去拉（没有对应 App，硬拉一个包名）==="
patch "$LAUNCHER" \
'        if (sourceId.isBlank() || sourceId == MediaSources.GENERIC_ID) return false' \
'        if (sourceId.isBlank()) return false'
run_tests
check_wiring
restore

echo
echo "=== 反向8：播放指令换成 playPause（翻转语义，很可能翻转成暂停）==="
patch "$LAUNCHER" \
'                    tracker.setPlaying(true)' \
'                    tracker.playPause()'
run_tests
check_wiring
restore

echo
echo "=== 反向9：会话比对放行所有包名（会给别的 App 下播放）==="
patch "$LAUNCHER" \
'        trackPkg != null && trackPkg == targetPkg' \
'        trackPkg != null'
run_tests
check_wiring
restore

echo
echo "=== 反向10：自动播放默认改成开（开机就弹音乐 App）==="
patch "$PREFS" \
'        get() = sp.getBoolean("auto_launch_media_app", false)' \
'        get() = sp.getBoolean("auto_launch_media_app", true)'
run_tests
check_wiring
restore

echo
echo "=== 反向11：MainActivity 不接线（功能根本不会发生）==="
# 整段注释掉，而不是只注释第一行 ——
# 只注释 `launchAndPlay(` 那一行的话，后面的参数列表会变成悬空语法，编译直接失败，
# 那样单测一条都跑不了（TOTAL=0），这条反向项就只剩源码断言在把关。
patch "$MAIN" \
'            com.cardesk.launcher.core.media.MediaAppLauncher.launchAndPlay(
                context = applicationContext,
                autoLaunchEnabled = prefs.autoLaunchMediaApp,
                sourceId = prefs.defaultMusicSource,
            )' \
'            // com.cardesk.launcher.core.media.MediaAppLauncher.launchAndPlay(
            //     context = applicationContext,
            //     autoLaunchEnabled = prefs.autoLaunchMediaApp,
            //     sourceId = prefs.defaultMusicSource,
            // )'
run_tests
check_wiring
restore

echo
echo "=== 反向12：设置页不给「启动后自动播放」开关落盘 ==="
patch "$SETTINGS" \
'            prefs.autoLaunchMediaApp = on' \
'            // prefs.autoLaunchMediaApp = on'
run_tests
check_wiring
restore

echo
echo "=== 反向13：导航树少一项（合并没做完）==="
patch "$SETTINGS" \
'                NavItem("media", "媒体源"),' \
''
run_tests
check_wiring
restore

echo
echo "=== 反向14：旧 key 不再路由（外部深链进来会空白）==="
patch "$SETTINGS" \
'            "desktoplyric", "clusterlyric" -> "歌词"' \
'            "desktoplyric", "clusterlyric" -> ""'
run_tests
check_wiring
restore

echo
echo "=== 反向15：死代码复活（globalGroups 又回来了）==="
patch "$SETTINGS" \
'    private val mergedGroups = listOf(' \
'    private val globalGroups = listOf<NavGroup>()

    private val mergedGroups = listOf('
run_tests
check_wiring
restore

echo
echo "=== 反向16：几何层下限改回 0.2 ==="
patch "$GEOM" \
'    const val MIN_WIDTH_RATIO = 0.1f' \
'    const val MIN_WIDTH_RATIO = 0.2f'
run_tests
check_wiring
restore

echo
echo "=== 反向17：存储层下限改回 30 ==="
patch "$PREFS" \
'        get() = intValue("desk_overlay_w", 100).coerceIn(10, 100)' \
'        get() = intValue("desk_overlay_w", 100).coerceIn(30, 100)'
run_tests
check_wiring
restore

echo
echo "=== 反向18：UI 层滑杆下限改回 30（拖到 10% 会被夹回去）==="
patch "$SETTINGS" \
'        sliderRow("模块宽度", prefs.overlayWidthPct, 10, 100, "%")' \
'        sliderRow("模块宽度", prefs.overlayWidthPct, 30, 100, "%")'
run_tests
check_wiring
restore

echo
echo "=== 还原后自检（应当 0 红灯）==="
run_tests
check_wiring

echo
echo "备份目录：$BK"
