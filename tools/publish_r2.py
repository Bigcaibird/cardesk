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
import datetime
import hashlib
import hmac
import json
import mimetypes
import os
import shutil
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET
from urllib.parse import quote, unquote

REGION = "auto"
SERVICE = "s3"
ALGORITHM = "AWS4-HMAC-SHA256"
S3_NS = "{http://s3.amazonaws.com/doc/2006-03-01/}"

WALLPAPER_PREFIX = "cardesk/wallpapers/"
WALLPAPER_STATIC_PREFIX = "cardesk/wallpapers/static/"
WALLPAPER_VIDEO_PREFIX = "cardesk/wallpapers/video/"
WALLPAPER_THUMB_PREFIX = "cardesk/wallpapers/thumbs/"
WALLPAPER_INDEX_KEY = "cardesk/wallpapers.json"
STATIC_EXTS = (".jpg", ".jpeg", ".png", ".webp")
VIDEO_EXTS = (".mp4", ".mkv", ".webm", ".mov", ".m4v", ".3gp")


def _sign(key, msg):
    return hmac.new(key, msg.encode(), hashlib.sha256).digest()


class R2:
    def __init__(self, account_id, access_key, secret_key, host=None):
        self.ak = access_key
        self.sk = secret_key
        self.host = host or f"{account_id}.r2.cloudflarestorage.com"
        # 最近一次响应的头（第八十四轮：读对象元数据 x-amz-meta-* 要用）
        self.last_headers = {}

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
                    self.last_headers = dict(r.headers.items())
                    return r.status, r.read()
            except urllib.error.HTTPError as e:
                body = e.read()
                self.last_headers = dict(e.headers.items()) if e.headers else {}
                if e.code in (500, 503) and attempt < retries - 1:
                    time.sleep(2)
                    continue
                return e.code, body
            except Exception as e:
                last = str(e)
                time.sleep(2)
        return 0, f"transport-error: {last}".encode()

    def put(self, bucket, key, data, content_type, headers=None):
        # 自定义头（比如 x-amz-meta-*）要一起参与签名：S3 只允许少签，不允许多签
        merged = {"Content-Type": content_type}
        merged.update(headers or {})
        code, body = self.request(
            "PUT", f"/{bucket}/{quote(key)}", payload=data,
            headers=merged,
        )
        return code, body

    def put_via_curl(self, bucket, key, path, content_type, headers=None):
        """大文件兜底：让 curl 重新签一次 SigV4 来 PUT。

        ## 为什么需要（第228轮）

        3.6MB+ 的 APK / 视频壁纸走 `urllib` 会反复 `Broken pipe` / `The write operation timed out`
        （`urlopen` 一次性把 body 全塞进 socket，中途被掐就没有断点）。
        同一个文件用 `curl --upload-file` 几秒就上去了。
        所以：`urllib` 报传输错误（code == 0）时，自动改用 curl 再传一次。
        """
        curl = shutil.which("curl")
        if not curl:
            return 0, b"transport-error: urllib \xe5\xa4\xb1\xe8\xb4\xa5\xef\xbc\x8ccurl \xe4\xb9\x9f\xe4\xb8\x8d\xe5\xad\x98\xe5\x9c\xa8"
        url = f"https://{self.host}/{bucket}/{quote(key)}"
        cmd = [
            curl, "-sS", "--retry", "3", "--retry-all-errors", "--max-time", "600",
            "-X", "PUT", "-H", f"Content-Type: {content_type}",
        ]
        for hk, hv in (headers or {}).items():
            cmd.extend(["-H", f"{hk}: {hv}"])
        cmd.extend([
            "--aws-sigv4", "aws:amz:auto:s3",
            "--user", f"{self.ak}:{self.sk}",
            "--upload-file", path,
            "-o", "/dev/null", "-w", "%{http_code}", url,
        ])
        try:
            out = subprocess.run(cmd, capture_output=True, text=True, timeout=660)
            code = int((out.stdout or "0").strip() or "0")
            return code, (out.stderr or "").encode()[:400]
        except Exception as e:
            return 0, f"transport-error: {e}".encode()

    def get(self, bucket, key):
        return self.request("GET", f"/{bucket}/{quote(key)}")

    def head(self, bucket, key):
        return self.request("HEAD", f"/{bucket}/{quote(key)}")

    def delete(self, bucket, key):
        return self.request("DELETE", f"/{bucket}/{quote(key)}")

    def list_objects(self, bucket, prefix):
        """S3 ListObjectsV2，返回 [(key, size)]。"""
        out = []
        token = ""
        while True:
            query = "list-type=2&prefix=" + quote(prefix, safe="")
            if token:
                query += "&continuation-token=" + quote(token, safe="")
            code, body = self.request("GET", f"/{bucket}/", query=query)
            if code != 200:
                raise RuntimeError(f"列出对象失败 HTTP {code}：{body[:400].decode('utf-8', 'replace')}")
            root = ET.fromstring(body.decode("utf-8"))
            for node in root.findall(f"{S3_NS}Contents"):
                key = node.findtext(f"{S3_NS}Key") or ""
                size = int(node.findtext(f"{S3_NS}Size") or "0")
                out.append((key, size))
            truncated = (root.findtext(f"{S3_NS}IsTruncated") or "").lower() == "true"
            token = root.findtext(f"{S3_NS}NextContinuationToken") or ""
            if not truncated or not token:
                break
        return out


