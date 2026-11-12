#!/usr/bin/env bash
# 第154轮反向验证：把「设置页无效项整改」改坏，确认对应断言**必红** ——
# 改坏 → 断言必红 → 还原 → 恢复全绿。
#
# 用法：bash tools/verify_y154_reverse.sh
#
# 背景（第154轮）：
#   审计（emu-verify/y154/SETTINGS-AUDIT.md）查出 12 个设置项
#   「把值写进偏好但没有任何代码读」——开关能切、能持久化，功能一点没变。
#
# 本轮定的口径：
#   - 删掉 9 项：360 环影 5 个视角选项 + 歌词页 4 个歌词槽字号滑杆
#   - 标注「暂不可用」3 项：桌面开机启动 / 自动导航投屏 / 胎压数字字号
#     （保留位置、点击只提示、**不再写偏好**）
#   - 顺带修好「恢复默认 15 槽位」：当场生效（原来要重启）
#
# 每一条都要能抓住 —— 改坏实现后，对应断言必须变红：
#   ① 「桌面开机启动」改回普通开关 → 又开始写那个没人读的偏好
#   ② 「自动导航投屏」改回普通开关 → 同上
#   ③ 「胎压数字字号」改回可拖动滑杆 → 拖得动却不生效
#   ④ 「暂不可用」行不弹提示 → 用户以为界面卡住了
#   ⑤ 「暂不可用」开关不置灰 → 跟普通开关长得一样，看不出不可用
#   ⑥ 把「倒车视角」加回 360 页 → 假开关复活
#   ⑦ 把「收起·主歌词」加回歌词页 → 假滑杆复活
#   ⑧ 「恢复默认 15 槽位」不通知宿主 → 又变成「要重启才生效」
#   ⑨ 360 总开关不重建本页 → 子项不出现（用户以为这一页坏了）
set -u
cd /Users/roy/Documents/desktopapp/cardesk

export JAVA_HOME=/Users/roy/Documents/desktopapp/toolchain/jdk-17.0.20.1+1/Contents/Home
export ANDROID_HOME=/Users/roy/Documents/desktopapp/toolchain/sdk
export ANDROID_SDK_ROOT=/Users/roy/Documents/desktopapp/toolchain/sdk
export GRADLE_USER_HOME=/Users/roy/Documents/desktopapp/.gradle-home
GRADLE=/Users/roy/Documents/desktopapp/toolchain/gradle-8.7/bin/gradle

SET=app/src/main/java/com/cardesk/launcher/ui/SettingsOverlay.kt
FILES="$SET"

BK=$(mktemp -d /tmp/y154-bk-XXXXXX)
echo "$BK" > /tmp/y154-bk-path.txt
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
    --tests "com.cardesk.launcher.ui.SettingsDeadItemsTest" \
    --console=plain > /tmp/y154-rev-run.log 2>&1
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
echo "=== 反向1：「桌面开机启动」改回普通开关（又开始写没人读的偏好）==="
patch "$SET" \
'        unavailableRow("桌面开机启动")' \
'        switchRow("桌面开机启动", prefs.bootAutostart) { prefs.bootAutostart = it }'
run_tests
restore

echo
echo "=== 反向2：「自动导航投屏」改回普通开关 ==="
patch "$SET" \
'        unavailableRow("自动导航投屏")' \
'        switchRow("自动导航投屏", prefs.startNaviCastEnabled) { prefs.startNaviCastEnabled = it }'
run_tests
restore

echo
echo "=== 反向3：「胎压数字字号」改回可拖动滑杆（拖得动却不生效）==="
patch "$SET" \
'        unavailableSliderRow("胎压数字字号")' \
'        sliderRow("胎压数字字号", prefs.deskTpmsValueSp, 10, 48, " px") { prefs.deskTpmsValueSp = it }'
run_tests
restore

echo
echo "=== 反向4：「暂不可用」行不弹提示（用户以为界面卡住了）==="
patch "$SET" \
'        val holder = row(mark, label)
        holder.setOnClickListener {
            Toast.makeText(context, "「$label」暂不可用，功能尚未接入", Toast.LENGTH_SHORT).show()
        }' \
'        val holder = row(mark, label)
        holder.setOnClickListener {
            Unit
        }'
run_tests
restore

echo
echo "=== 反向5：「暂不可用」的字不淡（跟正常文字一样显眼）==="
patch "$SET" \
'        val mark = Ui.text(context, "暂不可用", 20f, pal.textFaint).apply {' \
'        val mark = Ui.text(context, "暂不可用", 20f, pal.text).apply {'
run_tests
restore

echo
echo "=== 反向6：把「倒车视角」加回 360 页（假开关复活）==="
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
echo "=== 反向7：把「收起·主歌词」加回歌词页（假滑杆复活）==="
patch "$SET" \
'        section("首页中央歌词")' \
'        sliderRow("收起·主歌词", prefs.deskCompactMainSp, 14, 64, " px") { prefs.deskCompactMainSp = it }
        section("首页中央歌词")'
run_tests
restore

echo
echo "=== 反向8：「恢复默认 15 槽位」不通知宿主（又变成要重启才生效）==="
patch "$SET" \
'            onNavSlotsChanged?.invoke()
            onApply?.invoke()' \
'            onApply?.invoke()'
run_tests
restore

echo
echo "=== 反向9：360 总开关不重建本页（子项不出现，用户以为这一页坏了）==="
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
