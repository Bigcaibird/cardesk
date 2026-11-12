#!/usr/bin/env bash
# 第154~155轮反向验证：把设置页整改改坏，确认对应断言**必红** ——
# 改坏 → 断言必红 → 还原 → 恢复全绿。
#
# 用法：bash tools/verify_y155_reverse.sh
#
# 背景：
#   第154轮审计（emu-verify/y154/SETTINGS-AUDIT.md）查出 12 个设置项
#   「把值写进偏好但没有任何代码读」。
#
#   第154轮处置：删 9 项 + 标「暂不可用」3 项 + 修好「恢复默认 15 槽位」。
#   第155轮处置：把剩下的一次性清干净 ——
#     - 「自动导航投屏」删掉（没有实现可以接）
#     - 「桌面开机启动」实现掉（BootReceiver 按它决定要不要拉桌面）
#     - 「胎压数字字号」实现掉（ScenePage.drawTireCard 真读这个值）
#     - 顺带把「暂不可用」那套机制拆了（已经没有用户）
#
# 每一条都要能抓住 —— 改坏实现后，对应断言必须变红：
#   ① 「桌面开机启动」改回只写不执行 → 开机还是拉桌面
#   ② 「桌面开机启动」默认值改成 false → 老用户开机行为变了
#   ③ 「桌面开机启动」改回不可用行 → 又不是活的开关了
#   ④ 「胎压数字字号」不写偏好 → 拖了不生效
#   ⑤ 所有滑杆被禁用 → 拖不动
#   ⑥ 胎压渲染改回写死 28 → 字号根本没接到渲染上
#   ⑦ 把「自动导航投屏」加回设置页 → 删掉的项复活
#   ⑧ 设置页里加回「暂不可用」字样 → 占位没清干净
#   ⑨ 把「倒车视角」加回 360 页 → 假开关复活
#   ⑩ 把「收起·主歌词」加回歌词页 → 假滑杆复活
#   ⑪ 「恢复默认 15 槽位」不通知宿主 → 又变成「要重启才生效」
#   ⑫ 360 总开关不重建本页 → 子项不出现（用户以为这一页坏了）
set -u
cd /Users/roy/Documents/desktopapp/cardesk

export JAVA_HOME=/Users/roy/Documents/desktopapp/toolchain/jdk-17.0.20.1+1/Contents/Home
export ANDROID_HOME=/Users/roy/Documents/desktopapp/toolchain/sdk
export ANDROID_SDK_ROOT=/Users/roy/Documents/desktopapp/toolchain/sdk
export GRADLE_USER_HOME=/Users/roy/Documents/desktopapp/.gradle-home
GRADLE=/Users/roy/Documents/desktopapp/toolchain/gradle-8.7/bin/gradle

SET=app/src/main/java/com/cardesk/launcher/ui/SettingsOverlay.kt
BOOT=app/src/main/java/com/cardesk/launcher/BootReceiver.kt
SCENE=app/src/main/java/com/cardesk/launcher/ui/pages/ScenePage.kt
PREFS=app/src/main/java/com/cardesk/launcher/core/Prefs.kt
FILES="$SET $BOOT $SCENE $PREFS"

BK=$(mktemp -d /tmp/y155-bk-XXXXXX)
echo "$BK" > /tmp/y155-bk-path.txt
for f in $FILES; do
  mkdir -p "$BK/$(dirname "$f")"
  cp "$f" "$BK/$f"
done

restore() {
  for f in $FILES; do
    cp "$BK/$f" "$f"
  done
}

# 被打断（Ctrl-C / 超时）时也要把源码还原 ——
# 否则下一次跑这个脚本，基线就是在**改坏**的代码上跑的，红灯会变成假绿灯。
trap 'restore' EXIT INT TERM

