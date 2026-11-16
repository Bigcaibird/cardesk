#!/usr/bin/env python3
"""把 .work/3d/bake<NN>/slot 的烘焙产物安装成 cardesk 运行时资产。

约定（与 analysis/领克01写实车模-烘焙管线与运行时规范.md 对齐）：
  view  设计槽位        slot 文件名前缀                 安装到
  top   280x500          dialog-<W>x<H>-<state>          assets/car3d/<model>/top/
  card   88x176          card-<W>x<H>-<state>            assets/car3d/<model>/card/
  hero  680x380          hero-<W>x<H>-<state>            assets/car3d/<model>/hero/<color>/
  动画   top 家族        dialog-*-anim_<kind>_<i>        assets/.../top/anim_<kind>_<i>.webp

车漆多色：hero 家族按颜色分目录。深灰是主版（文件名不带后缀），
白/黑为 `hero-<W>x<H>-<state>-white.png` / `-black.png`。
三色必须由烘焙侧保证**同构图**（同状态 alpha bbox 一致），这里只做尺寸归一。

同一 view 家族的所有状态由烘焙侧保证**同一变换**（车身不跳），这里只做尺寸归一。
优先取尺寸完全一致的源图（免解码 3× 大图），否则取分辨率最大的一张。

用法：
  python3 tools/car3d/install_bake.py --src ../../.work/3d/bake01
  python3 tools/car3d/install_bake.py --src ../../.work/3d/bake05 --model lynk05
  python3 tools/car3d/install_bake.py --src ../../.work/3d/bake03 --model lynk03
"""
import argparse
import json
import os
import re
import shutil
import sys

from PIL import Image

# view → (槽位设计尺寸, slot 文件名前缀)
VIEWS = {
    "top": ((280, 500), "dialog"),
    "card": ((448, 315), "card"),
    "hero": ((600, 335), "hero"),
    "cardtop": ((256, 188), "cardtop"),
}

# 按颜色分目录的 view（hero=3/4 侧视，cardtop=卡片俯视横置）
COLOR_VIEWS = ("hero", "card", "cardtop")

# 每个 view 实际安装的色号：cardtop（俯视，可选视角）只装主版 dark，
# 其余色由运行时 effectiveColor 回退 defaultColor，省约 2/3 的 cardtop 体积。
VIEW_COLORS = {"hero": ("dark", "white", "black"),
               "card": ("dark", "white", "black"),
               "cardtop": ("dark",)}

# 车漆颜色：主版 dark 不带文件名后缀
COLORS = ["dark", "white", "black"]

# 车型元数据（安装时写进 model.json）
MODELS = {
    "lynk01": {"model": "01", "name": "领克 01",
               "dims": {"lengthMm": 4549, "widthMm": 1860, "heightMm": 1689, "wheelbaseMm": 2734}},
    "lynk05": {"model": "05", "name": "领克 05",
               "dims": {"lengthMm": 4592, "widthMm": 1879, "heightMm": 1628, "wheelbaseMm": 2734}},
    "lynk03": {"model": "03", "name": "领克 03",
               "dims": {"lengthMm": 4639, "widthMm": 1840, "heightMm": 1472, "wheelbaseMm": 2730}},
}

# 双门状态（第178轮）：两扇门同时开、其余关、窗关；资产缺这组时运行时回退 all。
DUAL_STATES = ["lf_rf", "lf_lb", "rf_rb", "lb_rb", "lf_rb", "rf_lb"]
STATES = ["closed", "lf", "rf", "lb", "rb", "all", "trunk", "win"] + DUAL_STATES

# 动画：kind → (槽位 ms, 期望帧数上限)
ANIMS = {"lf": 220, "rf": 220, "lb": 220, "rb": 220, "win": 260, "trunk": 260}

WEBP_Q = 76

# —— 俯视 32 状态（第 190 轮）——
# 门组合 key 按固定顺序 lf,rf,lb,rb 拼接；尾门开时追加 _trunk。
# 车窗在俯视下不可见（实测 cardtop 的 win 与 closed 逐像素相同），故不烘窗态。
TOP_ONLY_VIEW = "cardtop"
TOP_ONLY_SIZE = (356, 264)      # 卡片里俯视横置的显示尺寸
TOP_ONLY_MASTER = (712, 528)    # 烘焙母版（2×），安装时降采样
TOP_DOOR_KEYS = [
    "closed",
    "lf", "rf", "lb", "rb",
    "lf_rf", "lf_lb", "lf_rb", "rf_lb", "rf_rb", "lb_rb",
    "lf_rf_lb", "lf_rf_rb", "lf_lb_rb", "rf_lb_rb",
    "all",
]
TOP_STATES = [k for k in TOP_DOOR_KEYS] + [k + "_trunk" for k in TOP_DOOR_KEYS]
GAP_COLOR = (255, 138, 61)      # 门缝高亮橙
GAP_ALPHA = 230
GAP_DIFF_THRESHOLD = 8


