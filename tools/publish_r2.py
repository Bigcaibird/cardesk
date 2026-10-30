#!/usr/bin/env python3
"""把安装包和更新清单上传到 Cloudflare R2（第八十三轮）。

## 为什么不引三方库

App 与整个项目都是「零第三方依赖」的立场，这个脚本也一样：
AWS SigV4 签名手写就够了（~60 行），不必为一个签名去装 boto3。

## 凭据从哪来

**只从环境变量读**，脚本里、仓库里都不写密钥：

    export R2_AK=<Access Key ID>
    export R2_SK=<Secret Access Key>

R2 的密钥在 Cloudflare 控制台 R2 → Manage R2 API Tokens 里创建，
权限只勾「指定 bucket + Object Read & Write」。仓库是公开的，
密钥一旦进仓库等于公开。

## 用法

    python3 tools/publish_r2.py --bucket file --apk dist/CarDesk-ecarx-cardesk-4149.apk \
        --manifest dist/cardesk-latest.json

上传完会回读校验（长度 + SHA-256 对不上就报错退出）。
"""
import argparse
import hashlib
import hmac
import datetime
import os
import sys
import time
import urllib.error
import urllib.request
from urllib.parse import quote

REGION = "auto"
SERVICE = "s3"
ALGORITHM = "AWS4-HMAC-SHA256"


def _sign(key, msg):
    return hmac.new(key, msg.encode(), hashlib.sha256).digest()


class R2:
    def __init__(self, account_id, access_key, secret_key, host=None):
        self.ak = access_key
        self.sk = secret_key
        self.host = host or f"{account_id}.r2.cloudflarestorage.com"

    def _sign(self, method, path, query, payload, headers):
        """headers 里除 Host 之外的头都要参与签名（S3 只允许少签，不能多签）。"""
        t = datetime.datetime.now(datetime.timezone.utc)
        amzdate = t.strftime("%Y%m%dT%H%M%SZ")
        datestamp = t.strftime("%Y%m%d")
        ph = hashlib.sha256(payload).hexdigest()
        signed = {"host": self.host, "x-amz-content-sha256": ph, "x-amz-date": amzdate}
        for k, v in headers.items():
            signed[k.lower()] = v
        names = sorted(signed.keys())
        canonical_headers = "".join(f"{n}:{signed[n].strip()}\n" for n in names)
        signed_headers = ";".join(names)
        canonical_request = "\n".join([
            method, path, query, canonical_headers, signed_headers, ph,
        ])
        scope = f"{datestamp}/{REGION}/{SERVICE}/aws4_request"
        to_sign = "\n".join([
            ALGORITHM, amzdate, scope, hashlib.sha256(canonical_request.encode()).hexdigest(),
        ])
        k = _sign(("AWS4" + self.sk).encode(), datestamp)
        k = _sign(k, REGION)
        k = _sign(k, SERVICE)
        k = _sign(k, "aws4_request")
        sig = hmac.new(k, to_sign.encode(), hashlib.sha256).hexdigest()
        auth = (f"{ALGORITHM} Credential={self.ak}/{scope}, "
                f"SignedHeaders={signed_headers}, Signature={sig}")
        return auth, amzdate, ph

    def request(self, method, path, query="", payload=b"", headers=None, retries=4):
        headers = headers or {}
        url = f"https://{self.host}{path}" + (f"?{query}" if query else "")
        last = ""
        for attempt in range(retries):
            auth, amzdate, ph = self._sign(method, path, query, payload, headers)
            req = urllib.request.Request(url, data=payload if payload else None, method=method)
            req.add_header("Authorization", auth)
            req.add_header("x-amz-date", amzdate)
            req.add_header("x-amz-content-sha256", ph)
            for hk, hv in headers.items():
                req.add_header(hk, hv)
            try:
                with urllib.request.urlopen(req, timeout=120) as r:
                    return r.status, r.read()
            except urllib.error.HTTPError as e:
                body = e.read()
                if e.code in (500, 503) and attempt < retries - 1:
                    time.sleep(2)
                    continue
                return e.code, body
            except Exception as e:
                last = str(e)
                time.sleep(2)
        return 0, f"transport-error: {last}".encode()

    def put(self, bucket, key, data, content_type):
        code, body = self.request(
            "PUT", f"/{bucket}/{quote(key)}", payload=data,
            headers={"Content-Type": content_type},
        )
        return code, body

    def get(self, bucket, key):
        return self.request("GET", f"/{bucket}/{quote(key)}")


def sha256_bytes(data):
    return hashlib.sha256(data).hexdigest()


def main():
    ap = argparse.ArgumentParser(description="上传安装包与更新清单到 R2")
    ap.add_argument("--account-id", default="889bd2a63b8928674c13e38148873176")
    ap.add_argument("--bucket", required=True)
    ap.add_argument("--apk", default="", help="要上传的 APK")
    ap.add_argument("--manifest", default="", help="要上传的更新清单 JSON")
    ap.add_argument("--key-prefix", default="cardesk", help="APK 的对象 key 前缀")
    ap.add_argument("--manifest-key", default="cardesk/latest.json")
    ap.add_argument("--dry-run", action="store_true", help="只打印要传什么，不真的上传")
    args = ap.parse_args()

    ak = os.environ.get("R2_AK", "")
    sk = os.environ.get("R2_SK", "")
    if not ak or not sk:
        sys.exit("缺少凭据：请设置环境变量 R2_AK / R2_SK")
    r2 = R2(args.account_id, ak, sk)

    jobs = []
    if args.apk:
        name = os.path.basename(args.apk)
        jobs.append((f"{args.key_prefix}/{name}", args.apk, "application/vnd.android.package-archive"))
    if args.manifest:
        jobs.append((args.manifest_key, args.manifest, "application/json"))

    if not jobs:
        sys.exit("没东西可传：至少给 --apk 或 --manifest")

    for key, path, ctype in jobs:
        with open(path, "rb") as f:
            data = f.read()
        digest = sha256_bytes(data)
        print(f"== 待上传：{key}（{len(data)} 字节，sha256 {digest}）")
        if args.dry_run:
            continue
        code, body = r2.put(args.bucket, key, data, ctype)
        if code != 200:
            print(f"!! 上传失败 HTTP {code}：{body[:400].decode('utf-8', 'replace')}", file=sys.stderr)
            sys.exit(1)
        print(f"== 上传成功：{key}")
        # —— 回读校验 ——
        gcode, gbody = r2.get(args.bucket, key)
        if gcode != 200:
            print(f"!! 回读失败 HTTP {gcode}", file=sys.stderr)
            sys.exit(1)
        if len(gbody) != len(data) or sha256_bytes(gbody) != digest:
            print(f"!! 回读内容对不上：期望 {len(data)} 字节 / {digest}，实际 {len(gbody)} 字节 / {sha256_bytes(gbody)}", file=sys.stderr)
            sys.exit(1)
        print(f"== 回读一致：{len(gbody)} 字节，sha256 {sha256_bytes(gbody)}")

    print("\n上传完成。")


if __name__ == "__main__":
    main()
