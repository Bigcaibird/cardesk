#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
第153轮 · 第二页音乐卡「日间配色」的四种画法对比（供用户选方向）。

这四种画法用的是**同一张真机封面** + **同一套真实公式**
（照抄 CoverBackdrop.kt 的 relativeLuminance / targetRange / toneMap），
不是随手画的示意图。

  A 当前：深色氛围卡（卡面固定深色 + 文字固定浅色）
  B 旧路：白卡 + 三档深字 + 白罩（= 旧包 4246 的实测效果）
  C 折中：白卡 + 两档深字 + 淡白罩（文字档位砍到两档，罩可以淡很多）
  D 文字板：封面铺满整卡 + 浅色半透明「文字板」承载文字
"""
import io
import math
import os

from PIL import Image, ImageDraw, ImageFont

OUT = "/Users/roy/Documents/desktopapp/cardesk/emu-verify/y153"
CARD_W, CARD_H = 525, 863
FONT_PATH = "/System/Library/Fonts/Hiragino Sans GB.ttc"


def font(size):
    return ImageFont.truetype(FONT_PATH, size, index=0)


# ------------------------------------------------------------------ CoverBackdrop 的公式
def _linear(c):
    s = c / 255.0
    return s / 12.92 if s <= 0.04045 else ((s + 0.055) / 1.055) ** 2.4


def _to_srgb(l):
    if l <= 0.0031308:
        return int(l * 12.92 * 255 + 0.5)
    return int((1.055 * (l ** (1 / 2.4)) - 0.055) * 255 + 0.5)


def lum(rgb):
    r, g, b = rgb
    return (0.2126 * _linear(r) + 0.7152 * _linear(g) + 0.0722 * _linear(b))


def scale_color(rgb, k):
    return tuple(min(255, max(0, _to_srgb(_linear(c) * k))) for c in rgb)


def mix_white(rgb, s):
    return tuple(
        min(255, max(0, _to_srgb(_linear(c) + (1.0 - _linear(c)) * s))) for c in rgb
    )


def brighten_one(rgb):
    return tuple(min(255, c + 1) for c in rgb)


def target_range(surface_rgb, text_rgb):
    """照抄 CoverBackdrop.targetRange（SOLVE_CONTRAST = 3.05）。"""
    lt, ls = lum(text_rgb), lum(surface_rgb)
    if lt >= ls:
        return (0.0, (lt + 0.05) / 3.05 - 0.05)
    return (3.05 * (lt + 0.05) - 0.05, 1.0)


def tone_map(img, lo, hi, gamma=0.45):
    """照抄 CoverBackdrop.toneMap（逐像素 + 越界回踩）。"""
    px = list(img.getdata())
    lmax = max((lum(p) for p in px), default=0.0)
    if lmax <= 0:
        return img
    # 第一趟：逐像素映射（纯黑像素早退 —— 和 Kotlin 一致）
    out = []
    for p in px:
        lin = lum(p)
        if lin <= 0:
            out.append(p)
            continue
        t = (lin / lmax) ** gamma
        lout = lo + (hi - lo) * t
        if lout <= lin:
            q = scale_color(p, lout / lin)
        else:
            head = 1.0 - lin
            s = 1.0 if head <= 1e-9 else min(1.0, max(0.0, (lout - lin) / head))
            q = mix_white(p, s)
        out.append(q)
    # 第二趟：**单独**扫全图，把越界的像素夹回区间
    # （Kotlin CoverBackdrop.toneMap 里就是单独一趟 ——
    #  写在第一趟里面的话，纯黑像素的早退会连回踩一起跳掉，
    #  白罩上就会留下一颗颗黑点。第一版就是踩了这个坑。）
    fixed = []
    for q in out:
        guard = 0
        while guard < 260:
            l = lum(q)
            if l <= hi and l >= lo:
                break
            q = scale_color(q, 0.97) if l > hi else brighten_one(q)
            guard += 1
        fixed.append(q)
    res = Image.new(img.mode, img.size)
    res.putdata(fixed)
    return res


def build_cover(cover, lo, hi):
    """封面 → 中心裁到卡片比例 → 缩到短边 64 → 映射 → 拉回全卡。"""
    target_h = max(1, int(CARD_H * 64.0 / min(CARD_W, CARD_H)))
    target_w = max(1, int(CARD_W * 64.0 / min(CARD_W, CARD_H)))
    sw, sh = cover.size
    dst = CARD_W / CARD_H
    if sw / sh > dst:
        w = int(sh * dst)
        src = cover.crop(((sw - w) // 2, 0, (sw - w) // 2 + w, sh))
    else:
        h = int(sw / dst)
        src = cover.crop((0, (sh - h) // 2, sw, (sh - h) // 2 + h))
    small = src.resize((target_w, target_h), Image.LANCZOS)
    small = tone_map(small, lo, hi)
    return small.resize((CARD_W, CARD_H), Image.BILINEAR)


# ------------------------------------------------------------------ 排版
def draw_card(title, artist, lyrics, colors, face, cover_img=None, panel=None):
    """colors: dict(title=, sub=, faint=, accent=)"""
    card = Image.new("RGB", (CARD_W, CARD_H), face)
    if cover_img is not None:
        card.paste(cover_img, (0, 0))
    if panel is not None:
        px, py, pw, ph, palpha = panel
        plate = Image.new("RGB", (pw, ph), (255, 255, 255))
        mask = Image.new("L", (pw, ph), 0)
        ImageDraw.Draw(mask).rounded_rectangle((0, 0, pw - 1, ph - 1), radius=24, fill=255)
        card.paste(plate, (px, py), mask)
        # 半透明：把白色板按 alpha 叠回去
        ov = Image.new("RGB", (CARD_W, CARD_H), (255, 255, 255))
        plate_mask = Image.new("L", (CARD_W, CARD_H), 0)
        plate_mask.paste(mask, (px, py))
        plate_mask = plate_mask.point(lambda v: int(v * palpha))
        card.paste(ov, (0, 0), plate_mask)
    d = ImageDraw.Draw(card)

    # 来源行
    d.ellipse((26, 28, 46, 48), fill=colors["accent"])
    d.text((56, 24), "QQ音乐、播放中", font=font(20), fill=colors["faint"])

    # 专辑图
    art = Image.open("/tmp/y153_cover.png").resize((96, 96), Image.LANCZOS)
    if colors.get("art_round"):
        m = Image.new("L", (96, 96), 0)
        ImageDraw.Draw(m).rounded_rectangle((0, 0, 95, 95), radius=26, fill=255)
        card.paste(art, (24, 68), m)
    else:
        card.paste(art, (24, 68))

    # 歌名 / 歌手
    d.text((140, 82), title, font=font(38), fill=colors["title"])
    d.text((142, 128), artist, font=font(22), fill=colors["sub"])

    # 歌词三行（第一行高亮）
    d.text((CARD_W // 2, 392), lyrics[0], font=font(38), fill=colors["accent"], anchor="mm")
    d.text((CARD_W // 2, 470), lyrics[1], font=font(27), fill=colors["sub"], anchor="mm")
    d.text((CARD_W // 2, 526), lyrics[2], font=font(27), fill=colors["faint"], anchor="mm")

    # 进度条
    d.rounded_rectangle((25, 648, CARD_W - 25, 656), radius=4, fill=colors["faint"])
    d.rounded_rectangle((25, 648, 145, 656), radius=4, fill=colors["accent"])
    d.text((25, 672), "0:36", font=font(18), fill=colors["faint"])
    d.text((CARD_W - 25, 672), "4:09", font=font(18), fill=colors["faint"], anchor="ra")

    # 控制键（自己画，别指望字体里有 ❚❚ / ‹ / ›）
    cy = 726
    d.polygon([(152, cy - 12), (152, cy + 12), (132, cy)], fill=colors["sub"])
    d.rectangle((CARD_W // 2 - 16, cy - 14, CARD_W // 2 - 4, cy + 14), fill=colors["title"])
    d.rectangle((CARD_W // 2 + 4, cy - 14, CARD_W // 2 + 16, cy + 14), fill=colors["title"])
    d.polygon([(CARD_W - 152, cy - 12), (CARD_W - 152, cy + 12), (CARD_W - 132, cy)], fill=colors["sub"])
    return card



def draw_split_card(cover, title, artist, lyrics, colors):
    """D 方案：卡上半 = 封面原色区；卡下半 = 白色信息区（深色字）。"""
    card = Image.new("RGB", (CARD_W, CARD_H), (255, 255, 255))
    # 上半：封面按卡片比例 centerCrop，**不压暗**（保持原色）
    zone_h = 352
    sw, sh = cover.size
    dst = CARD_W / zone_h
    if sw / sh > dst:
        w = int(sh * dst)
        src = cover.crop(((sw - w) // 2, 0, (sw - w) // 2 + w, sh))
    else:
        h = int(sw / dst)
        src = cover.crop((0, (sh - h) // 2, sw, (sh - h) // 2 + h))
    card.paste(src.resize((CARD_W, zone_h), Image.LANCZOS), (0, 0))
    d = ImageDraw.Draw(card)
    # 来源行叠在封面左上（白字 + 半透明底）
    d.rounded_rectangle((16, 18, 190, 52), radius=17, fill=(0, 0, 0))
    d.ellipse((30, 28, 46, 44), fill=colors["accent"])
    d.text((54, 24), "QQ音乐、播放中", font=font(18), fill=(235, 238, 243))
    # 下半：白色信息区
    art = Image.open("/tmp/y153_cover.png").resize((96, 96), Image.LANCZOS)
    m = Image.new("L", (96, 96), 0)
    ImageDraw.Draw(m).rounded_rectangle((0, 0, 95, 95), radius=26, fill=255)
    card.paste(art, (24, zone_h + 22), m)
    d.text((140, zone_h + 30), title, font=font(38), fill=colors["title"])
    d.text((142, zone_h + 76), artist, font=font(22), fill=colors["sub"])
    d.text((CARD_W // 2, zone_h + 160), lyrics[0], font=font(38), fill=colors["accent"], anchor="mm")
    d.text((CARD_W // 2, zone_h + 222), lyrics[1], font=font(27), fill=colors["sub"], anchor="mm")
    d.text((CARD_W // 2, zone_h + 268), lyrics[2], font=font(27), fill=colors["faint"], anchor="mm")
    d.rounded_rectangle((25, 648, CARD_W - 25, 656), radius=4, fill=colors["faint"])
    d.rounded_rectangle((25, 648, 145, 656), radius=4, fill=colors["accent"])
    d.text((25, 672), "0:36", font=font(18), fill=colors["faint"])
    d.text((CARD_W - 25, 672), "4:09", font=font(18), fill=colors["faint"], anchor="ra")
    cy = 726
    d.polygon([(152, cy - 12), (152, cy + 12), (132, cy)], fill=colors["sub"])
    d.rectangle((CARD_W // 2 - 16, cy - 14, CARD_W // 2 - 4, cy + 14), fill=colors["title"])
    d.rectangle((CARD_W // 2 + 4, cy - 14, CARD_W // 2 + 16, cy + 14), fill=colors["title"])
    d.polygon([(CARD_W - 152, cy - 12), (CARD_W - 152, cy + 12), (CARD_W - 132, cy)], fill=colors["sub"])
    return card


def main():
    cover = Image.open("/tmp/y153_cover.png").convert("RGB")

    # —— 文字色板 ——
    NIGHT = dict(title=(237, 239, 243), sub=(162, 170, 184), faint=(117, 126, 142),
                 accent=(90, 216, 200))
    DAY3 = dict(title=(25, 28, 35), sub=(86, 93, 107), faint=(139, 146, 160),
                accent=(15, 156, 142))
    DAY2 = dict(title=(25, 28, 35), sub=(86, 93, 107), faint=(86, 93, 107),
                accent=(15, 156, 142))

    title, artist = "黑眼圈", "汪苏泷"
    lyrics = ("我也不知道", "我们的个性哪里不配", "我的眼睛好累")

    variants = []

    # A 当前：深色氛围卡
    lo, hi = target_range((0x1F, 0x23, 0x2D), (0x75, 0x7E, 0x8E))
    a = draw_card(title, artist, lyrics, NIGHT, (0x1F, 0x23, 0x2D),
                  build_cover(cover, lo, hi), panel=None)
    variants.append(("A 当前：深色氛围卡（深底浅字）", a, lo, hi, NIGHT["faint"]))

    # B 旧路：白卡 + 三档深字（= 旧包 4246）
    lo, hi = target_range((255, 255, 255), (0x8B, 0x92, 0xA0))
    b = draw_card(title, artist, lyrics, DAY3, (255, 255, 255),
                  build_cover(cover, lo, hi), panel=None)
    variants.append(("B 旧路：白卡 + 三档深字（= 旧包实测）", b, lo, hi, DAY3["faint"]))

    # C 折中：白卡 + 两档深字
    lo, hi = target_range((255, 255, 255), (0x56, 0x5D, 0x6B))
    c = draw_card(title, artist, lyrics, DAY2, (255, 255, 255),
                  build_cover(cover, lo, hi), panel=None)
    variants.append(("C 折中：白卡 + 两档深字 + 淡白罩", c, lo, hi, DAY2["sub"]))

    # D 上下分栏：上部封面原色区 + 下部白色信息区（版式重排）
    d = draw_split_card(cover, title, artist, lyrics, DAY3)
    variants.append(("D 上下分栏：上部封面区 + 下部白色信息区", d, 0.0, 1.0, DAY3["sub"]))

    # —— 拼成一张对比图 ——
    GAP, LABEL_H = 16, 46
    sheet = Image.new("RGB", (CARD_W * len(variants) + GAP * (len(variants) + 1), CARD_H + LABEL_H),
                      (18, 20, 24))
    sd = ImageDraw.Draw(sheet)
    lines = []
    for i, (label, img, lo, hi, weak) in enumerate(variants):
        x = GAP + i * (CARD_W + GAP)
        sheet.paste(img, (x, LABEL_H))
        sd.text((x + 4, 12), label, font=font(22), fill=(235, 238, 243))
        lines.append("%s\n    亮度区间 [%.4f, %.4f]（最弱文字色反推）" % (label, lo, hi))
    path = os.path.join(OUT, "10-day-style-compare.png")
    sheet.save(path)
    io.open(os.path.join(OUT, "10-day-style-compare.txt"), "w", encoding="utf-8").write("\n".join(lines))
    print(path)
    for l in lines:
        print(l)


if __name__ == "__main__":
    main()
