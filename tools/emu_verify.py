#!/usr/bin/env python3
"""CarDesk 模拟器验证助手：adb 封装 + 截图 + 关键流程。

用法:
  python3 emu_verify.py boot            # 等模拟器启动完成
  python3 emu_verify.py install         # 安装 debug APK 并设为桌面
  python3 emu_verify.py shot NAME       # 截图到 emu-verify/NAME.png
  python3 emu_verify.py tap X Y         # 点击
  python3 emu_verify.py seq-home        # 首页 / 开空调 / 360 / 音乐 / 抽屉
  python3 emu_verify.py seq-settings    # 设置面板逐页
  python3 emu_verify.py seq-system      # system 版能力（uid/silent install/adb）
  python3 emu_verify.py seq-lan         # 局域网传输上传/下载
  python3 emu_verify.py seq-task        # 计划任务到点拉起
  python3 emu_verify.py logcat-errors   # 抓 FATAL / Exception
"""
import os
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
SDK = os.path.expanduser("~/Documents/desktopapp/toolchain/sdk")
ADB = os.path.join(SDK, "platform-tools", "adb")
OUT = os.path.join(HERE, "..", "emu-verify")
APK_DEBUG = os.path.join(HERE, "..", "dist", "CarDesk-debug.apk")
APK_SYSTEM = os.path.join(HERE, "..", "dist", "CarDesk-replace-ecarx-4357.apk")
PKG = "com.cardesk.launcher"


def sh(cmd, timeout=120, check=False):
    print("$", cmd)
    p = subprocess.run(cmd, shell=True, capture_output=True, text=True, timeout=timeout)
    out = (p.stdout or "") + (p.stderr or "")
    if out.strip():
        print(out.strip()[:4000])
    if check and p.returncode != 0:
        raise SystemExit(f"command failed ({p.returncode}): {cmd}")
    return p


def adb(args, **kw):
    return sh(f'"{ADB}" {args}', **kw)


def ensure_out():
    os.makedirs(OUT, exist_ok=True)


def boot():
    adb("wait-for-device", timeout=600)
    for _ in range(120):
        r = subprocess.run(f'"{ADB}" shell getprop sys.boot_completed', shell=True, capture_output=True, text=True)
        if r.stdout.strip() == "1":
            print("boot completed")
            adb("shell settings put global window_animation_scale 0")
            adb("shell settings put global transition_animation_scale 0")
            adb("shell settings put global animator_duration_scale 0")
            return
        time.sleep(2)
    raise SystemExit("boot timeout")


def shot(name):
    ensure_out()
    path = os.path.join(OUT, f"{name}.png")
    with open(path, "wb") as f:
        subprocess.run(f'"{ADB}" exec-out screencap -p', shell=True, stdout=f, check=False)
    print("shot ->", path)


def tap(x, y, wait=0.8):
    adb(f"shell input tap {x} {y}")
    time.sleep(wait)


def swipe(x1, y1, x2, y2, ms=300, wait=0.8):
    adb(f"shell input swipe {x1} {y1} {x2} {y2} {ms}")
    time.sleep(wait)


def key(code, wait=0.6):
    adb(f"shell input keyevent {code}")
    time.sleep(wait)


def install(apk=APK_DEBUG, pkg=PKG):
    adb(f'uninstall {pkg}', timeout=60)
    adb(f'install -r -g "{apk}"', timeout=300, check=True)
    grant()


def grant():
    adb(f"shell cmd notification allow_listener {PKG}/com.cardesk.launcher.core.media.MediaNotificationListener")
    adb(f"shell pm grant {PKG} android.permission.BLUETOOTH_SCAN")
    adb(f"shell pm grant {PKG} android.permission.BLUETOOTH_CONNECT")
    adb(f"shell pm grant {PKG} android.permission.ACCESS_FINE_LOCATION")
    adb(f"shell cmd package set-home-activity {PKG}/.MainActivity")
    adb(f"shell am force-stop {PKG}")
    adb(f"shell am start -n {PKG}/.MainActivity")
    time.sleep(3)


