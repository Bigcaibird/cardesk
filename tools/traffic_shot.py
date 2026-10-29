#!/usr/bin/env python3
"""红绿灯渲染取证：造高德广播 → 抓场景页 → 自动裁出红绿灯放大图。

## 为什么单独写一个脚本

第八十轮的红绿灯「读秒与『秒』字重叠」缺陷就是靠模拟器截图发现的
（设计板是 Python 重绘，**完全看不出**真实字体度量的偏差）。

`tools/emu_regress.py traffic` 只负责广播 + 全屏截图，
但全屏 1920×1080 里红绿灯 chip 只有 ~80×100 像素，肉眼很难判断有没有压字，
所以这里再叠一步：**自动定位灯色色块 → 裁出放大图**，把「有没有重叠」看成一目了然。

## 为什么按 PID 管广播脚本（而不是 pkill -f）

第八十轮踩过这个坑：抓绿灯时用的 `pkill -f cardesk-nav-broadcast.sh`
**没把上一次的脚本杀掉**，红、绿两个脚本同时在发广播，
最后一帧谁是绿谁是红纯看谁先到 —— 结果绿灯截出来还是红的。

改成：**启动时记下 PID，结束时按 PID kill**，并额外扫一遍 `ps` 清理同名残留。
脚本名每次也带状态后缀（`-r` / `-g`），避免同名互相盖。

## 用法

```bash
python3 tools/traffic_shot.py red 12                  # 红灯 + 12 秒
python3 tools/traffic_shot.py green 8                 # 绿灯 + 8 秒
python3 tools/traffic_shot.py red 12 r80-check-red    # 自定义输出名
```

## 输出

- `emu-verify/<tag>.png`       全屏截图
- `emu-verify/<tag>-zoom.png`  红绿灯 chip 放大图（3 倍）
- stdout 里报告灯色色块坐标、判定是否检出
"""
import os
import re
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
SDK = os.path.expanduser("~/Documents/desktopapp/toolchain/sdk")
ADB = os.path.join(SDK, "platform-tools", "adb")
OUT = os.path.join(REPO, "emu-verify")
DEVICE = "emulator-5554"

ACTION_SEND = "AUTONAVI_STANDARD_BROADCAST_SEND"
ACTION_CRUISE = "NAVITOOL_CRUISE_TRAFFIC_LIGHTS"

# 灯色判定阈值（不写死品牌色，见 Skin.DANGER / Skin.OK）——
# 「绿」用**色相**判：g 明显高于 r 和 b。
# Skin.OK = 0xFF4CC38A → (76,195,138)，g 比 b 高 57、比 r 高 119，
# 早先按 (g>140, r<120, b<130) 判会**漏掉这个偏青的绿**。
RED_MIN = 150
GREEN_MIN = 140


def adb(args, timeout=120):
    return subprocess.run(f'"{ADB}" -s {DEVICE} {args}', shell=True,
                          capture_output=True, text=True, timeout=timeout)


def sh_out(args, timeout=120):
    return (adb(args, timeout=timeout).stdout or "")


def kill_all_scripts():
    """把设备上所有广播脚本按 PID 杀掉，并回报杀了几个。

    ## 必须用 `ps -ef` / `ps -A -o PID,ARGS`，不能用 `ps -A`

    `ps -A` 的 NAME 列只显示**进程名**（脚本一律叫 `sh`），
    按脚本名 grep 根本匹配不到 —— 第八十轮就因此让第七十九轮留下的
    红灯脚本（pid 8237，从 23:48 活到 00:41）一直跟绿灯脚本打架，
    截出来的「绿灯」其实是红的。
    """
    out = sh_out("shell ps -ef")
    killed = []
    for line in out.splitlines():
        # 注意：脚本名带状态后缀（`cardesk-nav-broadcast-g.sh`），
        # **不能**按 "nav-broadcast.sh" 整串匹配 —— 那样连新脚本都匹配不到。
        if "broadcast" not in line or ".sh" not in line:
            continue
        m = re.match(r"\S+\s+(\d+)\s", line)
        if m:
            pid = m.group(1)
            adb(f"shell kill -9 {pid}")
            killed.append(pid)
    if killed:
        time.sleep(1)
    print(f"清理广播脚本：{killed or '无'}")
    return killed


def start_loop(status, countdown, suffix):
    """后台循环发广播，返回 (pid, 远端脚本路径)。

    三个广播缺一不可 —— 只有红绿灯协议时导航卡不会出现，
    而红绿灯 chip 是画在**引导卡**里的。
    """
    script = (
        "while true; do "
        f"am broadcast -a {ACTION_SEND} --ei KEY_TYPE 10019 --ei EXTRA_STATE 8 >/dev/null 2>&1; "
        f"am broadcast -a {ACTION_SEND} --ei KEY_TYPE 10001 --ei ICON 1 --ei SEG_REMAIN_DIS 320 "
        "--es CUR_ROAD_NAME 'CURRD' --es NEXT_ROAD_NAME 'NEXTRD' "
        "--ei ROUTE_REMAIN_TIME 600 --ei ROUTE_REMAIN_DIS 3000 >/dev/null 2>&1; "
        f"am broadcast -a {ACTION_CRUISE} --es data "
        f"'{{\"ts\":0,\"lights\":[{{\"direction\":2,\"status\":{status},\"countdown\":{countdown},"
        "\"waitNum\":1,\"showType\":0}]}}' >/dev/null 2>&1; "
        "sleep 2; done"
    )
    local = f"/tmp/cardesk-nav-broadcast-{suffix}.sh"
    remote = f"/data/local/tmp/cardesk-nav-broadcast-{suffix}.sh"
    with open(local, "w") as f:
        f.write("#!/system/bin/sh\n" + script + "\n")
    adb(f"push {local} {remote}")
    adb(f"shell chmod 755 {remote}")
    out = sh_out(f"shell 'nohup sh {remote} >/dev/null 2>&1 & echo $!'")
    pid = out.strip().splitlines()[-1].strip() if out.strip() else ""
    time.sleep(3)
    print(f"广播脚本已启动 pid={pid} ({remote})")
    return pid, remote


