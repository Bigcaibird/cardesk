#!/usr/bin/env python3
"""把 APK 上传到 Cloudflare R2 的「云端应用库」，并重写车机读的清单（第八十四轮）。

## 它是什么

`worker/` 里的 Cloudflare Worker 是**手机端**的上传入口（浏览器选 APK → 传）。
这个脚本是**电脑端**的同一个入口（命令行传），两边产出的东西**完全一样**：

- APK 放在 `cardesk/apks/<文件名>`；
- 清单写在 `cardesk/apks.json`，车机（CarDesk 的「云端应用」页）读它。

用途分工：

- 平时用手机上的 Worker 传；
- 这个脚本给**开发和验证**用 —— 不用部署 Worker 就能把一个包塞到真 R2 上，
  车机端照样能拉到、能装。

## 清单格式（**车机端解析的权威在 `core/apkstore/ApkIndex.kt`**）

```json
{
  "schema": 1,
  "updatedAt": "2026-10-30T09:00:00Z",
  "items": [
    {
      "name": "酷狗音乐",
      "file": "kugou.apk",
      "key": "cardesk/apks/kugou.apk",
      "size": 12345678,
      "sha256": "64位小写十六进制",
      "addedAt": "2026-10-30T09:00:00Z"
    }
  ]
}
```

- `key` 是 **R2 对象 key**（相对域名根）—— 车机按域名根拼成绝对地址，
  和「在线更新」的 `latest.json` 同一个思路（改域名不用改清单）。
- `sha256` 是车机下载后要校验的摘要；**清单里没给就不校验**（不猜）。
- `name` / `file` / `addedAt` 只用于展示。

## 为什么每个 APK 要带 `x-amz-meta-*`

摘要不能重新算（那要把所有包再下一遍），所以**上传时就把摘要写进对象的元数据**，
之后扫前缀时读回来。Worker 用 R2 binding 的 `customMetadata` 写的是同一份元数据
（S3 侧就是 `x-amz-meta-*` 头），所以两边互相都认得。

## 凭据从哪来

**只从环境变量读**，脚本里、仓库里都不写密钥：

    export R2_AK=<Access Key ID>
    export R2_SK=<Secret Access Key>

## 用法

    python3 tools/publish_apks.py --bucket file --apk /path/to/app.apk --name "酷狗音乐"

上传完会回读校验（APK 与清单都验长度 + SHA-256）。
"""
import argparse
import datetime
import hashlib
import os
import sys
import xml.etree.ElementTree as ET
from urllib.parse import quote, unquote

from publish_r2 import R2, sha256_bytes  # 复用同一份 SigV4 实现

#: APK 对象 key 的前缀（与 Worker 的 PREFIX 一致）。
PREFIX = "cardesk/apks/"

#: 清单的 object key（与 Worker 的 INDEX_KEY 一致）。
INDEX_KEY = "cardesk/apks.json"

#: 清单里每个条目的字段顺序（与 Worker 写出来的顺序一致，方便人眼比对）。
ITEM_FIELDS = ("name", "file", "key", "size", "sha256", "addedAt")

S3_NS = "{http://s3.amazonaws.com/doc/2006-03-01/}"


def sanitize_file_name(raw):
    """文件名净化：只留最后一段，去掉路径分隔符与特殊字符（与 Worker 同规则）。"""
    base = os.path.basename(str(raw).replace("\\", "/"))
    out = []
    for ch in base:
        if ch.isalnum() or ch in "._- ()":
            out.append(ch)
        else:
            out.append("_")
    cleaned = "".join(out).strip()[:120]
    if not cleaned or cleaned in (".", ".."):
        return ""
    return cleaned


def list_objects(r2, bucket, prefix):
    """列出 bucket 里某个前缀下的对象，返回 [(key, size)]。"""
    # 查询参数里的值必须**逐字百分号编码**（连 `/` 也要编成 %2F）：
    # 服务端会用 RFC3986 规则重新规范化 query 再算签名，
    # 你签名时留了裸 `/`、它算的时候是 %2F，签名就对不上了（SignatureDoesNotMatch）。
    # 注意这和对象 key 的路径编码不是一回事 —— 路径里的 `/` 是分隔符，必须保留。
    query = "list-type=2&prefix=" + quote(prefix, safe="")
    code, body = r2.request("GET", f"/{bucket}/", query=query)
    if code != 200:
        raise RuntimeError(f"列出对象失败 HTTP {code}：{body[:400].decode('utf-8', 'replace')}")
    root = ET.fromstring(body.decode("utf-8"))
    out = []
    for node in root.findall(f"{S3_NS}Contents"):
        key = node.findtext(f"{S3_NS}Key") or ""
        size = int(node.findtext(f"{S3_NS}Size") or "0")
        if key.startswith(prefix) and key.endswith(".apk"):
            out.append((key, size))
    return out


def head_meta(r2, bucket, key):
    """读对象元数据（S3 的 x-amz-meta-* 头）。读不到就返回空 dict。"""
    code, body = r2.request("HEAD", f"/{bucket}/{_quote(key)}")
    if code != 200:
        return {}
    meta = {}
    for k, v in r2.last_headers.items():
        if k.lower().startswith("x-amz-meta-"):
            meta[k.lower()[len("x-amz-meta-"):]] = v
    return meta


def encode_meta(value):
    """展示名进对象元数据前先百分号编码。

    元数据（S3 侧就是 x-amz-meta-* 头）**只能放 ASCII**：
    头值按 latin-1 编码，中文放进去会直接报
    `'latin-1' codec can't encode characters`。
    所以存 `encodeURIComponent` 过的 ASCII，读的时候解回来 ——
    和 Worker 的写法一致，两边互相都认得。
    """
    return quote(str(value).encode("utf-8"), safe="")