def prefs_write(xml_name, content):
    """通过 run-as 写 SharedPreferences（debug 可签）。"""
    tmp = "/data/data/%s/shared_prefs/%s" % (PKG, xml_name)
    local = os.path.join(HERE, "..", "emu-verify", xml_name)
    os.makedirs(os.path.dirname(local), exist_ok=True)
    with open(local, "w") as f:
        f.write(content)
    adb(f'push "{local}" /data/local/tmp/{xml_name}')
    adb(f"shell run-as {PKG} mkdir -p shared_prefs")
    adb(f"shell run-as {PKG} cp /data/local/tmp/{xml_name} shared_prefs/{xml_name}")
    print("prefs written:", tmp)


def logcat_errors(tag=""):
    adb("logcat -d -v brief")
    adb("logcat -c")


def seq_home():
    shot("01-home")
    # 空调页（Dock 风扇）
    tap(1225, 1020)
    shot("02-climate")
    # 电源/AC/AUTO：气候页底部? 直接用 Dock 的电源与 AC 快捷验证状态联动
    tap(954, 1020)      # power
    tap(1503, 1020)     # A/C
    tap(1597, 1020)     # AUTO
    shot("03-climate-toggles")
    key(4)              # back -> 关闭气候页
    # 360
    tap(309, 1020)
    shot("04-360")
    tap(830, 1010)      # 前视
    shot("05-360-front")
    tap(1320, 1010)     # 退出
    # 音乐页
    tap(600, 1020)
    shot("06-music")
    key(4)
    # 应用抽屉
    tap(157, 1020)
    shot("07-drawer")
    key(4)
    # 设置
    tap(1885, 48)
    shot("08-settings")


def seq_settings():
    # 设置左栏导航（y 从 320 起，行高 60）
    rows = {
        "boot": 322, "update": 382, "keys": 470, "tasks": 530,
        "daynight": 620, "layout": 680, "navbar": 740,
        "camera360": 470, "tpms": 530, "dock": 470, "home": 530,
        "autostart": 620, "native": 680, "lan": 740, "activate": 800,
    }
    for name, y in [("boot", 322), ("update", 382), ("keys", 470), ("tasks", 530),
                    ("daynight", 620), ("layout", 680), ("navbar", 740)]:
        tap(470, y)
        shot(f"09-settings-{name}")
    # 切到「系统」tab（右上 tab）
    tap(700, 208)
    for name, y in [("camera360", 322), ("tpms", 382), ("dock", 470), ("home", 530),
                    ("autostart", 620), ("native", 680), ("lan", 740)]:
        tap(470, y)
        shot(f"10-settings-{name}")


def seq_lan():
    adb("forward tcp:18765 tcp:18765")
    print("在设置里手动启动局域网传输后，用 curl 上传验证")


def seq_task():
    adb(f"shell am force-stop {PKG}")
    adb(f"shell am start -n {PKG}/.MainActivity")
    time.sleep(3)
    print("计划任务：设置里新建一个 5 秒后打开「设置」的任务，然后等待 AlarmManager 触发")


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return
    cmd = sys.argv[1]
    if cmd == "boot":
        boot()
    elif cmd == "install":
        install()
    elif cmd == "install-system":
        install(APK_SYSTEM, "com.ecarx.qq.tool")
    elif cmd == "shot":
        shot(sys.argv[2])
    elif cmd == "tap":
        tap(int(sys.argv[2]), int(sys.argv[3]))
    elif cmd == "seq-home":
        seq_home()
    elif cmd == "seq-settings":
        seq_settings()
    elif cmd == "seq-lan":
        seq_lan()
    elif cmd == "seq-task":
        seq_task()
    elif cmd == "logcat-errors":
        logcat_errors()
    else:
        print("unknown command", cmd)


if __name__ == "__main__":
    main()
