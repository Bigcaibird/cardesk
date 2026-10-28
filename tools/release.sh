#!/usr/bin/env bash
# =============================================================================
# CarDesk 一键出包（第五十七轮起）
#
# 做的事：
#   1) **自动把版本号 +1**（version.properties 是版本号的唯一来源）
#   2) rsync 到一个**全新的临时目录**（干净全量构建，避免增量构建在 APK 里
#      留下对齐填充空隙）
#   3) 跑全量单测 + 出 system 版 APK
#   4) 校验包名 / versionCode / versionName，算 SHA-256，落到 dist/
#   5) 重写 dist/README.md（当前版本 + 目录里其它 APK 是旧包）
#
# 用法：
#   tools/release.sh                                  # 升版本号 + 出包（默认）
#   tools/release.sh --no-bump                        # 不升版本号（重出同一版）
#   tools/release.sh --no-test                        # 跳过单测（不推荐）
#   tools/release.sh --package com.ecarx.qq.tool      # 换包名出包
#   tools/release.sh --code 4200 --name 1.0.1104      # 指定版本号（不自动 +1）
#
# 环境变量（都有默认值，按仓库自带工具链走）：
#   CARDESK_TOOLCHAIN   默认 <repo>/../toolchain
#   CARDESK_GRADLE      默认 <toolchain>/gradle-8.7/bin/gradle
#   CARDESK_AAPT2       默认 <toolchain>/sdk/build-tools/34.0.0/aapt2
#   GRADLE_USER_HOME    默认 <repo>/../.gradle-home
# =============================================================================
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ROOT="$(dirname "$REPO")"
TOOLCHAIN="${CARDESK_TOOLCHAIN:-$ROOT/toolchain}"

export JAVA_HOME="${JAVA_HOME:-$TOOLCHAIN/jdk-17.0.20.1+1/Contents/Home}"
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$ROOT/.gradle-home}"
GRADLE="${CARDESK_GRADLE:-$TOOLCHAIN/gradle-8.7/bin/gradle}"
AAPT2="${CARDESK_AAPT2:-$TOOLCHAIN/sdk/build-tools/34.0.0/aapt2}"

PACKAGE="com.ecarx.cardesk"
BUMP=1
RUN_TEST=1
FORCE_CODE=""
FORCE_NAME=""

while [ $# -gt 0 ]; do
    case "$1" in
        --no-bump) BUMP=0 ;;
        --no-test) RUN_TEST=0 ;;
        --package) PACKAGE="$2"; shift ;;
        --code)    FORCE_CODE="$2"; shift ;;
        --name)    FORCE_NAME="$2"; shift ;;
        -h|--help) sed -n '2,30p' "${BASH_SOURCE[0]}"; exit 0 ;;
        *) echo "未知参数：$1" >&2; exit 2 ;;
    esac
    shift
done

VERSION_FILE="$REPO/version.properties"
[ -f "$VERSION_FILE" ] || { echo "缺少 $VERSION_FILE" >&2; exit 1; }

read_prop() {
    awk -F= -v k="$1" '$1 ~ "^"k"$" {gsub(/^[ \t]+|[ \t]+$/, "", $2); print $2; exit}' "$VERSION_FILE"
}

CURRENT_CODE="$(read_prop versionCode)"
CURRENT_NAME="$(read_prop versionName)"
[ -n "$CURRENT_CODE" ] || { echo "version.properties 里没有 versionCode" >&2; exit 1; }

# ---------------------------------------------------------------- 1) 升版本号
if [ -n "$FORCE_CODE" ]; then
    CODE="$FORCE_CODE"
    NAME="${FORCE_NAME:-1.0.$((CODE - 3096))}"
    echo "== 指定版本号：$CODE / $NAME"
elif [ "$BUMP" -eq 1 ]; then
    CODE=$((CURRENT_CODE + 1))
    NAME="1.0.$((CODE - 3096))"
    echo "== 版本号自动 +1：$CURRENT_CODE → $CODE（versionName $NAME）"
    # 写回版本文件：保留注释头，只把最后两行换成新版本号
    TMP_V="$(mktemp)"
    awk '!/^versionCode=/ && !/^versionName=/' "$VERSION_FILE" > "$TMP_V"
    printf 'versionCode=%s\nversionName=%s\n' "$CODE" "$NAME" >> "$TMP_V"
    mv "$TMP_V" "$VERSION_FILE"
else
    CODE="$CURRENT_CODE"
    NAME="${FORCE_NAME:-$CURRENT_NAME}"
    echo "== 不升版本号，沿用 $CODE / $NAME"
