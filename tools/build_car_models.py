#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把内置车模打包成资源包、生成远端索引（可选上传对象存储）。

## 为什么要有这个脚本

车模图片（`app/src/main/assets/car3d/<dir>/`，每个 360K~430K）不跟着 APK 走，
而是作为**独立资源包**放到公开对象存储上，App 按车型单独下载/更新（见
`core/car3d/CarModelRemote.kt`）。这里负责把它们打成可直接下载的产物：

    dist/car-models/<dir>-v<n>.zip     # 一个车型一个包
    dist/car-models/index.json         # App 读取的索引（含每个包的 sha256/size）
    dist/car-models/versions.json      # 本地产物版本记录（下次自增用）

## 确定性 ZIP

同样的源文件重复打包必须得到**逐字节相同**的 zip（否则 sha256 每次都变，
「有没有更新」就说不清了）。所以：条目名排序、时间戳固定 1980-01-01、
不写目录项、固定 `create_system`/权限位、`ZIP_DEFLATED`。

## 用法

    # 只打包 + 打印清单（不写 versions.json、不上传）
    python3 tools/build_car_models.py --dry-run

    # 打包并把 zip 与 index.json 传到 cardesk/car-models/
    export R2_AK=... R2_SK=...
    python3 tools/build_car_models.py --upload --bucket file

凭据**只从环境变量读**（与 `tools/publish_r2.py` 一致，仓库里不写密钥）；
没有凭据时 `--upload` 直接报错退出，不会静默跳过上传。

产物对象布局（bucket `file`，域名 `file.chuxi.bid`）：

    cardesk/car-models/index.json
    cardesk/car-models/lynk01-v1.zip
    cardesk/car-models/lynk03-v1.zip
    cardesk/car-models/lynk05-v1.zip
