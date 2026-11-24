#!/usr/bin/env bash
# =============================================================================
# CarDesk 一键出包（第五十七轮起）
#
# 做的事：
#   1) **自动把版本号 +1**（version.properties 是版本号的唯一来源）
#   2) rsync 到一个**全新的临时目录**（干净全量构建，避免增量构建在 APK 里
#      留下对齐填充空隙）
#   3) 跑全量单测 + 出 system 版 APK
#   4) 校验包名 / versionCode / versionName / **平台证书指纹**，算 SHA-256，落到 dist/
#   5) 重写 dist/README.md（当前版本 + 目录里其它 APK 是旧包）
#
# 用法：
#   tools/release.sh                                  # 升版本号 + 出**开发版**（默认，第三百零九轮起）
#   tools/release.sh --stable                         # 升版本号 + 出**正式版**
#   tools/release.sh --preview                        # 明确指定开发版（同默认）
#   tools/release.sh --no-bump                        # 不升版本号（重出同一版）
#   tools/release.sh --no-test                        # 跳过单测（不推荐）
#   tools/release.sh --code 4200 --name 1.0.1104      # 指定版本号（不自动 +1）
#   tools/release.sh --notes "这轮更新了什么"          # 写进 R2 更新清单的更新说明
#   tools/release.sh --notes-file notes.md            # 更新说明从文件读（优先于 --notes）
#
# ## 两条更新通道（第三百零九轮）
#
# | 通道 | versionName | 产物 | R2 |
# | --- | --- | --- | --- |
# | 开发版（默认） | `1.0.1224-preview` | `CarDesk-platform-ecarx-cardesk-4320-preview.apk` | `cardesk/preview/…`、`cardesk/preview/latest.json` |
# | 正式版（`--stable`） | `1.0.1224-system` | `CarDesk-platform-ecarx-cardesk-4320.apk` | `cardesk/…`、`cardesk/latest.json` |
#
# App 端「设置 → 在线更新 → 更新渠道」选哪条就查哪条清单；
# 两条独立，正式版用户不会被开发版弹窗打扰。
#
# ## 出什么包
#
# **只出 `com.ecarx.cardesk` 的 system 包**（平台签名 + uid 1000）。
#
# 第一九五轮起收紧：
# - 包名**固定**为 `com.ecarx.cardesk`（自有包名）——
#   不再出 `com.ecarx.qq.tool` 那个「替换参考 App」的版本
#   （`app/build.gradle.kts` 里 `assembleSystem` 不带 `-PcardeskPackage`
#   时的默认行为）。要替换参考 App 的话自己单独构建，别走发布脚本。
# - 单测跑 **system 变体**（`testSystemUnitTest`），
#   不再跑 debug 变体 —— 出的是 system 包，测的也该是 system。
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

# 出包包名：**固定**为 cardesk 自己的包名（第一九五轮起不再出替换版）。
PACKAGE="com.ecarx.cardesk"
BUMP=1
RUN_TEST=1
# 第三百零九轮：默认出**开发版**（用户要求）；`--stable` 才发正式版。
CHANNEL="preview"
FORCE_CODE=""
FORCE_NAME=""
NOTES=""
NOTES_FILE=""

while [ $# -gt 0 ]; do
    case "$1" in
        --no-bump) BUMP=0 ;;
        --no-test) RUN_TEST=0 ;;
        --stable)  CHANNEL="stable" ;;
        --preview) CHANNEL="preview" ;;
        --channel)
            case "$2" in
                stable|preview) CHANNEL="$2" ;;
                *) echo "--channel 只能是 stable / preview" >&2; exit 2 ;;
            esac
            shift ;;
        --package)
            # 第一九五轮：包名锁死成 com.ecarx.cardesk。
            # 传同一个值当没传；传别的直接拒绝（别让「替换版」从发布脚本里溜出去）。
            if [ "$2" != "$PACKAGE" ]; then
                echo "包名已固定为 $PACKAGE（不接受 '$2'）" >&2
                echo "要出别的包请自己跑 gradle assembleSystem -PcardeskPackage=..." >&2
                exit 2
            fi
            shift ;;
        --code)    FORCE_CODE="$2"; shift ;;
        --name)    FORCE_NAME="$2"; shift ;;
        --notes)   NOTES="$2"; shift ;;
        --notes-file) NOTES_FILE="$2"; shift ;;
        -h|--help) sed -n '2,50p' "${BASH_SOURCE[0]}"; exit 0 ;;
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
# 注意顺序：先把「基础版本号」定下来，再按通道决定 versionName 后缀，
# **最后**才写回 version.properties —— 否则开发版会把不带 -preview 的名字写进仓库。
WROTE_VERSION=0
if [ -n "$FORCE_CODE" ]; then
    CODE="$FORCE_CODE"
    NAME="${FORCE_NAME:-1.0.$((CODE - 3096))}"
    echo "== 指定版本号：$CODE / $NAME"
