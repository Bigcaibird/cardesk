#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
把 Kotlin / XML 注释里「提到参考 app」的措辞与引用清掉。

设计原则：
  1. **只动注释**：代码字符串一个字都不碰。
  2. **逐行处理**：只删掉「引用」那一小段，不重排整段注释的空白 ——
     KDoc 的缩进、列表符号、代码块都原样保留。
  3. **只做删除 / 等价替换**：所有规则都是「删掉一段引用」或「换个中性说法」。
  4. **先抽后删**：删除前先用 `--extract` 把每条引用导出成结构化数据，
     用来生成 `analysis/findings/08-alignment-index.md`，避免知识一起丢掉。
  5. **行结构绝对不动**：行尾换行先摘出来、处理完原样接回去；所有空白量词
     用 [ \\t] 而不是 \\s —— 否则会把换行吃掉、把相邻两行拼成一行。

归属判定（决定一个反引号里的标识符该删还是该留）：
  * `OURS`  = 我方**非注释代码**里出现过的 token（类名 / 函数名 / 常量 / 资源名 / 包名）。
  * `ORIG`  = 从「带行号 / 带 .java|.xml」的引用里反推出的**原实现类名**。
  * 判定顺序：带行号或文件名 → 删；原实现的包 → 删；`A.B` 且 A 属于原实现 → 删；
    我方 token → 留；其余 → 删。

用法：
    python3 tools/strip_ref_comments.py --dry-run            # 只报告，不写文件
    python3 tools/strip_ref_comments.py --apply              # 真正改写
    python3 tools/strip_ref_comments.py --extract out.json   # 只导出引用清单
