#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把逐屏 PNG 拼成 contact sheet，便于一次性目检所有页面的布局问题。

用法: python3 make_contact_sheets.py
产出: png/overview-day.png / png/overview-night.png
"""
import os

from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
PNG = os.path.join(HERE, "png")

SIDS = [
    "s01-home", "s02-nav", "s03-apps", "s04-hvac", "s05-settings",
    "s06-tasks", "s07-taskedit", "s08-picker", "s09-color", "s10-keys",
    "s11-slots", "s12-float", "s13-quickapps", "s14-adb",
]

CELL_W = 640
CELL_H = 360
COLS = 3
ROWS = 5
PAD = 10
LABEL = 26


def build(theme):
    w = COLS * CELL_W + (COLS + 1) * PAD
    h = ROWS * (CELL_H + LABEL) + (ROWS + 1) * PAD
    sheet = Image.new("RGB", (w, h), (18, 20, 26))
    dr = ImageDraw.Draw(sheet)
    for i, sid in enumerate(SIDS):
        p = os.path.join(PNG, f"{sid}-{theme}.png")
        if not os.path.exists(p):
            print("missing", p)
            continue
        im = Image.open(p).convert("RGB").resize((CELL_W, CELL_H), Image.LANCZOS)
        r, c = divmod(i, COLS)
        x = PAD + c * (CELL_W + PAD)
        y = PAD + r * (CELL_H + LABEL + PAD)
        dr.text((x + 4, y + 6), f"{sid}  [{theme}]", fill=(150, 160, 180))
        sheet.paste(im, (x, y + LABEL))
    out = os.path.join(PNG, f"overview-{theme}.png")
    sheet.save(out)
    print("wrote", out, sheet.size)


if __name__ == "__main__":
    for th in ("day", "night"):
        build(th)
