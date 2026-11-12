#!/usr/bin/env bash
# 第153轮反向验证：把「日间模式封面背景」改坏，确认对应断言**必红** ——
# 改坏 → 断言必红 → 还原 → 恢复全绿。
#
# 用法：bash tools/verify_y153_reverse.sh
#
# 背景（第153轮，用户反馈）：
#   「我在日间模式下点击第二页音乐卡片的图片，有提示开关图片背景，但实际无变化」
#
# 本轮最终方案（第153轮第二轮，用户问卷选定「C 折中」）：
#   日间 = 白底深字 + **两档**文字 + 淡白罩（区间 [0.4340, 1.0]）
#   夜间 = 深底浅字 + 三档文字 + 压暗封面（区间 [0, 0.0341]）→ 一行不改
#
# 这条需求有**七个互相独立**的失效路径，每一个都得能抓住：
#   ① 日间卡面又变回深色（不跟日间其它卡统一）；
#   ② 日间不砍档（最弱那档用 textFaint）→ 下限回到 0.9743 → 封面又没了；
#   ③ 日间强调色用主题那个浅青 → 白罩底上只有 1.57:1，歌词读不出来；
#   ④ 夜间被这次改动带偏（夜间本来就该是深底浅字）；
#   ⑤ 关掉开关时模糊图没被丢掉（开关等于没关）；
#   ⑥ 不再沿用用户自选的卡片色（一律换成标准底）；
#   ⑦ 日间「太暗的自选色要换白底」这条保护被去掉 → 深色字压不住。
#
# ## 第一版踩过的坑（写下来免得再踩）
#
# 第一版的反向 2 / 反向 3 **没红**，查出来是**测试本身**的问题，不是实现的问题：
#
#   - 组合卡是**构建时**建出来的（`NavPage.buildCombinedCard()`），
#     运行时切主题**不会**重建那些文字 ——
#     所以「先起桌面、再切主题」的写法，测到的其实是**夜间下建好的卡**，
#     主题跟着走不走的，在测试里根本看不出来。
#   - 于是改成「**先写 pref、再起桌面**」：桌面冷启动就落在目标主题，
#     组合卡也在那个主题下被构建出来。
#   - 另外 `Skin` 是**单例对象**，Robolectric 同 classloader 下静态状态**跨用例泄漏**，
#     所以每条用例跑完要 @After 还原。
set -u
cd /Users/roy/Documents/desktopapp/cardesk

export JAVA_HOME=/Users/roy/Documents/desktopapp/toolchain/jdk-17.0.20.1+1/Contents/Home
export ANDROID_HOME=/Users/roy/Documents/desktopapp/toolchain/sdk
export ANDROID_SDK_ROOT=/Users/roy/Documents/desktopapp/toolchain/sdk
export GRADLE_USER_HOME=/Users/roy/Documents/desktopapp/.gradle-home
GRADLE=/Users/roy/Documents/desktopapp/toolchain/gradle-8.7/bin/gradle

NAV=app/src/main/java/com/cardesk/launcher/ui/pages/NavPage.kt
DRAWABLE=app/src/main/java/com/cardesk/launcher/ui/widget/CoverBackdropDrawable.kt
FILES="$NAV $DRAWABLE"

BK=$(mktemp -d /tmp/y153-bk-XXXXXX)
echo "$BK" > /tmp/y153-bk-path.txt
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
    --tests "com.cardesk.launcher.NavCoverBackdropDayModeTest" \
    --console=plain > /tmp/y153-rev-run.log 2>&1
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
echo "=== 反向1：日间卡面又变回深色（不跟日间其它卡统一）==="
patch "$NAV" \
'    private val cardPal: Palette = if (pal.night) Palettes.night else Palettes.day' \
'    private val cardPal: Palette = Palettes.night'
run_tests
restore

echo
echo "=== 反向2：日间不砍档（最弱那档用 textFaint）→ 下限回到 0.9743 ==="
patch "$NAV" \
'        get() = if (pal.night) cardPal.textFaint else cardPal.textDim' \
'        get() = cardPal.textFaint'
run_tests
restore

echo
echo "=== 反向3：日间强调色用主题那个浅青（白罩底上只有 1.57:1）==="
patch "$NAV" \
'        get() = if (pal.night) CARD_ACCENT_NIGHT else CARD_ACCENT_DAY' \
'        get() = cardPal.accent'
run_tests
restore

echo
echo "=== 反向4：夜间被带偏（组合卡配色不再按日夜分两套）==="
patch "$NAV" \
'    private val cardPal: Palette = if (pal.night) Palettes.night else Palettes.day' \
'    private val cardPal: Palette = Palettes.day'
run_tests
restore

echo
echo "=== 反向5：关掉开关时模糊图没被丢掉（开关等于没关）==="
patch "$DRAWABLE" \
'            if (field == value) return
            field = value
            rebuild()
            invalidateSelf()' \
'            if (field == value) return
            field = value
            invalidateSelf()'
run_tests
restore

echo
echo "=== 反向6：不再沿用用户自选的卡片色（一律换成标准底）==="
patch "$NAV" \
'        return (alpha shl 24) or (if (ok) rgb else cardPal.surface and 0x00FFFFFF)' \
'        return (alpha shl 24) or (cardPal.surface and 0x00FFFFFF)'
run_tests
restore

echo
echo "=== 反向7：日间「太暗的自选色要换白底」这条保护被去掉 ==="
patch "$NAV" \
'            l >= com.cardesk.launcher.core.media.CoverBackdrop
                .minBackgroundLuminance(cardWeakText)' \
'            l >= 0.0'
run_tests
restore

echo
echo "=== 还原后自检（应当 0 红灯）==="
run_tests

echo
echo "备份目录：$BK"