ATTR = (
    "3D 模型：领克官方线上展厅（hall.lynkco.com.cn）公开资产，"
    "本 App 个人自用；官方未声明授权，请勿商用再分发"
)


def pick_source(slot_dir, prefix, state_or_anim, target=None, color=None):
    """在 slot/ 里找 `<prefix>-<W>x<H>-<name>[-<color>][-dark-card].png`。

    有 target 尺寸时优先用尺寸完全一致的那张（免解码 3× 大图，快且无损）；
    否则退回分辨率最大的一张（3×，下采样更锐）。
    纯色增强版 `-dark-card` 与非增强版同尺寸时优先后者（已统一增强）。
    """
    suffix = "" if color in (None, "dark") else rf"-{re.escape(color)}"
    pat = re.compile(
        rf"^{re.escape(prefix)}-(\d+)x(\d+)-{re.escape(state_or_anim)}{suffix}(-dark-card)?\.png$"
    )
    best = None
    for fn in os.listdir(slot_dir):
        m = pat.match(fn)
        if not m:
            continue
        w, h = int(m.group(1)), int(m.group(2))
        path = os.path.join(slot_dir, fn)
        if target and (w, h) == target:
            return path
        area = w * h
        if best is None or area > best[0]:
            best = (area, path)
    return best[1] if best else None


# 双门状态是过渡态（只在两扇门同时开时出现），用更低质量换体积。
DUAL_WEBP_Q = 60


def convert(src, size, out_webp, quality=None):
    im = Image.open(src).convert("RGBA")
    if im.size != size:
        im = im.resize(size, Image.LANCZOS)
    q = WEBP_Q if quality is None else quality
    im.save(out_webp, "WEBP", quality=q, alpha_quality=70, method=6)


def _install_states(slot, out_dir, prefix, size, states, color=None):
    os.makedirs(out_dir, exist_ok=True)
    n, missing = 0, []
    for st in states:
        src = pick_source(slot, prefix, st, size, color)
        if not src:
            missing.append(st)
            continue
        q = DUAL_WEBP_Q if "_" in st else None
        convert(src, size, os.path.join(out_dir, st + ".webp"), q)
        n += 1
    return n, missing



def _connected_components(mask):
    """8 邻域连通域标记（纯 Python，356x264 规模够快）。返回 [(area, [(x,y), ...]), ...]。"""
    w, h = mask.size
    px = mask.load()
    seen = bytearray(w * h)
    comps = []
    for y0 in range(h):
        for x0 in range(w):
            i0 = y0 * w + x0
            if seen[i0] or not px[x0, y0]:
                continue
            stack = [(x0, y0)]
            seen[i0] = 1
            pts = []
            while stack:
                x, y = stack.pop()
                pts.append((x, y))
                for dy in (-1, 0, 1):
                    for dx in (-1, 0, 1):
                        nx, ny = x + dx, y + dy
                        if 0 <= nx < w and 0 <= ny < h:
                            i = ny * w + nx
                            if not seen[i] and px[nx, ny]:
                                seen[i] = 1
                                stack.append((nx, ny))
            comps.append((len(pts), pts))
    return comps


