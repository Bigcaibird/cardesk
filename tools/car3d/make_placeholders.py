#!/usr/bin/env python3
"""生成 `car3d` 的**占位资产**（俯视车模，纯色块，不是最终写实图）。

## 这个脚本的定位

写实车模的烘焙管线见 `analysis/领克01写实车模-烘焙管线与运行时规范.md`，
最终资产是透明底分层 WebP（烘焙机出的）。本脚本**只负责占位**：

- 让运行时（`core/car3d/*`）从第一天起就能接真实资产目录结构跑通；
- 让 Robolectric 的 preview 截图能**看出**「哪扇门开、哪扇窗开、尾门开」；
- 总量 < 200KB（真实烘焙图之后原地替换 `assets-src/car3d/lynk01/` 即可）。

风格刻意做得极简：车身轮廓 + 玻璃/门/轮/灯色块。不做抗锯齿之外的任何修饰。

## 用法

    /Users/roy/.dsh/dsh-runtimes/dsh-primary-runtime/dependencies/python/bin/python3 \
        tools/car3d/make_placeholders.py

产物：

    assets-src/car3d/lynk01/model.json
    assets-src/car3d/lynk01/top/<state>.png
    assets-src/car3d/lynk01/top/anim_<kind>_<i>.png
"""

import json
import math
import os

from PIL import Image, ImageDraw

# 画布 = 弹窗槽位尺寸（280×500 设计像素）；真实烘焙图是它的 2×，这里够用。
W, H = 280, 500

ROOT = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.normpath(os.path.join(ROOT, "..", "..", "assets-src", "car3d", "lynk01"))
TOP = os.path.join(OUT, "top")

# 配色取 `core/CarColors.kt` 的夜间一套（车漆/玻璃/轮胎），开状态用橙色高亮。
BODY = (60, 74, 104, 255)        # 车漆 #3C4A68
BODY_EDGE = (128, 146, 190, 255)  # 亮边
GLASS = (14, 21, 34, 235)        # 玻璃
GLASS_EDGE = (92, 112, 150, 200)
TIRE = (22, 26, 36, 255)
TIRE_EDGE = (70, 78, 92, 255)
ACCENT = (242, 160, 61, 255)     # panelOpenFill 橙
ACCENT_EDGE = (255, 200, 120, 255)
TAIL = (192, 58, 58, 255)
HEAD = (201, 214, 255, 255)
GAP = (10, 14, 22, 255)          # 门开着时门原来位置的暗洞

# 车身轮廓（俯视，车头朝上）。与 `core/CarTopGeometry` 的 120×240 设计空间同构，
# 这里按 280×500 画布放大 2.33/2.08 倍。
BODY_PTS = [
    (140, 22), (168, 30), (188, 48), (198, 80),
    (202, 120), (204, 170), (204, 240), (203, 300),
    (200, 360), (196, 412), (186, 446), (168, 466),
    (140, 474), (112, 466), (94, 446), (84, 412),
    (80, 360), (77, 300), (76, 240), (76, 170),
    (78, 120), (82, 80), (92, 48), (112, 30),
]

WHEELS = [
    (18, 118, 48, 182), (232, 118, 262, 182),
    (18, 320, 48, 384), (232, 320, 262, 384),
]

# 车门：左前/右前/左后/右后（顺序 = CarPose.doors 的下标）。
# (side, y0, y1) —— side=-1 左、+1 右；hinge 在 y0（前门）或 y1（后门）。
DOORS = {
    "lf": (-1, 168, 258),
    "rf": (1, 168, 258),
    "lb": (-1, 262, 352),
    "rb": (1, 262, 352),
}


def rot(px, py, hx, hy, deg):
    """绕 (hx,hy) 旋转 deg 度（屏幕坐标，正值 = 顺时针视觉方向）。"""
    a = math.radians(deg)
    dx, dy = px - hx, py - hy
    return (hx + dx * math.cos(a) - dy * math.sin(a), hy + dx * math.sin(a) + dy * math.cos(a))


