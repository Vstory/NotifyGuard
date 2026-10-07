"""训练 base 垃圾通知分类器并导出到 app 资源。

用法：
    cd training && python3 train.py
    python3 train.py --extra mine.csv --extra-weight 3    # 叠加自采样本

语料是 samples/ 下的真实通知导出（来源与标签噪声见 samples/SOURCE.md），全程离线。
"""

from __future__ import annotations

import argparse
import csv
import random
import sys
from array import array
from collections import Counter
from pathlib import Path

import numpy as np
from scipy.sparse import csr_matrix, vstack
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import classification_report, confusion_matrix
from sklearn.model_selection import train_test_split
from sklearn.preprocessing import normalize as l2_normalize

from export import write_model, write_parity
from features import BUCKETS, NGRAM_MAX, NGRAM_MIN, feature_buckets

ROOT = Path(__file__).resolve().parent
PROJECT = ROOT.parent
MODEL_OUT = PROJECT / "app/src/main/resources/model/model.bin"
PARITY_OUT = PROJECT / "app/src/test/resources/model/parity.json"

# 该 reason 前缀表示通知被上游设备的通知滤盒 AI 判为广告后消去
DEFAULT_AD_REASON = "被其他通知服务取消"

# 同一文本重复推送很常见（广告尤其），重复次数转样本权重；上限防止单条刷满权重
MAX_DUP_WEIGHT = 10

# 手写边界样本：parity 单测要能覆盖「空串 / 单字符 / 纯数字 / 全 x / 代理对」这些分叉高发点
PARITY_FIXED = [
    "",
    "A",
    " ",
    "12345",
    "xxxxx",
    "你好",
    "😀🎉 限时特惠！！！",
    "您的验证码是 483920，5 分钟内有效，请勿泄露。",
    "【XX商城】双11狂欢！全场1折起，点击 http://t.cn/abc 领取888元红包，回复TD退订",
    "妈妈说周末回家吃饭",
    "会议改到下午三点，地点不变",
    "恭喜您被抽中为幸运用户，加微信 abc123 领取 iPhone 15 Pro Max",
    "低息贷款  无抵押　当天放款 详询 138-0000-0000",
    "Your package has been delivered to the front desk.",
    "Your Amazon order has shipped.",
    "ＦＵＬＬ－ＷＩＤＴＨ　１２３",
    "\u3000\u00a0\u2028\u200b",
    "İstanbul 促销",
    "This is a very long line without any digits or spaces just to check the hash path stays stable across implementations.",
    "<b>加粗</b> &amp; 实体",
    "验证码 verification code OTP",
    "限时抢购仅剩 3 分钟",
    "1",
    "x",
]


def read_rows(path: Path) -> list[dict[str, str]]:
    raw = path.read_bytes()
    for enc in ("utf-8-sig", "utf-8", "gb18030"):
        try:
            text = raw.decode(enc)
            break
        except UnicodeDecodeError:
            continue
    else:
        raise SystemExit(f"无法识别文件编码：{path}")
    delimiter = "\t" if text[:4096].count("\t") > text[:4096].count(",") else ","
    return list(csv.DictReader(text.splitlines(), delimiter=delimiter))


def load_csv(paths: list[Path], ad_reason: str) -> tuple[list[str], np.ndarray, list[float], Counter]:
    """真实通知 CSV（`title,content,reason`）→ (文本, 标签, 权重, 统计)。

    标签由消去理由推导而非人工标注，理由来源与噪声见 samples/SOURCE.md。
    标题与正文拼接成一条文本 —— 上游踩过的坑：只取标题会丢掉正文特征，而通知的广告性
    往往全在正文里（标题常只是 App 名）。
    """
    texts: list[str] = []
    labels: list[int] = []
    seen: dict[str, list] = {}  # 文本 -> [标签, 重复次数, 是否标签冲突]
    stats = Counter()
    for path in paths:
        rows = read_rows(path)
        stats["files"] += 1
        stats["rows"] += len(rows)
        for row in rows:
            text = "\n".join(
                t.strip() for t in ((row.get("title") or ""), (row.get("content") or "")) if t.strip()
            )
            if not text:
                stats["emptyText"] += 1
                continue
            reason = (row.get("reason") or "").strip()
            label = 1 if reason.startswith(ad_reason) else 0
            if text in seen:
                stats["dup"] += 1
                seen[text][1] += 1
                if label == 1 and seen[text][0] == 0:
                    # 同一文本两种判定时以广告为准：漏拦的代价高于误拦
                    seen[text][0] = 1
                    seen[text][2] = 1
                    stats["dupLabelConflict"] += 1
                continue
            seen[text] = [label, 1, 0]
            texts.append(text)

    labels = [seen[t][0] for t in texts]
    weights = [float(min(seen[t][1], MAX_DUP_WEIGHT)) for t in texts]
    stats["kept"] = len(texts)
    stats["pos"] = sum(labels)
    stats["neg"] = len(labels) - stats["pos"]
    return texts, np.asarray(labels, dtype=np.int8), weights, stats


def vectorise(texts: list[str]) -> csr_matrix:
    # array 而非 list：Python list of int 的内存开销在万级样本上就会拖慢
    indptr = array("i", [0])
    indices = array("i")
    values = array("f")
    for text in texts:
        for k, c in feature_buckets(text).items():
            indices.append(k)
            values.append(c)
        indptr.append(len(indices))
    x = csr_matrix(
        (
            np.frombuffer(values, dtype=np.float32),
            np.frombuffer(indices, dtype=np.int32),
            np.frombuffer(indptr, dtype=np.int32),
        ),
        shape=(len(texts), BUCKETS),
    )
    return l2_normalize(x, norm="l2", copy=False)