def screencap(path):
    with open(path, "wb") as f:
        subprocess.run(f'"{ADB}" -s {DEVICE} exec-out screencap -p', shell=True, stdout=f)


def count_colors(img):
    """统计红/绿像素数 —— 用来判断这一帧到底是哪个灯态。"""
    px = img.load()
    W, H = img.size
    red = green = 0
    rpts, gpts = [], []
    for y in range(H):
        for x in range(W):
            r, g, b = px[x, y]
            if r > RED_MIN and g < 100 and b < 100:
                red += 1
                rpts.append((x, y))
            elif g > GREEN_MIN and g > r + 35 and g > b + 15:
                green += 1
                gpts.append((x, y))
    return red, rpts, green, gpts


def biggest_blob(pts):
    """挑出**灯圆**那个连通块。

    顶栏头像、Dock 图标、续航进度条上也有绿色：
    - 进度条是 213×12 的扁条；
    - 头像/Dock 图标形状不规则。
    灯圆是 `TRAFFIC_LAMP_D = 46` 的**近似正方形实心块**，
    所以先按「宽高都在 30~60 且接近正方」筛，筛不到再退回最大块。
    """
    from collections import deque
    remaining = set(pts)
    blobs = []
    while remaining:
        seed = next(iter(remaining))
        q = deque([seed])
        remaining.discard(seed)
        comp = []
        while q:
            p = q.popleft()
            comp.append(p)
            x, y = p
            for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1),
                           (1, 1), (1, -1), (-1, 1), (-1, -1)):
                n = (x + dx, y + dy)
                if n in remaining:
                    remaining.discard(n)
                    q.append(n)
        xs = [p[0] for p in comp]
        ys = [p[1] for p in comp]
        w = max(xs) - min(xs) + 1
        h = max(ys) - min(ys) + 1
        blobs.append((len(comp), comp, min(xs), min(ys), max(xs), max(ys), w, h))

    squares = [b for b in blobs if 30 <= b[6] <= 60 and 30 <= b[7] <= 60
               and abs(b[6] - b[7]) <= 8]
    pool = squares or blobs
    pool.sort(key=lambda b: -b[0])
    return pool[0]


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return
    which = sys.argv[1]
    countdown = int(sys.argv[2]) if len(sys.argv) > 2 else 12
    tag = sys.argv[3] if len(sys.argv) > 3 else f"r80-traffic-{which}"
    status = 0 if which == "red" else 1

    os.makedirs(OUT, exist_ok=True)
    kill_all_scripts()
    start_loop(status, countdown, "r" if status == 0 else "g")

    full = os.path.join(OUT, f"{tag}.png")
    time.sleep(1)
    screencap(full)
    print("全屏 ->", full)

    try:
        from PIL import Image
    except ImportError:
        print("（无 PIL，跳过放大图）")
        kill_all_scripts()
        return

    img = Image.open(full).convert("RGB")
    red, rpts, green, gpts = count_colors(img)
    print(f"红色像素 {red} · 绿色像素 {green}")
    want, pts = ("red", rpts) if status == 0 else ("green", gpts)
    if not pts:
        print(f"❌ 没检出{want}灯 —— 这一帧不是这个灯态（可能两个脚本打架了）")
        kill_all_scripts()
        return
    # 灯圆在导航卡的右上角（1920×1080 设计坐标下大约 x 475~520 / y 117~163），
    # 但顶栏头像、Dock 图标上也有绿色 —— 取**最大连通块**才稳。
    comp = biggest_blob(pts)
    npx, _, x0, y0, x1, y1, bw, bh = comp
    print(f"{want}灯色块 x[{x0},{x1}] y[{y0},{y1}] 像素 {npx} 尺寸 {bw}×{bh}")

    # 往下扩到读秒行 —— 读秒和「秒」字都在圆下方
    pad = 26
    crop = img.crop((max(0, x0 - pad), max(0, y0 - pad),
                     min(img.size[0], x1 + pad + 12), min(img.size[1], y1 + pad + 26)))
    zoom = crop.resize((crop.size[0] * 3, crop.size[1] * 3), Image.LANCZOS)
    zp = os.path.join(OUT, f"{tag}-zoom.png")
    zoom.save(zp)
    print("放大图 ->", zp, zoom.size)
    kill_all_scripts()


if __name__ == "__main__":
    main()