def sha256_bytes(data):
    return hashlib.sha256(data).hexdigest()


def _now_iso():
    return datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def _encode_meta(value):
    return quote(str(value).encode("utf-8"), safe="")


def _decode_meta(value):
    if not value:
        return ""
    try:
        return unquote(value, encoding="utf-8", errors="strict")
    except Exception:
        return str(value)


def _sanitize_file_name(raw):
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


def _detect_wallpaper_type(path, explicit="auto"):
    if explicit in ("static", "video"):
        return explicit
    low = path.lower()
    if low.endswith(VIDEO_EXTS):
        return "video"
    if low.endswith(STATIC_EXTS):
        return "static"
    raise ValueError(f"无法识别壁纸类型（支持的静态 {STATIC_EXTS} / 动态 {VIDEO_EXTS}）：{path}")


def _guess_content_type(path, wp_type):
    guessed, _ = mimetypes.guess_type(path)
    if guessed:
        return guessed
    return "video/mp4" if wp_type == "video" else "image/jpeg"


def _extract_video_thumb(video_path):
    """自动给动态壁纸抽缩略图：优先 ffmpeg，macOS 无 ffmpeg 时回退系统自带 qlmanage + sips。"""
    ffmpeg = shutil.which("ffmpeg")
    if ffmpeg:
        tmp = tempfile.NamedTemporaryFile(prefix="wp-thumb-", suffix=".jpg", delete=False)
        tmp.close()
        cmd = [
            ffmpeg, "-y", "-ss", "00:00:01", "-i", video_path,
            "-frames:v", "1", "-vf", "scale=480:-2", "-q:v", "4", tmp.name,
        ]
        try:
            r = subprocess.run(cmd, capture_output=True, timeout=20)
            if r.returncode == 0 and os.path.getsize(tmp.name) > 0:
                return tmp.name
        except Exception:
            pass
        try:
            os.unlink(tmp.name)
        except OSError:
            pass

    qlmanage = shutil.which("qlmanage")
    if not qlmanage:
        return None
    tmp_dir = tempfile.mkdtemp(prefix="wp-ql-")
    try:
        r = subprocess.run(
            [qlmanage, "-t", "-s", "640", "-o", tmp_dir, video_path],
            capture_output=True,
            timeout=20,
        )
        png_path = os.path.join(tmp_dir, os.path.basename(video_path) + ".png")
        if r.returncode != 0 or not os.path.isfile(png_path) or os.path.getsize(png_path) <= 0:
            return None
        sips = shutil.which("sips")
        if sips:
            jpg_tmp = tempfile.NamedTemporaryFile(prefix="wp-thumb-", suffix=".jpg", delete=False)
            jpg_tmp.close()
            r2 = subprocess.run(
                [sips, "-s", "format", "jpeg", "-s", "formatOptions", "82", png_path, "--out", jpg_tmp.name],
                capture_output=True,
                timeout=20,
            )
            if r2.returncode == 0 and os.path.getsize(jpg_tmp.name) > 0:
                return jpg_tmp.name
            try:
                os.unlink(jpg_tmp.name)
            except OSError:
                pass
        png_tmp = tempfile.NamedTemporaryFile(prefix="wp-thumb-", suffix=".png", delete=False)
        png_tmp.close()
        shutil.copyfile(png_path, png_tmp.name)
        return png_tmp.name
    except Exception:
        return None
    finally:
        shutil.rmtree(tmp_dir, ignore_errors=True)


