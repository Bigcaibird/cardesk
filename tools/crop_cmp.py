#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把设计板 PNG 与离屏预览 PNG 的同一区域裁出来做 1:1 上下对照。

用法：
    python3 tools/crop_cmp.py <screen> <preview> <x> <y> <w> <h> [out] [--theme day|night]

`screen` 是 design/png/<screen>-<theme>.png 的前半段（例如 s04-hvac）。
`--theme` 默认 night（设计板默认出的是夜间版）。
"""
import argparse
import sys
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parent.parent


def main() -> int:
    ap = argparse.ArgumentParser(add_help=False)
    ap.add_argument("screen")
    ap.add_argument("preview")
    ap.add_argument("x", type=int)
    ap.add_argument("y", type=int)
    ap.add_argument("w", type=int)
    ap.add_argument("h", type=int)
    ap.add_argument("out", nargs="?")
    ap.add_argument("--theme", default="night", choices=["day", "night"])
    try:
        a = ap.parse_args()
    except SystemExit:
        if len(sys.argv) < 7:
            print(__doc__)
            return 2
        raise

    x, y, w, h = a.x, a.y, a.w, a.h
    out = Path(a.out) if a.out else Path("/tmp/design-diff") / f"crop-{a.screen}-{a.preview}.png"

    design = Image.open(ROOT / "design" / "png" / f"{a.screen}-{a.theme}.png").convert("RGB")
    prev = Image.open(ROOT / "preview" / a.preview).convert("RGB")
    box = (x, y, x + w, y + h)
    a_img = design.crop(box)
    b_img = prev.crop(box)
    gap = 6
    canvas = Image.new("RGB", (w, h * 2 + gap), (255, 0, 255))
    canvas.paste(a_img, (0, 0))
    canvas.paste(b_img, (0, h + gap))
    out.parent.mkdir(parents=True, exist_ok=True)
    canvas.save(out)
    print(out)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
