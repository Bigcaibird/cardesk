#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""歌词**逐字擦除**的模拟器定点取证工具（第八十一轮起）。

## 为什么要有这个脚本

用户要求：**「以后新功能开发都需要在虚拟机跑一遍结果，并提供截图」**。

第八十一轮修的是「**一句歌词还没擦除完就到下一句**」。
这个问题的特点是：**设计板（Python 重绘）完全看不出来** ——
因为问题不在排版，而在「擦除进度随时间怎么走」。

所以需要一个能**把播放位置钉死在某个毫秒**、然后截出擦除状态的工具：

- 探针（`com.cardesk.mediaprobe`）用 `position` + `play=false` 注入 MediaSession，
  位置就**冻结**在那个毫秒 ⇒ 同一个位置可以反复截图、可与旧包 A/B 对比；
- 截图后裁出**歌词区**，肉眼直接看擦除边界落在哪。

## 用法

```bash
# ① 钉到某个位置并截图（自动裁歌词区）
python3 tools/lyric_shot.py at 103497 r81-new-103497

# ② 只钉位置（不截图）
python3 tools/lyric_shot.py seek 103497

# ③ 只截图
python3 tools/lyric_shot.py shot r81-xxx
```

## 歌词区裁剪框

设计板坐标 1920×1080：音乐卡在右侧，歌词三行大约在
`x=1370..1880`、`y=375..565`（当前行 + 后面两行）。
"""
import os
import subprocess
import sys
import time

from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
SDK = os.environ.get("CARDESK_SDK", os.path.expanduser("~/Documents/desktopapp/toolchain/sdk"))
ADB = os.path.join(SDK, "platform-tools", "adb")
OUT = os.path.join(REPO, "emu-verify")

DEVICE = os.environ.get("CARDESK_DEVICE", "emulator-5554")
PKG = os.environ.get("CARDESK_PKG", "com.ecarx.cardesk")
PROBE = "com.cardesk.mediaprobe"
ACTIVITY = "com.cardesk.launcher.MainActivity"

TITLE = "怎么我睡不着你也不来救我"
ARTIST = "焦迈奇"
DURATION = "277000"

# 歌词区裁剪框（PIL 的 crop 取 (left, upper, right, lower)）
CROP = (1370, 375, 1880, 565)


def adb(args, timeout=120):
    return subprocess.run(
        f'"{ADB}" -s {DEVICE} {args}', shell=True, capture_output=True, text=True, timeout=timeout
    )


def seek(position_ms, play=False):
    """把探针重开在指定位置（play=false ⇒ 位置冻结）。"""
    adb(f"shell am force-stop {PROBE}")
    time.sleep(1.0)
    adb(
        f'shell am start -n {PROBE}/.MainActivity --es title "{TITLE}" --es artist "{ARTIST}" '
        f'--es album "{ARTIST}" --es duration "{DURATION}" --es position "{int(position_ms)}" '
        f'--es play "{"true" if play else "false"}"'
    )
    time.sleep(2.0)
    adb(f"shell am start -n {PKG}/{ACTIVITY}")
    time.sleep(3.0)


def shot(name):
    os.makedirs(OUT, exist_ok=True)
    path = os.path.join(OUT, f"{name}.png")
    with open(path, "wb") as f:
        subprocess.run(
            f'"{ADB}" -s {DEVICE} exec-out screencap -p', shell=True, stdout=f, check=False
        )
    print("shot ->", path)
    crop(path)
    return path


def crop(path):
    """裁出歌词区，放大 2 倍（肉眼看擦除边界用）。"""
    try:
        img = Image.open(path)
        box = img.crop(CROP)
        out = path[: -len(".png")] + "-lyric.png"
        box.resize((box.width * 2, box.height * 2), Image.LANCZOS).save(out)
        print("crop ->", out)
    except Exception as e:  # 裁剪失败不影响主流程
        print("crop failed:", e)


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return
    cmd = sys.argv[1]
    if cmd == "at":
        seek(int(sys.argv[2]))
        shot(sys.argv[3] if len(sys.argv) > 3 else "r81-lyric")
    elif cmd == "seek":
        seek(int(sys.argv[2]))
    elif cmd == "shot":
        shot(sys.argv[2])
    else:
        print("unknown command", cmd)


if __name__ == "__main__":
    main()
