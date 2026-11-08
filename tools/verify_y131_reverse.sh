#!/usr/bin/env bash
# 第131轮反向验证：把实现逐条改回「旧行为」，确认对应断言**必红**。
#
# 用法：bash tools/verify_y131_reverse.sh
#
# 背景（第131轮）：
#   用户诉求：「没打开 QQ 音乐或任何第三方播放器时按方向盘播放键，
#   打开的是爱趣听 —— 能不能让方控控制桌面默认选定的播放器？」
#
#   修复：没有会话时，播放目标按「卡片来源行 → 设置页默认源 → 记住的播放器
#   → 默认音乐应用列表」解析，并且**车机自带音源不由我们接管**。
#   下面每一条反向验证都对应一组断言：改坏 → 断言必红 → 还原 → 恢复全绿。
set -u
cd /Users/roy/Documents/desktopapp/cardesk

export JAVA_HOME=/Users/roy/Documents/desktopapp/toolchain/jdk-17.0.20.1+1/Contents/Home
export GRADLE_USER_HOME=/Users/roy/Documents/desktopapp/.gradle-home
GRADLE=/Users/roy/Documents/desktopapp/toolchain/gradle-8.7/bin/gradle

TARGET=app/src/main/java/com/cardesk/launcher/core/media/PlayTarget.kt
FILES="$TARGET app/src/test/java/com/cardesk/launcher/core/media/PlayTargetTest.kt"

# 自建备份（可复跑；不用相对路径 rm -rf）
BK=$(mktemp -d /tmp/y131-bk-XXXXXX)
echo "$BK" > /tmp/y131-bk-path.txt
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
    --tests "com.cardesk.launcher.core.media.PlayTargetTest" \
    --console=plain > /tmp/y131-rev-run.log 2>&1
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

echo
echo "=== 反向1：让「记住的上次播放器」优先于「桌面选定的来源」（旧行为）==="
patch "$TARGET" \
'        // 1) 卡片来源行 → 2) 设置页默认源：都是用户的**显式选择**，优先
        for (candidate in listOf(currentSourcePkg, defaultSourcePkg)) {' \
'        // 1) 记住的上次播放器优先（**旧行为**：历史压过用户当下的选择）
        for (candidate in listOf(rememberedPkg)) {'
run_tests
restore

echo
echo "=== 反向2：不跳过车机自带音源（爱趣听又会被选中）==="
patch "$TARGET" \
'        if (remembered.isNotEmpty() && isInstalled(remembered) && !isCarAudioPkg(remembered)) {' \
'        if (remembered.isNotEmpty() && isInstalled(remembered)) {'
run_tests
restore

echo
echo "=== 反向3：目标解析不检查安装状态（会拉起没装的 App）==="
patch "$TARGET" \
'            if (isInstalled(pkg)) return pkg' \
'            return pkg'
run_tests
restore

echo
echo "=== 反向4：车机自带音源也算我们接管（会去抢原车音源）==="
patch "$TARGET" \
'        return !isCarAudioPkg(t)' \
'        return true'
run_tests
restore

echo
echo "=== 反向5：车机自带音源判定失效（认不出爱趣听）==="
patch "$TARGET" \
'        return p.contains("wecarflow") || p.contains("aiquting") || p.contains("qqmusictv")' \
'        return false'
run_tests
restore

echo
echo "=== 反向6：去掉「设置页默认源」这一级（通用音乐时没有目标）==="
patch "$TARGET" \
'        for (candidate in listOf(currentSourcePkg, defaultSourcePkg)) {' \
'        for (candidate in listOf(currentSourcePkg)) {'
run_tests
restore

echo
echo "=== 反向7：去掉「默认音乐应用列表」兜底 ==="
patch "$TARGET" \
'        return defaultPackages.firstOrNull { isInstalled(it) }.orEmpty()' \
'        return ""'
run_tests
restore

echo
echo "=== 反向8：没有目标时也走「桌面选定媒体源」那条路 ==="
patch "$TARGET" \
'        if (shouldTakeOver(target)) NoSessionAction.SELECTED_SOURCE else NoSessionAction.FALLBACK' \
'        NoSessionAction.SELECTED_SOURCE'
run_tests
restore

echo
echo "=== 反向9：QQ 音乐车机版认不出来（不走三方协议）==="
patch "$TARGET" \
'    fun isQqTarget(target: String?): Boolean = target?.trim() == QqBroadcast.PACKAGE' \
'    fun isQqTarget(target: String?): Boolean = false'
run_tests
restore

echo
echo "=== 还原后自检（应当 0 红灯）==="
run_tests

echo
echo "备份目录：$BK"