fi

# ---------------------------------------------------------------- 2) 干净构建目录
WORK="$(mktemp -d "${TMPDIR:-/tmp}/cardesk-release-XXXXXX")"
echo "== 干净构建目录：$WORK"
rsync -a --checksum \
    --exclude build --exclude .gradle --exclude dist --exclude preview \
    "$REPO/" "$WORK/"

# ---------------------------------------------------------------- 3) 构建
cd "$WORK"
if [ "$RUN_TEST" -eq 1 ]; then
    echo "== 全量构建 + 单测"
    "$GRADLE" clean assembleSystem testDebugUnitTest \
        -PcardeskPackage="$PACKAGE" -PcardeskVersionCode="$CODE" -PcardeskVersionName="$NAME" \
        --no-daemon --console=plain
else
    echo "== 全量构建（跳过单测）"
    "$GRADLE" clean assembleSystem \
        -PcardeskPackage="$PACKAGE" -PcardeskVersionCode="$CODE" -PcardeskVersionName="$NAME" \
        --no-daemon --console=plain
fi

APK="$WORK/app/build/outputs/apk/system/app-system.apk"
[ -f "$APK" ] || { echo "没出包：$APK" >&2; exit 1; }

# ---------------------------------------------------------------- 4) 校验
BADGING="$("$AAPT2" dump badging "$APK" | head -1)"
echo "== badging：$BADGING"
case "$BADGING" in
    *"name='$PACKAGE'"*) : ;;
    *) echo "包名对不上（期望 $PACKAGE）" >&2; exit 1 ;;
esac
case "$BADGING" in
    *"versionCode='$CODE'"*) : ;;
    *) echo "versionCode 对不上（期望 $CODE）" >&2; exit 1 ;;
esac

sha256() {
    if command -v shasum >/dev/null 2>&1; then
        shasum -a 256 "$1" | awk '{print $1}'
    else
        sha256sum "$1" | awk '{print $1}'
    fi
}

DIST="$REPO/dist"
mkdir -p "$DIST"
# 命名沿用既有约定：CarDesk-<包名去掉 com.>-<versionCode>.apk
#   com.ecarx.cardesk → CarDesk-ecarx-cardesk-4124.apk
SHORT_PKG="$(echo "${PACKAGE#com.}" | tr '.' '-')"
OUT_NAME="CarDesk-${SHORT_PKG}-${CODE}.apk"
OUT="$DIST/$OUT_NAME"
cp "$APK" "$OUT"
SHA="$(sha256 "$OUT")"
echo "== 产物：$OUT"
echo "== SHA-256：$SHA"

# ---------------------------------------------------------------- 5) 重写 dist/README.md
{
    echo "# dist/ 交付说明"
    echo
    echo "> 本文件由 \`tools/release.sh\` **自动生成**（第五十七轮起）。"
    echo
    echo "## 当前版本"
    echo
    echo "| 文件 | 包名 | versionCode | versionName | 签名 | SHA-256 |"
    echo "| --- | --- | --- | --- | --- | --- |"
    echo "| \`$OUT_NAME\` | \`$PACKAGE\` | **$CODE** | \`$NAME-system\` | AOSP platform 签名 | \`$SHA\` |"
    echo
    echo "> 这是**唯一需要安装**的包。装到 \`/system/priv-app/<dir>/\` 后重启。"
    echo
    echo "## 目录里其它 APK = **旧包**"
    echo
    if [ -z "$(find "$DIST" -maxdepth 1 -name '*.apk' ! -name "$OUT_NAME" -print -quit)" ]; then
        echo "（没有其它 APK）"
    else
        echo "| 文件 | 说明 |"
        echo "| --- | --- |"
        find "$DIST" -maxdepth 1 -name '*.apk' ! -name "$OUT_NAME" -print | sort | while read -r f; do
            base="$(basename "$f")"
            info="$("$AAPT2" dump badging "$f" 2>/dev/null | head -1 | sed -E "s/.*name='([^']+)'.*versionCode='([^']+)'.*/\1 \/\2/" || true)"
            echo "| \`$base\` | 旧包（$info），**不要装** |"
        done
    fi
} > "$DIST/README.md"

echo
echo "==================== 出包完成 ===================="
echo "文件        : $OUT_NAME"
echo "包名        : $PACKAGE"
echo "versionCode : $CODE"
echo "versionName : $NAME-system"
echo "SHA-256     : $SHA"
echo "===================================================="
