#!/usr/bin/env bash
# 第148轮反向验证：把「点封面图切换歌曲图片作背景」逐条改坏，
# 确认对应断言**必红** —— 改坏 → 断言必红 → 还原 → 恢复全绿。
#
# 用法：bash tools/verify_y148_reverse.sh
#
# 背景（第148轮，用户需求）：
#   「歌曲图片作为第二页音乐卡片背景做成可开关的形式吧，通过点击音乐卡片的歌曲图片来切换」
#
# 这条功能的成败只有四件事：
#   ① 封面图上**真的**挂上了点击监听（点了能切）；
#   ② 切换**真的**落盘（桌面是杀进程重开的，不落盘等于没做）；
#   ③ 状态能**冷启动记住**（Kotlin 属性初始化不走 setter，这里最容易翻车）；
#   ④ 关掉时**真的**回到纯色卡面，且封面图上有明确标记（否则用户以为功能坏了）。
set -u
cd /Users/roy/Documents/desktopapp/cardesk

export JAVA_HOME=/Users/roy/Documents/desktopapp/toolchain/jdk-17.0.20.1+1/Contents/Home
export ANDROID_HOME=/Users/roy/Documents/desktopapp/toolchain/sdk
export ANDROID_SDK_ROOT=/Users/roy/Documents/desktopapp/toolchain/sdk
export GRADLE_USER_HOME=/Users/roy/Documents/desktopapp/.gradle-home
GRADLE=/Users/roy/Documents/desktopapp/toolchain/gradle-8.7/bin/gradle

PREFS=app/src/main/java/com/cardesk/launcher/core/Prefs.kt
DRAWABLE=app/src/main/java/com/cardesk/launcher/ui/widget/CoverBackdropDrawable.kt
ART=app/src/main/java/com/cardesk/launcher/ui/widget/AlbumArtView.kt
NAV=app/src/main/java/com/cardesk/launcher/ui/pages/NavPage.kt
FILES="$PREFS $DRAWABLE $ART $NAV"

# 自建备份（可复跑）
BK=$(mktemp -d /tmp/y148-bk-XXXXXX)
echo "$BK" > /tmp/y148-bk-path.txt
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
    --tests "com.cardesk.launcher.NavCoverBackdropToggleTest" \
    --tests "com.cardesk.launcher.ui.widget.CoverBackdropDrawableTest" \
    --tests "com.cardesk.launcher.ui.widget.AlbumArtViewTest" \
    --console=plain > /tmp/y148-rev-run.log 2>&1
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
  python3 - "$NAV" "$DRAWABLE" "$ART" "$PREFS" <<'PYEOF'
import io, sys
nav, drawable, art, prefs = [io.open(p, encoding='utf-8').read() for p in sys.argv[1:5]]
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

