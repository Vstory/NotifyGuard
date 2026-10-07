"""与 app/src/main/java/io/github/vstory/notifyguard/judge/SpamFeatures.kt 逐位一致。

改动本文件任何一个字符都必须同步改 Kotlin 侧，并重跑 `uv run train.py` 重生成 parity.json，
否则 `SpamModelParityTest` 会红——那是两端一致性的唯一可执行证据。
"""
from __future__ import annotations

import unicodedata

BUCKETS = 1 << 18
NGRAM_MIN = 1
NGRAM_MAX = 3

# 与 SpamFeatures.isSpace 保持同步（不能用 str.isspace()：判法不同）
SPACE_CHARS = frozenset(
    "\t\n\x0b\x0c\r\x1c\x1d\x1e\x1f \x85\xa0\u1680"
    "\u2000\u2001\u2002\u2003\u2004\u2005\u2006\u2007\u2008\u2009\u200a"
    "\u2028\u2029\u202f\u205f\u3000"
)

FNV_OFFSET = 0x811C9DC5
FNV_PRIME = 0x01000193


def normalize(text: str) -> str:
    """小写 → 去空白/字母 x/十进制数字。

    去空白、数字、x 是因为语料本身的加工痕迹（正常样本去过空格、垃圾样本把数字与人名
    掩码成连续 x），留着它们模型会学到「有空格=正常」「有 xxx=垃圾」而不是词本身。
    """
    out = []
    for ch in text.lower():
        if ch in SPACE_CHARS or ch == "x" or unicodedata.category(ch) == "Nd":
            continue
        out.append(ch)
    return "".join(out)


def code_units(text: str) -> bytes:
    """UTF-16-LE 字节序列：每个码元两字节（低位在前），保留代理项。

    必须按码元而非码点切 gram —— Kotlin 的 String 就是 UTF-16 码元序列，
    增补平面字符（emoji 等）在两侧的切法必须一致。
    """
    return text.encode("utf-16-le", "surrogatepass")


def fnv1a32(data: bytes) -> int:
    h = FNV_OFFSET
    for b in data:
        h ^= b
        h = (h * FNV_PRIME) & 0xFFFFFFFF
    return h


def feature_buckets(
    text: str,
    ngram_min: int = NGRAM_MIN,
    ngram_max: int = NGRAM_MAX,
    buckets: int = BUCKETS,
) -> dict[int, int]:
    units = code_units(normalize(text))
    n_units = len(units) // 2
    mask = buckets - 1
    counts: dict[int, int] = {}
    for n in range(ngram_min, ngram_max + 1):
        for start in range(0, n_units - n + 1):
            k = fnv1a32(units[start * 2 : (start + n) * 2]) & mask
            counts[k] = counts.get(k, 0) + 1
    return counts
