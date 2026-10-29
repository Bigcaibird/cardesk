#!/usr/bin/env bash
# 把平台签名版 CarDesk 预装进「可 root 的模拟器/真机」的 /system/priv-app，并补两条白名单。
#
# 用法：
#   tools/push_system_app.sh                 # 自动找 dist/ 里最新的替换版
#   tools/push_system_app.sh com.ecarx.qq.tool
#   tools/push_system_app.sh <adb-serial> <package-name>
#
# 前提：镜像为 AOSP test-keys（framework 证书 SHA-256 = c8a2e9bc…），且启动时带 -writable-system
#
# 为什么必须推这两条白名单：
#   1) hiddenapi-package-whitelist —— `android.app.ActivityView` 是 @SystemApi，
#      不在白名单里的话 Class.forName 会被 hidden API 拦截，**画中画必然降级成卡片**；
#   2) privapp-permissions —— ActivityView 需要 INTERNAL_SYSTEM_WINDOW 签名级权限。
# 白名单里的包名必须跟 APK 的实际包名一致，所以这里按 APK 动态生成。
#
# 氛围灯频谱还需要 RECORD_AUDIO / MODIFY_AUDIO_SETTINGS：
#   - privapp 白名单里**也**写上（system app 走这条最快）；
#   - 白名单只对 **privileged** 权限生效，而 RECORD_AUDIO 是 dangerous 权限，
#     所以重启后再补一刀 `pm grant` + `appops set`（幂等，批过了也不报错）。
#     没有这一步，频谱采集的第一道门就过不去 → 灯不亮、频谱条全空。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SERIAL="${1:-}"
PKG_OVERRIDE="${2:-}"

# ---- 找 APK：优先 dist/ 里最新的 CarDesk-replace-ecarx-*.apk，其次任意 CarDesk-*.apk ----
if [ -n "$PKG_OVERRIDE" ] && [ -f "$PKG_OVERRIDE" ]; then
  APK="$PKG_OVERRIDE"
else
  APK="$(ls -1t "$ROOT"/dist/CarDesk-replace-ecarx-*.apk 2>/dev/null | head -1 || true)"
  [ -n "$APK" ] || APK="$(ls -1t "$ROOT"/dist/CarDesk-*.apk 2>/dev/null | grep -v probe-app | head -1 || true)"
fi
[ -n "$APK" ] && [ -f "$APK" ] || { echo "dist/ 里找不到 APK，先跑 gradle assembleSystem"; exit 1; }
echo "APK: $APK"

# ---- 读出包名 ----
AAPT=""
for c in "${ANDROID_HOME:-}"/build-tools/*/aapt "${ANDROID_SDK_ROOT:-}"/build-tools/*/aapt; do
  [ -x "$c" ] && { AAPT="$c"; break; }
done
if [ -z "$AAPT" ]; then
  AAPT="$(ls -1 /Users/roy/Documents/desktopapp/toolchain/sdk/build-tools/*/aapt 2>/dev/null | head -1 || true)"
fi
PKG=""
if [ -n "$AAPT" ] && [ -x "$AAPT" ]; then
  PKG="$("$AAPT" dump badging "$APK" 2>/dev/null | sed -n "s/^package: name='\([^']*\)'.*/\1/p" | head -1)"
fi
[ -n "$PKG" ] || PKG="com.ecarx.qq.tool"
echo "包名: $PKG"

