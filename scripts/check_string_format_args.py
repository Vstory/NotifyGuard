#!/usr/bin/env python3
# TEMPLATE_VERSION=1.0.0    # 基于模板的版本（合并模板更新后升到模板版；勿删）
# SCRIPT_VERSION=1.0.0      # 项目侧迭代（复制后随定制改动 bump，初始=模板版）
# =============================================================
# check_string_format_args.py — 字符串资源格式化参数核对（Android / Gradle 工程通用）
#
# 定位：项目脚本副本，内容与母版一致（本项目零定制，UiText.Res 由 CI 命令行 --call 传入）。
#       母版在知识库 dev-guide/工程模板/check_string_format_args.py。
#       同步：对比两处 TEMPLATE_VERSION，母版高就整体覆盖、并把 TEMPLATE_VERSION 升到母版版、
#       SCRIPT_VERSION +1（项目定制段若有，合并时保留）。
#
# 补的是哪一层盲区（这道检查为什么必须存在）：
#   · 编译期：stringResource(id, vararg formatArgs: Any) 与 getString(id, Object...) 的实参
#     是 Any / Object —— 类型不检查、个数不检查；
#   · 单测：没引 Robolectric 的工程里没有 Android 资源表，Resources.getString 的格式化
#     根本不在测试范围内；
#   · 产物门禁：判据通常只到「资源在不在」，不涉及调用点的实参。
#   ⇒ 「模板 %1$d ↔ 实参给了 String」这类错配会一路全绿，只在真机点进那一屏时抛
#     IllegalFormatConversionException（`String.format` 的报错文案是 d != java.lang.String）。
#     少传抛 MissingFormatArgumentException，多传则让相邻位置错位、不报错。
#
# 核对三件事：
#   ① 同一个键在各语言包（values / values-zh-rCN …）里的占位符序列必须一致
#      —— 默认包写 %1$d、中文包写 %1$s 只在中文界面炸，最难发现；
#   ② 调用实参个数 == 模板占位符个数（取默认包的模板为准）；
#   ③ 数字格式符（%d %f %x %c …）位置上不许是明显返回 String 的实参
#      （字符串字面量 / 取资源的调用 / xxx.text()）。
#
# 用法：
#   python3 check_string_format_args.py [项目根]
#   python3 check_string_format_args.py --src app/src/main/java --res app/src/main/res .
# 默认（给了项目根时）：扫 <根>/app/src/main/{java,kotlin} 下的 *.kt / *.java，
#   读 <根>/app/src/main/res/values*/strings.xml。
# 退出码：有错 1（并打印 GitHub Actions 注解 ::error::），全绿 0。
#
# 识别范围：只认「取资源函数(R.string.字面量键, 实参…)」——
#   stringResource(R.string.k, a, b) · getString(R.string.k, a, b) ·
#   getQuantityString(...) · 项目自己的包装函数（用 --call 追加，如 --call UiText.Res）。
#   ⚠️ 不能按「任意函数名(R.string.k」识别：枚举项的构造器长一个样
#   （`enum class Item(@StringRes val labelRes: Int) { Call(R.string.protect_call, R.string.protect_call_note) }`），
#   放宽后每个枚举项都会被当成一处调用点，报一串假错 —— 判据必须落在函数名上。
# 参数载体：listOf(...) / Arrays.asList(...) / arrayOf(...) / List.of(...) 展开成逐个实参。
# 静态判不了的跳过（键不是字面量、实参带 * 展开、键不在资源表里），跳过数量会打印出来
#   —— 别把「跳过的多」读成「查得全」。
# =============================================================
import argparse
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

# (?<!%) 让 `%%`（字面百分号）不算占位符
SPEC = re.compile(r"(?<!%)%(?:(\d+)\$)?([dsfboxXeEgGc])")
# %s 吃任意对象，%b/%h 也吃任意对象，故这两类不做 String 检查
NUMERIC = set("dxXoeEfgGc")
DEFAULT_CALLS = ("stringResource", "getString", "getText", "getQuantityString")
LIST_WRAPPERS = ("listOf(", "arrayOf(", "Arrays.asList(", "List.of(", "Arrays.of(")
STRINGY = ('"', "stringResource(", "getString(", "getText(", "getQuantityString(", ".text()")


def call_re(names):
    """函数名白名单 + (R.string.字面量键 —— 前缀允许 `context.` 这类限定。"""
    alts = "|".join(re.escape(n) for n in names)
    return re.compile(rf"(?:[A-Za-z_][\w.]*\.)?(?:{alts})\(\s*(?:[A-Za-z_][\w.]*\.)?R\.string\.(\w+)")


def specs_of(text):
    """占位符 (位置, 类型) 序列；无下标的 %s 按出现次序占位。"""
    return sorted(
        (int(m.group(1)) if m.group(1) else i, m.group(2))
        for i, m in enumerate(SPEC.finditer(text), start=1)
    )


def load_strings(res_roots):
    """键 → {包名: 值}；包名取 values 目录名（values / values-zh-rCN …）。"""
    out = {}
    for root in res_roots:
        files = [root] if root.suffix == ".xml" else sorted(root.glob("values*/strings.xml"))
        for f in files:
            pkg = f.parent.name
            for s in ET.parse(f).getroot().iter("string"):
                out.setdefault(s.get("name"), {})[pkg] = "".join(s.itertext())
    return out


def match_paren(src, open_idx):
    depth, instr, i = 0, False, open_idx
    while i < len(src):
        c = src[i]
        if instr:
            if c == "\\":
                i += 2
                continue
            if c == '"':
                instr = False
        elif c == '"':
            instr = True
        elif c == "(":
            depth += 1
        elif c == ")":
            depth -= 1
            if depth == 0:
                return i
        i += 1
    return -1


