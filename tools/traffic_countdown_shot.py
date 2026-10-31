#!/usr/bin/env python3
"""红绿灯**读秒随时间变化**取证：定时抓帧 → 裁读秒区 → 拼成对比图。

## 为什么单独写一个脚本

`tools/traffic_shot.py` 只截**一帧**（判定灯色对不对、看数字和「秒」字有没有重叠），
但本轮（第八十六轮）要证的是**时间维度**上的东西：

> 真车反馈：「绿灯时倒计时一直在 3-2 秒之间，未能正确反应倒计时」。

锯齿是**时间序列**上的缺陷，截一帧看不出来 —— 必须按秒连着抓好几帧，
然后看「读秒」这一小块像素**是不是在两个值之间来回跳**。

## 判定方法（为什么能判出锯齿）

读秒那一小块区域在同一个数字下是**逐像素相同**的（没有动画），
所以对它做 md5：

- 旧包（每帧重锚）：序列会是 `A A B B A A B B …`（A/B 两个值来回切），
  **distinct = 2**，而且 B 之后又回到 A ⇒ 锯齿；
- 新包（只减不增）：序列会是 `A A A B B B C …`（每个值只出现一段），
  **distinct ≥ 3** 且**不回跳** ⇒ 单调递减。

同时脚本会把 logcat 里的 `红绿灯读秒显示`（raw / shown）也抓出来 ——
新包有这行日志，**数字是直接读出来的**，不用靠像素猜。

## 广播节奏为什么是 2 秒

真车上高德**不是每秒都刷**红绿灯字段（帧间隔约 2 秒），
旧代码「每收到一帧就按本机经过的秒数重算一次」的写法，
在 2 秒一帧 + 原始值恒定的前提下正好复现用户描述的 `3 → 2 → 3 → 2`。
所以这里的广播循环**固定 2 秒一帧**、原始值**恒定**（默认绿灯 3 秒）。

## 用法

```bash
python3 tools/traffic_countdown_shot.py r86-before           # 旧包（已装在模拟器上）
python3 tools/traffic_countdown_shot.py r86-after            # 新包
python3 tools/traffic_countdown_shot.py r86-red --lamp red --raw 12
```

输出：
- `emu-verify/<tag>-frames.png`  读秒区逐帧对比图（每帧一行，左边标时刻）
- `emu-verify/<tag>.png`          第一帧全屏
- stdout：读秒区哈希序列、distinct 数、logcat 里的 raw/shown 序列
"""
import hashlib
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
STANDARD_KEY_TYPE = 60073
STANDARD_DIR = 4
STATUS_RED = 1
STATUS_GREEN = 4

# 广播节奏：真车约 2 秒一帧（见模块 docstring）。
BROADCAST_INTERVAL = 2

# 灯色判定阈值（跟 traffic_shot.py 一致，见那边的注释）。
RED_MIN = 150
GREEN_MIN = 140


def adb(args, timeout=120):
    return subprocess.run(f'"{ADB}" -s {DEVICE} {args}', shell=True,
                          capture_output=True, text=True, timeout=timeout)


def sh_out(args, timeout=120):
    return adb(args, timeout=timeout).stdout or ""


def screencap(path):
    with open(path, "wb") as f:
        subprocess.run(f'"{ADB}" -s {DEVICE} exec-out screencap -p', shell=True, stdout=f)


def kill_all_scripts():
    """按 PID 杀掉设备上残留的广播脚本（脚本名都叫 `sh`，必须用 `ps -ef`）。

    ## 匹配条件为什么要放宽

    脚本名有两个来源：`cardesk-nav-broadcast-*.sh`（traffic_shot.py 起的）
    和 `cardesk-countdown-*.sh`（本脚本起的）。
    早先只按「含 broadcast」匹配，漏掉了本脚本自己的那批 ——
    残留脚本和新脚本一起发广播，帧率翻倍，读秒看起来反而不跳了（假象）。
    现在按「**含 .sh 且名字里有 broadcast 或 countdown**」来杀。
    """
    out = sh_out("shell ps -ef")
    killed = []
    for line in out.splitlines():
        if ".sh" not in line:
            continue
        if "broadcast" not in line and "countdown" not in line:
            continue
        m = re.match(r"\S+\s+(\d+)\s", line)
        if m:
            adb(f"shell kill -9 {m.group(1)}")
            killed.append(m.group(1))
    if killed:
        time.sleep(1)
    print(f"清理广播脚本：{killed or '无'}")
    return killed


