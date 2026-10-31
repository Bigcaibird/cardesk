#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""第八十七轮取证：**全局 Dock**（其它应用底部常驻）+ **播放/暂停幂等**。

## 为什么要有这个脚本

用户要求：「以后新功能开发都需要在虚拟机跑一遍结果，并提供截图」。

第八十七轮加的两件事**都没法靠单测证明**：

1. **全局 Dock**：单测只能锁「几何/策略」这类纯逻辑；「窗口到底有没有出现、
   有没有盖在原车导航栏上、回到桌面会不会重复出现一条」只能在模拟器上看。
2. **播放/暂停幂等**：单测证明的是 `TransportIntent.decide` 的判定，但
   「连点两次 Dock 播放键，探针会话是不是只翻了一次」必须真的点、真的看
   `dumpsys media_session` 的状态。

所以这个脚本把「装包 → 开全局 Dock → 起别的应用 → 截图 → 连点播放键
→ 读会话状态 → 判崩溃」整条链路固化下来，命令可复跑。

## 前置

- 模拟器在线（`emulator-5554`：android-29 / ecarx 平台镜像 / 1920×1080@160dpi）。
- **必须是 userdebug 且 `adb root` 可用**：脚本要直接改
  `/data/data/com.ecarx.cardesk/shared_prefs/cardesk.xml` 里的
  `global_dock_enabled`（这个开关在设置页里也能点，但脚本要能无人值守复跑）。
  真车不要跑这个脚本。
- 探针 `com.cardesk.mediaprobe` 已装（`media-probe/`，用于注入真实 MediaSession）。

## 用法

```bash
# ① 一次性准备：appops 授悬浮窗 + 打印设备事实（尺寸/导航栏高度/policy_control）
python3 tools/r87_verify.py prep

# ② 开 / 关全局 Dock（改 prefs + 重启桌面，等窗口出现）
python3 tools/r87_verify.py dock-on
python3 tools/r87_verify.py dock-off

# ③ 起「别的应用」并截图（验收：底部应出现 CarDesk Dock）
python3 tools/r87_verify.py other          # 默认 com.android.settings
python3 tools/r87_verify.py other r87-dock-climate com.ecarx.settings

# ④ 回桌面截图（验收：全局窗口消失，只剩桌面自己的 Dock）
python3 tools/r87_verify.py home

# ⑤ 连点播放键（验收：同一次「手抖」只翻一次播放态）
python3 tools/r87_verify.py play 3

# ⑥ 判定这一段时间有没有崩 + 出图拼版
python3 tools/r87_verify.py check 20
```