def _gap_overlay(closed_path, state_path, out_path, max_change=0.15):
    """生成「相对 closed 的几何变化轮廓」橙色高亮（俯视开门/尾门时用）。

    只在**母版 PNG** 上做 alpha 差分（无损，阈值低、边缘干净），降采样后再做
    中值/闭运算/去碎片/填内孔，最后形态学梯度取 2px 描边。颜色差分会整车糊橙，不用。
    """
    from PIL import ImageChops, ImageFilter

    closed = Image.open(closed_path).convert("RGBA")
    state = Image.open(state_path).convert("RGBA")
    if closed.size != state.size:
        closed = closed.resize(state.size, Image.LANCZOS)
    ca = closed.split()[3]
    sa = state.split()[3]
    diff = ImageChops.difference(ca, sa).point(lambda v: 255 if v > GAP_DIFF_THRESHOLD else 0)
    if diff.getbbox() is None:
        return False
    # 守卫：只差开门区域；变化面积超过整车一定比例说明机位/裁切没对齐，宁可不画。
    changed = sum(1 for v in diff.getdata() if v)
    total = sum(1 for v in sa.getdata() if v > 20)
    if total <= 0 or changed / total > max_change:
        print(f"!! gap 跳过（变化 {changed}/{total} = {changed / total:.1%} > {max_change:.0%}，疑似机位未对齐）",
              file=sys.stderr)
        return False

    mask = diff.filter(ImageFilter.MedianFilter(3))          # 去毛刺
    mask = mask.filter(ImageFilter.MaxFilter(3)).filter(ImageFilter.MinFilter(3))  # 闭运算补断点
    mask = mask.resize(TOP_ONLY_SIZE, Image.LANCZOS).point(lambda v: 255 if v > 127 else 0)
    # 桥接同一开口里被车身细节切开的几块（尾门玻璃/撑杆），避免出现多条描边
    mask = mask.filter(ImageFilter.MaxFilter(7)).point(lambda v: 255 if v > 127 else 0)

    comps = sorted(_connected_components(mask), key=lambda c: -c[0])
    if not comps:
        return False
    # 只保留最大块 + 不与它 bbox 内含的其他块（去掉尾门开启后内部的细碎轮廓）
    main_area, main_pts = comps[0]
    xs = [p[0] for p in main_pts]
    ys = [p[1] for p in main_pts]
    mbox = (min(xs), min(ys), max(xs), max(ys))
    keep = [main_pts]
    for area, pts in comps[1:]:
        if area < main_area * 0.10:
            continue
        cx = [p[0] for p in pts]
        cy = [p[1] for p in pts]
        b = (min(cx), min(cy), max(cx), max(cy))
        # bbox 交叠 >= 70% 视为「同一变化区域里的内层轮廓」，丢掉（尾门玻璃/撑杆等）
        ix = max(0, min(b[2], mbox[2]) - max(b[0], mbox[0]))
        iy = max(0, min(b[3], mbox[3]) - max(b[1], mbox[1]))
        bx = max(1, (b[2] - b[0]) * (b[3] - b[1]))
        if (ix * iy) / bx >= 0.70:
            continue
        keep.append(pts)
    # 逐块按行填充（把块内部的分割线/内孔吃掉），得到实心轮廓
    filled = Image.new("L", mask.size, 0)
    fp = filled.load()
    for pts in keep:
        rows = {}
        for x, y in pts:
            lo, hi = rows.get(y, (x, x))
            rows[y] = (min(lo, x), max(hi, x))
        for y, (lo, hi) in rows.items():
            for x in range(lo, hi + 1):
                fp[x, y] = 255
    region = filled.filter(ImageFilter.MaxFilter(5))          # 把新出现的门板包进区域
    edge = ImageChops.subtract(region.filter(ImageFilter.MaxFilter(3)),
                               region.filter(ImageFilter.MinFilter(3)))
    edge = edge.point(lambda v: GAP_ALPHA if v > 20 else 0)
    rgba = Image.new("RGBA", edge.size, GAP_COLOR + (0,))
    rgba.putalpha(edge)
    rgba.save(out_path, "WEBP", quality=80, alpha_quality=80, method=6)
    return True


def install_top_only(slot, dst, model_name):
    """只装 cardtop（俯视横置、单色、32 门态）+ 每个状态的 gap 高亮。"""
    out_dir = os.path.join(dst, TOP_ONLY_VIEW, "dark")
    os.makedirs(out_dir, exist_ok=True)
    installed, missing = 0, []
    for st in TOP_STATES:
        src = pick_source(slot, "cardtop", st, None, None)
        if not src:
            missing.append(st)
            continue
        convert(src, TOP_ONLY_SIZE, os.path.join(out_dir, st + ".webp"))
        installed += 1
    gaps = 0
    closed_master = pick_source(slot, "cardtop", "closed", None, None)
    if closed_master:
        for st in TOP_STATES:
            if st == "closed":
                continue
            state_master = pick_source(slot, "cardtop", st, None, None)
            if not state_master:
                continue
            # 变化面积随开门数线性增长，阈值按门数放宽（4 门全开 ~44%，实测 21~26%）
            doors_open = sum(1 for on in _top_state_semantics(st)["doors"] if on)
            limit = min(0.55, max(0.15, 0.09 * doors_open + 0.06))
            # 运行时已不再叠加门缝高亮（用户 m04741）：gap 只留档在 top32/，不进 APK
            _ = (closed_master, state_master)
    return installed, missing, gaps


