#!/usr/bin/env bash
# 第134轮反向验证：把实现逐条改回「旧行为」，确认对应断言**必红**。
#
# 用法：bash tools/verify_y134_reverse.sh
#
# 背景（第134轮）：
#   用户诉求：「接管方控之后，**仪表只有歌名和歌手，没有图片**；
#   车机默认的爱趣听是有图片的。」
#
#   根因（反编译原车 carmedia/MultiMedia.apk 实锤）：
#   原车 SDK 里 `IMediaInfo#getArtwork()` / `IMusicPlaybackInfo#getArtwork()`
#   返回 `android.net.Uri`；我们的动态代理**压根没实现**这个 getter
#   → 落到 `defaultValue()` → 返回 **null** → 仪表拿不到封面。
#
#   下面每一条反向验证都对应一组断言：改坏 → 断言必红 → 还原 → 恢复全绿。
set -u
cd /Users/roy/Documents/desktopapp/cardesk

export JAVA_HOME=/Users/roy/Documents/desktopapp/toolchain/jdk-17.0.20.1+1/Contents/Home
export GRADLE_USER_HOME=/Users/roy/Documents/desktopapp/.gradle-home
GRADLE=/Users/roy/Documents/desktopapp/toolchain/gradle-8.7/bin/gradle

LOGIC=app/src/main/java/com/cardesk/launcher/core/media/BridgeArtwork.kt
STORE=app/src/main/java/com/cardesk/launcher/core/media/ArtworkProvider.kt
BRIDGE=app/src/main/java/com/cardesk/launcher/core/media/OfficialMediaBridge.kt
FILES="$LOGIC $STORE $BRIDGE"

# 自建备份（可复跑；不用相对路径 rm -rf）
BK=$(mktemp -d /tmp/y134-bk-XXXXXX)
echo "$BK" > /tmp/y134-bk-path.txt
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
    --tests "com.cardesk.launcher.core.media.BridgeArtworkTest" \
    --tests "com.cardesk.launcher.core.media.ArtworkStoreTest" \
    --tests "com.cardesk.launcher.core.media.MediaBridgePolicyTest" \
    --console=plain > /tmp/y134-rev-run.log 2>&1
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

# 接线检查：代理里必须**真的**把封面 getter 接到 Uri 上。
# （这一条是 Android 侧代码，没有 JVM 单测能覆盖，用源码断言兜住。）
check_wiring() {
  python3 - "$BRIDGE" <<'PYEOF'
import io, sys
s = io.open(sys.argv[1], encoding='utf-8').read()
ok = ("ArtworkGetter.CURRENT -> artworkUri(s.artwork)" in s
      and "BridgeArtwork.artworkGetter(method.name)" in s)
print("RED=%d TOTAL=1 ERR=0" % (0 if ok else 1))
if not ok:
    print("   红: OfficialMediaBridge.代理不再应答 getArtwork（仪表又会没图）")
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

echo
echo "=== 反向1：认不出 getArtwork（回到「压根没实现」的老样子）==="
patch "$LOGIC" \
'        "getArtwork" -> ArtworkGetter.CURRENT' \
'        "getArtwork" -> ArtworkGetter.NONE'
run_tests
restore

echo
echo "=== 反向2：取值顺序颠倒（ART_URI 压过 DISPLAY_ICON_URI）==="
patch "$LOGIC" \
'        MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI,
        MediaMetadata.METADATA_KEY_ART_URI,' \
'        MediaMetadata.METADATA_KEY_ART_URI,
        MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI,'
run_tests
restore

echo
echo "=== 反向3：不认 content:// （MediaStore 的 albumart 是最常见的一种）==="
patch "$LOGIC" \
'        return s.startsWith("content://") ||
            s.startsWith("file://") ||' \
'        return s.startsWith("file://") ||'
run_tests
restore

echo
echo "=== 反向4：认 android.resource:// （跨进程根本读不到）==="
patch "$LOGIC" \
'        return s.startsWith("content://") ||' \
'        return s.startsWith("android.resource://") ||
            s.startsWith("content://") ||'
run_tests
restore

echo
echo "=== 反向5：本地路径文件不存在也当有图（给了也读不出来）==="
patch "$LOGIC" \
'        if (isLocalPath(s)) {
            return if (localFileExists) "file://$s" else EMPTY_ARTWORK
        }' \
'        if (isLocalPath(s)) {
            return "file://$s"
        }'
run_tests
restore

echo
echo "=== 反向6：封面变了不重推（异步到手的图永远推不出去）==="
patch "$LOGIC" \
'    fun changed(oldValue: String?, newValue: String?): Boolean =
        (oldValue ?: EMPTY_ARTWORK) != (newValue ?: EMPTY_ARTWORK)' \
'    fun changed(oldValue: String?, newValue: String?): Boolean = false'
run_tests
restore

echo
echo "=== 反向7：sanitize 放过 .. （目录穿越）==="
patch "$STORE" \
'        var s = sb.toString()
        // ".." 能穿越目录 —— 一律压掉（压完可能又冒出新的 ".."，所以用 while）
        while (s.contains("..")) s = s.replace("..", "_")' \
'        var s = sb.toString()'
run_tests
restore

echo
echo "=== 反向8：sanitize 放过 / （斜杠能把路径拆开）==="
patch "$STORE" \
'            val ok = ch == '"'"'.'"'"' || ch == '"'"'_'"'"' || ch == '"'"'-'"'"' ||
                (ch.isLetterOrDigit() && !ch.isWhitespace())' \
'            val ok = true'
run_tests
restore

echo
echo "=== 反向9：authority 写死（调试包与系统包抢同一个 authority）==="
patch "$STORE" \
'    fun authority(context: Context): String =
        "${context.packageName}${ArtworkProvider.AUTHORITY_SUFFIX}"' \
'    fun authority(context: Context): String =
        "com.ecarx.cardesk${ArtworkProvider.AUTHORITY_SUFFIX}"'
run_tests
restore

echo
echo "=== 反向10：歌的 key 恒为空（迟到的封面认不出是不是这一首的）==="
patch "$LOGIC" \
'        if (s.isEmpty() && t.isEmpty() && a.isEmpty()) return ""
        return "$s|$t|$a"' \
'        return ""'
run_tests
restore

echo
echo "=== 反向11：代理里把封面应答改成 null（回到「仪表没图」）==="
patch "$BRIDGE" \
'                BridgeArtwork.ArtworkGetter.CURRENT -> artworkUri(s.artwork)' \
'                BridgeArtwork.ArtworkGetter.CURRENT -> null'
check_wiring
restore

echo
echo "=== 还原后自检（应当 0 红灯）==="
run_tests
check_wiring

echo
echo "备份目录：$BK"