run_tests() {
  python3 -c "import shutil,glob;[shutil.rmtree(p,ignore_errors=True) for p in glob.glob('app/build/test-results/testSystemUnitTest')]"
  "$GRADLE" :app:testSystemUnitTest \
    --tests "com.cardesk.launcher.ui.SettingsLiveItemsTest" \
    --tests "com.cardesk.launcher.ui.SettingsDeadItemsTest" \
    --tests "com.cardesk.launcher.ui.SceneTpmsValueSpTest" \
    --console=plain > /tmp/y155-rev-run.log 2>&1
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

patch() { # patch <file> <old> <new>
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

echo
echo "=== 反向1：「桌面开机启动」改回只写不执行（开机还是拉桌面）==="
patch "$BOOT" \
'        if (prefs.bootAutostart) {' \
'        if (true) {'
run_tests
restore

echo
echo "=== 反向2：「桌面开机启动」默认值改成 false（老用户开机行为变了）==="
patch "$PREFS" \
'        get() = flag("desktop_boot_autostart", def = true)' \
'        get() = flag("desktop_boot_autostart", def = false)'
run_tests
restore

echo
echo "=== 反向3：「桌面开机启动」改回不可用行（又不是活的开关了）==="
patch "$SET" \
'        switchRow("桌面开机启动", prefs.bootAutostart) {
            val from = prefs.bootAutostart
            prefs.bootAutostart = it
            logSetting("desktop_boot_autostart", "桌面开机启动", from, it)
        }' \
'        row(Ui.text(context, "暂不可用", 20f, pal.textFaint), "桌面开机启动")'
run_tests
restore

echo
echo "=== 反向4：「胎压数字字号」不写偏好（拖了不生效）==="
patch "$SET" \
'            val from = prefs.deskTpmsValueSp
            prefs.deskTpmsValueSp = it
            logSettingDebug("desk_tpms_value_sp", "胎压数字字号", from, it)' \
'            logSettingDebug("desk_tpms_value_sp", "胎压数字字号", prefs.deskTpmsValueSp, it)'
run_tests
restore

echo
echo "=== 反向5：所有滑杆被禁用（拖不动）==="
patch "$SET" \
'        val bar = SeekBar(context).apply {
            this.max = ((max - min) / step).coerceAtLeast(0)' \
'        val bar = SeekBar(context).apply {
            isEnabled = false
            this.max = ((max - min) / step).coerceAtLeast(0)'
run_tests
restore

echo
echo "=== 反向6：胎压渲染改回写死 28（字号没接到渲染上）==="
patch "$SCENE" \
'        val valueSp = tpmsValueSp.toFloat()' \
'        val valueSp = 28f'
run_tests
restore

echo
echo "=== 反向7：把「自动导航投屏」加回设置页（删掉的项复活）==="
patch "$SET" \
'        switchRow("自动切换驾驶模式", prefs.startSwitchEnabled) {' \
'        switchRow("自动导航投屏", prefs.startNaviCastEnabled) { prefs.startNaviCastEnabled = it }
        switchRow("自动切换驾驶模式", prefs.startSwitchEnabled) {'
run_tests
restore

echo
echo "=== 反向8：设置页里加回「暂不可用」字样（占位没清干净）==="
patch "$SET" \
'        switchRow("自动切换驾驶模式", prefs.startSwitchEnabled) {' \
'        body.addView(Ui.text(context, "暂不可用", 20f, pal.textFaint))
        switchRow("自动切换驾驶模式", prefs.startSwitchEnabled) {'
run_tests
restore

echo
echo "=== 反向9：把「倒车视角」加回 360 页（假开关复活）==="
patch "$SET" \
'        chipRow("按键视角", CAMERA360_VIEW_LABELS, CAMERA360_VIEW_IDS.indexOf(prefs.camera360KeyView)) { idx ->
            val from = prefs.camera360KeyView
            val to = CAMERA360_VIEW_IDS[idx]
            prefs.camera360KeyView = to
            logSetting("camera360_key_view_v2", "按键视角", from, to)
        }' \
'        chipRow("按键视角", CAMERA360_VIEW_LABELS, CAMERA360_VIEW_IDS.indexOf(prefs.camera360KeyView)) { idx ->
            val from = prefs.camera360KeyView
            val to = CAMERA360_VIEW_IDS[idx]
            prefs.camera360KeyView = to
            logSetting("camera360_key_view_v2", "按键视角", from, to)
        }
        chipRow("倒车视角", CAMERA360_VIEW_LABELS, CAMERA360_VIEW_IDS.indexOf(prefs.camera360ReverseView)) { idx ->
            prefs.camera360ReverseView = CAMERA360_VIEW_IDS[idx]
        }'
run_tests
restore

echo
echo "=== 反向10：把「收起·主歌词」加回歌词页（假滑杆复活）==="
patch "$SET" \
'        section("首页中央歌词")' \
'        sliderRow("收起·主歌词", prefs.deskCompactMainSp, 14, 64, " px") { prefs.deskCompactMainSp = it }
        section("首页中央歌词")'
run_tests
restore

echo
echo "=== 反向11：「恢复默认 15 槽位」不通知宿主（又变成要重启才生效）==="
patch "$SET" \
'            onNavSlotsChanged?.invoke()
            onApply?.invoke()' \
'            onApply?.invoke()'
run_tests
restore

echo
echo "=== 反向12：360 总开关不重建本页（子项不出现，用户以为这一页坏了）==="
patch "$SET" \
'            logSetting("camera360_enabled_v2", "启用 360 环影增强", from, it)
            select(selectedKey)' \
'            logSetting("camera360_enabled_v2", "启用 360 环影增强", from, it)'
run_tests
restore

echo
echo "=== 还原后自检（应当 0 红灯）==="
run_tests

echo
echo "备份目录：$BK"
