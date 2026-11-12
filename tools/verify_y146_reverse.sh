#!/usr/bin/env bash
# 第146轮反向验证：把「专辑封面做第二页音乐卡背景」逐条改坏，
# 确认对应断言**必红** —— 改坏 → 断言必红 → 还原 → 恢复全绿。
#
# 用法：bash tools/verify_y146_reverse.sh
#
# 背景（第146轮，用户需求）：
#   「能实现把歌曲图片作为第二页音乐卡片的背景吗」
#
#   这条功能的成败只有两件事：
#   ① 封面真的铺在卡上了（不是把卡面换个颜色糊弄过去）；
#   ② 文字压在封面上还读得清（亮度上限由对比度反推）。
#
#   每一条反向验证都对应一组断言。
set -u
cd /Users/roy/Documents/desktopapp/cardesk

export JAVA_HOME=/Users/roy/Documents/desktopapp/toolchain/jdk-17.0.20.1+1/Contents/Home
export ANDROID_HOME=/Users/roy/Documents/desktopapp/toolchain/sdk
export ANDROID_SDK_ROOT=/Users/roy/Documents/desktopapp/toolchain/sdk
export GRADLE_USER_HOME=/Users/roy/Documents/desktopapp/.gradle-home
GRADLE=/Users/roy/Documents/desktopapp/toolchain/gradle-8.7/bin/gradle

BACKDROP=app/src/main/java/com/cardesk/launcher/core/media/CoverBackdrop.kt
DRAWABLE=app/src/main/java/com/cardesk/launcher/ui/widget/CoverBackdropDrawable.kt
NAV=app/src/main/java/com/cardesk/launcher/ui/pages/NavPage.kt
FILES="$BACKDROP $DRAWABLE $NAV"

# 自建备份（可复跑）
BK=$(mktemp -d /tmp/y146-bk-XXXXXX)
echo "$BK" > /tmp/y146-bk-path.txt
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
    --tests "com.cardesk.launcher.core.media.CoverBackdropTest" \
    --tests "com.cardesk.launcher.ui.widget.CoverBackdropDrawableTest" \
    --console=plain > /tmp/y146-rev-run.log 2>&1
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

# 接线检查：Android 侧的接线 JVM 单测覆盖不到，用源码断言兜住。
check_wiring() {
  python3 - "$NAV" "$DRAWABLE" <<'PYEOF'
import io, sys
nav, drawable = [io.open(p, encoding='utf-8').read() for p in sys.argv[1:3]]
checks = []

def has_exact(src, text):
    return text in src

def strip_comments(src):
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

# —— 接线 ——
checks.append((
    has_exact(nav, 'combinedCard.background = coverBackdrop'),
    "组合卡的背景必须是 CoverBackdropDrawable（不能还挂 Skin.flat 的纯色面）",
))
checks.append((
    has_exact(nav, '            coverBackdrop.bitmap = v'),
    "NavPage.artwork 的 setter 必须把封面喂给背景层",
))
checks.append((
    has_exact(nav, '        coverBackdrop.setStyle('),
    "applyCardStyle 必须刷新背景层（换肤 / 换卡片风格时遮罩强度要跟着重算）",
))
checks.append((
    no_text(nav, 'combinedCard.background = Skin.flat('),
    "组合卡不能同时还挂着一层纯色面盖住封面",
))
# —— 背景层自身 ——
checks.append((
    has_code(drawable, 'canvas.clipPath(clipPath)'),
    "封面必须按圆角裁剪（否则会盖到卡片外面的直角）",
))
checks.append((
    has_code(drawable, 'CoverBackdrop.toneMap('),
    "封面必须做亮度映射（不能原样上屏）",
))
checks.append((
    has_code(drawable, 'CoverBackdrop.targetRange('),
    "亮度区间必须由对比度反推（不能写死）",
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
echo "=== 反向1：不做亮度映射（原始封面直接上屏）==="
patch "$DRAWABLE" \
'        small.setPixels(mapped, 0, small.width, 0, 0, small.width, small.height)' \
'        // small.setPixels(mapped, 0, small.width, 0, 0, small.width, small.height)'
run_tests
restore

echo
echo "=== 反向2：亮度上限算错（当成 1.0 = 不压暗）==="
patch "$DRAWABLE" \
'        toneRange = CoverBackdrop.targetRange(surfaceColor and 0xFFFFFF, textColor)' \
'        toneRange = doubleArrayOf(0.0, 1.0)'
run_tests
restore

echo
echo "=== 反向3：对比度目标放宽到 1.5:1（字会糊）==="
patch "$BACKDROP" \
'    const val MIN_CONTRAST = 3.0' \
'    const val MIN_CONTRAST = 1.5'
run_tests
restore

echo
echo "=== 反向4：封面根本不铺（只画遮罩 = 换了个颜色的卡面）==="
patch "$DRAWABLE" \
'        canvas.drawBitmap(small, null, r, bitmapPaint)' \
'        // canvas.drawBitmap(small, null, r, bitmapPaint)'
run_tests
restore

echo
echo "=== 反向5：圆角裁剪去掉（封面盖到卡片直角上）==="
patch "$DRAWABLE" \
'        canvas.clipPath(clipPath)' \
'        // canvas.clipPath(clipPath)'
run_tests
restore

echo
echo "=== 反向6：没有封面时不画卡面（白板）==="
patch "$DRAWABLE" \
'        facePaint.color = surfaceColor
        canvas.drawRoundRect(r, cornerRadius, cornerRadius, facePaint)' \
'        facePaint.color = surfaceColor
        if (blurred != null) canvas.drawRoundRect(r, cornerRadius, cornerRadius, facePaint)'
run_tests
restore

echo
echo "=== 反向7：换歌时背景不跟着换（背景层拿不到新封面）==="
patch "$NAV" \
'            coverBackdrop.bitmap = v' \
'            // coverBackdrop.bitmap = v'
run_tests
check_wiring
restore

echo
echo "=== 反向8：组合卡又挂回纯色面（把封面盖住）==="
patch "$NAV" \
'        combinedCard.background = coverBackdrop' \
'        combinedCard.background = Skin.flat(com.cardesk.launcher.core.Shape.CARD)'
run_tests
check_wiring
restore

echo
echo "=== 反向9：applyCardStyle 不刷新背景层（换肤后遮罩强度是旧的）==="
patch "$NAV" \
'        coverBackdrop.setStyle(' \
'        if (false) coverBackdrop.setStyle('
check_wiring
restore

echo
echo "=== 反向10：gamma 改成 1.0（暗部不抬起来，结构糊掉）==="
patch "$BACKDROP" \
'    const val TONE_GAMMA = 0.45' \
'    const val TONE_GAMMA = 1.0'
run_tests
restore

echo
echo "=== 还原后自检（应当 0 红灯）==="
run_tests
check_wiring

echo
echo "备份目录：$BK"
