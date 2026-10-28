#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
生成「源码对齐索引」文档。

在把源码注释里的「参考」措辞清掉**之前**跑这个脚本，
把每一条被引用的口径（类 / 方法 / 行号 + 它在注释里说明的行为）
汇总成一份 markdown，避免注释删掉后知识一起丢了。

用法：
    python3 tools/build_alignment_doc.py --extract /tmp/ref_cites.json \
        --out ../analysis/findings/08-alignment-index.md
"""
import os
import re
import json
import collections

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

RE_CITE = re.compile(
    r"(?:对齐|照抄|抄|沿用|对应|来自)?\s*参考\s*"
    r"(?:(?:[`（(][^`）)]*[`）)])|(?:[A-Za-z_][A-Za-z0-9_$]*(?:\.[A-Za-z0-9_$]+)*(?::[\d\-、,:\s]*)?(?:\s*[（(]:[^）)]*[）)])?))"
)
# 说明句里带「差异 / 例外」的关键词 —— 这些是**我们自己偏离**原实现的地方
DIFF_KW = re.compile(
    r"(不一样|不同|而不是|例外|增强|没实现|未实现|没有这条|额外|多出|改成|去掉|删掉|不再|"
    r"暂未|补充|补齐|本轮|按用户要求|简化|覆盖|只是|仅|超出|不受限)"
)


def clean_context(line: str, ref: str) -> str:
    """把注释行里的「参考 …」去掉，留下说明本身。"""
    s = line.strip()
    s = s.lstrip("/").lstrip("*").lstrip(" ").strip()
    if s.endswith("*/"):
        s = s[:-2].strip()
    s = s.replace("参考 " + ref, "").replace("参考" + ref, "")
    s = re.sub(r"^\s*(对齐|照抄|抄|沿用|对应|来自)\s*", "", s)
    s = re.sub(r"^[（(]\s*[）)]", "", s)
    s = re.sub(r"^[：:，,。.、\s]+", "", s)
    s = re.sub(r"[（(]\s*[）)]", "", s)
    s = re.sub(r"\s{2,}", " ", s)
    return s.strip("：:，,、 ")


def main():
    args = sys.argv[1:] if (sys := __import__("sys")) else []
    extract = args[args.index("--extract") + 1]
    out = args[args.index("--out") + 1]

    items = json.load(open(extract, encoding="utf-8"))

    by_our_file = collections.defaultdict(list)
    by_target = collections.defaultdict(list)
    diffs = []
    for it in items:
        ref = it["ref"]
        tgt = re.sub(r"^[（(]?\s*(对齐|照抄|抄|沿用|对应|来自)?\s*参考\s*", "", ref)
        tgt = tgt.strip("`（）() ")
        if tgt in ("App", "APK", "app"):
            continue  # 「参考 App」这种泛指没有信息量
        ctx = clean_context(it["context"], ref)
        by_our_file[it["file"]].append((tgt, ctx, it["line"]))
        by_target[tgt].append((it["file"], it["line"], ctx))
        if DIFF_KW.search(it["context"]) and ctx:
            diffs.append((it["file"], it["line"], tgt, ctx))

    L = []
    A = L.append
    A("# 源码对齐索引")
    A("")
    A("> **这份文档为什么存在**")
    A(">")
    A("> 第六十二轮起，`cardesk/app/src` 的**注释里不再出现任何参考 app 的字样**——")
    A("> 代码里只留「我们自己的实现是什么」，")
    A("> 「这段行为对齐到哪一条口径」全部集中到这一份文档里。")
    A(">")
    A("> 查法：")
    A("> - 想知道**某个行为为什么是这个值** → 翻 §二「按我方文件」；")
    A("> - 想知道**某个原实现位置被用在哪** → 翻 §三「按原实现位置」；")
    A("> - 想知道**我们跟原实现不一样的地方** → 翻 §四「口径差异清单」。")
    A("")
    A("> 本文件是**内部开发文档**，不参与打包，不进 APK。")
    A("")
    A("## 一、统计")
    A("")
    A("| | 数量 |")
    A("| --- | --- |")
    A(f"| 清理掉的「参考」措辞（main + test + manifest） | 2385 处 |")
    A(f"| 记录在案的原实现引用 | {len(items)} 条 |")
    A(f"| 去重后的原实现位置 | {len(by_target)} 个 |")
    A(f"| 涉及的我方源文件 | {len(by_our_file)} 个 |")
    A(f"| 识别出的口径差异 | {len(diffs)} 处 |")
    A("")

    A("## 二、按我方文件")
    A("")
    A("| 我方文件 | 原实现位置 | 原注释里的说明 |")
    A("| --- | --- | --- |")
    for f in sorted(by_our_file):
        seen = set()
        rows = []
        for tgt, ctx, ln in sorted(by_our_file[f], key=lambda x: x[2]):
            key = (tgt, ctx)
            if key in seen:
                continue
            seen.add(key)
            rows.append((tgt, ctx))
        for tgt, ctx in rows:
            c = ctx.replace("|", "\\|")
            A(f"| `{f}` | `{tgt}` | {c} |")
    A("")

    A("## 三、按原实现位置（反向索引）")
    A("")
    A("| 原实现位置 | 次数 | 用在我方哪些文件 |")
    A("| --- | --- | --- |")
    for tgt in sorted(by_target, key=lambda k: -len(by_target[k])):
        files = sorted({f for f, _, _ in by_target[tgt]})
        fl = "、".join("`" + f.split("/")[-1] + "`" for f in files[:6])
        if len(files) > 6:
            fl += f" 等 {len(files)} 个"
        A(f"| `{tgt}` | {len(by_target[tgt])} | {fl} |")
    A("")

    A("## 四、口径差异清单")
    A("")
    A("下面这些是**我们自己刻意偏离原实现**的地方（原注释里明确写了差异），")
    A("改动代码时这些点要格外小心。")
    A("")
    A("| 我方文件:行 | 原实现位置 | 差异说明 |")
    A("| --- | --- | --- |")
    seen = set()
    for f, ln, tgt, ctx in diffs:
        key = (f, ln)
        if key in seen:
            continue
        seen.add(key)
        c = ctx.replace("|", "\\|")
        A(f"| `{f}:{ln}` | `{tgt}` | {c} |")
    A("")

    path = out if os.path.isabs(out) else os.path.join(ROOT, out)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        f.write("\n".join(L) + "\n")
    print(f"已写出 {path}")
    print(f"  我方文件 {len(by_our_file)} / 原实现位置 {len(by_target)} / 差异 {len(diffs)}")


if __name__ == "__main__":
    main()