def _upload_and_verify(r2, bucket, key, path, content_type, headers=None, dry_run=False):
    with open(path, "rb") as f:
        data = f.read()
    digest = sha256_bytes(data)
    print(f"== 待上传：{key}（{len(data)} 字节，sha256 {digest}）")
    if dry_run:
        return len(data), digest
    code, body = r2.put(bucket, key, data, content_type, headers=headers)
    if code == 0 and len(data) > 512 * 1024:
        print(f"== urllib 传输失败，改用 curl 重传：{key}")
        code, body = r2.put_via_curl(bucket, key, path, content_type, headers=headers)
    if code != 200:
        print(f"!! 上传失败 HTTP {code}：{body[:400].decode('utf-8', 'replace')}", file=sys.stderr)
        sys.exit(1)
    print(f"== 上传成功：{key}")
    # 回读校验：大于 10MB 时做 HEAD 校验长度 + 摘要元数据，否则全量 GET 校验 SHA-256
    if len(data) > 10 * 1024 * 1024:
        hcode, _ = r2.head(bucket, key)
        clen = int(r2.last_headers.get("Content-Length") or r2.last_headers.get("content-length") or "0")
        if hcode != 200 or clen != len(data):
            print(f"!! HEAD 校验失败 HTTP {hcode} length={clen} expect={len(data)}", file=sys.stderr)
            sys.exit(1)
        print(f"== HEAD 回读一致：{clen} 字节")
    else:
        gcode, gbody = r2.get(bucket, key)
        if gcode != 200:
            print(f"!! 回读失败 HTTP {gcode}", file=sys.stderr)
            sys.exit(1)
        if len(gbody) != len(data) or sha256_bytes(gbody) != digest:
            print(f"!! 回读内容对不上：期望 {len(data)} 字节 / {digest}，实际 {len(gbody)} 字节 / {sha256_bytes(gbody)}", file=sys.stderr)
            sys.exit(1)
        print(f"== 回读一致：{len(gbody)} 字节，sha256 {sha256_bytes(gbody)}")
    return len(data), digest


def _head_meta(r2, bucket, key):
    code, _ = r2.head(bucket, key)
    if code != 200:
        return {}
    meta = {}
    for k, v in r2.last_headers.items():
        if k.lower().startswith("x-amz-meta-"):
            meta[k.lower()[len("x-amz-meta-"):]] = v
    return meta


def rebuild_wallpaper_index(r2, bucket, dry_run=False):
    """扫描 R2 `cardesk/wallpapers/{static,video}/`，重写 `cardesk/wallpapers.json`。"""
    items = []
    thumbs = {k for k, _ in r2.list_objects(bucket, WALLPAPER_THUMB_PREFIX)}
    for wp_type, prefix, exts in (
        ("static", WALLPAPER_STATIC_PREFIX, STATIC_EXTS),
        ("video", WALLPAPER_VIDEO_PREFIX, VIDEO_EXTS),
    ):
        for key, size in r2.list_objects(bucket, prefix):
            low = key.lower()
            if not low.endswith(exts):
                continue
            file_name = key[len(prefix):]
            if not file_name or "/" in file_name:
                continue
            meta = _head_meta(r2, bucket, key)
            stem = os.path.splitext(file_name)[0]
            thumb_key = _decode_meta(meta.get("thumbkey"))
            if not thumb_key:
                for cand in (
                    f"{WALLPAPER_THUMB_PREFIX}{stem}.jpg",
                    f"{WALLPAPER_THUMB_PREFIX}{stem}.png",
                    f"{WALLPAPER_THUMB_PREFIX}{stem}.webp",
                ):
                    if cand in thumbs:
                        thumb_key = cand
                        break
            items.append({
                "name": _decode_meta(meta.get("wpname")) or stem,
                "type": meta.get("wptype") or wp_type,
                "file": file_name,
                "key": key,
                "thumbKey": thumb_key or (key if wp_type == "static" else ""),
                "size": size,
                "sha256": (meta.get("sha256") or "").lower(),
                "addedAt": meta.get("addedat") or "",
            })
    items.sort(key=lambda it: it.get("addedAt") or "", reverse=True)
    doc = {"schema": 1, "updatedAt": _now_iso(), "items": items}
    payload = json.dumps(doc, ensure_ascii=False, indent=2).encode("utf-8") + b"\n"
    print(f"== 在线壁纸清单：{WALLPAPER_INDEX_KEY}（{len(items)} 条，{len(payload)} 字节）")
    if dry_run:
        print(payload.decode("utf-8"))
        return doc
    code, body = r2.put(bucket, WALLPAPER_INDEX_KEY, payload, "application/json")
    if code != 200:
        print(f"!! 壁纸清单上传失败 HTTP {code}：{body[:400].decode('utf-8', 'replace')}", file=sys.stderr)
        sys.exit(1)
    gcode, gbody = r2.get(bucket, WALLPAPER_INDEX_KEY)
    if gcode != 200 or gbody != payload:
        print("!! 壁纸清单回读不一致", file=sys.stderr)
        sys.exit(1)
    print(f"== 壁纸清单回读一致（{len(items)} 条）")
    return doc