def start_loop(lamp, raw, suffix, warmup=3.0):
    """后台循环发广播：导航卡两帧 + 红绿灯一帧，**原始读秒恒定**，每 2 秒一轮。

    导航卡那两帧缺一不可 —— 只有红绿灯协议时引导卡不会出现，
    而读秒是画在引导卡右上角的红绿灯 chip 里的。

    @param warmup 起播后等多久再开始抓（读秒是**倒着走的**，
      等太久就只剩「没有读秒」的状态了 —— 第八十六轮踩过：
      raw=3 时等 3 秒再去抓，整段递减已经走完了）。
    """
    status = STATUS_RED if lamp == "red" else STATUS_GREEN
    # 绿灯时故意也给一个正数的红灯读秒（真车就是这样的），验证灯态不被它带偏。
    red_seconds = raw if lamp == "red" else raw + 11
    green_seconds = 0 if lamp == "red" else raw
    traffic = (
        f"am broadcast -a {ACTION_SEND} --ei KEY_TYPE {STANDARD_KEY_TYPE} "
        f"--ei dir {STANDARD_DIR} --ei trafficLightStatus {status} "
        f"--ei redLightCountDownSeconds {red_seconds} "
        f"--ei greenLightLastSecond {green_seconds} >/dev/null 2>&1; "
    )
    script = (
        "while true; do "
        f"am broadcast -a {ACTION_SEND} --ei KEY_TYPE 10019 --ei EXTRA_STATE 8 >/dev/null 2>&1; "
        f"am broadcast -a {ACTION_SEND} --ei KEY_TYPE 10001 --ei ICON 1 --ei SEG_REMAIN_DIS 320 "
        "--es CUR_ROAD_NAME 'CURRD' --es NEXT_ROAD_NAME 'NEXTRD' "
        "--ei ROUTE_REMAIN_TIME 600 --ei ROUTE_REMAIN_DIS 3000 >/dev/null 2>&1; "
        f"{traffic}"
        f"sleep {BROADCAST_INTERVAL}; done"
    )
    local = f"/tmp/cardesk-countdown-{suffix}.sh"
    remote = f"/data/local/tmp/cardesk-countdown-{suffix}.sh"
    with open(local, "w") as f:
        f.write("#!/system/bin/sh\n" + script + "\n")
    adb(f"push {local} {remote}")
    adb(f"shell chmod 755 {remote}")
    out = sh_out(f"shell 'nohup sh {remote} >/dev/null 2>&1 & echo $!'")
    pid = out.strip().splitlines()[-1].strip() if out.strip() else ""
    time.sleep(warmup)
    print(f"广播脚本已启动 pid={pid}（lamp={lamp} raw={raw} 每 {BROADCAST_INTERVAL}s 一帧）")
    return pid


# 引导卡（导航卡）区域 —— 1920×1080 固定布局下，导航卡恒在左上角这块。
# 限制在这个范围内找灯圆，才不会把状态栏头像 / Dock 图标 / 续航条当成灯。
CARD_REGION = (40, 70, 570, 300)

# 灯圆直径 46（TRAFFIC_LAMP_D），允许 ±6 的测量误差。
LAMP_SIZE_MIN = 38
LAMP_SIZE_MAX = 54


def find_lamp_blob(img, want):
    """找灯圆：**只取期望颜色**的像素，在引导卡区域内按「接近正方」筛连通块。

    ## 为什么要限制区域 + 按尺寸筛

    满屏都是绿色：状态栏左上角的「C」头像徽章、引导卡里的转向箭头、
    续航进度条、Dock 图标。它们有的也是「接近正方的小块」。

    - 区域限制（[CARD_REGION]）先排掉状态栏和下面几屏；
    - 尺寸限制（[LAMP_SIZE_MIN]~[LAMP_SIZE_MAX]）排掉转向箭头（60×70）;
    - 最后取**像素最多的**那个 —— 灯圆是直径 46 的**实心**圆，比任何徽章都实。
    """
    from collections import deque
    px = img.load()
    W, H = img.size
    x_lo, y_lo, x_hi, y_hi = CARD_REGION
    pts = set()
    for y in range(y_lo, min(H, y_hi)):
        for x in range(x_lo, min(W, x_hi)):
            r, g, b = px[x, y]
            if want == "red":
                if r > RED_MIN and g < 100 and b < 100:
                    pts.add((x, y))
            else:
                if g > GREEN_MIN and g > r + 35 and g > b + 15:
                    pts.add((x, y))
    blobs = []
    remaining = set(pts)
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
    squares = [b for b in blobs if LAMP_SIZE_MIN <= b[6] <= LAMP_SIZE_MAX
               and LAMP_SIZE_MIN <= b[7] <= LAMP_SIZE_MAX
               and abs(b[6] - b[7]) <= 8]
    pool = squares or blobs
    if not pool:
        return None
    pool.sort(key=lambda b: -b[0])
    return pool[0]


def digit_box(blob):
    """读秒区在灯圆**下方**：给出 (左, 上, 右, 下) 的裁剪框（相对灯圆）。

    宽度取 ±40：读秒是「数字 + 秒」整行**以圆心居中**，
    两位数（甚至三位数）也不会超出这个范围。
    """
    _, _, x0, y0, x1, y1, w, h = blob
    return (max(0, x0 - 40), max(0, y1 - 3),
            min(1920, x1 + 40), min(1080, y1 + 42))


def logcat_nav():
    out = sh_out("shell logcat -d -v time")
    keep = []
    for line in out.splitlines():
        if "红绿灯读秒显示" in line or "收到红绿灯数据" in line:
            keep.append(line)
    return keep


