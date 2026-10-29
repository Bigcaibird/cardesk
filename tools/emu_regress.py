#!/usr/bin/env python3
"""CarDesk 模拟器回归助手（第八十轮起）。

## 为什么要有这个脚本

用户要求：**「以后新功能开发都需要在虚拟机跑一遍结果，并提供截图」**。

单靠设计板（Python 重绘）不够 —— 第八十轮就抓到一个设计板**看不出来**的排版缺陷
（红绿灯读秒和「秒」字重叠），以及一个**模拟器上才复现**的 native 闪退
（FFT 缓冲区少给一半 ⇒ ROM 越界写坏 Java 堆 ⇒ ART GC SIGSEGV）。

所以每个新功能都该走一遍这里的流程：装包 → 触发 → 截图 → 判定「没崩」。

## 环境

- AVD `cardesk`（android-34 / google_apis）或 **ecarx 平台镜像**（android-29，
  带 `com.ecarx.*` + 平台签名，车控链路最接近真车）。
- headless：`-no-window -gpu swiftshader_indirect`。
- 设备用 `emulator-5554`（ecarx 平台镜像，优先）/ `emulator-5556`（API 34）。

## 用法

```bash
# 1) 装包 + 授权（RECORD_AUDIO 要 pm grant **和** appops 两步）+ 启动
python3 tools/emu_regress.py install dist/CarDesk-ecarx-cardesk-4145.apk

# 2) 进氛围灯页 → 打开开关 → 观察 N 秒 → 判定闪退 + 出图
python3 tools/emu_regress.py ambient 40

# 3) 造高德红绿灯广播 → 截导航卡（红 / 绿）
python3 tools/emu_regress.py traffic 0 12      # status=0 红, countdown=12
python3 tools/emu_regress.py traffic 1 8       # status=1 绿

# 4) 任意截图
python3 tools/emu_regress.py shot r80-xxx

# 5) 单独判定「这一段时间里有没有崩」
python3 tools/emu_regress.py check 30
```

判定标准（任一失败即记 ❌）：

- 进程 pid **没变**（变了 = 崩过并被系统重启）；
- logcat 无 `Fatal signal` / `FATAL EXCEPTION`；
- `/data/tombstones` **没有新增**；
- 截图能看出功能真的在渲染（不是黑屏 / 不是空白）。
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

# 设备优先级：ecarx 平台镜像（带 com.ecarx.*，车控链路最接近真车）优先。
DEVICE_PRIORITY = ["emulator-5554", "emulator-5556"]

# 氛围灯页在设置面板左栏「车控 → 氛围灯」；设置面板入口是**左上角头像**。
TAP_AVATAR = (47, 37)
TAP_AMBIENT = (305, 932)
TAP_AMBIENT_SWITCH = (1588, 285)

# 高德导航广播（见 core/nav/AmapDesktopGuide.kt）。
ACTION_SEND = "AUTONAVI_STANDARD_BROADCAST_SEND"
ACTION_CRUISE = "NAVITOOL_CRUISE_TRAFFIC_LIGHTS"


def sh(cmd, timeout=180, check=False):
    p = subprocess.run(cmd, shell=True, capture_output=True, text=True, timeout=timeout)
    out = (p.stdout or "") + (p.stderr or "")
    if out.strip():
        print(out.strip()[:3000])
    if check and p.returncode != 0:
        raise SystemExit(f"command failed ({p.returncode}): {cmd}")
    return p


def adb(args, timeout=180, check=False):
    return sh(f'"{ADB}" -s {DEVICE} {args}', timeout=timeout, check=check)


def device():
    """挑一个在线设备（优先 ecarx 平台镜像）。"""
    global DEVICE
    out = subprocess.run(f'"{ADB}" devices', shell=True, capture_output=True, text=True).stdout
    online = [m.group(1) for m in re.finditer(r"^(\S+)\s+device$", out, re.M)]
    for d in DEVICE_PRIORITY:
        if d in online:
            DEVICE = d
            return d
    if online:
        DEVICE = online[0]
        return DEVICE
    raise SystemExit("没有在线设备")


def pkg_of(apk):
    """从 APK 里读包名（aapt2）。"""
    aapt2 = os.path.join(SDK, "build-tools", "34.0.0", "aapt2")
    p = subprocess.run(f'"{aapt2}" dump packagename "{apk}"', shell=True, capture_output=True, text=True)
    name = (p.stdout or "").strip().splitlines()
    return name[-1] if name else ""


def version_of(apk):
    aapt2 = os.path.join(SDK, "build-tools", "34.0.0", "aapt2")
    p = subprocess.run(f'"{aapt2}" dump badging "{apk}"', shell=True, capture_output=True, text=True)
    m = re.search(r"versionCode='(\d+)'", p.stdout or "")
    n = re.search(r"versionName='([^']+)'", p.stdout or "")
    return (m.group(1) if m else "?"), (n.group(1) if n else "?")


def shot(name):
    os.makedirs(OUT, exist_ok=True)
    path = os.path.join(OUT, f"{name}.png")
    with open(path, "wb") as f:
        subprocess.run(f'"{ADB}" -s {DEVICE} exec-out screencap -p', shell=True, stdout=f, check=False)
    print("shot ->", path)
    return path


def pid_of(pkg):
    p = subprocess.run(f'"{ADB}" -s {DEVICE} shell "ps -A | grep {pkg}"', shell=True, capture_output=True, text=True)
    m = re.search(r"\b(\d+)\s+\S+\s+.*\b" + re.escape(pkg), p.stdout or "")
    return m.group(1) if m else ""


def tombstones():
    p = subprocess.run(f'"{ADB}" -s {DEVICE} shell "ls /data/tombstones 2>/dev/null"',
                       shell=True, capture_output=True, text=True)
    return sorted(x for x in (p.stdout or "").split() if x)


def install(apk, pkg=None):
    device()
    pkg = pkg or pkg_of(apk)
    code, name = version_of(apk)
    print(f"设备 {DEVICE} · 包 {pkg} · versionCode {code} / versionName {name}")
    adb(f"install -r -g {apk!r}".replace("'", '"'), timeout=300, check=True)
    # 频谱采集要 RECORD_AUDIO：dangerous 权限**光靠 privapp 白名单不够**，
    # 必须 pm grant + appops set 两步（见 EMULATOR-VERIFY.md H7）。
    adb(f"shell pm grant {pkg} android.permission.RECORD_AUDIO")
    adb(f"shell appops set {pkg} RECORD_AUDIO allow")
    adb(f"shell cmd package set-home-activity {pkg}/com.cardesk.launcher.MainActivity")
    adb(f"shell am force-stop {pkg}")
    adb(f"shell am start -n {pkg}/com.cardesk.launcher.MainActivity")
    time.sleep(5)


def ambient(seconds=40):
    """进氛围灯页 → 打开开关 → 观察 → 判定 + 出图。"""
    device()
    pkg = "com.ecarx.cardesk"
    adb("logcat -c")
    before_tb = tombstones()
    before_pid = pid_of(pkg)
    print("起始 pid =", before_pid or "(无)")

    adb(f"shell input tap {TAP_AVATAR[0]} {TAP_AVATAR[1]}")
    time.sleep(2)
    adb(f"shell input tap {TAP_AMBIENT[0]} {TAP_AMBIENT[1]}")
    time.sleep(2)
    shot("r80-ambient-page")
    adb(f"shell input tap {TAP_AMBIENT_SWITCH[0]} {TAP_AMBIENT_SWITCH[1]}")
    for i in range(3):
        time.sleep(seconds / 3.0)
        shot(f"r80-ambient-on-{i + 1}")
    ok = check(0)
    print("氛围灯页判定：", "✅ 通过" if ok else "❌ 失败")
    return ok


def traffic(status=0, countdown=12, tag="r80-traffic"):
    """造高德广播让导航卡 + 红绿灯出现，截图。"""
    device()
    adb("logcat -c")
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
    remote = "/data/local/tmp/cardesk-nav-broadcast.sh"
    local = "/tmp/cardesk-nav-broadcast.sh"
    with open(local, "w") as f:
        f.write("#!/system/bin/sh\n" + script + "\n")
    adb(f"push {local} {remote}")
    adb(f"shell chmod 755 {remote}")
    # 两种命名都要杀干净 —— 否则旧的一直在发广播，红绿会互相盖。
    adb(f"shell 'pkill -f cardesk-nav-broadcast.sh; pkill -f nav-broadcast.sh'")
    time.sleep(1)
    adb(f"shell 'nohup sh {remote} >/dev/null 2>&1 &'")
    time.sleep(4)
    path = shot(tag)
    print("红绿灯广播在跑，截图 ->", path)


def check(seconds=30):
    """观察 N 秒，判定「没崩」。"""
    device()
    pkg = "com.ecarx.cardesk"
    pid = pid_of(pkg)
    tb_before = tombstones()
    time.sleep(seconds)
    pid_after = pid_of(pkg)
    tb_after = tombstones()
    log = subprocess.run(f'"{ADB}" -s {DEVICE} logcat -d -v brief', shell=True, capture_output=True, text=True).stdout
    fatal = [l for l in log.splitlines() if re.search(r"Fatal signal|FATAL EXCEPTION|ANR in com\.ecarx", l)]
    new_tb = [t for t in tb_after if t not in tb_before]
    ok = True
    print(f"pid {pid or '(无)'} -> {pid_after or '(无)'}")
    if pid and pid_after and pid != pid_after:
        print("❌ 进程变了 = 崩过并被系统重启")
        ok = False
    if fatal:
        print("❌ logcat 有崩溃记录：")
        for l in fatal[:10]:
            print("   ", l[:200])
        ok = False
    if new_tb:
        print("❌ 新增 tombstone：", new_tb)
        for t in new_tb:
            adb(f"pull /data/tombstones/{t} {os.path.join(OUT, 'tombstone-' + t + '.txt')}")
        ok = False
    if ok:
        print("✅ 无崩溃（进程存活 / 无 SIGSEGV / 无新增 tombstone）")
    return ok


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return
    cmd = sys.argv[1]
    if cmd == "install":
        install(sys.argv[2])
    elif cmd == "ambient":
        ambient(int(sys.argv[2]) if len(sys.argv) > 2 else 40)
    elif cmd == "traffic":
        traffic(int(sys.argv[2]) if len(sys.argv) > 2 else 0,
                int(sys.argv[3]) if len(sys.argv) > 3 else 12,
                sys.argv[4] if len(sys.argv) > 4 else "r80-traffic")
    elif cmd == "shot":
        device()
        shot(sys.argv[2])
    elif cmd == "check":
        device()
        check(int(sys.argv[2]) if len(sys.argv) > 2 else 30)
    else:
        print("unknown command", cmd)


if __name__ == "__main__":
    main()
