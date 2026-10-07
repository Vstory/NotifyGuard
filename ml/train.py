"""训练 base 垃圾通知分类器并导出到 app 资源。

用法：
    cd ml && uv run train.py                    # 首次运行会下载公开语料
    uv run train.py --limit 100000 --sms-ham-only

手机上（aarch64 PRoot）跑要注意内存：向量化用 array 累积，但 80 万条仍可能吃数百 MB，
用 --limit 控规模。产物必须与 Kotlin 侧一致，见 README「一致性」一节。
"""
from __future__ import annotations

import argparse
import csv
import random
import sys
import time
import urllib.request
from array import array
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

# 公开语料：中文带标签短信（约 80 万条 label\tcontent，1 = 骚扰）+ 英文 SMS Spam Collection
ZH_URL = (
    "https://raw.githubusercontent.com/hrwhisper/SpamMessage/master/data/"
    "%E5%B8%A6%E6%A0%87%E7%AD%BE%E7%9F%AD%E4%BF%A1.txt"
)
EN_URL = (
    "https://raw.githubusercontent.com/mohitgupta-omg/"
    "Kaggle-SMS-Spam-Collection-Dataset-/master/spam.csv"
)

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


def download(data_dir: Path) -> tuple[Path, Path]:
    targets = [(ZH_URL, data_dir / "labeled_sms.txt"), (EN_URL, data_dir / "sms_en.csv")]
    for url, path in targets:
        if path.exists():
            continue
        path.parent.mkdir(parents=True, exist_ok=True)
        print(f"downloading {url} -> {path}", file=sys.stderr)
        urllib.request.urlretrieve(url, path)
    return targets[0][1], targets[1][1]


def load_corpus(zh_path: Path, en_path: Path) -> tuple[list[str], np.ndarray]:
    texts: list[str] = []
    labels: list[int] = []
    with open(zh_path, encoding="utf-8", errors="replace") as f:
        for line in f:
            line = line.rstrip("\n")
            if "\t" not in line:
                continue
            label, text = line.split("\t", 1)
            if label not in ("0", "1"):
                continue
            texts.append(text)
            labels.append(int(label))
    zh = len(texts)
    with open(en_path, encoding="latin-1", newline="") as f:
        for row in csv.reader(f):
            if len(row) < 2 or row[0] not in ("ham", "spam"):
                continue
            texts.append(row[1])
            labels.append(1 if row[0] == "spam" else 0)
    print(f"loaded zh={zh} en={len(texts) - zh}", file=sys.stderr)
    return texts, np.asarray(labels, dtype=np.int8)


def load_extra(path: Path) -> tuple[list[str], np.ndarray]:
    """本机自采 CSV（列：判定 / 通知标题 / 通知信息）：垃圾广告、骚扰 → 1，正常 → 0。

    短信语料和 App 通知分布差得远，真机效果要以自采样本为准；该文件只在本机，不进仓库。
    """
    texts: list[str] = []
    labels: list[int] = []
    seen: set[str] = set()
    with open(path, encoding="utf-8-sig", newline="") as f:
        for row in csv.DictReader(f):
            verdict = (row.get("判定") or "").strip()
            if verdict == "正常":
                y = 0
            elif verdict in ("垃圾广告", "骚扰"):
                y = 1
            else:
                continue
            text = "\n".join(
                t for t in ((row.get("通知标题") or "").strip(), (row.get("通知信息") or "").strip()) if t
            )
            if not text or text in seen:
                continue
            seen.add(text)
            texts.append(text)
            labels.append(y)
    return texts, np.asarray(labels, dtype=np.int8)