"""

import argparse
import datetime
import hashlib
import json
import os
import sys
import zipfile

# 与 App 端 CarModelRemote.FORMAT 必须一致：改了格式两边要一起发
FORMAT = "cardesk.carmodels/1"
# 索引结构版本（加字段时 +1，App 目前只读不校验它）
INDEX_VERSION = 1

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DEFAULT_ASSETS = os.path.join(REPO_ROOT, "app", "src", "main", "assets", "car3d")
DEFAULT_OUT = os.path.join(REPO_ROOT, "dist", "car-models")
DEFAULT_ACCOUNT_ID = "889bd2a63b8928674c13e38148873176"
DEFAULT_BUCKET = "file"
PREFIX = "cardesk/car-models"

# 固定时间戳（ZIP 能表示的最早时间），保证同样的输入打出同样的字节
ZIP_TIMESTAMP = (1980, 1, 1, 0, 0, 0)


# --------------------------------------------------------------------------- 基础工具


def sha256_bytes(data):
    return hashlib.sha256(data).hexdigest()


def sha256_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def human_size(n):
    if n >= 1024 * 1024:
        return "%.1fM" % (n / (1024.0 * 1024.0))
    if n >= 1024:
        return "%dK" % max(1, round(n / 1024.0))
    return "%dB" % n


def read_json(path, default):
    try:
        with open(path, "r", encoding="utf-8") as f:
            return json.load(f)
    except (OSError, ValueError):
        return default


def write_json_atomic(path, obj):
    """先写临时文件再 rename：脚本中途失败不会留下半个 index.json。"""
    os.makedirs(os.path.dirname(path), exist_ok=True)
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(obj, f, ensure_ascii=False, indent=2)
        f.write("\n")
    os.replace(tmp, path)


# --------------------------------------------------------------------------- 打包


def discover_models(assets_dir):
    """列出所有含 model.json 的车型目录（目录名即 App 侧 dir）。"""
    if not os.path.isdir(assets_dir):
        sys.exit("找不到车模资产目录：%s" % assets_dir)
    dirs = []
    for name in sorted(os.listdir(assets_dir)):
        path = os.path.join(assets_dir, name)
        if os.path.isdir(path) and os.path.isfile(os.path.join(path, "model.json")):
            dirs.append(name)
    if not dirs:
        sys.exit("车模资产目录里没有任何车型：%s" % assets_dir)
    return dirs


def collect_files(src_dir):
    """收集 (包内相对路径, 绝对路径)，按相对路径排序（普通话机/文件系统顺序一致）。"""
    files = []
    for root, dirnames, filenames in os.walk(src_dir):
        dirnames.sort()
        for name in sorted(filenames):
            if name == ".DS_Store":
                continue
            abs_path = os.path.join(root, name)
            rel = os.path.relpath(abs_path, src_dir).replace(os.sep, "/")
            files.append((rel, abs_path))
    files.sort(key=lambda item: item[0])
    return files


def build_zip(src_dir, out_path):
    """确定性 zip：条目名排序、固定时间戳、不写目录项、统一权限位。"""
    files = collect_files(src_dir)
    if not files:
        sys.exit("车型目录是空的：%s" % src_dir)
    tmp = out_path + ".tmp"
    with zipfile.ZipFile(tmp, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as zf:
        for rel, abs_path in files:
            info = zipfile.ZipInfo(rel, date_time=ZIP_TIMESTAMP)
            info.compress_type = zipfile.ZIP_DEFLATED
            # 固定成 DOS 属性：跨平台/跨机器打出来的头一致
            info.create_system = 0
            info.external_attr = 0
            with open(abs_path, "rb") as f:
                zf.writestr(info, f.read(), compresslevel=9)
    os.replace(tmp, out_path)
    return files


def model_display_name(src_dir, dir_name):
    """展示名取 model.json 里的 name（中文可保留）；没有就用目录名。"""
    data = read_json(os.path.join(src_dir, "model.json"), {})
    if isinstance(data, dict):
        name = data.get("name")
        if isinstance(name, str) and name.strip():
            return name.strip()
    return dir_name


def next_version(out_dir, dir_name, explicit_version):
    """版本号：--version 显式指定；否则读 dist/car-models/versions.json 自增。"""
    if explicit_version is not None:
        return explicit_version
    versions = read_json(os.path.join(out_dir, "versions.json"), {})
    if not isinstance(versions, dict):
        versions = {}
    try:
        return int(versions.get(dir_name, 0)) + 1
    except (TypeError, ValueError):
        return 1


def build(args):
    assets_dir = os.path.abspath(args.assets_dir)
    out_dir = os.path.abspath(args.out_dir)
    os.makedirs(out_dir, exist_ok=True)

    models = []
    for dir_name in discover_models(assets_dir):
        src_dir = os.path.join(assets_dir, dir_name)
        version = next_version(out_dir, dir_name, args.version)
        zip_name = "%s-v%d.zip" % (dir_name, version)
        zip_path = os.path.join(out_dir, zip_name)
        files = build_zip(src_dir, zip_path)
        size = os.path.getsize(zip_path)
        digest = sha256_file(zip_path)
        file_entries = [
            {"name": rel, "sha256": sha256_file(abs_path), "bytes": os.path.getsize(abs_path)}
            for rel, abs_path in files
        ]
        models.append(
            {
                "dir": dir_name,
                "name": model_display_name(src_dir, dir_name),
                "version": version,
                "size": size,
                "sha256": digest,
                "url": "%s/%s" % (PREFIX, zip_name),
                "files": file_entries,
                "_zip_path": zip_path,
            }
        )

    today = datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%d")
    index = {
        "format": FORMAT,
        "version": INDEX_VERSION,
        "updated": today,
        "models": [
            {
                "dir": m["dir"],
                "name": m["name"],
                "version": m["version"],
                "size": m["size"],
                "sha256": m["sha256"],
                "url": m["url"],
                "files": m["files"],
            }
            for m in models
        ],
    }
    index_path = os.path.join(out_dir, "index.json")
    write_json_atomic(index_path, index)

    return models, index_path


# --------------------------------------------------------------------------- 上传


def upload_objects(models, index_path, bucket, account_id):
    """复用发布脚本的 SigV4 实现；上传后回读校验长度与 sha256。"""
    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
    try:
        import publish_r2
    except ImportError as exc:  # 理论上不会：脚本就在同目录
        sys.exit("无法导入 tools/publish_r2.py：%s" % exc)

    ak = os.environ.get("R2_AK", "").strip()
    sk = os.environ.get("R2_SK", "").strip()
    if not ak or not sk:
        sys.exit(
            "缺少凭据：请设置环境变量 R2_AK / R2_SK（只从环境变量读，仓库里不写密钥）"
        )

    r2 = publish_r2.R2(account_id, ak, sk)
    targets = [(m["url"], m["_zip_path"], m["size"], m["sha256"]) for m in models]
    index_size = os.path.getsize(index_path)
    index_sha = sha256_file(index_path)
    targets.append((PREFIX + "/index.json", index_path, index_size, index_sha))

    for key, path, size, sha in targets:
        content_type = (
            "application/json" if key.endswith(".json") else "application/zip"
        )
        with open(path, "rb") as f:
            data = f.read()
        code, body = r2.put(bucket, key, data, content_type)
        if code != 200:
            # 大文件走 urllib 偶发失败时退回 curl（publish_r2 里已有实现）
            code, body = r2.put_via_curl(bucket, key, path, content_type)
        if code != 200:
            sys.exit("上传失败：%s（HTTP %s：%s）" % (key, code, body[:200]))

        # 回读校验：CDN 后面的对象读回来必须就是刚传的那份
        code, got = r2.get(bucket, key)
        if code != 200:
            sys.exit("回读失败：%s（HTTP %s）" % (key, code))
        if len(got) != size or sha256_bytes(got) != sha:
            sys.exit("回读校验失败：%s（长度或 sha256 与本地不一致）" % key)
        print("== 已上传并校验：%s（%s，sha256=%s）" % (key, human_size(size), sha))


# --------------------------------------------------------------------------- main


def main(argv=None):
    parser = argparse.ArgumentParser(
        description="打包内置车模为确定性 zip，生成 index.json，可选上传对象存储",
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    parser.add_argument(
        "--assets-dir",
        default=DEFAULT_ASSETS,
        help="车模资产根目录（默认 app/src/main/assets/car3d）",
    )
    parser.add_argument(
        "--out-dir",
        default=DEFAULT_OUT,
        help="产物输出目录（默认 dist/car-models）",
    )
    parser.add_argument(
        "--version",
        type=int,
        default=None,
        help="本次发布的版本号；不给则按 dist/car-models/versions.json 自增",
    )
    parser.add_argument(
        "--upload",
        action="store_true",
        help="把 zip 与 index.json 上传到对象存储（需要 R2_AK / R2_SK）",
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="只打包并打印清单：不写 versions.json、不上传",
    )
    parser.add_argument("--bucket", default=DEFAULT_BUCKET, help="R2 bucket（默认 file）")
    parser.add_argument(
        "--account-id",
        default=DEFAULT_ACCOUNT_ID,
        help="R2 账号 id（默认值与既有发布脚本一致）",
    )
    args = parser.parse_args(argv)

    print("== 车模资产：%s" % os.path.abspath(args.assets_dir))
    models, index_path = build(args)
    out_dir = os.path.abspath(args.out_dir)

    # 记录版本（dry-run 不写：保持「看一眼」不改变下次发布的版本号）
    if not args.dry_run:
        versions = read_json(os.path.join(out_dir, "versions.json"), {})
        if not isinstance(versions, dict):
            versions = {}
        for m in models:
            versions[m["dir"]] = m["version"]
        write_json_atomic(os.path.join(out_dir, "versions.json"), versions)

    print("== 打包完成%s" % ("（dry-run：未上传、未写 versions.json）" if args.dry_run else ""))
    for m in models:
        print(
            "==   %-8s v%-3d %-6s 共 %d 个文件  sha256=%s"
            % (m["dir"], m["version"], human_size(m["size"]), len(m["files"]), m["sha256"])
        )
        print("==     远端对象：%s" % m["url"])
    print("== 索引：%s（%d 个车模）" % (index_path, len(models)))
    print("==   远端对象：%s/index.json" % PREFIX)
    print("==   大小：%s  sha256=%s" % (human_size(os.path.getsize(index_path)), sha256_file(index_path)))

    if args.upload and not args.dry_run:
        upload_objects(models, index_path, args.bucket, args.account_id)
    elif args.upload and args.dry_run:
        print("== --dry-run 与 --upload 同时给出：以 dry-run 为准，未上传")
    return 0


if __name__ == "__main__":
    sys.exit(main())
