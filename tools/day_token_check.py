#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
日间 / 夜间「用色覆盖检查」。

对每一屏：把设计板 PNG 与离屏预览 PNG 的像素按颜色量化建直方图，然后双向比对：

  * 方向 A（设计板 → 预览）：设计板里成片出现的颜色，预览里有没有近似色？
    没有 → 说明这一块的**样式/配色没同步**（例如日间还在用夜间写死的色）。
  * 方向 B（预览 → 设计板）：预览里成片出现、设计板里没有的颜色，
    多半是 App 额外功能带来的（真图标、徽标、状态色），需要人工确认是否合理。

用法：
    python3 day_token_check.py                 # 日间
    python3 day_token_check.py --theme night   # 夜间
    python3 day_token_check.py --min-area 0.3  # 只看面积 >= 0.3% 的色块
"""
import argparse
import os
import sys
from collections import Counter

from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
DESIGN_DIR = os.path.join(ROOT, "design", "png")
PREVIEW_DIR = os.path.join(ROOT, "preview")

SCREENS = [
    ("s01-home", "01-home"),
    ("s02-nav", "02-nav"),
    ("s03-apps", "03-apps"),
    ("s04-hvac", "04-hvac"),
    ("s05-settings", "05-settings"),
    ("s06-tasks", "06-tasks"),
    ("s07-taskedit", "07-taskedit"),
    ("s08-picker", "08-picker"),
    ("s09-color", "09-color"),
    ("s10-keys", "10-keys"),
    ("s11-slots", "11-slots"),
    ("s12-float", "12-float"),
]

# 与设计板 TOKENS 对应的「关键色」，用于点名核对
KEY_COLORS = {
    "night": {
        "bg": (0x0F, 0x11, 0x16), "surface": (0x1F, 0x23, 0x2D),
        "surfaceSoft": (0x17, 0x1A, 0x22), "surfaceStrong": (0x2A, 0x2F, 0x3B),
        "stroke": (0x33, 0x39, 0x47), "text": (0xED, 0xEF, 0xF3),
        "textDim": (0xA2, 0xAA, 0xB8), "textFaint": (0x75, 0x7E, 0x8E),
        "accent": (0x33, 0xC6, 0xB4),
    },
    "day": {
        "bg": (0xF1, 0xF3, 0xF7), "surface": (0xFF, 0xFF, 0xFF),
        "surfaceSoft": (0xF4, 0xF6, 0xF9), "surfaceStrong": (0xE8, 0xEB, 0xF1),
        "stroke": (0xDF, 0xE3, 0xEA), "text": (0x19, 0x1C, 0x23),
        "textDim": (0x56, 0x5D, 0x6B), "textFaint": (0x8B, 0x92, 0xA0),
        "accent": (0x0F, 0x9C, 0x8E),
    },
}


def quantize(rgb, step=4):
    return tuple((c // step) * step for c in rgb)


def hist(path, step=4):
    im = Image.open(path).convert("RGB")
    w, h = im.size
    px = list(im.getdata())
    total = w * h
    cnt = Counter(quantize(p, step) for p in px)
    return cnt, total


def nearest(target, table, tol):
    """找表里离 target 最近的色（距离相同时取像素多的那个）。

    目标色也先量化，避免「原始色 vs 量化桶」的固定偏移把距离算大。
    """
    tq = quantize(target)
    best = None
    bestd = 10 ** 9
    bestn = -1
    for c, n in table.items():
        d = max(abs(c[i] - tq[i]) for i in range(3))
        if d < bestd or (d == bestd and n > bestn):
            bestd = d
            bestn = n
            best = (c, n)
    if bestd <= tol:
        return best
    return None


def fmt(c):
    return "#%02X%02X%02X" % c


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--theme", choices=["day", "night"], default="day")
    ap.add_argument("--min-area", type=float, default=0.2, help="面积百分比阈值")
    ap.add_argument("--tol", type=int, default=12, help="颜色匹配容差（单通道）")
    ap.add_argument("--out", default=None)
    args = ap.parse_args()

    theme = args.theme
    prefix = "e" if theme == "day" else "d"
    lines = []
    lines.append("主题：%s（预览前缀 %s）  容差=%d  最小面积=%.2f%%" % (theme, prefix, args.tol, args.min_area))
    lines.append("")

    key_missing = []
    for screen, prev in SCREENS:
        dpath = os.path.join(DESIGN_DIR, "%s-%s.png" % (screen, theme))
        ppath = os.path.join(PREVIEW_DIR, "%s%s.png" % (prefix, prev))
        if not (os.path.exists(dpath) and os.path.exists(ppath)):
            lines.append("%-12s 缺图，跳过" % screen)
            continue
        dc, dtot = hist(dpath)
        pc, ptot = hist(ppath)
        dtable = {c: n for c, n in dc.items()}
        ptable = {c: n for c, n in pc.items()}

        lines.append("=" * 92)
        lines.append("%s   设计板 %d 色 / 预览 %d 色" % (screen, len(dtable), len(ptable)))
        lines.append("-" * 92)
        lines.append("  A. 设计板有 / 预览缺：")
        miss = []
        for c, n in dc.most_common():
            area = 100.0 * n / dtot
            if area < args.min_area:
                break
            hit = nearest(c, ptable, args.tol)
            if hit is None:
                miss.append((area, c))
        if miss:
            for area, c in miss[:14]:
                lines.append("     缺失 %s  设计板面积 %.2f%%" % (fmt(c), area))
        else:
            lines.append("     （无）")

        lines.append("  B. 预览有 / 设计板无（App 额外内容）：")
        extra = []
        for c, n in pc.most_common():
            area = 100.0 * n / ptot
            if area < args.min_area:
                break
            hit = nearest(c, dtable, args.tol)
            if hit is None:
                extra.append((area, c))
        if extra:
            for area, c in extra[:14]:
                lines.append("     多余 %s  预览面积 %.2f%%" % (fmt(c), area))
        else:
            lines.append("     （无）")

        # 关键 token 点名核对：设计板里该 token 出现时，预览里是否也能找到
        for name, rgb in KEY_COLORS[theme].items():
            dhit = nearest(rgb, dtable, args.tol)
            if dhit is None:
                continue
            d_area = 100.0 * dhit[1] / dtot
            if d_area < args.min_area:
                continue
            phit = nearest(rgb, ptable, args.tol)
            p_area = 0.0 if phit is None else 100.0 * phit[1] / ptot
            flag = "OK " if phit is not None and p_area >= d_area * 0.35 else "!! "
            if flag == "!! ":
                key_missing.append((screen, name, d_area, p_area))
            lines.append("     %s token %-13s 设计 %.2f%%  预览 %.2f%%" % (flag, name, d_area, p_area))

    lines.append("")
    lines.append("=" * 92)
    lines.append("关键 token 覆盖不足汇总：")
    if key_missing:
        for screen, name, d, p in key_missing:
            lines.append("  %-12s %-13s 设计 %.2f%%  预览 %.2f%%" % (screen, name, d, p))
    else:
        lines.append("  （无）")

    out = args.out or os.path.join("/tmp", "token-check-%s.txt" % theme)
    with open(out, "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")
    print("写入：%s" % out)
    print("关键 token 覆盖不足：%d 处" % len(key_missing))


if __name__ == "__main__":
    sys.exit(main())
