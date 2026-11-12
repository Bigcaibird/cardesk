#!/usr/bin/env bash
# 第150轮反向验证：把「设置页文案精简」改坏，确认对应断言**必红** ——
# 改坏 → 断言必红 → 还原 → 恢复全绿。
#
# 用法：bash tools/verify_y150_reverse.sh
#
# 背景（第150轮，用户需求）：
#   「把设置面板里所有啰嗦的功能描述，需求解释的文字去除」
#
# 这条需求的成败有两面：
#   ① **该删的删干净了** —— 啰嗦文案不能再出现在设置页里；
#   ② **检查本身是有效的** —— 靠标记从视图树里挑说明文字，
#      万一标记被摘掉，检查会「什么都没查到」然后**假通过**。
#      所以第 3 条反向项专门盯这个。
set -u
cd /Users/roy/Documents/desktopapp/cardesk

export JAVA_HOME=/Users/roy/Documents/desktopapp/toolchain/jdk-17.0.20.1+1/Contents/Home
export ANDROID_HOME=/Users/roy/Documents/desktopapp/toolchain/sdk
export ANDROID_SDK_ROOT=/Users/roy/Documents/desktopapp/toolchain/sdk
export GRADLE_USER_HOME=/Users/roy/Documents/desktopapp/.gradle-home
GRADLE=/Users/roy/Documents/desktopapp/toolchain/gradle-8.7/bin/gradle

SETTINGS=app/src/main/java/com/cardesk/launcher/ui/SettingsOverlay.kt
FILES="$SETTINGS"

BK=$(mktemp -d /tmp/y150-bk-XXXXXX)
echo "$BK" > /tmp/y150-bk-path.txt
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
    --tests "com.cardesk.launcher.ui.SettingsCopyTest" \
    --console=plain > /tmp/y150-rev-run.log 2>&1
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

echo
echo "=== 反向1：给开关加回一句功能描述 ==="
patch "$SETTINGS" \
'switchRow("桌面开机启动", prefs.bootAutostart)' \
'switchRow("桌面开机启动", prefs.bootAutostart, "开机后进入本桌面")'
run_tests
restore

echo
echo "=== 反向2：加回一条被删掉的功能解释 note ==="
patch "$SETTINGS" \
'        switchRow("桌面开机启动", prefs.bootAutostart) {' \
'        note("开机后进入本桌面，功能立即生效。")
        switchRow("桌面开机启动", prefs.bootAutostart) {'
run_tests
restore

echo
echo "=== 反向3：把说明行的标记摘掉（检查会「什么都没查到」然后假通过）==="
patch "$SETTINGS" \
'            tag = NOTE_MARK' \
'            tag = null'
run_tests
restore

echo
echo "=== 反向4：把开关副标题的标记摘掉 ==="
patch "$SETTINGS" \
'.apply { tag = SUBTITLE_MARK }' \
'.apply { tag = null }'
run_tests
restore

echo
echo "=== 反向5：给一个原本没描述的开关加回描述（数量对不上）==="
# 注意：不能写成「在 switchRow(...) 后面接一行」—— 那样会把原来的 { } 块孤立掉，
# 编译直接失败，单测一条都跑不了（TOTAL=0），这条反向项就只剩「编译没过」在把关。
# 正确做法是**改实参**，语法保持合法。
patch "$SETTINGS" \
'switchRow("沉浸系统导航栏", prefs.oemNavImmersive)' \
'switchRow("沉浸系统导航栏", prefs.oemNavImmersive, "与下移 Dock 互斥")'
run_tests
restore

echo
echo "=== 还原后自检（应当 0 红灯）==="
run_tests

echo
echo "备份目录：$BK"