def close_overlays():
    """把设置面板 / 弹窗关掉，回到场景页。

    ## 为什么必须先关

    第八十五轮踩过：设置面板开着的时候截图，截到的是**设置页**，
    红绿灯 chip 根本没渲染 —— 结果「红灯」和「绿灯」两张图 md5 一样。
    这里连按两次 BACK：弹窗 → 面板 → 场景页。
    （桌面在场景页时按 BACK 不会退出，见 EMULATOR-VERIFY.md 的 B6。）
    """
    adb("shell input keyevent BACK")
    time.sleep(1)
    adb("shell input keyevent BACK")
    time.sleep(1.5)


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return
    args = sys.argv[1:]
    tag = args.pop(0)
    lamp = "green"
    raw = 3
    frames = 12
    interval = 1.0
    warmup = 1.0
    while args:
        a = args.pop(0)
        if a == "--lamp":
            lamp = args.pop(0)
        elif a == "--raw":
            raw = int(args.pop(0))
        elif a == "--frames":
            frames = int(args.pop(0))
        elif a == "--interval":
            interval = float(args.pop(0))
        elif a == "--warmup":
            warmup = float(args.pop(0))
        else:
            print(f"未知参数：{a}")
            return

    os.makedirs(OUT, exist_ok=True)
    kill_all_scripts()
    close_overlays()
    start_loop(lamp, raw, "cd" if lamp == "green" else "cr", warmup=warmup)

    from PIL import Image, ImageDraw

    crops = []
    hashes = []
    stamps = []
    full0 = None
    for i in range(frames):
        time.sleep(interval)
        stamps.append(time.strftime("%H:%M:%S"))
        p = os.path.join(OUT, f"_tmp-{tag}-{i:02d}.png")
        screencap(p)
        img = Image.open(p).convert("RGB")
        if full0 is None:
            full0 = os.path.join(OUT, f"{tag}.png")
            img.save(full0)
            blob = find_lamp_blob(img, lamp)
            if blob is None:
                print("❌ 没找到灯圆 —— 引导卡可能没出来（先按 BACK 关掉设置面板）")
                kill_all_scripts()
                return
            _, _, bx0, by0, bx1, by1, bw, bh = blob
            print(f"灯圆 x[{bx0},{bx1}] y[{by0},{by1}] 尺寸 {bw}×{bh}")
            box = digit_box(blob)
        crop = img.crop(box)
        crops.append((i * interval, crop))
        h = hashlib.md5(crop.tobytes()).hexdigest()[:8]
        hashes.append(h)
        os.remove(p)
    print(f"逐帧读秒区哈希：{hashes}")

    # 分组：同样的哈希 = 同一个数字。
    groups = []
    for h in hashes:
        if not groups or groups[-1][0] != h:
            groups.append([h, 0])
        groups[-1][1] += 1
    seq = []
    for h, n in groups:
        seq.append(h if n == 1 else f"{h}×{n}")
    distinct = len(set(hashes))
    print(f"读秒取值序列（按连续段）：{' → '.join(seq)}")
    print(f"distinct={distinct}（2 = 两个值来回跳 = 锯齿；≥3 = 单调往下走）")
    # 回跳检测：**按连续段**看 —— 同一个值连续出现几帧不算回跳，
    # 只有「A → B → A」这种**离开又回来**才是锯齿。
    runs = [g[0] for g in groups]
    seen = set()
    back = False
    for h in runs:
        if h in seen:
            back = True
            break
        seen.add(h)
    print(f"回跳={'有（锯齿：同一个值离开后又回来了）' if back else '无（单调：一路往下走）'}")
    print(f"连续段序列：{' → '.join(runs)}")

    # 拼对比图：每帧一行，左边写时刻
    scale = 3
    cw, ch = crops[0][1].size
    pad_l = 92
    sheet = Image.new("RGB", (pad_l + cw * scale, (ch * scale + 6) * len(crops)), (24, 26, 30))
    d = ImageDraw.Draw(sheet)
    for idx, (t, crop) in enumerate(crops):
        y = idx * (ch * scale + 6)
        big = crop.resize((cw * scale, ch * scale), Image.LANCZOS)
        sheet.paste(big, (pad_l, y))
        d.text((6, y + ch * scale // 2 - 14), f"t={t:4.1f}s", fill=(220, 220, 220))
        d.text((6, y + ch * scale // 2 - 2), stamps[idx], fill=(200, 200, 120))
        d.text((6, y + ch * scale // 2 + 10), hashes[idx], fill=(150, 190, 240))
        d.line([(0, y), (sheet.size[0], y)], fill=(70, 74, 80), width=1)
    sheet_path = os.path.join(OUT, f"{tag}-frames.png")
    sheet.save(sheet_path)
    print("逐帧对比图 ->", sheet_path, sheet.size)

    logs = logcat_nav()
    if logs:
        print("---- logcat（红绿灯）----")
        for line in logs[-40:]:
            print(line)
    else:
        print("---- logcat 里没有红绿灯读秒行（旧包没有 D 级日志）----")

    kill_all_scripts()


if __name__ == "__main__":
    main()