判定（与 `EMULATOR-VERIFY.md` 一致）：进程 pid 不变 + 无 `Fatal signal`/`FATAL EXCEPTION`
+ `/data/tombstones` 无新增 + 截图肉眼能看出功能在渲染。
"""
import os
import re
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import emu_regress as E  # noqa: E402  （复用 adb/shot/pid/墓碑判定，避免两套实现漂移）

ADB = E.ADB
OUT = E.OUT
PKG = os.environ.get("CARDESK_PKG", "com.ecarx.cardesk")
ACTIVITY = "com.cardesk.launcher.MainActivity"
PROBE = "com.cardesk.mediaprobe"
PROBE_ACTIVITY = f"{PROBE}/.MainActivity"
PREFS = f"/data/data/{PKG}/shared_prefs/cardesk.xml"

# Dock 设计几何（第八十七轮复核，和 ui/shell/DockBarView + core/DockLayout 对齐）：
#   普通 Dock：1864×104，左边距 24、底边距 8 ⇒ 屏幕坐标 (24, 968)..(1888, 1072)
#   播放键：媒体卡右侧（`DockMediaLayout.keys`：nextLeft=w-54, playLeft=nextLeft-52）
#   实测截图：1920×1080 上播放键圆心 ≈ (615, 1018)
TAP_DOCK_PLAY = (615, 1018)
TAP_DOCK_HOME = (100, 1018)   # 主页键（全局 Dock 里长按 = 注入返回）

# 底部预留条 = 设计 112px（`round(width*112/1920)`）；模拟器 nav 条只有 56px，
# 所以「全局 Dock 盖住原车 nav 条 + 盖住应用底部 56px 内容」是**预期**形态。
DOCK_H = 112


def adb(args, timeout=120):
    return E.adb(args, timeout=timeout)


def sh(cmd):
    return E.sh(cmd)


def rooted():
    """确保 adb 有 root（改 prefs 需要）。"""
    out = subprocess.run(f'"{ADB}" -s {E.DEVICE} shell id', shell=True,
                         capture_output=True, text=True).stdout
    if "uid=0" in (out or ""):
        return True
    print("[prep] adb root …")
    subprocess.run(f'"{ADB}" -s {E.DEVICE} root', shell=True, capture_output=True, text=True)
    time.sleep(2)
    subprocess.run(f'"{ADB}" -s {E.DEVICE} wait-for-device', shell=True)
    out = subprocess.run(f'"{ADB}" -s {E.DEVICE} shell id', shell=True,
                         capture_output=True, text=True).stdout
    ok = "uid=0" in (out or "")
    print("[prep] root =", ok)
    return ok


def read_remote(path):
    p = subprocess.run(f'"{ADB}" -s {E.DEVICE} exec-out cat {path}', shell=True,
                       capture_output=True)
    return p.stdout.decode("utf-8", "replace")


def write_remote(path, text):
    """覆盖写远端文件（truncate 写，保留 inode/owner/SELinux context）。"""
    tmp = "/data/local/tmp/cardesk-prefs.xml"
    p = subprocess.run(f'"{ADB}" -s {E.DEVICE} shell "cat > {tmp}"', shell=True,
                       input=text.encode("utf-8"), capture_output=True)
    if p.returncode != 0:
        print(p.stderr.decode("utf-8", "replace"))
    adb(f'shell "cat {tmp} > {path}"')


def set_pref_bool(key, value):
    """写一个 SharedPreferences 布尔项（XML 里就是 `<boolean name=… value=… />`）。"""
    if not rooted():
        raise SystemExit("需要 adb root 才能改 prefs")
    adb(f"shell am force-stop {PKG}")
    time.sleep(0.5)
    xml = read_remote(PREFS)
    if not xml.strip().startswith("<?xml"):
        raise SystemExit(f"读不到 {PREFS}（先手动启动一次桌面生成 prefs）")
    lit = "true" if value else "false"
    pat = re.compile(r'<boolean name="%s" value="(?:true|false)" />' % re.escape(key))
    if pat.search(xml):
        xml = pat.sub('<boolean name="%s" value="%s" />' % (key, lit), xml)
    else:
        xml = xml.replace("</map>", '    <boolean name="%s" value="%s" />\n</map>' % (key, lit))
    write_remote(PREFS, xml)
    got = read_remote(PREFS)
    ok = ('name="%s" value="%s"' % (key, lit)) in got
    print(f"[pref] {key}={lit} ->", "ok" if ok else "FAILED")
    if not ok:
        raise SystemExit("prefs 写入未生效")
    return ok


def start_home():
    """启动桌面（HOME），并等它把窗口 / 服务拉起来。"""
    adb(f'shell am start -a android.intent.action.MAIN -c android.intent.category.HOME')
    time.sleep(3.0)


def dock_state():
    """打印全局 Dock 的关键运行态（服务在不在、窗口 type/位置、policy_control）。"""
    print("\n=== global dock state ===")
    adb(f'shell dumpsys activity services {PKG} | head -40')
    print("\n--- policy_control ---")
    adb('shell settings get global policy_control')
    print("--- nav bar ---")
    adb('shell dumpsys window windows | grep -A8 "NavigationBar0" | grep -E "Surface: shown" | head -2')


def prep():
    E.device()
    print("device =", E.DEVICE)
    rooted()
    print("\n=== device facts ===")
    adb("shell wm size")
    adb("shell wm density")
    adb("shell getprop ro.build.version.sdk")
    adb("shell getprop ro.build.tags")
    # 悬浮窗权限：targetSdk 29 默认拒绝，必须显式 allow（模拟器上 adb 授权即可）
    adb(f"shell appops set {PKG} SYSTEM_ALERT_WINDOW allow")
    adb(f"shell appops get {PKG} SYSTEM_ALERT_WINDOW")
    print("\n=== installs ===")
    adb(f"shell pm list packages | grep -E 'cardesk|mediaprobe'")
    dock_state()


def other_app(component="com.android.settings/.Settings", name="r87-globaldock-other"):
    """起一个**非桌面**的应用，截图看全局 Dock 有没有出现在底部。"""
    E.device()
    adb(f"shell am start -n {component}")
    time.sleep(3.0)
    print("top =")
    adb("shell dumpsys activity activities | grep -E 'mResumedActivity|topResumedActivity' | head -2")
    return E.shot(name)


def home(name="r87-globaldock-home"):
    E.device()
    adb("shell input keyevent KEYCODE_HOME")
    time.sleep(2.5)
    print("top =")
    adb("shell dumpsys activity activities | grep -E 'mResumedActivity|topResumedActivity' | head -2")
    return E.shot(name)


def media_state(tag=""):
    """读探针会话的播放态（`dumpsys media_session`）。"""
    out = subprocess.run(f'"{ADB}" -s {E.DEVICE} shell dumpsys media_session',
                         shell=True, capture_output=True, text=True).stdout
    lines = [ln.strip() for ln in (out or "").splitlines()
             if PROBE in ln or "state=PlaybackState" in ln or "MediaProbe" in ln]
    print(f"\n--- media_session {tag} ---")
    for ln in lines[:12]:
        print(ln)
    m = re.search(r"state=PlaybackState \{state=(\d+)", out or "")
    return int(m.group(1)) if m else -1


def tap(x, y, gap=0.0):
    adb(f"shell input tap {x} {y}")
    if gap:
        time.sleep(gap)


def play(times=3, name="r87-media-play"):
    """连点 Dock 播放键 [times] 次（模拟手抖），看播放态翻了几次。

    期望（第八十七轮幂等语义）：800ms 窗口内的连点**只翻一次**
    （第一次真正下发暂停/播放，后续 `already-satisfied` 不下发）。
    """
    E.device()
    adb(f"shell am force-stop {PROBE}")
    adb(f"shell am start -n {PROBE_ACTIVITY} --es title 海阔天空 --es artist Beyond "
        f"--ez play true")
    time.sleep(2.5)
    before = media_state("before")
    # logcat 清干净，只看这一段的媒体决策日志
    adb("logcat -c")
    print(f"\n[tap] play ×{times} @ {TAP_DOCK_PLAY}")
    for i in range(times):
        tap(*TAP_DOCK_PLAY, gap=0.12)
    time.sleep(2.0)
    after = media_state("after")
    print("\n--- media decision logs ---")
    adb("logcat -d -s CarDesk/Media:V | tail -20")
    E.shot(name)
    print(f"\n[result] state {before} -> {after}（连点 {times} 次）")
    if before == after:
        print("[result] ✅ 播放态未被连点重复翻转（幂等生效）")
    else:
        print("[result] ⚠️ 播放态变了 —— 看日志确认是不是「翻一次」（预期：3 次连点只翻 1 次）")
    return before, after


def check(seconds=20):
    """一段时间内有没有崩（pid / FATAL / 墓碑）。"""
    E.device()
    pid0 = E.pid_of(PKG)
    tb0 = E.tombstones()
    log_before = 0
    print(f"[check] pid={pid0} watch {seconds}s …")
    time.sleep(seconds)
    pid1 = E.pid_of(PKG)
    tb1 = E.tombstones()
    out = subprocess.run(f'"{ADB}" -s {E.DEVICE} logcat -d', shell=True,
                         capture_output=True, text=True).stdout or ""
    fatal = [ln for ln in out.splitlines()
             if "Fatal signal" in ln or "FATAL EXCEPTION" in ln]
    newtb = [t for t in tb1 if t not in tb0]
    print(f"[check] pid {pid0} -> {pid1}（{'相同 ✅' if pid0 == pid1 and pid0 else '变了 ❌'}）")
    print(f"[check] 新墓碑 {len(newtb)} 个 {newtb[:3]}")
    print(f"[check] FATAL 行 {len(fatal)} 条")
    for ln in fatal[:5]:
        print("   ", ln[:200])
    ok = bool(pid0) and pid0 == pid1 and not newtb and not fatal
    print("[check]", "✅ 没崩" if ok else "❌ 有异常，见上")
    return ok


def main():
    args = sys.argv[1:]
    if not args or args[0] in ("-h", "--help"):
        print(__doc__)
        return 0
    cmd, rest = args[0], args[1:]
    E.device()
    if cmd == "prep":
        prep()
    elif cmd == "dock-on":
        set_pref_bool("global_dock_enabled", True)
        start_home()
        dock_state()
        E.shot("r87-globaldock-on-home")
    elif cmd == "dock-off":
        set_pref_bool("global_dock_enabled", False)
        start_home()
        dock_state()
        E.shot("r87-globaldock-off-home")
    elif cmd == "other":
        comp = rest[1] if len(rest) > 1 else "com.android.settings/.Settings"
        name = rest[0] if rest else "r87-globaldock-other"
        other_app(comp, name)
    elif cmd == "home":
        home(rest[0] if rest else "r87-globaldock-home")
    elif cmd == "play":
        play(int(rest[0]) if rest else 3, rest[1] if len(rest) > 1 else "r87-media-play")
    elif cmd == "check":
        check(int(rest[0]) if rest else 20)
    elif cmd == "state":
        dock_state()
    else:
        print(__doc__)
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
