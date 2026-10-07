#!/usr/bin/env bash
# ci-refresh-page.sh — CI 滚动发布页刷新器（NotifyGuard 项目副本）
# TEMPLATE_VERSION=1.0.0    # 模板版本号（模板内容变更时 bump；勿删）
# SCRIPT_VERSION=1.0.0      # 项目副本版本（本副本自有的改动在此 +1）
#
# 作用：把固定 tag 的 Release 正文重建为「最近 N 组构建」的表格，并删除被淘汰的旧资产。
#       与语言无关：任何「push 即出包、产物追加进同一页」的项目都能用。
#
# 用法（workflow 的刷新步骤里调用；gh 已认证或本地导出 GH_TOKEN）：
#   REPO=OWNER/REPO CI_TAG=ci PREFIX=MyApp KEEP_GROUPS=6 \
#     APKNAME_DEBUG=MyApp_1.0.0.1_20260101-1200_ci-debug_ab12cd34.apk \
#     ./refresh_ci_page.sh
#
# 输入（环境变量，全部有默认值，除 REPO）：
#   REPO          必需，OWNER/REPO
#   CI_TAG        滚动页 tag，默认 ci
#   PREFIX        产物名前缀，仅用于脚注文案
#   KEEP_GROUPS   保留组数，默认 6
#   COLUMNS       列集合与顺序，逗号分隔，默认 time,version,commit,variant,size,download
#                 可用列：time version commit variant size download
#   APKNAME_DEBUG / APKNAME_RELEASE   本次产物名（用于 ★ 标记；缺省则不标）
#   RUN_ID / SERVER  可选，脚注里「★ = 本次构建」的 Actions run 链接
#   FOOTER_EXTRA  可选，追加到脚注的 markdown 行（多行字符串）
#   CAUTION       可选，覆盖正文顶部提示块；显式传空串则不写提示块
#   WAIT_VISIBLE  默认 1；先轮询到本次产物在 API 可见再刷新（避免本轮缺自己那组）
#   BODY_FILE     正文落盘位置，默认 /tmp/ci_page_body.md
#   NAME_RE       资产名正则，默认 ^[A-Za-z0-9._-]+_[0-9]{8}-[0-9]{4}_ci-(debug|release)_[0-9a-f]{8}\.apk$
#   VARIANT_RE    变体段正则，默认 ^ci-(debug|release)$
#
# 约定（改本脚本前先读 工作流程/子流程/通用规范流程/GitHub域/CI滚动发布页流程.md）：
#   · 正文**全量重建**，不在旧正文上追加 —— 重建的输入是页面上真实存在的资产列表，
#     并发交错下每次产出都是「当时可见的完整状态」，最终收敛；追加式会丢对方的行。
#   · 组 = 时间戳|短号（不含变体）⇒ 同一次多变体算一组、页面占一行。
#   · 保留 = 按组去重后取前 KEEP_GROUPS 组，其余资产删除。重复删同一资产返回 404 属并发正常。
#   · 解析一律 awk -F'\t'，**不要**改回 `IFS=$'\t' read`：tab 属 IFS 空白字符，read 会合并
#     连续分隔符并剥离首尾 ⇒ 空字段被整格吞掉、后面字段全体左移，页面静默显示错值。
set -euo pipefail

: "${REPO:?需要 REPO=OWNER/REPO}"
CI_TAG="${CI_TAG:-ci}"
PREFIX="${PREFIX:-}"
KEEP_GROUPS="${KEEP_GROUPS:-6}"
COLUMNS="${COLUMNS:-time,version,commit,variant,size,download}"
WAIT_VISIBLE="${WAIT_VISIBLE:-1}"
BODY="${BODY_FILE:-/tmp/ci_page_body.md}"
# ⚠️ 默认值不能写成 `${NAME_RE:-^...{8}...}`：正则里的区间量词 `{8}` 的 `}` 会**提前闭合**
#   参数展开，默认值被截断成 `^...+_[0-9]{8-...`（静默变味、匹配全落空）。含 `{}` 的默认值
#   一律走 if 分支赋单引号字面量。
if [ -z "${NAME_RE:-}" ]; then
  NAME_RE='^[A-Za-z0-9._-]+_[0-9]{8}-[0-9]{4}_ci-(debug|release)_[0-9a-f]{8}\.apk$'
