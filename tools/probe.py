#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""像素探针：在指定坐标取设计板 / 预览的 RGB。

用法:
    python3 probe.py --theme day s08-picker 900 200 900 400
"""
import argparse
import os

from PIL import Image

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
    "s13-quickapps": "13-quickapps",
    "s14-adb": "14-adb",
}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--theme", default="day")
    ap.add_argument("screen")
    ap.add_argument("coords", nargs="+", type=int, help="x y [x y ...]")
    args = ap.parse_args()
    prefix = "e" if args.theme == "day" else "d"
    d = Image.open(os.path.join(DESIGN_DIR, "%s-%s.png" % (args.screen, args.theme))).convert("RGB")
    p = Image.open(os.path.join(PREVIEW_DIR, "%s%s.png" % (prefix, SCREENS[args.screen]))).convert("RGB")
    out = []
    out.append("屏幕 %s (%s)  设计 %s  预览 %s" % (args.screen, args.theme, d.size, p.size))
    for i in range(0, len(args.coords) - 1, 2):
        x, y = args.coords[i], args.coords[i + 1]
        dr, dg, db = d.getpixel((x, y))
        pr, pg, pb = p.getpixel((x, y))
        out.append("(%4d,%4d)  设计 #%02X%02X%02X   预览 #%02X%02X%02X   %s" % (
            x, y, dr, dg, db, pr, pg, pb,
            "同" if max(abs(dr - pr), abs(dg - pg), abs(db - pb)) <= 12 else "差"))
    print("\n".join(out))


if __name__ == "__main__":
    main()
