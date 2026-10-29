#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
生成 CarDesk 新版 UI 设计板（HTML）+ 逐屏 PNG。

设计语言：**浮屿 / Flat Isle**
- 简约：一次只讲一件事，卡片少、留白多
- 扁平：无投影、无玻璃拟态、无描边发光；层次靠留白 + 明度差 + 1px 描边
- 圆角：卡片 28 / 内嵌 18 / 图标底板 16 / 胶囊 999（设计坐标 1920×1080）
- 日夜：同一套 token，两套值

用法：
    python3 build_design_board.py            # 生成 index.html
    python3 build_design_board.py --png      # 顺便用 headless Chrome 导出 PNG
"""
import base64
import html
import os
import subprocess
import sys
import tempfile

OUT_DIR = os.path.dirname(os.path.abspath(__file__))
HTML_PATH = os.path.join(OUT_DIR, "index.html")
PNG_DIR = os.path.join(OUT_DIR, "png")
FONT_PATH = os.path.join(OUT_DIR, "fonts", "Outfit-latin.woff2")
CHROME = "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"

# ---------------------------------------------------------------- 设计 token

TOKENS = {
    "night": {
        "bg": "#0F1116", "bgGlow": "#191D26",
        "surface": "#1F232D", "surfaceSoft": "#171A22", "surfaceStrong": "#2A2F3B",
        "stroke": "#333947", "strokeSoft": "#262B36",
        "text": "#EDEFF3", "textDim": "#A2AAB8", "textFaint": "#757E8E",
        "accent": "#33C6B4", "accentSoft": "rgba(51,198,180,0.16)", "accentInk": "#0B1413",
        "ok": "#4CC38A", "warn": "#E0A340", "danger": "#E2574C",
        "dock": "#1C2029", "scrim": "rgba(8,9,12,0.66)",
        "carBody": "#3D465E", "road": "#181B23",
    },
    "day": {
        "bg": "#F1F3F7", "bgGlow": "#FFFFFF",
        "surface": "#FFFFFF", "surfaceSoft": "#F4F6F9", "surfaceStrong": "#E8EBF1",
        "stroke": "#DFE3EA", "strokeSoft": "#EAEDF2",
        "text": "#191C23", "textDim": "#565D6B", "textFaint": "#8B92A0",
        "accent": "#0F9C8E", "accentSoft": "rgba(15,156,142,0.12)", "accentInk": "#FFFFFF",
        "ok": "#1F9D5F", "warn": "#B7791F", "danger": "#C8413A",
        "dock": "#FFFFFF", "scrim": "rgba(24,26,32,0.38)",
        "carBody": "#8C97B0", "road": "#E6E9EF",
    },
}

# ---------------------------------------------------------------- 图标（lucide 24×24）

ICON = {
    "bluetooth": '<path d="m7 7 10 10-5 5V2l5 5L7 17"/>',
    "wifi": '<path d="M5 13a10 10 0 0 1 14 0"/><path d="M8.5 16.5a5 5 0 0 1 7 0"/><path d="M2 8.82a15 15 0 0 1 20 0"/><path d="M12 20h.01"/>',
    "signal": '<path d="M2 20h.01"/><path d="M7 20v-4"/><path d="M12 20v-8"/><path d="M17 20V8"/><path d="M22 4v16"/>',
    "battery": '<rect x="2" y="7" width="16" height="10" rx="2"/><path d="M22 11v2"/><path d="M6 11v2"/><path d="M10 11v2"/><path d="M14 11v2"/>',
    "pin": '<path d="M20 10c0 6-8 12-8 12s-8-6-8-12a8 8 0 0 1 16 0Z"/><circle cx="12" cy="10" r="3"/>',
    "sun": '<circle cx="12" cy="12" r="4"/><path d="M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M6.3 17.7l-1.4 1.4M19.1 4.9l-1.4 1.4"/>',
    "music": '<path d="M9 18V5l12-2v13"/><circle cx="6" cy="18" r="3"/><circle cx="18" cy="16" r="3"/>',
    "snow": '<path d="M12 2v20M4.2 6.5l15.6 11M19.8 6.5l-15.6 11"/><path d="M12 6l2-2M12 6l-2-2M12 18l2 2M12 18l-2 2"/>',
    "grid": '<rect x="3" y="3" width="7" height="7" rx="1"/><rect x="14" y="3" width="7" height="7" rx="1"/><rect x="3" y="14" width="7" height="7" rx="1"/><rect x="14" y="14" width="7" height="7" rx="1"/>',
    "panel": '<rect x="3" y="4" width="18" height="16" rx="3"/><path d="M9 4v16"/>',
    "circle": '<circle cx="12" cy="12" r="8"/><circle cx="12" cy="12" r="3"/>',
    "360": '<path d="M3 12a9 9 0 1 0 3-6.7"/><path d="M3 4v5h5"/>',
    "play": '<path d="M7 4.5v15l12-7.5z"/>',
    "pause": '<rect x="6" y="4" width="4" height="16" rx="1"/><rect x="14" y="4" width="4" height="16" rx="1"/>',
    "prev": '<path d="M18 5v14L8 12z"/><path d="M6 5v14"/>',
    "next": '<path d="M6 5v14l10-7z"/><path d="M18 5v14"/>',
    "power": '<path d="M12 3v9"/><path d="M6.6 6.6a8 8 0 1 0 10.8 0"/>',
    "fan": '<circle cx="12" cy="12" r="2"/><path d="M12 10c0-4 1-6 3-6s2 4-3 6"/><path d="M14 12c4 0 6 1 6 3s-4 2-6-3"/><path d="M12 14c0 4-1 6-3 6s-2-4 3-6"/><path d="M10 12c-4 0-6-1-6-3s4-2 6 3"/>',
    "seat": '<path d="M6 4h8a3 3 0 0 1 3 3v8"/><path d="M6 4v9a3 3 0 0 0 3 3h8"/><path d="M6 20h13"/>',
    "defrost": '<path d="M12 3v18M5 7h14M7 11h10M9 15h6"/>',
    "chevron": '<path d="m9 6 6 6-6 6"/>',
    "chevronDown": '<path d="m6 9 6 6 6-6"/>',
    "plus": '<path d="M12 5v14M5 12h14"/>',
    "minus": '<path d="M5 12h14"/>',
    "x": '<path d="M18 6 6 18M6 6l12 12"/>',
    "check": '<path d="M20 6 9 17l-5-5"/>',
    "list": '<path d="M8 6h13M8 12h13M8 18h13M3 6h.01M3 12h.01M3 18h.01"/>',
    "palette": '<circle cx="13.5" cy="6.5" r="1"/><circle cx="17.5" cy="10.5" r="1"/><circle cx="8.5" cy="7.5" r="1"/><circle cx="6.5" cy="12.5" r="1"/><path d="M12 2a10 10 0 0 0 0 20c1 0 1.8-.8 1.8-1.8 0-.5-.2-.9-.5-1.2-.3-.3-.5-.7-.5-1.2 0-1 .8-1.8 1.8-1.8H16a6 6 0 0 0 6-6c0-4.4-4.5-8-10-8Z"/>',
    "key": '<circle cx="7.5" cy="15.5" r="4.5"/><path d="m10.7 12.3 8.3-8.3M17 6l2.5 2.5M14.5 8.5 17 11"/>',
    "camera": '<path d="M3 8.5A2.5 2.5 0 0 1 5.5 6h1.8l1-2h7.4l1 2h1.8A2.5 2.5 0 0 1 21 8.5v9A2.5 2.5 0 0 1 18.5 20h-13A2.5 2.5 0 0 1 3 17.5Z"/><circle cx="12" cy="13" r="3.5"/>',
    "download": '<path d="M12 3v12"/><path d="m7 11 5 5 5-5"/><path d="M4 20h16"/>',
    "share": '<circle cx="18" cy="5" r="3"/><circle cx="6" cy="12" r="3"/><circle cx="18" cy="19" r="3"/><path d="m8.6 13.5 6.8 4M15.4 6.5l-6.8 4"/>',
    "search": '<circle cx="11" cy="11" r="7"/><path d="m20 20-3.5-3.5"/>',
    "home": '<path d="m3 10 9-7 9 7v9a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2Z"/><path d="M9 21v-8h6v8"/>',
    "gauge": '<path d="M12 14 16 9"/><path d="M4.5 18a9 9 0 1 1 15 0"/>',
    "thermo": '<path d="M14 14.8V5a2 2 0 1 0-4 0v9.8a4 4 0 1 0 4 0Z"/>',
    "fuel": '<path d="M3 22V4a2 2 0 0 1 2-2h6a2 2 0 0 1 2 2v18"/><path d="M2 22h12"/><path d="M13 9h3a2 2 0 0 1 2 2v6a2 2 0 0 0 2 2h1"/><path d="M17 8h2"/>',
    "disc": '<circle cx="12" cy="12" r="9"/><circle cx="12" cy="12" r="2.5"/>',
    "note": '<path d="M4 4h16v16H4z"/><path d="M8 9h8M8 13h8M8 17h5"/>',
    "calendar": '<rect x="3" y="5" width="18" height="16" rx="2"/><path d="M8 3v4M16 3v4M3 10h18"/>',
    "clock": '<circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/>',
    "route": '<circle cx="6" cy="19" r="3"/><circle cx="18" cy="5" r="3"/><path d="M9 19h4a4 4 0 0 0 4-4V9"/>',
    "layers": '<path d="m12 2 9 5-9 5-9-5z"/><path d="m3 12 9 5 9-5"/><path d="m3 17 9 5 9-5"/>',
    "phone": '<rect x="6" y="2" width="12" height="20" rx="3"/><path d="M11 18h2"/>',
    "cast": '<path d="M2 16.1A5.1 5.1 0 0 1 7.1 21"/><path d="M2 12.05A9.95 9.95 0 0 1 11.95 22"/><path d="M2 8V6a2 2 0 0 1 2-2h16a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2h-6"/><path d="M2 20h2"/>',
    "arrowUp": '<path d="M12 19V5"/><path d="m5 12 7-7 7 7"/>',
    "cornerRight": '<path d="M15 14l5-5-5-5"/><path d="M4 20v-7a4 4 0 0 1 4-4h12"/>',
    "cornerLeft": '<path d="M9 14 4 9l5-5"/><path d="M20 20v-7a4 4 0 0 0-4-4H4"/>',
    "trash": '<path d="M3 6h18M8 6V4h8v2M6 6l1 14h10l1-14"/>',
    "copy": '<rect x="9" y="9" width="12" height="12" rx="2"/><path d="M5 15H4a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1h10a1 1 0 0 1 1 1v1"/>',
    "edit": '<path d="M12 20h9"/><path d="M16.5 3.5a2.1 2.1 0 0 1 3 3L7 19l-4 1 1-4Z"/>',
    "eye": '<path d="M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7-10-7-10-7Z"/><circle cx="12" cy="12" r="3"/>',
    "info": '<circle cx="12" cy="12" r="9"/><path d="M12 11v5M12 8h.01"/>',
    "wind": '<path d="M3 8h10a3 3 0 1 0-3-3"/><path d="M3 12h13a3 3 0 1 1-3 3"/><path d="M3 16h7"/>',
    "sunrise": '<path d="M12 3v6M5.6 9.6 9 6.2M2 18h20"/><path d="M8 18a4 4 0 0 1 8 0"/><path d="m18.4 9.6-1.6 1.6"/>',
    "moon": '<path d="M21 12.8A9 9 0 1 1 11.2 3a7 7 0 0 0 9.8 9.8Z"/>',
    "refresh": '<path d="M3 12a9 9 0 0 1 15-6.7L21 8"/><path d="M21 3v5h-5"/><path d="M21 12a9 9 0 0 1-15 6.7L3 16"/><path d="M3 21v-5h5"/>',
    "car": '<path d="M5 17h14M6 17v2M18 17v2"/><path d="M4 13h16l-1.6-4.6A3 3 0 0 0 15.6 6H8.4a3 3 0 0 0-2.8 2.4Z"/><circle cx="7.5" cy="14.5" r="1"/><circle cx="16.5" cy="14.5" r="1"/>',
    "batteryLow": '<rect x="2" y="7" width="16" height="10" rx="2"/><path d="M22 11v2"/><path d="M6 11v2"/>',
}


def icon(name, size=24, sw=1.8, cls="", color="currentColor"):
    body = ICON.get(name, ICON["circle"])
    return (
        f'<svg class="ic {cls}" width="{size}" height="{size}" viewBox="0 0 24 24" '
        f'fill="none" stroke="{color}" stroke-width="{sw}" stroke-linecap="round" '
        f'stroke-linejoin="round" aria-hidden="true">{body}</svg>'
    )


# ---------------------------------------------------------------- CSS

def build_css():
    css = ["""