elif [ "$BUMP" -eq 1 ]; then
    CODE=$((CURRENT_CODE + 1))
    NAME="1.0.$((CODE - 3096))"
    WROTE_VERSION=1
    echo "== 版本号自动 +1：$CURRENT_CODE → $CODE（基础版本号 $NAME）"
else
    CODE="$CURRENT_CODE"
    # 上次是开发版（…-preview）时，重出正式版要把后缀剥掉
    NAME="${FORCE_NAME:-${CURRENT_NAME%-preview}}"
    echo "== 不升版本号，沿用 $CODE / $NAME"
fi

# 开发版：versionName 以 -preview 收尾，并且**不再**追加 buildType 的 -system
# （传空的 cardeskSystemSuffix，见 app/build.gradle.kts）——
# 最终 versionName 就是 `1.0.1224-preview`，与 R2 清单里的 version 完全一致。
if [ "$CHANNEL" = "preview" ]; then
    case "$NAME" in
        *-preview) : ;;
        *) NAME="$NAME-preview" ;;
    esac
    SUFFIX_PROP="-PcardeskSystemSuffix="
    CHANNEL_LABEL="开发版"
    MANIFEST_NAME="cardesk-latest-preview.json"
    R2_PREFIX="cardesk/preview"
else
    SUFFIX_PROP=""
    CHANNEL_LABEL="正式版"
    MANIFEST_NAME="cardesk-latest.json"
    R2_PREFIX="cardesk"
fi

# 写回版本文件：保留注释头，只把最后两行换成新版本号（开发版带 -preview）
if [ "$WROTE_VERSION" -eq 1 ]; then
    TMP_V="$(mktemp)"
    awk '!/^versionCode=/ && !/^versionName=/' "$VERSION_FILE" > "$TMP_V"
    printf 'versionCode=%s\nversionName=%s\n' "$CODE" "$NAME" >> "$TMP_V"
    mv "$TMP_V" "$VERSION_FILE"
fi
echo "== 更新通道：$CHANNEL_LABEL（versionCode $CODE / versionName $NAME）"

# ---------------------------------------------------------------- 2) 干净构建目录
WORK="$(mktemp -d "${TMPDIR:-/tmp}/cardesk-release-XXXXXX")"
# 收尾必须删掉这份干净构建目录：一次 1.8G，攒几次就把盘顶满了
# （2026-11-19 实测：TMPDIR 里堆了 8 份 cardesk-release-*，共 14.4G）。
# 产物在第 4.1 步之前已 cp 回 "$REPO/dist"，删 WORK 不影响出包。
trap 'rm -rf "$WORK"' EXIT
echo "== 干净构建目录：$WORK"
rsync -a --checksum \
    --exclude build --exclude .gradle --exclude dist --exclude dist-dev --exclude preview \
    "$REPO/" "$WORK/"

# ---------------------------------------------------------------- 3) 构建
cd "$WORK"
if [ "$RUN_TEST" -eq 1 ]; then
    echo "== 全量构建 + 单测（system 变体）"
    # 第一九五轮：测 **system 变体**（出的是 system 包，测的也该是 system），
    # 不再跑 debug 变体。
    "$GRADLE" clean assembleSystem testSystemUnitTest \
        -PcardeskPackage="$PACKAGE" -PcardeskVersionCode="$CODE" -PcardeskVersionName="$NAME" \
        $SUFFIX_PROP \
        --no-daemon --console=plain
else
    echo "== 全量构建（跳过单测）"
    "$GRADLE" clean assembleSystem \
        -PcardeskPackage="$PACKAGE" -PcardeskVersionCode="$CODE" -PcardeskVersionName="$NAME" \
        $SUFFIX_PROP \
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
# 第三百零九轮：通道要能从 versionName 一眼看出来（诊断包/更新弹窗都靠它）
case "$BADGING" in
    *"versionName='$NAME'"*) : ;;
    *) echo "versionName 对不上（期望 $NAME；通道 $CHANNEL_LABEL）" >&2; exit 1 ;;
esac

