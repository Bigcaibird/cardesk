#!/usr/bin/env python3
"""氛围灯动感取证：合成音源 → 进氛围灯页 → 抓连续帧 → 量化「颜色在流动」。

## 为什么要有这个脚本

第八十五轮之前，氛围灯「动得僵硬」这件事**只能靠感觉**：
- 车控通道在模拟器上写不出去 ⇒ 写车控那行日志根本不会出现；
- 设置页只显示**当前**一个颜色，看不出它是「流过去的」还是「跳过去的」。

所以这一轮加了两样东西：

1. **合成音源**（`media-probe` 的 `ToneActivity`，用 `AudioTrack` 播鼓 + 贝斯）
   —— 模拟器里本来没有任何能持续播放的音源（`EMULATOR-VERIFY.md` 第 0 节写过这条），
   于是频谱条一直是空的，灯当然也「不动」。
   用 `AudioTrack` 是关键：它走 **AudioFlinger**，输出混音的 `Visualizer(0)` 才抓得到
   （用 `tinyplay` 直接写 `/dev/snd` 是绕过 AudioFlinger 的，抓不到）。

2. **逐帧日志**（`AmbientLightController` 的「氛围灯帧」）
   —— 一行里同时记**目标色相**与**平滑后的当前色相**，
   于是「目标是台阶、当前是斜坡」在日志里一眼可见，
   还能直接算出两条链路的单帧跳变（见下面的「量化」）。

## 量化

日志里相邻两行的：

- `targetHue` 相减 = **老链路**的色相跳变（老代码直接拿它当色相）；
- `hue` 相减 = **新链路**（动感层平滑后）的色相跳变。

色相差再换成 RGB 距离（就是 `AmbientPalette.colorDistance` 的算法），
就是「灯一帧跳多远」—— 直接可比的数字。

## 用法

```bash
python3 tools/ambient_shot.py            # 默认抓 8 帧
python3 tools/ambient_shot.py 12         # 抓 12 帧
python3 tools/ambient_shot.py 8 mytag    # 自定义输出名
```
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
PKG = "com.ecarx.cardesk"
LOG_DIR = f"/data/data/{PKG}/files/logs"

# —— 设置页的点击坐标（1920×1080 设计坐标）——
#
# 「氛围灯」在左侧导航的「车控」组第一项，**要先滚动导航才看得见**。
# 这些坐标是实测出来的（滚完导航后截图量的），不要凭印象改。
TAP_AVATAR = (47, 37)      # 左上角头像 → 打开设置
NAV_SWIPE = (430, 880, 430, 660)   # 导航往下滚一段
TAP_AMBIENT = (305, 667)   # 「氛围灯」
TAP_AMBIENT_SWITCH = (1560, 285)   # 「启用氛围灯」开关

TONE_PKG = "com.cardesk.mediaprobe"
TONE_ACTIVITY = f"{TONE_PKG}/.ToneActivity"


def adb(args, timeout=120):
    return subprocess.run(f'"{ADB}" -s {DEVICE} {args}', shell=True,
                          capture_output=True, text=True, timeout=timeout)


def sh(args, timeout=120):
    return (adb(args, timeout=timeout).stdout or "")


def log_text():
    """App 自己的日志文件（最新那份）。

    只用于**落盘级别**（I/W/E）的行；逐帧日志是 D 级，默认不落盘
    （见 `LogLevel` 的说明），所以那部分从 logcat 拿。
    """
    out = sh(f"shell 'ls -t {LOG_DIR}/*.txt 2>/dev/null | head -1'").strip()
    if not out:
        return ""
    return sh(f"shell 'cat {out}'")


def start_logcat_capture(path):
    """后台抓 logcat（逐帧日志是 D 级，默认**不落盘**，只能从 logcat 拿）。

    直接用 `adb logcat -d` 回读缓冲区不行 —— 环形缓冲区里可能已经被别的进程刷走，
    所以这里在抓帧**之前**就开始后台抓，抓完再停。
    """
    handle = open(path, "w")
    # 先清空缓冲区 —— 否则抓到的第一行是**上一次**跑剩的旧行（量出来会全错）。
    subprocess.run(f'"{ADB}" -s {DEVICE} logcat -c', shell=True, capture_output=True)
    proc = subprocess.Popen(
        [ADB, "-s", DEVICE, "logcat", "-v", "brief",
         "CarDesk/Ambient:V", "CarDesk/Setting:V", "*:S"],
        stdout=handle, stderr=subprocess.STDOUT,
    )
    time.sleep(1.5)  # 等 logcat 把 tag 过滤装好
    return proc


def stop_logcat_capture(proc, path):
    time.sleep(0.5)
    proc.terminate()
    try:
        proc.wait(timeout=5)
    except Exception:
        pass
    with open(path) as f:
        return f.read()


def screencap(path):
    with open(path, "wb") as f:
        subprocess.run(f'"{ADB}" -s {DEVICE} exec-out screencap -p', shell=True, stdout=f)
    return path


def start_tone():
    """起合成音源（鼓 + 贝斯循环），再把 CarDesk 切回前台。

    ## 为什么要切回前台

    `am start` 会把 ToneActivity 带到前台 —— 后面的点击就全落在它身上了。
    切回 CarDesk 后音源**照样在播**（`AudioTrack` 不随 Activity 失焦而停），
    但焦点回到桌面这边才能点进设置页。
    """
    adb(f"shell am start -n {TONE_ACTIVITY}")
    time.sleep(3)
    adb(f"shell am start -n {PKG}/com.cardesk.launcher.MainActivity")
    time.sleep(2)


def open_ambient_page():
    """进「氛围灯」设置页。

    靠 App 自己的日志确认（`进入设置页 page=ambient`），
    不靠截图猜 —— 坐标一旦偏了，截图还能看见「启动切换」那一页，
    很容易误判成「进去了」。

    ## 为什么先按返回键

    上一轮跑完时设置页还开着，这时再点左上角头像**点不到**
    （浮层已经盖在上面了）⇒ 必须先退到桌面再重新进。
    """
    before = log_text().count("进入设置页 page=ambient")
    # 先退到桌面（浮层开着的时候返回键是关浮层，再按一次是回首页）
    for _ in range(2):
        adb("shell input keyevent KEYCODE_BACK")
        time.sleep(0.6)
    adb(f"shell input tap {TAP_AVATAR[0]} {TAP_AVATAR[1]}")
    time.sleep(2)
    adb(f"shell input swipe {NAV_SWIPE[0]} {NAV_SWIPE[1]} {NAV_SWIPE[2]} {NAV_SWIPE[3]} 300")
    time.sleep(1)
    adb(f"shell input tap {TAP_AMBIENT[0]} {TAP_AMBIENT[1]}")
    time.sleep(2)
    after = log_text().count("进入设置页 page=ambient")
    ok = after > before
    print(f"进入氛围灯页：{'✅' if ok else '❌'}（日志计数 {before} → {after}）")
    return ok


def ensure_switch_on():
    """确保「启用氛围灯」是开的。

    以 App 日志为准（`氛围灯开关 from=false to=true`），
    而不是去点一下 —— 开关本来就是开的话，再点一下就把它关了。
    """
    text = log_text()
    idx = text.rfind("氛围灯开关")
    if idx < 0:
        print("开关状态未知（日志里没有开关记录）→ 点一次")
        adb(f"shell input tap {TAP_AMBIENT_SWITCH[0]} {TAP_AMBIENT_SWITCH[1]}")
        time.sleep(3)
        return True
    tail = text[idx:idx + 120]
    on = "to=true" in tail
    if on:
        print("开关：已开 ✅")
        return True
    print("开关：关 → 点开")
    adb(f"shell input tap {TAP_AMBIENT_SWITCH[0]} {TAP_AMBIENT_SWITCH[1]}")
    time.sleep(3)
    return True


def parse_frames(text):
    """从日志里抠出「氛围灯帧」那一行行。"""
    rows = []
    for line in text.splitlines():
        if "氛围灯帧" not in line:
            continue
        row = {"_line": line}
        for key in ("targetHue", "hue", "targetLevel", "brightness", "bass", "dtMs"):
            m = re.search(rf"{key}=([-\d.]+)", line)
            if m:
                row[key] = float(m.group(1))
        m = re.search(r"hex=(#[0-9A-Fa-f]{6})", line)
        if m:
            row["hex"] = m.group(1)
        if "hue" in row and "targetHue" in row:
            rows.append(row)
    return rows


def hue_distance(a, b):
    """色相的最短角差（度）。"""
    d = (b - a) % 360.0
    if d > 180.0:
        d -= 360.0
    if d <= -180.0:
        d += 360.0
    return d


def rgb_of(hue):
    """和 `AmbientPalette.hsvToRgb` 同一套算法（饱和度 0.85，v = 1）。"""
    hue = hue % 360.0
    h = hue / 60.0
    c = 0.85
    x = c * (1 - abs(h % 2 - 1))
    m = 1 - c
    if h < 1:
        r1, g1, b1 = c, x, 0
    elif h < 2:
        r1, g1, b1 = x, c, 0
    elif h < 3:
        r1, g1, b1 = 0, c, x
    elif h < 4:
        r1, g1, b1 = 0, x, c
    elif h < 5:
        r1, g1, b1 = x, 0, c
    else:
        r1, g1, b1 = c, 0, x
    return ((r1 + m) * 255, (g1 + m) * 255, (b1 + m) * 255)


def color_distance(h1, h2):
    a = rgb_of(h1)
    b = rgb_of(h2)
    return sum((x - y) ** 2 for x, y in zip(a, b)) ** 0.5


def main():
    frames = int(sys.argv[1]) if len(sys.argv) > 1 else 8
    tag = sys.argv[2] if len(sys.argv) > 2 else "r85-ambient"

    os.makedirs(OUT, exist_ok=True)
    cap_path = os.path.join("/tmp", "cardesk-ambient-logcat.txt")
    print("=== 起 logcat 抓取（逐帧日志是 D 级，不落盘） ===")
    cap = start_logcat_capture(cap_path)
    print("=== 起合成音源 ===")
    start_tone()
    print("=== 进氛围灯页 ===")
    if not open_ambient_page():
        print("❌ 没进到氛围灯页，后面没法验")
        stop_logcat_capture(cap, cap_path)
        return 1
    ensure_switch_on()
    time.sleep(3)

    print(f"=== 抓 {frames} 帧 ===")
    shots = []
    for i in range(frames):
        p = os.path.join(OUT, f"{tag}-{i + 1:02d}.png")
        screencap(p)
        shots.append(p)
        print(f"  {i + 1}/{frames} -> {os.path.basename(p)}")
        time.sleep(0.4)

    text = stop_logcat_capture(cap, cap_path)
    with open(cap_path, "w") as f:
        f.write(text)

    # —— 量化 ——
    rows = parse_frames(text)
    print(f"\n=== 逐帧日志 {len(rows)} 行 ===")
    if len(rows) < 2:
        print("❌ 日志里没有足够的「氛围灯帧」—— 频谱可能没数据")
        for r in rows[:5]:
            print("   ", r["_line"])
        return 1

    old_jumps, new_jumps = [], []
    for a, b in zip(rows, rows[1:]):
        old_jumps.append(abs(hue_distance(a["targetHue"], b["targetHue"])))
        new_jumps.append(abs(hue_distance(a["hue"], b["hue"])))
    old_max = max(old_jumps)
    new_max = max(new_jumps)
    old_rgb = max(color_distance(a["targetHue"], b["targetHue"]) for a, b in zip(rows, rows[1:]))
    new_rgb = max(color_distance(a["hue"], b["hue"]) for a, b in zip(rows, rows[1:]))

    print(f"目标色相（老链路直接用的那个）：{rows[0]['targetHue']:.1f}° → "
          f"{rows[-1]['targetHue']:.1f}°，单步最大跳 {old_max:.1f}° / RGB {old_rgb:.1f}")
    print(f"平滑后色相（新链路）        ：{rows[0]['hue']:.1f}° → "
          f"{rows[-1]['hue']:.1f}°，单步最大跳 {new_max:.1f}° / RGB {new_rgb:.1f}")
    if old_max > 0:
        print(f"⇒ 单步色相跳变降到 {old_max / max(new_max, 1e-6):.1f}× "
              f"（RGB 距离 {old_rgb / max(new_rgb, 1e-6):.1f}×）")
    print("\n前 12 帧（目标 → 平滑后）：")
    for r in rows[:12]:
        print(f"  targetHue={r['targetHue']:6.1f}°  hue={r['hue']:6.1f}°  "
              f"brightness={r.get('brightness', 0):5.1f}  bass={r.get('bass', 0):.3f}  {r.get('hex', '')}")

    print("\n第一帧：")
    for r in rows:
        print("   ", r["_line"].strip())
        break
    return 0


if __name__ == "__main__":
    sys.exit(main())