*,*::before,*::after{box-sizing:border-box}
html,body{margin:0;padding:0}
body{
  background:var(--board-bg);
  color:var(--board-fg);
  font-family:"Outfit","PingFang SC","Hiragino Sans GB","Microsoft YaHei",system-ui,sans-serif;
  -webkit-font-smoothing:antialiased;
}
:root{
  --board-bg:#f7f6f3; --board-fg:#14171e; --board-muted:#6d7382; --board-line:#e4e1d8;
  --r-card:28px; --r-inner:18px; --r-tile:16px; --r-pill:999px;
}
.board{max-width:1360px;margin:0 auto;padding:56px 32px 120px}
.board.night{--board-bg:#0E1015;--board-fg:#E9ECF2;--board-muted:#8A93A3;--board-line:#232733}

.hero h1{font-size:40px;line-height:1.15;margin:0 0 12px;font-weight:600;letter-spacing:-0.01em}
.hero p.lede{font-size:16px;line-height:1.6;color:var(--board-muted);margin:0 0 8px;max-width:70ch}
.rulebar{display:flex;flex-wrap:wrap;gap:10px;margin:22px 0 0}
.rule{font-size:13px;padding:6px 12px;border:1px solid var(--board-line);border-radius:999px;color:var(--board-muted)}

h2.sec{font-size:26px;font-weight:600;margin:72px 0 6px;letter-spacing:-0.005em}
p.secsub{font-size:14px;color:var(--board-muted);margin:0 0 22px;max-width:76ch;line-height:1.6}

.screenwrap{border:1px solid var(--board-line);border-radius:20px;overflow:hidden;margin-bottom:26px;background:var(--board-bg)}
.screenbar{display:flex;align-items:center;gap:14px;padding:12px 18px;border-bottom:1px solid var(--board-line)}
.screenbar .idx{font-size:12px;color:var(--board-muted);font-variant-numeric:tabular-nums}
.screenbar .nm{font-size:15px;font-weight:600}
.screenbar .tag{font-size:12px;color:var(--board-muted);margin-left:auto}
.sheet{display:flex;gap:20px;padding:20px;flex-wrap:wrap}
.pane{flex:1 1 0;min-width:0}
.pane figcaption{font-size:12px;color:var(--board-muted);margin:10px 0 0;letter-spacing:.02em}
figure{margin:0}

/* ---- 模拟屏（设计坐标 1920×1080，缩放 0.5 展示） ---- */
.mockup{width:960px;height:540px;overflow:hidden;position:relative;border-radius:12px}
.screen{width:1920px;height:1080px;position:relative;transform:scale(.5);transform-origin:top left;
  font-family:"Outfit","PingFang SC","Hiragino Sans GB","Microsoft YaHei",system-ui,sans-serif;overflow:hidden;color:var(--text)}
.bgwash{position:absolute;inset:0;background:var(--bg)}
.bgwash::after{content:"";position:absolute;inset:0;background:radial-gradient(120% 90% at 50% 0%,var(--bgGlow) 0%,var(--bg) 62%)}
.abs{position:absolute}
.card{background:var(--surface);border:1px solid var(--stroke);border-radius:var(--r-card)}
.soft{background:var(--surfaceSoft);border-radius:var(--r-inner)}
.pill{background:var(--surfaceSoft);border:1px solid var(--stroke);border-radius:var(--r-pill)}
.t{color:var(--text)}.d{color:var(--textDim)}.f{color:var(--textFaint)}
.acc{color:var(--accent)}.warnc{color:var(--warn)}.okc{color:var(--ok)}
.ic{vertical-align:middle}
.row{display:flex;align-items:center}
.lbl{font-size:18px;letter-spacing:.02em}
.micro{font-size:15px;letter-spacing:.04em}
.sec-title{font-size:30px;font-weight:600;letter-spacing:-.01em}
.hairline{height:1px;background:var(--stroke);width:100%}
.switch{width:84px;height:48px;border-radius:999px;background:var(--surfaceStrong);position:relative;border:1px solid var(--stroke)}
.switch.on{background:var(--accent);border-color:var(--accent)}
.switch i{position:absolute;top:4px;left:4px;width:38px;height:38px;border-radius:50%;background:var(--surface)}
.switch.on i{left:40px;background:#fff}
.seg{display:inline-flex;gap:6px;background:var(--surfaceSoft);border-radius:999px;padding:5px;border:1px solid var(--stroke)}
.seg span{font-size:20px;padding:9px 22px;border-radius:999px;color:var(--textDim)}
.seg span.on{background:var(--accent);color:var(--accentInk);font-weight:600}
.tile{width:96px;height:96px;border-radius:var(--r-tile);background:var(--surfaceSoft);border:1px solid var(--stroke);
  display:flex;align-items:center;justify-content:center;color:var(--textDim)}
.chip{font-size:18px;padding:8px 18px;border-radius:var(--r-pill);background:var(--surfaceSoft);border:1px solid var(--stroke);color:var(--textDim)}
.chip.on{background:var(--accentSoft);border-color:var(--accent);color:var(--accent)}
.bar{height:12px;border-radius:999px;background:var(--surfaceStrong);overflow:hidden}
.bar i{display:block;height:100%;border-radius:999px;background:var(--accent)}
.bar.warn i{background:var(--warn)}
.kv{font-size:19px;color:var(--textDim)}
.kv b{display:block;font-size:30px;color:var(--text);font-weight:600;letter-spacing:-.01em;margin-top:2px}
.kv.sm b{font-size:24px}
.sheetnote{font-size:15px;color:var(--textFaint);line-height:1.5}
.iconrow{display:flex;gap:10px;align-items:center;color:var(--textDim)}
.statusicons{display:flex;gap:13px;align-items:center;color:var(--textDim)}
.appicon{width:76px;height:76px;border-radius:20px;display:flex;align-items:center;justify-content:center}
.step{width:46px;height:46px;border-radius:50%;background:var(--surfaceSoft);border:1px solid var(--stroke);
  display:inline-flex;align-items:center;justify-content:center;font-size:26px;color:var(--textDim)}
.temp{font-size:36px;font-weight:600;color:var(--text);letter-spacing:-.01em}
.fanseg{display:flex;gap:4px}
.fanseg i{display:block;width:8px;height:14px;border-radius:3px;background:var(--surfaceStrong)}
.fanseg i.on{background:var(--accent)}
.map{position:absolute;inset:0;overflow:hidden;border-radius:var(--r-card)}
.road{position:absolute;background:var(--road)}
"""]
    return "\n".join(css)


def font_face_css():
    """把 Outfit（可变字重 latin 子集）以 base64 内联，保证 index.html **完全自包含**（离线可看）。

    第四十八轮之前用的是 `<link>` 引 Google Fonts —— 离线打开会掉回系统字体，
    设计板的西文排版跟 PNG 对不上。改成 data URI 内联后，HTML 不再依赖任何外部网络资源。
    """
    if not os.path.exists(FONT_PATH):
        return ""
    with open(FONT_PATH, "rb") as f:
        b64 = base64.b64encode(f.read()).decode("ascii")
    return (
        "@font-face{font-family:'Outfit';font-style:normal;font-weight:100 900;"
        "font-display:swap;src:url(data:font/woff2;base64," + b64 + ") format('woff2');"
        "unicode-range:U+0000-00FF,U+0131,U+0152-0153,U+02BB-02BC,U+02C6,U+02DA,U+02DC,"
        "U+2000-206F,U+2074,U+20AC,U+2122,U+2191,U+2193,U+2212,U+2215,U+FEFF,U+FFFD}"
    )


def tok_css(theme):
    t = TOKENS[theme]
    return "\n".join(
        f"  --{k}:{v};" for k, v in t.items()
    )


# ---------------------------------------------------------------- 通用组件

def status_bar(t, mode="pill"):
    """顶部状态栏（新设计：扁平分段，不再是透明浮层 + 渐变遮罩）。"""
    bg = t["surface"] if mode == "pill" else "transparent"
    st = t["stroke"] if mode == "pill" else "transparent"
    parts = []
    parts.append(
        f'<div class="abs" style="left:29px;top:17px;height:43px;display:flex;align-items:center;gap:12px;">'
        f'<div style="width:36px;height:36px;border-radius:12px;background:var(--accent);color:var(--accentInk);'
        f'display:flex;align-items:center;justify-content:center;font-size:17px;font-weight:700">C</div>'
        f'<div class="pill" style="display:flex;align-items:center;gap:8px;padding:7px 15px;">'
        f'<span style="width:8px;height:8px;border-radius:50%;background:var(--accent)"></span>'
        f'<span style="font-size:13px;color:var(--text)">驾驶模式 · 待命</span></div>'
        f"</div>"
    )
    parts.append(
        f'<div class="abs" style="left:50%;top:17px;height:43px;transform:translateX(-50%);display:flex;align-items:center;gap:8px;">'
        f'<div class="pill" style="display:flex;align-items:center;gap:7px;padding:8px 15px;">'
        f'{icon("pin",15,1.2)}<span style="font-size:13px;color:var(--textDim)">位置获取中</span></div>'
        f'<div class="pill" style="display:flex;align-items:center;gap:7px;padding:8px 15px;">'
        f'{icon("sun",16,1.2)}<span style="font-size:15px;color:var(--text)">26°</span></div>'
        f"</div>"
    )
    parts.append(
        f'<div class="abs" style="right:29px;top:17px;height:43px;display:flex;align-items:center;gap:17px;">'
        f'<div class="statusicons">{icon("bluetooth",16)}</div>'
        f'<div class="statusicons">{icon("wifi",16)}</div>'
        f'<div class="statusicons">{icon("signal",16)}</div>'
        f'<div class="statusicons">{icon("battery",17)}<span style="font-size:15px;color:var(--text)">62%</span></div>'
        f'<div style="font-size:25px;font-weight:600;letter-spacing:-0.01em">15:41</div>'
        f"</div>"
    )
    return "".join(parts)


# ---------------------------------------------------------------- Dock 几何（第五十三轮）

# 与 App 侧**逐项对应**（改一处必须改两处）：
#   app/.../core/DockLayout.kt      —— KEY_LEFT / KEY_SLOT / KEY_GAP / KEY_COUNT /
#                                       GROUP_GAP / MIN_GROUP_GAP / MEDIA_RIGHT_INSET
#   app/.../ui/shell/DockBarView.kt —— CLIMATE_SLOT / CLIMATE_ICON_GAP / CLIMATE_POWER_GAP /
#                                       TEMP_MINUS_W / TEMP_ZONE_W / TEMP_PLUS_W / TEMP_GAP /
#                                       FAN_W / CLIMATE_TEXT_SIZE / CLIMATE_TEXT_PAD
#
# 第五十二轮之前这里还是「空调簇**绝对居中**」，算出来是
# **导航键挤在最左 → 中间空 179px → 空调簇和媒体卡只隔 16px 挤成一坨**。
# 第五十三轮按用户反馈改成**三组等距铺开**（GROUP_GAP = 104），
# 并把空调簇瘦身（图标槽 64 → 48、温度块 238 → 224），顺带修掉
# 「电源图标压住主驾温度 − 按钮」的重叠。
# 快捷应用槽位的示意图标（第五十四轮）。
# 与 App 侧离屏渲染注入的图标**同色同形**（50×50 圆角 14 色块 + 白色字母）。
QUICK_APP_ICONS = [
    ("#33C6B4", "N"),
    ("#E0A340", "T"),
    ("#4CC38A", "M"),
]

DOCK_GEOM = {
    "panel_w": 1872, "panel_top": 968, "panel_h": 104, "panel_r": 36,
    # 左段：4 个导航键
    "key_left": 22, "key_slot": 60, "key_gap": 14, "key_count": 4, "key_top": 22,
    # 段与段之间的呼吸位（第五十四轮：不再刻意留大空隙）
    "section_gap": 10,
    # 空调簇
    "slot": 48, "icon_gap": 8, "power_gap": 16,
    "temp_minus": 48, "temp_zone": 112, "temp_plus": 48, "temp_gap": 8,
    "temp_item": 224, "fan_w": 100, "ac_w": 75, "auto_w": 104,
    # 第二段：音乐卡
    "media_w": 365, "media_h": 88, "media_top": 8,
    "media_cover": 60, "media_pad": 14,
    # 最右段：快捷应用
    "quick_slots": 3, "quick_tile": 56, "quick_gap": 8, "quick_right": 22,
}

# 主页「房子」字形的几何（第七十七轮：原来是圆环 + 圆点，看不出是主页）。
# 与 App 侧**逐项对应**（改一处必须改两处）：
#   app/.../ui/shell/HomeHouseIcon.kt —— WALL_RATIO
#   app/.../ui/shell/DockBarView.kt    —— HOME_ICON_UNIT
HOME_ICON_UNIT = 13.0
HOME_WALL_RATIO = 0.78


def dock_sections():
    """四段布局的边界（与 App 的 `DockLayout.sections` 同一套算式）。

    ```
    导航键 22..304 │10│ 音乐卡 314..679 │10│ 空调簇 689..1656 │10│ 快捷应用 1666..1850
    ```

    第五十四轮：**音乐卡与空调簇对调**，段间不再刻意留白，
    省下来的宽度给最右边的快捷应用段（3 个 56 槽 + 2 个 8 间隙 = 184）。
    """
    g = DOCK_GEOM
    keys_right = g["key_left"] + g["key_count"] * g["key_slot"] + (g["key_count"] - 1) * g["key_gap"]
    quick_w = g["quick_slots"] * g["quick_tile"] + (g["quick_slots"] - 1) * g["quick_gap"]
    quick_right = g["panel_w"] - g["quick_right"]
    quick_left = quick_right - quick_w
    cluster_right = quick_left - g["section_gap"]
    cluster_left = cluster_right - dock_cluster_width()
    media_right = cluster_left - g["section_gap"]
    media_left = media_right - g["media_w"]
    return {
        "keys_left": g["key_left"], "keys_right": keys_right,
        "media_left": media_left, "media_right": media_right,
        "cluster_left": cluster_left, "cluster_right": cluster_right,
        "quick_left": quick_left, "quick_right": quick_right,
    }



def dock_cluster_width():
    """空调簇的**占位宽**（与 App 的 `climateClusterWidth()` 同一套算式）。
    ```
    [icon 48]×3 + [gap 8]×3 + AUTO 104 + A/C 75 + [gap 8]
    + 副驾温度 224 + 风量 100 + 主驾温度 224 + [gap 16] + 电源 48
    = 967
    ```
    """
    g = DOCK_GEOM
    return (
        g["slot"] * 4 + g["icon_gap"] * 4 + g["power_gap"]
        + g["auto_w"] + g["ac_w"] + g["temp_item"] * 2 + g["fan_w"]
    )


def _glyph_home():
    """主页键字形：**房子**（App `HomeHouseIcon`，第七十七轮由「圆环 + 圆点」改成房子）。

    形状与 App **完全一致**：
    - 屋顶是一个**开口的尖角**（左斜 + 右斜两笔，不封底）；
    - 房身是「左墙 → 地面 → 右墙」（上边开口，墙头正好落在屋顶那条线上）。

    `HOME_ICON_UNIT` / `HOME_WALL_RATIO` 照抄 App
    （`ui/shell/HomeHouseIcon.kt` 与 `DockBarView.HOME_ICON_UNIT`），
    改了 App 就要改这里 —— 设计板与实现不许走样。
    """
    c = 15.5
    u = HOME_ICON_UNIT
    # 取两位小数：13 * 0.78 在浮点里是 10.140000000000001，直接写进 SVG 会变成
    # 「5.359999999999999」这种脏字符串。
    w = round(u * HOME_WALL_RATIO, 2)
    r = lambda v: round(v, 2)  # noqa: E731
    return (
        f'<svg width="31" height="31" viewBox="0 0 31 31" fill="none" stroke="currentColor" '
        f'stroke-width="2.8" stroke-linecap="round" stroke-linejoin="round">'
        f'<path d="M{r(c - u)} {c}L{c} {r(c - u)}L{r(c + u)} {c}"/>'
        f'<path d="M{r(c - w)} {c}V{r(c + u)}H{r(c + w)}V{c}"/></svg>'
    )


def _glyph_apps():
    """应用键字形：3×3 实心小方块（App `drawApps`）。"""
    rects = "".join(
        f'<rect x="{0.5 + c * 11}" y="{0.5 + r * 11}" width="7" height="7" rx="2"/>'
        for r in range(3) for c in range(3)
    )
    return (
        f'<svg width="26" height="26" viewBox="0 0 26 26" fill="currentColor">{rects}</svg>'
    )


def _glyph_split():
    """分屏键字形：两片并排的圆角矩形（App `drawSplit`）。"""
    return (
        f'<svg width="31" height="31" viewBox="0 0 31 31" fill="none" stroke="currentColor" '
        f'stroke-width="2.6"><rect x="0.5" y="1.5" width="18" height="28" rx="4"/>'
        f'<rect x="16.5" y="1.5" width="14" height="28" rx="4"/></svg>'
    )


def _glyph_360():
    """360 键字形：圆角矩形 + 文字（App `draw360`）。"""
    return (
        f'<div style="width:48px;height:30px;border:2px solid currentColor;border-radius:12px;'
        f'display:flex;align-items:center;justify-content:center;font-size:22px;font-weight:700;'
        f'line-height:1">360</div>'
    )


def _temp_item_html(x, label):
    """温度块（**簇局部坐标** x = 块的左边界，224 宽）。

    结构 `[− 48] [间隙 8] [温度区 112] [间隙 8] [+ 48]` —— 与 App 的 `tempItem` 一一对应：
    数字 46px（App 是 50px，放不下 112 的温度区会自动等比缩到 ~46）+ 24px 度号，
    下面一行 13px 的「主驾 / 副驾」。
    """
    g = DOCK_GEOM
    minus_c = x + g["temp_minus"] / 2
    zone_l = x + g["temp_minus"] + g["temp_gap"]
    plus_c = zone_l + g["temp_zone"] + g["temp_gap"] + g["temp_plus"] / 2
    minus_svg = (
        f'<svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" '
        f'stroke-width="3" stroke-linecap="round"><path d="M4 12h16"/></svg>'
    )
    plus_svg = (
        f'<svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" '
        f'stroke-width="3" stroke-linecap="round"><path d="M12 4v16M4 12h16"/></svg>'
    )
    return "".join([
        f'<div class="abs" style="left:{minus_c - 12}px;top:40px;color:var(--textDim)">{minus_svg}</div>',
        f'<div class="abs" style="left:{plus_c - 12}px;top:40px;color:var(--textDim)">{plus_svg}</div>',
        f'<div class="abs" style="left:{zone_l}px;top:18px;width:{g["temp_zone"]}px;display:flex;'
        f'align-items:baseline;justify-content:center">'
        f'<span style="font-size:46px;font-weight:600;letter-spacing:-0.02em;color:var(--text)">23.5</span>'
        f'<span style="font-size:24px;color:var(--textDim)">°</span></div>',
        f'<div class="abs" style="left:{zone_l}px;top:76px;width:{g["temp_zone"]}px;text-align:center;'
        f'font-size:13px;color:var(--textDim)">{label}</div>',
    ])


def _fan_html(x):
    """风量块（**簇局部坐标** x = 左边，100 宽）：风扇图标 + 7 段电平。"""
    seg = "".join(f'<i class="{"on" if i == 0 else ""}"></i>' for i in range(7))
    return (
        f'<div class="abs" style="left:{x + 35}px;top:24px;color:var(--textDim)">{icon("fan",30)}</div>'
        f'<div class="abs" style="left:{x + 10}px;top:69px;display:flex;gap:4px">{seg}</div>'
    )


def _hvac_cluster_html():
    """空调簇（**簇局部坐标**，宽 = `dock_cluster_width()`）。

    从右往左：后除霜 · 前除霜 · 内外循环 · AUTO · A/C · 副驾温度 · 风量 · 主驾温度 · 电源
    —— 与 App 侧 `drawClimateCluster` 的顺序**逐项一致**（算式也一一对应）。
    """
    g = DOCK_GEOM
    x = dock_cluster_width()
    x -= g["slot"]; defrost_r = x + g["slot"] / 2
    x -= g["icon_gap"]
    x -= g["slot"]; defrost_f = x + g["slot"] / 2
    x -= g["icon_gap"]
    x -= g["slot"]; circ = x + g["slot"] / 2
    x -= g["icon_gap"]
    x -= g["auto_w"]; auto_c = x + g["auto_w"] / 2
    x -= g["ac_w"]; ac_c = x + g["ac_w"] / 2
    x -= g["icon_gap"]
    x -= g["temp_item"]; passenger = x
    x -= g["fan_w"]; fan = x
    x -= g["temp_item"]; driver = x
    x -= g["power_gap"]
    x -= g["slot"]; power = x + g["slot"] / 2
    assert abs(x) < 0.01, f"空调簇算式与绘制对不上：剩 {x}px"
    return "".join([
        f'<div class="abs" style="left:{power - 15}px;top:37px;color:var(--textDim)">{icon("power",30,2.6)}</div>',
        _temp_item_html(driver, "主驾"),
        _fan_html(fan),
        _temp_item_html(passenger, "副驾"),
        f'<div class="abs" style="left:{ac_c - 30}px;top:36px;width:60px;text-align:center;'
        f'font-size:28px;font-weight:600;color:var(--textDim)">A/C</div>',
        f'<div class="abs" style="left:{auto_c - 40}px;top:36px;width:80px;text-align:center;'
        f'font-size:28px;font-weight:600;color:var(--textDim)">AUTO</div>',
        f'<div class="abs" style="left:{circ - 15}px;top:37px;color:var(--textDim)">{icon("refresh",30)}</div>',
        f'<div class="abs" style="left:{defrost_f - 15}px;top:37px;color:var(--textDim)">{icon("defrost",30)}</div>',
        f'<div class="abs" style="left:{defrost_r - 15}px;top:37px;color:var(--textDim)">{icon("snow",30)}</div>',
    ])


def _media_card_html(media, artist):
    """媒体卡（**卡局部坐标**，371×88）。

    与 App 的 `drawMedia` 一一对应：封面 60（左 14 上 14，圆角 16）· 歌名 22px（基线 40）·
    歌手 16px（基线 62）· 上一首/播放/下一首三个键 · 底部 3px 进度条。
    """
    g = DOCK_GEOM
    cover = g["media_cover"]
    prog = round((g["media_w"] - 28) * 0.34)
    return "".join([
        f'<div class="abs" style="left:14px;top:14px;width:{cover}px;height:{cover}px;'
        f'border-radius:16px;background:var(--accentSoft);display:flex;align-items:center;'
        f'justify-content:center;color:var(--accent)">{icon("music",30)}</div>',
        f'<div class="abs" style="left:90px;top:22px;font-size:22px;color:var(--text)">{media}</div>',
        f'<div class="abs" style="left:90px;top:48px;font-size:16px;color:var(--textDim)">{artist}</div>',
        f'<div class="abs" style="left:224px;top:32px;color:var(--textDim)">{icon("prev",24)}</div>',
        f'<div class="abs" style="left:274px;top:32px;color:var(--textDim)">{icon("play",24)}</div>',
        f'<div class="abs" style="left:325px;top:32px;color:var(--textDim)">{icon("next",24)}</div>',
        f'<div class="abs" style="left:14px;top:83px;width:{prog}px;height:3px;border-radius:2px;'
        f'background:var(--accent)"></div>',
    ])


def _quick_apps_html():
    """最右段：**快捷应用**槽位（第五十四轮）。

    3 个 56 见方的槽位，槽与槽之间 8px。
    槽里放的是**真实应用图标**（App 侧画 `PackageManager` 拿到的图标），
    设计板用「圆角色块 + 字母」示意 —— 形状与颜色对齐 App 的注入图标。
    """
    g = DOCK_GEOM
    s = dock_sections()
    out = []
    for i, (color, letter) in enumerate(QUICK_APP_ICONS):
        slot_left = s["quick_left"] + i * (g["quick_tile"] + g["quick_gap"])
        slot_top = (g["panel_h"] - g["quick_tile"]) / 2
        # 图标本身比槽位小 6px（两边各留 3px），与 App 的 `tile - 6` 一致
        out.append(
            f'<div class="abs" style="left:{slot_left + 3}px;top:{slot_top + 3}px;width:50px;height:50px;'
            f'border-radius:14px;background:{color};color:#fff;display:flex;align-items:center;'
            f'justify-content:center;font-size:26px;font-weight:700">{letter}</div>'
        )
    return "".join(out)


def dock(t, variant="pill", media="小镇姑娘", artist="陶喆", slots=False):
    """底部 Dock（第五十四轮：**四段** · 音乐卡与空调簇对调 · 段间不刻意留白）。

    照 App 的 `DockBarView` + `core/DockLayout`：
    **主页 / 应用抽屉 / 分屏 / 360** · **音乐卡** · **空调簇** · **快捷应用（3 槽）**。

    第五十三轮是「三组等距」（间隙各 104），用户反馈「挤在一块，中间还有很长的空隙」——
    这一轮把音乐卡与空调簇**对调**，段间只留 [section_gap] 一口气，
    省下来的宽度全部给最右边的**快捷应用**段。
    """
    g = DOCK_GEOM
    if variant == "pill":
        s = dock_sections()
        cluster_w = dock_cluster_width()

        # —— 左段：4 个导航入口（字形按 App 的真实画法）——
        glyphs = [_glyph_home(), _glyph_apps(), _glyph_split(), _glyph_360()]
        items = []
        for i, gl in enumerate(glyphs):
            color = "var(--accent)" if i == 0 else "var(--textDim)"
            items.append(
                f'<div class="tile" style="width:{g["key_slot"]}px;height:{g["key_slot"]}px;'
                f'border-radius:20px;color:{color}">{gl}</div>'
            )

        return (
            f'<div style="position:absolute;left:24px;right:24px;top:{g["panel_top"]}px;'
            f'height:{g["panel_h"]}px;background:var(--dock);border:1px solid var(--stroke);'
            f'border-radius:{g["panel_r"]}px">'
            f'<div class="abs" style="left:{g["key_left"]}px;top:{g["key_top"]}px;display:flex;'
            f'gap:{g["key_gap"]}px">{"".join(items)}</div>'
            f'<div class="abs" style="left:{s["media_left"]}px;top:{g["media_top"]}px;'
            f'width:{g["media_w"]}px;height:{g["media_h"]}px;background:var(--surfaceSoft);'
            f'border-radius:28px;overflow:hidden">{_media_card_html(media, artist)}</div>'
            f'<div class="abs" style="left:{s["cluster_left"]}px;top:0;width:{cluster_w}px;'
            f'height:{g["panel_h"]}px">{_hvac_cluster_html()}</div>'
            f'{_quick_apps_html()}'
            f"</div>"
        )
    # 槽位条
    slot_defs = [
        ("home", "home", "桌面"), ("apps", "grid", "应用"), ("split", "panel", "分屏"),
        ("car", "car", "车辆"), ("music", "music", "音乐"), ("hvac", "snow", "空调"),
    ]
    chips = "".join(
        f'<div class="chip">{icon(ic,22)}<span style="margin-left:8px">{name}</span></div>'
        for _, ic, name in slot_defs
    )
    chips += f'<div class="chip on" style="margin-left:8px">{icon("note",22)}<span style="margin-left:8px">歌词</span></div>'
    return (
        f'<div class="abs" style="left:24px;right:24px;top:968px;height:96px;padding:0 26px;'
        f'background:var(--dock);border:1px solid var(--stroke);border-radius:36px;'
        f'display:flex;align-items:center;gap:12px">{chips}'
        f'<div style="margin-left:auto;display:flex;align-items:center;gap:16px;color:var(--textDim)">'
        f'{icon("clock",26)}<span style="font-size:22px">15:41</span></div>'
        f"</div>"
    )


def car_svg(open_doors=(), size=200, label_rows=True, theme="night"):
    """俯视车模（SVG）。open_doors: 0=左前 1=右前 2=左后 3=右后（开着的门会摆出去）。"""
    t = TOKENS[theme]
    body = t["carBody"]
    glass = "#20263A" if theme == "night" else "#C9D2E4"
    tire = "#0E1218" if theme == "night" else "#2A2F3A"
    seam = t["stroke"]
    warn = t["warn"]

    doors = [
        (0, 22, 58, 32, 104, 27, 58, 45),    # idx, x, y, x2, y2, hx, hy, ang
        (1, 88, 58, 98, 104, 93, 58, -45),
        (2, 22, 108, 32, 158, 27, 158, -45),
        (3, 88, 108, 98, 158, 93, 158, 45),
    ]
    door_svg = []
    for idx, x1, y1, x2, y2, hx, hy, ang in doors:
        opened = idx in open_doors
        fill = warn if opened else "none"
        sw = 2.2 if opened else 1.4
        op = "0.95" if opened else "0.55"
        g = ""
        if opened:
            g = f'<g transform="rotate({ang} {hx} {hy})">'
        door_svg.append(
            f'{g}<rect x="{x1}" y="{y1}" width="{x2-x1}" height="{y2-y1}" rx="3" '
            f'fill="{fill}" fill-opacity="0.55" stroke="{warn if opened else seam}" '
            f'stroke-width="{sw}" stroke-opacity="{op}"/>'
            + ("</g>" if opened else "")
        )

    windows = "".join(
        f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="2.5" fill="{glass}" fill-opacity="0.85" stroke="{seam}" stroke-width="1"/>'
        for x, y, w, h in [
            (34, 58, 9, 46), (77, 58, 9, 46), (34, 110, 9, 48), (77, 110, 9, 48),
        ]
    )
    return f"""
<svg viewBox="0 0 120 240" width="{size}" height="{size*2}" xmlns="http://www.w3.org/2000/svg">
  <rect x="12" y="60" width="7" height="30" rx="3" fill="{tire}"/>
  <rect x="101" y="60" width="7" height="30" rx="3" fill="{tire}"/>
  <rect x="12" y="146" width="7" height="30" rx="3" fill="{tire}"/>
  <rect x="101" y="146" width="7" height="30" rx="3" fill="{tire}"/>
  <path d="M60 5 C42 5 31 17 27 36 L20 92 C18 104 18 132 20 144 L27 202 C31 221 42 234 60 234
           C78 234 89 221 93 202 L100 144 C102 132 102 104 100 92 L93 36 C89 17 78 5 60 5 Z"
        fill="{body}" stroke="{seam}" stroke-width="1.4"/>
  <path d="M38 30 L82 30 L76 52 L44 52 Z" fill="{glass}"/>
  <rect x="42" y="54" width="36" height="52" rx="6" fill="{glass}"/>
  <path d="M42 108 L78 108 L82 130 L38 130 Z" fill="{glass}"/>
  <rect x="40" y="188" width="40" height="20" rx="4" fill="{glass}" opacity="0.7"/>
  {windows}
  {"".join(door_svg)}
  <rect x="42" y="212" width="36" height="16" rx="4" fill="none" stroke="{seam}" stroke-width="1.4"/>
  <rect x="44" y="10" width="32" height="16" rx="4" fill="none" stroke="{seam}" stroke-width="1.4"/>
</svg>"""


def traffic_light_chip(w, theme, lamp="danger", seconds=12):
    """红绿灯 chip（照 App `ScenePage.drawTrafficLight` 第七十九轮重画后的版式）。

    版式：**一个大圆 + 下方读秒**，右对齐到卡片内容右缘。

    ```
        ●        ← 大圆（直径 46），灯色实心
       12 秒     ← 读秒，在圆的下方
    ```

    App 侧几何（`ScenePage.kt` 的 `TRAFFIC_*` 常量）：
    - 圆直径 `TRAFFIC_LAMP_D = 46`
    - chip 左右内边距 `TRAFFIC_PAD_X = 14`、上 `TRAFFIC_PAD_TOP = 10`、下 `TRAFFIC_PAD_BOTTOM = 8`
    - 圆底到读秒文字顶 `TRAFFIC_GAP = 5`
    - 读秒字号 `TRAFFIC_SECONDS_SIZE = 30`、「秒」`TRAFFIC_UNIT_SIZE = 15`
    - chip 圆角 20

    卡内相对坐标：chip 右缘 = `w - 26`（= App 的 `x + w - pad`），
    chip 顶 = `rowTop(26) + 8 = 34`（App 的 `rowTop + 8`）。
    """
    right = w - 26
    chip_w = 81
    chip_left = right - chip_w
    chip_top = 34
    chip_h = 10 + 46 + 5 + 30 + 8
    circle_left = chip_left + (chip_w - 46) / 2
    circle_top = chip_top + 10
    # 文本基线 ≈ 圆底 + 间距 + 上伸部（30px 粗体约 24）= 119；div 的 top 换算成 95
    text_top = circle_top + 46 + 5 + 24 - 30 * 0.8
    return f"""
  <div class="abs" style="left:{chip_left}px;top:{chip_top}px;width:{chip_w}px;height:{chip_h}px;
    border-radius:20px;background:var(--surfaceSoft);border:1px solid var(--stroke)"></div>
  <div class="abs" style="left:{circle_left}px;top:{circle_top}px;width:46px;height:46px;
    border-radius:50%;background:var(--{lamp})"></div>
  <div class="abs" style="left:{chip_left}px;top:{text_top:.1f}px;width:{chip_w}px;text-align:center;
    font-size:30px;font-weight:600;color:var(--{lamp});line-height:1">{seconds}<span
    style="font-size:15px;font-weight:400;color:var(--textDim)"> 秒</span></div>"""


def nav_guide_card(x, y, w, theme):
    """导航引导卡（照 App `ScenePage.drawGuide` 的版式：转向图标 + 距离 + 道路 + 车道线 + 剩余/ETA）。

    卡内相对坐标沿用 App 的 pad=26 / rowH=110 / lanes marginTop=22 / bottom marginTop=20。
    """
    t = TOKENS[theme]
    # 车道线：App 侧 `MAX_GUIDE_LANES = 8`（参考 `paintGuideLanes:1982-2018` 最多 8 条）
    lane_icons = ["arrowUp", "arrowUp", "cornerRight", "arrowUp",
                  "arrowUp", "cornerLeft", "arrowUp", "arrowUp"]
    lane_chip = (
        f'<div class="abs" style="left:26px;top:158px;width:468px;height:70px;border-radius:22px;'
        f'background:var(--surfaceSoft);border:1px solid var(--stroke)"></div>'
    )
    lanes = "".join(
        f'<div class="abs" style="left:{41 + i*57}px;top:174px;width:38px;height:38px;'
        f'display:flex;align-items:center;justify-content:center;'
        f'color:{"var(--accent)" if i in (2, 5) else "var(--textDim)"}">{icon(ic,32)}</div>'
        for i, ic in enumerate(lane_icons)
    )
    traffic = traffic_light_chip(w, theme)
    return f"""
<div class="abs card" style="left:{x}px;top:{y}px;width:{w}px;height:296px">
  <div class="abs" style="left:26px;top:37px;width:88px;height:88px;border-radius:26px;
    background:var(--accentSoft);display:flex;align-items:center;justify-content:center">
    <svg width="54" height="64" viewBox="0 0 54 64" fill="none" aria-hidden="true">
      <path d="M24 60V30Q24 18 36 18H40" stroke="var(--accent)" stroke-width="8"
        stroke-linecap="butt" stroke-linejoin="round"/>
      <path d="M40 6L52 18L40 30Z" fill="var(--accent)"/>
    </svg>
  </div>
  <div class="abs" style="left:136px;top:32px;display:flex;align-items:baseline;gap:7px">
    <span style="font-size:70px;font-weight:600;letter-spacing:-0.02em;line-height:1">600</span>
    <span style="font-size:24px;color:var(--textDim)">米</span>
  </div>
  <div class="abs" style="left:136px;top:106px;font-size:24px">文景路</div>
  {traffic}
  {lane_chip}
  {lanes}
  <div class="abs" style="left:26px;top:228px;display:flex;align-items:baseline;gap:2px">
    <span style="font-size:18px;color:var(--textDim)">剩余 </span>
    <span style="font-size:21px">3.2</span>
    <span style="font-size:18px;color:var(--textDim);margin-left:2px"> 公里 · </span>
    <span style="font-size:21px">8</span>
    <span style="font-size:18px;color:var(--textDim);margin-left:2px"> 分</span>
  </div>
  <div class="abs" style="right:26px;top:228px;display:flex;align-items:baseline;gap:6px">
    <span style="font-size:21px">15:49</span>
    <span style="font-size:18px;color:var(--textDim)">到达</span>
  </div>
</div>"""


def lyric_block(theme, prev, current, nxt, top, main_size=52, side_size=24, max_w=960, cx=960):
    """居中三行歌词（上一句 / 当前句带擦除高亮 / 下一句），照 App `drawCenterLyric`。"""
    t = TOKENS[theme]
    half = max_w // 2
    wipe = (
        f'background:linear-gradient(90deg,var(--accent) 38%,var(--text) 38%);'
        f'-webkit-background-clip:text;background-clip:text;color:transparent'
    )
    return f"""
<div class="abs" style="left:{cx - half}px;top:{top}px;width:{max_w}px;text-align:center">
  <div style="font-size:{side_size}px;color:var(--textDim);line-height:1.2">{prev}</div>
  <div style="font-size:{main_size}px;font-weight:600;letter-spacing:-0.01em;line-height:1.24;margin-top:22px">
    <span style="{wipe}">{current}</span>
  </div>
  <div style="font-size:{side_size}px;color:var(--textDim);line-height:1.2;margin-top:24px">{nxt}</div>
</div>"""


def tire_card(x, y, w, h, theme, values=("240", "240", "238", "235")):
    """胎压卡（照 App `ScenePage.drawTireCard`：**中间竖置俯视车模** + 左列 FL/RL + 右列 FR/RR）。

    App 侧：卡宽 `TIRE_CARD_W = 350`、卡高 150，车模 72×144 竖置车头朝上，
    数值 28px + `kPa` 14px（间距 8px），两行 baseline = top+46 / top+104。
    """
    fl, fr, rl, rr = values
    rows = (24, 82)   # 对应 App 的 baseline top+46 / top+104（CSS 顶 = baseline − 22）
    left = ""
    right = ""
    for i, top in enumerate(rows):
        lv = (fl, rl)[i]   # 左列：FL, RL
        rv = (fr, rr)[i]   # 右列：FR, RR
        left += (
            f'<div class="abs" style="left:18px;top:{top}px;display:flex;align-items:baseline;gap:8px">'
            f'<span style="font-size:28px;font-weight:600;color:var(--text)">{lv}</span>'
            f'<span style="font-size:14px;color:var(--textDim)">kPa</span></div>'
        )
        right += (
            f'<div class="abs" style="right:18px;top:{top}px;display:flex;align-items:baseline;gap:8px">'
            f'<span style="font-size:28px;font-weight:600;color:var(--text)">{rv}</span>'
            f'<span style="font-size:14px;color:var(--textDim)">kPa</span></div>'
        )
    return f"""
<div class="abs card" style="left:{x}px;top:{y}px;width:{w}px;height:{h}px;overflow:hidden">
  {left}{right}
  <div class="abs" style="left:{(w - 72) // 2}px;top:{(h - 144) // 2}px">{car_svg((), 72, theme=theme)}</div>
</div>"""


def car_status_card(x, y, w, h, theme, open_doors=(), title="车况"):
    """车况卡：左侧一列 + 中间俯视车模 + 右侧一列，三区互不重叠。"""
    t = TOKENS[theme]
    left_rows = ["左前门", "左前窗", "左后门", "左后窗", "后备箱"]
    right_rows = ["右前门", "右前窗", "右后门", "右后窗", "引擎盖"]
    opened = set(open_doors)
    left_state = ["关", "关", "关", "关", "关"]
    right_state = ["关", "关", "关", "关", "关"]
    if 0 in opened:
        left_state[0] = "开"
    if 2 in opened:
        left_state[2] = "开"
    if 1 in opened:
        right_state[0] = "开"
    if 3 in opened:
        right_state[2] = "开"

    car_w = 92
    car_x = (w - car_w) // 2
    top0, step = 46, 38

    def col(rows, states, align):
        out = []
        for i, (r, s) in enumerate(zip(rows, states)):
            color = "var(--warn)" if s == "开" else "var(--textDim)"
            top = top0 + i * step
            if align == "left":
                out.append(
                    f'<div class="abs" style="left:24px;top:{top}px;font-size:21px;color:var(--textDim)">'
                    f'{r}<span style="margin-left:22px;color:{color};font-weight:500">{s}</span></div>'
                )
            else:
                out.append(
                    f'<div class="abs" style="right:24px;top:{top}px;font-size:21px;color:var(--textDim)">'
                    f'<span style="color:{color};font-weight:500">{s}</span>'
                    f'<span style="margin-left:22px">{r}</span></div>'
                )
        return "".join(out)

    return f"""
<div class="abs card" style="left:{x}px;top:{y}px;width:{w}px;height:{h}px;overflow:hidden">
  <div class="abs" style="left:24px;top:14px;font-size:21px;color:var(--textFaint)">{title}</div>
  {col(left_rows, left_state, "left")}
  {col(right_rows, right_state, "right")}
  <div class="abs" style="left:{car_x}px;top:52px">{car_svg(open_doors, car_w - 10, theme=theme)}</div>
</div>"""


def kv_card(x, y, w, h, title, pairs, theme, cols=2, small=False, gap=62, base=54):
    t = TOKENS[theme]
    rows = []
    cw = (w - 36) // cols
    for i, (k, v) in enumerate(pairs):
        r, c = divmod(i, cols)
        rows.append(
            f'<div class="abs" style="left:{18 + c*cw}px;top:{base + r*gap}px;width:{cw-14}px">'
            f'<div class="kv sm"><span>{k}</span><b>{v}</b></div></div>'
        )
    return (
        f'<div class="abs card" style="left:{x}px;top:{y}px;width:{w}px;height:{h}px">'
        f'<div class="abs" style="left:18px;top:14px;font-size:22px;color:var(--textFaint)">{title}</div>'
        f'{"".join(rows)}</div>'
    )


# ---------------------------------------------------------------- 页面

def screen_home(theme):
    t = TOKENS[theme]
    app_icons = []
    for i, (ic, lbl) in enumerate([("note", "CarDesk"), ("calendar", "日历"), ("phone", "电话")]):
        app_icons.append(
            f'<div class="abs" style="left:{24 + i*100}px;top:{42}px;text-align:center">'
            f'<div class="appicon" style="width:68px;height:68px;border-radius:18px;'
            f'background:var(--surfaceSoft);color:var(--textDim)">{icon(ic,30)}</div>'
            f'<div style="font-size:17px;color:var(--textDim);margin-top:8px">{lbl}</div></div>'
        )
    quick = []
    for i, (ic, lbl) in enumerate([("seat", "主驾加热"), ("wind", "主驾通风"), ("eye", "最大风挡")]):
        quick.append(
            f'<div class="abs" style="left:{22 + i*92}px;top:{44}px;text-align:center">'
            f'<div class="tile" style="width:72px;height:72px;border-radius:20px">{icon(ic,30)}</div>'
            f'<div style="font-size:16px;color:var(--textDim);margin-top:8px">{lbl}</div></div>'
        )
    return f"""
<div class="bgwash"></div>
{status_bar(t)}
{nav_guide_card(44, 73, 520, theme)}
{lyric_block(theme, "我看见 夜空中 最亮的星", "风雨里追赶 雾里分不清影踪", "请指引我靠近你", 412)}
<div class="abs" style="left:1488px;top:75px;width:388px;text-align:right">
  <div style="font-size:96px;font-weight:600;line-height:1;letter-spacing:-0.02em">26<span style="font-size:38px">°</span></div>
  <div style="font-size:20px;color:var(--textDim);margin-top:12px">{icon("sun",20)} 晴 · 空气优</div>
</div>
{kv_card(1488, 255, 388, 160, "行程数据", [("总里程", "12,480 km"), ("平均油耗", "6.8 L/100km"),
                                          ("行程里程", "128.4 km"), ("行程时长", "1 小时 23 分")], theme, gap=52, base=44)}
{car_status_card(1488, 429, 388, 250, theme)}
<div class="abs card" style="left:44px;top:764px;width:338px;height:150px">
  <div class="abs" style="left:18px;top:10px;font-size:21px;color:var(--textFaint)">快捷应用</div>
  {"".join(app_icons)}
</div>
<div class="abs card" style="left:402px;top:764px;width:338px;height:150px">
  <div class="abs" style="left:18px;top:10px;font-size:21px;color:var(--textFaint)">通用音乐 ▾</div>
  <div class="abs" style="left:18px;top:42px;width:68px;height:68px;border-radius:18px;background:var(--surfaceSoft)"></div>
  <div class="abs" style="left:100px;top:52px;font-size:26px;font-weight:600">暂无播放</div>
  <div class="abs" style="right:18px;top:45px;width:60px;height:60px;border-radius:50%;background:var(--surfaceStrong);display:flex;align-items:center;justify-content:center;color:var(--text)">{icon("play",26)}</div>
</div>
<div class="abs card" style="left:760px;top:764px;width:338px;height:150px">
  <div class="abs" style="left:18px;top:10px;font-size:21px;color:var(--textFaint)">快捷车控</div>
  {"".join(quick)}
</div>
{tire_card(1118, 764, 350, 150, theme)}
<div class="abs card" style="left:1488px;top:764px;width:388px;height:150px">
  <div class="abs" style="left:22px;top:14px;font-size:21px;color:var(--textDim)">续航</div>
  <div class="abs" style="left:22px;top:40px;font-size:50px;font-weight:600;letter-spacing:-0.01em">386<span style="font-size:20px;color:var(--textFaint)"> km</span></div>
  <div class="abs" style="left:22px;top:100px;width:344px"><div class="bar"><i style="width:62%"></i></div></div>
  <div class="abs" style="left:22px;top:118px;font-size:17px;color:var(--textFaint)">燃料剩余 · 62%</div>
</div>
<div class="abs" style="left:44px;top:440px;display:flex;flex-direction:column;gap:22px">
  <div class="tile" style="width:76px;height:76px;border-radius:26px">{icon("music",30)}</div>
  <div class="tile" style="width:76px;height:76px;border-radius:26px">{icon("snow",30)}</div>
</div>
{dock(t)}
<div class="abs" style="left:50%;top:926px;transform:translateX(-50%);display:flex;gap:10px;align-items:center;
  background:var(--surface);border:1px solid var(--stroke);border-radius:999px;padding:6px 16px">
  <span style="width:18px;height:8px;border-radius:999px;background:var(--accent)"></span>
  <span style="width:8px;height:8px;border-radius:50%;background:var(--textFaint)"></span>
</div>
"""


def screen_nav(theme):
    t = TOKENS[theme]
    roads = []
    for i, (x, y, w, h, rot) in enumerate([
        (-40, 210, 1200, 26, 0), (240, -60, 22, 700, 0), (560, -60, 18, 700, 0),
        (980, -60, 24, 700, 0), (120, 430, 1100, 20, 0), (860, 200, 900, 14, 0),
    ]):
        roads.append(f'<div class="road" style="left:{x}px;top:{y}px;width:{w}px;height:{h}px;transform:rotate({rot}deg)"></div>')
    route = "".join(roads)
    return f"""
<div class="bgwash"></div>
{status_bar(t)}
<div class="abs card" style="left:44px;top:73px;width:1240px;height:867px;overflow:hidden">
  <div class="map">{route}
    <div style="position:absolute;left:430px;top:300px;width:180px;height:180px;border-radius:50%;
      border:8px solid var(--accent);opacity:.9"></div>
    <div style="position:absolute;left:512px;top:382px;width:16px;height:16px;border-radius:50%;background:var(--accent)"></div>
  </div>
  <div class="abs" style="left:24px;top:20px;display:flex;gap:10px;color:var(--textFaint)">
    <span style="font-size:20px">···</span>
  </div>
  <div class="abs" style="left:24px;bottom:24px;background:var(--surface);border:1px solid var(--stroke);
    border-radius:999px;padding:12px 24px;font-size:22px;color:var(--text)">文景路 · 剩余 600 m</div>
</div>
<div class="abs card" style="left:1304px;top:73px;width:572px;height:867px">
  <div class="abs" style="left:24px;top:22px;display:flex;align-items:center;gap:10px;font-size:20px;color:var(--textDim)">
    <span style="width:10px;height:10px;border-radius:50%;background:var(--accent)"></span>通用音乐 ▾
    <span style="color:var(--textFaint)">已暂停</span>
  </div>
  <div class="abs" style="left:24px;top:66px;width:96px;height:96px;border-radius:26px;background:var(--surfaceSoft)"></div>
  <div class="abs" style="left:140px;top:78px;font-size:38px;font-weight:600">小镇姑娘</div>
  <div class="abs" style="left:140px;top:126px;font-size:22px;color:var(--textDim)">陶喆</div>
  <div class="abs" style="left:24px;top:318px;width:524px;text-align:center">
    <div style="font-size:38px;font-weight:600;color:var(--text);line-height:1.3">我看见 夜空中 最亮的星</div>
    <div style="font-size:27px;color:var(--textDim);margin-top:26px">请指引我靠近你</div>
    <div style="font-size:27px;color:var(--textFaint);margin-top:18px">风雨里追赶 雾里分不清影踪</div>
  </div>
  <div class="abs" style="left:24px;top:640px;width:524px"><div class="bar" style="height:8px"><i style="width:34%"></i></div></div>
  <div class="abs" style="left:24px;top:662px;width:524px;display:flex;justify-content:space-between;font-size:18px;color:var(--textFaint)">
    <span>1:24</span><span>4:05</span>
  </div>
  <div class="abs" style="left:0;top:700px;width:572px;display:flex;align-items:center;justify-content:center;gap:56px;color:var(--text)">
    {icon("prev",36)}{icon("play",48)}{icon("next",36)}
  </div>
</div>
{dock(t)}
"""


def screen_apps(theme):
    t = TOKENS[theme]
    cells = []
    icons = ["calendar", "phone", "clock", "note", "camera", "share", "download", "search", "copy", "trash", "eye", "info"]
    for i in range(18):
        ic = icons[i % len(icons)]
        r, c = divmod(i, 6)
        cells.append(
            f'<div class="abs" style="left:{40 + c*300}px;top:{150 + r*150}px;text-align:center">'
            f'<div class="appicon" style="margin:0 auto;background:var(--surfaceSoft);color:var(--textDim)">{icon(ic,36)}</div>'
            f'<div style="font-size:18px;color:var(--textDim);margin-top:12px">应用 {i+1}</div></div>'
        )
    return f"""
<div class="bgwash"></div>
{status_bar(t)}
<div class="abs card" style="left:44px;top:73px;width:1876px;height:867px">
  <div class="abs" style="left:40px;top:34px;font-size:40px;font-weight:600">全部应用</div>
  <div class="abs" style="right:40px;top:40px">
    <div class="seg"><span class="on">全屏打开</span><span>小窗打开</span></div>
  </div>
  <div class="abs" style="left:40px;top:100px;width:1796px" >
    <div class="hairline"></div>
  </div>
  {"".join(cells)}
</div>
{dock(t)}
"""


def screen_hvac(theme):
    t = TOKENS[theme]

    def wheel():
        return f"""
<div class="abs" style="left:40px;top:56px;width:260px;height:260px">
  <svg viewBox="0 0 100 100" width="260" height="260">
    <circle cx="50" cy="50" r="44" fill="none" stroke="var(--surfaceStrong)" stroke-width="8"/>
    <circle cx="50" cy="50" r="44" fill="none" stroke="var(--accent)" stroke-width="8"
      stroke-linecap="round" stroke-dasharray="276.5" stroke-dashoffset="60" transform="rotate(-90 50 50)"/>
  </svg>
  <div style="position:absolute;inset:0;display:flex;align-items:center;justify-content:center;
    font-size:62px;font-weight:600;letter-spacing:-0.02em">23.5°</div>
</div>"""

    def side(cx, label):
        return f"""
<div class="abs" style="left:{cx}px;top:110px;width:340px;text-align:center">
  <div style="font-size:26px;color:var(--textDim)">{label}</div>
  {wheel()}
  <div class="abs" style="left:0;right:0;top:352px;display:flex;gap:64px;justify-content:center">
    <div class="tile" style="width:72px;height:72px;border-radius:24px">{icon("minus",30)}</div>
    <div class="tile" style="width:72px;height:72px;border-radius:24px">{icon("plus",30)}</div>
  </div>
  <div class="abs" style="left:0;right:0;top:452px;display:flex;gap:24px;justify-content:center">
    <div class="chip">加热</div><div class="chip">通风</div>
  </div>
</div>"""

    bars = "".join(
        f'<div style="width:56px;height:56px;border-radius:16px;'
        f'background:{"var(--accent)" if i == 0 else "var(--surfaceStrong)"}"></div>' for i in range(7)
    )
    modes = "".join(
        f'<div style="width:156px;height:68px;border-radius:22px;'
        f'background:{"var(--accentSoft)" if i == 2 else "var(--surfaceSoft)"};'
        f'border:1px solid {"var(--accent)" if i == 2 else "var(--stroke)"};display:flex;align-items:center;'
        f'justify-content:center;font-size:24px;color:{"var(--accent)" if i == 2 else "var(--textDim)"}">{m}</div>'
        for i, m in enumerate(["吹脸", "吹脚", "脸+脚", "吹窗", "脸+窗", "脚+窗", "全吹"])
    )
    funcs = "".join(
        f'<div style="width:156px;height:68px;border-radius:22px;'
        f'background:var(--surfaceSoft);border:1px solid var(--stroke);display:flex;align-items:center;'
        f'justify-content:center;font-size:24px;color:var(--textDim)">{m}</div>'
        for m in ["A/C", "AUTO", "外循环", "温度同步"]
    )
    return f"""
<div class="bgwash"></div>
<div class="abs" style="left:0;right:0;top:0;bottom:0;background:var(--scrim)"></div>
<div class="abs card" style="left:140px;top:73px;width:1640px;height:867px">
  <div class="abs" style="left:40px;top:28px;font-size:40px;font-weight:600">空调</div>
  <div class="abs" style="left:150px;top:42px;font-size:22px;color:var(--textDim)">已关机</div>
  <div class="abs" style="right:36px;top:28px;width:150px;height:66px;border-radius:999px;border:1px solid var(--stroke);
    display:flex;align-items:center;justify-content:center;font-size:24px;color:var(--textDim)">关闭</div>
  {side(60, "主驾")}
  {side(1240, "副驾")}
  <div class="abs" style="left:460px;top:110px;width:720px;display:flex;flex-direction:column;align-items:center">
    <div style="font-size:26px;color:var(--textDim)">风量 0 档</div>
    <div style="display:flex;gap:14px;margin-top:18px">{bars}</div>
    <div style="font-size:26px;color:var(--textDim);margin-top:30px">吹风模式</div>
    <div style="display:flex;flex-wrap:wrap;gap:16px;margin-top:18px;justify-content:center;width:700px">{modes}</div>
    <div style="display:flex;gap:16px;margin-top:24px">{funcs}</div>
    <div style="display:flex;gap:24px;margin-top:26px">
      <div class="chip" style="font-size:24px;padding:14px 40px">前除霜</div>
      <div class="chip" style="font-size:24px;padding:14px 40px">后除霜</div>
    </div>
  </div>
</div>
"""


# ---------------------------------------------------------------- 设置页导航树

# 与 App 的 `SettingsOverlay.mergedGroups` **逐项一致**（第五十五轮起 20 项 / 5 组）。
# 坐标口径也照 App：
#   组标题 y 起 100，每组标题 +28，每个条目 +34，每组结束 +10；
#   树可视区 100..884（TREE_TOP / TREE_VIEW_H），VIN 行在 888。
SETTINGS_NAV_GROUPS = [
    ("智能功能", ["启动切换", "按键增强", "方控仪表", "计划任务", "桌面自启"]),
    ("布局与显示", ["日夜模式", "双画中画", "仪表歌词", "Dock 状态栏", "Dock 快捷应用"]),
    ("网络与升级", ["局域网传输", "在线更新"]),
    ("车控", ["360 环影", "启停控制", "自动热点", "ADB 调试"]),
    ("系统", ["默认设置", "系统诊断", "重启车机", "诊断日志"]),
]

# 树可视区高（与 App 的 TREE_VIEW_H 一致）：884 之后的条目在 App 里是滚下去的。
SETTINGS_TREE_VIEW_H = 784


def settings_nav(active_label):
    """设置页左栏导航树（与 App 的 `switchTab` 同一套坐标）。

    @param active_label 当前高亮的条目文案
    """
    items = []
    y = 100
    for gname, labels in SETTINGS_NAV_GROUPS:
        items.append(
            f'<div class="abs" style="left:36px;top:{y}px;font-size:16px;color:var(--textFaint)">{gname}</div>'
        )
        y += 28
        for label in labels:
            active = label == active_label
            items.append(
                f'<div class="abs" style="left:24px;top:{y}px;width:332px;height:32px;'
                f'border-radius:10px;background:{"var(--accentSoft)" if active else "transparent"};'
                f'border:1px solid {"var(--accent)" if active else "transparent"};'
                f'display:flex;align-items:center;padding:0 12px;font-size:20px;'
                f'color:{"var(--accent)" if active else "var(--textDim)"}">{label}</div>'
            )
            y += 34
        y += 10
    return f"""
  <div style="width:380px;height:100%;border-right:1px solid var(--stroke);position:relative">
    <div class="abs" style="left:36px;top:26px;font-size:34px;font-weight:600">桌面设置</div>
    <div class="abs" style="left:0;top:0;width:380px;height:884px;overflow:hidden">
      {"".join(items)}
    </div>
    <div class="abs" style="left:36px;top:888px;font-size:13px;color:var(--textFaint);
      font-family:ui-monospace,Menlo,Consolas,monospace">VIN：（空）</div>
    <div class="abs" style="right:36px;top:890px;font-size:16px;color:var(--accent);font-weight:600">开通免激活</div>
  </div>
"""


def screen_settings(theme):
    t = TOKENS[theme]
    rows = []
    rows.append(
        '<div class="abs" style="left:48px;top:190px;width:964px"><div class="hairline"></div></div>'
    )
    toggles = [
        ("自动导航投屏", "开机后将导航投送到仪表屏", True),
        ("自动切换驾驶模式", "根据场景切换经济 / 舒适 / 运动", True),
    ]
    y = 120
    for name, desc, on in toggles:
        rows.append(
            f'<div class="abs" style="left:48px;top:{y}px;width:820px">'
            f'<div style="font-size:28px;font-weight:600">{name}</div>'
            f'<div style="font-size:19px;color:var(--textDim);margin-top:8px">{desc}</div></div>'
            f'<div class="abs switch {"on" if on else ""}" style="left:928px;top:{y+14}px"><i></i></div>'
            f'<div class="abs" style="left:48px;top:{y+92}px;width:964px"><div class="hairline"></div></div>'
        )
        y += 130
    rows.append(
        f'<div class="abs" style="left:48px;top:{y}px;font-size:24px;color:var(--textDim)">目标驾驶模式</div>'
        f'<div class="abs" style="left:48px;top:{y+40}px">'
        f'<div class="seg"><span class="on">经济模式</span><span>舒适模式</span><span>运动模式</span>'
        f'<span>赛道模式</span><span>个性化</span></div></div>'
        f'<div class="abs" style="left:48px;top:{y+140}px;width:900px;font-size:18px;color:var(--textFaint);line-height:1.6">'
        f'参考 App 的「启动切换」只有导航投屏与驾驶模式两项；桌面自启在「桌面自启」页，'
        f'开机自动打开 ADB 在「车控 → ADB 调试」页。</div>'
    )
    return f"""
<div class="bgwash"></div>
<div class="abs" style="left:0;right:0;top:0;bottom:0;background:var(--scrim)"></div>
<div class="abs card" style="left:240px;top:80px;width:1440px;height:920px;display:flex">
  {settings_nav("启动切换")}
  <div style="flex:1;height:100%;position:relative;padding-left:0">
    <div class="abs" style="left:48px;top:34px;font-size:40px;font-weight:600">启动切换</div>
    <div class="abs" style="right:36px;top:36px;width:150px;height:66px;border-radius:999px;border:1px solid var(--stroke);
      display:flex;align-items:center;justify-content:center;font-size:24px;color:var(--textDim)">关闭</div>
    {"".join(rows)}
  </div>
</div>
"""


def screen_adb(theme):
    """ADB 调试页（第五十五轮）。

    版式照 App 的 `SettingsOverlay.pageAdb()`：
    面板 1440×920 @ (240,80)，左栏 380 + 1px 竖分割线，
    右栏标题 40px bold @ (48,34) + 右上「关闭」胶囊，
    标题下 1px 分割线 @ (48,190)，
    正文区从 y=120 起（相对面板），可视高 780。

    第一屏只有「当前状态」小节：
    小节标题 22px textFaint @ (48,128)（正文 top=120 + 8 上边距），
    10 行 KV：标签 22px textDim 左 66 / 取值 22px 右 994，行高 72 + 1px 分割线。
    """
    t = TOKENS[theme]
    rows = [
        ("身份", "uid 1000 · 平台签名", True),
        ("开发者选项", "开启", True),
        ("USB 调试", "开启", True),
        ("开发者页面", "启用", True),
        ("ADB 服务", "running", True),
        ("无线端口", "5555", True),
        ("本机 5555", "接受连接", True),
        ("本机 IPv4", "192.168.1.112", True),
        ("恢复记录", "调试 有 / USB 有 / 无线 有", True),
        ("前置检查", "固件与权限符合", True),
    ]
    body = []
    body.append(
        '<div class="abs" style="left:48px;top:128px;font-size:22px;color:var(--textFaint)">当前状态</div>'
    )
    for i, (label, value, ok) in enumerate(rows):
        top = 148 + i * 73
        color = "var(--ok)" if ok else "var(--textDim)"
        body.append(
            f'<div class="abs" style="left:66px;top:{top}px;width:928px;height:72px;'
            f'display:flex;align-items:center;justify-content:space-between">'
            f'<div style="font-size:22px;color:var(--textDim)">{label}</div>'
            f'<div style="font-size:22px;color:{color}">{value}</div></div>'
            f'<div class="abs" style="left:48px;top:{top + 72}px;width:964px"><div class="hairline"></div></div>'
        )
    return f"""
<div class="bgwash"></div>
<div class="abs" style="left:0;right:0;top:0;bottom:0;background:var(--scrim)"></div>
<div class="abs card" style="left:240px;top:80px;width:1440px;height:920px;display:flex">
  {settings_nav("ADB 调试")}
  <div style="flex:1;height:100%;position:relative;padding-left:0">
    <div class="abs" style="left:48px;top:34px;font-size:40px;font-weight:600">ADB 调试</div>
    <div class="abs" style="right:36px;top:36px;width:150px;height:66px;border-radius:999px;border:1px solid var(--stroke);
      display:flex;align-items:center;justify-content:center;font-size:24px;color:var(--textDim)">关闭</div>
    <div class="abs" style="left:48px;top:190px;width:964px"><div class="hairline"></div></div>
    {"".join(body)}
  </div>
</div>
"""


def screen_tasklist(theme):
    t = TOKENS[theme]
    rows = []
    for i, (name, cond, on) in enumerate([("开机后打开音乐", "开机时", True), ("夜间降低亮度", "每天 20:00", True),
                                          ("行车时隐藏浮窗", "车速 > 5", False), ("每周重启一次", "每周一 08:00", True)]):
        rows.append(
            f'<div class="abs" style="left:40px;top:{40 + i*112}px;width:880px;height:96px;border-radius:20px;'
            f'background:var(--surfaceSoft);border:1px solid var(--stroke);display:flex;align-items:center;padding:0 24px">'
            f'<div style="font-size:26px;font-weight:600">{name}</div>'
            f'<div style="font-size:19px;color:var(--textFaint);margin-left:20px">{cond}</div>'
            f'<div class="switch {"on" if on else ""}" style="margin-left:auto"><i></i></div>'
            f'<div style="margin-left:24px;color:var(--textFaint)">{icon("chevron",26)}</div></div>'
        )
    return f"""
<div class="bgwash"></div>
<div class="abs" style="left:0;right:0;top:0;bottom:0;background:var(--scrim)"></div>
<div class="abs card" style="left:440px;top:150px;width:1040px;height:700px">
  <div class="abs" style="left:40px;top:34px;font-size:38px;font-weight:600">计划任务</div>
  <div class="abs" style="right:36px;top:36px;font-size:22px;color:var(--accent)">新建</div>
  <div class="abs" style="left:40px;top:104px;width:960px"><div class="hairline"></div></div>
  {"".join(rows)}
</div>
"""


def screen_taskedit(theme):
    t = TOKENS[theme]
    return f"""
<div class="bgwash"></div>
<div class="abs" style="left:0;right:0;top:0;bottom:0;background:var(--scrim)"></div>
<div class="abs card" style="left:440px;top:150px;width:1040px;height:700px">
  <div class="abs" style="left:40px;top:34px;font-size:38px;font-weight:600">新建任务</div>
  <div class="abs" style="left:40px;top:110px;font-size:20px;color:var(--textFaint)">任务名称</div>
  <div class="abs" style="left:40px;top:142px;width:960px;height:76px;border-radius:18px;background:var(--surfaceSoft);
    border:1px solid var(--stroke);display:flex;align-items:center;padding:0 22px;font-size:26px">夜间降低亮度</div>
  <div class="abs" style="left:40px;top:248px;font-size:20px;color:var(--textFaint)">触发条件</div>
  <div class="abs" style="left:40px;top:280px"><div class="seg"><span class="on">开机时</span><span>每天</span>
    <span>每周</span><span>自定义</span></div></div>
  <div class="abs" style="left:40px;top:386px;font-size:20px;color:var(--textFaint)">执行动作</div>
  <div class="abs" style="left:40px;top:418px;width:960px;height:76px;border-radius:18px;background:var(--surfaceSoft);
    border:1px solid var(--stroke);display:flex;align-items:center;padding:0 22px;font-size:26px;color:var(--textDim)">
    选择应用或动作 {icon("chevron",26,1.8,"","var(--textFaint)")}</div>
  <div class="abs" style="right:36px;bottom:32px;display:flex;gap:16px">
    <div class="chip" style="font-size:24px;padding:14px 36px">取消</div>
    <div class="chip on" style="font-size:24px;padding:14px 36px;background:var(--accent);color:var(--accentInk)">保存</div>
  </div>
</div>
"""


def screen_picker(theme):
    t = TOKENS[theme]
    cells = []
    icons = ["note", "calendar", "phone", "clock", "camera", "share", "download", "search"]
    for i in range(12):
        r, c = divmod(i, 4)
        cells.append(
            f'<div class="abs" style="left:{40 + c*220}px;top:{190 + r*190}px;text-align:center">'
            f'<div class="appicon" style="margin:0 auto;width:96px;height:96px;border-radius:26px;background:var(--surfaceSoft);'
            f'color:var(--textDim)">{icon(icons[i%len(icons)],40)}</div>'
            f'<div style="font-size:18px;color:var(--textDim);margin-top:14px">应用 {i+1}</div></div>'
        )
    return f"""
<div class="bgwash"></div>
<div class="abs" style="left:0;right:0;top:0;bottom:0;background:var(--scrim)"></div>
<div class="abs card" style="left:420px;top:130px;width:1080px;height:740px">
  <div class="abs" style="left:40px;top:34px;font-size:38px;font-weight:600">选择应用</div>
  <div class="abs" style="left:40px;top:92px;width:1000px;height:68px;border-radius:18px;background:var(--surfaceSoft);
    border:1px solid var(--stroke);display:flex;align-items:center;padding:0 20px;gap:14px;color:var(--textFaint)">
    {icon("search",24)}<span style="font-size:22px">搜索应用</span></div>
  {"".join(cells)}
</div>
"""


def screen_quickapps(theme):
    """Dock 快捷应用配置面板（第五十四轮）。

    版式照 App 的 `DockQuickAppsDialog`：面板 1080×600 @ (420,240)，
    标题 38px bold @ (40,34) + 右上「关闭」胶囊，
    说明 20px @ (40,96)，
    三行槽位 1000×88 @ (40, 160 / 264 / 368)，
    底部「清空全部」胶囊 @ (40, 496)。
    """
    t = TOKENS[theme]
    rows = []
    apps = [
        ("#33C6B4", "N", "导航", "com.autonavi.amapauto"),
        ("#E0A340", "T", "电话", "com.android.dialer"),
        ("#4CC38A", "M", "音乐", "com.tencent.qqmusic"),
    ]
    for i, (color, letter, name, pkg) in enumerate(apps):
        top = 160 + i * 104
        rows.append(
            f'<div class="abs" style="left:40px;top:{top}px;width:1000px;height:88px;border-radius:18px;'
            f'background:var(--surfaceSoft);border:1px solid var(--stroke)">'
            f'<div class="abs" style="left:20px;top:16px;width:56px;height:56px;border-radius:18px;'
            f'background:var(--surfaceStrong);color:var(--textDim);display:flex;align-items:center;'
            f'justify-content:center;font-size:26px;font-weight:600">{name[0]}</div>'
            f'<div class="abs" style="left:92px;top:18px;font-size:24px;font-weight:600">{name}</div>'
            f'<div class="abs" style="left:92px;top:52px;font-size:17px;color:var(--textFaint)">{pkg}</div>'
            f'<div class="abs" style="right:118px;top:16px;height:56px;padding:0 22px;border-radius:999px;'
            f'background:var(--surfaceSoft);border:1px solid var(--stroke);display:flex;align-items:center;'
            f'font-size:20px;color:var(--textDim)">更换</div>'
            f'<div class="abs" style="right:20px;top:16px;height:56px;padding:0 22px;border-radius:999px;'
            f'background:var(--surfaceSoft);border:1px solid var(--stroke);display:flex;align-items:center;'
            f'font-size:20px;color:var(--textDim)">清除</div>'
            f"</div>"
        )
    return f"""
<div class="bgwash"></div>
<div class="abs" style="left:0;right:0;top:0;bottom:0;background:var(--scrim)"></div>
<div class="abs card" style="left:420px;top:240px;width:1080px;height:600px">
  <div class="abs" style="left:40px;top:34px;font-size:38px;font-weight:600">Dock 快捷应用</div>
  <div class="abs" style="right:36px;top:36px;width:150px;height:66px;border-radius:999px;background:var(--surfaceSoft);
    border:1px solid var(--stroke);display:flex;align-items:center;justify-content:center;font-size:24px;
    color:var(--textDim)">关闭</div>
  <div class="abs" style="left:40px;top:96px;font-size:20px;color:var(--textFaint)">选中的应用会固定在 Dock 最右侧，最多 3 个。点「更换」挑选应用，点「清除」清空该槽位。</div>
  {"".join(rows)}
  <div class="abs" style="left:40px;top:496px;width:200px;height:56px;border-radius:999px;background:var(--surfaceSoft);
    border:1px solid var(--stroke);display:flex;align-items:center;justify-content:center;font-size:20px;
    color:var(--textDim)">清空全部</div>
</div>
"""


def screen_color(theme):
    t = TOKENS[theme]
    sw = []
    colors = ["#2FBFAF", "#4E7FB8", "#C9913F", "#C9585F", "#8075B8", "#5A9968", "#9A6FA8", "#6E7885"]
    for i, c in enumerate(colors):
        r, cc = divmod(i, 4)
        sw.append(
            f'<div class="abs" style="left:{60 + cc*190}px;top:{150 + r*190}px;width:150px;height:150px;'
            f'border-radius:28px;background:{c};border:4px solid {"var(--accent)" if i == 0 else "transparent"}"></div>'
        )
    return f"""
<div class="bgwash"></div>
<div class="abs" style="left:0;right:0;top:0;bottom:0;background:var(--scrim)"></div>
<div class="abs card" style="left:440px;top:170px;width:1040px;height:660px">
  <div class="abs" style="left:40px;top:34px;font-size:38px;font-weight:600">强调色</div>
  {"".join(sw)}
  <div class="abs" style="left:60px;top:540px;width:920px"><div class="hairline"></div></div>
  <div class="abs" style="left:60px;top:568px;font-size:20px;color:var(--textFaint)">自定义</div>
  <div class="abs" style="left:60px;top:596px;width:920px;height:70px;border-radius:18px;background:var(--surfaceSoft);
    border:1px solid var(--stroke);display:flex;align-items:center;padding:0 20px;font-size:24px;color:var(--textDim)">#2FBFAF</div>
</div>
"""


def screen_keys(theme):
    t = TOKENS[theme]
    rows = []
    for i, (k, d, on) in enumerate([("方向盘上一曲", "长按切换上一首", True), ("方向盘下一曲", "长按切换下一首", True),
                                    ("音量滚轮", "调节媒体音量", True), ("长按音量键", "静音 / 取消静音", False)]):
        rows.append(
            f'<div class="abs" style="left:40px;top:{40 + i*104}px;width:900px;height:88px;border-radius:20px;'
            f'background:var(--surfaceSoft);border:1px solid var(--stroke);display:flex;align-items:center;padding:0 24px">'
            f'<div style="font-size:26px;font-weight:600">{k}</div>'
            f'<div style="font-size:19px;color:var(--textFaint);margin-left:20px">{d}</div>'
            f'<div class="switch {"on" if on else ""}" style="margin-left:auto"><i></i></div></div>'
        )
    return f"""
<div class="bgwash"></div>
<div class="abs" style="left:0;right:0;top:0;bottom:0;background:var(--scrim)"></div>
<div class="abs card" style="left:460px;top:180px;width:1000px;height:640px">
  <div class="abs" style="left:40px;top:34px;font-size:38px;font-weight:600">按键增强</div>
  {"".join(rows)}
</div>
"""


def screen_slots(theme):
    t = TOKENS[theme]
    rows = []
    for i, (n, ic) in enumerate([("桌面", "home"), ("应用", "grid"), ("分屏", "panel"), ("音乐", "music"), ("空调", "snow"), ("歌词", "note")]):
        # 行宽 740（40..780）：给右上角右对齐的「添加」标签（800..844）留出 20px 空档，
        # 否则行块背景（后绘制）会盖住标签左半边。
        rows.append(
            f'<div class="abs" style="left:40px;top:{30 + i*88}px;width:740px;height:72px;border-radius:18px;'
            f'background:var(--surfaceSoft);border:1px solid var(--stroke);display:flex;align-items:center;padding:0 20px;gap:16px">'
            f'<div class="tile" style="width:52px;height:52px;border-radius:16px">{icon(ic,24)}</div>'
            f'<div style="font-size:24px">{n}</div>'
            f'<div style="margin-left:auto;color:var(--textFaint)">{icon("trash",22)}</div></div>'
        )
    return f"""
<div class="bgwash"></div>
<div class="abs" style="left:0;right:0;top:0;bottom:0;background:var(--scrim)"></div>
<div class="abs card" style="left:520px;top:150px;width:880px;height:760px">
  <div class="abs" style="left:40px;top:32px;font-size:36px;font-weight:600">导航栏槽位</div>
  <div class="abs" style="right:36px;top:36px;font-size:22px;color:var(--accent)">添加</div>
  <div class="abs" style="left:40px;top:96px;width:800px"><div class="hairline"></div></div>
  {"".join(rows)}
</div>
"""


def screen_float(theme):
    t = TOKENS[theme]
    return f"""
<div class="bgwash"></div>
{status_bar(t)}
{nav_guide_card(44, 73, 520, theme)}
{lyric_block(theme, "我看见 夜空中 最亮的星", "风雨里追赶 雾里分不清影踪", "请指引我靠近你", 412)}
<div class="abs" style="left:1416px;top:75px;width:460px;text-align:right">
  <div style="font-size:96px;font-weight:600;line-height:1;letter-spacing:-0.02em">26<span style="font-size:38px">°</span></div>
  <div style="font-size:20px;color:var(--textDim);margin-top:12px">{icon("sun",20)} 晴 · 空气优</div>
</div>
<div class="abs card" style="left:44px;top:764px;width:340px;height:150px">
  <div class="abs" style="left:18px;top:10px;font-size:21px;color:var(--textFaint)">快捷应用</div>
  <div class="abs" style="left:26px;top:46px;text-align:center">
    <div class="appicon" style="width:68px;height:68px;border-radius:18px;background:var(--surfaceSoft);color:var(--textDim)">{icon("note",30)}</div>
    <div style="font-size:17px;color:var(--textDim);margin-top:8px">CarDesk</div></div>
  <div class="abs" style="left:126px;top:46px;text-align:center">
    <div class="appicon" style="width:68px;height:68px;border-radius:18px;background:var(--surfaceSoft);color:var(--textDim)">{icon("calendar",30)}</div>
    <div style="font-size:17px;color:var(--textDim);margin-top:8px">日历</div></div>
  <div class="abs" style="left:226px;top:46px;text-align:center">
    <div class="appicon" style="width:68px;height:68px;border-radius:18px;background:var(--surfaceSoft);color:var(--textDim)">{icon("phone",30)}</div>
    <div style="font-size:17px;color:var(--textDim);margin-top:8px">电话</div></div>
</div>
<div class="abs" style="left:44px;top:440px;display:flex;flex-direction:column;gap:22px">
  <div class="tile" style="width:76px;height:76px;border-radius:26px">{icon("music",30)}</div>
  <div class="tile" style="width:76px;height:76px;border-radius:26px">{icon("snow",30)}</div>
</div>
<div class="abs card" style="left:1300px;top:320px;width:460px;height:250px">
  <div class="abs" style="left:26px;top:20px;font-size:20px;color:var(--textFaint)">CarDesk 浮窗</div>
  <div class="abs" style="left:26px;top:62px;font-size:32px;font-weight:600">小镇姑娘</div>
  <div class="abs" style="left:26px;top:108px;font-size:22px;color:var(--textDim)">陶喆</div>
  <div class="abs" style="left:26px;top:166px;width:408px"><div class="bar" style="height:8px"><i style="width:34%"></i></div></div>
  <div class="abs" style="left:26px;top:192px;font-size:18px;color:var(--textFaint)">1:24 / 4:05</div>
  <div class="abs" style="right:22px;top:16px;width:44px;height:44px;border-radius:50%;background:var(--surfaceSoft);
    display:flex;align-items:center;justify-content:center;color:var(--textFaint)">{icon("x",24)}</div>
  <div class="abs" style="left:26px;top:212px;width:408px;height:4px;border-radius:999px;background:var(--stroke)"></div>
</div>
{dock(t)}
"""


# ---------------------------------------------------------------- 组装

SCREENS = [
    ("s01-home", "桌面（场景页）",
     "第一页 · 左上导航引导卡（8 条车道线）/ 居中三行歌词 / 右列行程·车况·续航三卡齐宽 388 / 底部 5 卡（含胎压卡）/ Dock 四段式",
     ["顶部状态栏（改为扁平分段，去掉透明浮层与渐变遮罩）",
      "左上导航引导卡（转向 + 距离 + **8 条车道线** + ETA）",
      "居中三行歌词（当前句带擦除高亮）", "右上天气大数字",
      "行程数据 2×2 KV 卡（388 宽，与续航卡齐宽）",
      "车况卡（俯视车模 + 门张开，388 宽，与续航卡齐宽）",
      "底部 5 张功能卡（快捷应用 / 通用音乐 / 快捷车控 / **胎压卡 350** / 续航 388）",
      "Dock 四段式：左 4 导航键 · **音乐卡** · **空调簇**（电源 / 双温区 / 风量 7 段 / A·C / AUTO / 循环 / 双除霜）· **最右 3 个快捷应用槽位**",
      "左侧圆形快捷按钮"],
     screen_home),
    ("s02-nav", "导航页", "第二页 · 左槽地图画中画 + 右槽组合卡（来源 / 封面 / 歌名 / 歌词 / 进度 / 播放）",
     ["左槽地图画中画（大圆角）", "右槽组合卡", "歌词垂直居中", "进度条与播放控制", "Dock"],
     screen_nav),
    ("s03-apps", "全部应用（抽屉）", "标题行 + 全屏 / 小窗分段 + 6 列网格",
     ["应用网格 6 列", "全屏 / 小窗分段", "拖拽位（图标可拖）"], screen_apps),
    ("s04-hvac", "空调浮层", "双温区 + 风量 + 吹风模式 + 功能行 + 除霜",
     ["主驾 / 副驾温度轮盘", "风量档位", "吹风模式 7 键", "A/C · AUTO · 外循环", "前后除霜"], screen_hvac),
    ("s05-settings", "设置总览", "左侧分组导航 + 右侧内容（开关 / 分段 / 说明）",
     ["左侧 20 个设置项分 5 组", "分组标题 + 当前项高亮", "开关行 + 描述", "驾驶模式分段", "关闭按钮"], screen_settings),
    ("s06-tasks", "计划任务", "任务列表 + 新建",
     ["任务行（名称 / 条件 / 开关）", "新建入口"], screen_tasklist),
    ("s07-taskedit", "任务编辑", "名称 / 触发条件 / 执行动作",
     ["输入框", "条件分段", "动作选择", "取消 / 保存"], screen_taskedit),
    ("s08-picker", "应用选择器", "搜索 + 网格",
     ["搜索框", "应用网格"], screen_picker),
    ("s09-color", "强调色", "预设色板 + 自定义",
     ["8 色预设", "自定义色值"], screen_color),
    ("s10-keys", "按键增强", "开关列表",
     ["按键项 + 开关"], screen_keys),
    ("s11-slots", "导航栏槽位", "槽位排序",
     ["槽位列表 + 拖拽", "添加入口"], screen_slots),
    ("s12-float", "浮窗", "桌面上的小窗（音乐等）",
     ["浮窗卡片", "关闭按钮"], screen_float),
    ("s13-quickapps", "Dock 快捷应用配置", "Dock 最右 3 个槽位 · 挑应用 / 清除 / 清空全部",
     ["三个槽位行（图标 / 应用名 / 包名）", "更换 / 清除", "清空全部"], screen_quickapps),
    ("s14-adb", "ADB 调试", "设置页「车控 → ADB 调试」· 当前状态 + 自动开关 + 操作 + 诊断",
     ["当前状态 10 行 KV（身份 / 开关 / 端口 / 5555 / IP / 恢复记录 / 前置检查）",
      "自动开关：自动 ADB / 无线 ADB 自启",
      "操作：开启 USB 调试 / 开启无线 ADB(5555) / 关闭无线并恢复端口 / 恢复 USB 接口 / 恢复调试开关 / **恢复全部设置**",
      "诊断：属性清单 + 崩溃日志（默认收起）"], screen_adb),
]


def build_board():
    parts = []
    parts.append("""<!doctype html>
<html lang="zh-CN"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>CarDesk UI 重设计 · 浮屿 Flat Isle</title>
<style>""")
    parts.append(build_css())
    parts.append(font_face_css())
    parts.append("</style></head><body>")
    parts.append('<div class="board" id="board">')

    # 封面
    parts.append("""
<section class="hero">
  <h1>CarDesk UI 重设计 · 浮屿 Flat Isle</h1>
  <p class="lede">在现有功能与信息架构完全保留的前提下，重做全部页面的视觉语言：
  去掉拟物壁纸与玻璃拟态，改成<b>纯色底 + 大圆角扁平卡片</b>，并给出一套可日夜切换的 token。</p>
  <p class="lede">所有画面按 1920×1080 设计坐标绘制（下列预览为 0.5 缩放），
  功能项与当前 App 一一对应 —— <b>没有增删任何功能</b>。</p>
  <div class="rulebar">
    <span class="rule">简约 · 一次只讲一件事</span>
    <span class="rule">扁平 · 无投影无玻璃拟态</span>
    <span class="rule">大圆角 · 卡片 28px</span>
    <span class="rule">日夜 · 同一套 token 两套值</span>
    <span class="rule">低饱和 · 单一强调色</span>
  </div>
</section>""")

    # token 段
    t_night = TOKENS["night"]
    t_day = TOKENS["day"]
    token_rows = []
    labels = [
        ("bg", "页面底色"), ("bgGlow", "径向渐变中心"), ("surface", "卡片面"),
        ("surfaceSoft", "卡内嵌块"), ("surfaceStrong", "选中 / 进度槽"),
        ("stroke", "1px 描边"), ("text", "主文字"), ("textDim", "次文字"),
        ("textFaint", "弱文字"), ("accent", "强调色"), ("ok", "正常"), ("warn", "警告"), ("danger", "危险"),
    ]
    for k, lab in labels:
        n, d = t_night[k], t_day[k]
        token_rows.append(f"""
<div style="display:grid;grid-template-columns:120px 150px 150px;gap:14px;align-items:center;
  padding:9px 0;border-bottom:1px solid var(--board-line)">
  <div style="font-size:13px;color:var(--board-muted)">{lab}<br><span style="font-size:11px;opacity:.7">{k}</span></div>
  <div style="display:flex;align-items:center;gap:9px"><span style="width:26px;height:26px;border-radius:8px;
    border:1px solid var(--board-line);background:{n}"></span><span style="font-size:12px;font-family:ui-monospace,monospace">{n}</span></div>
  <div style="display:flex;align-items:center;gap:9px"><span style="width:26px;height:26px;border-radius:8px;
    border:1px solid var(--board-line);background:{d}"></span><span style="font-size:12px;font-family:ui-monospace,monospace">{d}</span></div>
</div>""")
    parts.append(f"""
<h2 class="sec">设计 token</h2>
<p class="secsub">强调色由<b>青绿</b>承担（与参考 App 的蓝刻意区分）；日间的强调色略深以保证对比度。
层级不靠阴影，只靠 <b>留白 + 明度差 + 1px 描边</b>。</p>
<div style="display:grid;grid-template-columns:120px 150px 150px;gap:14px;margin-bottom:6px">
  <div style="font-size:13px;color:var(--board-muted)">用途</div>
  <div style="font-size:13px;color:var(--board-muted)">夜间</div>
  <div style="font-size:13px;color:var(--board-muted)">日间</div>
</div>
<div style="max-width:560px">{''.join(token_rows)}</div>
<div style="display:flex;gap:56px;margin-top:28px;flex-wrap:wrap">
  <div><div style="font-size:13px;color:var(--board-muted);margin-bottom:8px">圆角</div>
    <div style="display:flex;gap:10px;align-items:flex-end">
      <div style="width:74px;height:54px;border-radius:28px;background:var(--board-line)"></div>
      <div style="width:74px;height:54px;border-radius:18px;background:var(--board-line)"></div>
      <div style="width:74px;height:54px;border-radius:16px;background:var(--board-line)"></div>
      <div style="width:74px;height:54px;border-radius:999px;background:var(--board-line)"></div>
    </div>
    <div style="font-size:12px;color:var(--board-muted);margin-top:8px">卡片 28 · 内嵌 18 · 图标底板 16 · 胶囊 999</div></div>
  <div><div style="font-size:13px;color:var(--board-muted);margin-bottom:8px">字号阶梯</div>
    <div style="line-height:1.25"><span style="font-size:44px;font-weight:600">A</span>
      <span style="font-size:30px;font-weight:600;margin-left:14px">A</span>
      <span style="font-size:22px;margin-left:14px">A</span>
      <span style="font-size:18px;margin-left:14px;color:var(--board-muted)">A</span>
      <span style="font-size:15px;margin-left:14px;color:var(--board-muted)">A</span></div>
    <div style="font-size:12px;color:var(--board-muted);margin-top:8px">104 数值 · 30 标题 · 22 正文 · 18 标签 · 15 弱文本</div></div>
</div>""")

    # 对照说明
    rules = [
        ("去掉", "拟物山峦壁纸 → 纯色底 + 极淡径向渐变", "不再有任何拟物纹理；壁纸只承担氛围，不承担信息"),
        ("去掉", "玻璃拟态 / 半透明 / 背景模糊 → 纯不透明面", "车机强光下半透明文字对比度不可控，改成实底 + 1px 描边"),
        ("去掉", "投影 / 发光 / 描边光晕 → 零投影", "层次完全靠留白 + 明度差表达，夜间不糊"),
        ("去掉", "透明浮层状态栏 + 96px 渐变遮罩 → 扁平分段胶囊行", "状态栏与内容不再互相压暗，两套主题都能一眼看清"),
        ("改", "小圆角（12~16px）→ 大圆角（卡片 28 / 内嵌 18 / 胶囊 999）", "大圆角是这台车机的识别度来源，触摸目标也更友好"),
        ("改", "多色强调 → 单一低饱和强调色（青绿）", "和参考 App 的蓝刻意区分；状态色（正常/警告/危险）只在必要时出现"),
        ("改", "Dock 只画了几个键 → 三段式（左导航键 / 中空调簇 / 右媒体卡）",
         "空调相关设置整簇移到 Dock 中间，补齐电源 / 双温区 / 风量 7 段 / A·C / AUTO / 内外循环 / 前后除霜"),
        ("改", "右列卡片宽窄不一 → 行程 / 车况 / 续航统一 388 宽、右对齐 1876",
         "胎压卡宽 350 照 App，中间用竖置俯视车模显示四个轮压"),
        ("改", "导航引导卡车道线 4 条 → 满 8 条（照 MAX_GUIDE_LANES）",
         "装在 468×70 圆角 chip 里，转向道用强调色"),
        ("改", "状态栏占高 110px → 73px（元素整体缩到 2/3）",
         "车机屏幕小，状态栏原来吃掉太多竖向空间；缩小后页面内容整体上移，不留空白带"),
        ("保留", "全部 12 个页面 / 全部功能项 / 全部文案数值", "只换视觉语言，不增删任何功能，信息架构一字未改"),
    ]
    rule_rows = "".join(
        f'<div style="display:grid;grid-template-columns:56px 1fr 1fr;gap:18px;align-items:start;'
        f'padding:13px 0;border-bottom:1px solid var(--board-line)">'
        f'<div style="font-size:12px;font-weight:600;color:{"var(--board-fg)" if k != "去掉" else "var(--board-muted)"};'
        f'padding-top:2px">{k}</div>'
        f'<div style="font-size:14px;line-height:1.55">{a}</div>'
        f'<div style="font-size:13px;color:var(--board-muted);line-height:1.55">{b}</div></div>'
        for k, a, b in rules
    )
    parts.append(f"""
<h2 class="sec">与当前版本的对照</h2>
<p class="secsub">下面每一条都可在逐屏图里对上号：<b>功能项、文案、数值、层级关系全部不变</b>，
只换视觉语言（颜色 / 形状 / 层次 / 密度）。</p>
<div style="display:grid;grid-template-columns:56px 1fr 1fr;gap:18px;margin-bottom:4px">
  <div style="font-size:13px;color:var(--board-muted)">动作</div>
  <div style="font-size:13px;color:var(--board-muted)">改动</div>
  <div style="font-size:13px;color:var(--board-muted)">原因</div>
</div>
<div style="max-width:1180px">{rule_rows}</div>""")

    # 页面清单
    idx_rows = "".join(
        f'<tr><td style="padding:9px 12px;border-bottom:1px solid var(--board-line);font-family:ui-monospace,monospace;'
        f'font-size:12px;color:var(--board-muted)">{sid}</td>'
        f'<td style="padding:9px 12px;border-bottom:1px solid var(--board-line);font-size:14px">{name}</td>'
        f'<td style="padding:9px 12px;border-bottom:1px solid var(--board-line);font-size:13px;color:var(--board-muted)">'
        f'{desc}</td></tr>'
        for sid, name, desc, _f, _fn in SCREENS
    )
    parts.append(f"""
<h2 class="sec">页面清单</h2>
<p class="secsub">14 个页面对应现有 App 的全部界面；每个页面都给出<b>日间 + 夜间</b>两套。</p>
<table style="border-collapse:collapse;max-width:1180px;width:100%">
<thead><tr style="text-align:left">
  <th style="padding:9px 12px;font-size:13px;color:var(--board-muted);font-weight:500">ID</th>
  <th style="padding:9px 12px;font-size:13px;color:var(--board-muted);font-weight:500">页面</th>
  <th style="padding:9px 12px;font-size:13px;color:var(--board-muted);font-weight:500">包含内容</th>
</tr></thead>
<tbody>{idx_rows}</tbody></table>""")

    # 屏幕
    for sid, name, desc, feats, fn in SCREENS:
        panes = []
        for theme, label in (("day", "日间"), ("night", "夜间")):
            panes.append(
                f'<figure class="pane" data-sid="{sid}" data-theme="{theme}">'
                f'<div class="mockup" style="--bg:{t_day["bg"] if theme=="day" else t_night["bg"]};'
                f'--bgGlow:{t_day["bgGlow"] if theme=="day" else t_night["bgGlow"]};'
                f'--surface:{t_day["surface"] if theme=="day" else t_night["surface"]};'
                f'--surfaceSoft:{t_day["surfaceSoft"] if theme=="day" else t_night["surfaceSoft"]};'
                f'--surfaceStrong:{t_day["surfaceStrong"] if theme=="day" else t_night["surfaceStrong"]};'
                f'--stroke:{t_day["stroke"] if theme=="day" else t_night["stroke"]};'
                f'--text:{t_day["text"] if theme=="day" else t_night["text"]};'
                f'--textDim:{t_day["textDim"] if theme=="day" else t_night["textDim"]};'
                f'--textFaint:{t_day["textFaint"] if theme=="day" else t_night["textFaint"]};'
                f'--accent:{t_day["accent"] if theme=="day" else t_night["accent"]};'
                f'--accentSoft:{t_day["accentSoft"] if theme=="day" else t_night["accentSoft"]};'
                f'--accentInk:{t_day["accentInk"] if theme=="day" else t_night["accentInk"]};'
                f'--ok:{t_day["ok"] if theme=="day" else t_night["ok"]};'
                f'--warn:{t_day["warn"] if theme=="day" else t_night["warn"]};'
                f'--danger:{t_day["danger"] if theme=="day" else t_night["danger"]};'
                f'--dock:{t_day["dock"] if theme=="day" else t_night["dock"]};'
                f'--scrim:{t_day["scrim"] if theme=="day" else t_night["scrim"]};'
                f'--road:{t_day["road"] if theme=="day" else t_night["road"]};'
                f'"><div class="screen">{fn(theme)}</div></div>'
                f"<figcaption>{label}</figcaption></figure>"
            )
        chips = "".join(f'<span class="rule">{f}</span>' for f in feats)
        parts.append(f"""
<section class="screenwrap" id="{sid}-wrap">
  <div class="screenbar"><span class="idx">{sid.split('-')[0].upper()}</span>
    <span class="nm">{name}</span><span class="tag">{desc}</span></div>
  <div class="sheet">{''.join(panes)}</div>
  <div style="padding:0 20px 18px"><div class="rulebar">{chips}</div></div>
</section>""")

    parts.append("</div>")
    parts.append("""
<script>
(function(){
  const q = new URLSearchParams(location.search);
  const only = q.get('only'), theme = q.get('theme');
  const board = document.getElementById('board');
  if (only) {
    // 1) 只保留目标屏所在的 section，其余顶层节点（封面 / token 段 / 其它屏）全部移除
    Array.from(board.children).forEach(el => {
      if (!(el.classList.contains('screenwrap') && el.id === only + '-wrap')) el.remove();
    });
    // 2) 该 section 内只保留目标主题的那一屏
    board.querySelectorAll('figure.pane').forEach(f => {
      if (f.dataset.sid !== only || (theme && f.dataset.theme !== theme)) f.remove();
    });
    // 3) 去掉标题栏 / 图注 / 画板外框，让截图就是纯屏幕
    board.querySelectorAll('.screenbar, figcaption').forEach(e => e.remove());
    board.style.maxWidth = 'none';
    board.style.padding = '0';
    board.style.margin = '0';
    const sw = board.querySelector('.screenwrap');
    if (sw) { sw.style.border = 'none'; sw.style.borderRadius = '0'; sw.style.margin = '0'; sw.style.background = 'transparent'; }
    const sh = board.querySelector('.sheet');
    if (sh) { sh.style.padding = '0'; sh.style.gap = '0'; }
    const mk = board.querySelector('.mockup');
    if (mk) { mk.style.borderRadius = '0'; }
    const fig = board.querySelector('figure.pane');
    if (fig) { fig.style.flex = 'none'; }
    document.body.style.background = 'transparent';
  }
  const night = q.get('night');
  if (night === '1') board.classList.add('night');
})();
</script>
</body></html>""")
    return "".join(parts)


def export_png():
    """逐屏导出 1920×1080 PNG（.screen 已按 0.5 缩放，2x 设备像素比 = 1:1 设计坐标）。"""
    if not os.path.isdir(PNG_DIR):
        os.makedirs(PNG_DIR)
    jobs = []
    for sid, name, *_ in SCREENS:
        for theme in ("day", "night"):
            jobs.append((sid, theme))
    profile = tempfile.mkdtemp(prefix="cd-chrome-")
    for sid, theme in jobs:
        out = os.path.join(PNG_DIR, f"{sid}-{theme}.png")
        url = f"file://{HTML_PATH}?only={sid}&theme={theme}"
        cmd = [CHROME, "--headless", "--disable-gpu", "--hide-scrollbars",
               "--no-first-run", "--no-default-browser-check", "--disable-extensions",
               "--run-all-compositor-stages-before-draw",
               f"--user-data-dir={profile}",
               "--force-device-scale-factor=2", "--virtual-time-budget=4000",
               f"--window-size=960,540", f"--screenshot={out}", url]
        ok = False
        for _ in range(3):
            try:
                subprocess.run(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=45)
            except subprocess.TimeoutExpired:
                pass
            if os.path.exists(out) and os.path.getsize(out) > 2000:
                ok = True
                break
        size = os.path.getsize(out) if os.path.exists(out) else 0
        print(("ok  " if ok else "FAIL") + f" {os.path.basename(out)}  {size}B", flush=True)
    subprocess.run(["/bin/rm", "-rf", profile], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


if __name__ == "__main__":
    with open(HTML_PATH, "w", encoding="utf-8") as f:
        f.write(build_board())
    print("wrote", HTML_PATH, os.path.getsize(HTML_PATH), "bytes")
    if "--png" in sys.argv:
        export_png()