def vectorise(texts: list[str]) -> csr_matrix:
    # array 而非 list：百万行级下 Python list of int 的内存开销会翻好几倍
    indptr = array("i", [0])
    indices = array("i")
    values = array("f")
    t0 = time.time()
    for i, t in enumerate(texts):
        for k, c in feature_buckets(t).items():
            indices.append(k)
            values.append(c)
        indptr.append(len(indices))
        if i and i % 50000 == 0:
            print(f"  vectorised {i} ({time.time() - t0:.0f}s)", file=sys.stderr)
    x = csr_matrix(
        (np.frombuffer(values, dtype=np.float32), np.frombuffer(indices, dtype=np.int32), np.frombuffer(indptr, dtype=np.int32)),
        shape=(len(texts), BUCKETS),
    )
    return l2_normalize(x, norm="l2", copy=False)


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--data", default=str(ROOT / "data"), help="语料目录（进 .gitignore）")
    ap.add_argument("--C", type=float, default=1.0, help="LogisticRegression 正则强度")
    ap.add_argument("--limit", type=int, default=0, help="只用前 N 条短信语料（0 = 全部；手机建议 100000）")
    ap.add_argument("--extra", action="append", default=[], help="自采真实通知 CSV，可重复")
    ap.add_argument("--extra-weight", type=float, default=10.0, help="自采样本的样本权重")
    ap.add_argument("--sms-ham-only", action="store_true", help="短信语料只留正常样本当负样本（避免先验偏向骚扰）")
    args = ap.parse_args()

    zh_path, en_path = download(Path(args.data))
    texts, y = load_corpus(zh_path, en_path)
    if args.sms_ham_only:
        keep = [i for i, v in enumerate(y) if v == 0]
        texts, y = [texts[i] for i in keep], y[keep]
    if args.limit:
        texts, y = texts[: args.limit], y[: args.limit]
    print(f"corpus rows={len(texts)} spam={int(y.sum())} ham={int((y == 0).sum())}", file=sys.stderr)
    if len(set(y.tolist())) < 2:
        sys.exit("语料里只剩一类样本，无法训练（检查 --sms-ham-only / --limit 的组合）")

    x = vectorise(texts)
    x_tr, x_te, y_tr, y_te = train_test_split(x, y, test_size=0.2, random_state=42, stratify=y)
    w_tr = np.ones(x_tr.shape[0], dtype=np.float32)

    extra_holdout = None
    if args.extra:
        ex_texts: list[str] = []
        ex_list: list[np.ndarray] = []
        for path in args.extra:
            t, yy = load_extra(Path(path))
            ex_texts += t
            ex_list.append(yy)
        ex_y = np.concatenate(ex_list)
        print(f"extra rows={len(ex_texts)} spam={int(ex_y.sum())} ham={int((ex_y == 0).sum())}", file=sys.stderr)
        ex_x = vectorise(ex_texts)
        ex_x_tr, ex_x_te, ex_y_tr, ex_y_te = train_test_split(
            ex_x, ex_y, test_size=0.2, random_state=42, stratify=ex_y
        )
        extra_holdout = (ex_x_te, ex_y_te)
        x_tr = vstack([x_tr, ex_x_tr]).tocsr()
        y_tr = np.concatenate([y_tr, ex_y_tr])
        w_tr = np.concatenate([w_tr, np.full(ex_x_tr.shape[0], args.extra_weight, dtype=np.float32)])
    print(f"train rows={x_tr.shape[0]}", file=sys.stderr)

    clf = LogisticRegression(C=args.C, solver="liblinear", max_iter=1000)
    clf.fit(x_tr, y_tr, sample_weight=w_tr)

    def report(name: str, xx, yy) -> None:
        pp = clf.predict_proba(xx)[:, 1]
        for thr in (0.5, 0.7, 0.8, 0.9):
            print(f"\n== [{name}] threshold {thr} ==", file=sys.stderr)
            print(confusion_matrix(yy, (pp >= thr).astype(int)), file=sys.stderr)
            print(classification_report(yy, (pp >= thr).astype(int), digits=4), file=sys.stderr)

    report("sms holdout", x_te, y_te)
    if extra_holdout is not None:
        # 真实通知留出集才是实际效果的判据，短信留出集的指标不能替代
        report("real notifications holdout", *extra_holdout)

    weights = clf.coef_.reshape(-1).astype(np.float32)
    bias = float(clf.intercept_[0])
    deq, scale = write_model(MODEL_OUT, weights, bias, BUCKETS, NGRAM_MIN, NGRAM_MAX)

    rng = random.Random(20261007)
    sampled = rng.sample(texts, min(30, len(texts)))
    write_parity(PARITY_OUT, PARITY_FIXED + sampled, deq, bias)

    zq = x_te @ deq + bias
    drift = float(np.max(np.abs(1 / (1 + np.exp(-zq)) - clf.predict_proba(x_te)[:, 1])))
    print(
        f"\nwrote {MODEL_OUT} ({MODEL_OUT.stat().st_size} bytes) scale={scale:.6g} "
        f"量化带来的最大 |Δp|={drift:.4f}",
        file=sys.stderr,
    )
    print(f"wrote {PARITY_OUT}（{len(PARITY_FIXED) + len(sampled)} 条）", file=sys.stderr)


if __name__ == "__main__":
    main()