def decode_meta(value):
    """解开百分号编码的展示名；解不了就原样返回（名字难看点总比丢记录强）。"""
    if not value:
        return ""
    try:
        return unquote(value, encoding="utf-8", errors="strict")
    except Exception:
        return str(value)


def _quote(s):
    """URL 编码（保留 / 作为路径分隔符，其余转义）。"""
    return quote(s, safe="/")


def build_index(r2, bucket):
    """扫前缀 → 拼清单（结构和 Worker 写出来的完全一致）。"""
    items = []
    for key, size in list_objects(r2, bucket, PREFIX):
        meta = head_meta(r2, bucket, key)
        file_name = key[len(PREFIX):]
        items.append({
            "name": decode_meta(meta.get("appname")) or file_name[:-4],
            "file": file_name,
            "key": key,
            "size": size,
            "sha256": (meta.get("sha256") or "").lower(),
            "addedAt": meta.get("addedat") or "",
        })
    items.sort(key=lambda it: it.get("addedAt") or "", reverse=True)
    return {"schema": 1, "updatedAt": _now_iso(), "items": items}


def _now_iso():
    return datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def main():
    ap = argparse.ArgumentParser(description="上传 APK 到 R2 云端应用库并重写清单")
    ap.add_argument("--account-id", default="889bd2a63b8928674c13e38148873176")
    ap.add_argument("--bucket", required=True)
    ap.add_argument("--apk", action="append", default=[], help="要上传的 APK（可给多个）")
    ap.add_argument("--name", default="", help="展示名（只对第一个 APK 生效）")
    ap.add_argument("--dry-run", action="store_true", help="只打印要传什么，不真的上传")
    args = ap.parse_args()

    ak = os.environ.get("R2_AK", "")
    sk = os.environ.get("R2_SK", "")
    if not ak or not sk:
        sys.exit("缺少凭据：请设置环境变量 R2_AK / R2_SK")
    if not args.apk:
        sys.exit("没东西可传：至少给一个 --apk")

    r2 = R2(args.account_id, ak, sk)

    # —— 1. 传 APK ——
    for idx, path in enumerate(args.apk):
        file_name = sanitize_file_name(os.path.basename(path))
        if not file_name:
            sys.exit(f"文件名不合法：{path}")
        if not file_name.lower().endswith(".apk"):
            sys.exit(f"只接受 .apk：{path}")
        with open(path, "rb") as f:
            data = f.read()
        digest = sha256_bytes(data)
        added_at = _now_iso()
        app_name = args.name.strip() if (idx == 0 and args.name.strip()) else file_name[:-4]
        key = PREFIX + file_name
        print(f"== 待上传：{key}（{len(data)} 字节，sha256 {digest}，展示名「{app_name}」）")
        if args.dry_run:
            continue
        # 元数据要参与签名（x-amz-* 头必须签），所以一起传给 request()
        code, body = r2.put(
            args.bucket, key, data,
            "application/vnd.android.package-archive",
            headers={
                "x-amz-meta-sha256": digest,
                "x-amz-meta-appname": encode_meta(app_name),
                "x-amz-meta-addedat": added_at,
            },
        )
        if code != 200:
            sys.exit(f"!! 上传失败 HTTP {code}：{body[:400].decode('utf-8', 'replace')}")
        print(f"== 上传成功：{key}")
        # 回读校验：传坏了就当场发现，别等车机上才出事
        gcode, gbody = r2.get(args.bucket, key)
        if gcode != 200:
            sys.exit(f"!! 回读失败 HTTP {gcode}")
        if len(gbody) != len(data) or sha256_bytes(gbody) != digest:
            sys.exit("!! 回读内容对不上")
        print(f"== 回读一致：{len(gbody)} 字节，sha256 {sha256_bytes(gbody)}")

    if args.dry_run:
        return

    # —— 2. 重写清单 ——
    doc = build_index(r2, args.bucket)
    payload = json_dumps(doc)
    code, body = r2.put(args.bucket, INDEX_KEY, payload, "application/json")
    if code != 200:
        sys.exit(f"!! 清单上传失败 HTTP {code}：{body[:400].decode('utf-8', 'replace')}")
    print(f"== 清单已重写：{INDEX_KEY}（{len(doc['items'])} 个条目，{len(payload)} 字节）")
    gcode, gbody = r2.get(args.bucket, INDEX_KEY)
    if gcode != 200 or gbody != payload:
        sys.exit("!! 清单回读不一致")
    print("== 清单回读一致")


def json_dumps(doc):
    """按固定字段顺序输出（和 Worker 的 JSON.stringify 结果逐字节可比）。"""
    items = []
    for it in doc["items"]:
        items.append({k: it[k] for k in ITEM_FIELDS})
    out = '{"schema":%d,"updatedAt":%s,"items":[' % (
        doc["schema"],
        json_str(doc["updatedAt"]),
    )
    out += ",".join(
        "{" + ",".join("%s:%s" % (json_str(k), json_val(v)) for k, v in it.items()) + "}"
        for it in items
    )
    out += "]}"
    return out.encode("utf-8")


def json_str(s):
    import json
    return json.dumps(str(s), ensure_ascii=False)


def json_val(v):
    import json
    if isinstance(v, bool):
        return "true" if v else "false"
    if isinstance(v, int):
        return str(v)
    return json.dumps(str(v), ensure_ascii=False)


if __name__ == "__main__":
    main()
