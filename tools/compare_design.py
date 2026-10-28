#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
逐屏比对「设计板」与「离屏渲染」，把差异量化并生成左右对照图 + 差异热力图。

用法：
    python3 compare_design.py                    # 夜间，全部 14 屏
    python3 compare_design.py --theme day        # 日间，全部 14 屏
    python3 compare_design.py s01 s04            # 只比对指定屏（前缀即可，如 s01-home）
    python3 compare_design.py --out <dir>        # 换输出目录

映射关系（设计板 ↔ 预览）：

| 设计板 | 夜间预览 | 日间预览 | 旧预览（仅夜间） |
| --- | --- | --- | --- |
| s01-home | d01-home.png | e01-home.png | 01-home.png |
| s02-nav | d02-nav.png | e02-nav.png | 02-nav.png |
| s03-apps | d03-apps.png | e03-apps.png | 03-apps.png |
| s04-hvac | d04-hvac.png | e04-hvac.png | 07-climate.png |
| s05-settings | d05-settings.png | e05-settings.png | 06-settings.png |
| s06-tasks | d06-tasks.png | e06-tasks.png | 14-tasks.png |
| s07-taskedit | d07-taskedit.png | e07-taskedit.png | 15-task-editor.png |
| s08-picker | d08-picker.png | e08-picker.png | 17-app-picker.png |
| s09-color | d09-color.png | e09-color.png | 16-color.png |
| s10-keys | d10-keys.png | e10-keys.png | 18-keys.png |
| s11-slots | d11-slots.png | e11-slots.png | 13-navslot-dialog.png |
| s12-float | d12-float.png | e12-float.png | 19-floats.png |
| s13-quickapps | d13-quickapps.png | e13-quickapps.png | 20-quickapps.png |
| s14-adb | d14-adb.png | e14-adb.png | （新，无旧预览） |

