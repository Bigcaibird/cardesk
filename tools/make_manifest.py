#!/usr/bin/env python3
"""生成 R2 上的「最新版本清单」（第八十三轮）。

清单是 App 在线更新的唯一依据，格式由 App 端的 `R2Release.parse` 定：
`app/src/main/java/com/cardesk/launcher/core/update/R2Release.kt`

用法：
    python3 tools/make_manifest.py \
        --apk dist/CarDesk-ecarx-cardesk-4149.apk \
        --version 1.0.1053 --code 4149 --tag v1.0.1053 \
        --name "CarDesk 4149 / 1.0.1053-system" \
        --notes-file notes.md \
        --out dist/cardesk-latest.json

`apk.url` 写的是 **R2 对象 key**（默认 `cardesk/<apk 文件名>`），
和往 bucket 里上传时用的 key 完全一致 —— 填什么就传什么。
"""
import argparse
import hashlib
import json
import os
import sys

# 清单里 apk.url 是相对路径时，App 按「域名根 + key」拼地址
DEFAULT_KEY_PREFIX = "cardesk"


def sha256_of(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def main():
    ap = argparse.ArgumentParser(description="生成 R2 更新清单")
    ap.add_argument("--apk", required=True, help="APK 路径（用来算大小和 SHA-256）")
    ap.add_argument("--version", required=True, help="版本号，如 1.0.1053")
    ap.add_argument("--code", type=int, required=True, help="versionCode，如 4149")
    ap.add_argument("--tag", default="", help="发布标记，如 v1.0.1053")
    ap.add_argument("--name", default="", help="展示标题")
    ap.add_argument("--notes", default="", help="更新说明（直接给文本）")
    ap.add_argument("--notes-file", default="", help="更新说明文件（存在时优先于 --notes）")
    ap.add_argument("--key", default="", help="R2 对象 key，默认 <prefix>/<apk 文件名>")
    ap.add_argument("--key-prefix", default=DEFAULT_KEY_PREFIX, help="对象 key 前缀，默认 cardesk")
    ap.add_argument("--published-at", default="", help="发布时间（ISO8601，默认取当前 UTC）")
    ap.add_argument("--out", required=True, help="输出清单路径")
    args = ap.parse_args()

    if not os.path.isfile(args.apk):
        sys.exit(f"APK 不存在：{args.apk}")

    notes = args.notes
    if args.notes_file and os.path.isfile(args.notes_file):
        with open(args.notes_file, "r", encoding="utf-8") as f:
            notes = f.read().strip()

    key = args.key or f"{args.key_prefix}/{os.path.basename(args.apk)}"

    published_at = args.published_at
    if not published_at:
        import datetime
        published_at = datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")

    manifest = {
        "schema": 1,
        "version": args.version,
        "versionCode": args.code,
        "tag": args.tag or ("v" + args.version),
        "name": args.name or f"CarDesk {args.code} / {args.version}-system",
        "notes": notes,
        "publishedAt": published_at,
        "apk": {
            "name": os.path.basename(args.apk),
            "url": key,
            "size": os.path.getsize(args.apk),
            "sha256": sha256_of(args.apk),
        },
    }

    out_dir = os.path.dirname(os.path.abspath(args.out))
    if out_dir:
        os.makedirs(out_dir, exist_ok=True)
    with open(args.out, "w", encoding="utf-8") as f:
        json.dump(manifest, f, ensure_ascii=False, indent=2)
        f.write("\n")

    print(f"== 清单：{args.out}")
    print(f"== 对象 key：{key}")
    print(f"== APK：{manifest['apk']['size']} 字节，sha256 {manifest['apk']['sha256']}")


if __name__ == "__main__":
    main()