def _collect_wallpaper_files(files, directory):
    out = list(files)
    if directory:
        if not os.path.isdir(directory):
            sys.exit(f"壁纸目录不存在：{directory}")
        for entry in sorted(os.listdir(directory)):
            full = os.path.join(directory, entry)
            if os.path.isfile(full) and entry.lower().endswith(STATIC_EXTS + VIDEO_EXTS):
                out.append(full)
    return out


def main():
    ap = argparse.ArgumentParser(description="上传安装包/更新清单或静态/动态壁纸资源到 R2")
    ap.add_argument("--account-id", default="889bd2a63b8928674c13e38148873176")
    ap.add_argument("--bucket", required=True)
    ap.add_argument("--apk", default="", help="要上传的 APK")
    ap.add_argument("--manifest", default="", help="要上传的更新清单 JSON")
    ap.add_argument(
        "--channel",
        default="stable",
        choices=["stable", "preview"],
        help="更新通道：stable=正式版（默认，cardesk/）/ preview=开发版（cardesk/preview/）",
    )
    ap.add_argument(
        "--key-prefix",
        default="",
        help="APK 的对象 key 前缀（默认按 --channel：cardesk 或 cardesk/preview）",
    )
    ap.add_argument(
        "--manifest-key",
        default="",
        help="清单的对象 key（默认按 --channel：cardesk/latest.json 或 cardesk/preview/latest.json）",
    )
    # ---- 在线壁纸资源上传 ----
    ap.add_argument("--wallpaper", action="append", default=[], help="要上传的壁纸文件（静态图或动态视频，可重复指定）")
    ap.add_argument("--wallpaper-dir", default="", help="批量上传指定目录下的所有静态/动态壁纸")
    ap.add_argument(
        "--wallpaper-type",
        default="auto",
        choices=["auto", "static", "video"],
        help="壁纸类型：auto=按扩展名自动判断（默认）/ static=静态壁纸 / video=动态壁纸",
    )
    ap.add_argument("--wallpaper-name", default="", help="展示名称（仅对第一个 --wallpaper 生效，默认用文件名）")
    ap.add_argument("--thumb", default="", help="为第一个动态壁纸指定预览缩略图（不传且装了 ffmpeg 时自动抽帧）")
    ap.add_argument("--delete-wallpaper", action="append", default=[], help="删除 R2 上指定壁纸（完整 key）并重建清单")
    ap.add_argument("--rebuild-wallpaper-index", action="store_true", help="扫描 R2 现有壁纸并重写 cardesk/wallpapers.json")
    ap.add_argument("--dry-run", action="store_true", help="只打印要传什么，不真的上传")
    args = ap.parse_args()

    # 第三百零九轮：两条通道各自一套前缀 —— 正式版在 cardesk/，
    # 开发版整体挪进 cardesk/preview/（清单 + 安装包同一层，互不覆盖）。
    if args.channel == "preview":
        args.key_prefix = args.key_prefix or "cardesk/preview"
        args.manifest_key = args.manifest_key or "cardesk/preview/latest.json"
    else:
        args.key_prefix = args.key_prefix or "cardesk"
        args.manifest_key = args.manifest_key or "cardesk/latest.json"

    ak = os.environ.get("R2_AK", "")
    sk = os.environ.get("R2_SK", "")
    if not ak or not sk:
        if not args.dry_run:
            sys.exit("缺少凭据：请设置环境变量 R2_AK / R2_SK")
    r2 = R2(args.account_id, ak, sk)

    wp_files = _collect_wallpaper_files(args.wallpaper, args.wallpaper_dir)
    did_anything = False

    # ---- 1. 安装包 / 更新清单 ----
    jobs = []
    if args.apk:
        name = os.path.basename(args.apk)
        jobs.append((f"{args.key_prefix}/{name}", args.apk, "application/vnd.android.package-archive"))
    if args.manifest:
        jobs.append((args.manifest_key, args.manifest, "application/json"))

    for key, path, ctype in jobs:
        did_anything = True
        _upload_and_verify(r2, args.bucket, key, path, ctype, dry_run=args.dry_run)

    # ---- 2. 删除壁纸 ----
    for del_key in args.delete_wallpaper:
        did_anything = True
        print(f"== 待删除壁纸：{del_key}")
        if not args.dry_run:
            code, body = r2.delete(args.bucket, del_key)
            if code not in (200, 204):
                sys.exit(f"!! 删除失败 HTTP {code}：{body[:400].decode('utf-8', 'replace')}")
            print(f"== 已删除：{del_key}")

    # ---- 3. 上传壁纸资源 ----
    for idx, path in enumerate(wp_files):
        did_anything = True
        if not os.path.isfile(path):
            sys.exit(f"壁纸文件不存在：{path}")
        wp_type = _detect_wallpaper_type(path, args.wallpaper_type)
        file_name = _sanitize_file_name(os.path.basename(path))
        if not file_name:
            sys.exit(f"壁纸文件名不合法：{path}")
        stem = os.path.splitext(file_name)[0]
        display_name = args.wallpaper_name.strip() if (idx == 0 and args.wallpaper_name.strip()) else stem
        prefix = WALLPAPER_VIDEO_PREFIX if wp_type == "video" else WALLPAPER_STATIC_PREFIX
        key = prefix + file_name
        ctype = _guess_content_type(path, wp_type)

        thumb_key = ""
        auto_thumb_tmp = None
        if wp_type == "video":
            thumb_src = args.thumb if (idx == 0 and args.thumb) else None
            if not thumb_src and not args.dry_run:
                auto_thumb_tmp = _extract_video_thumb(path)
                thumb_src = auto_thumb_tmp
            if thumb_src:
                if not os.path.isfile(thumb_src):
                    sys.exit(f"缩略图不存在：{thumb_src}")
                thumb_ext = os.path.splitext(thumb_src)[1].lower() or ".jpg"
                thumb_key = f"{WALLPAPER_THUMB_PREFIX}{stem}{thumb_ext}"
                thumb_ct = _guess_content_type(thumb_src, "static")
                _upload_and_verify(r2, args.bucket, thumb_key, thumb_src, thumb_ct, dry_run=args.dry_run)
                if auto_thumb_tmp:
                    try:
                        os.unlink(auto_thumb_tmp)
                    except OSError:
                        pass
        else:
            thumb_key = key

        with open(path, "rb") as f:
            digest = sha256_bytes(f.read())
        headers = {
            "x-amz-meta-sha256": digest,
            "x-amz-meta-wpname": _encode_meta(display_name),
            "x-amz-meta-wptype": wp_type,
            "x-amz-meta-thumbkey": _encode_meta(thumb_key),
            "x-amz-meta-addedat": _now_iso(),
        }
        print(f"== 壁纸信息：类型={wp_type} 名称=「{display_name}」 缩略图={thumb_key or '无'}")
        _upload_and_verify(r2, args.bucket, key, path, ctype, headers=headers, dry_run=args.dry_run)

    # ---- 4. 重建在线壁纸清单 ----
    if wp_files or args.delete_wallpaper or args.rebuild_wallpaper_index:
        did_anything = True
        if not args.dry_run:
            rebuild_wallpaper_index(r2, args.bucket, dry_run=False)
        else:
            print(f"== [dry-run] 将扫描 {WALLPAPER_PREFIX} 并重写 {WALLPAPER_INDEX_KEY}")

    if not did_anything:
        sys.exit("没东西可传：请传 --apk / --manifest / --wallpaper / --wallpaper-dir / --rebuild-wallpaper-index")

    print("\n上传完成。")


if __name__ == "__main__":
    main()
