#!/usr/bin/env bash
# 第152轮反向验证：把「抽屉 UI 重做」改坏，确认对应断言**必红** ——
# 改坏 → 断言必红 → 还原 → 恢复全绿。
#
# 用法：bash tools/verify_y152_reverse.sh
#
# 背景（第152轮，用户需求）：
#   「优化一下应用抽屉的UI，现在太混乱了，三个点离图标太远，
#     把三个点的菜单改成长按图标弹出，去掉长按排序的功能，以及现在的三个点」
#
# 这条需求有**四个互相独立**的失效路径，每一个都得能抓住：
#   ① **⋮ 又长回来了**（而且离图标还是那么远）；
#   ② **长按又被拖动排序抢走**（菜单弹不出来）；
#   ③ **长按偷偷改了排序偏好**；
#   ④ **布局退回贴左沿 / 名称又被截断**。
set -u
cd /Users/roy/Documents/desktopapp/cardesk

export JAVA_HOME=/Users/roy/Documents/desktopapp/toolchain/jdk-17.0.20.1+1/Contents/Home
export ANDROID_HOME=/Users/roy/Documents/desktopapp/toolchain/sdk
export ANDROID_SDK_ROOT=/Users/roy/Documents/desktopapp/toolchain/sdk
export GRADLE_USER_HOME=/Users/roy/Documents/desktopapp/.gradle-home
GRADLE=/Users/roy/Documents/desktopapp/toolchain/gradle-8.7/bin/gradle

DRAWER=app/src/main/java/com/cardesk/launcher/ui/DrawerPage.kt
LAUNCHER=app/src/main/java/com/cardesk/launcher/ui/LauncherView.kt
FILES="$DRAWER $LAUNCHER"

BK=$(mktemp -d /tmp/y152-bk-XXXXXX)
echo "$BK" > /tmp/y152-bk-path.txt
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
    --tests "com.cardesk.launcher.ui.DrawerUiTest" \
    --tests "com.cardesk.launcher.ui.DrawerOverlayTest" \
    --console=plain > /tmp/y152-rev-run.log 2>&1
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
echo "=== 反向1：把「⋮」按钮加回来 ==="
python3 - <<'PYEOF' || { echo "!!! patch 失败（锚点不唯一或不存在）"; exit 1; }
import io
p = 'app/src/main/java/com/cardesk/launcher/ui/DrawerPage.kt'
s = io.open(p, encoding='utf-8').read()
anchor = '            cell.addView(body, FrameLayout.LayoutParams(-1, -1))\n'
assert s.count(anchor) == 1, 'anchor count=%d' % s.count(anchor)
more = '''            // 反向：把「⋮」按钮加回来（贴在格子右沿，离图标 242px）。
            val more = Ui.text(context, "\\u22EE", 24f, pal.textDim, bold = true).apply {
                gravity = Gravity.CENTER
                background = Ui.round(Skin.CHIP_FILL, Shape.PILL, 1f, Skin.CHIP_STROKE)
                isClickable = true
                pressFeedback()
            }
            cell.addView(
                more,
                FrameLayout.LayoutParams(52, 52, Gravity.TOP or Gravity.END).apply {
                    topMargin = 6
                    rightMargin = 6
                },
            )
'''
s = s.replace(anchor, anchor + more)
io.open(p, 'w', encoding='utf-8').write(s)
PYEOF
run_tests
restore

echo
echo "=== 反向2：长按又被拖动排序抢走（菜单弹不出来）==="
patch "$DRAWER" \
'                setOnLongClickListener {
                    showItemMenu(info, display)
                    true
                }' \
'                setOnLongClickListener {
                    val clip = ClipData.newPlainText("package", info.activityInfo.packageName)
                    startDragAndDrop(clip, View.DragShadowBuilder(this), null, 0)
                    true
                }'
# ClipData 的 import 也要加回来，否则编译失败（TOTAL=0，反向项就只剩「编译没过」在把关）
patch "$DRAWER" \
'import android.content.Context' \
'import android.content.ClipData
import android.content.Context'
run_tests
restore

echo
echo "=== 反向3：长按偷偷改写排序偏好 ==="
patch "$DRAWER" \
'                setOnLongClickListener {
                    showItemMenu(info, display)
                    true
                }' \
'                setOnLongClickListener {
                    prefs.appOrder = listOf("com.example.evil")
                    showItemMenu(info, display)
                    true
                }'
run_tests
restore

echo
echo "=== 反向4：名称列宽改回 76（又截成一串省略号）==="
patch "$DRAWER" \
'        const val LABEL_W = 160' \
'        const val LABEL_W = 76'
run_tests
restore

echo
echo "=== 反向5：图标列退回贴左沿 ==="
patch "$DRAWER" \
'                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL' \
'                gravity = Gravity.TOP or Gravity.START'
run_tests
restore

echo
echo "=== 反向6：底部提示改回旧文案（又提拖动排序和 ⋮）==="
patch "$DRAWER" \
'        val foot = Ui.text(context, "点按图标启动，长按图标打开菜单", 16f, Skin.TEXT_DIM).apply {' \
'        val foot = Ui.text(context, "点按图标启动，长按拖动排序；格子右上角 ⋮ 可重命名 / 应用信息 / 禁用·启用 / 卸载", 16f, Skin.TEXT_DIM).apply {'
run_tests
restore

echo
echo "=== 反向7：关抽屉时不清理浮层（菜单会残留到下一次打开）==="
patch "$LAUNCHER" \
'        drawerPage.clearOverlays()
        // 面板下滑 56px + 淡出（240ms），结束后摘掉。
        drawerPage.playClose {' \
'        // 面板下滑 56px + 淡出（240ms），结束后摘掉。
        drawerPage.playClose {'
run_tests
restore

echo
echo "=== 反向8：返回键不先关菜单（菜单打开时按返回会连抽屉一起关）==="
patch "$LAUNCHER" \
'            if (drawerPage.closeTopOverlay()) return true
            closeDrawer()' \
'            closeDrawer()'
run_tests
restore

echo
echo "=== 反向9：两处清理全拆掉（closeDrawer + showDrawer 双保险都没了）==="
# 只拆 closeDrawer 那处时，showDrawer 的兜底还顶着，
# 「重开抽屉不能带着上次的菜单」那条断言就不会红 ——
# 所以这里两处一起拆，才看得见那条断言到底有没有在把关。
patch "$LAUNCHER" \
'        drawerPage.clearOverlays()
        // 面板下滑 56px + 淡出（240ms），结束后摘掉。
        drawerPage.playClose {' \
'        // 面板下滑 56px + 淡出（240ms），结束后摘掉。
        drawerPage.playClose {'
patch "$LAUNCHER" \
'        drawerPage.clearOverlays()
        drawerPage.reload()' \
'        drawerPage.reload()'
run_tests
restore

echo
echo "=== 还原后自检（应当 0 红灯）==="
run_tests

echo
echo "备份目录：$BK"