def _install_winpatches(slot, dst, view_colors):
    """装窗口黄色贴片：assets/<view>/<color>/win_<id>.webp + 归一化 rect。"""
    src_root = os.path.join(slot, "winpatches")
    if os.path.isdir(src_root):
        for view, colors in view_colors.items():
            for color in colors:
                sd = os.path.join(src_root, view, color)
                if not os.path.isdir(sd):
                    continue
                dd = os.path.join(dst, view, color)
                os.makedirs(dd, exist_ok=True)
                for f in sorted(os.listdir(sd)):
                    if f.startswith("win_") and f.endswith(".webp"):
                        shutil.copyfile(os.path.join(sd, f), os.path.join(dd, f))
    rects = {}
    meta_p = os.path.join(slot, "winpatches.json")
    if os.path.exists(meta_p):
        try:
            raw = json.load(open(meta_p, encoding="utf-8"))
        except Exception:
            raw = {}
        for view, items in raw.items():
            if not isinstance(items, dict):
                continue
            vv = {}
            for wid, val in items.items():
                r = val.get("rect") if isinstance(val, dict) else val
                if isinstance(r, (list, tuple)) and len(r) == 4 and float(r[2]) > 0 and float(r[3]) > 0:
                    vv[wid] = [round(float(x), 4) for x in r]
            if vv:
                rects[view] = vv
    # 方案 A（用户 2026-11-14 定）：远侧窗在 3/4 机位下几何上不可见，
    # 画任何形状都会糊到车顶/风挡上变成"黄色方块"，所以干脆不画；
    # 右窗状态由卡下方文案与车辆状态面板的逐项标注表达。
    for view in list(rects.keys()):
        if view == "cardtop":      # 俯视四窗都看得见，保留四块
            continue
        for wid in ("rf", "rb"):
            rects[view].pop(wid, None)
    for view, colors in (view_colors or {}).items():
        if view == "cardtop":
            continue
        for color in colors:
            for wid in ("rf", "rb"):
                fp = os.path.join(dst, view, color, "win_%s.webp" % wid)
                if os.path.exists(fp):
                    os.remove(fp)
    return rects


def _translucent_far_patches(dst, rects, view_colors):
    """远侧窗（rf/rb）在 3/4 机位下被车身挡住，画成实心黄会像"贴了块方玻璃"。

    用户要"透视"效果：这里把远侧贴片按「该视角 closed 图的 alpha 轮廓」裁剪，
    并把 alpha 压到 ~45%，这样它只落在车身上、且读作隔着一层玻璃，不会糊到车外。
    """
    try:
        from PIL import Image
    except Exception:
        return
    for view, items in rects.items():
        for color in (view_colors.get(view) or []):
            base_p = os.path.join(dst, view, color, "closed.webp")
            if not os.path.exists(base_p):
                continue
            base = Image.open(base_p).convert("RGBA")
            bw, bh = base.size
            for wid in ("rf", "rb"):
                r = items.get(wid)
                p = os.path.join(dst, view, color, "win_%s.webp" % wid)
                if not r or not os.path.exists(p):
                    continue
                x = int(round(r[0] * bw)); y = int(round(r[1] * bh))
                w = max(1, int(round(r[2] * bw))); h = max(1, int(round(r[3] * bh)))
                scaled = Image.open(p).convert("RGBA").resize((w, h), Image.LANCZOS)
                body = base.crop((x, y, x + w, y + h))
                if body.size != (w, h):
                    canvas = Image.new("RGBA", (w, h), (0, 0, 0, 0))
                    canvas.paste(body, (0, 0))
                    body = canvas
                sa = list(scaled.split()[3].getdata())
                ba = list(body.split()[3].getdata())
                mask = Image.new("L", (w, h))
                mask.putdata([min(255, int(a * b / 255.0 * 0.45)) for a, b in zip(sa, ba)])
                # 透视表达：填充半透明（0.35），边缘提亮成"窗框轮廓"，这样远侧窗看得出是窗户而不是色块。
                from PIL import ImageFilter
                edge = ImageFilter.MaxFilter(3)
                grown = mask.filter(edge)
                edge_mask = Image.new("L", (w, h))
                edge_mask.putdata([max(0, g - m) for g, m in zip(grown.getdata(), mask.getdata())])
                out = Image.new("RGBA", (w, h), (245, 197, 66, 0))
                fill_a = [int(m * 0.35) for m in mask.getdata()]
                edge_a = [int(e * 0.95) for e in edge_mask.getdata()]
                out.putalpha(Image.new("L", (w, h)))
                cols = []
                fill_data = mask.getdata()
                for i, (m, e) in enumerate(zip(fill_data, edge_mask.getdata())):
                    cols.append((255, 226, 130, min(255, max(int(m * 0.07), int(e * 0.92)))))
                out.putdata(cols)
                out.save(p, "WEBP", quality=82, method=4)

