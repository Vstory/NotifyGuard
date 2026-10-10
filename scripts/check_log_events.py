#!/usr/bin/env python3
"""框架日志格式门禁：事件名 / 键名 / 语言 / 级别。

规则（与 app/src/main/java/io/github/vstory/notifyguard/core/ModuleLogger.kt 的 KDoc 同源，改一处要同改另一处）：

  L1 事件名必须是 `<域>.<事件>`，域取白名单，事件小写下划线
  L2 字段必须是 `键=值`，键为英文小写下划线；动态拼接（含 `$`）与 `err=` 除外
  L3 传给日志的字符串字面量里不许出现中文 —— 中文只允许来自 `ModuleLogger.err(t)` 的异常原文
  L4 不许再用 `debugRaw`、`[DBG]`（级别已表达这件事）
  L5 级别只允许 D/I/E（禁 WARN，见知识库《项目检查流程》①）

退码 0 = 通过。CI 的 Static check 档与本地自查跑的是同一个脚本。
"""
import re
import sys
from pathlib import Path

DOMAINS = {
    "boot", "hotreload", "assemble", "slot", "guard", "safemode", "systemui", "generation",
    "teardown", "context", "judge", "ai", "record", "label", "status", "config", "channel",
    "model", "delta", "app",
}
CALL = re.compile(r"\b(ModuleLogger|AppLogger)\.(info|error|debug|lines)\s*\(")
EVENT = re.compile(r"^[a-z][a-z0-9_]*(?:\.[a-z][a-z0-9_]*)+$")
FIELD = re.compile(r"^[a-z][a-z0-9_]*=")
HAN = re.compile(r"[\u3400-\u9fff\uf900-\ufaff]")


def args_of(text, open_paren):
    """取一对括号内的顶层参数（跨行、跳过字符串里的括号与逗号）。"""
    depth, i, cur, out, in_str = 1, open_paren + 1, "", [], False
    while i < len(text) and depth:
        c = text[i]
        if in_str:
            if c == "\\":
                cur += text[i:i + 2]
                i += 1
            elif c == '"':
                in_str = False
                cur += c
            else:
                cur += c
        elif c == '"':
            in_str = True
            cur += c
        elif c in "([{":
            depth += 1
            cur += c
        elif c in ")]}":
            depth -= 1
            if depth:
                cur += c
        elif c == "," and depth == 1:
            out.append(cur.strip())
            cur = ""
        else:
            cur += c
        i += 1
    if cur.strip():
        out.append(cur.strip())
    return out


def literal(arg):
    m = re.match(r'^"((?:[^"\\]|\\.)*)"$', arg)
    return m.group(1) if m else None


def main(root):
    problems = []
    files = sorted(Path(root).rglob("*.kt"))
    for path in files:
        src = path.read_text(encoding="utf-8")
        rel = path.relative_to(root)
        for m in CALL.finditer(src):
            tag, call = m.group(1), m.group(2)
            args = args_of(src, m.end() - 1)
            if not args:
                problems.append(f"{rel}: {tag}.{call}() 没有参数")
                continue
            event = literal(args[0])
            if event is None:
                if call != "lines":
                    problems.append(f"{rel}: {tag}.{call}() 的事件名不是字面量：{args[0][:40]}")
            elif call == "lines":
                # 例外：明细行由 InstallReport 拼好整行（格式在那边的 head() 里保证），调用点传的是变量
                if not EVENT.match(event):
                    problems.append(f"{rel}: 明细行未按 `<域>.<事件> …` 起头：{event[:50]}")
            else:
                if not EVENT.match(event):
                    problems.append(f"{rel}: 事件名不合 `<域>.<事件>`：{event}")
                elif event.split(".")[0] not in DOMAINS:
                    problems.append(f"{rel}: 域不在白名单（{event.split('.')[0]}）：{event}")
            for a in args[1:] if event is not None or call == "lines" else args:
                if HAN.search(a):
                    problems.append(f"{rel}: 日志字面量里有中文（中文只能来自 err= 的异常原文）：{a[:50]}")
                lit = literal(a)
                if lit is not None and lit != "err" and not FIELD.match(lit) and not EVENT.match(lit):
                    problems.append(f"{rel}: 字段不合 `键=值`：{lit[:50]}")
    for path in files:
        src = path.read_text(encoding="utf-8")
        rel = path.relative_to(root)
        for pat, why in (("debugRaw", "L4：debugRaw 已改名 debug"),
                         ("[DBG]", "L4：不再用 [DBG] 前缀（级别已表达）"),
                         ("Log.WARN", "L5：禁 WARN"),
                         ("logWarn", "L5：禁 WARN")):
            if pat in src:
                problems.append(f"{rel}: 命中 {pat} —— {why}")
    for p in problems:
        print(p)
    print(f"{'❌' if problems else '✅'} 日志格式：{len(files)} 个源文件，{len(problems)} 处不合规")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1] if len(sys.argv) > 1 else "."))