def report(name: str, labels: np.ndarray, proba: np.ndarray) -> None:
    for thr in (0.5, 0.7, 0.8, 0.9):
        pred = (proba >= thr).astype(int)
        print(f"\n== [{name}] threshold {thr} ==", file=sys.stderr)
        print(confusion_matrix(labels, pred), file=sys.stderr)
        print(classification_report(labels, pred, digits=4, zero_division=0), file=sys.stderr)


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--samples", default=str(ROOT / "samples"), help="语料目录或单个 CSV（默认 samples/）")
    ap.add_argument("--ad-reason", default=DEFAULT_AD_REASON, help="代表「被判为广告」的消去理由前缀")
    ap.add_argument("--extra", action="append", default=[], help="额外 CSV（同列格式），可重复")
    ap.add_argument("--extra-weight", type=float, default=1.0, help="额外样本的权重倍数")
    ap.add_argument("--C", type=float, default=1.0, help="LogisticRegression 正则强度的倒数")
    ap.add_argument("--val-ratio", type=float, default=0.2, help="留出集比例")
    ap.add_argument("--seed", type=int, default=20261007)
    args = ap.parse_args()

    sample_path = Path(args.samples)
    paths = sorted(sample_path.glob("*.csv")) if sample_path.is_dir() else [sample_path]
    if not paths:
        raise SystemExit(f"语料目录下没有 CSV：{sample_path}")

    texts, y, weights, stats = load_csv(paths, args.ad_reason)
    print(
        f"语料 文件={stats['files']} 行={stats['rows']} 保留={stats['kept']} "
        f"广告={stats['pos']} 正常={stats['neg']} 重复={stats['dup']}"
        f"（标签冲突 {stats['dupLabelConflict']}）空文本={stats['emptyText']}",
        file=sys.stderr,
    )
    if len(set(y.tolist())) < 2:
        sys.exit("语料里只剩一类样本，无法训练")

    x = vectorise(texts)

    idx_tr, idx_te = train_test_split(
        np.arange(len(texts)), test_size=args.val_ratio, random_state=args.seed, stratify=y
    )
    x_tr, y_tr = x[idx_tr], y[idx_tr]
    w_tr = np.asarray([weights[i] for i in idx_tr], dtype=np.float32)

    if args.extra:
        ex_texts, ex_y, ex_w, ex_stats = load_csv([Path(p) for p in args.extra], args.ad_reason)
        print(
            f"额外样本 保留={ex_stats['kept']} 广告={ex_stats['pos']} 正常={ex_stats['neg']}",
            file=sys.stderr,
        )
        x_tr = vstack([x_tr, vectorise(ex_texts)]).tocsr()
        y_tr = np.concatenate([y_tr, ex_y])
        w_tr = np.concatenate([w_tr, np.asarray(ex_w, dtype=np.float32) * args.extra_weight])

    print(f"训练 样本={x_tr.shape[0]} 广告={int((y_tr == 1).sum())} 正常={int((y_tr == 0).sum())}", file=sys.stderr)
    # balanced 不是可随手换掉的旋钮：不加时分数整体被压向 0，正样本分数中位数只有 0.60、
    # @0.8 召回 0.41（5 折 CV），阈值调不动；加上后中位数 0.85、召回 0.54。
    # 召回优先于精确率：误拦用户一眼看得见、点一下就能纠正，漏拦没人会发现。
    clf = LogisticRegression(C=args.C, solver="liblinear", max_iter=1000, class_weight="balanced")
    clf.fit(x_tr, y_tr, sample_weight=w_tr)

    # 阈值必须在留出集上标定：语料换成真实通知后分数分布与短信域完全不同
    report("holdout", y[idx_te], clf.predict_proba(x[idx_te])[:, 1])

    weights_out = clf.coef_.reshape(-1).astype(np.float32)
    bias = float(clf.intercept_[0])
    deq, scale = write_model(MODEL_OUT, weights_out, bias, BUCKETS, NGRAM_MIN, NGRAM_MAX)

    # parity 里掺真实样本：只看手写边界样本的话，双端分叉在真实分布上可能测不出来
    rng = random.Random(args.seed)
    pos = [texts[i] for i in np.flatnonzero(y == 1)]
    neg = [texts[i] for i in np.flatnonzero(y == 0)]
    sampled = [
        t
        for pair in zip(
            rng.sample(pos, min(15, len(pos))),
            rng.sample(neg, min(15, len(neg))),
        )
        for t in pair
    ]
    write_parity(PARITY_OUT, PARITY_FIXED + sampled, deq, bias)

    zq = x[idx_te] @ deq + bias
    drift = float(np.max(np.abs(1 / (1 + np.exp(-zq)) - clf.predict_proba(x[idx_te])[:, 1])))
    print(
        f"\nwrote {MODEL_OUT} ({MODEL_OUT.stat().st_size} bytes) scale={scale:.6g} "
        f"量化带来的最大 |Δp|={drift:.4f}",
        file=sys.stderr,
    )
    print(f"wrote {PARITY_OUT}（{len(PARITY_FIXED) + len(sampled)} 条）", file=sys.stderr)


if __name__ == "__main__":
    main()