def _top_state_semantics(st):
    """俯视 32 态的语义：doors(4) + trunk。"""
    body, trunk = (st[:-6], True) if st.endswith("_trunk") else (st, False)
    if body == "all":
        doors = [True] * 4
    elif body == "closed":
        doors = [False] * 4
    else:
        doors = [k in body.split("_") for k in ("lf", "rf", "lb", "rb")]
    return {"doors": doors, "trunk": trunk}


def run_top_only(args, dst, slot):
    """只装 cardtop（俯视横置、单色、32 门态）+ 门缝橙色高亮。"""
    meta = MODELS.get(args.model, {"model": args.model, "name": args.model, "dims": {}})
    installed, missing, gaps = install_top_only(slot, dst, args.model)
    if not installed:
        print(f"!! {args.model}: 一张 cardtop 都没装上（先让烘焙侧出 top32）", file=sys.stderr)
        return 1

    anchors = _load_anchors(slot)
    top_anchors = anchors.get(TOP_ONLY_VIEW, {}) if isinstance(anchors, dict) else {}
    model = {
        "format": "cardesk.car3d/1",
        "model": meta["model"],
        "name": meta["name"],
        "dims": meta["dims"],
        "views": {
            TOP_ONLY_VIEW: {
                "master": list(TOP_ONLY_MASTER),
                "slot": list(TOP_ONLY_SIZE),
                "colors": ["dark"],
                "defaultColor": "dark",
                "gaps": False,
            }
        },
        "states": {st: _top_state_semantics(st) for st in TOP_STATES},
        "anchors": {TOP_ONLY_VIEW: top_anchors} if top_anchors else {},
        "anim": {},
        "attribution": ATTR,
    }
    with open(os.path.join(dst, "model.json"), "w", encoding="utf-8") as f:
        json.dump(model, f, ensure_ascii=False, indent=2)
        f.write("\n")

    d = os.path.join(dst, TOP_ONLY_VIEW, "dark")
    total = sum(os.path.getsize(os.path.join(d, f)) for f in os.listdir(d))
    print(f"{TOP_ONLY_VIEW}: states={installed}/{len(TOP_STATES)} gaps={gaps} missing={missing}")
    print(f"total {total} bytes ({total/1024:.1f} KB)")
    return 0


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--src", required=True, help="bake<NN> 目录（用其 slot/ 子目录）")
    ap.add_argument("--model", default="lynk01", help="车型资产目录名，默认 lynk01")
    ap.add_argument("--dst", default=None, help="覆盖安装目标目录")
    ap.add_argument("--top-only", action="store_true",
                    help="只装 cardtop（俯视横置 32 门态 + 门缝橙色高亮），用于「只用俯视」方案")
    args = ap.parse_args()

    here = os.path.dirname(os.path.abspath(__file__))
    dst = os.path.abspath(
        args.dst or os.path.join(here, "..", "..", "app", "src", "main", "assets", "car3d", args.model)
    )
    slot = os.path.join(args.src, "slot")
    if args.top_only and os.path.isdir(os.path.join(args.src, "top32")):
        slot = os.path.join(args.src, "top32")   # 俯视 32 态单独目录
    if not os.path.isdir(slot):
        print(f"!! 没找到 {slot}", file=sys.stderr)
        return 1

    for sub in list(VIEWS) + ["hero"]:
        d = os.path.join(dst, sub)
        if os.path.isdir(d):
            shutil.rmtree(d)

    if args.top_only:
        return run_top_only(args, dst, slot)

    installed = {}
    view_colors = {}

    for view, (size, prefix) in VIEWS.items():
        if view in COLOR_VIEWS:
            have = []
            for color in VIEW_COLORS.get(view, COLORS):
                out_dir = os.path.join(dst, view, color)
                n, missing = _install_states(slot, out_dir, prefix, size, STATES, color)
                installed[(view, color)] = (n, missing)
                if n:
                    have.append(color)
                elif color != "dark":
                    # 没有该颜色的图就删掉空目录，运行时按缺色回退 dark
                    shutil.rmtree(out_dir, ignore_errors=True)
            view_colors[view] = have
            continue

        out_dir = os.path.join(dst, view)
        n, missing = _install_states(slot, out_dir, prefix, size, STATES)
        installed[view] = (n, missing)

        # 动画帧：只装 top 家族（card/hero 不播动画，省体积）
        anims = {}
        for kind, ms in (ANIMS.items() if view == "top" else []):
            frames = 0
            for i in range(12):
                src = pick_source(slot, "dialog", f"anim_{kind}_{i}", VIEWS["top"][0])
                if not src:
                    break
                convert(src, VIEWS["top"][0], os.path.join(out_dir, f"anim_{kind}_{i}.webp"))
                frames += 1
            if frames:
                anims[kind] = {"frames": frames, "ms": ms, "kinds": [kind]}
        installed[(view, "_anim")] = anims

    win_rects = _install_winpatches(slot, dst, view_colors)

    meta = MODELS.get(args.model, {"model": args.model, "name": args.model, "dims": {}})
    views = {}
    for v, (size, _p) in VIEWS.items():
        if v in COLOR_VIEWS:
            have = view_colors.get(v) or []
            if have:
                views[v] = {"master": list(size), "slot": list(size),
                            "colors": have,
                            "defaultColor": "black" if "black" in have else have[0]}
        elif installed.get(v, (0,))[0]:
            views[v] = {"master": list(size), "slot": list(size)}

    for _v, _r in win_rects.items():
        if _v in views:
            views[_v]["winpatches"] = _r

    model = {
        "format": "cardesk.car3d/1",
        "model": meta["model"],
        "name": meta["name"],
        "dims": meta["dims"],
        "views": views,
        "states": {st: ({} if st == "closed" else _state_semantics(st)) for st in STATES},
        "anchors": _load_anchors(slot),
        "anim": installed.get(("top", "_anim"), {}),
        "attribution": ATTR,
    }
    with open(os.path.join(dst, "model.json"), "w", encoding="utf-8") as f:
        json.dump(model, f, ensure_ascii=False, indent=2)
        f.write("\n")

    total = sum(os.path.getsize(os.path.join(r, f)) for r, _, fs in os.walk(dst) for f in fs)
    for key, val in installed.items():
        if isinstance(val, dict):
            continue
        n, missing = val
        label = key if isinstance(key, str) else "/".join(key)
        print(f"{label:12s} states={n}/{len(STATES)} missing={missing}")
    for cv, cols in view_colors.items():
        for color in cols:
            d = os.path.join(dst, cv, color)
            sz = sum(os.path.getsize(os.path.join(d, f)) for f in os.listdir(d))
            print(f"{cv}/{color:6s} {len(os.listdir(d))} 张 {sz} B")
    print(f"colors={view_colors} anim={list(model['anim'])}")
    print(f"total {total} bytes ({total/1024:.1f} KB)")
    if "hero" not in views and "top" not in views:
        print("!! hero / top 一张都没装上", file=sys.stderr)
        return 1
    return 0


def _load_anchors(slot):
    """读烘焙侧 anchors.json，把 tire_xx 归一成 tires.xx 结构。"""
    p = os.path.join(slot, "anchors.json")
    if not os.path.exists(p):
        return {}
    try:
        raw = json.load(open(p, encoding="utf-8"))
    except Exception:
        return {}
    out = {}
    for view, items in raw.items():
        v = {}
        tires = {}
        for k, val in items.items():
            if not isinstance(val, (list, tuple)) or len(val) < 2:
                continue
            if k.startswith("tire_"):
                tires[k[5:]] = [round(float(val[0]), 4), round(float(val[1]), 4)]
            else:
                v[k] = [round(float(val[0]), 4), round(float(val[1]), 4)]
        if tires:
            v["tires"] = tires
        out[view] = v
    return out


def _state_semantics(st):
    if st in ("lf", "rf", "lb", "rb"):
        return {"door": {st: 1}}
    if st == "all":
        return {"door": {"lf": 1, "rf": 1, "lb": 1, "rb": 1}}
    if st == "win":
        return {"window": {"lf": 1, "rf": 1, "lb": 1, "rb": 1}}
    if st == "trunk":
        return {"trunk": 1}
    return {}


if __name__ == "__main__":
    sys.exit(main())
