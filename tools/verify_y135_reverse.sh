#!/usr/bin/env bash
# 第135轮反向验证：把实现逐条改回「旧行为」，确认对应断言**必红**。
#
# 用法：bash tools/verify_y135_reverse.sh
#
# 背景（第135轮）：
#   用户拿 4231 版的真车诊断包，报了两个问题：
#   1) 「自动切换驾驶模式功能无效」；
#   2) 「没打开播放器的情况下，方控默认唤起的还是原车爱趣听」。
#
#   根因 1（只存不执行）：
#     设置页的 `start_switch_enabled` / `start_switch_mode` 全仓**只有 SettingsOverlay
#     在写**，没有任何执行端（`git log -S start_switch_enabled` 只命中首次提交 22d5b75）。
#     注意别和第132轮的 `iss_keep_enabled`（发动机启停）搞混 —— 那是另一个功能号。
#
#   根因 2（claim 缺位）：
#     诊断包实测 `bridge.registered=true`、`bridge.controlTarget=com.tencent.qqmusiccar`
#     （第131轮的目标修复**已生效**），但 `bridge.callbackCount=0`、`playClaimed=false`，
#     且所有日志里「OEM 方控回调」**0 条**。
#     原因：`MediaBridgePolicy.shouldAcquire` 要求「有样本**且正在播**」才抢播放权；
#     没在放 → 不 claim → 原车认为当前音源还是爱趣听 → 方控不交给我们
#     → 我们连回调都收不到 → 第131轮那段「拉起桌面选定播放器」的代码**根本没机会执行**。
#     参考 App（君君桌面） `SessionBridge.setBridgeEnabled(true)` 是「**启用即 claim**」
#     （`pushToMediaCenter(true)` → `t.a()`）。
#
#   下面每一条反向验证都对应一组断言：改坏 → 断言必红 → 还原 → 恢复全绿。
set -u
cd /Users/roy/Documents/desktopapp/cardesk

export JAVA_HOME=/Users/roy/Documents/desktopapp/toolchain/jdk-17.0.20.1+1/Contents/Home
export GRADLE_USER_HOME=/Users/roy/Documents/desktopapp/.gradle-home
GRADLE=/Users/roy/Documents/desktopapp/toolchain/gradle-8.7/bin/gradle

LOGIC=app/src/main/java/com/cardesk/launcher/vehicle/DriveModeSwitch.kt
EXEC=app/src/main/java/com/cardesk/launcher/vehicle/DriveModeAutoStart.kt
POLICY=app/src/main/java/com/cardesk/launcher/core/media/MediaBridgePolicy.kt
BRIDGE=app/src/main/java/com/cardesk/launcher/core/media/OfficialMediaBridge.kt
TRACKER=app/src/main/java/com/cardesk/launcher/core/media/MusicSessionTracker.kt
GATEWAY=app/src/main/java/com/cardesk/launcher/vehicle/AdaptapiGateway.kt
BOOT=app/src/main/java/com/cardesk/launcher/BootReceiver.kt
LAUNCHER=app/src/main/java/com/cardesk/launcher/ui/LauncherView.kt
FILES="$LOGIC $EXEC $POLICY $BRIDGE $TRACKER $GATEWAY $BOOT $LAUNCHER"

# 自建备份（可复跑；不用相对路径 rm -rf）
BK=$(mktemp -d /tmp/y135-bk-XXXXXX)
echo "$BK" > /tmp/y135-bk-path.txt
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
    --tests "com.cardesk.launcher.vehicle.DriveModeSwitchTest" \
    --tests "com.cardesk.launcher.core.media.MediaBridgePolicyTest" \
    --console=plain > /tmp/y135-rev-run.log 2>&1
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
  python3 - "$GATEWAY" "$BOOT" "$LAUNCHER" "$BRIDGE" "$TRACKER" <<'PYEOF'
import io, sys
gw, boot, launcher, bridge, tracker = [io.open(p, encoding='utf-8').read() for p in sys.argv[1:6]]
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

# 1) 真车通道的「选择型写入」必须**两参优先**（参考 App（君君桌面）口径）
body = func_body(gw, "override fun writeSelectFunction")
checks.append((
    'if (m2 != null && invokeBool(m2, f, func, value) == true) {' in body,
    "AdaptapiGateway.writeSelectFunction 必须是两参优先（两参在 zone 降级之前）",
))
checks.append((
    'intArrayOf(2, 1)' in body,
    "AdaptapiGateway.writeSelectFunction 的 zone 降级顺序必须是 2 再 1",
))

# 2) 执行端必须真的被接上（两个触发点）
checks.append((
    has_call(boot, 'DriveModeAutoStart.apply(context, "开机广播")'),
    "BootReceiver 必须在开机广播里触发自动切换驾驶模式",
))
checks.append((
    has_call(launcher, 'DriveModeAutoStart.apply(context, "桌面启动")'),
    "LauncherView 必须在桌面启动时触发自动切换驾驶模式（有些车机不给 BOOT_COMPLETED）",
))

