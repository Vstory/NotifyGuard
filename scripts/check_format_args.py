#!/usr/bin/env python3
"""字符串资源的格式化参数核对（源码层，不依赖 APK）。

为什么要这道门禁：`stringResource(id, vararg formatArgs: Any)` 的实参是 `Any`，编译期
不查类型；单测没有 Android 资源表（工程没引 Robolectric）；dex 断言只查「资源在不在」。
于是「模板 %1$d ↔ 实参给了 String」这类错配三层全漏，只在真机点进那一屏时抛
IllegalFormatConversionException 崩掉 —— 出过一次（records_filter_hit）。

核对三件事：
  ① 同一个键在默认包（英文）与各语言包里的占位符序列必须一致
     —— 英文写 %1$d、中文写 %1$s 只在中文界面炸，最难发现
  ② 调用实参个数 == 模板占位符个数
     —— 少传抛 MissingFormatArgumentException；多传让相邻位置错位
  ③ %d 位置上不许是明显返回 String 的实参（字符串字面量 / stringResource(...) / *.text()）

认得两种调用形态：stringResource(R.string.K, a, b) 与 UiText.Res(R.string.K, listOf(a, b))。
键不是字面量（如 stringResource(filter.labelRes)）的调用静态判不了，跳过 —— 那类键都是
无占位符的档名，跳过是安全的。
"""

import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

SPEC = re.compile(r"%(?:(\d+)\$)?([dsf])")


def specs_of(text: str) -> list[tuple[int, str]]:
    """占位符 (位置, 类型) 序列；`%%` 不是占位符，正则天然不匹配。"""
    out = []
    for i, m in enumerate(SPEC.finditer(text), start=1):
        out.append((int(m.group(1)) if m.group(1) else i, m.group(2)))
    return sorted(out)


def load_strings(root: Path) -> dict[str, dict[str, str]]:
    """键 → {包名: 值}，包名取 values 目录名（`values` / `values-zh-rCN` …）。"""
    out: dict[str, dict[str, str]] = {}
    for f in sorted(root.glob("app/src/main/res/values*/strings.xml")):
        pkg = f.parent.name
        for s in ET.parse(f).getroot().iter("string"):
            out.setdefault(s.get("name"), {})[pkg] = "".join(s.itertext())
    return out


def match_paren(src: str, open_idx: int) -> int:
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


def split_args(s: str) -> list[str]:
    args, depth, cur, instr = [], 0, "", False
    i = 0
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


CALL = re.compile(r"(?:stringResource|UiText\.Res)\(\s*R\.string\.(\w+)")


def looks_like_string(a: str) -> bool:
    return a.startswith('"') or a.startswith("stringResource(") or ".text()" in a


def main() -> int:
    root = Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()
    strings = load_strings(root)
    if not strings:
        print("::error::没读到任何 strings.xml")
        return 1

    bad: list[str] = []

    # ① 各语言包的占位符序列一致
    for key, by_pkg in sorted(strings.items()):
        seqs = {pkg: tuple(specs_of(v)) for pkg, v in by_pkg.items()}
        distinct = {s for s in seqs.values()}
        if len(distinct) > 1:
            bad.append(f"{key}: 各语言包占位符不一致 { {p: list(s) for p, s in seqs.items()} }")

    # ②③ 调用实参
    checked = 0
    for f in sorted(root.glob("app/src/main/java/**/*.kt")):
        src = f.read_text(encoding="utf-8")
        for m in CALL.finditer(src):
            key = m.group(1)
            popen = src.index("(", m.start())
            pclose = match_paren(src, popen)
            if pclose < 0:
                continue
            args = split_args(src[popen + 1 : pclose])
            if args and re.fullmatch(r"R\.string\.\w+", args[0]):
                args = args[1:]
            # UiText.Res 的参数载体是 listOf(...)，展开成实参
            if len(args) == 1 and args[0].startswith("listOf("):
                inner = args[0][len("listOf(") : -1]
                args = split_args(inner)
            by_pkg = strings.get(key)
            if by_pkg is None:
                continue
            checked += 1
            sp = specs_of(by_pkg.get("values", ""))
            line = src[: m.start()].count("\n") + 1
            where = f"{f.relative_to(root)}:{line}  {key}"
            if sp and not args:
                bad.append(f"{where}\n    模板要 {len(sp)} 个参数 {[t for _, t in sp]}，调用没传 —— 界面会原样显示这些 %")
                continue
            if len(args) != len(sp):
                bad.append(
                    f"{where}\n    模板要 {len(sp)} 个参数 {[t for _, t in sp]}，调用传了 {len(args)} 个 {args}"
                )
                continue
            for (idx, kind), a in zip(sp, args):
                if kind == "d" and looks_like_string(a):
                    bad.append(
                        f"{where}\n    第 {idx} 位是 %{idx}$d，实参却是 {a[:60]}"
                        " —— String 给 %d 会在真机抛 IllegalFormatConversionException"
                    )

    if bad:
        print(f"::error::字符串格式参数错配 {len(bad)} 处")
        print("\n".join(bad))
        return 1
    print(f"  ✅ 字符串格式参数一致（核对 {checked} 处带参调用 · {len(strings)} 个键）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
