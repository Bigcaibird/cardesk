#!/usr/bin/env python3
# 第193轮模拟器验证：读 dumpsys window，报告三个关键窗口的可见性。
# 判据（来自 GlobalDockService.updateVisibility）：
#   让位   → 「CarDesk 全局 Dock」mViewVisibility=0x8 / isOnScreen=false，
#            「CarDesk Dock 让位把手」isOnScreen=true
#   不让位 → 「CarDesk 全局 Dock」mViewVisibility=0x0 / isOnScreen=true，
#            把手 isOnScreen=false
import re
import subprocess
import sys

ADB = ["/Users/roy/Documents/desktopapp/toolchain/sdk/platform-tools/adb", "-s", "emulator-5560"]
TARGETS = ["CarDesk 全局 Dock", "CarDesk Dock 让位把手", "NavigationBar"]

out = subprocess.run(ADB + ["shell", "dumpsys", "window", "windows"],
                     capture_output=True, text=True).stdout

blocks = re.split(r"\n  Window #\d+ Window\{", out)
rows = []
for b in blocks:
    if not b.strip():
        continue
    head = b.split("}", 1)[0].strip()
    name = re.sub(r"^\S+\s+u\d+\s+", "", head)
    if name not in TARGETS:
        continue
    frame = re.search(r"mFrame=(\[[^\]]*\]\[[^\]]*\])", b)
    vis = re.search(r"mViewVisibility=(0x[0-9a-f]+)", b)
    onscreen = re.search(r"isOnScreen=(\w+)", b)
    visible = re.search(r"\n    isVisible=(\w+)", b)
    ty = re.search(r"ty=(\w+)", b)
    rows.append((
        name,
        frame.group(1) if frame else "?",
        vis.group(1) if vis else "?",
        onscreen.group(1) if onscreen else "?",
        visible.group(1) if visible else "?",
        ty.group(1) if ty else "?",
    ))

print("  %-24s %-22s %-7s %-7s %-7s %s" % ("窗口", "帧", "视图可见", "在屏", "可见", "类型"))
for r in rows:
    print("  %-24s %-22s %-7s %-7s %-7s %s" % r)

if not rows:
    print("  （没找到目标窗口）")
    sys.exit(1)