fi
if [ -z "${VARIANT_RE:-}" ]; then
  VARIANT_RE='^ci-(debug|release)$'
fi

HDR=(-H "Accept: application/vnd.github+json")
REL_API="repos/${REPO}/releases/tags/${CI_TAG}"
BASE="https://github.com/${REPO}/releases/download/${CI_TAG}"

# 1) 等本次产物在 API 可见：上传后查询有可见性延迟，轮询比固定 sleep 既快又不猜。
if [ "$WAIT_VISIBLE" = 1 ] && [ -n "${APKNAME_DEBUG:-}${APKNAME_RELEASE:-}" ]; then
  ok=0
  for _ in $(seq 1 30); do
    names="$(gh api --paginate "${HDR[@]}" "$REL_API" --jq '.assets[].name' 2>/dev/null || true)"
    hit=1
    for n in ${APKNAME_DEBUG:-} ${APKNAME_RELEASE:-}; do
      grep -qxF "$n" <<< "$names" || { hit=0; break; }
    done
    [ "$hit" = 1 ] && { ok=1; break; }
    sleep 3
  done
  [ "$ok" = 1 ] || echo "::warning::本次产物在快照中仍不可见，本轮正文可能暂缺本组（下次运行补齐）"
fi

# 2) 一次快照（名字 / id / 大小）→ 过滤命名规则 → 按组键倒序
SNAP="$(mktemp)"; SORTED="$(mktemp)"; KEEPKEYS="$(mktemp)"
trap 'rm -f "$SNAP" "$SORTED" "$KEEPKEYS"' EXIT

gh api --paginate "${HDR[@]}" "$REL_API" \
  --jq '.assets[] | [.name, (.id | tostring), (.size | tostring)] | @tsv' \
| awk -F'\t' -v name_re="$NAME_RE" -v var_re="$VARIANT_RE" '
    { name=$1; id=$2; size=$3
      if (name !~ name_re) next
      # 按 `_` **从右往左**取段 ⇒ 版本名里含 `_` 也不会错位
      n=split(name, P, "_")
      if (n < 5) next
      short=P[n]; var=P[n-1]; ts=P[n-2]; vc=P[n-3]
      if (var !~ var_re) next
      if (ts !~ /^[0-9]{8}-[0-9]{4}$/) next
      if (short !~ /^[0-9a-f]{8}\.apk$/) next
      sub(/^ci-/, "", var); sub(/\.apk$/, "", short)
      # 末列 = 组内序（debug 先）：同一组的两个变体共用一个组键，只按组键排会让 release
      #   排到 debug 前面，与脚注「排障优先用 debug」相反。
      printf "%s|%s\t%s\t%s\t%s\t%s\t%s\t%d\n", ts, short, name, id, vc, var, size, (var == "debug" ? 0 : 1)
    }' > "$SNAP"
echo "匹配命名规则的 asset 共 $(wc -l < "$SNAP") 个"
sort -t$'\t' -k1,1r -k7,7n "$SNAP" > "$SORTED"

awk -F'\t' -v k="$KEEP_GROUPS" '!seen[$1]++ { if (c++ >= k) exit; print $1 }' "$SORTED" > "$KEEPKEYS"
echo "保留 $(wc -l < "$KEEPKEYS") 组 / 上限 ${KEEP_GROUPS} 组"

# 3) 重建正文：表头与数据行共用同一份快照 ⇒「表格里列出的组」与「实际保留的组」必然一致
{
  if [ "${CAUTION+set}" = set ]; then
    [ -n "$CAUTION" ] && printf '%s\n' "$CAUTION"
  else
    echo "> [!CAUTION]"
    echo "> **本页面为自动构建的测试版**：push 到任意分支即出包，仅供测试，**请勿用于重要场景**。"
    echo "> 需要正式版，请到 [Releases](https://github.com/${REPO}/releases) 找对应的版本页。"
  fi
  echo
  echo "## 📥 最近构建（北京时间，新 → 旧）"
  echo
} > "$BODY"