# 3) 桥：注册成功必须主动 claim 一次
checks.append((
    has_call(bridge, 'claimIfNeeded("注册成功")'),
    "OfficialMediaBridge.register 成功后必须主动 claim 一次（对齐参考 App（君君桌面）「启用即 claim」）",
))
# claim 必须真的调 requestPlay（用精确片段，避免和 publish() 里的那处混淆）
cbody = func_body(bridge, "private fun claimIfNeeded")
checks.append((
    'invoke("requestPlay", token) as? Boolean) == true }.getOrDefault(false)' in cbody,
    "claimIfNeeded 必须真的调 requestPlay（只声明音源类型不算接管）",
))
checks.append((
    'invoke("updateCurrentSourceType", token, ONLINE)' in cbody,
    "claimIfNeeded 必须调 updateCurrentSourceType（声明当前音源类型）",
))
# 4) 车机自带音源在放的信号必须从 tracker 传到桥
checks.append((
    has_call(tracker, 'OfficialMediaBridge.setNativeSourcePlaying(nativeSourcePlaying())'),
    "MusicSessionTracker 必须把「车机自带音源正在放」同步给桥",
))

bad = [msg for ok, msg in checks if not ok]
print("RED=%d TOTAL=1 ERR=0" % (1 if bad else 0))
for m in bad:
    print("   红:", m)
PYEOF
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
check_wiring

echo
echo "=== 反向1：目标模式什么值都算合法（厂商的读失败哨兵会被当成模式写下去）==="
patch "$LOGIC" \
'    fun isValidTarget(target: Int): Boolean {
        if (DriveMode.isReadError(target)) return false
        val normalized = DriveMode.normalizeModeId(target)
        if (normalized == DriveMode.SELECTION_NONE) return false
        return DriveMode.shortLabelForValue(normalized) != null
    }' \
'    fun isValidTarget(target: Int): Boolean {
        return true
    }'
run_tests
restore

echo
echo "=== 反向2：读不到当前模式也判成「已经是目标」（读失败的机器永远切不了）==="
patch "$LOGIC" \
'        val c = current ?: return false' \
'        val c = current ?: return true'
run_tests
restore

echo
echo "=== 反向3：冷却失效（一次开机里对着车机连写几十次）==="
patch "$LOGIC" \
'        lastAt <= 0L || now - lastAt >= COOLDOWN_MS' \
'        true'
run_tests
restore

echo
echo "=== 反向4：写入退回「只有两参」的老路（固件不提供两参重载就切不了）==="
patch "$LOGIC" \
'        val ok = runCatching { gw.writeSelectFunction(DriveMode.FUNCTION_DRIVE_MODE_SELECT, target) }' \
'        val ok = runCatching { gw.writeFunction(DriveMode.FUNCTION_DRIVE_MODE_SELECT, target) }'
run_tests
restore

echo
echo "=== 反向5：zone 降级顺序颠倒（1 先 2 后，与参考 App（君君桌面）相反）==="
patch "$LOGIC" \
'    val FALLBACK_ZONES = intArrayOf(2, 1)' \
'    val FALLBACK_ZONES = intArrayOf(1, 2)'
run_tests
restore

echo
echo "=== 反向6：真车通道不走两参（改成三参优先，与参考 App（君君桌面）口径相反）==="
patch "$GATEWAY" \
'        val m2 = findMethod(f, "setFunctionValue", INT, INT)
        val m3 = findMethod(f, "setFunctionValue", INT, INT, INT)
        // 1) 两参（文档口径，也是参考 App（君君桌面）的第一选择）
        if (m2 != null && invokeBool(m2, f, func, value) == true) {' \
'        val m2 = findMethod(f, "setFunctionValue", INT, INT)
        val m3 = findMethod(f, "setFunctionValue", INT, INT, INT)
        // 1) 两参（文档口径，也是参考 App（君君桌面）的第一选择）
        if (false && m2 != null && invokeBool(m2, f, func, value) == true) {'
check_wiring
restore

echo
echo "=== 反向7：执行端不接开机广播（功能又变回「只存不执行」）==="
patch "$BOOT" \
'            com.cardesk.launcher.vehicle.DriveModeAutoStart.apply(context, "开机广播")' \
'            Unit'
check_wiring
restore

echo
echo "=== 反向8：claim 不看白名单（会去抢原车收音机 / USB / 蓝牙）==="
patch "$POLICY" \
'        acceptsControl(target) &&' \
'        true &&'
run_tests
restore

echo
echo "=== 反向9：车机自带音源在放时也 claim（会把用户正在听的电台切走）==="
patch "$POLICY" \
'        !nativeSourcePlaying' \
'        true'
run_tests
restore

echo
echo "=== 反向10：同一个目标反复 claim（跟用户手动切源来回打架）==="
patch "$POLICY" \
'        target != claimedTarget &&' \
'        true &&'
run_tests
restore

echo
echo "=== 反向11：注册成功后不主动 claim（回到「方控唤起爱趣听」）==="
patch "$BRIDGE" \
'                claimIfNeeded("注册成功")' \
'                Unit'
check_wiring
restore

echo
echo "=== 反向12：claim 只声明音源类型、不真的抢播放权==="
patch "$BRIDGE" \
'        val ok = runCatching { (invoke("requestPlay", token) as? Boolean) == true }.getOrDefault(false)' \
'        val ok = runCatching { (invoke("updateCurrentSourceType", token, ONLINE) as? Boolean) == true }.getOrDefault(false)'
check_wiring
restore

echo
echo "=== 反向13：车机自带音源的播放态不传给桥（永远看不到「电台在放」）==="
patch "$TRACKER" \
'        OfficialMediaBridge.setNativeSourcePlaying(nativeSourcePlaying())' \
'        Unit'
check_wiring
restore

echo
echo "=== 还原后自检（应当 0 红灯）==="
run_tests
check_wiring

echo
echo "备份目录：$BK"
