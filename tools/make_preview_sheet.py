#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把离屏渲染预览拼成 contact sheet（外加一版「设计板 ↔ 预览」对照表）。

用法:
    python3 tools/make_preview_sheet.py                 # 夜间 + 日间都出
    python3 tools/make_preview_sheet.py --theme night
    python3 tools/make_preview_sheet.py --theme day

产出:
    preview/overview-night.png      —— 14 屏夜间预览拼版
    preview/overview-day.png        —— 14 屏日间预览拼版
    preview/compare-night.png       —— 夜间 设计板 / 预览 逐屏对照拼版
    preview/compare-day.png         —— 日间 设计板 / 预览 逐屏对照拼版
"""
import argparse
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parent.parent
DESIGN = ROOT / "design" / "png"
PREVIEW = ROOT / "preview"

# (设计板屏名, 夜间预览, 日间预览)
SCREENS = [
    ("s01-home", "d01-home.png", "e01-home.png"),
    ("s02-nav", "d02-nav.png", "e02-nav.png"),
    ("s03-apps", "d03-apps.png", "e03-apps.png"),
    ("s04-hvac", "d04-hvac.png", "e04-hvac.png"),
    ("s05-settings", "d05-settings.png", "e05-settings.png"),
    ("s06-tasks", "d06-tasks.png", "e06-tasks.png"),
    ("s07-taskedit", "d07-taskedit.png", "e07-taskedit.png"),
    ("s08-picker", "d08-picker.png", "e08-picker.png"),
    ("s09-color", "d09-color.png", "e09-color.png"),
    ("s10-keys", "d10-keys.png", "e10-keys.png"),
    ("s11-slots", "d11-slots.png", "e11-slots.png"),
    ("s12-float", "d12-float.png", "e12-float.png"),
    ("s13-quickapps", "d13-quickapps.png", "e13-quickapps.png"),
    ("s14-adb", "d14-adb.png", "e14-adb.png"),
]

CELL_W = 640
CELL_H = 360
COLS = 3
ROWS = 5
PAD = 10
LABEL = 26
BG = (12, 14, 20)
FG = (237, 239, 243)
DIM = (162, 170, 184)

FONT_PATH = "/System/Library/Fonts/Hiragino Sans GB.ttc"


def cell(img: Image.Image) -> Image.Image:
    return img.convert("RGB").resize((CELL_W, CELL_H), Image.LANCZOS)


def font(size: int):
    try:
        return ImageFont.truetype(FONT_PATH, size)
    except Exception:
        return ImageFont.load_default()


def sheet(pairs, cols, rows, out: Path, title: str, sub: str) -> None:
    w = cols * CELL_W + (cols + 1) * PAD
    head = 54
    h = head + rows * (CELL_H + LABEL + PAD) + PAD
    canvas = Image.new("RGB", (w, h), BG)
    draw = ImageDraw.Draw(canvas)
    draw.text((PAD, 12), title, fill=FG, font=font(20))
    draw.text((PAD, 34), sub, fill=DIM, font=font(13))
    for i, (label, img) in enumerate(pairs):
        r, c = divmod(i, cols)
        x = PAD + c * (CELL_W + PAD)
        y = head + r * (CELL_H + LABEL + PAD)
        draw.text((x, y + 4), label, fill=DIM, font=font(13))
        canvas.paste(cell(img), (x, y + LABEL))
    out.parent.mkdir(parents=True, exist_ok=True)
    canvas.save(out)
    print(out)


def build(theme: str, tag: str, round_tag: str) -> None:
    idx = 0 if theme == "night" else 1
    prev = [
        (design_name, Image.open(PREVIEW / (night_name if idx == 0 else day_name)))
        for design_name, night_name, day_name in SCREENS
    ]
    sheet(prev, COLS, ROWS, PREVIEW / f"overview-{tag}.png",
          f"CarDesk · 离屏渲染 14 屏 · {tag}（{round_tag}）",
          "design/png/s01..s14 同款数据的实际渲染结果")

    cmp_pairs = []
    for design_name, night_name, day_name in SCREENS:
        preview_name = night_name if theme == "night" else day_name
        d = Image.open(DESIGN / f"{design_name}-{theme}.png")
        p = Image.open(PREVIEW / preview_name)
        cmp_pairs.append((f"设计 {design_name}", d))
        cmp_pairs.append((f"预览 {preview_name[:-4]}", p))
    sheet(cmp_pairs, 4, 7, PREVIEW / f"compare-{tag}.png",
          f"设计板 vs 离屏渲染 逐屏对照 · {tag}（{round_tag}）",
          f"每行两屏：左 = design/png/s*-{theme}.png，右 = preview/{'d' if theme == 'night' else 'e'}*.png")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--theme", choices=["day", "night", "both"], default="both")
    ap.add_argument("--round", default="第五十三轮")
    args = ap.parse_args()
    themes = ["night", "day"] if args.theme == "both" else [args.theme]
    for theme in themes:
        build(theme, theme, args.round)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