# ---- 生成白名单（包名必须与 APK 一致） ----
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
cat > "$TMP/privapp-permissions-$PKG.xml" <<EOF
<?xml version="1.0" encoding="utf-8"?>
<permissions>
    <privapp-permissions package="$PKG">
        <permission name="android.permission.CHANGE_COMPONENT_ENABLED_STATE"/>
        <permission name="android.permission.DELETE_PACKAGES"/>
        <permission name="android.permission.DEVICE_POWER"/>
        <permission name="android.permission.EXPAND_STATUS_BAR"/>
        <permission name="android.permission.INJECT_EVENTS"/>
        <permission name="android.permission.INSTALL_PACKAGES"/>
        <permission name="android.permission.INTERNAL_SYSTEM_WINDOW"/>
        <permission name="android.permission.MANAGE_USB"/>
        <permission name="android.permission.MEDIA_CONTENT_CONTROL"/>
        <permission name="android.permission.MODIFY_AUDIO_SETTINGS"/>
        <permission name="android.permission.MOUNT_UNMOUNT_FILESYSTEMS"/>
        <permission name="android.permission.READ_LOGS"/>
        <permission name="android.permission.REBOOT"/>
        <!-- 频谱采集（氛围灯）：抓输出混音必须要 RECORD_AUDIO。 -->
        <permission name="android.permission.RECORD_AUDIO"/>
        <!--
          SET_APPOP：让 system app 能**直接把自己的 AppOps 打开**
          （`pm grant` 只写 permission grant，不写 AppOps —— 结果就是
          「权限显示已授予，但一个位置/音频都拿不到」）。
          频谱的 RECORD_AUDIO 就是 danger 权限 + AppOp 双层，
          对应代码 `core/Permissions.kt` 的 `tryGrantRecordAudioAppOps`。
        -->
        <permission name="android.permission.SET_APPOP"/>
        <permission name="android.permission.STATUS_BAR"/>
        <permission name="android.permission.WRITE_SECURE_SETTINGS"/>
        <permission name="android.permission.WRITE_SETTINGS"/>
    </privapp-permissions>
</permissions>
EOF
cat > "$TMP/hiddenapi-package-whitelist.xml" <<EOF
<?xml version="1.0" encoding="utf-8"?>
<config>
    <hidden-api-whitelisted-app package="$PKG" />
</config>
EOF

ADB="adb${SERIAL:+ -s $SERIAL}"
$ADB root
sleep 1
$ADB remount
$ADB shell mkdir -p /system/priv-app/CarDesk
$ADB push "$APK" /system/priv-app/CarDesk/CarDesk.apk
$ADB shell chmod 644 /system/priv-app/CarDesk/CarDesk.apk
$ADB push "$TMP/privapp-permissions-$PKG.xml" /system/etc/permissions/
$ADB shell chmod 644 "/system/etc/permissions/privapp-permissions-$PKG.xml"
$ADB push "$TMP/hiddenapi-package-whitelist.xml" /system/etc/sysconfig/
$ADB shell chmod 644 /system/etc/sysconfig/hiddenapi-package-whitelist.xml
$ADB reboot
echo "已推送并重启；开机后执行："
echo "  adb shell pm path $PKG        # 应为 /system/priv-app/CarDesk/CarDesk.apk"
echo "  adb shell dumpsys package $PKG | grep userId   # 应为 userId=1000"
echo "  # 画中画是否为真嵌入（而不是降级卡片）："
echo "  adb logcat -s CarDesk/NavUI | grep '画中画显示'"
echo "    mode=embedded → 真嵌入成功；mode=fallback → 权限/白名单没生效"

# ---- 录音权限（频谱采集用）----
# privapp 白名单只对 privileged 权限生效，而 RECORD_AUDIO 是 dangerous 权限，
# 所以这里再补一刀：pm grant 写 permission grant，appops set 写 AppOps mode。
# 两层都写上，频谱采集的第一道门才不会卡住（都是幂等的，批过了也不报错）。
echo "---- 授权录音权限（频谱采集）----"
sleep 20
$ADB shell pm grant "$PKG" android.permission.RECORD_AUDIO || \
    echo "  (pm grant RECORD_AUDIO 失败：包还没装上？)"
$ADB shell pm grant "$PKG" android.permission.MODIFY_AUDIO_SETTINGS || \
    echo "  (pm grant MODIFY_AUDIO_SETTINGS 失败：包还没装上？)"
$ADB shell appops set "$PKG" android:record_audio allow || \
    echo "  (appops set record_audio 失败)"
echo "  查状态："
echo "    adb shell dumpsys package $PKG | grep -E 'RECORD_AUDIO|MODIFY_AUDIO'"
echo "    adb shell appops get $PKG android:record_audio"
