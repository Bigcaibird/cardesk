#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
把某一屏的差分按网格分解，找出「大头」到底落在哪块区域。

用法：
    python3 diff_grid.py --theme day s01-home 8 6
    python3 diff_grid.py --theme day s01-home 8 6 --top 12
"""
import argparse
import os

from PIL import Image, ImageChops, ImageFilter

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
DESIGN_DIR = os.path.join(ROOT, "design", "png")
PREVIEW_DIR = os.path.join(ROOT, "preview")

SCREENS = {
    "s01-home": "01-home", "s02-nav": "02-nav", "s03-apps": "03-apps",
    "s04-hvac": "04-hvac", "s05-settings": "05-settings", "s06-tasks": "06-tasks",
    "s07-taskedit": "07-taskedit", "s08-picker": "08-picker", "s09-color": "09-color",
    "s10-keys": "10-keys", "s11-slots": "11-slots",
    "s12-float": "12-float",
}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--theme", default="day")
    ap.add_argument("screen")
    ap.add_argument("cols", nargs="?", type=int, default=8)
    ap.add_argument("rows", nargs="?", type=int, default=6)
    ap.add_argument("--top", type=int, default=12)
    ap.add_argument("--blur", type=float, default=0, help="高斯模糊半径（>0 时看结构差）")
    args = ap.parse_args()

    prefix = "e" if args.theme == "day" else "d"
    a = Image.open(os.path.join(DESIGN_DIR, "%s-%s.png" % (args.screen, args.theme))).convert("RGB")
    b = Image.open(os.path.join(PREVIEW_DIR, "%s%s.png" % (prefix, SCREENS[args.screen]))).convert("RGB")
    if a.size != b.size:
        b = b.resize(a.size)
    if args.blur > 0:
        a = a.filter(ImageFilter.GaussianBlur(args.blur))
        b = b.filter(ImageFilter.GaussianBlur(args.blur))
    diff = ImageChops.difference(a, b).convert("L")
    w, h = diff.size
    px = diff.load()

    cw, ch = w // args.cols, h // args.rows
    cells = []
    for r in range(args.rows):
        for c in range(args.cols):
            tot = 0
            n = 0
            hot = 0
            for y in range(r * ch, (r + 1) * ch):
                for x in range(c * cw, (c + 1) * cw):
                    v = px[x, y]
                    tot += v
                    n += 1
                    if v > 40:
                        hot += 1
            cells.append((tot / float(n), r, c, 100.0 * hot / n))
    cells.sort(reverse=True)

    print("屏幕 %s (%s)  整体 mean=%.2f" % (
        args.screen, args.theme,
        sum(v for v, _, _, _ in cells) / len(cells)))
    print("格子大小 %dx%d" % (cw, ch))
    print("%-10s %-8s %-8s %-10s %s" % ("mean", "行", "列", "热区%", "区域(px)"))
    for mean, r, c, hot in cells[: args.top]:
        print("%-10.2f %-8d %-8d %-10.2f (%d,%d)-(%d,%d)" % (
            mean, r, c, hot, c * cw, r * ch, (c + 1) * cw, (r + 1) * ch))


if __name__ == "__main__":
    main()
