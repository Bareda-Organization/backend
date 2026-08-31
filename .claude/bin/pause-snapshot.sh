#!/bin/bash
# 병렬 좌석 작업을 중단할 때 재개에 필요한 상태를 한 번에 찍는다.
# 사용법: .claude/bin/pause-snapshot.sh <워크트리루트> <분기점해시>
# 예:    .claude/bin/pause-snapshot.sh /Users/mskim/Desktop/PJ/wt-sb-p9 df22250
set -u
WT="${1:?워크트리 루트를 인자로 줘라}"
BASE="${2:?분기점 커밋 해시를 인자로 줘라}"
OUT="$WT/PAUSE-SNAPSHOT.md"

{
  echo "# 중단 시점 상태 — $(date '+%Y-%m-%d %H:%M')"
  echo
  echo "분기점 \`$BASE\` · 워크트리 루트 \`$WT\`"
  echo
  echo "| 좌석 | HEAD | 분기점 이후 커밋 | 미커밋 | 보고서 |"
  echo "|:-:|---|:-:|:-:|:-:|"
  for d in "$WT"/*/; do
    [ -d "$d/.git" ] || [ -f "$d/.git" ] || continue
    n=$(basename "$d")
    head=$(git -C "$d" log --oneline -1 2>/dev/null | cut -c1-7)
    cnt=$(git -C "$d" log --oneline "$BASE"..HEAD 2>/dev/null | wc -l | tr -d ' ')
    dirty=$(git -C "$d" status --porcelain 2>/dev/null | wc -l | tr -d ' ')
    rep=$(ls "$d"report-*.md 2>/dev/null | wc -l | tr -d ' ')
    echo "| $n | \`$head\` | $cnt | $dirty | $rep |"
  done
  echo
  # 미커밋이 있는 좌석은 그것이 자기 작업분인지 심은 결함인지 가를 재료를 남긴다.
  # 추가만 있고 삭제가 없으면 대개 정상 작업분이고, 삭제·주석처리가 섞이면 심은 변형을 의심한다.
  for d in "$WT"/*/; do
    n=$(basename "$d")
    [ "$(git -C "$d" status --porcelain 2>/dev/null | wc -l | tr -d ' ')" = "0" ] && continue
    echo "## $n 미커밋 내역 — 자기 작업분인지 심은 변형인지 가를 것"
    echo '```'
    git -C "$d" status --porcelain 2>/dev/null
    echo '---'
    git -C "$d" diff --stat 2>/dev/null
    echo '```'
    echo
  done
  echo "## 커밋 목록"
  for d in "$WT"/*/; do
    n=$(basename "$d")
    log=$(git -C "$d" log --oneline "$BASE"..HEAD 2>/dev/null)
    [ -z "$log" ] && continue
    echo "**$n**"; echo '```'; echo "$log"; echo '```'
  done
} > "$OUT"

cat "$OUT"
echo
echo ">>> 저장 위치: $OUT"