# ---------------------------------------------------------------- 3.5) 平台证书校验
#
# `platform-key/` 不在的时候，`assembleSystem` 会**静默退回 debug 签名**
# （见 app/build.gradle.kts 的条件判断：密钥文件不存在就不设 signingConfig）。
# 那种包装到车机上会因为签名不符拿不到 `CAR_VENDOR_EXTENSION` ——
# 现象是「氛围灯不亮、车控句柄拿不到」，跟「没接氛围灯」一模一样，
# 很难一眼看出是签名的问题。
#
# 所以这里**验指纹**：不是车机那张平台证书就直接失败，不让它流到 dist/。
# （uni-launcher/tools/sign_car_apk.py 校验的是同一个值 —— 两个 app 同一张证书。）
PLATFORM_SHA256="c8a2e9bccf597c2fb6dc66bee293fc13f2fc47ec77bc6b2b0d52c11f51192ab8"
APKSIGNER_JAR="$(ls -1 "$TOOLCHAIN"/sdk/build-tools/*/lib/apksigner.jar 2>/dev/null | tail -1)"
if [ -z "$APKSIGNER_JAR" ] || [ ! -f "$APKSIGNER_JAR" ]; then
    echo "找不到 apksigner.jar（$TOOLCHAIN/sdk/build-tools）" >&2
    exit 1
fi
GOT_CERT="$("$JAVA_HOME/bin/java" -cp "$APKSIGNER_JAR" com.android.apksigner.ApkSignerTool \
    verify --print-certs "$APK" | sed -n 's/^Signer #1 certificate SHA-256 digest: //p')"
if [ "$GOT_CERT" != "$PLATFORM_SHA256" ]; then
    echo "证书不对：期望 $PLATFORM_SHA256，实际 '$GOT_CERT'" >&2
    echo "（这个包装到车机上会因为签名不符拿不到车控权限）" >&2
    echo "检查 $REPO/platform-key/ 是否存在（平台私钥不入库）" >&2
    exit 1
fi
echo "== 平台证书 SHA-256：$GOT_CERT"

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
# 名字里加 "-platform"：**这是平台签名的实车包**，
# 与开发包（debug 签名）、以及 uni-launcher 的实车包（UNI-Launcher-*.apk）
# 一眼能分开 —— 两个 app 同一张证书、不同应用与包名。
SHORT_PKG="$(echo "${PACKAGE#com.}" | tr '.' '-')"
if [ "$CHANNEL" = "preview" ]; then
    OUT_NAME="CarDesk-platform-${SHORT_PKG}-${CODE}-preview.apk"
else
    OUT_NAME="CarDesk-platform-${SHORT_PKG}-${CODE}.apk"
fi
OUT="$DIST/$OUT_NAME"
cp "$APK" "$OUT"
SHA="$(sha256 "$OUT")"
echo "== 产物：$OUT"
echo "== SHA-256：$SHA"

# ---------------------------------------------------------------- 4.1) R2 更新清单
# App 的「在线更新」从 Cloudflare R2 拉这个清单（解析见 core/update/R2Release.kt）。
# 清单里的 apk.url 是 **R2 对象 key**，和上传时用的 key 完全一致。
MANIFEST="$DIST/$MANIFEST_NAME"
if [ -n "$NOTES_FILE" ] && [ ! -f "$NOTES_FILE" ]; then
    echo "更新说明文件不存在：$NOTES_FILE" >&2
    exit 1
fi
# 清单里的 notes 会显示在 App 的「发现新版本」弹窗里；漏了就等于用户看不到更新说明。
if [ -z "$NOTES" ] && [ -z "$NOTES_FILE" ]; then
    echo "⚠️  没有传 --notes / --notes-file：本版 latest.json 的 notes 会是空字符串，" >&2
    echo "   在线更新弹窗将没有更新说明。补说明：tools/release.sh --no-bump --notes \"...\"" >&2
fi
python3 "$REPO/tools/make_manifest.py" \
    --apk "$OUT" \
    --version "$NAME" \
    --code "$CODE" \
    --tag "v$NAME" \
    --name "CarDesk $CODE / $NAME${CHANNEL_LABEL:+（$CHANNEL_LABEL）}" \
    --notes "$NOTES" \
    --notes-file "$NOTES_FILE" \
    --channel "$CHANNEL" \
    --out "$MANIFEST"

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
    echo "| \`$OUT_NAME\` | \`$PACKAGE\` | **$CODE** | \`$NAME\` | AOSP platform 签名 | \`$SHA\` |"
    echo
    echo "> 更新通道：**$CHANNEL_LABEL**（\`$CHANNEL\`）—— R2 清单 \`$R2_PREFIX/latest.json\`"
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
echo "通道        : $CHANNEL_LABEL（$CHANNEL）"
echo "versionCode : $CODE"
echo "versionName : $NAME"
echo "签名        : AOSP platform（SHA-256 $GOT_CERT）"
echo "SHA-256     : $SHA"
echo "R2 清单     : $MANIFEST"
echo "R2 对象 key : $R2_PREFIX/$OUT_NAME"
echo
echo "上传："
echo "  set -a; . ./R2.txt; set +a"
echo "  python3 tools/publish_r2.py --bucket file --channel $CHANNEL \\"
echo "      --apk $OUT --manifest $MANIFEST"
echo "===================================================="