依赖：Pillow（系统自带即可，不需要 numpy）。
"""
import os
import sys

from PIL import Image, ImageChops, ImageDraw, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DESIGN_DIR = os.path.join(ROOT, "design", "png")
PREVIEW_DIR = os.path.join(ROOT, "preview")

# (设计板名, 预览基名, 旧预览基名)
PAIRS = [
    ("s01-home", "01-home", "01-home"),
    ("s02-nav", "02-nav", "02-nav"),
    ("s03-apps", "03-apps", "03-apps"),
    ("s04-hvac", "04-hvac", "07-climate"),
    ("s05-settings", "05-settings", "06-settings"),
    ("s06-tasks", "06-tasks", "14-tasks"),
    ("s07-taskedit", "07-taskedit", "15-task-editor"),
    ("s08-picker", "08-picker", "17-app-picker"),
    ("s09-color", "09-color", "16-color"),
    ("s10-keys", "10-keys", "18-keys"),
    ("s11-slots", "11-slots", "13-navslot-dialog"),
    ("s12-float", "12-float", "19-floats"),
    ("s13-quickapps", "13-quickapps", "20-quickapps"),
    ("s14-adb", "14-adb", "14-adb"),
]

FONT_CJK = "/System/Library/Fonts/Hiragino Sans GB.ttc"


def load_font(size):
    try:
        return ImageFont.truetype(FONT_CJK, size)
    except Exception:
        return ImageFont.load_default()


def mean_diff(a, b):
    """平均绝对差（0..255）。"""
    d = ImageChops.difference(a, b).convert("L")
    hist = d.histogram()
    total = sum(i * c for i, c in enumerate(hist))
    return total / float(sum(hist) or 1)


def diff_bbox(a, b, threshold=40):
    """差异超过阈值的像素区域包围盒。"""
    d = ImageChops.difference(a, b).convert("L")
    return d.point(lambda v: 255 if v > threshold else 0).getbbox()


def compare(name, design_file, preview_file, out_dir):
    dp = os.path.join(DESIGN_DIR, design_file)
    pp = os.path.join(PREVIEW_DIR, preview_file)
    if not os.path.exists(dp):
        return None
    if not os.path.exists(pp):
        return (name, "缺少预览", preview_file, None)

    a = Image.open(dp).convert("RGB")
    b = Image.open(pp).convert("RGB")
    if a.size != b.size:
        b = b.resize(a.size)

    m = mean_diff(a, b)
    bbox = diff_bbox(a, b)

    # 左右对照图 + 差异热力图
    w, h = 640, 360
    side = Image.new("RGB", (w * 2 + 18, h + 34), (18, 18, 20))
    side.paste(a.resize((w, h)), (0, 34))
    side.paste(b.resize((w, h)), (w + 18, 34))
    dr = ImageDraw.Draw(side)
    f = load_font(17)
    dr.text((6, 6), "DESIGN " + design_file, font=f, fill=(255, 214, 120))
    dr.text((w + 24, 6), "PREVIEW " + preview_file, font=f, fill=(120, 214, 255))
    tag = os.path.splitext(preview_file)[0]
    side.save(os.path.join(out_dir, "%s-%s-side.jpg" % (name, tag)), quality=88)

    heat = ImageChops.difference(a, b).convert("L").point(lambda v: 255 if v > 40 else 0)
    heat = heat.convert("RGB")
    hot = Image.new("RGB", heat.size, (0, 0, 0))
    hp = hot.load()
    src = heat.load()
    for y in range(0, heat.size[1], 2):
        for x in range(0, heat.size[0], 2):
            if src[x, y][0] > 0:
                for dy in range(2):
                    for dx in range(2):
                        if x + dx < hot.size[0] and y + dy < hot.size[1]:
                            hp[x + dx, y + dy] = (255, 60, 40)
    hot.resize((w, h)).save(os.path.join(out_dir, "%s-%s-diff.png" % (name, tag)))

    return (name, "mean=%.2f" % m, bbox, "%s-%s-side.jpg" % (name, tag))


def main():
    out_dir = "/tmp/design-diff"
    theme = "night"
    args = []
    legacy = False
    skip_next = False
    for i, a in enumerate(sys.argv[1:]):
        if skip_next:
            skip_next = False
            continue
        if a == "--out" and i + 2 < len(sys.argv):
            out_dir = sys.argv[i + 2]
            skip_next = True
            continue
        if a == "--theme" and i + 2 < len(sys.argv):
            theme = sys.argv[i + 2]
            skip_next = True
            continue
        if a == "--legacy":
            legacy = True
            continue
        if a.startswith("--"):
            continue
        args.append(a)
    os.makedirs(out_dir, exist_ok=True)

    wanted = None
    if args:
        wanted = set(args)

    prefix = "d" if theme == "night" else "e"
    print("主题：%s（预览前缀 %s）" % (theme, prefix))
    print("%-14s %-12s %-28s %s" % ("SCREEN", "DIFF", "HOTSPOT BBOX", "SIDE"))
    print("-" * 78)
    rows = []
    for name, base, old_base in PAIRS:
        if wanted and not any(name.startswith(w) for w in wanted):
            continue
        candidates = ["%s%s.png" % (prefix, base)]
        if theme == "night" and legacy:
            # 旧版空态预览只在夜间存在，且早已过期（空数据），
            # 默认不比；需要时用 --legacy 打开。
            candidates.append("%s.png" % old_base)
        for preview in candidates:
            r = compare(name, "%s-%s.png" % (name, theme), preview, out_dir)
            if r is None:
                continue
            rows.append(r)
            print("%-14s %-12s %-28s %s" % (r[0], r[1], str(r[2]), r[3]))

    if rows:
        worst = max(rows, key=lambda r: float(r[1].split("=")[1]) if "mean=" in r[1] else -1)
        print("-" * 78)
        print("差异最大：%s (%s)" % (worst[0], worst[1]))
    print("输出目录：%s" % out_dir)


if __name__ == "__main__":
    main()
