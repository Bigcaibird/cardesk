#!/usr/bin/env bash
# 第151轮反向验证：把「媒体源改下拉 + 按钮不压下一项」改坏，确认对应断言**必红** ——
# 改坏 → 断言必红 → 还原 → 恢复全绿。
#
# 用法：bash tools/verify_y151_reverse.sh
#
# 背景（第151轮，用户需求）：
#   1. 媒体来源改成下拉选择；
#   2. 设置里 dock 栏快捷应用添加的按钮压到了下一个选项，修复这个问题。
#
# 两条需求各有两条独立的失效路径：
#   ① **下拉退回了 chip 行** —— 断言必须能抓住「又变回一行 chip」；
#   ② **按钮底部间距没了** —— 断言必须能抓住「底边又贴住下一行」。
# 所以反向项不是只改一处，而是每个需求各改两处。
set -u
cd /Users/roy/Documents/desktopapp/cardesk

export JAVA_HOME=/Users/roy/Documents/desktopapp/toolchain/jdk-17.0.20.1+1/Contents/Home
export ANDROID_HOME=/Users/roy/Documents/desktopapp/toolchain/sdk
export ANDROID_SDK_ROOT=/Users/roy/Documents/desktopapp/toolchain/sdk
export GRADLE_USER_HOME=/Users/roy/Documents/desktopapp/.gradle-home
GRADLE=/Users/roy/Documents/desktopapp/toolchain/gradle-8.7/bin/gradle

SETTINGS=app/src/main/java/com/cardesk/launcher/ui/SettingsOverlay.kt
FILES="$SETTINGS"

BK=$(mktemp -d /tmp/y151-bk-XXXXXX)
echo "$BK" > /tmp/y151-bk-path.txt
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
    --tests "com.cardesk.launcher.ui.SettingsDropdownTest" \
    --console=plain > /tmp/y151-rev-run.log 2>&1
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
echo "=== 反向1：把「默认来源」退回 chip 行（整段换回旧写法）==="
# 这一项必须改**整段**：chipRow 与 dropdownRow 的签名不同
# （一个是选中项文案 String，一个是下标 Int；回调一个是 (String) -> Unit，一个是 (Int) -> Unit），
# 只改函数名会编译失败 → 单测一条都跑不了（TOTAL=0），那条反向项就只剩「编译没过」在把关。
python3 - <<'PYEOF' || { echo "!!! patch 失败（锚点不唯一或不存在）"; exit 1; }
import io
p = 'app/src/main/java/com/cardesk/launcher/ui/SettingsOverlay.kt'
s = io.open(p, encoding='utf-8').read()
old = """        dropdownRow(
            "默认来源",
            sources.map { it.label },
            // 兜底：prefs 里存了不认识的 id 时退回「通用音乐」，
            // 与旧 chip 行的行为一致（找不到就显示 GENERIC_LABEL）。
            sources.indexOfFirst { it.id == prefs.defaultMusicSource }
                .let { if (it >= 0) it else sources.indexOfFirst { s -> s.id == com.cardesk.launcher.core.media.MediaSources.GENERIC_ID } },
        ) { index ->
            val src = sources[index]
            prefs.defaultMusicSource = src.id
            genericNote?.visibility =
                if (src.id == com.cardesk.launcher.core.media.MediaSources.GENERIC_ID) View.VISIBLE else View.GONE
            onDefaultMediaSource?.invoke(src.id)
        }"""
new = """        chipRow(
            "默认来源",
            sources.map { it.label },
            sources.indexOfFirst { it.id == prefs.defaultMusicSource }
                .let { if (it >= 0) it else sources.indexOfFirst { s -> s.id == com.cardesk.launcher.core.media.MediaSources.GENERIC_ID } },
        ) { index ->
            val src = sources[index]
            prefs.defaultMusicSource = src.id
            onDefaultMediaSource?.invoke(src.id)
        }"""
assert s.count(old) == 1, "patch 匹配 %d 次" % s.count(old)
s = s.replace(old, new)
io.open(p, 'w', encoding='utf-8').write(s)
PYEOF
run_tests
restore

echo
echo "=== 反向2：下拉少列一个来源（只列前 8 个）==="
patch "$SETTINGS" \
'            sources.map { it.label },
            // 兜底：prefs 里存了不认识的 id 时退回「通用音乐」，' \
'            sources.map { it.label }.dropLast(1),
            // 兜底：prefs 里存了不认识的 id 时退回「通用音乐」，'
run_tests
restore

echo
echo "=== 反向3：按钮底部的间距删掉（又贴住下一行）==="
patch "$SETTINGS" \
'            it.bottomMargin = 24' \
'            it.bottomMargin = 0'
run_tests
restore

echo
echo "=== 反向4：点选项不落盘（选了也不生效）==="
patch "$SETTINGS" \
'            prefs.defaultMusicSource = src.id' \
'            // 反向：故意不落盘'
run_tests
restore

echo
echo "=== 反向5：选了具体 App 之后那条提示不收起 ==="
patch "$SETTINGS" \
'            genericNote?.visibility =
                if (src.id == com.cardesk.launcher.core.media.MediaSources.GENERIC_ID) View.VISIBLE else View.GONE' \
'            // 反向：提示一直显示'
run_tests
restore

echo
echo "=== 还原后自检（应当 0 红灯）==="
run_tests

echo
echo "备份目录：$BK"