"""
import os
import re
import sys
import json
import collections

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "app", "src")
MAIN = os.path.join(SRC, "main")

# 原实现 app 的包名 —— 永远不能出现在我方注释里
ORIG_PKGS = ("com.dudu.autoui", "com.claunchercard")

# 平台 / 厂商 SDK / 公共库的包名前缀 —— 这些是**公共知识**，不是原实现专有，保留
PLATFORM_PKGS = (
    "android.", "androidx.", "com.android.", "com.bumptech.", "com.google.",
    "com.tencent.", "com.qq.", "com.baidu.", "com.amap.", "com.alibaba.",
    "com.autonavi.", "com.ecarx.", "com.sun.", "com.cardesk.", "com.huawei.",
    "org.", "java.", "javax.", "sun.", "net.", "dalvik.", "kotlinx.",
    "vendor.", "car.", "ecarx.", "local.", "data.", "this.",
)

# 通用 / 公开 API，即使我方代码里没直接写也保留
GENERIC = {
    "JSONArray", "JSONObject", "null", "true", "false", "this", "super",
    "TextView", "Math", "Integer", "Long", "Float", "Double", "String",
    "Boolean", "Byte", "Short", "Char",
    "View", "ViewGroup", "Context", "Intent", "Bundle", "Handler", "Looper",
    "Window", "WindowManager", "Activity", "PendingIntent", "Animation",
    "ObjectAnimator", "ValueAnimator", "PathInterpolator", "LinearInterpolator",
    "ComposeView", "SurfaceView", "TextureView", "Bitmap", "Canvas", "Paint",
    "Rect", "RectF", "Color", "Drawable", "Display", "AccessibilityNodeInfo",
    "MediaSession", "MediaMetadata", "MediaController", "Notification",
    "NotificationManager", "NotificationChannel", "AudioManager", "MediaPlayer",
    "PackageManager", "LauncherApps", "TextUtils", "AppOpsManager",
    "LocationManager", "LocationManagerService", "MediaSessionService",
    "PermissionManager", "ISensor", "IConnectable",
    "MODE_IGNORED", "MODE_ALLOWED", "MODE_FOREGROUND", "OP_FINE_LOCATION",
    "SET_APPOP", "checkSelfPermission", "enforceMediaPermissions",
    "WRITE_SECURE_SETTINGS", "CHANGE_COMPONENT_ENABLED_STATE", "MANAGE_USB",
    "PLATFORM.RSA",
    "setOnLongClickListener", "setOnClickListener", "compareAndSet",
    "getLastKnownLocation", "PendingIntent.getActivity",
    "super.onDraw", "match_parent", "wrap_content",
}

# 自有资源 / 文件名，**不能**当引用删掉
KEEP = ("AndroidManifest", "system-patch", "strings.xml", "themes.xml", "ic_cardesk")

# 我方历史上存在、后来删掉的类名 —— 属于「我们自己的历史」，不是原实现
OURS_LEGACY = {"HudLink", "HudScanDialog"}

TOKEN = re.compile(r"[A-Za-z_][A-Za-z0-9_$]*(?:\.[A-Za-z0-9_$]+)*")

# --------------------------------------------------------------------------
# ① 我方 / 原实现 token 集合
# --------------------------------------------------------------------------


def strip_kt_comments(src: str) -> str:
    """去掉 Kotlin 注释，只留代码（用于采集我方 token）。"""
    out = []
    i = 0
    n = len(src)
    in_str = None
    while i < n:
        c = src[i]
        if in_str:
            out.append(c)
            if c == chr(92) and i + 1 < n:
                out.append(src[i + 1])
                i += 2
                continue
            if c == in_str:
                in_str = None
            i += 1
            continue
        if c in "\"'":
            in_str = c
            out.append(c)
            i += 1
            continue
        if src.startswith("//", i):
            j = src.find("\n", i)
            if j < 0:
                break
            i = j
            continue
        if src.startswith("/*", i):
            j = src.find("*/", i + 2)
            if j < 0:
                break
            i = j + 2
            out.append(" ")
            continue
        out.append(c)
        i += 1
    return "".join(out)


def strip_xml_comments(src: str) -> str:
    return re.sub(r"<!--.*?-->", " ", src, flags=re.S)


def load_ours():
    """采集我方非注释代码里的全部 token。"""
    ours = set()
    for root, _, files in os.walk(MAIN):
        for fn in files:
            p = os.path.join(root, fn)
            if fn.endswith(".kt"):
                src = strip_kt_comments(open(p, encoding="utf-8").read())
            elif fn.endswith(".xml"):
                src = strip_xml_comments(open(p, encoding="utf-8").read())
            else:
                continue
            for m in TOKEN.finditer(src):
                ours.add(m.group(0))
            for m in re.finditer(r"[A-Za-z_][A-Za-z0-9_$]*", src):
                ours.add(m.group(0))
    return ours


REFS_JSON = os.path.join(ROOT, "tools", "ref_cites.json")


def _heads_from_ref(ref: str):
    """从一条引用里取出「原实现的类名」。"""
    out = set()
    for m in re.finditer(r"`([^`]+)`", ref):
        head = m.group(1).split(":")[0].split(".")[0].split("(")[0].strip()
        if re.fullmatch(r"[A-Z][A-Za-z0-9_$]*", head):
            out.add(head)
    for m in re.finditer(r"\b([A-Z][A-Za-z0-9_$]*)(?:\.[A-Za-z0-9_$]+)+", ref):
        out.add(m.group(1))
    for m in re.finditer(r"\b([A-Z][A-Za-z0-9_$]*)\.(?:java|xml)\b", ref):
        out.add(m.group(1))
    return out


def load_orig():
    """反推**原实现**的类名表。

    工具本身不幂等：跑完一遍源码里就没有「参考 X.java:123」了，
    所以原实现类名表必须从**独立保存的引用清单**（tools/ref_cites.json）里读；
    清单缺失时再退回扫描当前源码。
    """
    orig = set()
    if os.path.exists(REFS_JSON):
        try:
            items = json.load(open(REFS_JSON, encoding="utf-8"))
        except Exception:
            items = []
        for it in items:
            orig |= _heads_from_ref(it.get("ref", ""))
    for root, _, files in os.walk(SRC):
        for fn in files:
            if not fn.endswith((".kt", ".xml")):
                continue
            for raw in open(os.path.join(root, fn), encoding="utf-8"):
                for m in re.finditer(r"`([^`]*)`", raw):
                    c = m.group(1)
                    if not (re.search(r":[0-9]", c) or re.search(r"\.(?:java|xml)", c)):
                        continue
                    head = c.split(":")[0].split(".")[0]
                    if head and re.match(r"^[A-Z]", head):
                        orig.add(head)
                for m in re.finditer(r"\b([A-Z][A-Za-z0-9_$]*)\.(?:java|xml)\b", raw):
                    orig.add(m.group(1))
    return orig


OURS = load_ours()
ORIG = load_orig() - OURS  # 同名的按我方处理


# --------------------------------------------------------------------------
# ② 措辞替换（中性说法）
# --------------------------------------------------------------------------
PHRASE = [
    (re.compile(r"本复刻版"), "本应用"),
    (re.compile(r"复刻版"), "本应用"),
    (re.compile(r"复刻侧"), "我们"),
    (re.compile(r"复刻参考"), "沿用"),
    (re.compile(r"原版桌面"), "车机桌面"),
    (re.compile(r"与参考一致"), "一致"),
    (re.compile(r"与参考相同"), "相同"),
    (re.compile(r"与参考\s*App\s*一致"), "一致"),
    (re.compile(r"与参考\s*App\s*相同"), "相同"),
    (re.compile(r"与参考\s*App\s*的"), "与"),
    (re.compile(r"复刻"), ""),
]

# 删除型关键词：前缀动词 + 「参考」+ 可选限定词 + 可选的「的」
KEYWORD_RE = re.compile(
    r"(?:对齐|照抄|沿用|抄|对应|来自|依照|按照|参照)?[ \t]*"
    r"参考[ \t]*(?:App|app|APP|实现|版本|源码|代码|工程|项目)?[ \t]*的?"
)


# --------------------------------------------------------------------------
# ③ 定位引用识别
# --------------------------------------------------------------------------
RE_LINE = re.compile(r":[0-9]")
RE_FILE = re.compile(r"\.(?:java|xml)")
RE_IDENT = re.compile(r"^[A-Za-z_][A-Za-z0-9_$]*(?:\.[A-Za-z0-9_$]+)*$")
RE_CONST_ASSIGN = re.compile(r"^[A-Z][A-Za-z0-9_$]*(?:\.[A-Za-z0-9_$]+)*[ \t]*=[ \t]*\S+$")
RE_IDENT_CALL = re.compile(
    r"^[A-Za-z_][A-Za-z0-9_$]*(?:\.[A-Za-z0-9_$]+)*(?:\(\))?(?::[0-9][0-9\-]*)?$")
RE_PLAIN_FILE = re.compile(r"\b[A-Za-z_][A-Za-z0-9_$]*\.(?:java|xml)(?::[0-9][0-9\-]*)?")
RE_PLAIN_LINE = re.compile(r"(?<![\w`])(:[0-9][0-9\-]*)")

# 注释里**没加反引号**的原实现标识符链：SceneDesktop.BOTTOM_CARD_IDS / DesignPages:1314
RE_PLAIN_CHAIN = re.compile(
    r"(?<![\w`\-])"
    r"([A-Z][A-Za-z0-9_$]*(?:\.[A-Za-z0-9_$]+)*)"
    r"(?::[0-9][0-9\-]*)?"
    r"(?:[ \t]*[（(][ \t]*:[0-9][0-9\-]*[ \t]*[）)])?"
    r"(?:\(\))?"
)


def split_pad(inner: str):
    """把注释内容拆成 (前导空白, 正文, 尾部空白)。

    全空白行里前导与尾部会**重叠**（比如 "        " 既全是前导也是全是尾部），
    不处理的话每跑一次工具行尾就会多出一截空白。
    """
    n = len(inner)
    lead_len = n - len(inner.lstrip())
    pad_len = n - len(inner.rstrip())
    if lead_len + pad_len > n:
        return "", "", ""
    return inner[:lead_len], inner[lead_len:n - pad_len], inner[n - pad_len:]


def is_locator(content: str) -> bool:
    """反引号内容是否长得像「一个定位引用」。"""
    c = content.strip()
    if not c:
        return False
    if RE_LINE.search(c) or RE_FILE.search(c):
        return True
    if RE_IDENT.match(c):
        return True
    # 「原实现类.常量 = 值」也算定位引用
    if RE_CONST_ASSIGN.match(c):
        return True
    if RE_IDENT_CALL.match(c) and "(" in c:
        return True
    return False


def keep_content(content: str) -> bool:
    """判断一个定位引用该保留（我方符号）还是删除（原实现专有）。"""
    c = content.strip()
    if not c:
        return False
    if any(k in c for k in KEEP):
        return True
    if c in GENERIC or c in OURS_LEGACY:
        return True
    # 原实现的包名 → 必删
    if any(c.startswith(p) for p in ORIG_PKGS):
        return False
    # 带行号 / 带文件名 → 指向原实现的源码位置 → 删
    if RE_LINE.search(c) or RE_FILE.search(c):
        return False
    # 看起来是包路径（首段全小写）→ 平台/公共库保留，原实现包删除
    if "." in c and c.split(".")[0].islower():
        if c.startswith(PLATFORM_PKGS):
            return True
        return c in OURS
    # A.B 形式：A 属于原实现 → 删
    if "." in c:
        head = c.split(".")[0]
        if head in ORIG:
            return False
        return c in OURS or head in OURS
    # 裸标识符
    return c in OURS


# --------------------------------------------------------------------------
# ④ 主处理
# --------------------------------------------------------------------------


def strip_plain_chains(seg: str) -> str:
    """清掉注释里**没加反引号**的原实现标识符链。"""
    def repl(m):
        head = m.group(1).split(".")[0]
        if head in ORIG and head not in OURS:
            return ""
        return m.group(0)
    return RE_PLAIN_CHAIN.sub(repl, seg)


def strip_citations(text: str):
    """删除定位引用（保留我方符号）。

    删除时**顺带吃掉紧邻的分隔符**（、，,/ 等），否则会留下「、」这种残渣；
    但只吃紧邻的，不会误伤正文里的斜杠（数学式、路径）。
    """
    seps = "、，,/＋+"
    out = []
    i = 0
    n = len(text)
    plain = []
    while i < n:
        c = text[i]
        if c == "`":
            j = text.find("`", i + 1)
            if j < 0:
                out.append(strip_plain_chains("".join(plain)))
                plain = []          # 反引号跨行时，plain 只能输出一次
                out.append(text[i:])
                break
            content = text[i + 1:j]
            out.append(strip_plain_chains("".join(plain)))
            plain = []
            if is_locator(content) and not keep_content(content):
                # 向前吃掉分隔符（连同其后的空格）
                k = i
                while k > 0 and text[k - 1] in " \t":
                    k -= 1
                if k > 0 and text[k - 1] in seps:
                    i = k - 1
                else:
                    # 否则向后吃
                    m = j + 1
                    while m < n and text[m] in " \t":
                        m += 1
                    if m < n and text[m] in seps:
                        j = m
                i = j + 1
                continue
            out.append(text[i:j + 1])
            i = j + 1
            continue
        plain.append(c)
        i += 1
    out.append(strip_plain_chains("".join(plain)))
    t = "".join(out)
    # 裸的文件名引用（Xxx.java / Xxx.java:123）
    t = RE_PLAIN_FILE.sub("", t)
    return t


def strip_keywords(text: str):
    """删掉「参考」等措辞及其紧跟的连接词。"""
    t = KEYWORD_RE.sub("", text)
    if t != text:
        # 关键词删掉后可能留下悬空的分隔符 / 「的」
        t = re.sub(r"[：:，,、；;][ \t]*$", "", t)
        t = re.sub(r"[ \t]+的(?=[ \t]*[（(「『`])", "", t)
    t = re.sub(r"[（(][ \t]*的[ \t]*[）)]", "", t)
    t = re.sub(r"[ \t]{2,}", " ", t)
    return t


def tidy(text: str):
    """收拾删除后留下的痕迹。

    所有空白量词都用 [ \\t] 而不是 \\s —— 否则会把行尾换行一起吃掉，
    导致相邻两行被拼成一行。
    """
    t = text
    # 空反引号
    t = re.sub(r"``", "", t)
    # 行首的悬空分隔符 / 悬空的「的」
    t = re.sub(r"^[ \t]*[：:，,。.、→+][ \t]*", "", t)
    t = re.sub(r"^[ \t]*的[ \t]*", "", t)
    # 行首多余的空格（后面紧跟反引号或括号）
    t = re.sub(r"^[ \t]+(?=[`（(「『])", "", t)
    # 括号 / 引号后多余的空格
    t = re.sub(r"[（(「『][ \t]+", lambda m: m.group(0)[0], t)
    # 悬空分隔符（后面是收尾标点）—— 去掉后可能形成空括号
    t = re.sub(r"[ \t]*[、，,：:][ \t]*(?=[）)】」。；;、，,：:])", "", t)
    # 悬空的斜杠（前面不是字母数字，说明是分隔符而不是路径的一部分）
    t = re.sub(r"(?<![A-Za-z0-9])/[ \t]*(?=[）)】」])", "", t)
    # 括号配对：删完后右括号比左括号多，就把多出来的右括号去掉
    if t.count("）") > t.count("（"):
        t = re.sub(r"）", "", t, count=t.count("）") - t.count("（"))
    # 行尾只剩一个左括号（配对的右括号在下一行、且已被删掉）→ 去掉
    t = re.sub(r"[（]\s*$", "", t)
    # 全角空括号（ASCII 的 () 是方法调用，不能动）
    t = re.sub(r"[（][ \t]*[）]", "", t)
    # 箭头后面空了
    t = re.sub(r"[ \t]*→[ \t]*(?=[）)】」（(]|$)", "", t)
    # 标点前多出的空格
    t = re.sub(r"[ \t]+([：:，,。.、；;)）])", r"\1", t)
    # 行尾空格
    t = re.sub(r"[ \t]+$", "", t)
    return t


def strip_content(text: str):
    """清掉一段注释内容里的「参考」措辞与引用，返回 (新内容, 是否改动)。

    入参**不含**换行符。
    """
    orig = text
    t = text
    for rx, rep in PHRASE:
        t = rx.sub(rep, t)
    t = strip_citations(t)
    t = strip_keywords(t)
    t = tidy(t)
    return t, t != orig


def content_meaningful(t: str) -> bool:
    """注释内容里还有没有实质文字（只剩标点就整行删掉）。"""
    return bool(re.search(r"[\w\u4e00-\u9fff]", t))


def strip_comment_line(raw: str):
    """处理一行：返回 (新行 或 None, 是否改动)。

    行尾换行先摘出来，改完再原样接回去 —— 保证绝不动行结构。
    """
    nl = "\n" if raw.endswith("\n") else ""
    body = raw[: len(raw) - len(nl)] if nl else raw
    s = body.lstrip()
    indent = body[: len(body) - len(s)]
    # —— 单行块注释：`/** xxx */` 或 `/* xxx */` ——
    if s.startswith("/*"):
        open_len = 3 if s.startswith("/**") else 2
        end = s.find("*/", open_len)
        inner = s[open_len:end] if end >= 0 else s[open_len:]
        tail = "*/" if end >= 0 else ""
        lead, content, pad = split_pad(inner)
        t, ch = strip_content(content)
        if not ch:
            return raw, False
        new_body = indent + s[:open_len] + lead + t + pad + tail
        new_body = guard_terminator(body, new_body)
        if not content_meaningful(t):
            # 整条注释被清空 → 整行删掉（若这是跨行注释的开头则只留开头符）
            return (indent + s[:open_len] + nl) if not tail else None, True
        return new_body + nl, True
    # —— KDoc / 块注释的行：` * xxx` ——
    if s.startswith("*"):
        inner = s[1:]
        lead, content, _pad = split_pad(inner)
        t, ch = strip_content(content)
        if not ch:
            return raw, False
        new_body = indent + "*" + lead + t
        new_body = guard_terminator(body, new_body)
        if not content_meaningful(t):
            return None, True
        return new_body + nl, True
    # —— 行注释：`// xxx` ——
    if s.startswith("//"):
        inner = s[2:]
        lead, content, _pad = split_pad(inner)
        t, ch = strip_content(content)
        if not ch:
            return raw, False
        head = indent + "//" + lead
        if not content_meaningful(t):
            return (head.rstrip() + nl) if head.strip() else None, True
        return head + t + nl, True
    # —— XML 注释：`<!-- xxx -->` ——
    if s.startswith("<!--"):
        end = s.find("-->")
        inner = s[4:end] if end >= 0 else s[4:]
        tail = "-->" if end >= 0 else ""
        t, ch = strip_content(inner)
        if not ch:
            return raw, False
        new_body = indent + "<!--" + t + tail
        if not content_meaningful(t):
            return (indent + "<!--" + tail + nl) if tail else None, True
        return new_body + nl, True
    # —— 代码行尾的注释：`val x = 1  // 参考 X` ——
    in_str = None
    i = 0
    while i < len(s):
        c = s[i]
        if in_str:
            if c == chr(92):
                i += 2
                continue
            if c == in_str:
                in_str = None
            i += 1
            continue
        if c in "\"'":
            in_str = c
            i += 1
            continue
        if s.startswith("//", i):
            inner = s[i + 2:]
            lead, content, _pad = split_pad(inner)
            t, ch = strip_content(content)
            if not ch:
                return raw, False
            head = indent + s[:i] + "//" + lead
            if not content_meaningful(t):
                return (head.rstrip() + nl) if head.strip() else None, True
            return head + t + nl, True
        i += 1
    return raw, False


def guard_terminator(old: str, new: str) -> str:
    """安全网：删除内容后不要凭空造出「*/」把块注释提前关掉。"""
    if "*/" in old:
        return new
    i = new.find("*/")
    if i < 0:
        return new
    return new[:i] + "* /" + new[i + 2:]


def strip_block_line(body: str):
    """处理块注释内部的一行。

    返回 (新行主体, 是否改动, 本行是否结束块注释)。
    """
    s = body.lstrip()
    indent = body[: len(body) - len(s)]
    # 先按「*/」切分，再剥「*」前缀 —— 否则结束行 `*/` 会被拆成 `*` + `/`
    i = s.find("*/")
    if i >= 0:
        inner_all = s[:i]
        tail = s[i:]
        ends = True
    else:
        inner_all, tail = s, ""
        ends = False
    prefix = ""
    if inner_all.startswith("*"):
        prefix = "*"
        inner_all = inner_all[1:]
    inner = inner_all
    lead, content, pad = split_pad(inner)
    t, ch = strip_content(content)
    new_body = indent + prefix + lead + t + pad + tail
    new_body = guard_terminator(body, new_body)
    return new_body, (ch or new_body != body), ends


def process_file(path: str, apply: bool):
    is_xml = path.endswith(".xml")
    with open(path, encoding="utf-8") as f:
        lines = f.readlines()
    out = []
    changed = False
    in_xml = False
    in_block = False
    for raw in lines:
        nl = "\n" if raw.endswith("\n") else ""
        body = raw[: len(raw) - len(nl)] if nl else raw
        s = body.lstrip()
        indent = body[: len(body) - len(s)]
        # —— Kotlin 多行块注释（非 `*` 前缀的行）——
        if in_block:
            new_body, ch, ends = strip_block_line(body)
            if ch:
                changed = True
            out.append(new_body + nl)
            if ends:
                in_block = False
            continue
        # —— XML 多行注释 ——
        if is_xml and in_xml:
            if "-->" in s:
                i = s.find("-->")
                inner = s[:i]
                tail = s[i:]
                t, ch = strip_content(inner)
                if ch:
                    changed = True
                out.append((indent + t + tail + nl) if content_meaningful(t)
                           else (indent + tail + nl))
                in_xml = False
            else:
                t, ch = strip_content(s)
                if ch:
                    changed = True
                out.append((indent + t + nl) if content_meaningful(t) else (indent + nl))
            continue
        new, ch = strip_comment_line(raw)
        if ch:
            changed = True
        if new is None:
            continue
        out.append(new)
        if is_xml and "<!--" in new and "-->" not in new:
            in_xml = True
        elif not is_xml:
            ss = new.lstrip()
            if ss.startswith("/*") and "*/" not in ss[2:]:
                in_block = True
    if changed and apply:
        with open(path, "w", encoding="utf-8") as f:
            f.writelines(out)
    return changed, out


# --------------------------------------------------------------------------
# ⑤ 抽取（用于生成对齐索引文档）
# --------------------------------------------------------------------------

RE_CITE_CLAUSE = re.compile(
    r"(?:对齐|照抄|抄|沿用|对应|来自)?\s*参考\s*"
    r"(?:(?:[`（(][^`）)]*[`）)])|(?:[A-Za-z_][A-Za-z0-9_$]*(?:\.[A-Za-z0-9_$]+)*(?::[\d\-、,:\s]*)?(?:\s*[（(]:[^）)]*[）)])?))"
)


def collect_citations():
    items = []
    for root, _, files in os.walk(SRC):
        for fn in sorted(files):
            if not fn.endswith((".kt", ".xml")):
                continue
            p = os.path.join(root, fn)
            rel = os.path.relpath(p, ROOT)
            for i, raw in enumerate(open(p, encoding="utf-8"), 1):
                if "参考" not in raw:
                    continue
                for m in RE_CITE_CLAUSE.finditer(raw):
                    ref = m.group(0).strip()
                    if len(ref) < 4:
                        continue
                    items.append({
                        "file": rel,
                        "line": i,
                        "ref": ref,
                        "context": raw.strip(),
                    })
    return items


def main():
    args = sys.argv[1:]
    apply_ = "--apply" in args
    if "--extract" in args:
        out = args[args.index("--extract") + 1]
        items = collect_citations()
        with open(out, "w", encoding="utf-8") as f:
            json.dump(items, f, ensure_ascii=False, indent=1)
        print(f"导出引用条目 {len(items)} 条 → {out}")
        return

    changed_files = []
    residue = collections.Counter()
    residue_samples = {}
    for root, _, files in os.walk(SRC):
        for fn in sorted(files):
            if not fn.endswith((".kt", ".xml")):
                continue
            p = os.path.join(root, fn)
            changed, out = process_file(p, apply_)
            if changed:
                changed_files.append(os.path.relpath(p, ROOT))
            for i, l in enumerate(out, 1):
                if any(k in l for k in ("参考", "复刻", "原版", "原实现", "原 App", "原App", "原桌面")):
                    residue[l.strip()] += 1
                    residue_samples.setdefault(l.strip(), (os.path.relpath(p, ROOT), i))

    print(f"改动文件 {len(changed_files)} 个" + ("（已写入）" if apply_ else "（dry-run）"))
    print()
    print(f"=== 残留措辞 {sum(residue.values())} 条 / {len(residue)} 种 ===")
    for line, c in residue.most_common(400):
        f, i = residue_samples[line]
        print(f"{c:3d}  {f}:{i}")
        print(f"      {line[:170]}")


if __name__ == "__main__":
    main()
