#!/usr/bin/env bash
# 从上游拉取语料覆盖 samples/，并回写 SOURCE.md 里的 commit 与行数。
# 默认跟上游默认分支 HEAD；给一个 commit 就钉住那个版本（可复现构建用）。
set -euo pipefail

REPO="ytdttj/NotificationCleaner"
REPO_URL="https://github.com/${REPO}.git"
UPSTREAM_DIR="training/samples"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEST="$HERE/samples"
REF="${1:-HEAD}"

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

# blob:none + sparse：上游整个仓库不小，只要语料那两个 CSV
git clone --quiet --filter=blob:none --sparse "$REPO_URL" "$TMP/upstream"
git -C "$TMP/upstream" sparse-checkout set "$UPSTREAM_DIR" >/dev/null
if [ "$REF" != "HEAD" ]; then
  git -C "$TMP/upstream" fetch --quiet --depth 1 origin "$REF"
  git -C "$TMP/upstream" checkout --quiet FETCH_HEAD
fi

COMMIT="$(git -C "$TMP/upstream" rev-parse HEAD)"
DATE="$(git -C "$TMP/upstream" log -1 --format=%cs)"

mkdir -p "$DEST"
cp "$TMP/upstream/$UPSTREAM_DIR"/*.csv "$DEST/"

rows() { python3 -c "import csv,sys;print(sum(1 for _ in csv.DictReader(open(sys.argv[1],encoding='utf-8-sig',newline=''))))" "$1"; }

echo "上游 commit $COMMIT ($DATE)"
for f in "$DEST"/*.csv; do
  echo "  $(basename "$f"): $(rows "$f") 行"
done

python3 - "$HERE/samples/SOURCE.md" "$COMMIT" "$DATE" "$DEST" <<'PY'
import re, sys, csv
from pathlib import Path

source_md, commit, date, dest = sys.argv[1], sys.argv[2], sys.argv[3], Path(sys.argv[4])
text = Path(source_md).read_text(encoding="utf-8")
text = re.sub(r"\| 上游 commit \|.*\|", f"| 上游 commit | `{commit}`（{date}） |", text)

rows = {p.name: sum(1 for _ in csv.DictReader(p.open(encoding="utf-8-sig", newline=""))) for p in dest.glob("*.csv")}
for name, n in rows.items():
    text = re.sub(rf"(\| `{re.escape(name)}` \|[^|]*\| )\d+( \|)", rf"\g<1>{n}\g<2>", text)
Path(source_md).write_text(text, encoding="utf-8")
print("已回写 SOURCE.md")
PY

echo
echo "语料变了就要重跑训练：cd $HERE && python3 train.py"