def door_quad(side, y0, y1, angle):
    """门板四边形（含铰链旋转）。左门板厚 20px 贴在车身外侧。"""
    x_out = 74 if side < 0 else 206
    x_in = x_out + 20 * (-side)  # 向车身内侧一点（占位图刻意加厚，缩到 88px 槽位还能看出来）
    hinge_y = y0 if y0 > 100 and y0 < 300 else y1
    pts = [(x_out, y0), (x_out, y1), (x_in, y1), (x_in, y0)]
    hx, hy = (x_out, y0) if side < 0 else (x_out, y0)
    if side < 0:
        hx, hy = x_out, y0  # 左前/左后都以前端为铰链（占位图不追求机械正确）
    else:
        hx, hy = x_out, y1
    return [rot(px, py, hx, hy, angle) for px, py in pts]


def draw_glass(d: ImageDraw.ImageDraw, open_windows: bool):
    # 前风挡（梯形）、车顶玻璃、后风挡
    if not open_windows:
        d.polygon([(96, 108), (184, 108), (192, 140), (88, 140)], fill=GLASS)
        d.rectangle([92, 148, 188, 296], fill=GLASS)
        d.polygon([(88, 304), (192, 304), (184, 336), (96, 336)], fill=GLASS)
    # 窗框永远画：开窗时只剩细框 + 一道亮缝，和「关」明显不同
    d.rectangle([92, 148, 188, 296], outline=GLASS_EDGE, width=2)
    d.line([92, 200, 188, 200], fill=GLASS_EDGE, width=2)
    if open_windows:
        # 开窗：玻璃退下去，露出发亮的窗沿（一条细亮线）
        for y in (168, 176):
            d.line([96, y, 184, y], fill=ACCENT_EDGE, width=2)


def draw_doors(d: ImageDraw.ImageDraw, angles):
    for kind, (side, y0, y1) in DOORS.items():
        ang = angles.get(kind, 0.0)
        # 门缝（关着时也能看出四扇门）
        x = 76 if side < 0 else 204
        d.line([x, y0, x, y1], fill=(20, 26, 38, 255), width=2)
        if ang > 0.1:
            # 原位：暗洞
            d.rectangle([min(x, x + 20 * -side), y0, max(x, x + 20 * -side), y1], fill=GAP)
            d.polygon(door_quad(side, y0, y1, ang), fill=ACCENT, outline=ACCENT_EDGE)
        # 把手（一个小色块，纯装饰）
        hy = (y0 + y1) // 2
        hx = 78 if side < 0 else 202
        d.rectangle([hx - 3, hy - 8, hx + 3, hy + 8], fill=(180, 190, 210, 255))


def draw_trunk_open(d: ImageDraw.ImageDraw, angle: float):
    # 尾门：绕尾端上沿掀起（俯视图里表现为车尾多出一块橙色板 + 尾端暗洞）
    if angle <= 0.1:
        return
    l = 34 * (angle / 55.0)
    d.rectangle([96, 474 - l, 184, 474], fill=GAP)
    d.polygon(
        [(96, 474), (184, 474), (190, 474 + l * 0.55), (90, 474 + l * 0.55)],
        fill=ACCENT, outline=ACCENT_EDGE,
    )


def draw_car(door_angles=None, window_open=False, trunk_angle=0.0):
    img = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    # 车底阴影（一点点，纯为了不飘）
    d.ellipse([52, 40, 228, 486], fill=(0, 0, 0, 40))
    d.polygon(BODY_PTS, fill=BODY, outline=BODY_EDGE)
    for x0, y0, x1, y1 in WHEELS:
        d.rounded_rectangle([x0, y0, x1, y1], radius=8, fill=TIRE, outline=TIRE_EDGE)
    draw_glass(d, window_open)
    draw_doors(d, door_angles or {})
    # 灯组
    d.polygon([(84, 34), (110, 26), (110, 44), (86, 50)], fill=HEAD)
    d.polygon([(196, 34), (170, 26), (170, 44), (194, 50)], fill=HEAD)
    d.rectangle([88, 452, 118, 466], fill=TAIL)
    d.rectangle([162, 452, 192, 466], fill=TAIL)
    draw_trunk_open(d, trunk_angle)
    return img


