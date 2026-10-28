#!/usr/bin/env bash
# 从真机上一次性收集 CarDesk 的诊断日志。
#
# 用法：
#   tools/collect-logs.sh [serial]        # 不带 serial 时自动用唯一在线设备
#
# 产出：
#   ./cardesk-logs-<时间戳>/
#       ├── cardesk-logs.zip     诊断包（App 内「设置 → 系统 → 诊断日志 → 导出诊断包」生成）
#       ├── logcat.txt           进程存活期间的 logcat
#       ├── prefs.xml            设置全量键值
#       └── device.txt           设备 / 版本 / 通道状态
#
# 说明：
#   - 需要 adb（platform-tools 在仓库同级目录 ../toolchain/sdk/platform-tools）。
#   - system 包名是 com.ecarx.qq.tool，debug 包名是 com.cardesk.launcher，两个都试。

set -u

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

ADB_BIN="adb"
if [ -x "$REPO_ROOT/../toolchain/sdk/platform-tools/adb" ]; then
    ADB_BIN="$REPO_ROOT/../toolchain/sdk/platform-tools/adb"
fi

SERIAL="${1:-}"
if [ -z "$SERIAL" ]; then
    # 只有一个在线设备时自动选它
    mapfile -t DEVICES < <("$ADB_BIN" devices | awk 'NR>1 && $2=="device" {print $1}')
    if [ "${#DEVICES[@]}" -eq 1 ]; then
        SERIAL="${DEVICES[0]}"
    elif [ "${#DEVICES[@]}" -eq 0 ]; then
        echo "没有在线设备，先检查 adb devices" >&2
        exit 1
    else
        echo "有多个设备，请指定 serial：${DEVICES[*]}" >&2
        exit 1
    fi
fi

STAMP="$(date +%Y%m%d-%H%M%S)"
OUT_DIR="${PWD}/cardesk-logs-${STAMP}"
mkdir -p "$OUT_DIR"

echo "==> 设备 $SERIAL"
"$ADB_BIN" -s "$SERIAL" shell getprop ro.product.model 2>/dev/null | tee "$OUT_DIR/device.txt"
"$ADB_BIN" -s "$SERIAL" shell getprop ro.build.version.release 2>/dev/null | sed 's/^/Android /' | tee -a "$OUT_DIR/device.txt"
"$ADB_BIN" -s "$SERIAL" shell getprop ro.build.version.sdk 2>/dev/null | sed 's/^/SDK /' | tee -a "$OUT_DIR/device.txt"
"$ADB_BIN" -s "$SERIAL" shell getprop ro.build.fingerprint 2>/dev/null | sed 's/^/Fingerprint /' | tee -a "$OUT_DIR/device.txt"

# ---- 1. App 内导出的诊断包（Download/CarDesk/）
echo "==> 拉取诊断包"
FOUND=0
for pkg in com.cardesk.launcher com.ecarx.qq.tool; do
    # Android 10+ 用 MediaStore，先按包名查最近写入的记录
    ZIP_PATH="$("$ADB_BIN" -s "$SERIAL" shell "ls -t /sdcard/Download/CarDesk/ 2>/dev/null | head -1" | tr -d '\r')"
    if [ -n "$ZIP_PATH" ]; then
        "$ADB_BIN" -s "$SERIAL" pull "/sdcard/Download/CarDesk/$ZIP_PATH" "$OUT_DIR/cardesk-logs.zip" >/dev/null 2>&1 && FOUND=1 && break
    fi
    # 私有目录（需 root 或 debuggable 包）
    "$ADB_BIN" -s "$SERIAL" shell "run-as $pkg ls -t /data/data/$pkg/files/logs 2>/dev/null | head -5" >/dev/null 2>&1
done
[ "$FOUND" -eq 0 ] && echo "   （没找到诊断包；可在车机里打开 设置 → 系统 → 诊断日志 → 导出诊断包 后再跑一次）"

# ---- 2. logcat
echo "==> 抓 logcat"
"$ADB_BIN" -s "$SERIAL" logcat -d -v threadtime 2>/dev/null | grep -i "cardesk" > "$OUT_DIR/logcat.txt" || true
"$ADB_BIN" -s "$SERIAL" logcat -d -b crash -v threadtime 2>/dev/null > "$OUT_DIR/crash.txt" || true

# ---- 3. 设置全量键值
echo "==> 抓设置键值"
for pkg in com.cardesk.launcher com.ecarx.qq.tool; do
    if "$ADB_BIN" -s "$SERIAL" shell "run-as $pkg cat /data/data/$pkg/shared_prefs/cardesk.xml" > "$OUT_DIR/prefs.xml" 2>/dev/null; then
        [ -s "$OUT_DIR/prefs.xml" ] && break
    fi
done

# ---- 4. 车控通道相关系统日志
echo "==> 抓车控/系统相关日志"
"$ADB_BIN" -s "$SERIAL" logcat -d -v threadtime 2>/dev/null \
    | grep -Ei "ecarx|adaptapi|car\.lib|vfmisc|HvacController|car\.hmi|android\.hardware\.automotive" \
    > "$OUT_DIR/vehicle.txt" || true

echo
echo "完成：$OUT_DIR"
ls -la "$OUT_DIR"
echo
echo "把这个目录打包发给开发即可："
echo "  tar czf cardesk-logs-${STAMP}.tar.gz -C \"$(dirname "$OUT_DIR")\" \"$(basename "$OUT_DIR")\""