awk -F'\t' -v base="$BASE" -v cols="$COLUMNS" \
    -v w1="${APKNAME_DEBUG:-}" -v w2="${APKNAME_RELEASE:-}" '
  BEGIN {
    n = split(cols, C, ",")
    H["time"]="时间"; H["version"]="版本"; H["commit"]="提交"
    H["variant"]="变体"; H["size"]="大小"; H["download"]="下载"
    hdr="|"; sep="|"
    for (i = 1; i <= n; i++) { hdr = hdr " " (C[i] in H ? H[C[i]] : C[i]) " |"; sep = sep "---|" }
    print hdr; print sep
  }
  function cell(c) {
    if (c == "time")     return pretty
    if (c == "version")  return ver "(" code ")"
    if (c == "commit")   return short
    if (c == "variant")  return var
    if (c == "size")     return sprintf("%.1f MB", size / 1048576)
    if (c == "download") return "[下载](" base "/" name ")"
    return c
  }
  NR == FNR { keep[$1] = 1; next }
  {
    key=$1; name=$2; id=$3; vc=$4; var=$5; size=$6
    if (!(key in keep)) next
    ts=key; short=key
    sub(/\|.*$/, "", ts)          # 组键 = 时间戳|短号
    sub(/^.*\|/, "", short)
    pretty=substr(ts,1,4)"-"substr(ts,5,2)"-"substr(ts,7,2)" "substr(ts,10,2)":"substr(ts,12,2)
    ver=vc;  sub(/\.[^.]*$/, "", ver)
    code=vc; sub(/^.*\./, "", code)
    # ★ 标记「本次构建」所在行，固定在**第一列**前缀（换列序也不跑偏）
    mark = (name == w1 || name == w2) ? "★ " : ""
    line="|"
    for (i = 1; i <= n; i++) {
      v = cell(C[i])
      line = line " " ((i == 1) ? mark : "") v " |"
    }
    printf "%s\n", line
  }' "$KEEPKEYS" "$SORTED" >> "$BODY"

{
  echo
  echo "---"
  if [ -n "${RUN_ID:-}" ] && [ -n "${SERVER:-}" ]; then
    echo "★ = 本次构建（[${RUN_ID}](${SERVER}/${REPO}/actions/runs/${RUN_ID})）"
  else
    echo "★ = 本次构建"
  fi
  echo
  echo -n "文件名规则 = \`<前缀>_<版本>.<code>_<时间>_ci-<变体>_<短号>.apk\`"
  [ -n "$PREFIX" ] && echo -n "（前缀 = ${PREFIX}）"
  echo -n "，短号 = 提交 SHA 前 8 位"
  case ",$COLUMNS," in *,commit,*) echo -n "（即表格「提交」列）" ;; esac
  echo "。按时间戳追加、不覆盖旧包，本页只保留最近 ${KEEP_GROUPS} 组。"
  [ -n "${FOOTER_EXTRA:-}" ] && printf '%s\n' "$FOOTER_EXTRA"
  echo
  echo "本页地址**永久固定**（可收藏）—— GitHub 的 Release 列表按**创建时间**排列且无置顶机制，新版本发布后本页会下移。"
  echo "下方 **Assets 列表由 GitHub 按上传时间排列、无法调整** —— 请以上方表格为准取最新包。"
} >> "$BODY"

gh release edit "$CI_TAG" --notes-file "$BODY" >/dev/null
echo "=== 页面正文（$(wc -l < "$BODY") 行）==="
cat "$BODY"

# 4) 清理：非保留组一律删除（id 单字段读，不涉 IFS 陷阱）
deleted=0; failed=0
while read -r id; do
  [ -n "$id" ] || continue
  if gh api -X DELETE "${HDR[@]}" "repos/${REPO}/releases/assets/${id}" >/dev/null 2>&1; then
    deleted=$((deleted + 1))
  else
    failed=$((failed + 1))
  fi
done < <(awk -F'\t' 'NR==FNR { keep[$1]=1; next } !($1 in keep) { print $3 }' "$KEEPKEYS" "$SORTED")

echo "已删除 $deleted 个旧 asset"
[ "$failed" -eq 0 ] || echo "::notice::$failed 个删除失败（并发下被另一次运行抢先删除会返回 404，属正常，不影响收敛）"