STATES = {
    "closed": lambda: draw_car(),
    "lf": lambda: draw_car({"lf": 60}),
    "rf": lambda: draw_car({"rf": 60}),
    "lb": lambda: draw_car({"lb": 60}),
    "rb": lambda: draw_car({"rb": 60}),
    "all": lambda: draw_car({"lf": 60, "rf": 60, "lb": 60, "rb": 60}),
    "win": lambda: draw_car(window_open=True),
    "trunk": lambda: draw_car(trunk_angle=55.0),
}

# 动画帧：值 = 该帧的「开度 0..1」，运行时按 model.json 的 frames 倒放/正放。
ANIM = {
    "lf": [0.0, 0.34, 0.67, 1.0],
    "rf": [0.0, 0.34, 0.67, 1.0],
    "lb": [0.0, 0.34, 0.67, 1.0],
    "rb": [0.0, 0.34, 0.67, 1.0],
    "win": [0.0, 0.5, 1.0],
    "trunk": [0.0, 0.33, 0.66, 1.0],
}


def anim_frame(kind: str, t: float):
    if kind in DOORS:
        return draw_car({kind: 60.0 * t})
    if kind == "win":
        return draw_car(window_open=t > 0.05,
                        door_angles={}) if t < 1.0 else draw_car(window_open=True)
    return draw_car(trunk_angle=55.0 * t)


def main() -> None:
    os.makedirs(TOP, exist_ok=True)
    total = 0
    for name, fn in STATES.items():
        p = os.path.join(TOP, name + ".png")
        fn().save(p, optimize=True)
        total += os.path.getsize(p)
    for kind, ts in ANIM.items():
        for i, t in enumerate(ts):
            p = os.path.join(TOP, "anim_%s_%d.png" % (kind, i))
            anim_frame(kind, t).save(p, optimize=True)
            total += os.path.getsize(p)

    model = {
        "format": "cardesk.car3d/1",
        "model": "01",
        "name": "领克 01（占位）",
        "dims": {"lengthMm": 4549, "widthMm": 1860, "heightMm": 1689, "wheelbaseMm": 2734},
        "views": {
            # 占位图直接按弹窗槽位 280×500 画；真实烘焙会出 560×1000 master + 280×500 slot。
            "top": {"master": [560, 1000], "slot": [280, 500]},
        },
        "states": {
            "closed": {},
            "lf": {"door": {"lf": 1}},
            "rf": {"door": {"rf": 1}},
            "lb": {"door": {"lb": 1}},
            "rb": {"door": {"rb": 1}},
            "all": {"door": {"lf": 1, "rf": 1, "lb": 1, "rb": 1}},
            "win": {"window": {"lf": 1, "rf": 1, "lb": 1, "rb": 1}},
            "trunk": {"trunk": 1},
        },
        "anim": {
            "door": {"frames": 4, "ms": 220, "kinds": ["lf", "rf", "lb", "rb"]},
            "window": {"frames": 3, "ms": 260, "kinds": ["win"]},
            "trunk": {"frames": 4, "ms": 260, "kinds": ["trunk"]},
        },
        "attribution": "3D 模型：cardesk 自绘占位（Pillow 脚本生成）；真实模型待替换为 CC-BY 4.0 资产",
    }
    mp = os.path.join(OUT, "model.json")
    with open(mp, "w", encoding="utf-8") as f:
        json.dump(model, f, ensure_ascii=False, indent=2)
        f.write("\n")
    total += os.path.getsize(mp)
    print("wrote %d files, %.1f KB total" % (len(STATES) + sum(len(v) for v in ANIM.values()) + 1, total / 1024.0))


if __name__ == "__main__":
    main()