def split_args(s):
    """按顶层逗号切实参（忽略括号与字符串内的逗号）。"""
    args, depth, cur, instr, i = [], 0, "", False, 0
    while i < len(s):
        ch = s[i]
        if instr:
            if ch == "\\":
                cur += s[i : i + 2]
                i += 2
                continue
            cur += ch
            if ch == '"':
                instr = False
        elif ch == '"':
            instr = True
            cur += ch
        elif ch in "([{":
            depth += 1
            cur += ch
        elif ch in ")]}":
            depth -= 1
            cur += ch
        elif ch == "," and depth == 0:
            args.append(cur.strip())
            cur = ""
        else:
            cur += ch
        i += 1
    if cur.strip():
        args.append(cur.strip())
    return [a for a in args if a]


def unwrap_list(args):
    """listOf(...) / Arrays.asList(...) 是「实参载体」，展开成逐个实参。"""
    if len(args) == 1:
        for w in LIST_WRAPPERS:
            if args[0].startswith(w) and args[0].endswith(")"):
                return split_args(args[0][len(w) : -1])
    return args


def is_stringy(a):
    return a.startswith(STRINGY)


def check_strings(strings, bad):
    for key, by_pkg in sorted(strings.items()):
        seqs = {pkg: tuple(specs_of(v)) for pkg, v in by_pkg.items()}
        if len(set(seqs.values())) > 1:
            shown = {pkg: [f"%{i}${t}" if i else f"%{t}" for i, t in s] for pkg, s in seqs.items()}
            bad.append(f"{key}: 各语言包的占位符不一致 {shown}")


def check_calls(src_roots, strings, bad, rx):
    checked = skipped = 0
    for root in src_roots:
        files = sorted(list(root.rglob("*.kt")) + list(root.rglob("*.java")))
        for f in files:
            src = f.read_text(encoding="utf-8")
            for m in rx.finditer(src):
                key = m.group(1)
                popen = src.index("(", m.start())
                pclose = match_paren(src, popen)
                if pclose < 0:
                    skipped += 1
                    continue
                args = split_args(src[popen + 1 : pclose])
                if args and re.fullmatch(r"[\w.]*R\.string\.\w+", args[0]):
                    args = args[1:]
                if any(a.startswith("*") for a in args):
                    skipped += 1
                    continue
                args = unwrap_list(args)
                by_pkg = strings.get(key)
                if by_pkg is None:
                    skipped += 1
                    continue
                checked += 1
                sp = specs_of(by_pkg.get("values", next(iter(by_pkg.values()))))
                line = src[: m.start()].count("\n") + 1
                where = f"{f.relative_to(Path.cwd()) if f.is_relative_to(Path.cwd()) else f}:{line}  {key}"
                types = [f"%{i}${t}" if i else f"%{t}" for i, t in sp]
                if sp and not args:
                    bad.append(
                        f"{where}\n    模板要 {len(sp)} 个参数 {types}，调用没传 —— 界面会原样显示这些 %"
                    )
                    continue
                if len(args) != len(sp):
                    bad.append(
                        f"{where}\n    模板要 {len(sp)} 个参数 {types}，调用传了 {len(args)} 个 {args}"
                    )
                    continue
                for (idx, kind), a in zip(sp, args):
                    if kind in NUMERIC and is_stringy(a):
                        bad.append(
                            f"{where}\n    第 {idx} 位是 %{idx}${kind}，实参却是 {a[:60]}"
                            " —— String 给数字格式符会在真机抛 IllegalFormatConversionException"
                        )
    return checked, skipped


def main():
    ap = argparse.ArgumentParser(description="核对字符串资源的格式化参数（模板 ↔ 调用 ↔ 各语言包）")
    ap.add_argument("root", nargs="?", default=".", help="项目根，默认当前目录")
    ap.add_argument("--src", action="append", default=[], help="源码根，可多次；默认 <根>/app/src/main/{java,kotlin}")
    ap.add_argument("--res", action="append", default=[], help="资源根，可多次；默认 <根>/app/src/main/res")
    ap.add_argument("--call", action="append", default=[], help="追加取资源的函数名（如 --call UiText.Res），可多次")
    a = ap.parse_args()

    root = Path(a.root).resolve()
    src_roots = [Path(p) for p in a.src] or [
        root / "app/src/main/java",
        root / "app/src/main/kotlin",
    ]
    res_roots = [Path(p) for p in a.res] or [root / "app/src/main/res"]
    src_roots = [p for p in src_roots if p.is_dir()]
    res_roots = [p for p in res_roots if p.exists()]
    if not src_roots or not res_roots:
        print(f"::error::没找到源码根或资源根（源码 {src_roots} / 资源 {res_roots}）—— 用 --src / --res 指定")
        return 1

    strings = load_strings(res_roots)
    if not strings:
        print(f"::error::{res_roots} 下没读到任何 strings.xml")
        return 1

    bad = []
    check_strings(strings, bad)
    checked, skipped = check_calls(src_roots, strings, bad, call_re(tuple(DEFAULT_CALLS) + tuple(a.call)))

    if bad:
        print(f"::error::字符串格式参数错配 {len(bad)} 处")
        print("\n".join(bad))
        return 1
    print(f"  ✅ 字符串格式参数一致（核对 {checked} 处调用 · {len(strings)} 个键 · 跳过 {skipped} 处静态判不了的）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
