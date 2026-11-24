#!/usr/bin/env bash
# 提交前自查：不新增「第三方 App 名称 / 逆向类」表述（工作区 AGENTS.md §2 的硬约束）。
#
# 背景：仓库历史文档（CHANGELOG / tools/notes-* / tools/handoff-* / ref_cites.json 等）
# 里存在大量存量表述，不能也不该在本次约束里一次性清洗。因此本脚本默认只检查
# **本次改动新增的内容**（未提交的 diff 增行 + 未跟踪的新文件），存量文件只在
# --all 模式下做信息性清单，不判失败。
#
# 用法：
#   bash tools/check_no_external_mentions.sh            # 检查本次改动（默认；有命中则退出码 1）
#   bash tools/check_no_external_mentions.sh --all      # 全仓存量清单（信息性，退出码 0）
#   bash tools/check_no_external_mentions.sh --all --strict   # 全仓存量也判失败
#   bash tools/check_no_external_mentions.sh --staged   # 只看已 staged 的改动（pre-commit 用）
#
# 约定：
# - 只扫 git 跟踪的文本文件（二进制靠 git grep -I / diff 文本判定跳过）。
# - 「系统变体的打包身份」是唯一豁免：cardesk 的系统变体以既有车机桌面包名分发，
#   该包名只允许出现在打包相关文件里（见 PKG_ALLOW 正则），其余位置一律视为违规。
set -uo pipefail

cd "$(dirname "$0")/.." || exit 2

# 产品名 / 逆向类表述
PATTERN='君君|[Jj]unjun|chepaiyunhui|逆向|破解|反编译|对标|参考 ?[Aa][Pp][Pp]|参考应用|提取自'
# 第三方包名（除打包身份文件外不得出现）
PKG_PATTERN='com[./]ecarx[./]qq[./]tool'
PKG_ALLOW='^(app/build\.gradle\.kts|app/src/system/|tools/push_system_app\.sh|tools/release\.sh|tools/publish_apks\.py)'

MODE=changed
STRICT=0
SELF=tools/check_no_external_mentions.sh
for arg in "$@"; do
  case "$arg" in
    --all) MODE=all ;;
    --staged) MODE=staged ;;
    --strict) STRICT=1 ;;
    -h|--help) sed -n '2,30p' "$0"; exit 0 ;;
    *) echo "未知参数: $arg（--all / --staged / --strict / --help）" >&2; exit 2 ;;
  esac
done

hits=0

report() { # $1=file  $2=line  $3=text
  hits=$((hits + 1))
  printf '  %s:%s: %s\n' "$1" "$2" "$3"
}

# 扫描一段 diff 的增行，按 hunk 头推算新文件行号。
scan_diff() {
  git diff -U0 --no-color "$@" 2>/dev/null | awk -v pat="$PATTERN" -v pkg="$PKG_PATTERN" -v self="$SELF" '
    /^diff --git / {
      f = $0
      sub(/^diff --git a\//, "", f)
      sub(/ b\/.*$/, "", f)
      next
    }
    /^\+\+\+ / { next }
    /^--- / { next }
    /^@@ / {
      if (match($0, /\+[0-9]+/)) line = substr($0, RSTART + 1, RLENGTH - 1) + 0
      next
    }
    f == self { next }
    /^\+/ {
      t = substr($0, 2)
      if (t ~ pat || t ~ pkg) printf "%s\t%d\t%s\n", f, line, t
      line++
      next
    }
  ' | while IFS=$'\t' read -r f l t; do
        case "$f" in
          $PKG_ALLOW) t=$(printf '%s' "$t" | sed -E "s/$PKG_PATTERN//g") ;; # 打包身份：剥掉包名后再判产品名/逆向词
        esac
        if printf '%s' "$t" | grep -Eq "$PATTERN"; then
          printf 'VIOLATION\t%s\t%s\t%s\n' "$f" "$l" "$t"
        fi
      done
}

echo "== 检查范围：$([ "$MODE" = all ] && echo '全仓存量（信息性）' || echo '本次改动新增内容')"

if [ "$MODE" = all ]; then
  # 存量清单：只列文件与命中数，避免刷屏（脚本自身含关键词表，永远豁免）
  while IFS= read -r f; do
    n=$(git grep -cI -E "$PATTERN" -- "$f" 2>/dev/null | head -1 | sed 's/.*://')
    printf '  %s: %s 处\n' "$f" "${n:-?}"
    hits=$((hits + 1))
  done < <(git grep -lI -E "$PATTERN" 2>/dev/null | grep -vxF "$SELF")
  if [ "$hits" -gt 0 ]; then
    echo "存量命中文件 $hits 个（历史文档/注释；按约束不再扩散、不新增即可）。"
    [ "$STRICT" -eq 1 ] && exit 1
  else
    echo "全仓无命中。"
  fi
  exit 0
fi

TMP=$(mktemp "${TMPDIR:-/tmp}/cardesk-mentions.XXXXXX") || exit 2
trap 'rm -f "$TMP"' EXIT

if [ "$MODE" = staged ]; then
  scan_diff --cached >>"$TMP"
else
  scan_diff >>"$TMP"
  scan_diff --cached >>"$TMP"
  # 未跟踪的新文件（不含二进制）
  git ls-files -o --exclude-standard -z >"$TMP.files"
  while IFS= read -r -d '' f; do
    case "$f" in *.png|*.jpg|*.jpeg|*.webp|*.apk|*.jar|*.zip|*.dex|*.so) continue ;; esac
    [ "$f" = "$SELF" ] && continue
    grep -nIE "$PATTERN|$PKG_PATTERN" -- "$f" 2>/dev/null | sed "s|^|${f}:|" >>"$TMP"
  done <"$TMP.files"
  rm -f "$TMP.files"
fi

while IFS= read -r line; do
  [ -z "$line" ] && continue
  case "$line" in
    VIOLATION*)
      IFS=$'\t' read -r _ f l t <<<"$line"
      report "$f" "$l" "${t:0:160}"
      ;;
    *)
      # 未跟踪文件的 grep 直出（file:line:text）
      report "${line%%:*}" "$(printf '%s' "$line" | cut -d: -f2)" "$(printf '%s' "$line" | cut -d: -f3-)"
      ;;
  esac
done <"$TMP"

if [ "$hits" -gt 0 ]; then
  echo
  echo "✗ 命中 $hits 处：请改成中性表述（内置资产 / 远端资源包 / 车机通道 / 按本项目约定设计）。"
  exit 1
fi
echo "✓ 未发现新增的第三方 App 名称或逆向类表述。"
exit 0