# —— 入口 ——
checks.append((
    has_exact(nav, 'setOnClickListener { toggleCoverBackdrop() }'),
    "封面图上必须挂上点击监听（否则「点图片切换」根本触发不了）",
))
checks.append((
    has_exact(nav, 'prefs.navCoverBackdropEnabled = next'),
    "切换必须落盘（桌面是杀进程重开的，不落盘等于没做）",
))
checks.append((
    has_exact(nav, 'onCoverBackdropToggled?.invoke(next)'),
    "切换必须回调给宿主（否则用户点了不知道状态变了）",
))
# —— 冷启动 ——
checks.append((
    has_exact(nav, '        syncCoverBackdropState()'),
    "build() 里必须同步一次状态（属性初始化不走 setter，不补这下次启动背景会自己回来）",
))
checks.append((
    has_exact(nav, '        coverBackdrop.coverEnabled = coverBackdropEnabled'),
    "同步时必须把状态灌进背景层",
))
# —— 背景层 ——
checks.append((
    has_code(drawable, 'if (!coverEnabled) return'),
    "rebuild() 必须认 coverEnabled（关掉时不该再生成模糊图）",
))
checks.append((
    has_exact(nav, '    val coverBackdropOn: Boolean get() = coverBackdrop.coverEnabled'),
    "必须把背景层的真实状态暴露出来（否则冷启动漏灌状态这条测不出来）",
))
# —— 封面图：用户明确要求不加任何标记 ——
checks.append((
    no_text(art, 'backdropDisabled'),
    "封面图上不能有任何「背景已关」标记（用户明确要求：不要斜杠、不要暗罩）",
))
checks.append((
    no_text(art, 'drawDisabledMark'),
    "封面图上不能有画标记的逻辑",
))
checks.append((
    has_code(art, '        canvas.clipPath(path)'),
    "封面图必须按圆角裁剪（否则封面会变成方角）",
))
# —— 偏好 ——
checks.append((
    has_exact(prefs, 'sp.getBoolean("nav_cover_backdrop_enabled", true)'),
    "封面背景开关默认必须是开（保持第146轮上线的现状）",
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
echo "=== 反向1：封面图上不挂点击监听（点了没反应）==="
patch "$NAV" \
'            setOnClickListener { toggleCoverBackdrop() }' \
'            // setOnClickListener { toggleCoverBackdrop() }'
run_tests
check_wiring
restore

echo
echo "=== 反向2：切换不落盘（重启就丢）==="
patch "$NAV" \
'        prefs.navCoverBackdropEnabled = next' \
'        // prefs.navCoverBackdropEnabled = next'
run_tests
check_wiring
restore

echo
echo "=== 反向3：不回调给宿主（用户点了不知道状态变了）==="
patch "$NAV" \
'        onCoverBackdropToggled?.invoke(next)' \
'        // onCoverBackdropToggled?.invoke(next)'
run_tests
check_wiring
restore

echo
echo "=== 反向4：build() 里不同步状态（冷启动背景自己回来）==="
patch "$NAV" \
'        syncCoverBackdropState()
    }' \
'        // syncCoverBackdropState()
    }'
run_tests
check_wiring
restore

echo
echo "=== 反向5：同步时不给背景层灌状态（关了背景照样显示）==="
patch "$NAV" \
'        coverBackdrop.coverEnabled = coverBackdropEnabled' \
'        // coverBackdrop.coverEnabled = coverBackdropEnabled'
run_tests
check_wiring
restore

echo
echo "=== 反向6：rebuild 不认开关（关了也照样生成模糊图）==="
patch "$DRAWABLE" \
'        if (!coverEnabled) return' \
'        if (!coverEnabled && false) return'
run_tests
check_wiring
restore

echo
echo "=== 反向7：开关恒为关（背景永远开不起来）==="
patch "$DRAWABLE" \
'    var coverEnabled: Boolean = true' \
'    var coverEnabled: Boolean = false'
run_tests
check_wiring
restore

echo
echo "=== 反向8：rebuild 里判反（开着的时候反而不生成模糊图）==="
patch "$DRAWABLE" \
'        if (!coverEnabled) return' \
'        if (coverEnabled) return'
run_tests
check_wiring
restore

echo
echo "=== 反向9：封面不按圆角裁剪（方角封面暴露在卡片上）==="
patch "$ART" \
'        canvas.save()
        canvas.clipPath(path)
        val bmp = bitmap' \
'        canvas.save()
        // canvas.clipPath(path)
        val bmp = bitmap'
run_tests
check_wiring
restore

echo
echo "=== 反向10：模糊图短边写死（比例和卡片对不上）==="
patch "$DRAWABLE" \
'        val scale = CoverBackdrop.BLUR_SHORT_SIDE.toFloat() / minOf(cardW, cardH)' \
'        val scale = 32f / minOf(cardW, cardH)'
run_tests
check_wiring
restore

echo
echo "=== 反向11：把「暗罩 + 斜杠」标记加回封面图（用户明确不要）==="
# 这条是**反向验证用户要求本身**：
# 有人日后「好心」给封面图加回禁用标记，这条必须立刻红。
patch "$ART" \
'        canvas.restore()
    }
}' \
'        canvas.drawRect(r, android.graphics.Paint().apply { color = 0x8C000000.toInt() })
        canvas.restore()
    }
}'
run_tests
check_wiring
restore

echo
echo "=== 反向12：默认改成关（升级后用户的现状被改掉）==="
patch "$PREFS" \
'        get() = sp.getBoolean("nav_cover_backdrop_enabled", true)' \
'        get() = sp.getBoolean("nav_cover_backdrop_enabled", false)'
run_tests
check_wiring
restore

echo
echo "=== 还原后自检（应当 0 红灯）==="
run_tests
check_wiring

echo
echo "备份目录：$BK"
